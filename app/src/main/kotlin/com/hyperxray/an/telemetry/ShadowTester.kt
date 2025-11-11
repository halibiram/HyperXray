package com.hyperxray.an.telemetry

import kotlin.random.Random
import java.time.Instant

/**
 * ShadowTester: Mock throughput/RTT/loss generator for simulation.
 * 
 * Generates realistic network flow metrics for testing and simulation purposes.
 * Uses configurable parameters to simulate different network conditions.
 */
class ShadowTester(
    /**
     * Random seed for reproducible results (null = random)
     */
    private val seed: Int? = null,
    
    /**
     * Base throughput in bytes per second (default: 10MB/s)
     */
    private val baseThroughput: Double = 10_000_000.0,
    
    /**
     * Throughput variance (0.0 to 1.0, default: 0.2 = ±20%)
     */
    private val throughputVariance: Double = 0.2,
    
    /**
     * Base RTT P95 in milliseconds (default: 150ms)
     */
    private val baseRttP95: Double = 150.0,
    
    /**
     * RTT variance (0.0 to 1.0, default: 0.3 = ±30%)
     */
    private val rttVariance: Double = 0.3,
    
    /**
     * Base handshake time in milliseconds (default: 200ms)
     */
    private val baseHandshakeTime: Double = 200.0,
    
    /**
     * Handshake time variance (0.0 to 1.0, default: 0.25 = ±25%)
     */
    private val handshakeVariance: Double = 0.25,
    
    /**
     * Base packet loss rate (0.0 to 1.0, default: 0.01 = 1%)
     */
    private val baseLoss: Double = 0.01,
    
    /**
     * Loss variance (0.0 to 1.0, default: 0.5 = ±50%)
     */
    private val lossVariance: Double = 0.5
) {
    private val random: Random = if (seed != null) {
        Random(seed)
    } else {
        Random.Default
    }
    
    /**
     * Generate a single TelemetryMetrics sample with realistic network conditions.
     * 
     * @return TelemetryMetrics with generated values
     */
    fun generateMetrics(): TelemetryMetrics {
        // Generate throughput with variance
        val throughput = generateWithVariance(
            base = baseThroughput,
            variance = throughputVariance,
            min = 0.0
        )
        
        // Generate RTT P95 with variance
        val rttP95 = generateWithVariance(
            base = baseRttP95,
            variance = rttVariance,
            min = 1.0
        )
        
        // Generate handshake time with variance
        val handshakeTime = generateWithVariance(
            base = baseHandshakeTime,
            variance = handshakeVariance,
            min = 10.0
        )
        
        // Generate packet loss with variance (clamped to [0, 1])
        val loss = generateWithVariance(
            base = baseLoss,
            variance = lossVariance,
            min = 0.0,
            max = 1.0
        )
        
        return TelemetryMetrics(
            throughput = throughput,
            rttP95 = rttP95,
            handshakeTime = handshakeTime,
            loss = loss,
            timestamp = Instant.now()
        )
    }
    
    /**
     * Generate multiple metrics samples.
     * 
     * @param count Number of samples to generate
     * @return List of TelemetryMetrics
     */
    fun generateMetrics(count: Int): List<TelemetryMetrics> {
        require(count > 0) { "Count must be positive" }
        return (1..count).map { generateMetrics() }
    }
    
    /**
     * Generate metrics with a specific network condition profile.
     * 
     * @param profile Network condition profile (GOOD, AVERAGE, POOR)
     * @return TelemetryMetrics matching the profile
     */
    fun generateMetrics(profile: NetworkProfile): TelemetryMetrics {
        val (triple, loss) = when (profile) {
            NetworkProfile.GOOD -> {
                // Good: High throughput, low RTT, low loss
                Triple(
                    baseThroughput * 1.2,
                    baseRttP95 * 0.7,
                    baseHandshakeTime * 0.8
                ) to baseLoss * 0.5
            }
            NetworkProfile.AVERAGE -> {
                // Average: Baseline values
                Triple(
                    baseThroughput,
                    baseRttP95,
                    baseHandshakeTime
                ) to baseLoss
            }
            NetworkProfile.POOR -> {
                // Poor: Low throughput, high RTT, high loss
                Triple(
                    baseThroughput * 0.6,
                    baseRttP95 * 1.5,
                    baseHandshakeTime * 1.3
                ) to baseLoss * 2.0
            }
        }
        val (thr, rtt, hs) = triple
        
        return TelemetryMetrics(
            throughput = generateWithVariance(thr, throughputVariance, 0.0),
            rttP95 = generateWithVariance(rtt, rttVariance, 1.0),
            handshakeTime = generateWithVariance(hs, handshakeVariance, 10.0),
            loss = generateWithVariance(loss, lossVariance, 0.0, 1.0),
            timestamp = Instant.now()
        )
    }
    
    /**
     * Generate a value with variance around a base value.
     * 
     * @param base Base value
     * @param variance Variance factor (0.0 to 1.0)
     * @param min Minimum value (default: no minimum)
     * @param max Maximum value (default: no maximum)
     * @return Generated value with variance
     */
    private fun generateWithVariance(
        base: Double,
        variance: Double,
        min: Double = Double.NEGATIVE_INFINITY,
        max: Double = Double.POSITIVE_INFINITY
    ): Double {
        val varianceRange = base * variance
        val offset = random.nextDouble(-varianceRange, varianceRange)
        val value = base + offset
        return value.coerceIn(min, max)
    }
    
    /**
     * Network condition profiles for testing
     */
    enum class NetworkProfile {
        GOOD,      // High performance
        AVERAGE,   // Baseline performance
        POOR       // Degraded performance
    }
    
    companion object {
        /**
         * Create a ShadowTester with default good network conditions
         */
        fun createGood(): ShadowTester {
            return ShadowTester(
                baseThroughput = 15_000_000.0,
                baseRttP95 = 100.0,
                baseHandshakeTime = 150.0,
                baseLoss = 0.005
            )
        }
        
        /**
         * Create a ShadowTester with default average network conditions
         */
        fun createAverage(): ShadowTester {
            return ShadowTester()
        }
        
        /**
         * Create a ShadowTester with default poor network conditions
         */
        fun createPoor(): ShadowTester {
            return ShadowTester(
                baseThroughput = 5_000_000.0,
                baseRttP95 = 250.0,
                baseHandshakeTime = 350.0,
                baseLoss = 0.02
            )
        }
    }
}

