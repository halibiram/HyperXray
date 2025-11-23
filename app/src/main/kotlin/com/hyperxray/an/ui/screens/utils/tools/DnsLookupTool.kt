package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
fun DnsLookupTool() {
    var domain by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("A") }
    var result by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val recordTypes = listOf("A", "AAAA", "MX", "TXT", "NS", "CNAME")

    fun lookup() {
        if (domain.isBlank()) return
        scope.launch {
            isLoading = true
            result = null
            try {
                // Using Cloudflare DNS over HTTPS
                val response = withContext(Dispatchers.IO) {
                    val url = URL("https://cloudflare-dns.com/dns-query?name=$domain&type=$type")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.setRequestProperty("Accept", "application/dns-json")
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                
                val json = JSONObject(response)
                val status = json.optInt("Status")
                if (status != 0) {
                    result = "Error: DNS Status $status"
                } else {
                    val answers = json.optJSONArray("Answer")
                    if (answers == null || answers.length() == 0) {
                        result = "No records found."
                    } else {
                        val sb = StringBuilder()
                        for (i in 0 until answers.length()) {
                            val rec = answers.getJSONObject(i)
                            sb.append(rec.optString("data")).append("\n")
                        }
                        result = sb.toString().trim()
                    }
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
                text = "DNS Lookup (DoH)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domain") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                
                Box {
                    Button(onClick = { expanded = true }) {
                        Text(type)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        recordTypes.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = { 
                                    type = t
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = { lookup() },
                    enabled = !isLoading && domain.isNotBlank(),
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
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
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
