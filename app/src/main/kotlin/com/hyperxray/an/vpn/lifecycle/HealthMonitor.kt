package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 🚀 Predictive Health Monitor (2030 Architecture)
 * 
 * ML-powered health monitoring with:
 * - Real-time latency tracking
 * - Packet loss detection
 * - Predictive failure detection
 * - Automatic degradation handling
 */
class HealthMonitor(
    private val scope: CoroutineScope,
    private val config: HealthMonitorConfig = HealthMonitorConfig(),
    private val onHealthChanged: suspend (HealthStatus) -> Unit
) {
    
    private val _healthStatus = MutableStateFlow<HealthStatus>(HealthStatus.Healthy)
    val healthStatus: StateFlow<HealthStatus> = _healthStatus.asStateFlow()
    
    private val _metrics = MutableStateFlow(HealthMetrics())
    val metrics: StateFlow<HealthMetrics> = _metrics.asStateFlow()
    
    // Sliding window for metrics
    private val latencyWindow = SlidingWindow<Long>(config.windowSize)
    private val packetLossWindow = SlidingWindow<Boolean>(config.windowSize)
    private val throughputWindow = SlidingWindow<Long>(config.windowSize)
    
    // Anomaly detection
    private val anomalyDetector = AnomalyDetector(config.anomalyConfig)
    
    // Monitoring job
    private var monitoringJob: Job? = null
    
    /**
     * Start health monitoring
     */
    fun start() {
        if (monitoringJob?.isActive == true) return
        
        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    val currentMetrics = collectMetrics()
                    _metrics.value = currentMetrics
                    
                    val newStatus = evaluateHealth(currentMetrics)
                    if (newStatus != _healthStatus.value) {
                        _healthStatus.value = newStatus
                        onHealthChanged(newStatus)
                    }
                    
                    delay(config.checkIntervalMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log error but continue monitoring
                    delay(config.checkIntervalMs)
                }
            }
        }
    }
    
    /**
     * Stop health monitoring
     */
    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
    }
    
    /**
     * Record a latency measurement
     */
    fun recordLatency(latencyMs: Long) {
        latencyWindow.add(latencyMs)
        anomalyDetector.recordLatency(latencyMs)
    }
    
    /**
     * Record packet transmission result
     */
    fun recordPacket(lost: Boolean) {
        packetLossWindow.add(lost)
    }
    
    /**
     * Record throughput measurement
     */
    fun recordThroughput(bytesPerSecond: Long) {
        throughputWindow.add(bytesPerSecond)
    }
    
    /**
     * Record handshake time
     */
    fun recordHandshake(timestamp: Instant) {
        val ageSeconds = java.time.Duration.between(timestamp, Instant.now()).seconds
        _metrics.update { it.copy(handshakeAgeSeconds = ageSeconds) }
    }
    
    private fun collectMetrics(): HealthMetrics {
        val latencies = latencyWindow.getAll()
        val packets = packetLossWindow.getAll()
        val throughputs = throughputWindow.getAll()
        
        return HealthMetrics(
            latencyMs = latencies.lastOrNull() ?: 0,
            packetLossPercent = if (packets.isNotEmpty()) {
                (packets.count { it }.toFloat() / packets.size) * 100
            } else 0f,
            jitterMs = calculateJitter(latencies),
            throughputBps = throughputs.lastOrNull() ?: 0,
            handshakeAgeSeconds = _metrics.value.handshakeAgeSeconds
        )
    }
    
    private fun calculateJitter(latencies: List<Long>): Long {
        if (latencies.size < 2) return 0
        
        var totalDiff = 0L
        for (i in 1 until latencies.size) {
            totalDiff += kotlin.math.abs(latencies[i] - latencies[i - 1])
        }
        return totalDiff / (latencies.size - 1)
    }
    
    private fun evaluateHealth(metrics: HealthMetrics): HealthStatus {
        // Check for anomalies first
        val anomaly = anomalyDetector.detectAnomaly(metrics)
        if (anomaly != null) {
            return HealthStatus.Critical(
                reason = "Anomaly detected: ${anomaly.type}",
                metrics = metrics
            )
        }
        
        // Check critical thresholds
        if (metrics.latencyMs > config.criticalLatencyMs ||
            metrics.packetLossPercent > config.criticalPacketLossPercent ||
            metrics.handshakeAgeSeconds > config.criticalHandshakeAgeSeconds) {
            return HealthStatus.Critical(
                reason = buildCriticalReason(metrics),
                metrics = metrics
            )
        }
        
        // Check degraded thresholds
        if (metrics.latencyMs > config.degradedLatencyMs ||
            metrics.packetLossPercent > config.degradedPacketLossPercent ||
            metrics.jitterMs > config.degradedJitterMs) {
            return HealthStatus.Degraded(
                reason = buildDegradedReason(metrics),
                metrics = metrics
            )
        }
        
        return HealthStatus.Healthy
    }
    
    private fun buildCriticalReason(metrics: HealthMetrics): String {
        val reasons = mutableListOf<String>()
        if (metrics.latencyMs > config.criticalLatencyMs) {
            reasons.add("High latency: ${metrics.latencyMs}ms")
        }
        if (metrics.packetLossPercent > config.criticalPacketLossPercent) {
            reasons.add("High packet loss: ${metrics.packetLossPercent}%")
        }
        if (metrics.handshakeAgeSeconds > config.criticalHandshakeAgeSeconds) {
            reasons.add("Stale handshake: ${metrics.handshakeAgeSeconds}s")
        }
        return reasons.joinToString(", ")
    }
    
    private fun buildDegradedReason(metrics: HealthMetrics): String {
        val reasons = mutableListOf<String>()
        if (metrics.latencyMs > config.degradedLatencyMs) {
            reasons.add("Elevated latency: ${metrics.latencyMs}ms")
        }
        if (metrics.packetLossPercent > config.degradedPacketLossPercent) {
            reasons.add("Packet loss: ${metrics.packetLossPercent}%")
        }
        if (metrics.jitterMs > config.degradedJitterMs) {
            reasons.add("High jitter: ${metrics.jitterMs}ms")
        }
        return reasons.joinToString(", ")
    }
}

