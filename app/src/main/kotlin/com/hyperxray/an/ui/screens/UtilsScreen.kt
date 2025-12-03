package com.hyperxray.an.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hyperxray.an.common.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtilsScreen(
    navController: NavHostController? = null
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Management Tools Section
            item {
                ToolSectionHeader("Management Tools")
            }
            item {
                UtilsToolCard(
                    title = "WARP Account",
                    description = "Register and manage Cloudflare WARP account",
                    onClick = { navController?.navigate(ROUTE_UTILS_WARP) }
                )
            }

            // Network Tools Section
            item {
                ToolSectionHeader("Network Tools")
            }
            item {
                UtilsToolCard(
                    title = "IP Information",
                    description = "Get your public IP and location info",
                    onClick = { navController?.navigate(ROUTE_UTILS_IP_INFO) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Ping Test",
                    description = "Test latency to DNS servers",
                    onClick = { navController?.navigate(ROUTE_UTILS_PING_TEST) }
                )
            }
            item {
                UtilsToolCard(
                    title = "GeoIP Lookup",
                    description = "Lookup IP geolocation information",
                    onClick = { navController?.navigate(ROUTE_UTILS_GEOIP_LOOKUP) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Network Info",
                    description = "View device network information",
                    onClick = { navController?.navigate(ROUTE_UTILS_NETWORK_INFO) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Speed Test",
                    description = "Test your internet connection speed",
                    onClick = { navController?.navigate(ROUTE_UTILS_SPEED_TEST) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Port Scanner",
                    description = "Scan open ports on a target",
                    onClick = { navController?.navigate(ROUTE_UTILS_PORT_SCANNER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Traceroute",
                    description = "Trace network path to destination",
                    onClick = { navController?.navigate(ROUTE_UTILS_TRACEROUTE) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Wake on LAN",
                    description = "Send magic packet to wake device",
                    onClick = { navController?.navigate(ROUTE_UTILS_WAKE_ON_LAN) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Subnet Calculator",
                    description = "Calculate subnet information",
                    onClick = { navController?.navigate(ROUTE_UTILS_SUBNET_CALCULATOR) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Whois Lookup",
                    description = "Lookup domain registration info",
                    onClick = { navController?.navigate(ROUTE_UTILS_WHOIS_LOOKUP) }
                )
            }
            item {
                UtilsToolCard(
                    title = "DNS Lookup",
                    description = "Query DNS records",
                    onClick = { navController?.navigate(ROUTE_UTILS_DNS_LOOKUP) }
                )
            }
            item {
                UtilsToolCard(
                    title = "SSL Certificate Checker",
                    description = "Check SSL certificate details",
                    onClick = { navController?.navigate(ROUTE_UTILS_SSL_CERT_CHECKER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "HTTP Headers Viewer",
                    description = "View HTTP request/response headers",
                    onClick = { navController?.navigate(ROUTE_UTILS_HTTP_HEADERS_VIEWER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "LAN Scanner",
                    description = "Scan devices on local network",
                    onClick = { navController?.navigate(ROUTE_UTILS_LAN_SCANNER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "HTTP Client",
                    description = "Send HTTP requests and view responses",
                    onClick = { navController?.navigate(ROUTE_UTILS_HTTP_CLIENT) }
                )
            }
            item {
                UtilsToolCard(
                    title = "ASN Lookup",
                    description = "Lookup Autonomous System Number info",
                    onClick = { navController?.navigate(ROUTE_UTILS_ASN_LOOKUP) }
                )
            }

            // General Utilities Section
            item {
                ToolSectionHeader("General Utilities")
            }
            item {
                UtilsToolCard(
                    title = "MAC Address Lookup",
                    description = "Lookup MAC address vendor info",
                    onClick = { navController?.navigate(ROUTE_UTILS_MAC_ADDRESS_LOOKUP) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Unit Converter",
                    description = "Convert between data units",
                    onClick = { navController?.navigate(ROUTE_UTILS_UNIT_CONVERTER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Base64 Converter",
                    description = "Encode/decode Base64 strings",
                    onClick = { navController?.navigate(ROUTE_UTILS_BASE64_CONVERTER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "URL Converter",
                    description = "Encode/decode URL strings",
                    onClick = { navController?.navigate(ROUTE_UTILS_URL_CONVERTER) }
                )
            }
            item {
                UtilsToolCard(
                    title = "Password Generator",
                    description = "Generate secure passwords",
                    onClick = { navController?.navigate(ROUTE_UTILS_PASSWORD_GENERATOR) }
                )
            }
        }
    }
}

@Composable
fun ToolSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
    )
}

@Composable
fun UtilsToolCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A).copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
