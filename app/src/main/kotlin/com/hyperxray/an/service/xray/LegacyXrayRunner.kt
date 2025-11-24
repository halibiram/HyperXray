package com.hyperxray.an.service.xray

import android.content.Intent
import android.util.Log
import com.hyperxray.an.common.ConfigUtils
import com.hyperxray.an.common.ConfigUtils.extractPortsFromJson
import com.hyperxray.an.service.TProxyService
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
            
            // Use libxray.so directly with Android linker
            val xrayPath = "$libraryDir/libxray.so"
            val excludedPorts = extractPortsFromJson(config.configContent)
            val apiPort = TProxyUtils.findAvailablePort(excludedPorts)
            if (apiPort == null) {
                val errorMessage = "Failed to find available port. All ports in range 10000-65535 are in use or excluded."
                Log.e(TAG, errorMessage)
                
                // Send Telegram notification for port allocation error
                runnerContext.telegramNotificationManager?.let { manager ->
                    runnerContext.serviceScope.launch {
                        manager.notifyError(
                            "Port Allocation Error\n\n" +
                            "All ports in range 10000-65535 are in use or excluded.\n" +
                            "Xray-core cannot start without an available port."
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
                            runnerContext.stopXray("Failed to find available port for Xray API")
                        }
                    }
                }
                return
            }
            runnerContext.prefs.apiPort = apiPort
            Log.d(TAG, "Found and set API port: $apiPort")

            val processBuilder = TProxyUtils.getProcessBuilder(runnerContext.context, xrayPath)
            currentProcess = processBuilder.start()
            runnerContext.xrayProcess = currentProcess

            // CRITICAL: Start reading process output IMMEDIATELY (before config write)
            // This allows us to capture error messages even if process crashes before/during config read
            runnerContext.xrayLogHandler.startReading(currentProcess!!, runnerContext.logFileManager)
            
            // Small delay to allow process to potentially output startup errors
            Thread.sleep(100)
            
            // Validate process startup with periodic checks to catch early exits
            // Check process status multiple times instead of fixed sleep to detect failures quickly
            val checkInterval = 50L // Check every 50ms
            val minStartupChecks = 2 // Minimum 2 checks (100ms) before considering process started
            val maxStartupChecks = 100 // Maximum 100 checks (5 seconds) as safety timeout
            
            var checksPerformed = 0
            var processValidated = false
            
            // Periodically check if process stays alive during startup
            while (checksPerformed < maxStartupChecks) {
                delay(checkInterval)
                checksPerformed++
                
                // Check if process exited during startup
                if (!currentProcess!!.isAlive) {
                    val exitValue = try {
                        currentProcess!!.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    val errorMessage = "Xray process exited during startup after ${checksPerformed * checkInterval}ms (exit code: $exitValue)"
                    Log.e(TAG, errorMessage)
                    
                    // Send Telegram notification for Xray startup crash
                    runnerContext.telegramNotificationManager?.let { manager ->
                        runnerContext.serviceScope.launch {
                            manager.notifyError(
                                "Xray Startup Crash\n\n" +
                                "Xray process exited during startup.\n" +
                                "Exit code: $exitValue\n" +
                                "Startup time: ${checksPerformed * checkInterval}ms"
                            )
                        }
                    }
                    
                    throw IOException(errorMessage)
                }
                
                // After minimum checks, if process is still alive, consider it started
                // Note: We can't easily verify if process is fully ready, but being alive
                // for a reasonable time (100ms+) is a good indicator it started successfully
                if (checksPerformed >= minStartupChecks) {
                    processValidated = true
                    Log.d(TAG, "Process startup validated after ${checksPerformed * checkInterval}ms")
                    break
                }
            }
            
            // Final validation check
            if (!processValidated) {
                // We hit the maximum check limit - verify process is still alive
                if (!currentProcess!!.isAlive) {
                    val exitValue = try {
                        currentProcess!!.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    val errorMessage = "Xray process exited during startup validation (exit code: $exitValue)"
                    Log.e(TAG, errorMessage)
                    
                    // Send Telegram notification for Xray validation crash
                    runnerContext.telegramNotificationManager?.let { manager ->
                        runnerContext.serviceScope.launch {
                            manager.notifyError(
                                "Xray Validation Crash\n\n" +
                                "Xray process exited during startup validation.\n" +
                                "Exit code: $exitValue"
                            )
                        }
                    }
                    
                    throw IOException(errorMessage)
                }
                // Process is alive but we hit timeout - log warning but proceed
                Log.w(TAG, "Process startup validation hit timeout (${maxStartupChecks * checkInterval}ms), but process is alive. Proceeding.")
            }

            Log.d(TAG, "Writing config to xray stdin from: ${config.configFile.canonicalPath}")
            val injectedConfigContent =
                ConfigUtils.injectStatsService(runnerContext.prefs, config.configContent)
            
            // CRITICAL: Verify UDP support is enabled in dokodemo-door inbounds before sending to Xray
            try {
                val configJson = org.json.JSONObject(injectedConfigContent)
                val inboundsArray = configJson.optJSONArray("inbounds") ?: configJson.optJSONArray("inbound")
                if (inboundsArray != null) {
                    var dokodemoFound = false
                    var udpEnabled = false
                    for (i in 0 until inboundsArray.length()) {
                        val inbound = inboundsArray.getJSONObject(i)
                        val protocol = inbound.optString("protocol", "").lowercase()
                        if (protocol == "dokodemo-door" || protocol == "dokodemo" || protocol == "tunnel") {
                            dokodemoFound = true
                            val settings = inbound.optJSONObject("settings")
                            if (settings != null) {
                                val network = settings.opt("network")
                                if (network is org.json.JSONArray) {
                                    val networks = mutableSetOf<String>()
                                    for (j in 0 until network.length()) {
                                        networks.add(network.optString(j, "").lowercase())
                                    }
                                    udpEnabled = networks.contains("udp") && networks.contains("tcp")
                                    Log.i(TAG, "🔍 VERIFICATION: dokodemo-door inbound #${i+1}: network=${network.toString()}, UDP enabled=$udpEnabled")
                                } else {
                                    Log.w(TAG, "⚠️ VERIFICATION FAILED: dokodemo-door inbound #${i+1} network is not an array: $network")
                                }
                            } else {
                                Log.w(TAG, "⚠️ VERIFICATION FAILED: dokodemo-door inbound #${i+1} has no settings object")
                            }
                        }
                    }
                    if (dokodemoFound && !udpEnabled) {
                        Log.e(TAG, "❌ CRITICAL ERROR: dokodemo-door inbound found but UDP is NOT enabled in config!")
                    } else if (dokodemoFound && udpEnabled) {
                        Log.i(TAG, "✅ VERIFICATION SUCCESS: dokodemo-door inbound has UDP enabled in config")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying config before writing to Xray: ${e.message}", e)
            }
            
            try {
                currentProcess!!.outputStream.use { os ->
                    os.write(injectedConfigContent.toByteArray())
                    os.flush()
                }
                Log.d(TAG, "Config written to Xray stdin successfully")
            } catch (e: IOException) {
                if (!currentProcess!!.isAlive) {
                    val exitValue = try { currentProcess!!.exitValue() } catch (ex: IllegalThreadStateException) { -1 }
                    Log.e(TAG, "Xray process exited while writing config, exit code: $exitValue")
                    
                    // Try to read any error output before it's lost
                    try {
                        val errorReader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                        val errorOutput = StringBuilder()
                        var lineCount = 0
                        var line = errorReader.readLine()
                        while (lineCount < 10 && line != null) {
                            errorOutput.append(line).append("\n")
                            line = errorReader.readLine()
                            lineCount++
                        }
                        if (errorOutput.isNotEmpty()) {
                            Log.e(TAG, "Xray error output before crash:\n$errorOutput")
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Could not read error output: ${ex.message}")
                    }
                }
                throw e
            }
            
            // Start SOCKS5 readiness check after Xray process starts
            // Check readiness both when we detect "Xray ... started" in logs AND
            // after a fixed delay to ensure we catch it even if log format changes
            runnerContext.setSocks5ReadinessChecked(false)
            
            // CRITICAL: Check if process is still alive after config write (synchronous)
            // Xray may exit immediately if config is invalid
            // Wait synchronously to ensure process has processed config
            // Read stderr output in parallel to capture errors immediately
            val stderrOutput = StringBuilder()
            val stderrJob = runnerContext.serviceScope.launch(Dispatchers.IO) {
                try {
                    val errorReader = BufferedReader(InputStreamReader(currentProcess!!.errorStream))
                    while (isActive && currentProcess!!.isAlive) {
                        try {
                            val line = errorReader.readLine()
                            if (line == null) {
                                delay(100) // Wait a bit before checking again
                                continue
                            }
                            stderrOutput.append(line).append("\n")
                            Log.e(TAG, "Xray stderr: $line")
                            // Limit buffer size to prevent memory issues
                            if (stderrOutput.length > 5000) {
                                stderrOutput.delete(0, stderrOutput.length - 2500) // Keep last half
                            }
                        } catch (e: IOException) {
                            if (currentProcess!!.isAlive) {
                                delay(100)
                                continue
                            } else {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read stderr: ${e.message}")
                }
            }
            
            // Increased delay to allow process to fully start and output any errors
            delay(3000) // Wait 3 seconds for process to process config and potentially output errors
            
            if (!currentProcess!!.isAlive) {
                val exitValue = try {
                    currentProcess!!.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                
                // Cancel stderr reading job
                stderrJob.cancel()
                
                // Try to read stdout error output before process cleanup
                val stdoutOutput = StringBuilder()
                try {
                    val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                    var lineCount = 0
                    var line = reader.readLine()
                    while (lineCount < 50 && line != null) {
                        stdoutOutput.append(line).append("\n")
                        Log.e(TAG, "Xray stdout: $line")
                        line = reader.readLine()
                        lineCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read process stdout: ${e.message}")
                }
                
                // Combine stderr and stdout output
                val allErrorOutput = StringBuilder()
                if (stderrOutput.isNotEmpty()) {
                    allErrorOutput.append("=== STDERR ===\n")
                    allErrorOutput.append(stderrOutput)
                }
                if (stdoutOutput.isNotEmpty()) {
                    if (allErrorOutput.isNotEmpty()) allErrorOutput.append("\n")
                    allErrorOutput.append("=== STDOUT ===\n")
                    allErrorOutput.append(stdoutOutput)
                }
                
                val errorMessage = if (allErrorOutput.isNotEmpty()) {
                    "Xray process exited immediately after config write (exit code: $exitValue)\n\n" +
                    "Xray process output:\n$allErrorOutput"
                } else {
                    "Xray process exited immediately after config write (exit code: $exitValue)\n\n" +
                    "No error output captured. Possible causes:\n" +
                    "1. Invalid JSON config format\n" +
                    "2. Missing required config fields\n" +
                    "3. Invalid inbound/outbound configuration\n" +
                    "4. File permissions issue\n" +
                    "5. Missing geoip/geosite files"
                }
                
                Log.e(TAG, errorMessage)
                
                // Send detailed error notification
                runnerContext.telegramNotificationManager?.let { manager ->
                    runnerContext.serviceScope.launch {
                        manager.notifyError(
                            "Xray Immediate Crash After Config\n\n" +
                            "Exit code: $exitValue\n\n" +
                            if (allErrorOutput.isNotEmpty()) {
                                "Xray Process Output:\n${allErrorOutput.toString().take(1000)}\n\n"
                            } else {
                                ""
                            } +
                            "Check log file and config file for details."
                        )
                    }
                }
                
                val errorIntent = Intent(runnerContext.getActionError())
                errorIntent.setPackage(runnerContext.getApplicationPackageName())
                errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
                runnerContext.sendBroadcast(errorIntent)
                
                throw IOException(errorMessage)
            }
            
            // Synchronous process: SOCKS5 readiness is checked before Xray startup
            // No background check needed since we wait synchronously
            runnerContext.setSocks5ReadinessChecked(true)
            
            Log.d(TAG, "Xray process started successfully, monitoring output stream.")
            
            // CRITICAL: Broadcast instance status for legacy mode (MainViewModel needs this for connection state)
            // In legacy mode, we have a single instance running
            if (currentProcess != null && currentProcess!!.isAlive) {
                try {
                    val processId = try {
                        val pidField = currentProcess!!.javaClass.getDeclaredField("pid")
                        pidField.isAccessible = true
                        pidField.getLong(currentProcess!!)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not get process ID: ${e.message}")
                        0L
                    }
                    
                    val apiPort = runnerContext.prefs.apiPort
                    if (processId > 0 && apiPort > 0) {
                        // Broadcast instance status for legacy mode
                        val statusIntent = Intent(runnerContext.getActionInstanceStatusUpdate())
                        statusIntent.setPackage(runnerContext.getApplicationPackageName())
                        statusIntent.putExtra("instance_count", 1)
                        statusIntent.putExtra("has_running", true)
                        statusIntent.putExtra("instance_0_pid", processId.toInt())
                        statusIntent.putExtra("instance_0_port", apiPort)
                        statusIntent.putExtra("instance_0_index", 0)
                        statusIntent.putExtra("instance_0_status", "Running")
                        runnerContext.sendBroadcast(statusIntent)
                        Log.d(TAG, "Broadcasted legacy mode instance status: pid=$processId, port=$apiPort")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error broadcasting legacy instance status: ${e.message}", e)
                }
                
                // Broadcast SOCKS5 readiness if not already done
                if (runnerContext.isSocks5ReadinessChecked()) {
                    try {
                        val socksPort = runnerContext.prefs.socksPort
                        val socksAddress = runnerContext.prefs.socksAddress
                        val readyIntent = Intent(runnerContext.getActionSocks5Ready())
                        readyIntent.setPackage(runnerContext.getApplicationPackageName())
                        readyIntent.putExtra("socks_address", socksAddress)
                        readyIntent.putExtra("socks_port", socksPort)
                        readyIntent.putExtra("is_ready", true)
                        runnerContext.sendBroadcast(readyIntent)
                        Log.d(TAG, "Broadcasted SOCKS5 readiness for legacy mode: $socksAddress:$socksPort")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error broadcasting SOCKS5 readiness: ${e.message}", e)
                    }
                }
            }
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
        return currentProcess != null && currentProcess!!.isAlive
    }
}