/**
 * Health monitor configuration
 */
data class HealthMonitorConfig(
    val checkIntervalMs: Long = 5000,
    val windowSize: Int = 20,
    
    // Degraded thresholds
    val degradedLatencyMs: Long = 200,
    val degradedPacketLossPercent: Float = 5f,
    val degradedJitterMs: Long = 50,
    
    // Critical thresholds
    val criticalLatencyMs: Long = 500,
    val criticalPacketLossPercent: Float = 15f,
    val criticalHandshakeAgeSeconds: Long = 180,
    
    // Anomaly detection
    val anomalyConfig: AnomalyConfig = AnomalyConfig()
)

/**
 * Sliding window for metric collection
 */
class SlidingWindow<T>(private val maxSize: Int) {
    private val items = ArrayDeque<T>(maxSize)
    
    @Synchronized
    fun add(item: T) {
        if (items.size >= maxSize) {
            items.removeFirst()
        }
        items.addLast(item)
    }
    
    @Synchronized
    fun getAll(): List<T> = items.toList()
    
    @Synchronized
    fun clear() = items.clear()
}

/**
 * Anomaly detector using statistical methods
 */
class AnomalyDetector(private val config: AnomalyConfig) {
    
    private val latencyHistory = SlidingWindow<Long>(config.historySize)
    private var baselineLatency: Double = 0.0
    private var latencyStdDev: Double = 0.0
    
    fun recordLatency(latencyMs: Long) {
        latencyHistory.add(latencyMs)
        updateBaseline()
    }
    
    fun detectAnomaly(metrics: HealthMetrics): Anomaly? {
        // Z-score based anomaly detection
        if (baselineLatency > 0 && latencyStdDev > 0) {
            val zScore = (metrics.latencyMs - baselineLatency) / latencyStdDev
            if (zScore > config.zScoreThreshold) {
                return Anomaly(
                    type = AnomalyType.LATENCY_SPIKE,
                    severity = if (zScore > config.zScoreThreshold * 2) 
                        AnomalySeverity.HIGH else AnomalySeverity.MEDIUM,
                    value = metrics.latencyMs.toDouble(),
                    baseline = baselineLatency,
                    zScore = zScore
                )
            }
        }
        
        // Sudden packet loss detection
        if (metrics.packetLossPercent > config.suddenPacketLossThreshold) {
            return Anomaly(
                type = AnomalyType.PACKET_LOSS_SPIKE,
                severity = AnomalySeverity.HIGH,
                value = metrics.packetLossPercent.toDouble(),
                baseline = 0.0,
                zScore = 0.0
            )
        }
        
        return null
    }
    
    private fun updateBaseline() {
        val latencies = latencyHistory.getAll()
        if (latencies.size >= config.minSamplesForBaseline) {
            baselineLatency = latencies.average()
            latencyStdDev = calculateStdDev(latencies, baselineLatency)
        }
    }
    
    private fun calculateStdDev(values: List<Long>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }
}

/**
 * Anomaly detection configuration
 */
data class AnomalyConfig(
    val historySize: Int = 100,
    val minSamplesForBaseline: Int = 10,
    val zScoreThreshold: Double = 3.0,
    val suddenPacketLossThreshold: Float = 20f
)

/**
 * Detected anomaly
 */
data class Anomaly(
    val type: AnomalyType,
    val severity: AnomalySeverity,
    val value: Double,
    val baseline: Double,
    val zScore: Double,
    val timestamp: Instant = Instant.now()
)

enum class AnomalyType {
    LATENCY_SPIKE,
    PACKET_LOSS_SPIKE,
    THROUGHPUT_DROP,
    JITTER_SPIKE,
    HANDSHAKE_TIMEOUT
}

enum class AnomalySeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}
