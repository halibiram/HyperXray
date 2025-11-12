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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.SecurityException
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
            var process: Process? = null
            try {
                Log.d(TAG, "Starting AI log capture for tags: ${aiTags.joinToString()}")
                
                // Build logcat command to filter by tags
                // Format: logcat -v time -s TAG1:* TAG2:* ...
                val command = mutableListOf("logcat", "-v", "time", "-s")
                aiTags.forEach { tag ->
                    command.add("$tag:*")
                }
                
                // Execute logcat command with proper error handling
                try {
                    process = Runtime.getRuntime().exec(command.toTypedArray())
                    logcatProcess = process
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to execute logcat command: ${e.message}", e)
                    // logcat might not be available on all devices or might require permissions
                    return@launch
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception when executing logcat: ${e.message}", e)
                    // Insufficient permissions to execute logcat
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error executing logcat: ${e.message}", e)
                    return@launch
                }
                
                // Validate process was created
                if (process == null) {
                    Log.e(TAG, "Failed to create logcat process - process is null")
                    return@launch
                }
                
                // Check if process is alive before proceeding
                try {
                    // Wait a brief moment to see if process starts successfully
                    delay(100)
                    if (!process.isAlive) {
                        val exitValue = try {
                            process.exitValue()
                        } catch (e: IllegalThreadStateException) {
                            -1
                        }
                        Log.e(TAG, "logcat process exited immediately (exit code: $exitValue)")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking process status: ${e.message}", e)
                    return@launch
                }
                
                // Read logcat output with proper error handling and cleanup
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (isActive) {
                            try {
                                // Check if process is still alive before reading
                                if (!process.isAlive) {
                                    val exitValue = try {
                                        process.exitValue()
                                    } catch (e: IllegalThreadStateException) {
                                        -1
                                    }
                                    Log.d(TAG, "logcat process exited (exit code: $exitValue), stopping capture")
                                    break
                                }
                                
                                // Check if reader is ready to avoid blocking indefinitely
                                if (!reader.ready()) {
                                    // No data available yet, check cancellation and process status
                                    delay(100)
                                    ensureActive() // Check for cancellation
                                    if (!process.isAlive) {
                                        break
                                    }
                                    continue
                                }
                                
                                // Read line (this may block, but we've checked reader is ready)
                                val line = reader.readLine()
                                if (line == null) {
                                    // EOF - stream closed
                                    Log.d(TAG, "logcat stream reached EOF")
                                    break
                                }
                                
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
                                
                                // Small delay to allow cancellation to be checked
                                delay(10)
                                
                            } catch (e: CancellationException) {
                                Log.d(TAG, "Log capture cancelled")
                                throw e // Re-throw to properly handle cancellation
                            } catch (e: IOException) {
                                // Check if process is still alive
                                if (process.isAlive) {
                                    Log.e(TAG, "IOException during log capture (process alive): ${e.message}", e)
                                } else {
                                    Log.d(TAG, "IOException during log capture (process dead): ${e.message}")
                                }
                                break
                            } catch (e: Exception) {
                                if (isActive) {
                                    Log.e(TAG, "Unexpected error during log capture: ${e.message}", e)
                                }
                                break
                            }
                        }
                    }
                } finally {
                    // Ensure process streams are closed
                    try {
                        process.inputStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing process input stream", e)
                    }
                    try {
                        process.errorStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing process error stream", e)
                    }
                    try {
                        process.outputStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing process output stream", e)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "AI log capture cancelled")
                // Don't log as error - cancellation is expected
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing AI logs: ${e.message}", e)
            } finally {
                // Always destroy process and clean up
                try {
                    process?.destroy()
                    // Give process a moment to terminate gracefully (non-blocking check)
                    // Note: We use a try-catch around delay in case of cancellation
                    try {
                        delay(100)
                        if (process?.isAlive == true) {
                            Log.d(TAG, "Process still alive, forcing destroy")
                            process?.destroyForcibly()
                        }
                    } catch (e: CancellationException) {
                        // Cancellation during delay - just force destroy immediately
                        if (process?.isAlive == true) {
                            Log.d(TAG, "Cancellation detected, forcing process destroy")
                            process?.destroyForcibly()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying logcat process: ${e.message}", e)
                } finally {
                    // Always clear process reference
                    logcatProcess = null
                }
            }
        }
    }
    
    /**
     * Stop capturing AI logs.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping AI log capture")
        
        // Cancel the capture job (non-blocking)
        captureJob?.cancel()
        
        // Remove pending broadcasts
        handler.removeCallbacks(broadcastLogsRunnable)
        
        // Broadcast any remaining logs (non-blocking)
        try {
            broadcastLogsRunnable.run()
        } catch (e: Exception) {
            Log.w(TAG, "Error broadcasting remaining logs: ${e.message}", e)
        }
        
        // Destroy logcat process and clean up streams
        val process = logcatProcess
        if (process != null) {
            try {
                // Close streams first to prevent blocking
                try {
                    process.inputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing process input stream in stopCapture", e)
                }
                try {
                    process.errorStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing process error stream in stopCapture", e)
                }
                try {
                    process.outputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing process output stream in stopCapture", e)
                }
                
                // Destroy process (non-blocking)
                process.destroy()
                // Force destroy if still alive after a moment
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying logcat process in stopCapture: ${e.message}", e)
            }
            logcatProcess = null
        }
        
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

