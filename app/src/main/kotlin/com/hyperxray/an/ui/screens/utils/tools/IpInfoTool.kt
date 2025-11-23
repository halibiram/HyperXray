package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@Composable
fun IpInfoTool() {
    var ipInfo by remember { mutableStateOf<IpInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun fetchIpInfo() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val response = withContext(Dispatchers.IO) {
                    URL("http://ip-api.com/json").readText()
                }
                val json = JSONObject(response)
                ipInfo = IpInfo(
                    ip = json.optString("query"),
                    country = json.optString("country"),
                    city = json.optString("city"),
                    isp = json.optString("isp")
                )
            } catch (e: Exception) {
                error = "Failed to fetch IP info: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchIpInfo()
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IP Information",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                IconButton(onClick = { fetchIpInfo() }, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (ipInfo != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Public IP", ipInfo!!.ip)
                    InfoRow("Location", "${ipInfo!!.city}, ${ipInfo!!.country}")
                    InfoRow("ISP", ipInfo!!.isp)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
    }
}

data class IpInfo(
    val ip: String,
    val country: String,
    val city: String,
    val isp: String
)
