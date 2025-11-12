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
     */
    fun logFeedback(
        context: Context,
        sni: String,
        svcClass: Int,
        routeDecision: Int,
        success: Boolean,
        latencyMs: Float,
        throughputKbps: Float
    ) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            
            // Redact SNI for privacy (keep only domain structure)
            val redactedSni = redactSni(sni)
            
            // Append to JSONL file
            FileWriter(logFile, true).use { writer ->
                val json = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("sni", redactedSni)
                    put("svcClass", svcClass)
                    put("routeDecision", routeDecision)
                    put("success", success)
                    put("latencyMs", latencyMs)
                    put("throughputKbps", throughputKbps)
                }
                writer.appendLine(json.toString())
                writer.flush()
            }
            
            // Rotate log if too large (10MB)
            if (logFile.length() > 10 * 1024 * 1024) {
                rotateLog(logFile)
            }
            
            Log.d(TAG, "Logged feedback: sni=$redactedSni, svc=$svcClass, route=$routeDecision")
            
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

