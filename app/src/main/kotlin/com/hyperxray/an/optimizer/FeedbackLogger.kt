package com.hyperxray.an.optimizer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * FeedbackLogger: Real-time feedback recording for network monitor hooks.
 * 
 * Logs feedback to learner_log.jsonl for continuous learning.
 * 
 * Usage:
 * ```kotlin
 * FeedbackLogger.log(
 *     context = context,
 *     sni = "googlevideo.com",
 *     latency = 42.0,
 *     throughput = 890.0,
 *     success = true
 * )
 * ```
 */
object FeedbackLogger {
    private const val TAG = "FeedbackLogger"
    private const val LOG_FILE_NAME = "learner_log.jsonl"
    
    /**
     * Log feedback event.
     * 
     * @param context Android context
     * @param sni Server Name Indication
     * @param latency Latency in milliseconds
     * @param throughput Throughput in kbps
     * @param success Whether the connection was successful
     */
    fun log(
        context: Context,
        sni: String,
        latency: Double,
        throughput: Double,
        success: Boolean
    ) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            
            // Redact SNI for privacy
            val redactedSni = redactSni(sni)
            
            // Create JSON entry
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("sni", redactedSni)
                put("latency", latency)
                put("throughput", throughput)
                put("success", success)
            }
            
            // Append to JSONL file
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(json.toString())
                writer.flush()
            }
            
            // Update learner state counters
            val learnerState = LearnerState(context)
            if (success) {
                learnerState.incrementSuccess()
            } else {
                learnerState.incrementFail()
            }
            
            // Rotate log if too large (1GB)
            val maxLogSize = 1024L * 1024L * 1024L // 1GB
            if (logFile.length() > maxLogSize) {
                rotateLog(logFile)
                Log.i(TAG, "Log file exceeded 1GB limit, rotated to archive")
            }
            
            Log.d(TAG, "Logged feedback: sni=$redactedSni, latency=${latency}ms, throughput=${throughput}kbps, success=$success")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing feedback log: ${e.message}", e)
        }
    }
    
    /**
     * Redact SNI for privacy (keep only domain structure).
     */
    private fun redactSni(sni: String): String {
        val parts = sni.split(".")
        if (parts.size > 3) {
            // Redact middle parts: a.b.c.d.example.com -> a.***.c.d.example.com
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

