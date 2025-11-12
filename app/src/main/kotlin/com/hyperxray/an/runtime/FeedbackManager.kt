package com.hyperxray.an.runtime

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * FeedbackManager: Collects latency(ms) & throughput(kbps) from network layer.
 * 
 * Maintains adaptive thresholds using rolling percentile windows.
 * Logs to JSONL file (tls_v5_runtime_log.jsonl) with privacy-safe format.
 */
class FeedbackManager(
    private val logDir: File,
    private val adaptiveWindowSize: Int = 60
) {
    private val TAG = "FeedbackManager"
    private val LOG_FILE_NAME = "tls_v5_runtime_log.jsonl"
    
    private val metricsHistory = mutableListOf<NetworkMetrics>()
    private val json = Json { 
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    
    @Serializable
    data class NetworkMetrics(
        val timestamp: String,
        val sni: String,
        val serviceType: Int,
        val routingDecision: Int,
        val latencyMs: Float,
        val throughputKbps: Float,
        val success: Boolean
    )
    
    /**
     * Record network metrics for a routing decision.
     */
    fun recordMetrics(
        sni: String,
        serviceType: Int,
        routingDecision: Int,
        latencyMs: Float,
        throughputKbps: Float,
        success: Boolean
    ) {
        try {
            // Redact PII from SNI (keep only domain structure)
            val redactedSni = redactSni(sni)
            
            val metrics = NetworkMetrics(
                timestamp = Instant.now().toString(),
                sni = redactedSni,
                serviceType = serviceType,
                routingDecision = routingDecision,
                latencyMs = latencyMs,
                throughputKbps = throughputKbps,
                success = success
            )
            
            synchronized(metricsHistory) {
                metricsHistory.add(metrics)
                
                // Keep only recent window
                if (metricsHistory.size > adaptiveWindowSize * 2) {
                    metricsHistory.removeAt(0)
                }
            }
            
            // Write to JSONL log file (crash-safe)
            writeLogEntry(metrics)
            
            Log.d(TAG, "Recorded metrics: sni=$redactedSni, latency=${latencyMs}ms, throughput=${throughputKbps}kbps")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording metrics: ${e.message}", e)
        }
    }
    
    /**
     * Get adaptive latency threshold (p95 percentile).
     */
    fun getLatencyThreshold(): Float {
        synchronized(metricsHistory) {
            if (metricsHistory.isEmpty()) {
                return 1000f // Default 1 second
            }
            
            val latencies = metricsHistory.map { it.latencyMs }.sorted()
            val p95Index = (latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)
            return latencies[p95Index]
        }
    }
    
    /**
     * Get adaptive throughput threshold (p5 percentile).
     */
    fun getThroughputThreshold(): Float {
        synchronized(metricsHistory) {
            if (metricsHistory.isEmpty()) {
                return 100f // Default 100 kbps
            }
            
            val throughputs = metricsHistory.map { it.throughputKbps }.sorted()
            val p5Index = (throughputs.size * 0.05).toInt().coerceAtMost(throughputs.size - 1)
            return throughputs[p5Index]
        }
    }
    
    /**
     * Get success rate for a routing decision.
     */
    fun getSuccessRate(routingDecision: Int): Float {
        synchronized(metricsHistory) {
            val relevant = metricsHistory.filter { it.routingDecision == routingDecision }
            if (relevant.isEmpty()) {
                return 0.5f // Default 50%
            }
            
            val successes = relevant.count { it.success }
            return successes.toFloat() / relevant.size
        }
    }
    
    /**
     * Get recent metrics (last N entries).
     */
    fun getRecentMetrics(count: Int = 10): List<NetworkMetrics> {
        synchronized(metricsHistory) {
            return metricsHistory.takeLast(count)
        }
    }
    
    /**
     * Redact PII from SNI (privacy-safe).
     */
    private fun redactSni(sni: String): String {
        // Keep domain structure but redact subdomains if too specific
        val parts = sni.split(".")
        if (parts.size > 3) {
            // Redact middle parts: a.b.c.d.example.com -> a.***.c.d.example.com
            return (parts.take(1) + listOf("***") + parts.takeLast(2)).joinToString(".")
        }
        return sni
    }
    
    /**
     * Write log entry to JSONL file (crash-safe).
     */
    private fun writeLogEntry(metrics: NetworkMetrics) {
        try {
            val logFile = File(logDir, LOG_FILE_NAME)
            
            // Ensure directory exists
            logDir.mkdirs()
            
            // Append to JSONL file
            FileWriter(logFile, true).use { writer ->
                val jsonLine = json.encodeToString(metrics)
                writer.appendLine(jsonLine)
                writer.flush()
            }
            
            // Rotate log if too large (10MB)
            if (logFile.length() > 10 * 1024 * 1024) {
                rotateLog(logFile)
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing log entry: ${e.message}", e)
        }
    }
    
    /**
     * Rotate log file when it gets too large.
     */
    private fun rotateLog(logFile: File) {
        try {
            val timestamp = Instant.now().toEpochMilli()
            val rotatedFile = File(logDir, "${LOG_FILE_NAME}.$timestamp")
            logFile.renameTo(rotatedFile)
            Log.i(TAG, "Rotated log file: ${rotatedFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log file: ${e.message}", e)
        }
    }
}

