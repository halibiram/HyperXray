package com.hyperxray.an.ui.screens.utils.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hyperxray.an.ui.theme.*
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class ParsedConfig(
    val protocol: String,
    val address: String,
    val port: Int,
    val uuid: String = "",
    val alterId: Int = 0,
    val security: String = "",
    val network: String = "",
    val path: String = "",
    val host: String = "",
    val tls: String = "",
    val sni: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val remarks: String = "",
    val raw: Map<String, String> = emptyMap()
)

@Composable
fun ConfigParserTool() {
    val context = LocalContext.current
    var configUrl by remember { mutableStateOf("") }
    var parsedConfig by remember { mutableStateOf<ParsedConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun parseVlessUrl(url: String): ParsedConfig {
        // vless://uuid@address:port?params#remarks
        val uri = URI(url)
        val userInfo = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port
        val query = uri.query ?: ""
        val fragment = uri.fragment ?: ""
        
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        
        return ParsedConfig(
            protocol = "VLESS",
            address = host,
            port = port,
            uuid = userInfo,
            security = params["security"] ?: "none",
            network = params["type"] ?: "tcp",
            path = URLDecoder.decode(params["path"] ?: "", "UTF-8"),
            host = params["host"] ?: "",
            tls = params["security"] ?: "",
            sni = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            remarks = URLDecoder.decode(fragment, "UTF-8"),
            raw = params
        )
    }

    fun parseVmessUrl(url: String): ParsedConfig {
        // vmess://base64encoded
        val base64Part = url.removePrefix("vmess://")
        val decoded = String(Base64.decode(base64Part, Base64.DEFAULT))
        val json = JSONObject(decoded)
        
        return ParsedConfig(
            protocol = "VMess",
            address = json.optString("add", ""),
            port = json.optInt("port", 0),
            uuid = json.optString("id", ""),
            alterId = json.optInt("aid", 0),
            security = json.optString("scy", "auto"),
            network = json.optString("net", "tcp"),
            path = json.optString("path", ""),
            host = json.optString("host", ""),
            tls = json.optString("tls", ""),
            sni = json.optString("sni", ""),
            remarks = json.optString("ps", ""),
            raw = mapOf(
                "v" to json.optString("v", "2"),
                "type" to json.optString("type", "none")
            )
        )
    }

    fun parseTrojanUrl(url: String): ParsedConfig {
        // trojan://password@address:port?params#remarks
        val uri = URI(url)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port
        val query = uri.query ?: ""
        val fragment = uri.fragment ?: ""
        
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        
        return ParsedConfig(
            protocol = "Trojan",
            address = host,
            port = port,
            uuid = password,
            security = "tls",
            network = params["type"] ?: "tcp",
            path = URLDecoder.decode(params["path"] ?: "", "UTF-8"),
            host = params["host"] ?: "",
            tls = "tls",
            sni = params["sni"] ?: host,
            remarks = URLDecoder.decode(fragment, "UTF-8"),
            raw = params
        )
    }

    fun parseShadowsocksUrl(url: String): ParsedConfig {
        // ss://base64(method:password)@address:port#remarks
        val withoutPrefix = url.removePrefix("ss://")
        val parts = withoutPrefix.split("@")
        
        val (method, password) = if (parts.size == 2) {
            val decoded = String(Base64.decode(parts[0], Base64.DEFAULT))
            val methodParts = decoded.split(":", limit = 2)
            methodParts[0] to methodParts.getOrElse(1) { "" }
        } else {
            "" to ""
        }
        
        val hostPart = parts.getOrElse(1) { parts[0] }
        val hostPortRemarks = hostPart.split("#")
        val hostPort = hostPortRemarks[0].split(":")
        val remarks = hostPortRemarks.getOrElse(1) { "" }
        
        return ParsedConfig(
            protocol = "Shadowsocks",
            address = hostPort.getOrElse(0) { "" },
            port = hostPort.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
            uuid = password,
            security = method,
            remarks = URLDecoder.decode(remarks, "UTF-8"),
            raw = mapOf("method" to method)
        )
    }

    fun parseConfig() {
        if (configUrl.isBlank()) {
            error = "Please enter a config URL"
            return
        }
        
        try {
            error = null
            parsedConfig = when {
                configUrl.startsWith("vless://") -> parseVlessUrl(configUrl)
                configUrl.startsWith("vmess://") -> parseVmessUrl(configUrl)
                configUrl.startsWith("trojan://") -> parseTrojanUrl(configUrl)
                configUrl.startsWith("ss://") -> parseShadowsocksUrl(configUrl)
                else -> throw IllegalArgumentException("Unsupported protocol")
            }
        } catch (e: Exception) {
            error = "Parse failed: ${e.message}"
            parsedConfig = null
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            configUrl = clip.getItemAt(0).text?.toString() ?: ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Input Card
        GlassCard(glowColor = FuturisticColors.NeonPurple) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "CONFIG PARSER",
                    color = FuturisticColors.NeonPurple,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Parse VLESS, VMess, Trojan, and Shadowsocks configuration URLs",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
                
                OutlinedTextField(
                    value = configUrl,
                    onValueChange = { configUrl = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    label = { Text("Config URL") },
                    placeholder = { Text("vless://... vmess://... trojan://... ss://...") },
                    trailingIcon = {
                        IconButton(onClick = { pasteFromClipboard() }) {
                            Icon(Icons.Default.ContentPaste, null, tint = FuturisticColors.NeonPurple)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FuturisticColors.NeonPurple,
                        unfocusedBorderColor = FuturisticColors.NeonPurple.copy(alpha = 0.3f)
                    )
                )
                
                CyberButton(
                    onClick = { parseConfig() },
                    enabled = configUrl.isNotBlank(),
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Code, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PARSE CONFIG", fontWeight = FontWeight.Bold)
                }
            }
        }
        
        // Parsed Result Card
        parsedConfig?.let { config ->
            GlassCard(glowColor = FuturisticColors.NeonGreen) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        NeonText(
                            text = "PARSED CONFIG",
                            color = FuturisticColors.NeonGreen,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Surface(shape = RoundedCornerShape(8.dp), color = getProtocolColor(config.protocol).copy(alpha = 0.2f)) {
                            Text(config.protocol, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = getProtocolColor(config.protocol), fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    if (config.remarks.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = FuturisticColors.NeonCyan.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Label, null, tint = FuturisticColors.NeonCyan, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(config.remarks, color = FuturisticColors.NeonCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    
                    HorizontalDivider(color = FuturisticColors.NeonGreen.copy(alpha = 0.2f))
                    
                    // Server Info
                    ConfigField("Address", config.address, FuturisticColors.NeonCyan)
                    ConfigField("Port", "${config.port}", FuturisticColors.NeonYellow)
                    
                    if (config.uuid.isNotEmpty()) {
                        ConfigField(if (config.protocol == "Shadowsocks") "Password" else "UUID", 
                            config.uuid.take(8) + "..." + config.uuid.takeLast(4), FuturisticColors.NeonMagenta)
                    }
                    
                    HorizontalDivider(color = FuturisticColors.NeonGreen.copy(alpha = 0.2f))
                    
                    // Transport
                    if (config.network.isNotEmpty()) ConfigField("Network", config.network, FuturisticColors.NeonPurple)
                    if (config.security.isNotEmpty()) ConfigField("Security", config.security, FuturisticColors.NeonOrange)
                    if (config.tls.isNotEmpty()) ConfigField("TLS", config.tls, FuturisticColors.NeonGreen)
                    if (config.sni.isNotEmpty()) ConfigField("SNI", config.sni, FuturisticColors.NeonBlue)
                    if (config.host.isNotEmpty()) ConfigField("Host", config.host, FuturisticColors.NeonCyan)
                    if (config.path.isNotEmpty()) ConfigField("Path", config.path, FuturisticColors.NeonYellow)
                    
                    // Reality specific
                    if (config.publicKey.isNotEmpty()) {
                        HorizontalDivider(color = FuturisticColors.NeonGreen.copy(alpha = 0.2f))
                        Text("Reality Settings", color = FuturisticColors.NeonMagenta, fontWeight = FontWeight.Bold)
                        ConfigField("Public Key", config.publicKey.take(20) + "...", FuturisticColors.NeonMagenta)
                        if (config.shortId.isNotEmpty()) ConfigField("Short ID", config.shortId, FuturisticColors.NeonPurple)
                        if (config.fingerprint.isNotEmpty()) ConfigField("Fingerprint", config.fingerprint, FuturisticColors.NeonCyan)
                    }
                }
            }
        }
        
        // Error Display
        error?.let {
            GlassCard(glowColor = FuturisticColors.ErrorGlow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null, tint = FuturisticColors.ErrorGlow)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = it, color = FuturisticColors.ErrorGlow)
                }
            }
        }
        
        // Supported Protocols Card
        GlassCard(glowColor = FuturisticColors.NeonBlue.copy(alpha = 0.5f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Supported Protocols", color = FuturisticColors.NeonBlue, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("VLESS" to FuturisticColors.NeonCyan, "VMess" to FuturisticColors.NeonGreen, 
                           "Trojan" to FuturisticColors.NeonMagenta, "SS" to FuturisticColors.NeonYellow).forEach { (name, color) ->
                        Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.2f)) {
                            Text(name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigField(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.6f))
        Text(value, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

private fun getProtocolColor(protocol: String): Color {
    return when (protocol) {
        "VLESS" -> FuturisticColors.NeonCyan
        "VMess" -> FuturisticColors.NeonGreen
        "Trojan" -> FuturisticColors.NeonMagenta
        "Shadowsocks" -> FuturisticColors.NeonYellow
        else -> FuturisticColors.NeonPurple
    }
}
