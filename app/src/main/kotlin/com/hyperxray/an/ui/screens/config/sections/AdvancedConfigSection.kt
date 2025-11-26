package com.hyperxray.an.ui.screens.config.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Advanced configuration section for DPI Evasion (God Mode) features.
 * Includes TLS Fragmentation and Mux (Multiplexing) settings.
 */
@Composable
fun AdvancedConfigSection(
    enableFragment: Boolean,
    fragmentLength: String,
    fragmentInterval: String,
    enableMux: Boolean,
    muxConcurrency: Int,
    onFragmentSettingsChange: (enabled: Boolean, length: String, interval: String) -> Unit,
    onMuxSettingsChange: (enabled: Boolean, concurrency: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚡ DPI Evasion (God Mode)",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color(0xFFE0E0E0)
            )

            // TLS Fragmentation Section
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable TLS Fragmentation",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFE0E0E0)
                        )
                        Text(
                            text = "Fragment packets to evade DPI analysis",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF808080)
                        )
                    }
                    Switch(
                        checked = enableFragment,
                        onCheckedChange = { enabled ->
                            onFragmentSettingsChange(
                                enabled,
                                if (enabled) fragmentLength else "",
                                if (enabled) fragmentInterval else ""
                            )
                        }
                    )
                }

                // Fragment Length Range
                if (enableFragment) {
                    OutlinedTextField(
                        value = fragmentLength,
                        onValueChange = { newValue ->
                            onFragmentSettingsChange(
                                true,
                                newValue,
                                fragmentInterval
                            )
                        },
                        label = { 
                            Text(
                                "Length Range (packets)",
                                color = Color(0xFFB0B0B0)
                            ) 
                        },
                        placeholder = { 
                            Text(
                                "100-200",
                                color = Color(0xFF808080)
                            ) 
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE0E0E0),
                            unfocusedTextColor = Color(0xFFE0E0E0),
                            focusedBorderColor = Color(0xFF4A9EFF),
                            unfocusedBorderColor = Color(0xFF404040),
                            focusedLabelColor = Color(0xFFB0B0B0),
                            unfocusedLabelColor = Color(0xFF808080)
                        ),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Text
                        )
                    )

                    // Fragment Interval
                    OutlinedTextField(
                        value = fragmentInterval,
                        onValueChange = { newValue ->
                            onFragmentSettingsChange(
                                true,
                                fragmentLength,
                                newValue
                            )
                        },
                        label = { 
                            Text(
                                "Interval (ms)",
                                color = Color(0xFFB0B0B0)
                            ) 
                        },
                        placeholder = { 
                            Text(
                                "10-30",
                                color = Color(0xFF808080)
                            ) 
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE0E0E0),
                            unfocusedTextColor = Color(0xFFE0E0E0),
                            focusedBorderColor = Color(0xFF4A9EFF),
                            unfocusedBorderColor = Color(0xFF404040),
                            focusedLabelColor = Color(0xFFB0B0B0),
                            unfocusedLabelColor = Color(0xFF808080)
                        ),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Text
                        )
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF404040))
            )

            // Mux (Multiplexing) Section
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Mux (Multiplexing)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFE0E0E0)
                        )
                        Text(
                            text = "Reduces handshake latency",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF808080)
                        )
                    }
                    Switch(
                        checked = enableMux,
                        onCheckedChange = { enabled ->
                            onMuxSettingsChange(
                                enabled,
                                muxConcurrency
                            )
                        }
                    )
                }

                // Mux Concurrency Slider
                if (enableMux) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mux Concurrency",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFE0E0E0)
                            )
                            Text(
                                text = muxConcurrency.toString(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFF4A9EFF)
                            )
                        }
                        Slider(
                            value = muxConcurrency.toFloat(),
                            onValueChange = { newValue ->
                                onMuxSettingsChange(
                                    true,
                                    newValue.toInt()
                                )
                            },
                            valueRange = 1f..1024f,
                            steps = 1023, // 1024 steps (1 to 1024)
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Color(0xFF4A9EFF),
                                activeTrackColor = Color(0xFF4A9EFF),
                                inactiveTrackColor = Color(0xFF404040)
                            )
                        )
                        Text(
                            text = "Range: 1 to 1024",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF808080)
                        )
                    }
                }
            }
        }
    }
}

