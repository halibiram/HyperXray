package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * XrayCoreAiOptimizer: AI-powered optimizer for Xray-core configuration.
 * 
 * Uses machine learning to dynamically optimize Xray-core settings including:
 * - Routing rules and domain strategy
 * - Outbound selection and load balancing
 * - Policy settings (buffer sizes, connection limits)
 * - DNS configuration
 * - Performance tuning based on real-time metrics
 * 
 * Optimization targets:
 * - Maximize throughput
 * - Minimize latency (RTT)
 * - Minimize packet loss
 * - Optimize resource usage (CPU, memory)
 * - Improve connection stability
 */
class XrayCoreAiOptimizer(
    private val context: Context,
    private val prefs: Preferences,
    private val deepModel: DeepPolicyModel = DeepPolicyModel(context),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val TAG = "XrayCoreAiOptimizer"
    
    /**
     * Optimized Xray core configuration
     */
    data class XrayOptimizedConfig(
        val domainStrategy: String, // "AsIs", "IPIfNonMatch", "IPOnDemand"
        val domainMatcher: String, // "linear", "hybrid", "mph"
        val routingRules: List<RoutingRule>,
        val policyLevels: Map<String, PolicyLevel>,
        val dnsConfig: DnsConfig,
        val outboundSelection: OutboundSelection,
        val timestamp: Instant = Instant.now()
    )
    
    /**
     * Routing rule for domain/IP matching
     */
    data class RoutingRule(
        val type: String, // "field", "domain", "ip"
        val domain: List<String>? = null,
        val ip: List<String>? = null,
        val outboundTag: String,
        val enabled: Boolean = true
    )
    
    /**
     * Policy level configuration
     */
    data class PolicyLevel(
        val connectionIdle: Int, // seconds
        val handshake: Int, // seconds
        val uplinkOnly: Int, // seconds
        val downlinkOnly: Int, // seconds
        val statsUserUplink: Boolean,
        val statsUserDownlink: Boolean,
        val bufferSize: Int // bytes
    )
    
    /**
     * DNS configuration
     */
    data class DnsConfig(
        val servers: List<String>,
        val queryStrategy: String, // "UseIP", "UseIPv4", "UseIPv6"
        val cacheSize: Int,
        val cacheStrategy: String // "cache", "cacheIfSuccess"
    )
    
    /**
     * Outbound selection strategy
     */
    data class OutboundSelection(
        val strategy: String, // "random", "leastPing", "leastLoad"
        val checkInterval: Int, // seconds
        val failover: Boolean
    )
    
    /**
     * Optimization result
     */
    data class OptimizationResult(
        val config: XrayOptimizedConfig,
        val expectedImprovement: Double,
        val metrics: XrayMetrics?,
        val success: Boolean,
        val needsReload: Boolean,
        val error: String? = null
    )
    
    /**
     * Xray core metrics
     */
    data class XrayMetrics(
        val throughput: Long, // bytes/sec
        val rtt: Double, // milliseconds
        val loss: Double, // 0.0-1.0
        val connections: Int,
        val memoryUsage: Long, // bytes
        val cpuUsage: Double, // 0.0-1.0
        val goroutines: Int,
        val jitter: Double = 0.0, // milliseconds - RTT variance
        val connectionQuality: Double = 0.0, // 0.0-1.0 - overall quality score
        val timestamp: Instant = Instant.now()
    )
    
    private var currentConfig: XrayOptimizedConfig? = null
    private var optimizationJob: Job? = null
    private var isOptimizing = false
    private var coreStatsClient: CoreStatsClient? = null
    
    // Metrics history for quality tracking
    private val rttHistory: MutableList<Double> = mutableListOf()
    private val lossHistory: MutableList<Double> = mutableListOf()
    private val throughputHistory: MutableList<Long> = mutableListOf()
    private val maxHistorySize = 20 // Keep last 20 measurements
    
    /**
     * Start continuous optimization loop.
     * Collects metrics from Xray core and adjusts configuration.
     */
    fun startOptimization(
        apiPort: Int,
        optimizationIntervalMs: Long = 60000L // Optimize every 60 seconds
    ) {
        if (isOptimizing) {
            Log.w(TAG, "Optimization already running")
            return
        }
        
        isOptimizing = true
        
        // Initialize stats client
        try {
            coreStatsClient = CoreStatsClient.create("127.0.0.1", apiPort)
            Log.i(TAG, "Connected to Xray core stats API on port $apiPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Xray core stats API", e)
            isOptimizing = false
            return
        }
        
        optimizationJob = scope.launch {
            Log.i(TAG, "Starting Xray core AI optimization loop (interval: ${optimizationIntervalMs}ms)")
            
            // Initial delay to allow Xray core to start and collect baseline metrics
            delay(10000L)
            
            while (isActive && isOptimizing) {
                try {
                    // Collect current metrics from Xray core
                    val metrics = collectMetrics()
                    
                    if (metrics != null) {
                        // Run optimization
                        val result = optimizeConfiguration(metrics)
                        
                        if (result.success && result.needsReload) {
                            // Apply optimized configuration
                            val applied = applyConfiguration(result.config)
                            
                            if (applied) {
                                // Notify callback if configuration was applied
                                onConfigurationApplied?.invoke(result.config, true)
                                
                                Log.i(TAG, "Applied optimized Xray core configuration: " +
                                        "DomainStrategy=${result.config.domainStrategy}, " +
                                        "DomainMatcher=${result.config.domainMatcher}, " +
                                        "Expected improvement: ${result.expectedImprovement}%")
                            }
                        } else {
                            Log.d(TAG, "Optimization skipped: ${result.error ?: "No significant improvement"}")
                        }
                    } else {
                        Log.w(TAG, "Failed to collect metrics, skipping optimization cycle")
                    }
                    
                    // Wait before next optimization cycle
                    delay(optimizationIntervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in optimization loop", e)
                    delay(optimizationIntervalMs)
                }
            }
        }
    }
    
    /**
     * Stop optimization loop.
     */
    fun stopOptimization() {
        isOptimizing = false
        optimizationJob?.cancel()
        optimizationJob = null
        coreStatsClient?.close()
        coreStatsClient = null
        Log.i(TAG, "Stopped Xray core AI optimization")
    }
    
    /**
     * Collect metrics from Xray core stats API.
     */
    private suspend fun collectMetrics(): XrayMetrics? {
        return try {
            val stats = coreStatsClient?.getSystemStats()
            val traffic = coreStatsClient?.getTraffic()
            
            if (stats == null) {
                Log.w(TAG, "Failed to get system stats from Xray core")
                return null
            }
            
            // Calculate throughput from traffic stats
            val throughput = if (traffic != null) {
                // Estimate throughput (simplified - in production, track over time)
                (traffic.uplink + traffic.downlink) / 60L // bytes per second estimate
            } else {
                0L
            }
            
            // Estimate RTT from goroutines and memory (heuristic)
            val rtt = estimateRtt(stats)
            
            // Estimate packet loss (heuristic based on memory pressure)
            val loss = estimateLoss(stats)
            
            // Calculate CPU usage (heuristic based on goroutines)
            val cpuUsage = estimateCpuUsage(stats)
            
            // Calculate jitter from RTT history (RTT variance)
            val jitter = calculateJitter(rtt)
            
            // Calculate overall connection quality score (0.0-1.0)
            val connectionQuality = calculateConnectionQuality(rtt, loss, jitter, throughput)
            
            // Update history
            updateMetricsHistory(rtt, loss, throughput)
            
            XrayMetrics(
                throughput = throughput,
                rtt = rtt,
                loss = loss,
                connections = stats.numGoroutine,
                memoryUsage = stats.alloc,
                cpuUsage = cpuUsage,
                goroutines = stats.numGoroutine,
                jitter = jitter,
                connectionQuality = connectionQuality
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting metrics", e)
            null
        }
    }
    
    /**
     * Estimate RTT from system stats (heuristic).
     */
    private fun estimateRtt(stats: com.xray.app.stats.command.SysStatsResponse): Double {
        // Heuristic: more goroutines and memory pressure = higher latency
        val baseRtt = 50.0
        val goroutineFactor = stats.numGoroutine * 0.5
        val memoryFactor = (stats.alloc / (1024.0 * 1024.0 * 1024.0)) * 10.0 // GB to ms
        return baseRtt + goroutineFactor + memoryFactor
    }
    
    /**
     * Estimate packet loss from system stats (heuristic).
     */
    private fun estimateLoss(stats: com.xray.app.stats.command.SysStatsResponse): Double {
        // Heuristic: high memory usage and many goroutines = potential loss
        val memoryPressure = (stats.alloc / (1024.0 * 1024.0 * 1024.0)).coerceIn(0.0, 2.0) // 0-2GB
        val goroutinePressure = (stats.numGoroutine / 1000.0).coerceIn(0.0, 1.0)
        return (memoryPressure * 0.02 + goroutinePressure * 0.01).coerceIn(0.0, 0.1) // 0-10% loss
    }
    
    /**
     * Estimate CPU usage from system stats (heuristic).
     */
    private fun estimateCpuUsage(stats: com.xray.app.stats.command.SysStatsResponse): Double {
        // Heuristic: more goroutines = higher CPU usage
        val goroutineFactor = (stats.numGoroutine / 500.0).coerceIn(0.0, 1.0)
        return goroutineFactor
    }
    
    /**
     * Calculate jitter (RTT variance) from RTT history.
     */
    private fun calculateJitter(currentRtt: Double): Double {
        if (rttHistory.isEmpty()) {
            return 0.0
        }
        
        // Calculate standard deviation of RTT values
        val mean = rttHistory.average()
        val variance = rttHistory.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        
        return stdDev.coerceIn(0.0, 100.0) // Cap at 100ms
    }
    
    /**
     * Calculate overall connection quality score (0.0-1.0).
     * Higher score = better quality.
     */
    private fun calculateConnectionQuality(
        rtt: Double,
        loss: Double,
        jitter: Double,
        throughput: Long
    ): Double {
        // RTT score: lower is better (0-500ms range)
        val rttScore = (1.0 - (rtt / 500.0).coerceIn(0.0, 1.0)) * 0.3
        
        // Loss score: lower is better (0-10% range)
        val lossScore = (1.0 - (loss * 10.0).coerceIn(0.0, 1.0)) * 0.3
        
        // Jitter score: lower is better (0-100ms range)
        val jitterScore = (1.0 - (jitter / 100.0).coerceIn(0.0, 1.0)) * 0.2
        
        // Throughput score: higher is better (0-100 Mbps range)
        val throughputMbps = (throughput * 8.0) / (1024 * 1024)
        val throughputScore = (throughputMbps / 100.0).coerceIn(0.0, 1.0) * 0.2
        
        return (rttScore + lossScore + jitterScore + throughputScore).coerceIn(0.0, 1.0)
    }
    
    /**
     * Update metrics history for trend analysis.
     */
    private fun updateMetricsHistory(rtt: Double, loss: Double, throughput: Long) {
        rttHistory.add(rtt)
        lossHistory.add(loss)
        throughputHistory.add(throughput)
        
        // Keep only last N measurements
        if (rttHistory.size > maxHistorySize) {
            rttHistory.removeAt(0)
        }
        if (lossHistory.size > maxHistorySize) {
            lossHistory.removeAt(0)
        }
        if (throughputHistory.size > maxHistorySize) {
            throughputHistory.removeAt(0)
        }
    }
    
    /**
     * Optimize Xray core configuration based on current metrics.
     */
    fun optimizeConfiguration(metrics: XrayMetrics): OptimizationResult {
        return try {
            Log.d(TAG, "Optimizing Xray core configuration based on metrics: " +
                    "throughput=${metrics.throughput}, rtt=${metrics.rtt}, " +
                    "loss=${metrics.loss}, jitter=${metrics.jitter}, " +
                    "quality=${String.format("%.2f", metrics.connectionQuality)}, " +
                    "connections=${metrics.connections}")
            
            // Extract features for AI model
            val features = extractFeatures(metrics)
            
            // Get AI recommendation
            val aiRecommendation = getAiRecommendation(features)
            
            // Create optimized configuration
            val optimizedConfig = createOptimizedConfig(aiRecommendation, metrics)
            
            // Validate configuration
            val validatedConfig = validateConfig(optimizedConfig)
            
            // Calculate expected improvement
            val expectedImprovement = calculateExpectedImprovement(metrics, validatedConfig)
            
            // Check if improvement is significant enough to apply
            val currentConfig = getCurrentConfig()
            val configChanged = hasConfigChanged(currentConfig, validatedConfig)
            
            // Lower threshold for gaming/low latency scenarios
            val isGamingMode = metrics.throughput < 3 * 1024 * 1024 && 
                    metrics.rtt < 200 && 
                    metrics.jitter > 10
            
            val improvementThreshold = if (isGamingMode) {
                2.0 // Lower threshold for gaming (more aggressive)
            } else {
                3.0 // Normal threshold
            }
            
            if (configChanged && expectedImprovement > improvementThreshold) {
                OptimizationResult(
                    config = validatedConfig,
                    expectedImprovement = expectedImprovement,
                    metrics = metrics,
                    success = true,
                    needsReload = true
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
                    needsReload = false,
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
                needsReload = false,
                error = e.message
            )
        }
    }
    
    /**
     * Extract features from metrics for AI model input.
     */
    private fun extractFeatures(metrics: XrayMetrics): DoubleArray {
        // Normalize features to [0, 1] range for AI model
        // Features: throughput, rtt, loss, connections, memory, cpu, goroutines
        val features = DoubleArray(16) { 0.0 }
        
        // Throughput: normalize to 0-100 Mbps range
        val throughputMbps = (metrics.throughput * 8.0) / (1024 * 1024)
        features[0] = (throughputMbps / 100.0).coerceIn(0.0, 1.0)
        
        // RTT: normalize to 0-500ms range
        features[1] = (metrics.rtt / 500.0).coerceIn(0.0, 1.0)
        
        // Loss: already 0-1 range
        features[2] = metrics.loss.coerceIn(0.0, 1.0)
        
        // Handshake time (estimated from RTT)
        features[3] = (metrics.rtt * 2.0 / 2000.0).coerceIn(0.0, 1.0)
        
        // Jitter: normalize to 0-100ms range
        features[4] = (metrics.jitter / 100.0).coerceIn(0.0, 1.0)
        
        // Uplink (estimated from throughput)
        features[5] = features[0] * 0.5
        
        // Downlink (estimated from throughput)
        features[6] = features[0] * 0.5
        
        // Goroutines: normalize to 0-1000 range
        features[7] = (metrics.goroutines / 1000.0).coerceIn(0.0, 1.0)
        
        // Memory usage: normalize to 0-1GB range
        features[8] = (metrics.memoryUsage / (1024.0 * 1024.0 * 1024.0)).coerceIn(0.0, 1.0)
        
        // Current configuration features (normalized)
        val currentConfig = getCurrentConfig()
        features[9] = when (currentConfig.domainStrategy) {
            "AsIs" -> 0.0
            "IPIfNonMatch" -> 0.5
            "IPOnDemand" -> 1.0
            else -> 0.5
        }
        features[10] = when (currentConfig.domainMatcher) {
            "linear" -> 0.0
            "hybrid" -> 0.5
            "mph" -> 1.0
            else -> 0.5
        }
        features[11] = 0.5 // Placeholder for timeout
        
        // Connection quality score (0.0-1.0)
        features[12] = metrics.connectionQuality
        
        // Network conditions (placeholder - can be enhanced)
        features[13] = 0.5 // Time of day
        features[14] = 0.5 // Signal strength
        features[15] = 0.5 // Network type
        
        return features
    }
    
    /**
     * Get AI recommendation for Xray core configuration.
     */
    private fun getAiRecommendation(features: DoubleArray): IntArray {
        return try {
            if (!deepModel.isModelLoaded()) {
                Log.w(TAG, "AI model not loaded, using heuristic optimization")
                return getHeuristicRecommendation(features)
            }
            
            // Use AI model to predict optimal configuration
            val modelOutput = deepModel.inferRaw(features)
            
            // Ensure we have at least 5 outputs
            if (modelOutput.size < 5) {
                Log.w(TAG, "Model output size ${modelOutput.size} < 5, using heuristic")
                return getHeuristicRecommendation(features)
            }
            
            // Map model output to configuration adjustments
            val adjustments = IntArray(5)
            
            // Domain strategy adjustment
            adjustments[0] = when {
                modelOutput[0] > 0.66 -> 1  // Use IPOnDemand
                modelOutput[0] < 0.33 -> -1 // Use AsIs
                else -> 0                   // Use IPIfNonMatch
            }
            
            // Domain matcher adjustment
            adjustments[1] = when {
                modelOutput[1] > 0.66 -> 1  // Use mph
                modelOutput[1] < 0.33 -> -1 // Use linear
                else -> 0                   // Use hybrid
            }
            
            // Policy optimization
            adjustments[2] = when {
                modelOutput[2] > 0.66 -> 1  // Increase buffer
                modelOutput[2] < 0.33 -> -1 // Decrease buffer
                else -> 0                   // No change
            }
            
            // DNS optimization
            adjustments[3] = if (modelOutput[3] > 0.5) 1 else 0
            
            // Routing optimization
            adjustments[4] = if (modelOutput[4] > 0.5) 1 else 0
            
            Log.d(TAG, "AI recommendations: DomainStrategy=${adjustments[0]}, " +
                    "DomainMatcher=${adjustments[1]}, Policy=${adjustments[2]}, " +
                    "DNS=${adjustments[3]}, Routing=${adjustments[4]}")
            
            adjustments
        } catch (e: Exception) {
            Log.e(TAG, "Error getting AI recommendation", e)
            getHeuristicRecommendation(features)
        }
    }
    
    /**
     * Get heuristic recommendation when AI model is unavailable.
     * Includes gaming-optimized recommendations for low latency scenarios.
     */
    private fun getHeuristicRecommendation(features: DoubleArray): IntArray {
        val adjustments = IntArray(5)
        val throughput = features[0]
        val rtt = features[1]
        val loss = features[2]
        val jitter = features[4]
        val connectionQuality = features[12]
        
        // Gaming mode detection: Low throughput but low latency requirement
        // Games typically have low throughput but need very low latency
        val isGamingMode = throughput < 0.3 && rtt < 0.4 && jitter > 0.1
        
        if (isGamingMode) {
            // Gaming-optimized: prioritize latency and jitter reduction
            Log.d(TAG, "Gaming mode detected: optimizing for low latency and jitter")
            adjustments[0] = -1 // AsIs (skip DNS lookup for speed)
            adjustments[1] = -1 // linear matcher (fastest for small rule sets)
            adjustments[2] = 0 // Keep buffer moderate (not too large to avoid delay)
            adjustments[3] = 1 // Enable DNS optimization (reduce DNS latency)
            adjustments[4] = 1 // Enable routing optimization
        }
        // High throughput + low latency: use IPOnDemand and mph matcher
        else if (throughput > 0.7 && rtt < 0.3) {
            adjustments[0] = 1 // IPOnDemand
            adjustments[1] = 1 // mph matcher
            adjustments[3] = 1 // Enable DNS optimization
            adjustments[4] = 1 // Enable routing optimization
        }
        // High latency: use AsIs and linear matcher
        else if (rtt > 0.5) {
            adjustments[0] = -1 // AsIs
            adjustments[1] = -1 // linear matcher
            adjustments[2] = -1 // Decrease buffer
        }
        // High loss: use IPIfNonMatch and hybrid matcher
        else if (loss > 0.05) {
            adjustments[0] = 0 // IPIfNonMatch
            adjustments[1] = 0 // hybrid matcher
            adjustments[2] = 1 // Increase buffer
        }
        // High jitter: prioritize stability
        else if (jitter > 0.3) {
            adjustments[0] = 0 // IPIfNonMatch (balanced)
            adjustments[1] = 0 // hybrid matcher (stable)
            adjustments[2] = 1 // Increase buffer (reduce packet loss)
            adjustments[4] = 1 // Enable routing optimization
        }
        
        return adjustments
    }
    
    /**
     * Create optimized configuration from AI recommendation.
     * Includes gaming-optimized settings for low latency scenarios.
     */
    private fun createOptimizedConfig(
        recommendation: IntArray,
        metrics: XrayMetrics
    ): XrayOptimizedConfig {
        val current = getCurrentConfig()
        
        // Apply domain strategy recommendation
        val newDomainStrategy = when (recommendation[0]) {
            1 -> "IPOnDemand"
            -1 -> "AsIs"
            else -> "IPIfNonMatch"
        }
        
        // Apply domain matcher recommendation
        val newDomainMatcher = when (recommendation[1]) {
            1 -> "mph"
            -1 -> "linear"
            else -> "hybrid"
        }
        
        // Apply policy level recommendations
        // For gaming/low latency: use smaller buffers to reduce delay
        val isGamingMode = metrics.throughput < 3 * 1024 * 1024 && // < 3MB/s
                metrics.rtt < 200 && // < 200ms
                metrics.jitter > 10 // Some jitter present
        
        val newPolicyLevels = current.policyLevels.mapValues { (_, level) ->
            val newBufferSize = when {
                isGamingMode -> {
                    // Gaming mode: smaller buffers for lower latency
                    // But not too small to avoid packet loss
                    val gamingBuffer = max(16384, min(level.bufferSize, 32768))
                    when (recommendation[2]) {
                        1 -> min(gamingBuffer + 4096, 32768) // Smaller increments
                        -1 -> max(gamingBuffer - 4096, 16384)
                        else -> gamingBuffer
                    }
                }
                else -> {
                    when (recommendation[2]) {
                        1 -> min(level.bufferSize + 8192, 65536)
                        -1 -> max(level.bufferSize - 8192, 8192)
                        else -> level.bufferSize
                    }
                }
            }
            
            // For gaming: reduce connection idle time to free resources faster
            val newConnectionIdle = if (isGamingMode) {
                min(level.connectionIdle, 120) // 2 minutes max for games
            } else {
                level.connectionIdle
            }
            
            level.copy(
                bufferSize = newBufferSize,
                connectionIdle = newConnectionIdle
            )
        }
        
        // Apply DNS recommendations
        // For gaming: prioritize low latency DNS (reuse isGamingMode from above)
        val newDnsConfig = if (recommendation[3] == 1 || isGamingMode) {
            current.dnsConfig.copy(
                cacheSize = if (isGamingMode) {
                    // Gaming: larger cache to reduce DNS lookups
                    min(current.dnsConfig.cacheSize + 2000, 10000)
                } else {
                    min(current.dnsConfig.cacheSize + 1000, 10000)
                },
                queryStrategy = if (isGamingMode) {
                    "UseIPv4" // IPv4 is typically faster for games
                } else {
                    "UseIP"
                }
            )
        } else {
            current.dnsConfig
        }
        
        // Apply routing recommendations
        val newRoutingRules = if (recommendation[4] == 1) {
            // Add optimized routing rules
            current.routingRules + listOf(
                RoutingRule(
                    type = "field",
                    outboundTag = "direct",
                    enabled = true
                )
            )
        } else {
            current.routingRules
        }
        
        return XrayOptimizedConfig(
            domainStrategy = newDomainStrategy,
            domainMatcher = newDomainMatcher,
            routingRules = newRoutingRules,
            policyLevels = newPolicyLevels,
            dnsConfig = newDnsConfig,
            outboundSelection = current.outboundSelection
        )
    }
    
    /**
     * Validate configuration against bounds and safety constraints.
     */
    private fun validateConfig(config: XrayOptimizedConfig): XrayOptimizedConfig {
        // Ensure valid domain strategy
        val validDomainStrategy = when (config.domainStrategy) {
            "AsIs", "IPIfNonMatch", "IPOnDemand" -> config.domainStrategy
            else -> "IPIfNonMatch"
        }
        
        // Ensure valid domain matcher
        val validDomainMatcher = when (config.domainMatcher) {
            "linear", "hybrid", "mph" -> config.domainMatcher
            else -> "hybrid"
        }
        
        return config.copy(
            domainStrategy = validDomainStrategy,
            domainMatcher = validDomainMatcher
        )
    }
    
    /**
     * Calculate expected performance improvement.
     * Takes connection quality into account for more accurate predictions.
     */
    private fun calculateExpectedImprovement(
        currentMetrics: XrayMetrics,
        newConfig: XrayOptimizedConfig
    ): Double {
        val current = getCurrentConfig()
        var improvement = 0.0
        
        // Base improvement from configuration changes
        // Domain strategy change → latency improvement
        if (newConfig.domainStrategy != current.domainStrategy) {
            // Better improvement if connection quality is low (more room for improvement)
            val qualityFactor = if (currentMetrics.connectionQuality < 0.5) 1.5 else 1.0
            improvement += 5.0 * qualityFactor
        }
        
        // Domain matcher change → routing performance improvement
        if (newConfig.domainMatcher != current.domainMatcher) {
            val qualityFactor = if (currentMetrics.connectionQuality < 0.5) 1.5 else 1.0
            improvement += 3.0 * qualityFactor
        }
        
        // Policy buffer increase → throughput improvement
        val avgBufferIncrease = newConfig.policyLevels.values
            .zip(current.policyLevels.values)
            .map { (new, old) -> (new.bufferSize - old.bufferSize) / old.bufferSize.toDouble() }
            .average()
        if (avgBufferIncrease > 0) {
            // Higher improvement if throughput is low
            val throughputFactor = if (currentMetrics.throughput < 1024 * 1024) 1.5 else 1.0 // < 1MB/s
            improvement += avgBufferIncrease * 10.0 * throughputFactor
        }
        
        // DNS optimization → latency improvement
        if (newConfig.dnsConfig.cacheSize > current.dnsConfig.cacheSize) {
            // Higher improvement if RTT is high
            val rttFactor = if (currentMetrics.rtt > 200) 1.5 else 1.0
            improvement += 2.0 * rttFactor
        }
        
        // Connection quality-based adjustments
        // If quality is very low, expect higher improvement potential
        if (currentMetrics.connectionQuality < 0.3) {
            improvement *= 1.3 // 30% bonus for very poor connections
        }
        
        // If jitter is high, routing optimizations help more
        if (currentMetrics.jitter > 50) {
            if (newConfig.domainStrategy != current.domainStrategy || 
                newConfig.domainMatcher != current.domainMatcher) {
                improvement += 2.0 // Extra improvement for jitter reduction
            }
        }
        
        // If packet loss is high, buffer optimizations help more
        if (currentMetrics.loss > 0.05) {
            if (avgBufferIncrease > 0) {
                improvement += 3.0 // Extra improvement for loss reduction
            }
        }
        
        // Gaming mode bonus: if low throughput + low latency (gaming pattern)
        val isGamingMode = currentMetrics.throughput < 3 * 1024 * 1024 && 
                currentMetrics.rtt < 200 && 
                currentMetrics.jitter > 10
        
        if (isGamingMode) {
            // Gaming optimizations provide more noticeable improvement
            if (newConfig.domainStrategy == "AsIs" || 
                newConfig.domainMatcher == "linear") {
                improvement += 5.0 // Significant improvement for gaming latency
            }
            if (newConfig.dnsConfig.queryStrategy == "UseIPv4") {
                improvement += 3.0 // IPv4 DNS is faster for games
            }
            Log.d(TAG, "Gaming mode detected: applying latency-focused optimizations")
        }
        
        return improvement
    }
    
    /**
     * Check if configuration has changed.
     */
    private fun hasConfigChanged(
        current: XrayOptimizedConfig,
        new: XrayOptimizedConfig
    ): Boolean {
        return current.domainStrategy != new.domainStrategy ||
                current.domainMatcher != new.domainMatcher ||
                current.routingRules.size != new.routingRules.size ||
                current.policyLevels != new.policyLevels ||
                current.dnsConfig != new.dnsConfig
    }
    
    /**
     * Apply optimized configuration to Xray core config file.
     * Returns true if configuration was applied successfully.
     */
    private fun applyConfiguration(config: XrayOptimizedConfig): Boolean {
        return try {
            val configPath = prefs.selectedConfigPath ?: return false
            val configFile = File(configPath)
            
            if (!configFile.exists()) {
                Log.e(TAG, "Config file not found: $configPath")
                return false
            }
            
            // Read current config
            val configContent = configFile.readText()
            val jsonObject = JSONObject(configContent)
            
            // Apply routing optimizations
            var routingObject = jsonObject.optJSONObject("routing")
            if (routingObject == null) {
                routingObject = JSONObject()
                jsonObject.put("routing", routingObject)
            }
            routingObject.put("domainStrategy", config.domainStrategy)
            routingObject.put("domainMatcher", config.domainMatcher)
            
            // Apply policy optimizations
            var policyObject = jsonObject.optJSONObject("policy")
            if (policyObject == null) {
                policyObject = JSONObject()
                jsonObject.put("policy", policyObject)
            }
            
            var levelsObject = policyObject.optJSONObject("levels")
            if (levelsObject == null) {
                levelsObject = JSONObject()
                policyObject.put("levels", levelsObject)
            }
            
            config.policyLevels.forEach { (level, policyLevel) ->
                var levelObject = levelsObject.optJSONObject(level)
                if (levelObject == null) {
                    levelObject = JSONObject()
                    levelsObject.put(level, levelObject)
                }
                
                var bufferObject = levelObject.optJSONObject("buffer")
                if (bufferObject == null) {
                    bufferObject = JSONObject()
                    levelObject.put("buffer", bufferObject)
                }
                bufferObject.put("connection", policyLevel.bufferSize)
            }
            
            // Apply DNS optimizations
            var dnsObject = jsonObject.optJSONObject("dns")
            if (dnsObject == null) {
                dnsObject = JSONObject()
                jsonObject.put("dns", dnsObject)
            }
            dnsObject.put("queryStrategy", config.dnsConfig.queryStrategy)
            
            var cacheObject = dnsObject.optJSONObject("cache")
            if (cacheObject == null) {
                cacheObject = JSONObject()
                dnsObject.put("cache", cacheObject)
            }
            cacheObject.put("size", config.dnsConfig.cacheSize)
            
            // Write updated config
            configFile.writeText(jsonObject.toString(2))
            
            currentConfig = config
            
            Log.i(TAG, "Applied optimized Xray core configuration to: $configPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error applying configuration", e)
            false
        }
    }
    
    /**
     * Callback for when configuration is applied.
     * Can be set to trigger Xray core reload.
     */
    var onConfigurationApplied: ((XrayOptimizedConfig, Boolean) -> Unit)? = null
    
    /**
     * Get current configuration from config file.
     */
    private fun getCurrentConfig(): XrayOptimizedConfig {
        return currentConfig ?: try {
            val configPath = prefs.selectedConfigPath
            if (configPath != null) {
                val configFile = File(configPath)
                if (configFile.exists()) {
                    val configContent = configFile.readText()
                    val jsonObject = JSONObject(configContent)
                    
                    val routingObject = jsonObject.optJSONObject("routing")
                    val domainStrategy = routingObject?.optString("domainStrategy", "IPIfNonMatch") ?: "IPIfNonMatch"
                    val domainMatcher = routingObject?.optString("domainMatcher", "hybrid") ?: "hybrid"
                    
                    val policyObject = jsonObject.optJSONObject("policy")
                    val levelsObject = policyObject?.optJSONObject("levels")
                    val policyLevels = mutableMapOf<String, PolicyLevel>()
                    
                    if (levelsObject != null) {
                        levelsObject.keys().forEach { level ->
                            val levelObject = levelsObject.optJSONObject(level)
                            val bufferObject = levelObject?.optJSONObject("buffer")
                            val bufferSize = bufferObject?.optInt("connection", 32768) ?: 32768
                            
                            policyLevels[level] = PolicyLevel(
                                connectionIdle = 300,
                                handshake = 4,
                                uplinkOnly = 2,
                                downlinkOnly = 5,
                                statsUserUplink = true,
                                statsUserDownlink = true,
                                bufferSize = bufferSize
                            )
                        }
                    }
                    
                    val dnsObject = jsonObject.optJSONObject("dns")
                    val dnsConfig = DnsConfig(
                        servers = listOf("8.8.8.8", "1.1.1.1"),
                        queryStrategy = dnsObject?.optString("queryStrategy", "UseIP") ?: "UseIP",
                        cacheSize = dnsObject?.optJSONObject("cache")?.optInt("size", 1000) ?: 1000,
                        cacheStrategy = "cache"
                    )
                    
                    val config = XrayOptimizedConfig(
                        domainStrategy = domainStrategy,
                        domainMatcher = domainMatcher,
                        routingRules = emptyList(),
                        policyLevels = policyLevels.ifEmpty { mapOf("0" to PolicyLevel(300, 4, 2, 5, true, true, 32768)) },
                        dnsConfig = dnsConfig,
                        outboundSelection = OutboundSelection("random", 60, false)
                    )
                    
                    currentConfig = config
                    config
                } else {
                    getDefaultConfig()
                }
            } else {
                getDefaultConfig()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading current config", e)
            getDefaultConfig()
        }
    }
    
    /**
     * Get default configuration.
     */
    private fun getDefaultConfig(): XrayOptimizedConfig {
        return XrayOptimizedConfig(
            domainStrategy = "IPIfNonMatch",
            domainMatcher = "hybrid",
            routingRules = emptyList(),
            policyLevels = mapOf("0" to PolicyLevel(300, 4, 2, 5, true, true, 32768)),
            dnsConfig = DnsConfig(listOf("8.8.8.8", "1.1.1.1"), "UseIP", 1000, "cache"),
            outboundSelection = OutboundSelection("random", 60, false)
        )
    }
    
    /**
     * Get current optimized configuration.
     */
    fun getCurrentOptimizedConfig(): XrayOptimizedConfig? {
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

