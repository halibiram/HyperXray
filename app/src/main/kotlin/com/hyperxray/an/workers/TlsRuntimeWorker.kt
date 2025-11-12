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
            
            // Initialize components
            initializeComponents()
            
            // Process SNI samples from logs
            val sniSamples = collectSniSamples()
            
            if (sniSamples.isEmpty()) {
                Log.d(TAG, "No SNI samples found, skipping inference")
                return@withContext Result.success()
            }
            
            // Process each SNI sample
            for (sniSample in sniSamples.take(10)) { // Limit to 10 per run
                processSniSample(sniSample)
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
            Result.retry() // Retry on failure
        }
    }
    
    /**
     * Initialize all components.
     */
    private suspend fun initializeComponents() {
        tlsModel = TlsSniModel(applicationContext, mcPasses = 5)
        tlsModel.init()
        
        val logDir = File(applicationContext.filesDir, "logs")
        feedbackManager = FeedbackManager(logDir, adaptiveWindowSize = 60)
        
        val epsilon = 0.08f // From config or default
        banditRouter = BanditRouter(epsilon = epsilon, learningRate = 0.1f)
        
        val outputDir = File(applicationContext.filesDir, "xray_config")
        realityAdvisor = RealityAdvisor(outputDir)
        
        Log.d(TAG, "Components initialized")
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
        /**
         * Schedule periodic work.
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<TlsRuntimeWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i("TlsRuntimeWorker", "Scheduled periodic TLS SNI optimization work")
        }
    }
}

