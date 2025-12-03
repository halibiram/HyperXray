package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 🚀 Predictive Reconnection System (2030 Architecture)
 * 
 * ML-powered reconnection with:
 * - Failure prediction before it happens
 * - Optimal server selection
 * - Adaptive backoff based on network conditions
 * - Pre-emptive connection warming
 */
class PredictiveReconnect(
    private val scope: CoroutineScope,
    private val config: PredictiveConfig = PredictiveConfig()
) {
    
    // Feature vectors for prediction
    private val featureHistory = mutableListOf<FeatureVector>()
    
    // Model weights (would be loaded from ONNX in production)
    private var modelWeights = ModelWeights()
    
    // Prediction results
    private val _predictions = MutableStateFlow<ConnectionPrediction?>(null)
    val predictions: StateFlow<ConnectionPrediction?> = _predictions.asStateFlow()
    
    // Server scores
    private val serverScores = mutableMapOf<String, ServerScore>()
    
    /**
     * Record connection metrics for learning
     */
    fun recordMetrics(metrics: ConnectionMetrics) {
        val features = extractFeatures(metrics)
        featureHistory.add(features)
        
        // Keep history bounded
        if (featureHistory.size > config.maxHistorySize) {
            featureHistory.removeAt(0)
        }
        
        // Update predictions
        updatePredictions(features)
        
        // Update server scores
        updateServerScore(metrics.serverId, metrics)
    }
    
    /**
     * Predict connection failure probability
     */
    fun predictFailure(currentMetrics: ConnectionMetrics): Float {
        val features = extractFeatures(currentMetrics)
        return sigmoid(dotProduct(features.toArray(), modelWeights.failureWeights))
    }
    
    /**
     * Get optimal reconnection delay
     */
    fun getOptimalBackoff(
        attempt: Int,
        lastMetrics: ConnectionMetrics?,
        networkType: NetworkType
    ): Duration {
        val baseBackoff = config.baseBackoffMs
        
        // Exponential backoff with jitter
        val exponentialBackoff = baseBackoff * (2.0.pow(attempt.coerceAtMost(10)))
        
        // Adjust based on network type
        val networkMultiplier = when (networkType) {
            NetworkType.WIFI -> 1.0
            NetworkType.CELLULAR -> 1.5
            NetworkType.ETHERNET -> 0.8
            else -> 2.0
        }
        
        // Adjust based on recent failure patterns
        val failureMultiplier = if (lastMetrics != null) {
            val failureProb = predictFailure(lastMetrics)
            1.0 + failureProb // Higher failure probability = longer backoff
        } else 1.0
        
        // Add jitter (±20%)
        val jitter = 1.0 + (Math.random() - 0.5) * 0.4
        
        val finalBackoff = (exponentialBackoff * networkMultiplier * failureMultiplier * jitter)
            .coerceAtMost(config.maxBackoffMs.toDouble())
        
        return finalBackoff.toLong().milliseconds
    }
    
    /**
     * Select optimal server for reconnection
     */
    fun selectOptimalServer(availableServers: List<String>): String? {
        if (availableServers.isEmpty()) return null
        
        // Score each server
        val scoredServers = availableServers.map { serverId ->
            val score = serverScores[serverId] ?: ServerScore(serverId)
            serverId to calculateServerPriority(score)
        }
        
        // Select server with highest priority (using softmax for exploration)
        return softmaxSelect(scoredServers)
    }
    
    /**
     * Check if pre-emptive reconnection is recommended
     */
    fun shouldPreemptivelyReconnect(currentMetrics: ConnectionMetrics): PreemptiveAction {
        val failureProb = predictFailure(currentMetrics)
        
        return when {
            failureProb > config.criticalFailureThreshold -> {
                PreemptiveAction.ReconnectImmediately(
                    reason = "High failure probability: ${(failureProb * 100).toInt()}%",
                    suggestedServer = selectOptimalServer(getAvailableServers())
                )
            }
            failureProb > config.warningFailureThreshold -> {
                PreemptiveAction.WarmupBackup(
                    reason = "Elevated failure risk: ${(failureProb * 100).toInt()}%",
                    suggestedServer = selectOptimalServer(getAvailableServers())
                )
            }
            else -> PreemptiveAction.None
        }
    }
    
    /**
     * Update model with feedback (online learning)
     */
    fun provideFeedback(prediction: ConnectionPrediction, actualOutcome: ConnectionOutcome) {
        // Simple online gradient descent update
        val error = if (actualOutcome == ConnectionOutcome.FAILED) 1f else 0f
        val predictionError = error - prediction.failureProbability
        
        // Update weights (simplified - real implementation would use proper optimizer)
        val learningRate = config.learningRate
        for (i in modelWeights.failureWeights.indices) {
            if (i < prediction.features.size) {
                modelWeights.failureWeights[i] += learningRate * predictionError * prediction.features[i]
            }
        }
    }
    
    // ===== Private Methods =====
    
    private fun extractFeatures(metrics: ConnectionMetrics): FeatureVector {
        return FeatureVector(
            latencyNormalized = normalizeLatency(metrics.latencyMs),
            packetLossNormalized = metrics.packetLossPercent / 100f,
            jitterNormalized = normalizeJitter(metrics.jitterMs),
            throughputNormalized = normalizeThroughput(metrics.throughputBps),
            handshakeAgeNormalized = normalizeHandshakeAge(metrics.handshakeAgeSeconds),
            networkTypeEncoded = encodeNetworkType(metrics.networkType),
            timeOfDayEncoded = encodeTimeOfDay(metrics.timestamp),
            recentFailureRate = calculateRecentFailureRate(),
            connectionDurationNormalized = normalizeConnectionDuration(metrics.connectionDurationSeconds)
        )
    }
    
    private fun normalizeLatency(latencyMs: Long): Float {
        // Log-scale normalization for latency
        return (ln(latencyMs.toDouble() + 1) / ln(1000.0)).toFloat().coerceIn(0f, 1f)
    }
    
    private fun normalizeJitter(jitterMs: Long): Float {
        return (jitterMs.toFloat() / 200f).coerceIn(0f, 1f)
    }
    
    private fun normalizeThroughput(throughputBps: Long): Float {
        // Inverse normalization - higher throughput = lower value (better)
        val maxThroughput = 100_000_000L // 100 Mbps
        return 1f - (throughputBps.toFloat() / maxThroughput).coerceIn(0f, 1f)
    }
    
    private fun normalizeHandshakeAge(ageSeconds: Long): Float {
        return (ageSeconds.toFloat() / 180f).coerceIn(0f, 1f)
    }
    
    private fun normalizeConnectionDuration(durationSeconds: Long): Float {
        // Longer connections are more stable
        return 1f - (durationSeconds.toFloat() / 3600f).coerceIn(0f, 1f)
    }
    
    private fun encodeNetworkType(networkType: NetworkType): Float {
        return when (networkType) {
            NetworkType.WIFI -> 0.2f
            NetworkType.ETHERNET -> 0.1f
            NetworkType.CELLULAR -> 0.6f
            NetworkType.VPN -> 0.3f
            NetworkType.UNKNOWN -> 0.8f
            NetworkType.NONE -> 1.0f
        }
    }
    
    private fun encodeTimeOfDay(timestamp: Instant): Float {
        val hour = java.time.LocalDateTime.ofInstant(
            timestamp, 
            java.time.ZoneId.systemDefault()
        ).hour
        
        // Peak hours (evening) have higher congestion
        return when (hour) {
            in 0..6 -> 0.2f   // Night - low congestion
            in 7..9 -> 0.5f   // Morning rush
            in 10..16 -> 0.3f // Daytime
            in 17..22 -> 0.7f // Evening peak
            else -> 0.4f
        }
    }
    
    private fun calculateRecentFailureRate(): Float {
        if (featureHistory.isEmpty()) return 0f
        
        val recentHistory = featureHistory.takeLast(10)
        // This would track actual failures in production
        return 0f
    }
    
    private fun updatePredictions(features: FeatureVector) {
        val failureProb = sigmoid(dotProduct(features.toArray(), modelWeights.failureWeights))
        
        _predictions.value = ConnectionPrediction(
            failureProbability = failureProb,
            confidence = calculateConfidence(),
            suggestedAction = determineSuggestedAction(failureProb),
            features = features.toArray().toList(),
            timestamp = Instant.now()
        )
    }
    
    private fun calculateConfidence(): Float {
        // Confidence increases with more training data
        val dataPoints = featureHistory.size
        return (1f - exp(-dataPoints / 100f)).toFloat()
    }
    
    private fun determineSuggestedAction(failureProb: Float): SuggestedAction {
        return when {
            failureProb > 0.8f -> SuggestedAction.RECONNECT_NOW
            failureProb > 0.5f -> SuggestedAction.PREPARE_BACKUP
            failureProb > 0.3f -> SuggestedAction.MONITOR_CLOSELY
            else -> SuggestedAction.CONTINUE
        }
    }
    
    private fun updateServerScore(serverId: String, metrics: ConnectionMetrics) {
        val currentScore = serverScores.getOrPut(serverId) { ServerScore(serverId) }
        
        // Update running averages
        val alpha = 0.1f // Exponential moving average factor
        currentScore.avgLatency = alpha * metrics.latencyMs + (1 - alpha) * currentScore.avgLatency
        currentScore.avgPacketLoss = alpha * metrics.packetLossPercent + (1 - alpha) * currentScore.avgPacketLoss
        currentScore.connectionCount++
        currentScore.lastUsed = metrics.timestamp
        
        serverScores[serverId] = currentScore
    }
    
    private fun calculateServerPriority(score: ServerScore): Float {
        // Lower latency and packet loss = higher priority
        val latencyScore = 1f - (score.avgLatency / 500f).coerceIn(0f, 1f)
        val packetLossScore = 1f - (score.avgPacketLoss / 20f).coerceIn(0f, 1f)
        val reliabilityScore = score.successRate
        
        // Recency bonus - prefer recently successful servers
        val recencyBonus = if (score.lastUsed != null) {
            val ageMinutes = java.time.Duration.between(score.lastUsed, Instant.now()).toMinutes()
            (1f - ageMinutes / 60f).coerceIn(0f, 0.2f)
        } else 0f
        
        return (latencyScore * 0.3f + packetLossScore * 0.3f + reliabilityScore * 0.3f + recencyBonus)
    }
    
    private fun softmaxSelect(scoredServers: List<Pair<String, Float>>): String? {
        if (scoredServers.isEmpty()) return null
        
        val temperature = config.explorationTemperature
        val expScores = scoredServers.map { (server, score) ->
            server to exp(score / temperature)
        }
        
        val sumExp = expScores.sumOf { it.second.toDouble() }
        val probabilities = expScores.map { (server, expScore) ->
            server to (expScore / sumExp).toFloat()
        }
        
        // Sample based on probabilities
        var cumulative = 0f
        val random = Math.random().toFloat()
        
        for ((server, prob) in probabilities) {
            cumulative += prob
            if (random <= cumulative) {
                return server
            }
        }
        
        return scoredServers.first().first
    }
    
    private fun getAvailableServers(): List<String> {
        return serverScores.keys.toList()
    }
    
    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    }
    
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val minSize = minOf(a.size, b.size)
        for (i in 0 until minSize) {
            sum += a[i] * b[i]
        }
        return sum
    }
}

