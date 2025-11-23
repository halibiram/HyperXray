package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun UnitConverterTool() {
    var inputValue by remember { mutableStateOf("") }
    var fromUnit by remember { mutableStateOf("MB") }
    var toUnit by remember { mutableStateOf("GB") }
    var result by remember { mutableStateOf("") }
    
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    
    fun convert() {
        val value = inputValue.toDoubleOrNull() ?: return
        
        val fromIndex = units.indexOf(fromUnit)
        val toIndex = units.indexOf(toUnit)
        
        // Convert to Bytes first
        var bytes = BigDecimal(value)
        for (i in 0 until fromIndex) {
            bytes = bytes.multiply(BigDecimal(1024))
        }
        
        // Convert to target unit
        var target = bytes
        for (i in 0 until toIndex) {
            target = target.divide(BigDecimal(1024), 10, RoundingMode.HALF_UP)
        }
        
        // Format
        result = target.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    LaunchedEffect(inputValue, fromUnit, toUnit) {
        convert()
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
                text = "Data Unit Converter",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = { Text("Value") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                
                UnitDropdown(selected = fromUnit, onSelect = { fromUnit = it }, units = units)
            }
            
            Text("=", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = result,
                    onValueChange = {},
                    label = { Text("Result") },
                    modifier = Modifier.weight(1f),
                    readOnly = true,
                    singleLine = true
                )
                
                UnitDropdown(selected = toUnit, onSelect = { toUnit = it }, units = units)
            }
        }
    }
}

@Composable
private fun UnitDropdown(selected: String, onSelect: (String) -> Unit, units: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(selected)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            units.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit) },
                    onClick = { 
                        onSelect(unit)
                        expanded = false
                    }
                )
            }
        }
    }
}
