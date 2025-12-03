package com.hyperxray.an.ui.screens.log

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Modal bottom sheet for log options v2.1
 * Contains filters, view mode, sort order, and export options
 */
@Composable
fun LogOptionsSheet(
    selectedLogLevel: LogLevel?,
    selectedConnectionType: ConnectionType?,
    showSniffingOnly: Boolean,
    showAiOnly: Boolean,
    viewMode: LogViewMode,
    sortOrder: LogSortOrder,
    onLogLevelSelected: (LogLevel?) -> Unit,
    onConnectionTypeSelected: (ConnectionType?) -> Unit,
    onShowSniffingOnlyChanged: (Boolean) -> Unit,
    onShowAiOnlyChanged: (Boolean) -> Unit,
    onViewModeChange: (LogViewMode) -> Unit,
    onSortOrderChange: (LogSortOrder) -> Unit,
    onExportLogs: (LogExportFormat) -> Unit,
    logFile: File?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    val infiniteTransition = rememberInfiniteTransition(label = "options")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOG OPTIONS",
                color = LogColorPalette.NeonPurple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp
            )
            
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF151520))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // FILTERS SECTION
        OptionsSectionHeader(
            icon = Icons.Default.FilterList,
            title = "FILTERS",
            color = LogColorPalette.NeonCyan
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Log Level Filter
        OptionsSubHeader(title = "Log Level")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OptionChip(
                text = "ERROR",
                isSelected = selectedLogLevel == LogLevel.ERROR,
                activeColor = LogColorPalette.NeonRed,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.ERROR) null else LogLevel.ERROR) },
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                text = "WARN",
                isSelected = selectedLogLevel == LogLevel.WARN,
                activeColor = LogColorPalette.NeonYellow,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.WARN) null else LogLevel.WARN) },
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                text = "INFO",
                isSelected = selectedLogLevel == LogLevel.INFO,
                activeColor = LogColorPalette.NeonCyan,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.INFO) null else LogLevel.INFO) },
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                text = "DEBUG",
                isSelected = selectedLogLevel == LogLevel.DEBUG,
                activeColor = LogColorPalette.NeonGreen,
                onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.DEBUG) null else LogLevel.DEBUG) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connection Type Filter
        OptionsSubHeader(title = "Network Type")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OptionChip(
                text = "TCP",
                isSelected = selectedConnectionType == ConnectionType.TCP,
                activeColor = LogColorPalette.NeonGreen,
                onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.TCP) null else ConnectionType.TCP) },
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                text = "UDP",
                isSelected = selectedConnectionType == ConnectionType.UDP,
                activeColor = LogColorPalette.NeonBlue,
                onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.UDP) null else ConnectionType.UDP) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Focus Filters
        OptionsSubHeader(title = "Focus")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OptionChip(
                text = "SNIFFING",
                isSelected = showSniffingOnly,
                activeColor = LogColorPalette.NeonOrange,
                onClick = { onShowSniffingOnlyChanged(!showSniffingOnly) },
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                text = "AI LOGS",
                isSelected = showAiOnly,
                activeColor = LogColorPalette.NeonPurple,
                onClick = { onShowAiOnlyChanged(!showAiOnly) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // VIEW MODE SECTION
        OptionsSectionHeader(
            icon = Icons.Default.ViewModule,
            title = "VIEW MODE",
            color = LogColorPalette.NeonGreen
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewModeOption(
                icon = Icons.Default.Terminal,
                title = "Terminal",
                description = "Classic terminal style",
                isSelected = viewMode == LogViewMode.TERMINAL,
                onClick = { onViewModeChange(LogViewMode.TERMINAL) }
            )
            ViewModeOption(
                icon = Icons.Default.ViewList,
                title = "Compact",
                description = "Compact list view",
                isSelected = viewMode == LogViewMode.COMPACT,
                onClick = { onViewModeChange(LogViewMode.COMPACT) }
            )
            ViewModeOption(
                icon = Icons.Default.ViewAgenda,
                title = "Detailed",
                description = "Detailed card view",
                isSelected = viewMode == LogViewMode.DETAILED,
                onClick = { onViewModeChange(LogViewMode.DETAILED) }
            )
            ViewModeOption(
                icon = Icons.Default.Timeline,
                title = "Timeline",
                description = "Timeline visualization",
                isSelected = viewMode == LogViewMode.TIMELINE,
                onClick = { onViewModeChange(LogViewMode.TIMELINE) }
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // SORT ORDER SECTION
        OptionsSectionHeader(
            icon = Icons.Default.Sort,
            title = "SORT ORDER",
            color = LogColorPalette.NeonOrange
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortOptionChip(
                text = "NEWEST",
                isSelected = sortOrder == LogSortOrder.NEWEST_FIRST,
                onClick = { onSortOrderChange(LogSortOrder.NEWEST_FIRST) },
                modifier = Modifier.weight(1f)
            )
            SortOptionChip(
                text = "OLDEST",
                isSelected = sortOrder == LogSortOrder.OLDEST_FIRST,
                onClick = { onSortOrderChange(LogSortOrder.OLDEST_FIRST) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortOptionChip(
                text = "BY LEVEL",
                isSelected = sortOrder == LogSortOrder.BY_LEVEL,
                onClick = { onSortOrderChange(LogSortOrder.BY_LEVEL) },
                modifier = Modifier.weight(1f)
            )
            SortOptionChip(
                text = "BY TYPE",
                isSelected = sortOrder == LogSortOrder.BY_TYPE,
                onClick = { onSortOrderChange(LogSortOrder.BY_TYPE) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // EXPORT SECTION
        OptionsSectionHeader(
            icon = Icons.Default.FileDownload,
            title = "EXPORT",
            color = LogColorPalette.NeonMagenta
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Share button
        ExportButton(
            icon = Icons.Default.Share,
            text = "Share Log File",
            color = LogColorPalette.NeonCyan,
            onClick = { logFile?.let { shareLogFile(context, it) } }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Export format buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExportFormatChip(
                text = "TXT",
                onClick = { onExportLogs(LogExportFormat.TXT) },
                modifier = Modifier.weight(1f)
            )
            ExportFormatChip(
                text = "JSON",
                onClick = { onExportLogs(LogExportFormat.JSON) },
                modifier = Modifier.weight(1f)
            )
            ExportFormatChip(
                text = "CSV",
                onClick = { onExportLogs(LogExportFormat.CSV) },
                modifier = Modifier.weight(1f)
            )
            ExportFormatChip(
                text = "HTML",
                onClick = { onExportLogs(LogExportFormat.HTML) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun OptionsSectionHeader(
    icon: ImageVector,
    title: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.1f))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = title,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun OptionsSubHeader(title: String) {
    Text(
        text = title,
        color = Color.Gray,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
    )
}

@Composable
private fun OptionChip(
    text: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedBg by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.15f) else Color(0xFF101015),
        animationSpec = tween(200),
        label = "bg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.6f) else Color(0xFF202025),
        animationSpec = tween(200),
        label = "border"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(animatedBg)
            .border(1.dp, animatedBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) activeColor else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun ViewModeOption(
    icon: ImageVector,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = LogColorPalette.NeonGreen
    val animatedBg by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.1f) else Color(0xFF0A0A0D),
        animationSpec = tween(200),
        label = "bg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.5f) else Color(0xFF151520),
        animationSpec = tween(200),
        label = "border"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(animatedBg)
            .border(1.dp, animatedBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) color else Color.Gray,
            modifier = Modifier.size(24.dp)
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isSelected) color else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
            Text(
                text = description,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SortOptionChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = LogColorPalette.NeonOrange
    val animatedBg by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.15f) else Color(0xFF101015),
        animationSpec = tween(200),
        label = "bg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.6f) else Color(0xFF202025),
        animationSpec = tween(200),
        label = "border"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(animatedBg)
            .border(1.dp, animatedBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) color else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun ExportButton(
    icon: ImageVector,
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ExportFormatChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101015))
            .border(1.dp, LogColorPalette.NeonMagenta.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = LogColorPalette.NeonMagenta,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp
        )
    }
}

private fun shareLogFile(context: Context, logFile: File) {
    try {
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(context, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }
        
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
