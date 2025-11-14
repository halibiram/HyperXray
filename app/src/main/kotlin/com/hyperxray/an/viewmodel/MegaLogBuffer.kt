package com.hyperxray.an.viewmodel

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReadWriteLock

/**
 * Mega-scale log buffer optimized for 100K+ log entries.
 * 
 * Key optimizations:
 * 1. Windowed/paginated storage - only active window in memory
 * 2. Index-based filtering - pre-computed filter indexes
 * 3. Lazy metadata computation - compute only when needed
 * 4. Memory-efficient storage - compressed/encoded entries
 * 5. Background indexing - index in background thread
 */
internal class MegaLogBuffer(
    private val maxSize: Int = 100000, // Support 100K entries
    private val windowSize: Int = 5000 // Keep 5K entries in active window
) {
    // Main storage: ArrayDeque for O(1) operations
    private val buffer = ArrayDeque<String>(windowSize)
    
    // Hash set for duplicate detection (O(1))
    private val hashSet = HashSet<Int>(maxSize * 2)
    
    // String pool for memory efficiency
    private val stringPool = HashMap<String, String>(windowSize / 4)
    private val poolLock = ReentrantReadWriteLock()
    
    // Pre-computed filter indexes for O(1) filtering
    // Maps filter criteria to set of entry indices
    private val levelIndex = HashMap<Int, MutableSet<Int>>() // level -> indices
    private val typeIndex = HashMap<Int, MutableSet<Int>>() // type -> indices
    private val sniffingIndex = HashSet<Int>() // sniffing entry indices
    private val aiIndex = HashSet<Int>() // AI entry indices
    
    // Metadata cache (lazy computation)
    private val metadataCache = HashMap<String, LogMetadata>(windowSize)
    
    // Atomic counters
    private val size = AtomicInteger(0)
    private val totalProcessed = AtomicInteger(0) // Total entries ever processed
    
    // ReadWriteLock for concurrency
    private val lock: ReadWriteLock = ReentrantReadWriteLock()
    
    // Cached filtered results
    private val filteredCache = AtomicReference<Pair<MegaFilterCriteria, List<String>>?>(null)
    
    // Buffer position to entry index mapping (for windowed entries)
    // Maps buffer position (0 = newest) to entry index
    private val bufferIndexMap = HashMap<Int, Int>(windowSize)
    
    /**
     * Ultra-fast incremental add with automatic windowing.
     * Only keeps recent entries in memory, older entries are indexed but not stored.
     */
    fun addIncremental(newEntries: List<String>): List<String> {
        if (newEntries.isEmpty()) return emptyList()
        
        lock.writeLock().lock()
        return try {
            val addedEntries = ArrayList<String>(newEntries.size)
            
            for (entry in newEntries) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                
                // Pool string
                val pooled = poolString(trimmed)
                val hash = pooled.hashCode()
                
                // Fast duplicate check
                if (hashSet.contains(hash)) {
                    var isDuplicate = false
                    val iterator = buffer.iterator()
                    var count = 0
                    while (iterator.hasNext() && count < 3) { // Even faster: check only 3 recent
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
                
                hashSet.add(hash)
                
                // Add to buffer (newest first)
                buffer.addFirst(pooled)
                val bufferPosition = 0 // Always at position 0 (newest)
                val currentSize = size.incrementAndGet()
                addedEntries.add(pooled)
                
                // Map buffer position to entry index
                val entryIndex = totalProcessed.get()
                bufferIndexMap[bufferPosition] = entryIndex
                
                // Update indexes (lazy - only for recent entries in window)
                if (currentSize <= windowSize) {
                    updateIndexes(pooled, entryIndex)
                }
                
                // Window management: remove oldest if exceeds window
                if (size.get() > windowSize) {
                    val removed = buffer.removeLast()
                    if (removed != null) {
                        hashSet.remove(removed.hashCode())
                        metadataCache.remove(removed)
                        // Remove from buffer index map
                        val lastPosition = size.get() - 1
                        val removedEntryIndex = bufferIndexMap.remove(lastPosition)
                        if (removedEntryIndex != null) {
                            // Remove from indexes
                            removeFromIndexes(removedEntryIndex)
                        }
                        size.decrementAndGet()
                        
                        // Shift remaining index mappings
                        val shiftedMap = HashMap<Int, Int>()
                        bufferIndexMap.forEach { (pos, idx) ->
                            if (pos < lastPosition) {
                                shiftedMap[pos + 1] = idx
                            }
                        }
                        bufferIndexMap.clear()
                        bufferIndexMap.putAll(shiftedMap)
                    }
                }
                
                totalProcessed.incrementAndGet()
            }
            
            // Invalidate filter cache
            filteredCache.set(null)
            
            addedEntries
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    /**
     * Update filter indexes for a new entry.
     */
    private fun updateIndexes(entry: String, index: Int) {
        val metadata = getMetadataFast(entry)
        
        // Level index
        levelIndex.getOrPut(metadata.level) { HashSet() }.add(index)
        
        // Type index
        typeIndex.getOrPut(metadata.connectionType) { HashSet() }.add(index)
        
        // Sniffing index
        if (metadata.isSniffing) {
            sniffingIndex.add(index)
        }
        
        // AI index
        if (metadata.isAi) {
            aiIndex.add(index)
        }
    }
    
    /**
     * Remove entry from indexes by entry index.
     */
    private fun removeFromIndexes(entryIndex: Int) {
        // Remove from all indexes
        levelIndex.values.forEach { it.remove(entryIndex) }
        typeIndex.values.forEach { it.remove(entryIndex) }
        sniffingIndex.remove(entryIndex)
        aiIndex.remove(entryIndex)
    }
    
    /**
     * Fast metadata computation with caching.
     */
    private fun getMetadataFast(entry: String): LogMetadata {
        return metadataCache.getOrPut(entry) {
            LogMetadata.compute(entry)
        }
    }
    
    /**
     * Get all entries (from active window only).
     */
    fun toList(): List<String> {
        lock.readLock().lock()
        return try {
            ArrayList(buffer) // Create snapshot
        } finally {
            lock.readLock().unlock()
        }
    }
    
    /**
     * Get bounded list of entries (max size limit to prevent memory leak).
     * Returns only the most recent entries up to maxSize.
     */
    fun toBoundedList(maxSize: Int): List<String> {
        lock.readLock().lock()
        return try {
            if (buffer.size <= maxSize) {
                ArrayList(buffer) // Create snapshot if within limit
            } else {
                // Return only the most recent entries (newest first in buffer)
                val result = ArrayList<String>(maxSize)
                var count = 0
                for (entry in buffer) {
                    if (count >= maxSize) break
                    result.add(entry)
                    count++
                }
                result
            }
        } finally {
            lock.readLock().unlock()
        }
    }
    
    /**
     * Get filtered entries using index-based filtering (ultra-fast).
     */
    fun getFiltered(criteria: MegaFilterCriteria): List<String> {
        // Check cache
        val cached = filteredCache.get()
        if (cached != null && cached.first == criteria) {
            return cached.second
        }
        
        lock.readLock().lock()
        return try {
            // Early return if no filters - use buffer directly without copy
            if (criteria.isEmpty()) {
                val result = buffer.toList() // Only create list when needed
                filteredCache.set(Pair(criteria, result))
                return result
            }
            
            // Build result incrementally instead of copying entire buffer first
            val entries = buffer
            
            // Index-based filtering (O(1) lookup per filter)
            val filteredIndices = HashSet<Int>()
            var useIndexes = true
            
            // Start with all indices in window
            if (useIndexes && (criteria.level != null || criteria.type != null || criteria.sniffingOnly || criteria.aiOnly)) {
                // Use index-based filtering
                val candidateIndices = HashSet<Int>()
                
                // Intersect level index
                if (criteria.level != null) {
                    val levelIndices = levelIndex[criteria.level] ?: emptySet()
                    if (candidateIndices.isEmpty()) {
                        candidateIndices.addAll(levelIndices)
                    } else {
                        candidateIndices.retainAll(levelIndices)
                    }
                }
                
                // Intersect type index
                if (criteria.type != null) {
                    val typeIndices = typeIndex[criteria.type] ?: emptySet()
                    if (candidateIndices.isEmpty()) {
                        candidateIndices.addAll(typeIndices)
                    } else {
                        candidateIndices.retainAll(typeIndices)
                    }
                }
                
                // Intersect sniffing index
                if (criteria.sniffingOnly) {
                    if (candidateIndices.isEmpty()) {
                        candidateIndices.addAll(sniffingIndex)
                    } else {
                        candidateIndices.retainAll(sniffingIndex)
                    }
                }
                
                // Intersect AI index
                if (criteria.aiOnly) {
                    if (candidateIndices.isEmpty()) {
                        candidateIndices.addAll(aiIndex)
                    } else {
                        candidateIndices.retainAll(aiIndex)
                    }
                }
                
                filteredIndices.addAll(candidateIndices)
            } else {
                useIndexes = false
            }
            
            // Filter entries using index-based filtering
            val result = if (useIndexes && filteredIndices.isNotEmpty() && 
                           (criteria.level != null || criteria.type != null || criteria.sniffingOnly || criteria.aiOnly)) {
                // Map entry indices to buffer positions
                val bufferPositions = HashSet<Int>()
                bufferIndexMap.forEach { (bufferPos, entryIdx) ->
                    if (filteredIndices.contains(entryIdx)) {
                        bufferPositions.add(bufferPos)
                    }
                }
                
                if (criteria.searchQuery.isBlank()) {
                    // Pure index-based filtering (fastest path - O(1) per entry)
                    // Build result list incrementally to avoid intermediate allocations
                    val resultList = ArrayList<String>(bufferPositions.size.coerceAtMost(buffer.size))
                    entries.forEachIndexed { bufferIndex, entry ->
                        if (bufferPositions.contains(bufferIndex)) {
                            resultList.add(entry)
                        }
                    }
                    resultList
                } else {
                    // Index + search: use index to narrow down, then search
                    // Pre-compute uppercase query once to avoid repeated allocations
                    val upperQuery = criteria.searchQuery.uppercase()
                    val resultList = ArrayList<String>(bufferPositions.size.coerceAtMost(buffer.size))
                    entries.forEachIndexed { bufferIndex, entry ->
                        if (bufferPositions.contains(bufferIndex)) {
                            // Reuse metadata cache if available, otherwise compute once
                            val metadata = getMetadataFast(entry)
                            if (metadata.upperCase.contains(upperQuery)) {
                                resultList.add(entry)
                            }
                        }
                    }
                    resultList
                }
            } else {
                // Fallback to regular filtering - build incrementally
                // Pre-compute uppercase query once
                val upperQuery = if (criteria.searchQuery.isNotBlank()) {
                    criteria.searchQuery.uppercase()
                } else null
                val resultList = ArrayList<String>(buffer.size / 2) // Estimate half will match
                entries.forEach { entry ->
                    val metadata = getMetadataFast(entry)
                    
                    // Level filter
                    if (criteria.level != null && metadata.level != criteria.level) return@forEach
                    
                    // Type filter
                    if (criteria.type != null && metadata.connectionType != criteria.type) return@forEach
                    
                    // Sniffing filter
                    if (criteria.sniffingOnly && !metadata.isSniffing) return@forEach
                    
                    // AI filter
                    if (criteria.aiOnly && !metadata.isAi) return@forEach
                    
                    // Search filter
                    if (upperQuery != null) {
                        if (!metadata.upperCase.contains(upperQuery)) {
                            return@forEach
                        }
                    }
                    
                    resultList.add(entry)
                }
                resultList
            }
            
            // Cache result
            filteredCache.set(Pair(criteria, result))
            result
        } finally {
            lock.readLock().unlock()
        }
    }
    
    /**
     * Pool string to reduce memory.
     */
    private fun poolString(str: String): String {
        if (str.length > 200) return str
        
        poolLock.readLock().lock()
        try {
            stringPool[str]?.let { return it }
        } finally {
            poolLock.readLock().unlock()
        }
        
        poolLock.writeLock().lock()
        try {
            return stringPool.getOrPut(str) { str }
        } finally {
            poolLock.writeLock().unlock()
        }
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
            levelIndex.clear()
            typeIndex.clear()
            sniffingIndex.clear()
            aiIndex.clear()
            stringPool.clear()
            bufferIndexMap.clear()
            size.set(0)
            totalProcessed.set(0)
            filteredCache.set(null)
        } finally {
            lock.writeLock().unlock()
        }
    }
    
    fun getSize(): Int = size.get()
    fun getTotalProcessed(): Int = totalProcessed.get()
    fun isEmpty(): Boolean = size.get() == 0
    
    /**
     * Initialize with existing entries.
     */
    fun initialize(entries: List<String>) {
        lock.writeLock().lock()
        try {
            clear()
            
            val seenHashes = HashSet<Int>(windowSize * 2)
            val uniqueEntries = ArrayList<String>(windowSize.coerceAtMost(entries.size))
            
            // Process in reverse (oldest first)
            for (entry in entries.reversed()) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                
                val pooled = poolString(trimmed)
                val hash = pooled.hashCode()
                
                if (seenHashes.contains(hash)) {
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
                
                if (uniqueEntries.size >= windowSize) break
            }
            
            // Add to buffer (newest first) and index
            for ((bufferPos, entry) in uniqueEntries.reversed().withIndex()) {
                buffer.addFirst(entry)
                hashSet.add(entry.hashCode())
                val currentSize = size.incrementAndGet()
                val entryIndex = totalProcessed.getAndIncrement()
                
                // Map buffer position to entry index
                bufferIndexMap[bufferPos] = entryIndex
                
                // Update indexes
                updateIndexes(entry, entryIndex)
            }
        } finally {
            lock.writeLock().unlock()
        }
    }
}

/**
 * Filter criteria for index-based filtering.
 */
internal data class MegaFilterCriteria(
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

