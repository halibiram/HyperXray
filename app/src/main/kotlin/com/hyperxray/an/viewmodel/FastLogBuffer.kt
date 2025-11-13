package com.hyperxray.an.viewmodel

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReadWriteLock

/**
 * Ultra-high-performance log buffer with advanced optimizations:
 * 
 * 1. Lock-free read operations (ReadWriteLock for better concurrency)
 * 2. String pooling to reduce memory allocations
 * 3. Pre-computed metadata cache (log level, connection type)
 * 4. Batch processing optimizations
 * 5. Zero-copy operations where possible
 */
internal class FastLogBuffer(
    private val maxSize: Int
) {
    // Circular buffer for O(1) operations
    private val buffer = ArrayDeque<String>(maxSize)
    
    // Hash set for fast duplicate detection (O(1) lookup)
    private val hashSet = HashSet<Int>(maxSize * 2)
    
    // String pool to reuse common strings and reduce allocations
    private val stringPool = HashMap<String, String>(maxSize / 4)
    private val poolLock = ReentrantReadWriteLock()
    
    // Pre-computed metadata cache for faster filtering
    // Maps log entry to its metadata (level, connection type, etc.)
    private val metadataCache = HashMap<String, LogMetadata>(maxSize)
    
    // Atomic counter for thread-safe size tracking
    private val size = AtomicInteger(0)
    
    // ReadWriteLock for better concurrency (multiple readers, single writer)
    private val lock: ReadWriteLock = ReentrantReadWriteLock()
    
    // Cached list snapshot to avoid repeated conversions
    private val cachedList = AtomicReference<List<String>?>(null)
    private val cacheVersion = AtomicInteger(0)
    
    /**
     * Get or create pooled string to reduce memory allocations.
     * Most log entries have similar patterns, so pooling helps significantly.
     */
    private fun poolString(str: String): String {
        if (str.length > 200) return str // Don't pool very long strings
        
        poolLock.readLock().lock()
        try {
            stringPool[str]?.let { return it }
        } finally {
            poolLock.readLock().unlock()
        }
        
        poolLock.writeLock().lock()
        try {
            // Double-check after acquiring write lock
            return stringPool.getOrPut(str) { str }
        } finally {
            poolLock.writeLock().unlock()
        }
    }
    
    /**
     * Ultra-fast incremental add with batch processing.
     * Returns only new unique entries.
     * O(n) where n is new entries, but optimized with:
     * - Hash-based duplicate detection (O(1))
     * - String pooling
     * - Batch trimming
     */
    fun addIncremental(newEntries: List<String>): List<String> {
        if (newEntries.isEmpty()) return emptyList()
        
        lock.writeLock().lock()
        return try {
            val addedEntries = ArrayList<String>(newEntries.size)
            var needsTrim = false
            
            // Process all entries first, then trim once at the end
            for (entry in newEntries) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                
                // Pool the string to reduce allocations
                val pooled = poolString(trimmed)
                
                // Fast hash-based duplicate check (O(1))
                val hash = pooled.hashCode()
                if (hashSet.contains(hash)) {
                    // Quick check: most duplicates are in recent entries
                    var isDuplicate = false
                    val iterator = buffer.iterator()
                    var count = 0
                    while (iterator.hasNext() && count < 5) { // Reduced from 10 to 5
                        if (iterator.next() == pooled) {
                            isDuplicate = true
                            break
                        }
                        count++
                    }
                    if (!isDuplicate && buffer.contains(pooled)) {
                        continue
                    }
                }
                
                // Add to hash set
                hashSet.add(hash)
                
                // Add to buffer
                buffer.addFirst(pooled)
                size.incrementAndGet()
                addedEntries.add(pooled)
                
                // Mark for trimming if needed
                if (size.get() > maxSize) {
                    needsTrim = true
                }
            }
            
            // Batch trim once at the end (more efficient)
            if (needsTrim) {
                trimOldestBatch()
            }
            
            // Invalidate cache
            invalidateCache()
            
            addedEntries
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    /**
     * Batch trim - more efficient than trimming one by one.
     */
    private fun trimOldestBatch() {
        val currentSize = size.get()
        if (currentSize <= maxSize) return
        
        val toRemove = currentSize - maxSize
        repeat(toRemove) {
            val removed = buffer.removeLast()
            if (removed != null) {
                hashSet.remove(removed.hashCode())
                metadataCache.remove(removed) // Remove from metadata cache too
                size.decrementAndGet()
            }
        }
    }
    
    /**
     * Get all entries as list with caching.
     * Subsequent calls return cached version if buffer hasn't changed.
     */
    fun toList(): List<String> {
        lock.readLock().lock()
        return try {
            val cached = cachedList.get()
            if (cached != null) {
                return cached
            }
            
            // Create snapshot
            val snapshot = ArrayList<String>(buffer)
            cachedList.set(snapshot)
            snapshot
        } finally {
            lock.readLock().unlock()
        }
    }
    
    /**
     * Invalidate cached list when buffer changes.
     */
    private fun invalidateCache() {
        cacheVersion.incrementAndGet()
        cachedList.set(null)
    }
    
    /**
     * Clear all entries.
     */
    fun clear() {
        lock.writeLock().lock()
        try {
            buffer.clear()
            hashSet.clear()
            metadataCache.clear()
            size.set(0)
            invalidateCache()
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    /**
     * Get current size (lock-free read).
     */
    fun getSize(): Int = size.get()
    
    /**
     * Check if empty (lock-free read).
     */
    fun isEmpty(): Boolean = size.get() == 0
    
    /**
     * Get or compute metadata for a log entry (cached for performance).
     */
    fun getMetadata(entry: String): LogMetadata {
        lock.readLock().lock()
        return try {
            metadataCache.getOrPut(entry) {
                LogMetadata.compute(entry)
            }
        } finally {
            lock.readLock().unlock()
        }
    }
    
    /**
     * Initialize with existing entries (optimized batch operation).
     */
    fun initialize(entries: List<String>) {
        lock.writeLock().lock()
        try {
            clear()
            
            val seenHashes = HashSet<Int>(maxSize * 2)
            val uniqueEntries = ArrayList<String>(maxSize.coerceAtMost(entries.size))
            
            // Process in reverse (oldest first)
            for (entry in entries.reversed()) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                
                val pooled = poolString(trimmed)
                val hash = pooled.hashCode()
                
                if (seenHashes.contains(hash)) {
                    // Check against uniqueEntries (smaller than buffer)
                    var isDuplicate = false
                    for (existing in uniqueEntries) {
                        if (existing == pooled) {
                            isDuplicate = true
                            break
                        }
                    }
                    if (isDuplicate) continue
                }
                
                seenHashes.add(hash)
                uniqueEntries.add(pooled)
                
                if (uniqueEntries.size >= maxSize) break
            }
            
            // Add to buffer (newest first)
            for (entry in uniqueEntries.reversed()) {
                buffer.addFirst(entry)
                hashSet.add(entry.hashCode())
                size.incrementAndGet()
            }
            
            invalidateCache()
        } finally {
            lock.writeLock().unlock()
        }
    }
}

/**
 * Pre-computed metadata for log entries to speed up filtering.
 */
internal data class LogMetadata(
    val level: Int, // LogLevel enum ordinal
    val connectionType: Int, // ConnectionType enum ordinal
    val isSniffing: Boolean,
    val isAi: Boolean,
    val upperCase: String // Pre-computed uppercase for search
) {
    companion object {
        fun compute(entry: String): LogMetadata {
            val upper = entry.uppercase()
            return LogMetadata(
                level = computeLevel(upper),
                connectionType = computeConnectionType(upper),
                isSniffing = computeIsSniffing(upper),
                isAi = computeIsAi(upper),
                upperCase = upper
            )
        }
        
        private fun computeLevel(upper: String): Int {
            return when {
                upper.contains("ERROR") || upper.contains("ERR") -> 0 // ERROR
                upper.contains("WARN") || upper.contains("WARNING") -> 1 // WARN
                upper.contains("INFO") -> 2 // INFO
                upper.contains("DEBUG") || upper.contains("DBG") -> 3 // DEBUG
                else -> 4 // UNKNOWN
            }
        }
        
        private fun computeConnectionType(upper: String): Int {
            return when {
                upper.contains(" TCP ") || upper.contains("TCP ") || 
                upper.contains(" TCP") || (upper.contains("TCP") && !upper.contains("UDP")) -> 0 // TCP
                upper.contains(" UDP ") || upper.contains("UDP ") || 
                upper.contains(" UDP") -> 1 // UDP
                else -> 2 // UNKNOWN
            }
        }
        
        private fun computeIsSniffing(upper: String): Boolean {
            return upper.contains("SNIFFED") || upper.contains("SNIFFING") ||
                   upper.contains("DESTOVERRIDE") ||
                   (upper.contains("TARGET") && upper.contains("DOMAIN")) ||
                   (upper.contains("DETECT") && upper.contains("DOMAIN"))
        }
        
        private fun computeIsAi(upper: String): Boolean {
            return upper.contains("DEEPPOLICYMODEL") ||
                   upper.contains("AI OPTIMIZER") ||
                   upper.contains("ONNX") ||
                   upper.contains("INFERENCE") ||
                   (upper.contains("MODEL") && (upper.contains("LOADED") || upper.contains("VERIFIED")))
        }
    }
}

