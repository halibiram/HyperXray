package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@Composable
fun GeoIpLookupTool() {
    var query by remember { mutableStateOf("") }
    var ipInfo by remember { mutableStateOf<IpInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun lookupIp() {
        if (query.isBlank()) return
        
        scope.launch {
            isLoading = true
            error = null
            ipInfo = null
            try {
                val response = withContext(Dispatchers.IO) {
                    URL("http://ip-api.com/json/$query").readText()
                }
                val json = JSONObject(response)
                if (json.optString("status") == "fail") {
                    error = "Lookup failed: ${json.optString("message")}"
                } else {
                    ipInfo = IpInfo(
                        ip = json.optString("query"),
                        country = json.optString("country"),
                        city = json.optString("city"),
                        isp = json.optString("isp")
                    )
                }
            } catch (e: Exception) {
                error = "Failed to lookup: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "GeoIP Lookup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter IP or Domain") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )

                Button(
                    onClick = { lookupIp() },
                    enabled = !isLoading && query.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Search")
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
                    InfoRow("IP Address", ipInfo!!.ip)
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
