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
import java.net.URL

@Composable
fun MacAddressLookupTool() {
    var macAddress by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun lookup() {
        if (macAddress.isBlank()) return
        scope.launch {
            isLoading = true
            vendor = null
            error = null
            try {
                val response = withContext(Dispatchers.IO) {
                    try {
                        URL("https://api.macvendors.com/$macAddress").readText()
                    } catch (e: Exception) {
                        if (e.message?.contains("404") == true) "Vendor not found" else throw e
                    }
                }
                vendor = response
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
                text = "MAC Address Lookup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = macAddress,
                    onValueChange = { macAddress = it },
                    label = { Text("MAC Address") },
                    placeholder = { Text("00:11:22:33:44:55") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { lookup() },
                    enabled = !isLoading && macAddress.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    else Icon(Icons.Default.Search, contentDescription = "Lookup")
                }
            }

            if (error != null) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            vendor?.let {
                Column {
                    Text(text = "Vendor", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
