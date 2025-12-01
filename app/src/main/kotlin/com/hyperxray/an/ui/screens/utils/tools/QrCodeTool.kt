package com.hyperxray.an.ui.screens.utils.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hyperxray.an.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun QrCodeTool() {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrSize by remember { mutableStateOf(256) }
    var errorCorrection by remember { mutableStateOf("M") }
    var error by remember { mutableStateOf<String?>(null) }

    val presets = listOf(
        "vless://" to "VLESS Config",
        "vmess://" to "VMess Config",
        "trojan://" to "Trojan Config",
        "ss://" to "Shadowsocks",
        "wg://" to "WireGuard"
    )

    fun generateQrCode() {
        if (inputText.isBlank()) {
            error = "Please enter text or config"
            return
        }
        
        try {
            error = null
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.ERROR_CORRECTION] = when (errorCorrection) {
                "L" -> ErrorCorrectionLevel.L
                "M" -> ErrorCorrectionLevel.M
                "Q" -> ErrorCorrectionLevel.Q
                "H" -> ErrorCorrectionLevel.H
                else -> ErrorCorrectionLevel.M
            }
            hints[EncodeHintType.MARGIN] = 1
            
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(inputText, BarcodeFormat.QR_CODE, qrSize, qrSize, hints)
            
            val bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888)
            for (x in 0 until qrSize) {
                for (y in 0 until qrSize) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            qrBitmap = bitmap
        } catch (e: Exception) {
            error = "Failed to generate QR: ${e.message}"
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            inputText = clip.getItemAt(0).text?.toString() ?: ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Generator Card
        GlassCard(glowColor = FuturisticColors.NeonCyan) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "QR CODE GENERATOR",
                    color = FuturisticColors.NeonCyan,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                // Preset buttons
                Text("Quick Presets", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.take(4).forEach { (prefix, label) ->
                        FilterChip(
                            selected = inputText.startsWith(prefix),
                            onClick = { if (!inputText.startsWith(prefix)) inputText = prefix },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FuturisticColors.NeonCyan.copy(alpha = 0.2f),
                                selectedLabelColor = FuturisticColors.NeonCyan
                            )
                        )
                    }
                }
                
                // Input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Text or Config URL") },
                    placeholder = { Text("vless://... or any text") },
                    trailingIcon = {
                        IconButton(onClick = { pasteFromClipboard() }) {
                            Icon(Icons.Default.ContentPaste, null, tint = FuturisticColors.NeonCyan)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FuturisticColors.NeonCyan,
                        unfocusedBorderColor = FuturisticColors.NeonCyan.copy(alpha = 0.3f)
                    )
                )
                
                // Options
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Size
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Size: ${qrSize}px", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = qrSize.toFloat(),
                            onValueChange = { qrSize = it.toInt() },
                            valueRange = 128f..512f,
                            colors = SliderDefaults.colors(thumbColor = FuturisticColors.NeonCyan, activeTrackColor = FuturisticColors.NeonCyan)
                        )
                    }
                    
                    // Error Correction
                    Column {
                        Text("Error Correction", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("L", "M", "Q", "H").forEach { level ->
                                FilterChip(
                                    selected = errorCorrection == level,
                                    onClick = { errorCorrection = level },
                                    label = { Text(level) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = FuturisticColors.NeonPurple.copy(alpha = 0.2f),
                                        selectedLabelColor = FuturisticColors.NeonPurple
                                    )
                                )
                            }
                        }
                    }
                }
                
                // Generate Button
                CyberButton(
                    onClick = { generateQrCode() },
                    enabled = inputText.isNotBlank(),
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GENERATE QR CODE", fontWeight = FontWeight.Bold)
                }
            }
        }
        
        // QR Display Card
        qrBitmap?.let { bitmap ->
            GlassCard(glowColor = FuturisticColors.NeonGreen) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    NeonText(
                        text = "GENERATED QR CODE",
                        color = FuturisticColors.NeonGreen,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(256.dp).padding(8.dp)
                        )
                    }
                    
                    // Info
                    Text(
                        "Size: ${qrSize}x${qrSize} • Error Correction: $errorCorrection",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    
                    // Detected type
                    val configType = when {
                        inputText.startsWith("vless://") -> "VLESS Configuration"
                        inputText.startsWith("vmess://") -> "VMess Configuration"
                        inputText.startsWith("trojan://") -> "Trojan Configuration"
                        inputText.startsWith("ss://") -> "Shadowsocks Configuration"
                        inputText.startsWith("wg://") -> "WireGuard Configuration"
                        inputText.startsWith("http") -> "URL"
                        else -> "Plain Text"
                    }
                    
                    Surface(shape = RoundedCornerShape(8.dp), color = FuturisticColors.NeonCyan.copy(alpha = 0.1f)) {
                        Text(configType, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = FuturisticColors.NeonCyan)
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
        
        // Info Card
        GlassCard(glowColor = FuturisticColors.NeonYellow.copy(alpha = 0.5f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = FuturisticColors.NeonYellow, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Error Correction Levels", color = FuturisticColors.NeonYellow, fontWeight = FontWeight.Bold)
                }
                Text(
                    "• L (7%) - Smallest QR, least recovery\n" +
                    "• M (15%) - Balanced (recommended)\n" +
                    "• Q (25%) - Better recovery\n" +
                    "• H (30%) - Best recovery, largest QR",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}
