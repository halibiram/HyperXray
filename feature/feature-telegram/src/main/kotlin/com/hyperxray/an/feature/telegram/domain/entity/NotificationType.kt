package com.hyperxray.an.feature.telegram.domain.entity

/**
 * Telegram notification types
 */
sealed class NotificationType {
    object VpnStatus : NotificationType()
    object Error : NotificationType()
    object PerformanceMetrics : NotificationType()
    object Manual : NotificationType()
    
    val displayName: String
        get() = when (this) {
            is VpnStatus -> "VPN Status"
            is Error -> "Error Notifications"
            is PerformanceMetrics -> "Performance Metrics"
            is Manual -> "Manual Notifications"
        }
}

