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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    
    // Lazy initialization to ensure applicationContext is ready
    // Use lateinit to allow proper exception propagation
    private lateinit var learner: OnDeviceLearner
    private lateinit var learnerState: LearnerState
    
    /**
     * Initialize learner components with explicit error handling.
     * @throws IllegalStateException if initialization fails
     */
    private fun initializeLearnerComponents() {
        if (!::learner.isInitialized) {
            try {
                learner = OnDeviceLearner(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize OnDeviceLearner: ${e.message}", e)
                throw IllegalStateException("OnDeviceLearner initialization failed", e)
            }
        }
        
        if (!::learnerState.isInitialized) {
            try {
                learnerState = LearnerState(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LearnerState: ${e.message}", e)
                throw IllegalStateException("LearnerState initialization failed", e)
            }
        }
    }
    
    data class FeedbackEntry(
        val timestamp: Long,
        val sni: String,
        val latency: Double,
        val throughput: Double,
        val success: Boolean,
        val svcClass: Int = 7,
        val routeDecision: Int = 0,
        val alpn: String = "h2",
        val rtt: Double? = null,
        val jitter: Double? = null,
        val networkType: String? = null,
        val hourOfDay: Int? = null,
        val dayOfWeek: Int? = null
    )
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "RealityWorker started")
            
            // Initialize components with explicit error handling
            try {
                initializeLearnerComponents()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to initialize learner components: ${e.message}", e)
                // Retry initialization failures with backoff
                return@withContext Result.retry()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during learner component initialization: ${e.message}", e)
                // Retry on unexpected errors
                return@withContext Result.retry()
            }
            
            // Read feedback logs
            val logFile = File(applicationContext.filesDir, "learner_log.jsonl")
            if (!logFile.exists()) {
                Log.d(TAG, "No feedback logs found, skipping update")
                return@withContext Result.success()
            }
            
            // Parse recent log entries (last 1M for optimal AI learning)
            // 1GB allows ~6-7 million entries, we process last 1M for better learning
            val maxEntries = 1_000_000
            val recentEntries = parseRecentLogs(logFile, maxEntries = maxEntries)
            
            if (recentEntries.isEmpty()) {
                Log.d(TAG, "No recent feedback entries found")
                return@withContext Result.success()
            }
            
            // Aggregate metrics
            val aggregated = aggregateMetrics(recentEntries)
            
            // Update learner with aggregated feedback
            for (feedback in aggregated) {
                try {
                    learner.updateWithFeedback(
                        success = feedback.success,
                        throughputKbps = feedback.throughput,
                        svcIdx = feedback.svcIdx,
                        routeIdx = feedback.routeIdx
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating feedback for svc=${feedback.svcIdx}, route=${feedback.routeIdx}: ${e.message}", e)
                    // Continue with next feedback entry
                }
            }
            
            // Save learner state
            try {
                learnerState.save()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving learner state: ${e.message}", e)
                // Continue - state updates are atomic via SharedPreferences
            }
            
            // Rebuild policy JSON
            try {
                rebuildPolicyJson()
            } catch (e: Exception) {
                Log.e(TAG, "Error rebuilding policy JSON: ${e.message}", e)
                // Continue - policy rebuild failure doesn't break the worker
            }
            
            // Trigger Xray reload
            try {
                triggerXrayReload()
            } catch (e: Exception) {
                Log.w(TAG, "Error triggering Xray reload: ${e.message}", e)
                // Continue - reload failure is non-critical
            }
            
            Log.i(TAG, "Updated learner from ${recentEntries.size} feedback entries, ${aggregated.size} aggregated updates")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "RealityWorker failed: ${e.message}", e)
            // Retry initialization failures with backoff
            if (e is IllegalStateException && (
                e.message?.contains("initialization failed") == true ||
                e.message?.contains("not initialized") == true
            )) {
                Log.w(TAG, "Initialization failed, will retry: ${e.message}")
                Result.retry() // Retry initialization failures
            } else {
                // Retry on transient failures
                Result.retry()
            }
        }
    }
    
    /**
     * Parse recent log entries from JSONL file.
     * Supports large files (up to 1GB) by reading last maxEntries efficiently.
     */
    private fun parseRecentLogs(logFile: File, maxEntries: Int): List<FeedbackEntry> {
        val entries = mutableListOf<FeedbackEntry>()
        
        try {
            val fileSize = logFile.length()
            Log.d(TAG, "Parsing log file: size=${fileSize} bytes (${fileSize / 1024.0 / 1024.0}MB), maxEntries=$maxEntries")
            
            // Read last maxEntries lines efficiently using sliding window
            // For large files (1M+ entries), we use a memory-efficient approach
            val lines = if (fileSize > 100 * 1024 * 1024) { // > 100MB
                // For large files, use sliding window to avoid loading entire file
                logFile.bufferedReader().use { reader ->
                    val slidingWindow = ArrayDeque<String>(maxEntries)
                    reader.forEachLine { line ->
                        slidingWindow.addLast(line)
                        if (slidingWindow.size > maxEntries) {
                            slidingWindow.removeFirst()
                        }
                    }
                    slidingWindow.toList()
                }
            } else {
                // For smaller files, read all and take last N
                logFile.bufferedReader().use { reader ->
                    reader.readLines().takeLast(maxEntries)
                }
            }
            
            Log.d(TAG, "Parsing ${lines.size} entries from log file (${fileSize / 1024.0 / 1024.0}MB)")
            
            lines.forEach { line ->
                try {
                    val json = JSONObject(line)
                    val entry = FeedbackEntry(
                        timestamp = json.getLong("timestamp"),
                        sni = json.getString("sni"),
                        latency = json.optDouble("latencyMs", json.optDouble("latency", 0.0)),
                        throughput = json.optDouble("throughputKbps", json.optDouble("throughput", 0.0)),
                        success = json.getBoolean("success"),
                        svcClass = json.optInt("svcClass", 7),
                        routeDecision = json.optInt("routeDecision", 0),
                        alpn = json.optString("alpn")?.takeIf { it.isNotEmpty() } ?: "h2",
                        rtt = if (json.has("rtt")) json.optDouble("rtt") else null,
                        jitter = if (json.has("jitter")) json.optDouble("jitter") else null,
                        networkType = json.optString("networkType")?.takeIf { it.isNotEmpty() },
                        hourOfDay = if (json.has("hourOfDay")) json.optInt("hourOfDay") else null,
                        dayOfWeek = if (json.has("dayOfWeek")) json.optInt("dayOfWeek") else null
                    )
                    entries.add(entry)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse log entry: ${e.message}")
                }
            }
            
            Log.d(TAG, "Successfully parsed ${entries.size} entries from log file")
        } catch (e: IOException) {
            Log.e(TAG, "Error reading log file: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing log file: ${e.message}", e)
        }
        
        return entries
    }
    
    /**
     * Aggregate metrics by service class and routing decision.
     * Returns list of (svcIdx, routeIdx, success, avgThroughput).
     * Groups entries by (svcClass, routeDecision) and aggregates metrics per group.
     */
    private fun aggregateMetrics(entries: List<FeedbackEntry>): List<AggregatedFeedback> {
        if (entries.isEmpty()) {
            return emptyList()
        }
        
        // Group entries by (svcClass, routeDecision)
        val grouped = entries.groupBy { entry ->
            // Clamp svcClass to valid range [0, 7]
            val svcClass = entry.svcClass.coerceIn(0, 7)
            // Clamp routeDecision to valid range [0, 2]
            val routeDecision = entry.routeDecision.coerceIn(0, 2)
            Pair(svcClass, routeDecision)
        }
        
        Log.d(TAG, "Aggregating ${entries.size} entries into ${grouped.size} groups")
        
        // Aggregate metrics per group
        val aggregated = grouped.map { (key, groupEntries) ->
            val (svcClass, routeDecision) = key
            val successCount = groupEntries.count { it.success }
            val failCount = groupEntries.size - successCount
            val avgThroughput = groupEntries.map { it.throughput }.average().toFloat()
            val avgLatency = groupEntries.map { it.latency }.average().toDouble()
            
            // Update global counters (only once per entry)
            // Note: We update counters here, but they're already updated per entry
            // This is just for aggregation statistics
            
            // Determine success based on majority
            val isSuccess = successCount > failCount
            
            Log.d(TAG, "Group (svc=$svcClass, route=$routeDecision): " +
                    "success=$successCount, fail=$failCount, " +
                    "avgThroughput=${avgThroughput}kbps, avgLatency=${avgLatency}ms")
            
            AggregatedFeedback(
                svcIdx = svcClass,
                routeIdx = routeDecision,
                success = isSuccess,
                throughput = avgThroughput
            )
        }
        
        // Update global success/fail counters efficiently for large datasets (1M+ entries)
        // For large datasets, we sample entries to update counters to avoid performance issues
        val totalSuccess = entries.count { it.success }
        val totalFail = entries.size - totalSuccess
        
        // Update counters efficiently based on dataset size
        // For 1M+ entries, we sample to update counters (every Nth entry) to maintain performance
        if (entries.size < 10000) {
            // For small datasets (<10K), update per entry (more accurate)
            for (entry in entries) {
                if (entry.success) {
                    learnerState.incrementSuccess()
                } else {
                    learnerState.incrementFail()
                }
            }
        } else {
            // For large datasets (1M+ entries), sample entries to update counters
            // This maintains accuracy while avoiding performance issues
            val sampleRate = entries.size / 10000 // Sample 1 in every N entries
            var sampled = 0
            for (entry in entries) {
                if (sampled % sampleRate == 0) {
                    if (entry.success) {
                        learnerState.incrementSuccess()
                    } else {
                        learnerState.incrementFail()
                    }
                }
                sampled++
            }
        }
        
        Log.d(TAG, "Aggregated ${aggregated.size} feedback groups from ${entries.size} entries " +
                "(success=$totalSuccess, fail=$totalFail)")
        
        return aggregated
    }
    
    // Helper data class for aggregation
    private data class AggregatedFeedback(
        val svcIdx: Int,
        val routeIdx: Int,
        val success: Boolean,
        val throughput: Float
    )
    
    /**
     * Rebuild xray_reality_policy_v*.json with updated learner state.
     * Creates versioned policy files for AI Insights tracking.
     */
    private fun rebuildPolicyJson() {
        try {
            val filesDir = applicationContext.filesDir
            val version = "v10"
            val timestamp = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val timestampStr = dateFormat.format(java.util.Date(timestamp))
            
            // Create versioned policy file name
            val policyFileName = "xray_reality_policy_$version.json"
            val policyFile = File(filesDir, policyFileName)
            
            val temp = learnerState.getTemperature()
            val svcBiases = learnerState.getSvcBiases()
            val routeBiases = learnerState.getRouteBiases()
            val successCount = learnerState.getSuccessCount()
            val failCount = learnerState.getFailCount()
            val successRate = learnerState.getSuccessRate()
            
            // Load existing feedback to generate policy entries
            val feedbackEntries = loadRecentFeedback()
            
            // Build policy array from feedback entries
            // Use most recent entries (last 5000) for policy display
            // Sort by timestamp (most recent first) and take unique SNIs (most recent route decision for each SNI)
            val recentEntries = feedbackEntries.sortedByDescending { it.timestamp }.take(5000)
            val sniToEntry = mutableMapOf<String, FeedbackEntry>()
            
            // Keep only the most recent entry for each SNI (already sorted by timestamp desc)
            recentEntries.forEach { entry ->
                if (!sniToEntry.containsKey(entry.sni)) {
                    sniToEntry[entry.sni] = entry
                }
            }
            
            // Sort by timestamp (most recent first) for display
            val sortedEntries = sniToEntry.values.sortedByDescending { it.timestamp }
            
            // Convert to policy array with individual timestamps (use same dateFormat)
            val policyArray = org.json.JSONArray()
            sortedEntries.forEach { entry ->
                val entryTimestampStr = dateFormat.format(java.util.Date(entry.timestamp))
                val policyEntry = JSONObject().apply {
                    put("sni", entry.sni)
                    put("svc_class", entry.svcClass)
                    put("route_decision", entry.routeDecision)
                    put("alpn", entry.alpn) // Use actual ALPN from feedback
                    put("timestamp", entryTimestampStr) // Use entry's own timestamp
                    put("latency_ms", entry.latency)
                    put("throughput_kbps", entry.throughput)
                    put("success", entry.success)
                }
                policyArray.put(policyEntry)
            }
            
            Log.d(TAG, "Policy array: ${policyArray.length()} unique SNIs from ${recentEntries.size} recent entries (total feedback: ${feedbackEntries.size})")
            
            // Build JSON object with policy array
            val policy = JSONObject().apply {
                put("version", version)
                put("timestamp", timestampStr)
                put("policy", policyArray)
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
            
            Log.d(TAG, "Rebuilt policy JSON: ${policyFile.absolutePath} with ${policyArray.length()} entries")
            
            // Clean up old policy files (keep last 5 versions)
            cleanupOldPolicyFiles(filesDir, version)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error rebuilding policy JSON: ${e.message}", e)
        }
    }
    
    /**
     * Load recent feedback entries from learner_log.jsonl.
     */
    private fun loadRecentFeedback(): List<FeedbackEntry> {
        val logFile = File(applicationContext.filesDir, "learner_log.jsonl")
        if (!logFile.exists()) {
            return emptyList()
        }
        
        return try {
            val maxEntries = 1_000_000 // Load last 1M entries for optimal AI learning
            val fileSize = logFile.length()
            Log.d(TAG, "Loading feedback: file size=${fileSize} bytes (${fileSize / 1024.0 / 1024.0}MB)")
            
            // Read last maxEntries lines efficiently
            val lines = logFile.bufferedReader().use { reader ->
                val allLines = reader.readLines()
                allLines.takeLast(maxEntries)
            }
            
            Log.d(TAG, "Loaded ${lines.size} entries from feedback log")
            
            lines.mapNotNull { line ->
                try {
                    val json = JSONObject(line)
                    FeedbackEntry(
                        timestamp = json.getLong("timestamp"),
                        sni = json.getString("sni"),
                        latency = json.optDouble("latencyMs", json.optDouble("latency", 0.0)),
                        throughput = json.optDouble("throughputKbps", json.optDouble("throughput", 0.0)),
                        success = json.getBoolean("success"),
                        svcClass = json.optInt("svcClass", 7),
                        routeDecision = json.optInt("routeDecision", 0),
                        alpn = json.optString("alpn")?.takeIf { it.isNotEmpty() } ?: "h2",
                        rtt = if (json.has("rtt")) json.optDouble("rtt") else null,
                        jitter = if (json.has("jitter")) json.optDouble("jitter") else null,
                        networkType = json.optString("networkType")?.takeIf { it.isNotEmpty() },
                        hourOfDay = if (json.has("hourOfDay")) json.optInt("hourOfDay") else null,
                        dayOfWeek = if (json.has("dayOfWeek")) json.optInt("dayOfWeek") else null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse feedback entry: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading feedback: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Clean up old policy files, keeping only last 5 versions.
     */
    private fun cleanupOldPolicyFiles(filesDir: File, currentVersion: String) {
        try {
            val policyFiles = filesDir.listFiles { _, name ->
                name.startsWith("xray_reality_policy_v") && name.endsWith(".json")
            }?.sortedBy { it.name }?.toList() ?: emptyList()
            
            if (policyFiles.size > 5) {
                val filesToDelete = policyFiles.dropLast(5)
                filesToDelete.forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted old policy file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old policy files: ${e.message}")
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
        @Volatile
        private var isScheduled = false
        @Volatile
        private var isScheduling = false
        private val scheduleLock = Any()
        private val scheduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        
        /**
         * Schedule periodic work (5 minutes for more frequent policy updates).
         * Handles IllegalStateException if WorkManager is not initialized yet.
         * Idempotent - safe to call multiple times.
         * Uses coroutines instead of Handler to avoid main looper issues.
         * Never throws - all errors are handled internally with retry logic.
         */
        fun schedule(context: Context) {
            synchronized(scheduleLock) {
                if (isScheduled) {
                    Log.d("RealityWorker", "Already scheduled, skipping")
                    return
                }
                if (isScheduling) {
                    Log.d("RealityWorker", "Scheduling in progress, skipping")
                    return
                }
                isScheduling = true
            }
            
            // Use coroutine scope for retries to avoid Handler/main looper issues
            scheduleScope.launch {
                try {
                    val success = attemptSchedule(context.applicationContext, 0)
                    if (!success) {
                        Log.w("RealityWorker", "Scheduling failed, will retry on next schedule() call")
                    }
                } catch (e: Exception) {
                    // Reset scheduling flag on unexpected error (attemptSchedule should handle this, but be safe)
                    synchronized(scheduleLock) {
                        isScheduling = false
                    }
                    Log.e("RealityWorker", "Unexpected error in schedule coroutine: ${e.message}", e)
                }
            }
        }
        
        private suspend fun attemptSchedule(ctx: Context, retryCount: Int): Boolean {
            return try {
                // Add initial delay on first attempt to give WorkManager time to initialize
                if (retryCount == 0) {
                    delay(100L) // Small delay to allow WorkManager.initialize() to complete
                }
                
                // Check if WorkManager is ready - this can throw IllegalStateException
                val workManager = try {
                    WorkManager.getInstance(ctx)
                } catch (e: IllegalStateException) {
                    // WorkManager not ready - retry with backoff
                    if (retryCount < 10) {
                        val backoffMs = when {
                            retryCount == 0 -> 200L // First retry: 200ms
                            retryCount < 3 -> 500L * retryCount // Next few: 500ms, 1000ms
                            else -> (1000L * (1 shl (retryCount - 2))).coerceAtMost(5000L) // Exponential backoff
                        }
                        Log.d("RealityWorker", "WorkManager not ready (attempt ${retryCount + 1}/10), waiting ${backoffMs}ms")
                        delay(backoffMs)
                        return attemptSchedule(ctx.applicationContext, retryCount + 1)
                    } else {
                        Log.e("RealityWorker", "WorkManager not ready after 10 attempts: ${e.message}")
                        throw e // Re-throw to be caught by outer catch
                    }
                }
                
                // WorkManager is ready - create and enqueue work request
                val workRequest = PeriodicWorkRequestBuilder<RealityWorker>(
                    5, TimeUnit.MINUTES // Changed from 30 minutes to 5 minutes for more frequent updates
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresCharging(false) // Can run without charging
                            .setRequiresDeviceIdle(false) // Can run when not idle
                            .build()
                    )
                    .build()
                
                workManager.enqueueUniquePeriodicWork(
                    "reality_worker",
                    ExistingPeriodicWorkPolicy.REPLACE, // Replace existing to update interval
                    workRequest
                )
                
                synchronized(scheduleLock) {
                    isScheduled = true
                    isScheduling = false
                }
                Log.i("RealityWorker", "Scheduled periodic learning work (5 min)")
                true
            } catch (e: IllegalStateException) {
                // WorkManager not initialized - retry with backoff
                if (retryCount < 10) {
                    val backoffMs = when {
                        retryCount == 0 -> 200L
                        retryCount < 3 -> 500L * retryCount
                        else -> (1000L * (1 shl (retryCount - 2))).coerceAtMost(5000L)
                    }
                    Log.w("RealityWorker", "WorkManager not initialized (attempt ${retryCount + 1}/10), retrying in ${backoffMs}ms: ${e.message}")
                    delay(backoffMs)
                    attemptSchedule(ctx.applicationContext, retryCount + 1)
                } else {
                    Log.e("RealityWorker", "Failed to schedule work after 10 retries: ${e.message}", e)
                    // Reset flags to allow manual retry later
                    synchronized(scheduleLock) {
                        isScheduled = false
                        isScheduling = false
                    }
                    false
                }
            } catch (e: Exception) {
                Log.e("RealityWorker", "Failed to schedule work: ${e.message}", e)
                // Reset flags to allow retry
                synchronized(scheduleLock) {
                    isScheduled = false
                    isScheduling = false
                }
                false
            }
        }
    }
}

