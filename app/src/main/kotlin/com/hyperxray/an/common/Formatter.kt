package com.hyperxray.an.common

import kotlin.math.ln
import kotlin.math.pow

/**
 * Formats byte count into human-readable string (B, KB, MB, GB, TB).
 */
fun formatBytes(bytes: Long): String {
    if (bytes == 0L) return "0B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "%.2f%s".format(bytes / 1024.0.pow(exp), units[exp])
}

/**
 * Formats number count into human-readable string with suffix (K, M, G, T, P, E).
 */
fun formatNumber(count: Long): String {
    if (count < 1000) return count.toString()
    val suffix = listOf('K', 'M', 'G', 'T', 'P', 'E')
    val exp = (ln(count.toDouble()) / ln(1000.0)).toInt().coerceAtMost(suffix.size)
    return "%.1f%c".format(count / 1000.0.pow(exp), suffix[exp - 1])
}

/**
 * Formats uptime in seconds to HH:MM:SS format.
 */
fun formatUptime(seconds: Int): String = when {
    seconds < 0 -> "N/A"
    else -> {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        "%02d:%02d:%02d".format(hours, minutes, secs)
    }
}

/**
 * Formats throughput in bytes per second to human-readable string.
 */
fun formatThroughput(bytesPerSecond: Double): String {
    if (bytesPerSecond <= 0.0) return "0 B/s"
    // Use Double directly to preserve small values
    val bytes = bytesPerSecond.toLong()
    if (bytes == 0L && bytesPerSecond > 0.0) {
        // For very small values, show in bytes with decimal
        return "%.2f B/s".format(bytesPerSecond)
    }
    return "${formatBytes(bytes)}/s"
}

/**
 * Formats RTT (Round-Trip Time) in milliseconds.
 */
fun formatRtt(ms: Double): String {
    return "%.2f ms".format(ms)
}

/**
 * Formats packet loss as percentage.
 */
fun formatLoss(loss: Double): String {
    return "%.2f%%".format(loss * 100.0)
}

/**
 * Formats handshake time in milliseconds.
 */
fun formatHandshakeTime(ms: Double): String {
    return "%.2f ms".format(ms)
}
