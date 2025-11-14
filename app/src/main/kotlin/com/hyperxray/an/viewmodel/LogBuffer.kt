package com.hyperxray.an.viewmodel

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance circular buffer for log entries with O(1) operations.
 * Uses Set-based duplicate detection with timestamp+message keys for reliable deduplication.
 * 
 * Algorithm features:
 * 1. Circular buffer (ArrayDeque) for O(1) add/remove operations
 * 2. Set-based duplicate detection using timestamp+message keys (O(1) lookup, no hash collisions)
 * 3. Incremental updates (only new entries added, no full list recreation)
 * 4. Lazy trimming (trim only when necessary)
 * 5. Memory-efficient (pre-allocated capacity)
 */
internal class LogBuffer(
    private val maxSize: Int
) {
    // Circular buffer for O(1) operations
    private val buffer = ArrayDeque<String>(maxSize)
    
    // Set-based duplicate detection using timestamp+message keys
    // Using ConcurrentHashMap-backed Set for thread-safety and O(1) operations
    private val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>(maxSize * 2))
    
    // Atomic counter for thread-safe size tracking
    private val size = AtomicInteger(0)
    
    /**
     * Extracts timestamp from log entry string.
     * Returns a Pair of (timestamp, message) similar to parseLogEntry but without UI dependencies.
     */
    private fun extractTimestamp(entry: String): Pair<String, String> {
        var timestampEndIndex = 0
        while (timestampEndIndex < entry.length) {
            val c = entry[timestampEndIndex]
            if (Character.isDigit(c) || c == '/' || c == ' ' || c == ':' || c == '.' || c == '-') {
                timestampEndIndex++
            } else {
                break
            }
        }
        
        if (timestampEndIndex > 0) {
            val potentialTimestamp = entry.substring(0, timestampEndIndex).trim()
            // Check if it looks like a timestamp (contains date and time separators)
            if (potentialTimestamp.contains("/") && potentialTimestamp.contains(":") ||
                potentialTimestamp.contains("-") && potentialTimestamp.contains(":")) {
                val message = entry.substring(timestampEndIndex).trim()
                return Pair(potentialTimestamp, message)
            }
        }
        
        // No timestamp found, return empty timestamp and full message
        return Pair("", entry)
    }
    
    /**
     * Creates a unique key for duplicate detection: timestamp_message
     */
    private fun createKey(entry: String): String {
        val (timestamp, message) = extractTimestamp(entry)
        return "${timestamp}_${message}"
    }
    
    /**
     * Add new log entries incrementally.
     * Returns only the new unique entries that were actually added.
     * O(n) where n is number of new entries (each checked in O(1) time).
     * 
     * Uses Set-based duplicate detection with timestamp+message keys for reliable O(1) lookup.
     */
    fun addIncremental(newEntries: List<String>): List<String> {
        val addedEntries = mutableListOf<String>()
        
        for (entry in newEntries) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            
            // Create unique key for duplicate detection
            val key = createKey(trimmed)
            
            // Set.add() returns true if the element was added (not a duplicate)
            if (seen.add(key)) {
                // Add to circular buffer
                buffer.addFirst(trimmed) // Newest at front
                size.incrementAndGet()
                addedEntries.add(trimmed)
                
                // Lazy trimming: only trim when we exceed maxSize
                if (size.get() > maxSize) {
                    trimOldest()
                }
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
                val key = createKey(removed)
                seen.remove(key)
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
        seen.clear()
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
        val tempSeen = HashSet<String>(maxSize * 2)
        val uniqueEntries = mutableListOf<String>()
        
        // Process in reverse order (oldest first) so newest will be at front after addFirst
        val reversedEntries = entries.reversed()
        
        for (entry in reversedEntries) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            
            // Create unique key for duplicate detection
            val key = createKey(trimmed)
            
            // Set-based duplicate check (O(1))
            if (tempSeen.add(key)) {
                uniqueEntries.add(trimmed)
                
                // Stop if we've reached maxSize
                if (uniqueEntries.size >= maxSize) break
            }
        }
        
        // Add to buffer (newest first) and seen set
        for (entry in uniqueEntries.reversed()) {
            buffer.addFirst(entry)
            val key = createKey(entry)
            seen.add(key)
            size.incrementAndGet()
        }
    }
}

