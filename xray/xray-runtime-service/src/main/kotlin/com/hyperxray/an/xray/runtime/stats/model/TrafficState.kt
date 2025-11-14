package com.hyperxray.an.xray.runtime.stats.model

/**
 * Traffic statistics (uplink and downlink bytes).
 * Moved from app module to xray-runtime-service.
 */
data class TrafficState(
    val uplink: Long,
    val downlink: Long
)

