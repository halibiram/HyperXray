package com.hyperxray.an.ui.screens.utils.tools

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun Base64ConverterTool() {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var isEncode by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun process() {
        error = null
        try {
            output = if (isEncode) {
                Base64.encodeToString(input.toByteArray(), Base64.DEFAULT).trim()
            } else {
                String(Base64.decode(input, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            error = "Invalid Base64 input"
        }
    }

    LaunchedEffect(input, isEncode) {
        if (input.isNotEmpty()) process() else output = ""
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
                text = "Base64 Converter",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            TabRow(selectedTabIndex = if (isEncode) 0 else 1) {
                Tab(selected = isEncode, onClick = { isEncode = true }, text = { Text("Encode") })
                Tab(selected = !isEncode, onClick = { isEncode = false }, text = { Text("Decode") })
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (isEncode) "Text" else "Base64") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                maxLines = 5
            )

            if (error != null) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            OutlinedTextField(
                value = output,
                onValueChange = {},
                label = { Text("Result") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                readOnly = true,
                maxLines = 5
            )
        }
    }
}
