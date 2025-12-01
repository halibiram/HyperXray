package com.hyperxray.an.ui.screens.log

import androidx.compose.ui.graphics.Color

enum class LogLevel {
    ERROR, WARN, INFO, DEBUG, UNKNOWN
}

enum class ConnectionType {
    TCP, UDP, UNKNOWN
}

/**
 * Parsed log data structure for caching parsed log entry information.
 * This prevents repeated parsing operations during recomposition.
 */
data class ParsedLogData(
    val logLevel: LogLevel,
    val connectionType: ConnectionType,
    val timestamp: String,
    val message: String,
    val containsFailed: Boolean,
    val hasSNI: Boolean,
    val isSniffing: Boolean,
    val isDns: Boolean,
    val isAi: Boolean = false,
    val domain: String? = null,
    val ipAddress: String? = null,
    val port: Int? = null,
    val protocol: String? = null,
    val bytesTransferred: Long? = null,
    val latencyMs: Long? = null
)

/**
 * Log statistics for dashboard display
 */
data class LogStats(
    val totalCount: Int = 0,
    val errorCount: Int = 0,
    val warnCount: Int = 0,
    val infoCount: Int = 0,
    val tcpCount: Int = 0,
    val udpCount: Int = 0,
    val dnsCount: Int = 0,
    val sniffCount: Int = 0,
    val aiCount: Int = 0,
    val uniqueDomains: Set<String> = emptySet(),
    val logsPerSecond: Float = 0f
)

/**
 * View mode for log display
 */
enum class LogViewMode {
    TERMINAL,      // Classic terminal style
    COMPACT,       // Compact list view
    DETAILED,      // Detailed card view
    TIMELINE       // Timeline visualization
}

/**
 * Sort order for logs
 */
enum class LogSortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    BY_LEVEL,
    BY_TYPE
}

/**
 * Export format options
 */
enum class LogExportFormat {
    TXT,
    JSON,
    CSV,
    HTML
}

/**
 * Futuristic color palette for log visualization
 */
object LogColorPalette {
    // Neon colors
    val NeonCyan = Color(0xFF00FFFF)
    val NeonMagenta = Color(0xFFFF00FF)
    val NeonGreen = Color(0xFF00FF99)
    val NeonOrange = Color(0xFFFF9900)
    val NeonPurple = Color(0xFFBD00FF)
    val NeonRed = Color(0xFFFF0055)
    val NeonYellow = Color(0xFFFFD700)
    val NeonBlue = Color(0xFF00AAFF)
    
    // Background colors
    val DeepBlack = Color(0xFF050505)
    val DarkGray = Color(0xFF0A0A0A)
    val MediumGray = Color(0xFF151515)
    val LightGray = Color(0xFF202020)
    
    // Status colors
    val Success = Color(0xFF00FF88)
    val Error = Color(0xFFFF3366)
    val Warning = Color(0xFFFFAA00)
    val Info = Color(0xFF00AAFF)
    
    fun getLogLevelColor(level: LogLevel): Color = when (level) {
        LogLevel.ERROR -> NeonRed
        LogLevel.WARN -> NeonYellow
        LogLevel.INFO -> NeonCyan
        LogLevel.DEBUG -> NeonGreen
        LogLevel.UNKNOWN -> Color.Gray
    }
    
    fun getConnectionTypeColor(type: ConnectionType): Color = when (type) {
        ConnectionType.TCP -> NeonGreen
        ConnectionType.UDP -> NeonCyan
        ConnectionType.UNKNOWN -> Color.Gray
    }
}

// Log parsing utilities are defined in LogParsing.kt

/**
 * Extract IP address and port from log entry
 */
fun extractIpAndPort(logEntry: String): Pair<String, Int>? {
    val ipv4Pattern = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d+)""")
    val ipv6Pattern = Regex("""\[([a-fA-F0-9:]+)\]:(\d+)""")
    
    ipv4Pattern.find(logEntry)?.let { match ->
        return Pair(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 0)
    }
    
    ipv6Pattern.find(logEntry)?.let { match ->
        return Pair(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 0)
    }
    
    return null
}
