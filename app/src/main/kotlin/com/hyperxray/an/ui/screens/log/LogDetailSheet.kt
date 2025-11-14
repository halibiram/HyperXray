package com.hyperxray.an.ui.screens.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.hyperxray.an.ui.theme.LogColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailSheet(logEntry: String) {
    val logLevel = try { parseLogLevel(logEntry) } catch (e: Exception) { LogLevel.UNKNOWN }
    val connectionType = try { parseConnectionType(logEntry) } catch (e: Exception) { ConnectionType.UNKNOWN }
    val (timestamp, message) = try { parseLogEntry(logEntry) } catch (e: Exception) { Pair("", logEntry) }
    val isSniffing = try { isSniffingLog(logEntry) } catch (e: Exception) { false }
    val sniffedDomain = try { extractSniffedDomain(logEntry) } catch (e: Exception) { null }
    val isDns = try { isDnsLog(logEntry) } catch (e: Exception) { false }
    val dnsQuery = try { extractDnsQuery(logEntry) } catch (e: Exception) { null }
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(24.dp)
    ) {
        // Header
        Text(
            text = "Log Details",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
        
        // Log Level
        DetailRow(
            label = "Log Level",
            value = logLevel.name,
            badge = { LogLevelBadge(level = logLevel) }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Connection Type
        if (connectionType != ConnectionType.UNKNOWN) {
            DetailRow(
                label = "Connection Type",
                value = connectionType.name,
                badge = { ConnectionTypeBadge(type = connectionType) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // Sniffing
        if (isSniffing) {
            DetailRow(
                label = "Sniffing",
                value = "Enabled",
                badge = { SniffingBadge() }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // DNS
        if (isDns) {
            DetailRow(
                label = "DNS",
                value = "Query/Resolve",
                badge = { DnsBadge() }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // Timestamp
        if (timestamp.isNotEmpty()) {
            DetailRow(
                label = "Timestamp",
                value = timestamp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // Full Message
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Full Message",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = logEntry,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp),
                    lineHeight = 20.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Parsed Message
        if (message.isNotEmpty() && message != logEntry) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Message Content",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(16.dp),
                        lineHeight = 20.sp
                    )
                }
            }
        }
        
        // DNS Information
        if (isDns && dnsQuery != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "DNS Information",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LogColors.dnsContainerColor() // Theme-aware cyan container
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "DNS Query: ",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LogColors.dnsTextColor()
                            )
                            Text(
                                text = dnsQuery,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = LogColors.dnsTextColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        
        // Sniffing Information
        if (isSniffing && sniffedDomain != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Sniffing Information",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LogColors.sniffingContainerColor() // Theme-aware orange container
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "Sniffed Domain: ",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LogColors.sniffingTextColor()
                            )
                            Text(
                                text = sniffedDomain,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = LogColors.sniffingTextColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        
        // SNI Information
        val sniInfo = try { extractSNI(logEntry) } catch (e: Exception) { null }
        val certInfo = try { extractCertificateInfo(logEntry) } catch (e: Exception) { null }
        
        if (sniInfo != null || certInfo != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "TLS/SSL Information",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // SNI
                        if (sniInfo != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "SNI: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = sniInfo,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (certInfo != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        
                        // Certificate Information
                        certInfo?.forEach { (key, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${key.replaceFirstChar { it.uppercaseChar() }}: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Additional Info
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        
        Column {
            Text(
                text = "Additional Information",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            InfoItem("Length", "${logEntry.length} characters")
            InfoItem("Contains TLS/SSL", if (logEntry.uppercase().contains("TLS") || logEntry.uppercase().contains("SSL")) "Yes" else "No")
            InfoItem("Contains Handshake", if (logEntry.uppercase().contains("HANDSHAKE")) "Yes" else "No")
            InfoItem("Contains Certificate", if (logEntry.uppercase().contains("CERTIFICATE")) "Yes" else "No")
            InfoItem("Contains SNI", if (sniInfo != null) "Yes" else "No")
            InfoItem("Contains Sniffing", if (isSniffing) "Yes" else "No")
            InfoItem("Contains DNS", if (isDns) "Yes" else "No")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    badge: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (badge != null) {
                badge()
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (label == "Timestamp") FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

