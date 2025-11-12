package com.hyperxray.an.optimizer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * RealityWorkManager: Background worker for continuous learning.
 * 
 * Periodically analyzes feedback logs, updates LearnerState biases,
 * and syncs updated xray_reality_policy.json if available.
 */
class RealityWorkManager(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "RealityWorkManager"
    private val learner = OnDeviceLearner(applicationContext)
    private val learnerState = LearnerState(applicationContext)
    
    data class FeedbackLogEntry(
        val timestamp: Long,
        val sni: String,
        val svcClass: Int,
        val routeDecision: Int,
        val success: Boolean,
        val latencyMs: Float,
        val throughputKbps: Float,
        val alpn: String = "h2",
        val rtt: Double? = null,
        val jitter: Double? = null,
        val networkType: String? = null
    )
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "RealityWorkManager started")
            
            // Read feedback logs
            val logFile = File(applicationContext.filesDir, "learner_log.jsonl")
            if (!logFile.exists()) {
                Log.d(TAG, "No feedback logs found, skipping update")
                return@withContext Result.success()
            }
            
            // Parse recent log entries (last 10K for worker performance)
            // Full 1M entries are processed in RealityWorker for policy generation
            val recentEntries = parseRecentLogs(logFile, maxEntries = 10_000)
            
            if (recentEntries.isEmpty()) {
                Log.d(TAG, "No recent feedback entries found")
                return@withContext Result.success()
            }
            
            // Update learner with feedback
            for (entry in recentEntries) {
                learner.updateWithFeedback(
                    success = entry.success,
                    throughputKbps = entry.throughputKbps,
                    svcIdx = entry.svcClass,
                    routeIdx = entry.routeDecision
                )
            }
            
            // Write updated policy JSON
            writePolicyJson()
            
            Log.i(TAG, "Updated learner from ${recentEntries.size} feedback entries")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "RealityWorkManager failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    /**
     * Parse recent log entries from JSONL file.
     */
    private fun parseRecentLogs(logFile: File, maxEntries: Int): List<FeedbackLogEntry> {
        val entries = mutableListOf<FeedbackLogEntry>()
        
        try {
            logFile.readLines().takeLast(maxEntries).forEach { line ->
                try {
                    val json = JSONObject(line)
                    val entry = FeedbackLogEntry(
                        timestamp = json.getLong("timestamp"),
                        sni = json.getString("sni"),
                        svcClass = json.optInt("svcClass", 7),
                        routeDecision = json.optInt("routeDecision", 0),
                        success = json.getBoolean("success"),
                        latencyMs = json.optDouble("latencyMs", json.optDouble("latency", 0.0)).toFloat(),
                        throughputKbps = json.optDouble("throughputKbps", json.optDouble("throughput", 0.0)).toFloat(),
                        alpn = json.optString("alpn")?.takeIf { it.isNotEmpty() } ?: "h2",
                        rtt = if (json.has("rtt")) json.optDouble("rtt") else null,
                        jitter = if (json.has("jitter")) json.optDouble("jitter") else null,
                        networkType = json.optString("networkType")?.takeIf { it.isNotEmpty() }
                    )
                    entries.add(entry)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse log entry: ${e.message}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading log file: ${e.message}", e)
        }
        
        return entries
    }
    
    /**
     * Write updated xray_reality_policy.json.
     */
    private fun writePolicyJson() {
        try {
            val policyFile = File(applicationContext.filesDir, "xray_reality_policy.json")
            val temp = learnerState.getTemperature()
            val svcBiases = learnerState.getSvcBiases()
            val routeBiases = learnerState.getRouteBiases()
            
            // Build JSON object
            val policy = JSONObject().apply {
                put("version", "v9")
                put("timestamp", System.currentTimeMillis())
                put("learner", JSONObject().apply {
                    put("temperature", temp)
                    put("svcBiases", org.json.JSONArray(svcBiases.toList()))
                    put("routeBiases", org.json.JSONArray(routeBiases.toList()))
                })
            }
            
            val json = policy.toString(2)
            
            FileWriter(policyFile).use { writer ->
                writer.write(json)
                writer.flush()
            }
            
            Log.d(TAG, "Written policy to ${policyFile.absolutePath}")
            
            // TODO: Trigger Xray reload if file-based reload is available
            triggerXrayReload()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing policy JSON: ${e.message}", e)
        }
    }
    
    /**
     * Trigger Xray configuration reload.
     */
    private fun triggerXrayReload() {
        // Option 1: Send broadcast intent to TProxyService
        try {
            val intent = android.content.Intent("com.hyperxray.an.RELOAD_CONFIG")
            intent.setPackage(applicationContext.packageName)
            applicationContext.sendBroadcast(intent)
            Log.d(TAG, "Sent reload broadcast to TProxyService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger Xray reload: ${e.message}")
        }
        
        // Option 2: Write to watched file (if Xray supports file watching)
        // Option 3: Call Xray API endpoint (if available)
    }
    
    companion object {
        /**
         * Schedule periodic work.
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<RealityWorkManager>(
                30, TimeUnit.MINUTES // Run every 30 minutes
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i("RealityWorkManager", "Scheduled periodic learning work")
        }
    }
}

