package com.hyperxray.an.telemetry

import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Ring buffer implementation for storing telemetry data.
 * Fixed capacity of 2000 entries with automatic overwrite on overflow.
 */
class TelemetryStore(
    private val capacity: Int = 2000
) {
    private val buffer = Array<TelemetryMetrics?>(capacity) { null }
    private var writeIndex = 0
    private var size = 0
    private val lock = ReentrantReadWriteLock()

    /**
     * Add a new telemetry entry to the store.
     * Overwrites oldest entry when buffer is full.
     */
    fun add(metrics: TelemetryMetrics) {
        lock.write {
            buffer[writeIndex] = metrics
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) {
                size++
            }
        }
    }

    /**
     * Add multiple entries at once
     */
    fun addAll(metricsList: List<TelemetryMetrics>) {
        lock.write {
            metricsList.forEach { metrics ->
                buffer[writeIndex] = metrics
                writeIndex = (writeIndex + 1) % capacity
                if (size < capacity) {
                    size++
                }
            }
        }
    }

    /**
     * Get all entries in chronological order (oldest first)
     */
    fun getAll(): List<TelemetryMetrics> {
        return lock.read {
            if (size == 0) {
                return emptyList()
            }
            
            val result = mutableListOf<TelemetryMetrics>()
            
            if (size < capacity) {
                // Buffer not full, read from start
                for (i in 0 until size) {
                    buffer[i]?.let { result.add(it) }
                }
            } else {
                // Buffer full, read from writeIndex (oldest) to writeIndex-1 (newest)
                for (i in 0 until capacity) {
                    val index = (writeIndex + i) % capacity
                    buffer[index]?.let { result.add(it) }
                }
            }
            
            result
        }
    }

    /**
     * Get entries within a time range
     */
    fun getInRange(start: Instant, end: Instant): List<TelemetryMetrics> {
        return lock.read {
            getAll().filter { it.timestamp >= start && it.timestamp <= end }
        }
    }

    /**
     * Get the most recent N entries
     */
    fun getRecent(count: Int): List<TelemetryMetrics> {
        return lock.read {
            getAll().takeLast(count)
        }
    }

    /**
     * Get current size of the store
     */
    fun size(): Int {
        return lock.read { size }
    }

    /**
     * Check if store is empty
     */
    fun isEmpty(): Boolean {
        return lock.read { size == 0 }
    }

    /**
     * Check if store is full
     */
    fun isFull(): Boolean {
        return lock.read { size == capacity }
    }

    /**
     * Clear all entries
     */
    fun clear() {
        lock.write {
            for (i in buffer.indices) {
                buffer[i] = null
            }
            writeIndex = 0
            size = 0
        }
    }

    /**
     * Get aggregated statistics for recent entries (last 60 seconds or last 60 samples)
     */
    fun getAggregated(windowSeconds: Int = 60, maxSamples: Int = 60): AggregatedTelemetry? {
        return lock.read {
            val allEntries = getAll()
            if (allEntries.isEmpty()) {
                return null
            }
            
            val now = Instant.now()
            val windowStart = now.minusSeconds(windowSeconds.toLong())
            
            // Get entries from the last windowSeconds or last maxSamples, whichever is more recent
            val recentEntries = allEntries
                .filter { it.timestamp.isAfter(windowStart) }
                .takeLast(maxSamples)
            
            // If no recent entries, use the most recent maxSamples
            val entries = if (recentEntries.isEmpty()) {
                allEntries.takeLast(maxSamples)
            } else {
                recentEntries
            }
            
            if (entries.isEmpty()) {
                return null
            }
            
            // Filter out zero throughput values for accurate average calculation
            val validThroughputs = entries.map { it.throughput }.filter { it > 0.0 }
            val avgThroughput = if (validThroughputs.isNotEmpty()) {
                validThroughputs.average()
            } else {
                // If all throughputs are 0, try to find the most recent non-zero value from all entries
                val allValidThroughputs = allEntries.map { it.throughput }.filter { it > 0.0 }
                if (allValidThroughputs.isNotEmpty()) {
                    allValidThroughputs.last() // Use most recent valid value
                } else {
                    0.0 // No valid throughput data available
                }
            }
            
            val rtts = entries.map { it.rttP95 }
            val handshakeTimes = entries.map { it.handshakeTime }
            val losses = entries.map { it.loss }
            
            val sortedRtts = rtts.sorted()
            val rttP95Index = (sortedRtts.size * 0.95).toInt().coerceAtMost(sortedRtts.size - 1)
            val rttP95 = if (sortedRtts.isNotEmpty()) sortedRtts[rttP95Index] else 0.0
            
            AggregatedTelemetry(
                avgThroughput = avgThroughput,
                rttP95 = rttP95,
                avgHandshakeTime = handshakeTimes.average(),
                avgLoss = losses.average(),
                sampleCount = entries.size,
                windowStart = entries.first().timestamp,
                windowEnd = entries.last().timestamp
            )
        }
    }
}



