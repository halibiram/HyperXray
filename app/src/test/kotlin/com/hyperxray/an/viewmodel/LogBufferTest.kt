package com.hyperxray.an.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for LogBuffer duplicate detection and performance.
 */
class LogBufferTest {
    
    private lateinit var logBuffer: LogBuffer
    
    @Before
    fun setUp() {
        logBuffer = LogBuffer(maxSize = 1000)
    }
    
    @Test
    fun `test duplicate entries in sequence are not inserted`() {
        val entry = "2024/01/01 12:00:00 Test log message"
        
        val added1 = logBuffer.addIncremental(listOf(entry))
        assertEquals(1, added1.size)
        assertEquals(entry, added1[0])
        
        // Add same entry again immediately
        val added2 = logBuffer.addIncremental(listOf(entry))
        assertEquals(0, added2.size) // Should be rejected as duplicate
        
        assertEquals(1, logBuffer.getSize())
    }
    
    @Test
    fun `test duplicate entries separated by others are not inserted`() {
        val entry1 = "2024/01/01 12:00:00 First log message"
        val entry2 = "2024/01/01 12:00:01 Second log message"
        val entry3 = "2024/01/01 12:00:02 Third log message"
        
        // Add entries
        logBuffer.addIncremental(listOf(entry1, entry2, entry3))
        assertEquals(3, logBuffer.getSize())
        
        // Add entry1 again (duplicate)
        val added = logBuffer.addIncremental(listOf(entry1))
        assertEquals(0, added.size) // Should be rejected
        
        assertEquals(3, logBuffer.getSize())
    }
    
    @Test
    fun `test 10000 log flood performance`() {
        val entries = (1..10000).map { 
            "2024/01/01 12:00:${String.format("%02d", it % 60)} Log entry number $it"
        }
        
        val startTime = System.currentTimeMillis()
        val added = logBuffer.addIncremental(entries)
        val endTime = System.currentTimeMillis()
        
        // All entries should be added (they're all unique)
        assertEquals(10000, added.size)
        assertEquals(1000, logBuffer.getSize()) // Should be capped at maxSize
        
        // Performance check: should complete in reasonable time (< 1 second)
        val duration = endTime - startTime
        assertTrue("Processing 10000 entries took too long: ${duration}ms", duration < 1000)
    }
    
    @Test
    fun `test hash collision scenarios with different messages same timestamp`() {
        // Different messages with same timestamp should all be added
        val entry1 = "2024/01/01 12:00:00 First message"
        val entry2 = "2024/01/01 12:00:00 Second message"
        val entry3 = "2024/01/01 12:00:00 Third message"
        
        val added = logBuffer.addIncremental(listOf(entry1, entry2, entry3))
        assertEquals(3, added.size)
        assertEquals(3, logBuffer.getSize())
    }
    
    @Test
    fun `test hash collision scenarios with same message different timestamps`() {
        // Same message with different timestamps should all be added
        val entry1 = "2024/01/01 12:00:00 Same message"
        val entry2 = "2024/01/01 12:00:01 Same message"
        val entry3 = "2024/01/01 12:00:02 Same message"
        
        val added = logBuffer.addIncremental(listOf(entry1, entry2, entry3))
        assertEquals(3, added.size)
        assertEquals(3, logBuffer.getSize())
    }
    
    @Test
    fun `test entries without timestamp are handled correctly`() {
        val entry1 = "No timestamp log message"
        val entry2 = "Another no timestamp message"
        
        val added = logBuffer.addIncremental(listOf(entry1, entry2))
        assertEquals(2, added.size)
        assertEquals(2, logBuffer.getSize())
        
        // Adding same entry again should be rejected
        val added2 = logBuffer.addIncremental(listOf(entry1))
        assertEquals(0, added2.size)
    }
    
