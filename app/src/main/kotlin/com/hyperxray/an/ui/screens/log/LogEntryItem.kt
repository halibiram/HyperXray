package com.hyperxray.an.ui.screens.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.ui.theme.LogColors

@Composable
fun LogEntryItem(
    logEntry: String,
    onClick: () -> Unit = {}
) {
    val parsedData = remember(logEntry) {
        try {
            val upperEntry = logEntry.uppercase()
            val parsedLog = try { parseLogEntry(logEntry) } catch (e: Exception) { Pair("", logEntry) }
            ParsedLogData(
                logLevel = try { parseLogLevel(logEntry) } catch (e: Exception) { LogLevel.UNKNOWN },
                connectionType = try { parseConnectionType(logEntry) } catch (e: Exception) { ConnectionType.UNKNOWN },
                timestamp = parsedLog.first,
                message = parsedLog.second,
                containsFailed = try { upperEntry.contains("FAILED") } catch (e: Exception) { false },
                hasSNI = try { extractSNI(logEntry) != null } catch (e: Exception) { false },
                isSniffing = try { isSniffingLog(logEntry) } catch (e: Exception) { false },
                isDns = try { isDnsLog(logEntry) } catch (e: Exception) { false }
            )
        } catch (e: Exception) {
            ParsedLogData(
                logLevel = LogLevel.UNKNOWN,
                connectionType = ConnectionType.UNKNOWN,
                timestamp = "",
                message = logEntry,
                containsFailed = false,
                hasSNI = false,
                isSniffing = false,
                isDns = false
            )
        }
    }

    val accentColor = when {
        parsedData.containsFailed -> Color(0xFFFF0055) // Neon Red
        parsedData.logLevel == LogLevel.WARN -> Color(0xFFFFD700) // Gold
        parsedData.isDns -> Color(0xFF00FFFF) // Cyan
        parsedData.isSniffing -> Color(0xFFFF9900) // Orange
        parsedData.hasSNI -> Color(0xFFBD00FF) // Purple
        else -> Color(0xFF00FF99) // Spring Green
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFF050505)) // Match screen bg
            .padding(horizontal = 16.dp, vertical = 4.dp) // Tighter vertical padding for terminal look
    ) {
        // Timestamp Column
        if (parsedData.timestamp.isNotEmpty()) {
            Text(
                text = parsedData.timestamp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        // Content Column
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Futuristic Marker
                Text(
                    text = ">>",
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.width(6.dp))

                // Badges (Text-only for terminal look)
                if (parsedData.logLevel != LogLevel.UNKNOWN && parsedData.logLevel != LogLevel.INFO) {
                    Text(
                        text = "[${parsedData.logLevel.name}]",
                        color = accentColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                
                if (parsedData.connectionType != ConnectionType.UNKNOWN) {
                    Text(
                        text = "[${parsedData.connectionType.name}]",
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            Text(
                text = parsedData.message,
                color = if (parsedData.containsFailed) Color(0xFFFF5555) else Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            
            // Separator line for that "scanline" feel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(1.dp)
                    .background(Color(0xFF151515))
            )
        }
    }
}
