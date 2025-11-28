package com.hyperxray.an.service.xray

import android.content.Intent
import android.util.Log
import com.hyperxray.an.core.config.utils.ConfigInjector
import com.hyperxray.an.core.config.utils.ConfigParser
import com.hyperxray.an.service.utils.TProxyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException

/**
 * Legacy XrayRunner implementation for single-instance Xray-core execution.
 * 
 * This implementation handles the traditional single-process mode where
 * Xray-core runs as a single Process instance.
 */
class LegacyXrayRunner(
    private val runnerContext: XrayRunnerContext
) : XrayRunner {
    
    private val TAG = "LegacyXrayRunner"
    
    @Volatile
    private var currentProcess: Process? = null
    
    override suspend fun start(config: XrayConfig) {
        currentProcess = null
        try {
            val libraryDir = runnerContext.getNativeLibraryDir()
            if (libraryDir == null) {
                val errorMessage = "Failed to get native library directory."
                Log.e(TAG, errorMessage)
                
                // Send Telegram notification for library directory error
                runnerContext.telegramNotificationManager?.let { manager ->
                    runnerContext.serviceScope.launch {
                        manager.notifyError(
                            "Xray Library Error\n\n" +
                            "Failed to get native library directory.\n" +
                            "Xray-core cannot start."
                        )
                    }
                }
                
                val errorIntent = Intent(runnerContext.getActionError())
                errorIntent.setPackage(runnerContext.getApplicationPackageName())
                errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
                runnerContext.sendBroadcast(errorIntent)
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("Failed to get native library directory")
                        }
                    }
                }
                return
            }
            
            // Xray-core is embedded in libhyperxray.so, not started as separate process
            Log.w(TAG, "LegacyXrayRunner is deprecated - Xray-core is embedded in libhyperxray.so")
            val errorMessage = "LegacyXrayRunner is no longer supported. Xray-core is embedded in libhyperxray.so."
            Log.e(TAG, errorMessage)
            
            // Send Telegram notification
            runnerContext.telegramNotificationManager?.let { manager ->
                runnerContext.serviceScope.launch {
                    manager.notifyError(
                        "Legacy Xray Runner Deprecated\n\n" +
                        "Xray-core is now embedded in libhyperxray.so.\n" +
                        "LegacyXrayRunner is no longer supported."
                    )
                }
            }
            
            val errorIntent = Intent(runnerContext.getActionError())
            errorIntent.setPackage(runnerContext.getApplicationPackageName())
            errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
            runnerContext.sendBroadcast(errorIntent)
            runnerContext.handler.post {
                if (!runnerContext.isStopping()) {
                    runnerContext.serviceScope.launch {
                        runnerContext.stopXray("LegacyXrayRunner is deprecated")
                    }
                }
            }
            return
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "Xray process reading interrupted.")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing xray", e)
            
            // Send Telegram error notification for Xray crash
            runnerContext.telegramNotificationManager?.let { manager ->
                runnerContext.serviceScope.launch {
                    manager.notifyError(
                        "Xray process error: ${e.message ?: "Unknown error"}\n\n" +
                        "Error type: ${e.javaClass.simpleName}"
                    )
                }
            }
        } finally {
            Log.d(TAG, "Xray process task finished.")
            
            // CRITICAL FIX: Only stop VPN if process actually exited, not just because finally block executed
            // Process may still be running even if readProcessStreamWithTimeout finished
            val processActuallyExited = currentProcess != null && !currentProcess!!.isAlive
            
            // Clean up process reference only if process actually exited
            if (processActuallyExited) {
                if (runnerContext.xrayProcess === currentProcess) {
                    runnerContext.xrayProcess = null
                } else {
                    Log.w(TAG, "Finishing task for an old xray process instance.")
                }
            } else {
                // Process is still running - don't clean up reference
                Log.d(TAG, "Process is still running, keeping process reference. Task finished but process continues.")
            }
            
            // Only call stopXray if process actually exited (not just because finally block executed)
            if (runnerContext.isReloadingRequested()) {
                Log.d(TAG, "Xray process stopped due to configuration reload.")
                runnerContext.setReloadingRequested(false)
            } else if (processActuallyExited && !runnerContext.isStopping()) {
                Log.d(TAG, "Xray process exited unexpectedly. Stopping VPN.")
                
                // Send Telegram notification for unexpected process exit
                val exitValue = try {
                    currentProcess?.exitValue() ?: -1
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                
                runnerContext.telegramNotificationManager?.let { manager ->
                    runnerContext.serviceScope.launch {
                        manager.notifyError(
                            "Xray process exited unexpectedly\n\n" +
                            "Exit code: $exitValue\n" +
                            "Process may have crashed"
                        )
                    }
                }
                
                // Use handler to call stopXray on main thread to avoid reentrancy issues
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("Xray process exited unexpectedly (legacy mode)")
                        }
                    }
                }
            } else if (!processActuallyExited && currentProcess != null) {
                // Process is still running - this is normal, don't stop VPN
                Log.d(TAG, "Process is still running. Task finished but process continues normally.")
            }
        }
    }
    
    override suspend fun stop() {
        val processToStop = currentProcess
        if (processToStop != null && processToStop.isAlive) {
            try {
                // Close stdin to signal graceful shutdown
                processToStop.outputStream?.close()
                Log.d(TAG, "Closed stdin to signal graceful shutdown.")
                
                // Wait a bit for graceful shutdown
                delay(1000)
                
                // Force destroy if still alive
                if (processToStop.isAlive) {
                    processToStop.destroyForcibly()
                    Log.d(TAG, "Force destroyed Xray process.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Xray process: ${e.message}", e)
            }
        }
        currentProcess = null
        runnerContext.xrayProcess = null
    }
    
    override fun isRunning(): Boolean {
        val process = currentProcess
        return process != null && process.isAlive
    }
}







