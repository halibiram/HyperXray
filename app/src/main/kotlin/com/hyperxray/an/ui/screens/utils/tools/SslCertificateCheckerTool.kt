package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import javax.net.ssl.HttpsURLConnection

@Composable
fun SslCertificateCheckerTool() {
    var urlInput by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SslResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun checkSsl() {
        if (urlInput.isBlank()) return
        scope.launch {
            isLoading = true
            result = null
            error = null
            try {
                val targetUrl = if (urlInput.startsWith("http")) urlInput else "https://$urlInput"
                withContext(Dispatchers.IO) {
                    val url = URL(targetUrl)
                    val conn = url.openConnection() as HttpsURLConnection
                    conn.connectTimeout = 5000
                    conn.connect()
                    
                    val certs = conn.serverCertificates
                    if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                        val cert = certs[0] as X509Certificate
                        val subject = cert.subjectDN.name
                        val issuer = cert.issuerDN.name
                        val validFrom = cert.notBefore
                        val validTo = cert.notAfter
                        val daysRemaining = (validTo.time - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)
                        
                        result = SslResult(subject, issuer, validFrom, validTo, daysRemaining)
                    } else {
                        error = "No X509Certificate found"
                    }
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
                text = "SSL Certificate Checker",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("URL / Domain") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { checkSsl() },
                    enabled = !isLoading && urlInput.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    else Icon(Icons.Default.Lock, contentDescription = "Check")
                }
            }

            if (error != null) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            result?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("Subject", it.subject.substringBefore(",")) // Simplified
                    InfoRow("Issuer", it.issuer.substringBefore(",")) // Simplified
                    InfoRow("Valid From", SimpleDateFormat("yyyy-MM-dd").format(it.validFrom))
                    InfoRow("Valid To", SimpleDateFormat("yyyy-MM-dd").format(it.validTo))
                    
                    val color = if (it.daysRemaining > 30) Color(0xFF10B981) else Color(0xFFEF4444)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Expires In", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${it.daysRemaining} days",
                            color = color,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

data class SslResult(
    val subject: String,
    val issuer: String,
    val validFrom: Date,
    val validTo: Date,
    val daysRemaining: Long
)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.widthIn(max = 200.dp))
    }
}
