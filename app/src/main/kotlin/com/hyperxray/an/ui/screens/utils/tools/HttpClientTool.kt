package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun HttpClientTool() {
    var urlInput by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("GET") }
    var requestBody by remember { mutableStateOf("") }
    var responseStatus by remember { mutableStateOf("") }
    var responseBody by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    
    val methods = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")
    val scope = rememberCoroutineScope()

    fun sendRequest() {
        if (urlInput.isBlank()) return
        scope.launch {
            isLoading = true
            responseStatus = ""
            responseBody = ""
            
            try {
                val targetUrl = if (urlInput.startsWith("http")) urlInput else "https://$urlInput"
                withContext(Dispatchers.IO) {
                    val url = URL(targetUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    
                    if (method in listOf("POST", "PUT", "PATCH") && requestBody.isNotEmpty()) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json") // Default to JSON
                        val writer = OutputStreamWriter(conn.outputStream)
                        writer.write(requestBody)
                        writer.flush()
                        writer.close()
                    }
                    
                    val status = conn.responseCode
                    val message = conn.responseMessage
                    responseStatus = "$status $message"
                    
                    val stream = if (status < 400) conn.inputStream else conn.errorStream
                    responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
                }
            } catch (e: Exception) {
                responseStatus = "Error"
                responseBody = e.message ?: "Unknown error"
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
                text = "HTTP Client",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Button(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(method)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        methods.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { method = m; expanded = false })
                        }
                    }
                }
                
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("URL") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                
                IconButton(onClick = { sendRequest() }, enabled = !isLoading) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    else Icon(Icons.Default.PlayArrow, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (method in listOf("POST", "PUT", "PATCH")) {
                OutlinedTextField(
                    value = requestBody,
                    onValueChange = { requestBody = it },
                    label = { Text("Request Body (JSON)") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }

            if (responseStatus.isNotEmpty()) {
                Text(
                    text = "Status: $responseStatus",
                    color = if (responseStatus.startsWith("2")) Color(0xFF10B981) else Color(0xFFEF4444),
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = responseBody,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White
                    )
                }
            }
        }
    }
}