/**
 * Feature vector for ML prediction
 */
data class FeatureVector(
    val latencyNormalized: Float,
    val packetLossNormalized: Float,
    val jitterNormalized: Float,
    val throughputNormalized: Float,
    val handshakeAgeNormalized: Float,
    val networkTypeEncoded: Float,
    val timeOfDayEncoded: Float,
    val recentFailureRate: Float,
    val connectionDurationNormalized: Float
) {
    fun toArray(): FloatArray = floatArrayOf(
        latencyNormalized,
        packetLossNormalized,
        jitterNormalized,
        throughputNormalized,
        handshakeAgeNormalized,
        networkTypeEncoded,
        timeOfDayEncoded,
        recentFailureRate,
        connectionDurationNormalized
    )
}

/**
 * Connection metrics for prediction
 */
data class ConnectionMetrics(
    val serverId: String,
    val latencyMs: Long,
    val packetLossPercent: Float,
    val jitterMs: Long,
    val throughputBps: Long,
    val handshakeAgeSeconds: Long,
    val networkType: NetworkType,
    val connectionDurationSeconds: Long,
    val timestamp: Instant = Instant.now()
)

/**
 * Connection prediction result
 */
data class ConnectionPrediction(
    val failureProbability: Float,
    val confidence: Float,
    val suggestedAction: SuggestedAction,
    val features: List<Float>,
    val timestamp: Instant
)

