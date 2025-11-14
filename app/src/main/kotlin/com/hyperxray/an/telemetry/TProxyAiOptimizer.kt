package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.Safeguard
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import java.time.Instant

/**
 * TProxyAiOptimizer: AI-powered optimizer for TProxy configuration parameters.
 * 
 * Uses machine learning to dynamically adjust TProxy settings (MTU, buffer sizes, timeouts)
 * based on real-time network performance metrics.
 * 
 * Optimization targets:
 * - Maximize throughput
 * - Minimize latency (RTT)
 * - Minimize packet loss
 * - Optimize resource usage
 */
class TProxyAiOptimizer(
    private val context: Context,
    private val prefs: Preferences,
    private val deepModel: DeepPolicyModel = DeepPolicyModel(context),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val TAG = "TProxyAiOptimizer"
    
    // Current optimized configuration
    data class TProxyOptimizedConfig(
        val mtu: Int,
        val taskStackSize: Int,
        val tcpBufferSize: Int,
        val limitNofile: Int,
        val connectTimeout: Int,
        val readWriteTimeout: Int,
        val socks5Pipeline: Boolean,
        val tunnelMultiQueue: Boolean,
        val timestamp: Instant = Instant.now()
    )
    
    // Optimization result
    data class OptimizationResult(
        val config: TProxyOptimizedConfig,
        val expectedImprovement: Double,
        val metrics: TelemetryMetrics?,
        val success: Boolean,
        val error: String? = null
    )
    
    private var currentConfig: TProxyOptimizedConfig? = null
    private var optimizationJob: Job? = null
    private var isOptimizing = false
    private var metricsCollector: TProxyMetricsCollector? = null
    
    // Configuration bounds (safe ranges)
    private val configBounds = ConfigBounds(
        mtuMin = 1280,
        mtuMax = 9000,
        taskStackMin = 16384,
        taskStackMax = 262144,
        tcpBufferMin = 8192,
        tcpBufferMax = 65432, // Max TCP_SND_BUF limit
        limitNofileMin = 1024,
        limitNofileMax = 1048576,
        connectTimeoutMin = 1000,
        connectTimeoutMax = 30000,
        readWriteTimeoutMin = 5000,
        readWriteTimeoutMax = 300000
    )
    
    data class ConfigBounds(
        val mtuMin: Int,
        val mtuMax: Int,
        val taskStackMin: Int,
        val taskStackMax: Int,
        val tcpBufferMin: Int,
        val tcpBufferMax: Int,
        val limitNofileMin: Int,
        val limitNofileMax: Int,
        val connectTimeoutMin: Int,
        val connectTimeoutMax: Int,
        val readWriteTimeoutMin: Int,
        val readWriteTimeoutMax: Int
    )
    
    /**
     * Start continuous optimization loop.
     * Collects metrics periodically and adjusts TProxy configuration.
     * Uses native hev-socks5-tunnel stats for accurate performance metrics.
     */
    fun startOptimization(
        coreStatsState: CoreStatsState? = null,
        optimizationIntervalMs: Long = 30000L // Optimize every 30 seconds
    ) {
        if (isOptimizing) {
            Log.w(TAG, "Optimization already running")
            return
        }
        
        isOptimizing = true
        metricsCollector = TProxyMetricsCollector(context, prefs)
        
        optimizationJob = scope.launch {
            Log.i(TAG, "Starting TProxy AI optimization loop (interval: ${optimizationIntervalMs}ms)")
            Log.i(TAG, "Using native hev-socks5-tunnel stats for accurate metrics")
            
            // Initial delay to allow TProxy to start and collect baseline metrics
            delay(5000L)
            
            while (isActive && isOptimizing) {
                try {
                    // Collect current metrics (includes native TProxy stats)
                    val metrics = metricsCollector?.collectMetrics(coreStatsState)
                    
                    if (metrics != null) {
                        // Run optimization with error handling
                        val result = try {
                            optimizeConfiguration(metrics, coreStatsState)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in optimizeConfiguration: ${e.javaClass.simpleName}: ${e.message}", e)
                            // Return failure result to continue loop
                            OptimizationResult(
                                config = getCurrentConfig(),
                                expectedImprovement = 0.0,
                                metrics = metrics,
                                success = false,
                                error = "Optimization failed: ${e.message}"
                            )
                        }
                        
                        if (result.success) {
                            try {
                                // Apply optimized configuration
                                val needsReload = applyConfiguration(result.config)
                                
                                // Notify callback if configuration was applied
                                onConfigurationApplied?.invoke(result.config, needsReload)
                                
                                Log.i(TAG, "Applied optimized configuration: MTU=${result.config.mtu}, " +
                                        "Buffer=${result.config.tcpBufferSize}, " +
                                        "Pipeline=${result.config.socks5Pipeline}, " +
                                        "MultiQueue=${result.config.tunnelMultiQueue}, " +
                                        "Expected improvement: ${result.expectedImprovement}%, " +
                                        "NeedsReload=$needsReload")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error applying configuration: ${e.javaClass.simpleName}: ${e.message}", e)
                                // Continue loop even if application fails
                            }
                        } else {
                            Log.d(TAG, "Optimization skipped: ${result.error}")
                        }
                    } else {
                        Log.w(TAG, "Failed to collect metrics, skipping optimization cycle")
                    }
                    
                    // Wait before next optimization cycle
                    delay(optimizationIntervalMs)
                } catch (e: Exception) {
                    // Catch-all for any unexpected exceptions in loop
                    Log.e(TAG, "Error in optimization loop: ${e.javaClass.simpleName}: ${e.message}", e)
                    // Continue loop - don't let exceptions kill the optimization thread
                    try {
                        delay(optimizationIntervalMs)
                    } catch (delayEx: Exception) {
                        Log.e(TAG, "Error in delay, breaking loop", delayEx)
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Stop optimization loop.
     */
    fun stopOptimization() {
        isOptimizing = false
        isWarmedUp = false // Reset warm-up state
        optimizationJob?.cancel()
        optimizationJob = null
        metricsCollector = null
        Log.i(TAG, "Stopped TProxy AI optimization")
    }
    
    /**
     * Optimize TProxy configuration based on current metrics.
     */
    fun optimizeConfiguration(
        metrics: TelemetryMetrics,
        coreStatsState: CoreStatsState? = null
    ): OptimizationResult {
        return try {
            Log.d(TAG, "Optimizing TProxy configuration based on metrics: " +
                    "throughput=${metrics.throughput}, rtt=${metrics.rttP95}, " +
                    "loss=${metrics.loss}, handshake=${metrics.handshakeTime}")
            
            // Extract features for AI model
            val features = extractFeatures(metrics, coreStatsState)
            
            // Get AI recommendation
            val aiRecommendation = getAiRecommendation(features)
            
            // Create optimized configuration
            val optimizedConfig = createOptimizedConfig(aiRecommendation, metrics)
            
            // Validate configuration
            val validatedConfig = validateConfig(optimizedConfig)
            
            // Calculate expected improvement
            val expectedImprovement = calculateExpectedImprovement(metrics, validatedConfig)
            
            // Check if improvement is significant enough to apply
            // Also check if config actually changed
            val currentConfig = getCurrentConfig()
            val configChanged = validatedConfig.mtu != currentConfig.mtu ||
                    validatedConfig.tcpBufferSize != currentConfig.tcpBufferSize ||
                    validatedConfig.connectTimeout != currentConfig.connectTimeout ||
                    validatedConfig.readWriteTimeout != currentConfig.readWriteTimeout ||
                    validatedConfig.socks5Pipeline != currentConfig.socks5Pipeline ||
                    validatedConfig.tunnelMultiQueue != currentConfig.tunnelMultiQueue
            
            if (configChanged && expectedImprovement > 2.0) { // Only apply if >2% improvement expected and config changed
                OptimizationResult(
                    config = validatedConfig,
                    expectedImprovement = expectedImprovement,
                    metrics = metrics,
                    success = true
                )
            } else {
                if (!configChanged) {
                    Log.d(TAG, "Configuration unchanged, keeping current config")
                } else {
                    Log.d(TAG, "Expected improvement ($expectedImprovement%) is too small, keeping current config")
                }
                OptimizationResult(
                    config = validatedConfig,
                    expectedImprovement = expectedImprovement,
                    metrics = metrics,
                    success = false,
                    error = if (!configChanged) "Config unchanged" else "Improvement too small"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing configuration", e)
            OptimizationResult(
                config = getCurrentConfig(),
                expectedImprovement = 0.0,
                metrics = metrics,
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Extract features from metrics for AI model input.
     */
    private fun extractFeatures(
        metrics: TelemetryMetrics,
        coreStatsState: CoreStatsState?
    ): DoubleArray {
        // Normalize features to [0, 1] range for AI model
        // Features: throughput, rtt, loss, handshake, uplink, downlink, goroutines, memory
        val features = DoubleArray(16) { 0.0 }
        
        // Throughput: normalize to 0-100 Mbps range
        features[0] = (metrics.throughput / (100 * 1024 * 1024)).coerceIn(0.0, 1.0)
        
        // RTT: normalize to 0-500ms range
        features[1] = (metrics.rttP95 / 500.0).coerceIn(0.0, 1.0)
        
        // Loss: already 0-1 range
        features[2] = metrics.loss.coerceIn(0.0, 1.0)
        
        // Handshake time: normalize to 0-2000ms range
        features[3] = (metrics.handshakeTime / 2000.0).coerceIn(0.0, 1.0)
        
        // Jitter (estimated from RTT variance)
        features[4] = (metrics.rttP95 * 0.1 / 50.0).coerceIn(0.0, 1.0)
        
        // Uplink: normalize to 0-100 Mbps range
        if (coreStatsState != null) {
            val uplinkMbps = (coreStatsState.uplink * 8.0) / (1024 * 1024)
            features[5] = (uplinkMbps / 100.0).coerceIn(0.0, 1.0)
            
            // Downlink: normalize to 0-100 Mbps range
            val downlinkMbps = (coreStatsState.downlink * 8.0) / (1024 * 1024)
            features[6] = (downlinkMbps / 100.0).coerceIn(0.0, 1.0)
            
            // Goroutines: normalize to 0-1000 range
            features[7] = (coreStatsState.numGoroutine / 1000.0).coerceIn(0.0, 1.0)
            
            // Memory usage: normalize to 0-1GB range
            features[8] = (coreStatsState.alloc / (1024 * 1024 * 1024.0)).coerceIn(0.0, 1.0)
        }
        
        // Current configuration features (normalized)
        val currentConfig = getCurrentConfig()
        features[9] = ((currentConfig.mtu - configBounds.mtuMin) / (configBounds.mtuMax - configBounds.mtuMin).toDouble()).coerceIn(0.0, 1.0)
        features[10] = ((currentConfig.tcpBufferSize - configBounds.tcpBufferMin) / (configBounds.tcpBufferMax - configBounds.tcpBufferMin).toDouble()).coerceIn(0.0, 1.0)
        features[11] = ((currentConfig.connectTimeout - configBounds.connectTimeoutMin) / (configBounds.connectTimeoutMax - configBounds.connectTimeoutMin).toDouble()).coerceIn(0.0, 1.0)
        
        // Network conditions (placeholder - can be enhanced with actual network info)
        features[12] = 0.5 // ASN (normalized)
        features[13] = 0.5 // Time of day (normalized)
        features[14] = 0.5 // Signal strength (normalized)
        features[15] = 0.5 // Network type (normalized)
        
        return features
    }
    
    // Track warm-up state for first inference
    private var isWarmedUp = false
    
    /**
     * Get AI recommendation for TProxy configuration.
     */
    private fun getAiRecommendation(features: DoubleArray): IntArray {
        return try {
            if (!deepModel.isModelLoaded()) {
                Log.w(TAG, "AI model not loaded, using heuristic optimization")
                return getHeuristicRecommendation(features)
            }
            
            // Validate features before inference
            if (features.isEmpty()) {
                Log.w(TAG, "Features array is empty, using heuristic")
                return getHeuristicRecommendation(features)
            }
            
            // Warm-up: run inference once before first real use (helps with cold start)
            if (!isWarmedUp) {
                try {
                    Log.d(TAG, "Warming up model with first inference")
                    val warmupOutput = deepModel.inferRaw(features)
                    if (warmupOutput.size >= 5) {
                        isWarmedUp = true
                        Log.d(TAG, "Model warm-up successful (output size: ${warmupOutput.size})")
                    } else {
                        Log.w(TAG, "Model warm-up failed (output size: ${warmupOutput.size}), will retry on next cycle")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Model warm-up failed, will retry on next cycle: ${e.message}")
                    // Don't fail here, just log and continue
                }
            }
            
            // Use AI model to predict optimal configuration
            // Model outputs 5 actions (we'll map to configuration adjustments)
            val modelOutput = try {
                deepModel.inferRaw(features)
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed: ${e.javaClass.simpleName}: ${e.message}", e)
                // Return empty array to trigger heuristic fallback
                DoubleArray(0)
            }
            
            // Validate output size - must be exactly 5 or more
            if (modelOutput.isEmpty()) {
                Log.w(TAG, "Model output is empty, using heuristic")
                return getHeuristicRecommendation(features)
            }
            
            if (modelOutput.size < 5) {
                Log.w(TAG, "Model output size ${modelOutput.size} < 5, using heuristic")
                return getHeuristicRecommendation(features)
            }
            
            // Validate output values (check for NaN/Inf)
            var hasInvalidValues = false
            for (i in 0 until minOf(5, modelOutput.size)) {
                val value = modelOutput[i]
                if (value.isNaN() || value.isInfinite()) {
                    Log.w(TAG, "Model output[$i] is invalid (NaN/Inf: $value), clamping to 0.5")
                    modelOutput[i] = 0.5
                    hasInvalidValues = true
                }
            }
            if (hasInvalidValues) {
                Log.w(TAG, "Model output contained invalid values, clamped to safe defaults")
            }
            
            // Map model output to configuration adjustments
            // Output is probabilities/scores for 5 actions
            // We interpret them as: -1 (decrease), 0 (no change), +1 (increase)
            // For boolean flags: >0.5 = enable, <=0.5 = disable
            val adjustments = IntArray(5)
            
            // MTU adjustment: map [0,1] to [-1, 0, 1]
            adjustments[0] = when {
                modelOutput[0] > 0.66 -> 1  // Increase MTU
                modelOutput[0] < 0.33 -> -1 // Decrease MTU
                else -> 0                   // No change
            }
            
            // Buffer adjustment
            adjustments[1] = when {
                modelOutput[1] > 0.66 -> 1  // Increase buffer
                modelOutput[1] < 0.33 -> -1 // Decrease buffer
                else -> 0                   // No change
            }
            
            // Timeout adjustment
            adjustments[2] = when {
                modelOutput[2] > 0.66 -> 1  // Increase timeout
                modelOutput[2] < 0.33 -> -1 // Decrease timeout
                else -> 0                   // No change
            }
            
            // Pipeline: enable if probability > 0.5
            adjustments[3] = if (modelOutput[3] > 0.5) 1 else 0
            
            // MultiQueue: enable if probability > 0.5
            adjustments[4] = if (modelOutput[4] > 0.5) 1 else 0
            
            Log.d(TAG, "AI recommendations: MTU=${adjustments[0]}, Buffer=${adjustments[1]}, " +
                    "Timeout=${adjustments[2]}, Pipeline=${adjustments[3]}, MultiQueue=${adjustments[4]}")
            
            adjustments
        } catch (e: Exception) {
            Log.e(TAG, "Error getting AI recommendation: ${e.javaClass.simpleName}: ${e.message}", e)
            // Always return heuristic on any exception
            getHeuristicRecommendation(features)
        }
    }
    
    /**
     * Get heuristic recommendation when AI model is unavailable.
     */
    private fun getHeuristicRecommendation(features: DoubleArray): IntArray {
        // Heuristic: adjust based on throughput and latency
        val adjustments = IntArray(5)
        val throughput = features[0]
        val rtt = features[1]
        val loss = features[2]
        
        // High throughput + low latency: increase MTU and buffer
        if (throughput > 0.7 && rtt < 0.3) {
            adjustments[0] = 1 // Increase MTU
            adjustments[1] = 1 // Increase buffer
            adjustments[3] = 1 // Enable pipeline
            adjustments[4] = 1 // Enable multi-queue
        }
        // High latency: reduce timeouts, enable optimizations
        else if (rtt > 0.5) {
            adjustments[2] = -1 // Decrease timeout
            adjustments[3] = 1 // Enable pipeline
        }
        // High loss: reduce MTU, increase buffer
        else if (loss > 0.05) {
            adjustments[0] = -1 // Decrease MTU
            adjustments[1] = 1 // Increase buffer
        }
        
        return adjustments
    }
    
    /**
     * Create optimized configuration from AI recommendation.
     */
    private fun createOptimizedConfig(
        recommendation: IntArray,
        metrics: TelemetryMetrics
    ): TProxyOptimizedConfig {
        val current = getCurrentConfig()
        
        // Apply recommendations with bounds checking
        val newMtu = clamp(
            current.mtu + (recommendation[0] * 500),
            configBounds.mtuMin,
            configBounds.mtuMax
        )
        
        val newBuffer = clamp(
            current.tcpBufferSize + (recommendation[1] * 8192),
            configBounds.tcpBufferMin,
            configBounds.tcpBufferMax
        )
        
        val newTimeout = clamp(
            current.connectTimeout + (recommendation[2] * 1000),
            configBounds.connectTimeoutMin,
            configBounds.connectTimeoutMax
        )
        
        val newPipeline = recommendation[3] == 1
        val newMultiQueue = recommendation[4] == 1
        
        // Adaptive task stack based on throughput
        val newTaskStack = if (metrics.throughput > 10 * 1024 * 1024) {
            // High throughput: increase stack size
            clamp(current.taskStackSize + 16384, configBounds.taskStackMin, configBounds.taskStackMax)
        } else {
            current.taskStackSize
        }
        
        // Adaptive nofile limit based on connections
        val newNofile = if (metrics.throughput > 5 * 1024 * 1024) {
            // High throughput: increase file descriptor limit
            clamp(current.limitNofile + 1024, configBounds.limitNofileMin, configBounds.limitNofileMax)
        } else {
            current.limitNofile
        }
        
        // Adaptive read/write timeout based on RTT
        val newReadWriteTimeout = if (metrics.rttP95 > 100) {
            // High RTT: increase timeout
            clamp(current.readWriteTimeout + 5000, configBounds.readWriteTimeoutMin, configBounds.readWriteTimeoutMax)
        } else {
            current.readWriteTimeout
        }
        
        return TProxyOptimizedConfig(
            mtu = newMtu,
            taskStackSize = newTaskStack,
            tcpBufferSize = newBuffer,
            limitNofile = newNofile,
            connectTimeout = newTimeout,
            readWriteTimeout = newReadWriteTimeout,
            socks5Pipeline = newPipeline,
            tunnelMultiQueue = newMultiQueue
        )
    }
    
    /**
     * Validate configuration against bounds and safety constraints.
     */
    private fun validateConfig(config: TProxyOptimizedConfig): TProxyOptimizedConfig {
        return config.copy(
            mtu = clamp(config.mtu, configBounds.mtuMin, configBounds.mtuMax),
            taskStackSize = clamp(config.taskStackSize, configBounds.taskStackMin, configBounds.taskStackMax),
            tcpBufferSize = clamp(config.tcpBufferSize, configBounds.tcpBufferMin, configBounds.tcpBufferMax),
            limitNofile = clamp(config.limitNofile, configBounds.limitNofileMin, configBounds.limitNofileMax),
            connectTimeout = clamp(config.connectTimeout, configBounds.connectTimeoutMin, configBounds.connectTimeoutMax),
            readWriteTimeout = clamp(config.readWriteTimeout, configBounds.readWriteTimeoutMin, configBounds.readWriteTimeoutMax)
        )
    }
    
    /**
     * Calculate expected performance improvement.
     */
    private fun calculateExpectedImprovement(
        currentMetrics: TelemetryMetrics,
        newConfig: TProxyOptimizedConfig
    ): Double {
        val current = getCurrentConfig()
        
        // Estimate improvement based on configuration changes
        var improvement = 0.0
        
        // MTU increase → throughput improvement
        if (newConfig.mtu > current.mtu) {
            val mtuIncrease = (newConfig.mtu - current.mtu) / current.mtu.toDouble()
            improvement += mtuIncrease * 10.0 // ~10% improvement per MTU increase
        }
        
        // Buffer increase → latency reduction
        if (newConfig.tcpBufferSize > current.tcpBufferSize) {
            val bufferIncrease = (newConfig.tcpBufferSize - current.tcpBufferSize) / current.tcpBufferSize.toDouble()
            improvement += bufferIncrease * 5.0 // ~5% improvement per buffer increase
        }
        
        // Pipeline → throughput improvement
        if (newConfig.socks5Pipeline && !current.socks5Pipeline) {
            improvement += 15.0 // ~15% improvement from pipeline
        }
        
        // Multi-queue → throughput improvement
        if (newConfig.tunnelMultiQueue && !current.tunnelMultiQueue) {
            improvement += 10.0 // ~10% improvement from multi-queue
        }
        
        return improvement
    }
    
    /**
     * Apply optimized configuration to Preferences.
     * Returns true if configuration was applied and TProxy should be reloaded.
     */
    private fun applyConfiguration(config: TProxyOptimizedConfig): Boolean {
        return try {
            val oldConfig = getCurrentConfig()
            
            prefs.tunnelMtuCustom = config.mtu
            prefs.taskStackSizeCustom = config.taskStackSize
            prefs.tcpBufferSize = config.tcpBufferSize
            prefs.limitNofile = config.limitNofile
            prefs.connectTimeout = config.connectTimeout
            prefs.readWriteTimeout = config.readWriteTimeout
            prefs.socks5Pipeline = config.socks5Pipeline
            prefs.tunnelMultiQueue = config.tunnelMultiQueue
            
            currentConfig = config
            
            // Check if TProxy-relevant parameters changed (those that require reload)
            val needsReload = config.mtu != oldConfig.mtu ||
                    config.taskStackSize != oldConfig.taskStackSize ||
                    config.tcpBufferSize != oldConfig.tcpBufferSize ||
                    config.limitNofile != oldConfig.limitNofile ||
                    config.connectTimeout != oldConfig.connectTimeout ||
                    config.readWriteTimeout != oldConfig.readWriteTimeout ||
                    config.socks5Pipeline != oldConfig.socks5Pipeline ||
                    config.tunnelMultiQueue != oldConfig.tunnelMultiQueue
            
            Log.i(TAG, "Applied optimized TProxy configuration: " +
                    "MTU=${config.mtu}, Buffer=${config.tcpBufferSize}, " +
                    "Pipeline=${config.socks5Pipeline}, MultiQueue=${config.tunnelMultiQueue}, " +
                    "NeedsReload=$needsReload")
            
            needsReload
        } catch (e: Exception) {
            Log.e(TAG, "Error applying configuration", e)
            false
        }
    }
    
    /**
     * Callback for when configuration is applied.
     * Can be set to trigger TProxy reload.
     */
    var onConfigurationApplied: ((TProxyOptimizedConfig, Boolean) -> Unit)? = null
    
    /**
     * Get current configuration from Preferences.
     */
    private fun getCurrentConfig(): TProxyOptimizedConfig {
        return TProxyOptimizedConfig(
            mtu = prefs.tunnelMtuCustom,
            taskStackSize = prefs.taskStackSizeCustom,
            tcpBufferSize = prefs.tcpBufferSize,
            limitNofile = prefs.limitNofile,
            connectTimeout = prefs.connectTimeout,
            readWriteTimeout = prefs.readWriteTimeout,
            socks5Pipeline = prefs.socks5Pipeline,
            tunnelMultiQueue = prefs.tunnelMultiQueue
        )
    }
    
    /**
     * Clamp value between min and max.
     */
    private fun clamp(value: Int, min: Int, max: Int): Int {
        return max(min, min(max, value))
    }
    
    /**
     * Get current optimized configuration.
     */
    fun getCurrentOptimizedConfig(): TProxyOptimizedConfig? {
        return currentConfig ?: getCurrentConfig().let {
            currentConfig = it
            it
        }
    }
    
    /**
     * Check if optimization is running.
     */
    fun isOptimizing(): Boolean {
        return isOptimizing
    }
}

