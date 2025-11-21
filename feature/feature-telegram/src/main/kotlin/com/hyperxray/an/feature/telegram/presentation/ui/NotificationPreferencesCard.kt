package com.hyperxray.an.feature.telegram.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Card for notification preferences
 */
@Composable
fun NotificationPreferencesCard(
    notifyVpnStatus: Boolean,
    notifyErrors: Boolean,
    notifyPerformance: Boolean,
    notifyDnsCache: Boolean,
    notifyManual: Boolean,
    onNotifyVpnStatusChange: (Boolean) -> Unit,
    onNotifyErrorsChange: (Boolean) -> Unit,
    onNotifyPerformanceChange: (Boolean) -> Unit,
    onNotifyDnsCacheChange: (Boolean) -> Unit,
    onNotifyManualChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Notification Preferences",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            NotificationPreferenceItem(
                title = "VPN Status",
                description = "Notify when VPN connects or disconnects",
                checked = notifyVpnStatus,
                onCheckedChange = onNotifyVpnStatusChange
            )

            NotificationPreferenceItem(
                title = "Error Notifications",
                description = "Notify about critical errors",
                checked = notifyErrors,
                onCheckedChange = onNotifyErrorsChange
            )

            NotificationPreferenceItem(
                title = "Performance Metrics",
                description = "Notify about performance statistics",
                checked = notifyPerformance,
                onCheckedChange = onNotifyPerformanceChange
            )

            NotificationPreferenceItem(
                title = "DNS Cache Info",
                description = "Notify about DNS cache statistics",
                checked = notifyDnsCache,
                onCheckedChange = onNotifyDnsCacheChange
            )

            NotificationPreferenceItem(
                title = "Manual Notifications",
                description = "Allow manual notification commands",
                checked = notifyManual,
                onCheckedChange = onNotifyManualChange
            )
        }
    }
}

@Composable
private fun NotificationPreferenceItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}




