package com.hyperxray.an.data.source

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hyperxray.an.service.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures AI-related logs from logcat and adds them to LogFileManager.
 * Monitors specific tags related to AI components and broadcasts them.
 */
class AiLogCapture(
    private val context: Context,
    private val logFileManager: LogFileManager
) {
    private val TAG = "AiLogCapture"
    
    // AI-related log tags to monitor
    private val aiTags = listOf(
        "DeepPolicyModel",
        "HyperXrayApplication",
        "ModelFallbackHandler",
        "ModelDeploymentSummary",
        "ModelSignatureVerifier",
        "OptimizerOrchestrator",
        "RealityBandit",
        "ShadowTester",
        "Scaler",
        "DeepTrainer"
    )
    
    private var logcatProcess: Process? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(TProxyService.ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(context.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    TProxyService.EXTRA_LOG_DATA, ArrayList(logBroadcastBuffer)
                )
                context.sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                Log.d(TAG, "Broadcasted AI logs batch.")
            }
        }
    }
    
    /**
     * Start capturing AI logs from logcat.
     */
    fun startCapture() {
        if (captureJob?.isActive == true) {
            Log.d(TAG, "AI log capture already running")
            return
        }
        
        captureJob = scope.launch {
            try {
                Log.d(TAG, "Starting AI log capture for tags: ${aiTags.joinToString()}")
                
                // Build logcat command to filter by tags
                // Format: logcat -v time -s TAG1:* TAG2:* ...
                val command = mutableListOf("logcat", "-v", "time", "-s")
                aiTags.forEach { tag ->
                    command.add("$tag:*")
                }
                
                logcatProcess = Runtime.getRuntime().exec(command.toTypedArray())
                
                BufferedReader(InputStreamReader(logcatProcess!!.inputStream)).use { reader ->
                    var line: String? = null
                    while (isActive) {
                        line = reader.readLine()
                        if (line == null) break
                        
                        // Format: MM-DD HH:MM:SS.XXX PID-TID/TAG: MESSAGE
                        // Convert to standard format for consistency
                        val formattedLine = formatLogcatLine(line)
                        if (formattedLine != null) {
                            logFileManager.appendLog(formattedLine)
                            // Broadcast immediately
                            synchronized(logBroadcastBuffer) {
                                logBroadcastBuffer.add(formattedLine)
                                handler.removeCallbacks(broadcastLogsRunnable)
                                handler.post(broadcastLogsRunnable)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing AI logs", e)
            } finally {
                logcatProcess?.destroy()
                logcatProcess = null
            }
        }
    }
    
    /**
     * Stop capturing AI logs.
     */
    fun stopCapture() {
        captureJob?.cancel()
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run() // Broadcast any remaining logs
        logcatProcess?.destroy()
        logcatProcess = null
        Log.d(TAG, "AI log capture stopped")
    }
    
    /**
     * Format logcat line to match Xray log format.
     * Input: MM-DD HH:MM:SS.XXX PID-TID/TAG: MESSAGE
     * Output: YYYY/MM/DD HH:MM:SS [TAG] MESSAGE
     */
    private fun formatLogcatLine(logcatLine: String): String? {
        return try {
            if (logcatLine.isBlank()) return null
            
            // Parse logcat format: MM-DD HH:MM:SS.XXX PID-TID/TAG: MESSAGE
            // Example: "01-15 14:30:45.123 12345-12345/DeepPolicyModel: Loading model"
            val colonIndex = logcatLine.indexOf(':')
            if (colonIndex == -1) return null
            
            val beforeColon = logcatLine.substring(0, colonIndex).trim()
            val message = logcatLine.substring(colonIndex + 1).trim()
            
            // Split before colon: "MM-DD HH:MM:SS.XXX PID-TID/TAG"
            val parts = beforeColon.split(" ", limit = 3)
            if (parts.size < 3) return null
            
            val datePart = parts[0] // MM-DD
            val timePart = parts[1].substringBeforeLast(".") // HH:MM:SS (remove milliseconds)
            val pidTidTag = parts[2] // PID-TID/TAG
            
            // Extract tag from PID-TID/TAG
            val tag = pidTidTag.substringAfterLast("/", "Unknown")
            
            // Parse date and convert to YYYY/MM/DD format
            val currentYear = SimpleDateFormat("yyyy", Locale.US).format(Date())
            val formattedDate = "$currentYear/$datePart"
            val formattedLine = "$formattedDate $timePart [$tag] $message"
            
            formattedLine
        } catch (e: Exception) {
            // If parsing fails, return original line with prefix
            "[AI] $logcatLine"
        }
    }
}

