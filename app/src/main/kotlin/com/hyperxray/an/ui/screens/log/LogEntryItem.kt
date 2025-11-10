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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogEntryItem(
    logEntry: String,
    onClick: () -> Unit = {}
) {
    val logLevel = try { parseLogLevel(logEntry) } catch (e: Exception) { LogLevel.UNKNOWN }
    val connectionType = try { parseConnectionType(logEntry) } catch (e: Exception) { ConnectionType.UNKNOWN }
    val (timestamp, message) = try { parseLogEntry(logEntry) } catch (e: Exception) { Pair("", logEntry) }
    val containsFailed = try { logEntry.uppercase().contains("FAILED") } catch (e: Exception) { false }
    val hasSNI = try { extractSNI(logEntry) != null } catch (e: Exception) { false }
    val isSniffing = try { isSniffingLog(logEntry) } catch (e: Exception) { false }
    val isDns = try { isDnsLog(logEntry) } catch (e: Exception) { false }
    
    // Get connection type color for left border
    // Priority: Failed > DNS > Sniffing > SNI > TCP/UDP
    val borderColor = when {
        containsFailed -> MaterialTheme.colorScheme.error // Red for failed logs
        isDns -> Color(0xFF00BCD4) // Cyan for DNS logs
        isSniffing -> Color(0xFFFF6F00) // Orange for sniffing logs
        hasSNI -> Color(0xFF9C27B0) // Purple for SNI logs
        connectionType == ConnectionType.TCP -> Color(0xFF2196F3) // Blue for TCP
        connectionType == ConnectionType.UDP -> Color(0xFF4CAF50) // Green for UDP
        else -> Color.Transparent
    }
    
    // Use error container color for failed logs, cyan tint for DNS, orange tint for sniffing, purple tint for SNI logs
    val cardColor = when {
        containsFailed -> MaterialTheme.colorScheme.errorContainer
        isDns -> Color(0xFFE0F7FA) // Light cyan container
        isSniffing -> Color(0xFFFFF3E0) // Light orange container
        hasSNI -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
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
                    .padding(12.dp)
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

