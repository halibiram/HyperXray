package com.hyperxray.an.ui.screens.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailSheet(logEntry: String) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // Parse log data
    val logLevel = try { parseLogLevel(logEntry) } catch (e: Exception) { LogLevel.UNKNOWN }
    val connectionType = try { parseConnectionType(logEntry) } catch (e: Exception) { ConnectionType.UNKNOWN }
    val (timestamp, message) = try { parseLogEntry(logEntry) } catch (e: Exception) { Pair("", logEntry) }
    val isSniffing = try { isSniffingLog(logEntry) } catch (e: Exception) { false }
    val sniffedDomain = try { extractSniffedDomain(logEntry) } catch (e: Exception) { null }
    val isDns = try { isDnsLog(logEntry) } catch (e: Exception) { false }
    val dnsQuery = try { extractDnsQuery(logEntry) } catch (e: Exception) { null }
    val isAi = try { isAiLog(logEntry) } catch (e: Exception) { false }
    val sniInfo = try { extractSNI(logEntry) } catch (e: Exception) { null }
    val certInfo = try { extractCertificateInfo(logEntry) } catch (e: Exception) { null }
    val ipPort = try { extractIpAndPort(logEntry) } catch (e: Exception) { null }
    
    val accentColor = when {
        logLevel == LogLevel.ERROR -> LogColorPalette.NeonRed
        logLevel == LogLevel.WARN -> LogColorPalette.NeonYellow
        isAi -> LogColorPalette.NeonPurple
        isDns -> LogColorPalette.NeonCyan
        isSniffing -> LogColorPalette.NeonOrange
        else -> LogColorPalette.NeonGreen
    }
    
    // Animations
    val infiniteTransition = rememberInfiniteTransition(label = "detail")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {
        // Header with badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOG DETAILS",
                color = accentColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp
            )
            
            // Copy button
            IconButton(
                onClick = { copyToClipboard(context, logEntry) },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.1f))
                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Badges row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CyberBadge(text = logLevel.name, color = LogColorPalette.getLogLevelColor(logLevel))
            if (connectionType != ConnectionType.UNKNOWN) {
                CyberBadge(text = connectionType.name, color = LogColorPalette.getConnectionTypeColor(connectionType))
            }
            if (isDns) CyberBadge(text = "DNS", color = LogColorPalette.NeonCyan)
            if (isSniffing) CyberBadge(text = "SNIFFING", color = LogColorPalette.NeonOrange)
            if (isAi) CyberBadge(text = "AI", color = LogColorPalette.NeonPurple)
            if (sniInfo != null) CyberBadge(text = "SNI", color = LogColorPalette.NeonMagenta)
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Info cards grid
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Timestamp
            if (timestamp.isNotEmpty()) {
                InfoCard(
                    icon = Icons.Default.Schedule,
                    title = "TIMESTAMP",
                    value = timestamp,
                    color = LogColorPalette.NeonCyan
                )
            }
            
            // Domain info
            val domain = sniffedDomain ?: dnsQuery ?: sniInfo
            if (domain != null) {
                InfoCard(
                    icon = Icons.Default.Language,
                    title = if (isDns) "DNS QUERY" else if (isSniffing) "SNIFFED DOMAIN" else "SNI",
                    value = domain,
                    color = LogColorPalette.NeonMagenta
                )
            }
            
            // IP Address
            if (ipPort != null) {
                InfoCard(
                    icon = Icons.Default.Router,
                    title = "ENDPOINT",
                    value = "${ipPort.first}:${ipPort.second}",
                    color = LogColorPalette.NeonGreen
                )
            }
            
            // TLS/Certificate info
            if (certInfo != null) {
                TlsInfoCard(certInfo = certInfo, color = LogColorPalette.NeonPurple)
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Full message section
        Text(
            text = "RAW LOG",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF050508))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = glowAlpha * 0.3f),
                            accentColor.copy(alpha = glowAlpha * 0.1f),
                            accentColor.copy(alpha = glowAlpha * 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Column {
                // Line numbers effect
                Row {
                    Column(
                        modifier = Modifier.padding(end = 12.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        logEntry.lines().forEachIndexed { index, _ ->
                            Text(
                                text = "${index + 1}",
                                color = Color.Gray.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                    
                    // Vertical separator
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(IntrinsicSize.Max)
                            .background(Color(0xFF202020))
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // Log content
                    Text(
                        text = logEntry,
                        color = Color(0xFFE0E0E0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Analysis section
        AnalysisSection(logEntry = logEntry, accentColor = accentColor)
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    value: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.05f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TlsInfoCard(
    certInfo: Map<String, String>,
    color: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.05f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "TLS/SSL INFORMATION",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            certInfo.forEach { (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${key.uppercase()}:",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.width(80.dp)
                    )
                    Text(
                        text = value,
                        color = Color(0xFFE0E0E0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisSection(
    logEntry: String,
    accentColor: Color
) {
    val upperEntry = logEntry.uppercase()
    
    Column {
        Text(
            text = "ANALYSIS",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF080810))
                .border(1.dp, Color(0xFF151520), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisItem(
                    label = "Length",
                    value = "${logEntry.length} chars",
                    color = accentColor
                )
                AnalysisItem(
                    label = "Contains TLS/SSL",
                    value = if (upperEntry.contains("TLS") || upperEntry.contains("SSL")) "Yes" else "No",
                    color = if (upperEntry.contains("TLS") || upperEntry.contains("SSL")) LogColorPalette.NeonGreen else Color.Gray
                )
                AnalysisItem(
                    label = "Contains Handshake",
                    value = if (upperEntry.contains("HANDSHAKE")) "Yes" else "No",
                    color = if (upperEntry.contains("HANDSHAKE")) LogColorPalette.NeonGreen else Color.Gray
                )
                AnalysisItem(
                    label = "Contains Error",
                    value = if (upperEntry.contains("ERROR") || upperEntry.contains("FAILED")) "Yes" else "No",
                    color = if (upperEntry.contains("ERROR") || upperEntry.contains("FAILED")) LogColorPalette.NeonRed else Color.Gray
                )
                AnalysisItem(
                    label = "Contains IP Address",
                    value = if (extractIpAndPort(logEntry) != null) "Yes" else "No",
                    color = if (extractIpAndPort(logEntry) != null) LogColorPalette.NeonCyan else Color.Gray
                )
            }
        }
    }
}

@Composable
private fun AnalysisItem(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Log Entry", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
