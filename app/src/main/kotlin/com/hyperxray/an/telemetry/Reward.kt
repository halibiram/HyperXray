package com.hyperxray.an.telemetry

/**
 * Calculate reward based on telemetry metrics.
 * 
 * Reward formula: thr*1.0 - (rtt_p95*0.6/100) - (hs_time*0.3/1000) - (loss*2*100)
 * 
 * Where:
 * - thr: Throughput in bytes per second
 * - rtt_p95: 95th percentile RTT in milliseconds
 * - hs_time: Handshake time in milliseconds
 * - loss: Packet loss rate (0.0 to 1.0)
 * 
 * @param metrics Telemetry metrics to calculate reward from
 * @return Reward value as Double (higher is better)
 */
fun calculateReward(metrics: TelemetryMetrics): Double {
    val thr = metrics.throughput
    val rttP95 = metrics.rttP95
    val hsTime = metrics.handshakeTime
    val loss = metrics.loss
    
    // Reward formula: thr*1.0 - (rtt_p95*0.6/100) - (hs_time*0.3/1000) - (loss*2*100)
    val reward = thr * 1.0 - (rttP95 * 0.6 / 100.0) - (hsTime * 0.3 / 1000.0) - (loss * 2.0 * 100.0)
    
    return reward
}

/**
 * Calculate reward from aggregated telemetry data
 */
fun calculateReward(aggregated: AggregatedTelemetry): Double {
    val metrics = TelemetryMetrics(
        throughput = aggregated.avgThroughput,
        rttP95 = aggregated.rttP95,
        handshakeTime = aggregated.avgHandshakeTime,
        loss = aggregated.avgLoss,
        timestamp = aggregated.windowEnd
    )
    return calculateReward(metrics)
}

/**
 * Calculate reward from individual metric values
 */
fun calculateReward(
    throughput: Double,
    rttP95: Double,
    handshakeTime: Double,
    loss: Double
): Double {
    val metrics = TelemetryMetrics(
        throughput = throughput,
        rttP95 = rttP95,
        handshakeTime = handshakeTime,
        loss = loss
    )
    return calculateReward(metrics)
}



