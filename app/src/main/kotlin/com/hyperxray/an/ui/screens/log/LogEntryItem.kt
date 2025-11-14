package com.hyperxray.an.ui.screens.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
    // Cache all parsing results to avoid recomputation on every recomposition
    // This prevents GC pressure from repeated string operations
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
    
    val logLevel = parsedData.logLevel
    val connectionType = parsedData.connectionType
    val timestamp = parsedData.timestamp
    val message = parsedData.message
    val containsFailed = parsedData.containsFailed
    val hasSNI = parsedData.hasSNI
    val isSniffing = parsedData.isSniffing
    val isDns = parsedData.isDns
    
    // Get connection type color for left border
    // Priority: Failed > DNS > Sniffing > SNI > TCP/UDP
    val borderColor = when {
        containsFailed -> MaterialTheme.colorScheme.error // Red for failed logs
        isDns -> LogColors.dnsBorderColor() // Theme-aware cyan for DNS logs
        isSniffing -> LogColors.sniffingBorderColor() // Theme-aware orange for sniffing logs
        hasSNI -> LogColors.sniBorderColor() // Theme-aware purple for SNI logs
        connectionType == ConnectionType.TCP -> LogColors.tcpColor() // Theme-aware blue for TCP
        connectionType == ConnectionType.UDP -> LogColors.udpColor() // Theme-aware green for UDP
        else -> Color.Transparent
    }
    
    // Use error container color for failed logs, theme-aware colors for DNS, sniffing, SNI logs
    val cardColor = when {
        containsFailed -> MaterialTheme.colorScheme.errorContainer
        isDns -> LogColors.dnsContainerColor() // Theme-aware cyan container
        isSniffing -> LogColors.sniffingContainerColor() // Theme-aware orange container
        hasSNI -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
            hoveredElevation = 4.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left border indicator
            if (borderColor != Color.Transparent) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxSize()
                        .background(
                            borderColor,
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                bottomStart = 12.dp,
                                topEnd = 0.dp,
                                bottomEnd = 0.dp
                            )
                        )
                )
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Log level badge, connection type badge, SNI badge, and timestamp row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Log level badge
                    LogLevelBadge(level = logLevel)
                    
                    // Connection type badge (TCP/UDP)
                    if (connectionType != ConnectionType.UNKNOWN) {
                        Spacer(modifier = Modifier.width(6.dp))
                        ConnectionTypeBadge(type = connectionType)
                    }
                    
                    // SNI badge
                    if (hasSNI) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SNIBadge()
                    }
                    
                    // Sniffing badge
                    if (isSniffing) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SniffingBadge()
                    }
                    
                    // DNS badge
                    if (isDns) {
                        Spacer(modifier = Modifier.width(6.dp))
                        DnsBadge()
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Timestamp
                    if (timestamp.isNotEmpty()) {
                        Text(
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // Log message
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

