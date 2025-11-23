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
fun WhoisLookupTool() {
    var domain by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun lookup() {
        if (domain.isBlank()) return
        scope.launch {
            isLoading = true
            result = null
            try {
                // Using rdap.org as a proxy for RDAP/Whois data
                val response = withContext(Dispatchers.IO) {
                    URL("https://rdap.org/domain/$domain").readText()
                }
                val json = JSONObject(response)
                
                // Parse some common RDAP fields
                val handle = json.optString("handle")
                val events = json.optJSONArray("events")
                var regDate = ""
                var expDate = ""
                
                if (events != null) {
                    for (i in 0 until events.length()) {
                        val event = events.getJSONObject(i)
                        val action = event.optString("eventAction")
                        val date = event.optString("eventDate")
                        if (action == "registration") regDate = date
                        if (action == "expiration") expDate = date
                    }
                }
                
                val entities = json.optJSONArray("entities")
                var registrar = "Unknown"
                if (entities != null && entities.length() > 0) {
                     // Simplified parsing, often the first entity or one with role 'registrar'
                     registrar = entities.getJSONObject(0).optString("handle", "Unknown")
                }

                result = """
                    Handle: $handle
                    Registrar: $registrar
                    Registered: $regDate
                    Expires: $expDate
                """.trimIndent()
                
            } catch (e: Exception) {
                result = "Error: ${e.message}\n(Note: RDAP might not support all TLDs or rate limits apply)"
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
                text = "Whois / RDAP Lookup",
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
