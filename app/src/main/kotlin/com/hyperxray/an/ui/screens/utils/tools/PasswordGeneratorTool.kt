package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun PasswordGeneratorTool() {
    var length by remember { mutableFloatStateOf(16f) }
    var useUpper by remember { mutableStateOf(true) }
    var useLower by remember { mutableStateOf(true) }
    var useNumbers by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    
    val clipboardManager = LocalClipboardManager.current

    fun generate() {
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = "abcdefghijklmnopqrstuvwxyz"
        val numbers = "0123456789"
        val symbols = "!@#$%^&*()_+-=[]{}|;:,.<>?"
        
        var chars = ""
        if (useUpper) chars += upper
        if (useLower) chars += lower
        if (useNumbers) chars += numbers
        if (useSymbols) chars += symbols
        
        if (chars.isEmpty()) {
            password = ""
            return
        }
        
        password = (1..length.toInt())
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }

    LaunchedEffect(Unit) { generate() }

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
                text = "Password Generator",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            OutlinedTextField(
                value = password,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                trailingIcon = {
                    IconButton(onClick = { generate() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                    }
                }
            )
            
            Button(
                onClick = { clipboardManager.setText(AnnotatedString(password)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Copy to Clipboard")
            }

            Text("Length: ${length.toInt()}")
            Slider(
                value = length,
                onValueChange = { length = it },
                valueRange = 4f..64f,
                steps = 60
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    OptionCheckbox("Uppercase", useUpper) { useUpper = it }
                    OptionCheckbox("Lowercase", useLower) { useLower = it }
                }
                Column {
                    OptionCheckbox("Numbers", useNumbers) { useNumbers = it }
                    OptionCheckbox("Symbols", useSymbols) { useSymbols = it }
                }
            }
        }
    }
}

@Composable
private fun OptionCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
