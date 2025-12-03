package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 🚀 Telemetry System (2030 Architecture)
 * 
 * OpenTelemetry-inspired observability for VPN lifecycle.
 * Provides distributed tracing, metrics, and structured logging.
 */

/**
 * Telemetry events emitted by the state machine
 */
sealed interface TelemetryEvent {
    val timestamp: Instant
    val traceId: String?
    
    data class StateTransition(
        val fromState: String,
        val toState: String,
        val event: String,
        override val timestamp: Instant = Instant.now(),
        val sessionId: String,
        override val traceId: String
    ) : TelemetryEvent
    
    data class TransitionRejected(
        val fromState: String,
        val event: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
        override val traceId: String? = null
    ) : TelemetryEvent
    
    data class TransitionError(
        val event: String,
        val error: String,
        override val timestamp: Instant = Instant.now(),
        override val traceId: String? = null
    ) : TelemetryEvent
    
    data class SideEffectExecuted(
        val effectType: String,
        val durationMs: Long,
        val success: Boolean,
        val error: String? = null,
        override val timestamp: Instant = Instant.now(),
        override val traceId: String? = null
    ) : TelemetryEvent
    
    data class MetricRecorded(
        val name: String,
        val value: Double,
        val unit: String,
        val tags: Map<String, String> = emptyMap(),
        override val timestamp: Instant = Instant.now(),
        override val traceId: String? = null
    ) : TelemetryEvent
    
    data class SpanStarted(
        val spanId: String,
        val parentSpanId: String?,
        val operationName: String,
        override val timestamp: Instant = Instant.now(),
        override val traceId: String
    ) : TelemetryEvent
    
    data class SpanEnded(
        val spanId: String,
        val durationMs: Long,
        val status: SpanStatus,
        override val timestamp: Instant = Instant.now(),
        override val traceId: String
    ) : TelemetryEvent
}

/**
 * Span status for distributed tracing
 */
enum class SpanStatus {
    OK, ERROR, CANCELLED, TIMEOUT
}

/**
 * Telemetry collector that aggregates and exports telemetry data
 */
class TelemetryCollector(
    private val scope: CoroutineScope,
    private val config: TelemetryConfig = TelemetryConfig()
) {
    
    // Metrics storage
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, Double>()
    private val histograms = ConcurrentHashMap<String, MutableList<Double>>()
    
    // Event buffer for batch export
    private val _eventBuffer = MutableSharedFlow<TelemetryEvent>(
        extraBufferCapacity = config.bufferSize
    )
    
    // Aggregated metrics flow
    private val _aggregatedMetrics = MutableStateFlow<AggregatedMetrics>(AggregatedMetrics())
    val aggregatedMetrics: StateFlow<AggregatedMetrics> = _aggregatedMetrics.asStateFlow()
    
    // Exporters
    private val exporters = mutableListOf<TelemetryExporter>()
    
    init {
        // Start aggregation loop
        scope.launch {
            aggregateMetrics()
        }
        
        // Start export loop
        scope.launch {
            exportTelemetry()
        }
    }
    
    /**
     * Record a telemetry event
     */
    suspend fun record(event: TelemetryEvent) {
        _eventBuffer.emit(event)
        
        // Update real-time metrics based on event type
        when (event) {
            is TelemetryEvent.StateTransition -> {
                incrementCounter("state_transitions_total")
                incrementCounter("state_transitions_${event.toState}")
            }
            is TelemetryEvent.TransitionError -> {
                incrementCounter("transition_errors_total")
            }
            is TelemetryEvent.SideEffectExecuted -> {
                incrementCounter("side_effects_total")
                if (event.success) {
                    incrementCounter("side_effects_success")
                } else {
                    incrementCounter("side_effects_failure")
                }
                recordHistogram("side_effect_duration_ms", event.durationMs.toDouble())
            }
            else -> {}
        }
    }
    
    /**
     * Increment a counter metric
     */
    fun incrementCounter(name: String, delta: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }
    
    /**
     * Set a gauge metric
     */
    fun setGauge(name: String, value: Double) {
        gauges[name] = value
    }
    
    /**
     * Record a histogram value
     */
    fun recordHistogram(name: String, value: Double) {
        histograms.computeIfAbsent(name) { mutableListOf() }.add(value)
    }
    
    /**
     * Register a telemetry exporter
     */
    fun registerExporter(exporter: TelemetryExporter) {
        exporters.add(exporter)
    }
    
    /**
     * Get current counter value
     */
    fun getCounter(name: String): Long = counters[name]?.get() ?: 0
    
    /**
     * Get current gauge value
     */
    fun getGauge(name: String): Double? = gauges[name]
    
    /**
     * Get histogram statistics
     */
    fun getHistogramStats(name: String): HistogramStats? {
        val values = histograms[name] ?: return null
        if (values.isEmpty()) return null
        
        val sorted = values.sorted()
        return HistogramStats(
            count = values.size,
            min = sorted.first(),
            max = sorted.last(),
            mean = values.average(),
            p50 = percentile(sorted, 0.5),
            p90 = percentile(sorted, 0.9),
            p99 = percentile(sorted, 0.99)
        )
    }
    
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val index = (sorted.size * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
    
    private suspend fun aggregateMetrics() {
        while (true) {
            kotlinx.coroutines.delay(config.aggregationIntervalMs)
            
            _aggregatedMetrics.value = AggregatedMetrics(
                timestamp = Instant.now(),
                counters = counters.mapValues { it.value.get() },
                gauges = gauges.toMap(),
                histograms = histograms.keys.associateWith { getHistogramStats(it) }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
            )
        }
    }
    
    private suspend fun exportTelemetry() {
        _eventBuffer.collect { event ->
            exporters.forEach { exporter ->
                try {
                    exporter.export(event)
                } catch (e: Exception) {
                    // Log export error but don't fail
                }
            }
        }
    }
}

/**
 * Telemetry configuration
 */
data class TelemetryConfig(
    val bufferSize: Int = 1000,
    val aggregationIntervalMs: Long = 5000,
    val exportBatchSize: Int = 100,
    val enableTracing: Boolean = true,
    val enableMetrics: Boolean = true,
    val enableLogging: Boolean = true
)

/**
 * Aggregated metrics snapshot
 */
data class AggregatedMetrics(
    val timestamp: Instant = Instant.now(),
    val counters: Map<String, Long> = emptyMap(),
    val gauges: Map<String, Double> = emptyMap(),
    val histograms: Map<String, HistogramStats> = emptyMap()
)

/**
 * Histogram statistics
 */
data class HistogramStats(
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val p50: Double,
    val p90: Double,
    val p99: Double
)

/**
 * Telemetry exporter interface
 */
interface TelemetryExporter {
    suspend fun export(event: TelemetryEvent)
    suspend fun flush()
    fun close()
}

/**
 * Console exporter for debugging
 */
class ConsoleExporter : TelemetryExporter {
    override suspend fun export(event: TelemetryEvent) {
        println("[TELEMETRY] ${event.timestamp} - $event")
    }
    
    override suspend fun flush() {}
    override fun close() {}
}

/**
 * In-memory exporter for testing
 */
class InMemoryExporter : TelemetryExporter {
    private val events = mutableListOf<TelemetryEvent>()
    
    override suspend fun export(event: TelemetryEvent) {
        synchronized(events) {
            events.add(event)
        }
    }
    
    override suspend fun flush() {}
    override fun close() {}
    
    fun getEvents(): List<TelemetryEvent> = synchronized(events) { events.toList() }
    fun clear() = synchronized(events) { events.clear() }
}
