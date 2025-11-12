package com.hyperxray.an.common

import android.util.Log
import com.hyperxray.an.telemetry.TelemetryMetrics

/**
 * Safeguard monitors connection metrics and enforces safety thresholds.
 * Prevents rollout of configurations that exceed acceptable performance limits.
 *
 * Thresholds:
 * - Handshake time: < 1200ms
 * - Packet loss: < 0.03 (3%)
 * - RTT P95: < 300ms
 */
object Safeguard {
    private const val TAG = "Safeguard"
    
    // Safety thresholds
    private const val MAX_HANDSHAKE_MS = 1200.0
    private const val MAX_LOSS = 0.03
    private const val MAX_RTT_P95_MS = 300.0
    
    /**
     * Result of a safeguard check
     */
    data class SafeguardResult(
        val ok: Boolean,
        val violations: List<String>,
        val metrics: TelemetryMetrics
    ) {
        fun isOk(): Boolean = ok && violations.isEmpty()
    }
    
    /**
     * Checks if metrics pass all safety thresholds.
     * 
     * @param metrics Telemetry metrics to validate
     * @return SafeguardResult with pass/fail status and violation details
     */
    fun check(metrics: TelemetryMetrics): SafeguardResult {
        val violations = mutableListOf<String>()
        
        // Check handshake time
        if (metrics.handshakeTime >= MAX_HANDSHAKE_MS) {
            violations.add("Handshake time ${metrics.handshakeTime}ms exceeds threshold ${MAX_HANDSHAKE_MS}ms")
        }
        
        // Check packet loss
        if (metrics.loss >= MAX_LOSS) {
            violations.add("Packet loss ${metrics.loss} (${metrics.loss * 100}%) exceeds threshold ${MAX_LOSS * 100}%")
        }
        
        // Check RTT P95
        if (metrics.rttP95 >= MAX_RTT_P95_MS) {
            violations.add("RTT P95 ${metrics.rttP95}ms exceeds threshold ${MAX_RTT_P95_MS}ms")
        }
        
        val ok = violations.isEmpty()
        
        if (ok) {
            Log.d(TAG, "Safeguard check PASSED: hs=${metrics.handshakeTime}ms, loss=${metrics.loss}, rtt_p95=${metrics.rttP95}ms")
        } else {
            Log.w(TAG, "Safeguard check FAILED: ${violations.size} violation(s)")
            violations.forEach { Log.w(TAG, "  - $it") }
        }
        
        return SafeguardResult(ok, violations, metrics)
    }
    
    /**
     * Convenience method that returns true if metrics pass all thresholds.
     */
    fun ok(metrics: TelemetryMetrics): Boolean {
        return check(metrics).isOk()
    }
    
    /**
     * Gets the maximum allowed handshake time in milliseconds.
     */
    fun getMaxHandshakeMs(): Double = MAX_HANDSHAKE_MS
    
    /**
     * Gets the maximum allowed packet loss rate (0.0 to 1.0).
     */
    fun getMaxLoss(): Double = MAX_LOSS
    
    /**
     * Gets the maximum allowed RTT P95 in milliseconds.
     */
    fun getMaxRttP95Ms(): Double = MAX_RTT_P95_MS
}




