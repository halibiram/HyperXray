package com.hyperxray.an.ui.screens.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.feature.dns.DnsManager
import com.hyperxray.an.feature.dns.DnsRecord
import com.hyperxray.an.feature.dns.DnsCacheStats
import kotlinx.coroutines.delay

private const val TAG = "DnsCacheManagerScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsCacheManagerScreen() {
    val context = LocalContext.current
    
    // Local state for stats
    var stats by remember { mutableStateOf<DnsCacheStats?>(null) }
    var records by remember { mutableStateOf<List<DnsRecord>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Initialize DnsCacheManager and update stats periodically
    LaunchedEffect(Unit) {
        try {
            DnsCacheManager.initialize(context.applicationContext)
            Log.d(TAG, "DnsCacheManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DnsCacheManager", e)
        }
        
        // Update stats immediately and then periodically
        // Commit 312450a: DnsCacheManager only has getStats() method, not getStatsData() or getCacheEntries()
        while (true) {
            try {
                val statsString = DnsCacheManager.getStats()
                // Parse stats string: "DNS Cache: X entries, hits=Y, misses=Z, hitRate=W%"
                val entriesMatch = Regex("(\\d+) entries").find(statsString)
                val hitsMatch = Regex("hits=(\\d+)").find(statsString)
                val missesMatch = Regex("misses=(\\d+)").find(statsString)
                val hitRateMatch = Regex("hitRate=(\\d+)%").find(statsString)
                
                val entryCount = entriesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val hits = hitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val misses = missesMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val hitRate = hitRateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                val newStats = DnsCacheStats(
                    totalRecords = entryCount,
                    hitRate = hitRate / 100.0,
                    memoryUsage = 0L, // Not available in commit 312450a
                    avgTtl = 0 // Not available in commit 312450a
                )
                
                // Commit 312450a: getCacheEntries() not available, so records list is empty
                val newRecords = emptyList<DnsRecord>()
                
                // Update state
                stats = newStats
                records = newRecords
                
                Log.d(TAG, "Stats updated: $statsString")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating stats", e)
                e.printStackTrace()
            }
            
            delay(1000) // Update every 1 second
        }
    }

    val filteredRecords = remember(records, searchQuery) {
        if (searchQuery.isBlank()) {
            records
        } else {
            records.filter {
                it.domain.contains(searchQuery, ignoreCase = true) ||
                it.ip.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "DNS Cache Manager",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = Color.White
        )

        // Stats Grid
        val currentStats = stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatsCard(
                title = "Total Records",
                value = currentStats?.totalRecords?.toString() ?: "0",
                color = Color(0xFF3B82F6),
                modifier = Modifier.weight(1f)
            )
            StatsCard(
                title = "Hit Rate",
                value = if (currentStats != null) "${(currentStats.hitRate * 100).toInt()}%" else "0%",
                color = Color(0xFF10B981),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatsCard(
                title = "Memory",
                value = if (currentStats != null) "${currentStats.memoryUsage} KB" else "0 KB",
                color = Color(0xFFF59E0B),
                modifier = Modifier.weight(1f)
            )
            StatsCard(
                title = "Avg TTL",
                value = if (currentStats != null) "${currentStats.avgTtl}s" else "0s",
                color = Color(0xFF8B5CF6),
                modifier = Modifier.weight(1f)
            )
        }

        // Controls & Search
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search domain or IP") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            Button(
                onClick = { DnsManager.clearCache() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }

        // Records List
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cached Records",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                text = "${filteredRecords.size} items",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredRecords) { record ->
                DnsRecordItem(record)
            }
        }
    }
}

@Composable
fun StatsCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.15f),
                        color.copy(alpha = 0.05f)
                    )
                )
            )
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = color
            )
        }
    }
}

@Composable
fun DnsRecordItem(record: DnsRecord) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.domain,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = record.ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = record.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "TTL: ${record.ttl}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
