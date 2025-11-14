package com.hyperxray.an.ui.screens.log

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
    val isDns: Boolean
)

