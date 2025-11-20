package com.hyperxray.an.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Timestamp serializer for Instant
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    
    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilli())
    }
    
    override fun deserialize(decoder: Decoder): Instant {
        return Instant.ofEpochMilli(decoder.decodeLong())
    }
}

/**
 * Telemetry metrics for a single measurement point
 */
@Serializable
data class TelemetryMetrics(
    /**
     * Throughput in bytes per second
     */
    val throughput: Double = 0.0,
    
    /**
     * 95th percentile Round-Trip Time in milliseconds
     */
    val rttP95: Double = 0.0,
    
    /**
     * Handshake time in milliseconds
     */
    val handshakeTime: Double = 0.0,
    
    /**
     * Packet loss rate (0.0 to 1.0)
     */
    val loss: Double = 0.0,
    
    /**
     * UDP error rate (errors per minute)
     */
    val udpErrorRate: Double = 0.0,
    
    /**
     * UDP error category distribution (counts per category)
     * 0=IDLE_TIMEOUT, 1=SHUTDOWN, 2=NORMAL_OPERATION, 3=UNKNOWN
     */
    val udpErrorCategoryCounts: List<Int> = listOf(0, 0, 0, 0),
    
    /**
     * Timestamp of the measurement
     */
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant = Instant.now()
)

/**
 * Aggregated telemetry data for a time window
 */
@Serializable
data class AggregatedTelemetry(
    /**
     * Average throughput in bytes per second
     */
    val avgThroughput: Double,
    
    /**
     * 95th percentile RTT in milliseconds
     */
    val rttP95: Double,
    
    /**
     * Average handshake time in milliseconds
     */
    val avgHandshakeTime: Double,
    
    /**
     * Average packet loss rate (0.0 to 1.0)
     */
    val avgLoss: Double,
    
    /**
     * Average UDP error rate (errors per minute)
     */
    val avgUdpErrorRate: Double = 0.0,
    
    /**
     * Number of samples aggregated
     */
    val sampleCount: Int,
    
    /**
     * Time window start
     */
    @Serializable(with = InstantSerializer::class)
    val windowStart: Instant,
    
    /**
     * Time window end
     */
    @Serializable(with = InstantSerializer::class)
    val windowEnd: Instant
)




