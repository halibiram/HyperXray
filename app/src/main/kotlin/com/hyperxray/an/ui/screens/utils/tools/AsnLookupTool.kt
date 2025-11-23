package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
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
fun AsnLookupTool() {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun lookup() {
        if (query.isBlank()) return
        scope.launch {
            isLoading = true
            result = null
            try {
                // Using ip-api.com which supports ASN lookup via IP or just IP info including ASN
                // For direct ASN info, we might need a specific endpoint, but ip-api gives ASN for an IP.
                // If user enters AS number, we might need a different API like bgpview.io or similar.
                // Let's try to support IP first, or use a free ASN API.
                // Using ip-api.com for IP-based ASN info is reliable.
                
                val response = withContext(Dispatchers.IO) {
                    URL("http://ip-api.com/json/$query?fields=status,message,country,isp,org,as").readText()
                }
                
                val json = JSONObject(response)
                if (json.optString("status") == "success") {
                    result = """
                        ASN: ${json.optString("as")}
                        ISP: ${json.optString("isp")}
                        Org: ${json.optString("org")}
                        Country: ${json.optString("country")}
                    """.trimIndent()
                } else {
                    result = "Error: ${json.optString("message")}"
                }
            } catch (e: Exception) {
                result = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
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
                text = "ASN / IP Lookup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("IP Address") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { lookup() },
                    enabled = !isLoading && query.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    else Icon(Icons.Default.Search, contentDescription = "Lookup")
                }
            }

            result?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                )
            }
        }
    }
}
