package com.hyperxray.an.optimizer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * RealityWorker: Background worker for continuous learning and policy updates.
 * 
 * Periodically (30 minutes):
 * - Reads feedback logs (learner_log.jsonl)
 * - Aggregates success/failure metrics
 * - Updates LearnerState biases via EMA
 * - Rebuilds xray_reality_policy.json
 * - Sends Xray-core reload command
 * 
 * Can run when device is idle/charging.
 */
class RealityWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "RealityWorker"
    private val learner = OnDeviceLearner(applicationContext)
    private val learnerState = LearnerState(applicationContext)
    
    data class FeedbackEntry(
        val timestamp: Long,
        val sni: String,
        val latency: Double,
        val throughput: Double,
        val success: Boolean
    )
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "RealityWorker started")
            
            // Read feedback logs
            val logFile = File(applicationContext.filesDir, "learner_log.jsonl")
            if (!logFile.exists()) {
                Log.d(TAG, "No feedback logs found, skipping update")
                return@withContext Result.success()
            }
            
            // Parse recent log entries (last 100)
            val recentEntries = parseRecentLogs(logFile, maxEntries = 100)
            
            if (recentEntries.isEmpty()) {
                Log.d(TAG, "No recent feedback entries found")
                return@withContext Result.success()
            }
            
            // Aggregate metrics
            val aggregated = aggregateMetrics(recentEntries)
            
            // Update learner with aggregated feedback
            for (feedback in aggregated) {
                learner.updateWithFeedback(
                    success = feedback.success,
                    throughputKbps = feedback.throughput,
                    svcIdx = feedback.svcIdx,
                    routeIdx = feedback.routeIdx
                )
            }
            
            // Save learner state
            learnerState.save()
            
            // Rebuild policy JSON
            rebuildPolicyJson()
            
            // Trigger Xray reload
            triggerXrayReload()
            
            Log.i(TAG, "Updated learner from ${recentEntries.size} feedback entries, ${aggregated.size} aggregated updates")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "RealityWorker failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    /**
     * Parse recent log entries from JSONL file.
     */
    private fun parseRecentLogs(logFile: File, maxEntries: Int): List<FeedbackEntry> {
        val entries = mutableListOf<FeedbackEntry>()
        
        try {
            logFile.readLines().takeLast(maxEntries).forEach { line ->
                try {
                    val json = JSONObject(line)
                    val entry = FeedbackEntry(
                        timestamp = json.getLong("timestamp"),
                        sni = json.getString("sni"),
                        latency = json.getDouble("latency"),
                        throughput = json.getDouble("throughput"),
                        success = json.getBoolean("success")
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
     * Aggregate metrics by service class and routing decision.
     * Returns list of (svcIdx, routeIdx, success, avgThroughput).
     */
    private fun aggregateMetrics(entries: List<FeedbackEntry>): List<AggregatedFeedback> {
        // Group by (sni pattern -> infer svc/route)
        // For simplicity, aggregate all entries and use average
        val successCount = entries.count { it.success }
        val failCount = entries.size - successCount
        val avgThroughput = entries.map { it.throughput }.average().toFloat()
        
        // Update counters
        if (successCount > 0) {
            learnerState.incrementSuccess()
        }
        if (failCount > 0) {
            learnerState.incrementFail()
        }
        
        // Return aggregated update (default service/route)
        // In real implementation, would group by actual svc/route from inference
        return listOf(
            AggregatedFeedback(0, 1, successCount > failCount, avgThroughput) // Default: YouTube, Direct
        )
    }
    
    // Helper data class for aggregation
    private data class AggregatedFeedback(
        val svcIdx: Int,
        val routeIdx: Int,
        val success: Boolean,
        val throughput: Float
    )
    
    /**
     * Rebuild xray_reality_policy.json with updated learner state.
     */
    private fun rebuildPolicyJson() {
        try {
            val policyFile = File(applicationContext.filesDir, "xray_reality_policy.json")
            val temp = learnerState.getTemperature()
            val svcBiases = learnerState.getSvcBiases()
            val routeBiases = learnerState.getRouteBiases()
            val successCount = learnerState.getSuccessCount()
            val failCount = learnerState.getFailCount()
            val successRate = learnerState.getSuccessRate()
            
            // Build JSON object
            val policy = JSONObject().apply {
                put("version", "v10")
                put("timestamp", System.currentTimeMillis())
                put("learner", JSONObject().apply {
                    put("temperature", temp)
                    put("svcBiases", org.json.JSONArray(svcBiases.toList()))
                    put("routeBiases", org.json.JSONArray(routeBiases.toList()))
                    put("successCount", successCount)
                    put("failCount", failCount)
                    put("successRate", successRate)
                })
            }
            
            val json = policy.toString(2)
            
            FileWriter(policyFile).use { writer ->
                writer.write(json)
                writer.flush()
            }
            
            Log.d(TAG, "Rebuilt policy JSON: ${policyFile.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error rebuilding policy JSON: ${e.message}", e)
        }
    }
    
    /**
     * Trigger Xray-core reload command.
     */
    private fun triggerXrayReload() {
        try {
            // Option 1: Broadcast intent (preferred)
            val intent = Intent("com.hyperxray.REALITY_RELOAD")
            intent.setPackage(applicationContext.packageName)
            applicationContext.sendBroadcast(intent)
            Log.d(TAG, "Sent REALITY_RELOAD broadcast to Xray")
            
            // Option 2: Also try the existing reload action
            val reloadIntent = Intent("com.hyperxray.an.RELOAD_CONFIG")
            reloadIntent.setPackage(applicationContext.packageName)
            applicationContext.sendBroadcast(reloadIntent)
            Log.d(TAG, "Sent RELOAD_CONFIG broadcast")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger Xray reload: ${e.message}")
        }
    }
    
    companion object {
        /**
         * Schedule periodic work (30 minutes, can run when idle/charging).
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<RealityWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresCharging(false) // Can run without charging
                        .setRequiresDeviceIdle(false) // Can run when not idle
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "reality_worker",
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                workRequest
            )
            
            Log.i("RealityWorker", "Scheduled periodic learning work (30 min)")
        }
    }
}

