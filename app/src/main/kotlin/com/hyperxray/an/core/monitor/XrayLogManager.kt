package com.hyperxray.an.core.monitor

import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages collection of Xray logs from native Go log channel.
 * Periodically polls native log channel and forwards logs to XrayLogHandler.
 * Persists monitoring state in SharedPreferences to survive process death.
 */
class XrayLogManager(
    private val scope: CoroutineScope,
    private val logHandler: com.hyperxray.an.service.managers.XrayLogHandler,
    private val prefs: Preferences
) {
    private val TAG = "XrayLogManager"
    
    private var monitoringJob: Job? = null
    private var isMonitoring = false
    
    init {
        // Restore monitoring state from SharedPreferences
        if (prefs.xrayLogMonitoringEnabled) {
            isMonitoring = true
            Log.d(TAG, "Restoring Xray log monitoring state from SharedPreferences")
        }
    }
    
    // Native function to get Xray logs
    private external fun getXrayLogsNative(maxCount: Int): String?
    
    companion object {
        init {
            try {
                System.loadLibrary("hyperxray-jni")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("XrayLogManager", "Failed to load native library: ${e.message}", e)
            }
        }
    }
    
    /**
     * Starts monitoring Xray logs from native channel.
     * Polls every 500ms to collect logs.
     * If monitoring was restored from SharedPreferences (process death recovery),
     * this will restart the monitoring job.
     */
    fun startMonitoring() {
        // If already monitoring and job is running, do nothing
        if (isMonitoring && monitoringJob != null) {
            Log.d(TAG, "Log monitoring already started")
            return
        }
        
        // If monitoring was restored but job is null (process death), restart job
        if (isMonitoring && monitoringJob == null) {
            Log.d(TAG, "Restarting Xray log monitoring job after process death recovery")
        }
        
        isMonitoring = true
        // Persist monitoring state to SharedPreferences
        prefs.xrayLogMonitoringEnabled = true
        Log.d(TAG, "Starting Xray log monitoring from native channel")
        AiLogHelper.i(TAG, "🚀 XrayLogManager: Starting log monitoring from native channel")
        
        monitoringJob = scope.launch(Dispatchers.IO) {
            while (isActive && isMonitoring) {
                try {
                    // Get logs from native channel (up to 100 logs per poll)
                    val logsJson = getXrayLogsNative(100)
                    
                    if (logsJson != null) {
                        try {
                            val json = JSONObject(logsJson)
                            
                            // Check for error
                            if (json.has("error")) {
                                val error = json.getString("error")
                                when (error) {
                                    "no tunnel running" -> {
                                        // Normal - tunnel not started yet
                                        if (System.currentTimeMillis() % 10000 < 500) {
                                            Log.d(TAG, "No tunnel running yet (waiting...)")
                                        }
                                    }
                                    "log channel closed" -> {
                                        // Log channel was closed - this is a problem
                                        Log.w(TAG, "⚠️ Log channel closed - logs may not be available")
                                        AiLogHelper.w(TAG, "⚠️ XrayLogManager: Log channel closed")
                                        // Note: We continue polling in case channel is reopened
                                    }
                                    else -> {
                                        Log.w(TAG, "Error getting logs: $error")
                                    }
                                }
                            } else if (json.has("logs")) {
                                val logsArray = json.getJSONArray("logs")
                                val count = json.optInt("count", logsArray.length())
                                
                                if (count > 0) {
                                    Log.d(TAG, "Received $count logs from native channel")
                                    AiLogHelper.d(TAG, "📥 XrayLogManager: Received $count logs from native channel")
                                    
                                    // Process each log line
                                    for (i in 0 until logsArray.length()) {
                                        val logLine = logsArray.getString(i)
                                        Log.v(TAG, "Processing log line: $logLine")
                                        logHandler.processLogLine(logLine)
                                    }
                                } else {
                                    // Log when no logs are received (less verbose)
                                    if (System.currentTimeMillis() % 10000 < 500) { // Log every ~10 seconds
                                        Log.w(TAG, "⚠️ No logs available in native channel (polling...)")
                                        Log.w(TAG, "⚠️ Possible causes:")
                                        Log.w(TAG, "⚠️   1. XrayLogWriter not receiving log messages from Xray-core")
                                        Log.w(TAG, "⚠️   2. Log channel is closed or not initialized")
                                        Log.w(TAG, "⚠️   3. Xray-core is not generating logs (check log level in config)")
                                        Log.w(TAG, "⚠️   4. Log channel buffer is full and logs are being dropped")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse logs JSON: ${e.message}", e)
                        }
                    }
                    
                    // Poll every 500ms
                    delay(500)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in log monitoring loop: ${e.message}", e)
                        delay(1000) // Wait longer on error
                    }
                }
            }
        }
    }
    
    /**
     * Stops monitoring Xray logs.
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        isMonitoring = false
        // Persist monitoring state to SharedPreferences
        prefs.xrayLogMonitoringEnabled = false
        Log.d(TAG, "Stopping Xray log monitoring")
        
        monitoringJob?.cancel()
        monitoringJob = null
    }
}


