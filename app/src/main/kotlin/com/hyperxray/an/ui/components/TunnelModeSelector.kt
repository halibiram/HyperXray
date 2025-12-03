package com.hyperxray.an.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hyperxray.an.common.TunnelMode

/**
 * A composable that displays a selector for choosing between tunnel modes.
 * 
 * @param currentMode The currently selected tunnel mode
 * @param onModeSelected Callback when a mode is selected
 * @param modifier Modifier for the component
 */
@Composable
fun TunnelModeSelector(
    currentMode: TunnelMode,
    onModeSelected: (TunnelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Tunnel Mode",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        TunnelMode.entries.forEach { mode ->
            TunnelModeOption(
                mode = mode,
                isSelected = currentMode == mode,
                onSelect = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
private fun TunnelModeOption(
    mode: TunnelMode,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = mode.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
