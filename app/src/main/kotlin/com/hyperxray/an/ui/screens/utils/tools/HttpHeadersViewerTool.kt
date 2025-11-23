package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
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
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun HttpHeadersViewerTool() {
    var urlInput by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf<Map<String, List<String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetchHeaders() {
        if (urlInput.isBlank()) return
        scope.launch {
            isLoading = true
            headers = null
            error = null
            try {
                val targetUrl = if (urlInput.startsWith("http")) urlInput else "https://$urlInput"
                withContext(Dispatchers.IO) {
                    val url = URL(targetUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5000
                    conn.connect()
                    headers = conn.headerFields
                    conn.disconnect()
                }
            } catch (e: Exception) {
                error = "Error: ${e.message}"
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
                text = "HTTP Headers Viewer",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("URL") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { fetchHeaders() },
                    enabled = !isLoading && urlInput.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    else Icon(Icons.Default.List, contentDescription = "Fetch")
                }
            }

            if (error != null) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            headers?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    it.forEach { (key, value) ->
                        if (key != null) { // key is null for status line
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = value.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    modifier = Modifier.widthIn(max = 200.dp)
                                )
                            }
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}