    @Test
    fun `test empty entries are filtered out`() {
        val entries = listOf(
            "2024/01/01 12:00:00 Valid entry",
            "",
            "   ",
            "2024/01/01 12:00:01 Another valid entry"
        )
        
        val added = logBuffer.addIncremental(entries)
        assertEquals(2, added.size)
        assertEquals(2, logBuffer.getSize())
    }
    
    @Test
    fun `test trimming when maxSize is exceeded`() {
        val smallBuffer = LogBuffer(maxSize = 5)
        
        val entries = (1..10).map {
            "2024/01/01 12:00:${String.format("%02d", it)} Entry $it"
        }
        
        val added = smallBuffer.addIncremental(entries)
        assertEquals(10, added.size) // All unique entries added
        assertEquals(5, smallBuffer.getSize()) // But buffer size is capped at 5
    }
    
    @Test
    fun `test initialize with duplicates`() {
        val entries = listOf(
            "2024/01/01 12:00:00 First entry",
            "2024/01/01 12:00:01 Second entry",
            "2024/01/01 12:00:00 First entry", // Duplicate
            "2024/01/01 12:00:02 Third entry"
        )
        
        logBuffer.initialize(entries)
        
        // Should only have 3 unique entries
        assertEquals(3, logBuffer.getSize())
        
        val snapshot = logBuffer.toList()
        assertTrue(snapshot.contains("2024/01/01 12:00:00 First entry"))
        assertTrue(snapshot.contains("2024/01/01 12:00:01 Second entry"))
        assertTrue(snapshot.contains("2024/01/01 12:00:02 Third entry"))
    }
    
    @Test
    fun `test clear removes all entries`() {
        logBuffer.addIncremental(listOf(
            "2024/01/01 12:00:00 Entry 1",
            "2024/01/01 12:00:01 Entry 2"
        ))
        
        assertEquals(2, logBuffer.getSize())
        
        logBuffer.clear()
        
        assertEquals(0, logBuffer.getSize())
        assertTrue(logBuffer.isEmpty())
        
        // After clear, should be able to add entries again
        val added = logBuffer.addIncremental(listOf("2024/01/01 12:00:02 Entry 3"))
        assertEquals(1, added.size)
        assertEquals(1, logBuffer.getSize())
    }
    
    @Test
    fun `test toList returns entries in newest first order`() {
        val entries = listOf(
            "2024/01/01 12:00:00 Oldest",
            "2024/01/01 12:00:01 Middle",
            "2024/01/01 12:00:02 Newest"
        )
        
        logBuffer.addIncremental(entries)
        
        val snapshot = logBuffer.toList()
        assertEquals(3, snapshot.size)
        // Newest should be first (reverseLayout = true in UI, but buffer stores newest first)
        assertEquals("2024/01/01 12:00:02 Newest", snapshot[0])
        assertEquals("2024/01/01 12:00:01 Middle", snapshot[1])
        assertEquals("2024/01/01 12:00:00 Oldest", snapshot[2])
    }
    
    @Test
    fun `test mixed timestamp formats`() {
        val entries = listOf(
            "2024/01/01 12:00:00 Slash format",
            "2024-01-01 12:00:01 Dash format",
            "No timestamp format",
            "2024/01/01 12:00:02 Another slash"
        )
        
        val added = logBuffer.addIncremental(entries)
        assertEquals(4, added.size)
        assertEquals(4, logBuffer.getSize())
        
        // All should be unique
        val snapshot = logBuffer.toList()
        assertEquals(4, snapshot.size)
    }
    
    @Test
    fun `test duplicate detection with whitespace variations`() {
        val entry1 = "2024/01/01 12:00:00 Message"
        val entry2 = "  2024/01/01 12:00:00 Message  " // Same after trim
        
        logBuffer.addIncremental(listOf(entry1))
        val added = logBuffer.addIncremental(listOf(entry2))
        
        // Should be detected as duplicate after trimming
        assertEquals(0, added.size)
        assertEquals(1, logBuffer.getSize())
    }
}

