package com.hyperxray.an.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import com.hyperxray.an.core.network.TLSFeatureEncoder
import com.hyperxray.an.ml.TlsSniModel
import com.hyperxray.an.runtime.BanditRouter
import com.hyperxray.an.runtime.FeedbackManager
import com.hyperxray.an.runtime.RealityAdvisor
import com.hyperxray.an.ui.screens.log.extractSNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * TlsRuntimeWorker: Periodic WorkManager job for TLS SNI optimization.
 * 
 * Pulls SNI samples from logs, encodes features, runs inference,
 * logs results, triggers bandit update & advisor write.
 * Hot-reload hook to Xray (via file or local API).
 */
class TlsRuntimeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "TlsRuntimeWorker"
    
    private lateinit var tlsModel: TlsSniModel
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var banditRouter: BanditRouter
    private lateinit var realityAdvisor: RealityAdvisor
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "TlsRuntimeWorker started")
            
            // Initialize components (idempotent, with retry logic)
            initializeComponents()
            
            // Check if model is ready before processing
            if (!::tlsModel.isInitialized || !tlsModel.isReady()) {
                Log.w(TAG, "TlsSniModel not ready, skipping inference but continuing with other tasks")
                // Still update thresholds and write policy even without model
                updateAdaptiveThresholds()
                writePolicyAndProfile()
                return@withContext Result.success()
            }
            
            // Process SNI samples from logs
            val sniSamples = collectSniSamples()
            
            if (sniSamples.isEmpty()) {
                Log.d(TAG, "No SNI samples found, skipping inference")
                // Still update thresholds and write policy
                updateAdaptiveThresholds()
                writePolicyAndProfile()
                return@withContext Result.success()
            }
            
            // Process each SNI sample
            for (sniSample in sniSamples.take(10)) { // Limit to 10 per run
                try {
                    processSniSample(sniSample)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SNI sample $sniSample: ${e.message}", e)
                    // Continue with next sample
                }
            }
            
            // Update adaptive thresholds
            updateAdaptiveThresholds()
            
            // Write policy and profile files
            writePolicyAndProfile()
            
            // Schedule next run (15 minutes)
            scheduleNextRun()
            
            Log.i(TAG, "TlsRuntimeWorker completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "TlsRuntimeWorker failed: ${e.message}", e)
            // Retry initialization failures with exponential backoff
            if (e is IllegalStateException && (
                e.message?.contains("initialization failed") == true ||
                e.message?.contains("not initialized") == true ||
                e.message?.contains("not ready") == true
            )) {
                // Initialization failure - retry with backoff
                Log.w(TAG, "Initialization failed, will retry: ${e.message}")
                Result.retry()
            } else {
                // Transient failures - retry
                Result.retry()
            }
        }
    }
    
    /**
     * Initialize all components.
     * Idempotent - safe to call multiple times.
     * @throws IllegalStateException if critical initialization fails
     */
    private suspend fun initializeComponents() {
        // Initialize TlsSniModel with retry logic
        if (!::tlsModel.isInitialized) {
            tlsModel = TlsSniModel(applicationContext, mcPasses = 5)
        }
        
        // Retry initialization up to 3 times with exponential backoff
        var initSuccess = false
        var retryCount = 0
        val maxRetries = 3
        var lastException: Exception? = null
        
        while (!initSuccess && retryCount < maxRetries) {
            try {
                tlsModel.init()
                initSuccess = tlsModel.isReady()
                if (!initSuccess) {
                    throw IllegalStateException("TlsSniModel.init() completed but model is not ready")
                }
            } catch (e: Exception) {
                lastException = e
                retryCount++
                if (retryCount < maxRetries) {
                    val backoffMs = (1000L * (1 shl retryCount)).coerceAtMost(5000L)
                    Log.w(TAG, "TlsSniModel init failed (attempt $retryCount/$maxRetries), retrying in ${backoffMs}ms: ${e.message}")
                    kotlinx.coroutines.delay(backoffMs)
                } else {
                    Log.e(TAG, "TlsSniModel init failed after $maxRetries attempts: ${e.message}", e)
                    // Throw to propagate initialization failure - worker must retry
                    throw IllegalStateException("TlsSniModel initialization failed after $maxRetries attempts", e)
                }
            }
        }
        
        // Initialize FeedbackManager (safe - just file operations)
        try {
            if (!::feedbackManager.isInitialized) {
                val logDir = File(applicationContext.filesDir, "logs")
                feedbackManager = FeedbackManager(logDir, adaptiveWindowSize = 60)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FeedbackManager: ${e.message}", e)
            throw IllegalStateException("FeedbackManager initialization failed", e)
        }
        
        // Initialize BanditRouter (safe - pure Kotlin)
        try {
            if (!::banditRouter.isInitialized) {
                val epsilon = 0.08f // From config or default
                banditRouter = BanditRouter(epsilon = epsilon, learningRate = 0.1f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BanditRouter: ${e.message}", e)
            throw IllegalStateException("BanditRouter initialization failed", e)
        }
        
        // Initialize RealityAdvisor (safe - just file operations)
        try {
            if (!::realityAdvisor.isInitialized) {
                val outputDir = File(applicationContext.filesDir, "xray_config")
                realityAdvisor = RealityAdvisor(outputDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RealityAdvisor: ${e.message}", e)
            throw IllegalStateException("RealityAdvisor initialization failed", e)
        }
        
        Log.d(TAG, "Components initialized successfully (TlsSniModel ready: $initSuccess)")
    }
    
    /**
     * Collect SNI samples from recent logs.
     */
    private suspend fun collectSniSamples(): List<String> = withContext(Dispatchers.IO) {
        // TODO(v5): Read from actual log files or Xray API
        // For now, return empty list (will be populated from real logs)
        emptyList()
    }
    
    /**
     * Process a single SNI sample.
     */
    private suspend fun processSniSample(sni: String) {
        try {
            // Extract ALPN (default to h2)
            val alpn = "h2" // TODO(v5): Extract from logs
            
            // Encode features
            val features = TLSFeatureEncoder.encode(sni, alpn)
            
            // Run inference
            val (serviceType, routingDecision) = tlsModel.infer(features)
            
            // Get bandit routing decision (may override model)
            val finalRoute = banditRouter.selectRoute(serviceType)
            
            // Simulate network metrics (TODO(v5): Get from actual network layer)
            val latencyMs = simulateLatency(finalRoute)
            val throughputKbps = simulateThroughput(finalRoute)
            val success = latencyMs < 2000f // Success if latency < 2s
            
            // Record feedback
            feedbackManager.recordMetrics(
                sni = sni,
                serviceType = serviceType,
                routingDecision = finalRoute,
                latencyMs = latencyMs,
                throughputKbps = throughputKbps,
                success = success
            )
            
            // Update bandit with reward
            val reward = banditRouter.computeReward(latencyMs, throughputKbps, success)
            banditRouter.updateReward(finalRoute, reward)
            
            Log.d(TAG, "Processed SNI: $sni -> service=$serviceType, route=$finalRoute, reward=$reward")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SNI sample: ${e.message}", e)
        }
    }
    
    /**
     * Simulate latency based on routing decision (for testing).
     */
    private fun simulateLatency(route: Int): Float {
        return when (route) {
            0 -> 150f + (Math.random() * 100).toFloat() // Proxy: 150-250ms
            1 -> 50f + (Math.random() * 50).toFloat() // Direct: 50-100ms
            2 -> 80f + (Math.random() * 40).toFloat() // Optimized: 80-120ms
            else -> 200f
        }
    }
    
    /**
     * Simulate throughput based on routing decision (for testing).
     */
    private fun simulateThroughput(route: Int): Float {
        return when (route) {
            0 -> 2000f + (Math.random() * 1000).toFloat() // Proxy: 2-3 Mbps
            1 -> 5000f + (Math.random() * 2000).toFloat() // Direct: 5-7 Mbps
            2 -> 4000f + (Math.random() * 1500).toFloat() // Optimized: 4-5.5 Mbps
            else -> 1000f
        }
    }
    
    /**
     * Update adaptive thresholds from feedback.
     */
    private fun updateAdaptiveThresholds() {
        val latThreshold = feedbackManager.getLatencyThreshold()
        val tputThreshold = feedbackManager.getThroughputThreshold()
        
        Log.d(TAG, "Adaptive thresholds: latency=$latThreshold ms, throughput=$tputThreshold kbps")
    }
    
    /**
     * Write policy and profile files.
     */
    private fun writePolicyAndProfile() {
        val latThreshold = feedbackManager.getLatencyThreshold()
        val tputThreshold = feedbackManager.getThroughputThreshold()
        
        // Write policy with targets
        realityAdvisor.writePolicy(latThreshold, tputThreshold)
        
        // Write profile for most common service type (default to 0=YouTube)
        realityAdvisor.writeProfile(serviceType = 0, route = 1)
        
        // TODO(v5): Trigger Xray hot-reload if file-based reload is available
        triggerXrayReload()
    }
    
    /**
     * Trigger Xray configuration reload.
     */
    private fun triggerXrayReload() {
        // TODO(v5): Implement Xray reload hook
        // Option 1: Write to a file that Xray watches
        // Option 2: Call Xray API endpoint if available
        // Option 3: Send broadcast intent to TProxyService
        Log.d(TAG, "Xray reload triggered (placeholder)")
    }
    
    /**
     * Schedule next run.
     */
    private fun scheduleNextRun() {
        // WorkManager will automatically reschedule based on constraints
        // This is handled by the WorkRequest configuration
    }
    
    companion object {
        @Volatile
        private var isScheduled = false
        private val scheduleLock = Any()
        private val scheduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        
        /**
         * Schedule periodic work.
         * NEVER throws - all exceptions are caught and handled internally.
         * Handles IllegalStateException if WorkManager is not initialized yet.
         * Idempotent - safe to call multiple times.
         * Uses coroutines instead of Handler to avoid main looper issues.
         */
        fun schedule(context: Context) {
            synchronized(scheduleLock) {
                if (isScheduled) {
                    Log.d("TlsRuntimeWorker", "Already scheduled, skipping")
                    return
                }
            }
            
            // Use coroutine scope for retries to avoid Handler/main looper issues
            // Wrap in try-catch to ensure no exceptions escape this method
            try {
                scheduleScope.launch {
                    try {
                        attemptSchedule(context.applicationContext, 0)
                    } catch (e: Exception) {
                        // Catch any unexpected exceptions in the coroutine
                        Log.e("TlsRuntimeWorker", "Unexpected error in schedule coroutine: ${e.message}", e)
                        // Reset isScheduled flag to allow retry
                        synchronized(scheduleLock) {
                            isScheduled = false
                        }
                    }
                }
            } catch (e: Exception) {
                // Catch any exceptions from launch itself (shouldn't happen, but be safe)
                Log.e("TlsRuntimeWorker", "Failed to launch schedule coroutine: ${e.message}", e)
                synchronized(scheduleLock) {
                    isScheduled = false
                }
            }
        }
        
        /**
         * Attempt to schedule work with retry logic.
         * Returns true if successful, false if all retries exhausted.
         */
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
                        Log.d("TlsRuntimeWorker", "WorkManager not ready (attempt ${retryCount + 1}/10), waiting ${backoffMs}ms")
                        delay(backoffMs)
                        return attemptSchedule(ctx.applicationContext, retryCount + 1)
                    } else {
                        Log.e("TlsRuntimeWorker", "WorkManager not ready after 10 attempts: ${e.message}")
                        throw e // Re-throw to be caught by outer catch
                    }
                }
                
                // WorkManager is ready - create and enqueue work request
                val workRequest = PeriodicWorkRequestBuilder<TlsRuntimeWorker>(
                    15, java.util.concurrent.TimeUnit.MINUTES
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                
                workManager.enqueue(workRequest)
                synchronized(scheduleLock) {
                    isScheduled = true
                }
                Log.i("TlsRuntimeWorker", "Scheduled periodic TLS SNI optimization work")
                true
            } catch (e: IllegalStateException) {
                // WorkManager not initialized - retry with backoff
                if (retryCount < 10) {
                    val backoffMs = when {
                        retryCount == 0 -> 200L
                        retryCount < 3 -> 500L * retryCount
                        else -> (1000L * (1 shl (retryCount - 2))).coerceAtMost(5000L)
                    }
                    Log.w("TlsRuntimeWorker", "WorkManager not initialized (attempt ${retryCount + 1}/10), retrying in ${backoffMs}ms: ${e.message}")
                    delay(backoffMs)
                    attemptSchedule(ctx.applicationContext, retryCount + 1)
                } else {
                    Log.e("TlsRuntimeWorker", "Failed to schedule work after 10 retries: ${e.message}", e)
                    // Reset flag to allow manual retry later
                    synchronized(scheduleLock) {
                        isScheduled = false
                    }
                    false
                }
            } catch (e: Exception) {
                Log.e("TlsRuntimeWorker", "Failed to schedule work: ${e.message}", e)
                // Reset flag to allow retry
                synchronized(scheduleLock) {
                    isScheduled = false
                }
                false
            }
        }
    }
}

