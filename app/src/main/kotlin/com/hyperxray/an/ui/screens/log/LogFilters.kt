package com.hyperxray.an.ui.screens.log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LogFilters(
    selectedLogLevel: LogLevel?,
    selectedConnectionType: ConnectionType?,
    showSniffingOnly: Boolean,
    showAiOnly: Boolean,
    onLogLevelSelected: (LogLevel?) -> Unit,
    onConnectionTypeSelected: (ConnectionType?) -> Unit,
    onShowSniffingOnlyChanged: (Boolean) -> Unit,
    onShowAiOnlyChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Log Level filters - top row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedLogLevel == LogLevel.ERROR,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.ERROR) null else LogLevel.ERROR) },
                label = { Text("ERROR", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = selectedLogLevel == LogLevel.WARN,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.WARN) null else LogLevel.WARN) },
                label = { Text("WARN", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = selectedLogLevel == LogLevel.INFO,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.INFO) null else LogLevel.INFO) },
                label = { Text("INFO", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = selectedLogLevel == LogLevel.DEBUG,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.DEBUG) null else LogLevel.DEBUG) },
                label = { Text("DEBUG", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = selectedLogLevel == null,
                onClick = { onLogLevelSelected(null) },
                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Connection Type filters - middle row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedConnectionType == ConnectionType.UDP,
                onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.UDP) null else ConnectionType.UDP) },
                label = { Text("UDP", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = selectedConnectionType == ConnectionType.TCP,
                onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.TCP) null else ConnectionType.TCP) },
                label = { Text("TCP", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = selectedConnectionType == null,
                onClick = { onConnectionTypeSelected(null) },
                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Log Categories filters - bottom row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = showSniffingOnly,
                onClick = { onShowSniffingOnlyChanged(!showSniffingOnly) },
                label = { Text("🔍 Sniffing", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = showAiOnly,
                onClick = { onShowAiOnlyChanged(!showAiOnly) },
                label = { Text("🤖 AI", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
        }
    }
}

