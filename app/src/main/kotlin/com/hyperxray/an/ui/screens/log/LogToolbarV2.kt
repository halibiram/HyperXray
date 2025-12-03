package com.hyperxray.an.ui.screens.log

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

/**
 * Simplified toolbar for LogScreen v2.1
 * Options moved to modal sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogToolbarV2(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    isAutoScrollEnabled: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    logFile: File?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val infiniteTransition = rememberInfiniteTransition(label = "toolbar")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "borderAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080808))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        LogColorPalette.NeonCyan.copy(alpha = borderAlpha),
                        LogColorPalette.NeonPurple.copy(alpha = borderAlpha * 0.5f),
                        LogColorPalette.NeonCyan.copy(alpha = borderAlpha)
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search section
            AnimatedVisibility(
                visible = isSearchExpanded,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                SearchFieldV2(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = { onSearchExpandedChange(false) },
                    focusRequester = focusRequester,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!isSearchExpanded) {
                // Search button
                ToolbarIconButtonV2(
                    icon = Icons.Default.Search,
                    contentDescription = "Search",
                    onClick = { 
                        onSearchExpandedChange(true)
                        scope.launch { focusRequester.requestFocus() }
                    },
                    color = LogColorPalette.NeonCyan
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Right side actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto-scroll toggle
                ToolbarIconButtonV2(
                    icon = if (isAutoScrollEnabled) Icons.Default.VerticalAlignBottom else Icons.Default.PauseCircle,
                    contentDescription = if (isAutoScrollEnabled) "Auto-scroll ON" else "Auto-scroll OFF",
                    onClick = { onAutoScrollChange(!isAutoScrollEnabled) },
                    color = if (isAutoScrollEnabled) LogColorPalette.NeonGreen else Color.Gray,
                    isActive = isAutoScrollEnabled
                )

                // Share
                ToolbarIconButtonV2(
                    icon = Icons.Default.Share,
                    contentDescription = "Share",
                    onClick = { logFile?.let { shareLogFile(context, it) } },
                    color = LogColorPalette.NeonPurple
                )

                // Clear
                ToolbarIconButtonV2(
                    icon = Icons.Default.DeleteSweep,
                    contentDescription = "Clear Logs",
                    onClick = { showClearConfirm = true },
                    color = LogColorPalette.NeonRed
                )
            }
        }
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Color(0xFF101010),
            title = {
                Text(
                    "Clear All Logs?",
                    color = LogColorPalette.NeonRed,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This action cannot be undone. All log entries will be permanently deleted.",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearLogs()
                        showClearConfirm = false
                    }
                ) {
                    Text("CLEAR", color = LogColorPalette.NeonRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("CANCEL", color = Color.Gray, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun SearchFieldV2(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0A0A))
            .border(1.dp, LogColorPalette.NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = LogColorPalette.NeonCyan.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
        
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(LogColorPalette.NeonCyan),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "Search logs...",
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
        
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Clear",
                tint = Color.Gray,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") }
            )
        }
        
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close search",
            tint = LogColorPalette.NeonCyan.copy(alpha = 0.7f),
            modifier = Modifier
                .size(18.dp)
                .clickable { onClose() }
        )
    }
}

@Composable
private fun ToolbarIconButtonV2(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    color: Color,
    isActive: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) color.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = if (isActive) color.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = color.copy(alpha = if (isActive) 1f else 0.7f),
            modifier = Modifier.size(20.dp)
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
