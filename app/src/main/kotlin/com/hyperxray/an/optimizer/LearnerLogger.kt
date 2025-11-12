package com.hyperxray.an.optimizer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * LearnerLogger: Logs learning events to JSONL file.
 * 
 * Writes to /data/data/<pkg>/files/learner_log.jsonl
 */
object LearnerLogger {
    private const val TAG = "LearnerLogger"
    private const val LOG_FILE_NAME = "learner_log.jsonl"
    
    /**
     * Log a feedback event.
     * 
     * @param context Android context
     * @param sni Server Name Indication
     * @param svcClass Service class (0-7)
     * @param routeDecision Routing decision (0=Proxy, 1=Direct, 2=Optimized)
     * @param success Whether the connection was successful
     * @param latencyMs Latency in milliseconds
     * @param throughputKbps Throughput in kbps
     * @param alpn ALPN protocol (default: "h2")
     * @param rtt Round-trip time in milliseconds (optional)
     * @param jitter Jitter (RTT variance) in milliseconds (optional)
     * @param networkType Network type (WiFi/4G/5G, optional)
     */
    fun logFeedback(
        context: Context,
        sni: String,
        svcClass: Int,
        routeDecision: Int,
        success: Boolean,
        latencyMs: Float,
        throughputKbps: Float,
        alpn: String = "h2",
        rtt: Double? = null,
        jitter: Double? = null,
        networkType: String? = null
    ) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            
            // Redact SNI for privacy (keep only domain structure)
            val redactedSni = redactSni(sni)
            
            // Get current timestamp
            val timestamp = System.currentTimeMillis()
            
            // Extract temporal features from timestamp
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = timestamp
            val hourOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) // 0-23
            val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) // 1-7 (Sunday=1)
            
            // Append to JSONL file
            FileWriter(logFile, true).use { writer ->
                val json = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("sni", redactedSni)
                    put("svcClass", svcClass)
                    put("routeDecision", routeDecision)
                    put("success", success)
                    put("latencyMs", latencyMs)
                    put("throughputKbps", throughputKbps)
                    put("alpn", alpn)
                    
                    // Temporal features
                    put("hourOfDay", hourOfDay)
                    put("dayOfWeek", dayOfWeek)
                    
                    // Network context (optional)
                    if (rtt != null) {
                        put("rtt", rtt)
                    }
                    if (jitter != null) {
                        put("jitter", jitter)
                    }
                    if (networkType != null) {
                        put("networkType", networkType)
                    }
                }
                writer.appendLine(json.toString())
                writer.flush()
            }
            
            // Rotate log if too large (1GB)
            val maxLogSize = 1024L * 1024L * 1024L // 1GB
            if (logFile.length() > maxLogSize) {
                rotateLog(logFile)
                Log.i(TAG, "Log file exceeded 1GB limit, rotated to archive")
            }
            
            Log.d(TAG, "Logged feedback: sni=$redactedSni, svc=$svcClass, route=$routeDecision, alpn=$alpn, rtt=$rtt, jitter=$jitter, networkType=$networkType")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing log entry: ${e.message}", e)
        }
    }
    
    /**
     * Redact SNI for privacy.
     */
    private fun redactSni(sni: String): String {
        val parts = sni.split(".")
        if (parts.size > 3) {
            // Redact middle parts
            return (parts.take(1) + listOf("***") + parts.takeLast(2)).joinToString(".")
        }
        return sni
    }
    
    /**
     * Rotate log file when it gets too large.
     */
    private fun rotateLog(logFile: File) {
        try {
            val timestamp = System.currentTimeMillis()
            val rotatedFile = File(logFile.parent, "${LOG_FILE_NAME}.$timestamp")
            logFile.renameTo(rotatedFile)
            Log.i(TAG, "Rotated log file: ${rotatedFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log file: ${e.message}", e)
        }
    }
}