/**
 * Suggested action based on prediction
 */
enum class SuggestedAction {
    CONTINUE,
    MONITOR_CLOSELY,
    PREPARE_BACKUP,
    RECONNECT_NOW
}

/**
 * Pre-emptive action recommendation
 */
sealed interface PreemptiveAction {
    data object None : PreemptiveAction
    
    data class WarmupBackup(
        val reason: String,
        val suggestedServer: String?
    ) : PreemptiveAction
    
    data class ReconnectImmediately(
        val reason: String,
        val suggestedServer: String?
    ) : PreemptiveAction
}

/**
 * Connection outcome for feedback
 */
enum class ConnectionOutcome {
    SUCCESS,
    FAILED,
    DEGRADED
}

/**
 * Server score for selection
 */
data class ServerScore(
    val serverId: String,
    var avgLatency: Float = 100f,
    var avgPacketLoss: Float = 0f,
    var connectionCount: Int = 0,
    var failureCount: Int = 0,
    var lastUsed: Instant? = null
) {
    val successRate: Float
        get() = if (connectionCount > 0) {
            1f - (failureCount.toFloat() / connectionCount)
        } else 0.5f
}

/**
 * Model weights (simplified - would be loaded from ONNX)
 */
data class ModelWeights(
    var failureWeights: FloatArray = FloatArray(9) { 0.1f }
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModelWeights) return false
        return failureWeights.contentEquals(other.failureWeights)
    }
    
    override fun hashCode(): Int = failureWeights.contentHashCode()
}

/**
 * Predictive reconnect configuration
 */
data class PredictiveConfig(
    val maxHistorySize: Int = 1000,
    val baseBackoffMs: Long = 1000,
    val maxBackoffMs: Long = 60000,
    val warningFailureThreshold: Float = 0.5f,
    val criticalFailureThreshold: Float = 0.8f,
    val learningRate: Float = 0.01f,
    val explorationTemperature: Float = 0.5f
)
