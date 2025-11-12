package com.hyperxray.an.telemetry

import java.time.Instant

/**
 * Test/demo file for reward calculation.
 * This file demonstrates the reward calculation with mock data.
 */
object RewardTest {
    /**
     * Generate sample reward output for mock data
     */
    fun demonstrateRewardCalculation() {
        println("=== Reward Calculation Demonstration ===")
        println()
        
        // Mock data scenarios
        val scenarios = listOf(
            "High Performance" to TelemetryMetrics(
                throughput = 10000000.0, // 10 MB/s
                rttP95 = 50.0, // 50ms
                handshakeTime = 100.0, // 100ms
                loss = 0.001, // 0.1% loss
                timestamp = Instant.now()
            ),
            "Medium Performance" to TelemetryMetrics(
                throughput = 5000000.0, // 5 MB/s
                rttP95 = 100.0, // 100ms
                handshakeTime = 200.0, // 200ms
                loss = 0.005, // 0.5% loss
                timestamp = Instant.now()
            ),
            "Low Performance" to TelemetryMetrics(
                throughput = 1000000.0, // 1 MB/s
                rttP95 = 200.0, // 200ms
                handshakeTime = 500.0, // 500ms
                loss = 0.02, // 2% loss
                timestamp = Instant.now()
            ),
            "Poor Performance" to TelemetryMetrics(
                throughput = 500000.0, // 0.5 MB/s
                rttP95 = 500.0, // 500ms
                handshakeTime = 1000.0, // 1000ms
                loss = 0.05, // 5% loss
                timestamp = Instant.now()
            )
        )
        
        scenarios.forEach { (name, metrics) ->
            val reward = calculateReward(metrics)
            println("Scenario: $name")
            println("  Throughput: ${metrics.throughput / 1000000.0} MB/s")
            println("  RTT P95: ${metrics.rttP95} ms")
            println("  Handshake Time: ${metrics.handshakeTime} ms")
            println("  Loss: ${metrics.loss * 100}%")
            println("  Reward: $reward")
            println()
        }
        
        println("=== Reward Formula Breakdown ===")
        println("Reward = thr*1.0 - (rtt_p95*0.6/100) - (hs_time*0.3/1000) - (loss*2*100)")
        println()
        
        // Detailed calculation example
        val example = scenarios[0].second
        val thrComponent = example.throughput * 1.0
        val rttComponent = example.rttP95 * 0.6 / 100.0
        val hsComponent = example.handshakeTime * 0.3 / 1000.0
        val lossComponent = example.loss * 2.0 * 100.0
        val totalReward = thrComponent - rttComponent - hsComponent - lossComponent
        
        println("Example calculation (High Performance):")
        println("  thr*1.0 = ${example.throughput} * 1.0 = $thrComponent")
        println("  rtt_p95*0.6/100 = ${example.rttP95} * 0.6 / 100 = $rttComponent")
        println("  hs_time*0.3/1000 = ${example.handshakeTime} * 0.3 / 1000 = $hsComponent")
        println("  loss*2*100 = ${example.loss} * 2 * 100 = $lossComponent")
        println("  Total Reward = $thrComponent - $rttComponent - $hsComponent - $lossComponent = $totalReward")
    }
}




