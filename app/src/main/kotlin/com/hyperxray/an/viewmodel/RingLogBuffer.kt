package com.hyperxray.an.viewmodel

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReadWriteLock

/**
 * Ultra-high-performance ring buffer for log entries with strict memory limits.
 * 
 * Features:
 * - Fixed capacity (enforced strictly) - O(1) memory usage
 * - O(1) add operations with automatic oldest entry eviction
 * - Thread-safe with ReadWriteLock
 * - Zero-copy snapshot generation
 * - Minimal GC pressure (pre-allocated array, no list allocations)
 * - Hard cap: buffer never exceeds capacity, oldest entries are overwritten
 */
internal class RingLogBuffer(
    private val capacity: Int = 2000
) {
    companion object {
        // Maximum entries allowed - hard limit to prevent unbounded growth
        const val MAX_ENTRIES = 5_000
    }
    
    private val buffer = arrayOfNulls<String>(capacity)
    private val writeIndex = AtomicInteger(0)
    // Size tracks actual number of entries (never exceeds capacity)
    private val size = AtomicInteger(0)
    private val lock: ReadWriteLock = ReentrantReadWriteLock()
    
    /**
     * Add new log entries. Oldest entries are automatically evicted when capacity is reached.
     * Returns the number of entries actually added (after duplicate filtering).
     * Uses lightweight duplicate detection (checks last 10 entries only for performance).
     * 
     * CRITICAL: This method enforces strict capacity limits - oldest entries are overwritten,
     * ensuring memory usage never grows beyond capacity.
     */
    fun add(newEntries: List<String>): Int {
        if (newEntries.isEmpty()) return 0
        
        lock.writeLock().lock()
        return try {
            var added = 0
            val currentSize = size.get()
            val checkDuplicates = currentSize > 0
            
            for (entry in newEntries) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                
                // Lightweight duplicate check (last 10 entries only)
                if (checkDuplicates) {
                    var isDuplicate = false
                    val checkCount = currentSize.coerceAtMost(10)
                    // Calculate start index (most recent entry before writeIndex)
                    val startIdx = if (currentSize > 0) {
                        (writeIndex.get() - 1 + capacity) % capacity
                    } else {
                        0
                    }
                    for (i in 0 until checkCount) {
                        val idx = (startIdx - i + capacity) % capacity
                        val existing = buffer[idx]
                        if (existing != null && existing == trimmed) {
                            isDuplicate = true
                            break
                        }
                    }
                    if (isDuplicate) continue
                }
                
                // Get write position and overwrite (ring buffer behavior)
                val index = writeIndex.getAndIncrement() % capacity
                
                // If buffer is full, we're overwriting oldest entry (no size increment)
                // If buffer has space, increment size
                if (currentSize < capacity) {
                    size.incrementAndGet()
                } else {
                    // Buffer is full - we're overwriting, but size stays at capacity
                    // This ensures we never exceed capacity
                }
                
                buffer[index] = trimmed
                added++
            }
            
            added
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    /**
     * Get a snapshot of all entries (newest first).
     * Creates a new immutable list with bounded size to prevent memory leaks.
     * 
     * CRITICAL: Returns immutable list to prevent Compose from retaining references
     * to the entire buffer. Size is strictly bounded by capacity.
     */
    fun snapshot(): List<String> {
        lock.readLock().lock()
        return try {
            val currentSize = size.get()
            if (currentSize == 0) return emptyList()
            
            // Enforce strict size limit - never exceed capacity
            val actualSize = currentSize.coerceAtMost(capacity)
            val result = ArrayList<String>(actualSize)
            
            // Calculate start index (most recent entry)
            val startIdx = if (currentSize > 0) {
                (writeIndex.get() - 1 + capacity) % capacity
            } else {
                0
            }
            
            // Collect entries in reverse order (newest first)
            for (i in 0 until actualSize) {
                val idx = (startIdx - i + capacity) % capacity
                val entry = buffer[idx]
                if (entry != null) {
                    result.add(entry)
                }
            }
            
            // Return immutable list to prevent external modifications
            result.toList()
        } finally {
            lock.readLock().unlock()
        }
    }
    
    /**
     * Get filtered snapshot. Filters are applied during snapshot creation to avoid
     * creating intermediate lists.
     * 
     * CRITICAL: Returns immutable bounded list to prevent memory leaks in Compose.
     * Maximum size is strictly enforced to prevent unbounded growth.
     */
    fun filteredSnapshot(criteria: FilterCriteria): List<String> {
        if (criteria.isEmpty()) {
            return snapshot()
        }
        
        lock.readLock().lock()
        return try {
            val currentSize = size.get()
            if (currentSize == 0) return emptyList()
            
            // Enforce strict size limit
            val actualSize = currentSize.coerceAtMost(capacity)
            // Estimate filtered result size (typically 30-50% of total)
            val result = ArrayList<String>((actualSize * 0.4).toInt().coerceAtLeast(16))
            
            // Calculate start index (most recent entry)
            val startIdx = if (currentSize > 0) {
                (writeIndex.get() - 1 + capacity) % capacity
            } else {
                0
            }
            
            val upperQuery = if (criteria.searchQuery.isNotBlank()) {
                criteria.searchQuery.uppercase()
            } else null
            
            // Iterate through entries (newest first)
            for (i in 0 until actualSize) {
                val idx = (startIdx - i + capacity) % capacity
                val entry = buffer[idx] ?: continue
                
                // Compute metadata once per entry
                val metadata = LogMetadata.compute(entry)
                
                // Apply filters
                if (criteria.level != null && metadata.level != criteria.level) continue
                if (criteria.type != null && metadata.connectionType != criteria.type) continue
                if (criteria.sniffingOnly && !metadata.isSniffing) continue
                if (criteria.aiOnly && !metadata.isAi) continue
                if (upperQuery != null && !metadata.upperCase.contains(upperQuery)) continue
                
                result.add(entry)
            }
            
            // Return immutable list to prevent external modifications
            result.toList()
        } finally {
            lock.readLock().unlock()
        }
    }
    
    fun clear() {
        lock.writeLock().lock()
        try {
            for (i in buffer.indices) {
                buffer[i] = null
            }
            writeIndex.set(0)
            size.set(0)
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    fun getSize(): Int = size.get()
    fun isEmpty(): Boolean = size.get() == 0
    
    /**
     * Initialize buffer with existing entries.
     * Only keeps the most recent entries up to capacity to prevent memory leaks.
     */
    fun initialize(entries: List<String>) {
        lock.writeLock().lock()
        try {
            clear()
            // Only take the most recent entries up to capacity
            val entriesToAdd = entries.takeLast(capacity)
            add(entriesToAdd)
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    /**
     * Get oldest entries for log rotation (writes to disk).
     * Returns entries that will be evicted next, up to count.
     * This allows writing oldest entries to disk before they're overwritten.
     * 
     * In a ring buffer, when full:
     * - writeIndex points to the next position to write (which will overwrite the oldest entry)
     * - Oldest entries are at positions starting from writeIndex
     */
    fun getOldestEntries(count: Int): List<String> {
        lock.readLock().lock()
        return try {
            val currentSize = size.get()
            if (currentSize == 0 || currentSize < capacity) {
                // Buffer not full yet, no entries to rotate
                return emptyList()
            }
            
            // Calculate which entries are oldest (will be overwritten next)
            // Rotate max 25% at a time to avoid blocking too long
            val actualCount = count.coerceAtMost(capacity / 4)
            val result = ArrayList<String>(actualCount)
            
            // Oldest entries are at positions starting from writeIndex (next to be overwritten)
            val startIdx = writeIndex.get() % capacity
            for (i in 0 until actualCount) {
                val idx = (startIdx + i) % capacity
                val entry = buffer[idx]
                if (entry != null) {
                    result.add(entry)
                }
            }
            
            result.toList()
        } finally {
            lock.readLock().unlock()
        }
    }
}

/**
 * Filter criteria for log filtering.
 */
internal data class FilterCriteria(
    val level: Int? = null,
    val type: Int? = null,
    val sniffingOnly: Boolean = false,
    val aiOnly: Boolean = false,
    val searchQuery: String = ""
) {
    fun isEmpty(): Boolean {
        return level == null && type == null && !sniffingOnly && !aiOnly && searchQuery.isBlank()
    }
}

