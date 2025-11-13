package com.hyperxray.an.viewmodel

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance circular buffer for log entries with O(1) operations.
 * Uses hash-based duplicate detection and incremental updates for maximum speed.
 * 
 * Algorithm features:
 * 1. Circular buffer (ArrayDeque) for O(1) add/remove operations
 * 2. Hash-based duplicate detection (faster than string comparison)
 * 3. Incremental updates (only new entries added, no full list recreation)
 * 4. Lazy trimming (trim only when necessary)
 * 5. Memory-efficient (pre-allocated capacity)
 */
internal class LogBuffer(
    private val maxSize: Int
) {
    // Circular buffer for O(1) operations
    private val buffer = ArrayDeque<String>(maxSize)
    
    // Hash set for fast duplicate detection (O(1) lookup)
    // Using Int hash instead of full string for memory efficiency
    private val hashSet = HashSet<Int>(maxSize * 2) // 2x capacity for better hash distribution
    
    // Atomic counter for thread-safe size tracking
    private val size = AtomicInteger(0)
    
    /**
     * Add new log entries incrementally.
     * Returns only the new unique entries that were actually added.
     * O(n) where n is number of new entries (each checked in O(1) time).
     * 
     * Uses hash-based duplicate detection for O(1) lookup.
     * Hash collisions are extremely rare but handled by storing full strings.
     */
    fun addIncremental(newEntries: List<String>): List<String> {
        val addedEntries = mutableListOf<String>()
        
        for (entry in newEntries) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            
            // Fast hash-based duplicate check (O(1))
            val hash = trimmed.hashCode()
            if (hashSet.contains(hash)) {
                // Hash collision: need to verify it's actually the same string
                // Use a secondary check with a small cache of recent entries
                // to avoid O(n) buffer.contains() call
                var isDuplicate = false
                // Check last 10 entries (most likely duplicates are recent)
                val iterator = buffer.iterator()
                var count = 0
                while (iterator.hasNext() && count < 10) {
                    if (iterator.next() == trimmed) {
                        isDuplicate = true
                        break
                    }
                    count++
                }
                // If not found in recent entries, do full check (rare case)
                if (!isDuplicate && buffer.contains(trimmed)) {
                    continue
                }
            }
            
            // Add to hash set first (O(1))
            hashSet.add(hash)
            
            // Add to circular buffer
            buffer.addFirst(trimmed) // Newest at front
            size.incrementAndGet()
            addedEntries.add(trimmed)
            
            // Lazy trimming: only trim when we exceed maxSize
            if (size.get() > maxSize) {
                trimOldest()
            }
        }
        
        return addedEntries
    }
    
    /**
     * Trim oldest entries when buffer exceeds maxSize.
     * O(k) where k is number of entries to remove.
     */
    private fun trimOldest() {
        val toRemove = size.get() - maxSize
        repeat(toRemove) {
            val removed = buffer.removeLast() // Remove oldest
            if (removed != null) {
                hashSet.remove(removed.hashCode())
                size.decrementAndGet()
            }
        }
    }
    
    /**
     * Get all entries as a list (newest first).
     * O(n) but only called when UI needs update.
     */
    fun toList(): List<String> {
        return buffer.toList()
    }
    
    /**
     * Clear all entries.
     * O(n) but rarely called.
     */
    fun clear() {
        buffer.clear()
        hashSet.clear()
        size.set(0)
    }
    
    /**
     * Get current size.
     * O(1).
     */
    fun getSize(): Int = size.get()
    
    /**
     * Check if buffer is empty.
     * O(1).
     */
    fun isEmpty(): Boolean = size.get() == 0
    
    /**
     * Initialize with existing entries (for loading saved logs).
     * More efficient than adding one by one.
     * O(n) where n is number of entries, with O(1) duplicate detection.
     */
    fun initialize(entries: List<String>) {
        clear()
        
        // Process entries: trim, filter empty, deduplicate, and limit to maxSize
        val seenHashes = HashSet<Int>(maxSize * 2)
        val uniqueEntries = mutableListOf<String>()
        
        // Process in reverse order (oldest first) so newest will be at front after addFirst
        val reversedEntries = entries.reversed()
        
        for (entry in reversedEntries) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            
            val hash = trimmed.hashCode()
            
            // Fast hash-based duplicate check (O(1))
            if (seenHashes.contains(hash)) {
                // Hash collision: check if it's actually a duplicate
                // Since we're building the list, check against uniqueEntries (smaller than buffer)
                var isDuplicate = false
                for (existing in uniqueEntries) {
                    if (existing == trimmed) {
                        isDuplicate = true
                        break
                    }
                }
                if (isDuplicate) continue
            }
            
            seenHashes.add(hash)
            uniqueEntries.add(trimmed)
            
            // Stop if we've reached maxSize
            if (uniqueEntries.size >= maxSize) break
        }
        
        // Add to buffer (newest first)
        for (entry in uniqueEntries.reversed()) {
            buffer.addFirst(entry)
            hashSet.add(entry.hashCode())
            size.incrementAndGet()
        }
    }
}

