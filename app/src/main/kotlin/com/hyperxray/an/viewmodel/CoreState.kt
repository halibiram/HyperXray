package com.hyperxray.an.viewmodel

/**
 * Traffic statistics (uplink and downlink bytes).
 */
data class TrafficState(
    val uplink: Long,
    val downlink: Long
)

/**
 * Complete statistics state from Xray-core including traffic, memory, and runtime metrics.
 */
data class CoreStatsState(
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkThroughput: Double = 0.0, // bytes per second
    val downlinkThroughput: Double = 0.0, // bytes per second
    val numGoroutine: Int = 0,
    val numGC: Int = 0,
    val alloc: Long = 0,
    val totalAlloc: Long = 0,
    val sys: Long = 0,
    val mallocs: Long = 0,
    val frees: Long = 0,
    val liveObjects: Long = 0,
    val pauseTotalNs: Long = 0,
    val uptime: Int = 0
)