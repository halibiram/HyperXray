package com.hyperxray.an.feature.dashboard

/**
 * Resource IDs provider for Dashboard feature.
 * Implemented by the app module to provide resource access.
 */
interface DashboardResources {
    val drawablePlay: Int
    val drawablePause: Int
    val drawableDashboard: Int
    val drawableCloudDownload: Int
    val drawableSettings: Int
    val drawableOptimizer: Int
    
    val stringStatsUplink: Int
    val stringStatsDownlink: Int
    val stringStatsNumGoroutine: Int
    val stringStatsNumGc: Int
    val stringStatsUptime: Int
    val stringStatsAlloc: Int
    val stringVpnDisconnected: Int
}

