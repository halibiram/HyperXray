package com.hyperxray.an.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.common.ROUTE_TELEGRAM_SETTINGS
import com.hyperxray.an.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Futuristic Color Palette
private object FuturisticSettingsColors {
    val NeonCyan = Color(0xFF00F5FF)
    val NeonMagenta = Color(0xFFFF00FF)
    val NeonPurple = Color(0xFF8B5CF6)
    val NeonBlue = Color(0xFF3B82F6)
    val NeonGreen = Color(0xFF00FF88)
    val NeonOrange = Color(0xFFFF6B00)
    val NeonPink = Color(0xFFFF0080)
    val NeonYellow = Color(0xFFFFE500)
    val DeepSpace = Color(0xFF000011)
    val GlassWhite = Color(0x1AFFFFFF)
    val SuccessGlow = Color(0xFF00FF88)
    val ErrorGlow = Color(0xFFFF3366)
    val WarningGlow = Color(0xFFFFAA00)
}

@Composable
private fun Modifier.neonGlow(
    color: Color = FuturisticSettingsColors.NeonCyan,
    glowRadius: Dp = 8.dp,
    animated: Boolean = true
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "neonGlow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val effectiveAlpha = if (animated) alpha else 0.7f
    return this.drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = effectiveAlpha * 0.25f),
                    color.copy(alpha = effectiveAlpha * 0.08f),
                    Color.Transparent
                ),
                radius = glowRadius.toPx() * 3
            )
        )
    }
}

@Composable
private fun Modifier.holographicShimmer(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "holographic")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    return this.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    FuturisticSettingsColors.NeonCyan.copy(alpha = 0.6f),
                    FuturisticSettingsColors.NeonMagenta.copy(alpha = 0.4f),
                    FuturisticSettingsColors.NeonPurple.copy(alpha = 0.6f),
                    FuturisticSettingsColors.NeonBlue.copy(alpha = 0.4f)
                ),
                start = Offset(offset - 500f, 0f),
                end = Offset(offset, size.height)
            ),
            blendMode = BlendMode.Overlay,
            alpha = 0.12f
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val context = LocalContext.current
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val geoipProgress by mainViewModel.geoipDownloadProgress.collectAsStateWithLifecycle()
    val geositeProgress by mainViewModel.geositeDownloadProgress.collectAsStateWithLifecycle()
    val isCheckingForUpdates by mainViewModel.isCheckingForUpdates.collectAsStateWithLifecycle()
    val newVersionTag by mainViewModel.newVersionAvailable.collectAsStateWithLifecycle()

    val vpnDisabled = settingsState.switches.disableVpn

    var showGeoipDeleteDialog by remember { mutableStateOf(false) }
    var showGeositeDeleteDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var editingRuleFile by remember { mutableStateOf<String?>(null) }
    var ruleFileUrl by remember { mutableStateOf("") }

    val themeOptions = listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.Auto)
    var selectedThemeOption by remember { mutableStateOf(settingsState.switches.themeMode) }
    var themeExpanded by remember { mutableStateOf(false) }

    // Animated background gradient
    val infiniteTransition = rememberInfiniteTransition(label = "bgGradient")
    val gradientAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientAngle"
    )

    // Rule File Edit Bottom Sheet
    if (editingRuleFile != null) {
        FuturisticBottomSheet(
            onDismissRequest = { editingRuleFile = null },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                NeonSectionTitle("📥 ${stringResource(R.string.rule_file_update_url)}")
                Spacer(modifier = Modifier.height(16.dp))
                FuturisticTextField(
                    value = ruleFileUrl,
                    onValueChange = { ruleFileUrl = it },
                    label = "URL",
                    trailingIcon = {
                        val clipboardManager = LocalClipboard.current
                        IconButton(onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.let {
                                    ruleFileUrl = it.toString()
                                }
                            }
                        }) {
                            Icon(painterResource(id = R.drawable.paste), "Paste", tint = FuturisticSettingsColors.NeonCyan)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = {
                    ruleFileUrl = if (editingRuleFile == "geoip.dat") context.getString(R.string.geoip_url)
                    else context.getString(R.string.geosite_url)
                }) {
                    Text(stringResource(id = R.string.restore_default_url), color = FuturisticSettingsColors.NeonPurple)
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FuturisticButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) editingRuleFile = null }
                    }, color = Color.Gray.copy(alpha = 0.5f)) { Text(stringResource(R.string.cancel)) }
                    Spacer(modifier = Modifier.width(12.dp))
                    FuturisticButton(onClick = {
                        mainViewModel.downloadRuleFile(ruleFileUrl, editingRuleFile!!)
                        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) editingRuleFile = null }
                    }) { Text(stringResource(R.string.update)) }
                }
            }
        }
    }

    // Dialogs
    if (showGeoipDeleteDialog) {
        FuturisticAlertDialog(
            onDismissRequest = { showGeoipDeleteDialog = false },
            title = stringResource(R.string.delete_rule_file_title),
            text = stringResource(R.string.delete_rule_file_message),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = { mainViewModel.restoreDefaultGeoip { }; showGeoipDeleteDialog = false },
            onDismiss = { showGeoipDeleteDialog = false }
        )
    }

    if (showGeositeDeleteDialog) {
        FuturisticAlertDialog(
            onDismissRequest = { showGeositeDeleteDialog = false },
            title = stringResource(R.string.delete_rule_file_title),
            text = stringResource(R.string.delete_rule_file_message),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = { mainViewModel.restoreDefaultGeosite { }; showGeositeDeleteDialog = false },
            onDismiss = { showGeositeDeleteDialog = false }
        )
    }

    if (newVersionTag != null) {
        FuturisticAlertDialog(
            onDismissRequest = { mainViewModel.clearNewVersionAvailable() },
            title = stringResource(R.string.new_version_available_title),
            text = stringResource(R.string.new_version_available_message, newVersionTag!!),
            confirmText = stringResource(R.string.download),
            dismissText = stringResource(id = android.R.string.cancel),
            onConfirm = { mainViewModel.downloadNewVersion(newVersionTag!!) },
            onDismiss = { mainViewModel.clearNewVersionAvailable() },
            accentColor = FuturisticSettingsColors.NeonGreen
        )
    }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val horizontalPadding = if (isTablet) 24.dp else 16.dp

    // Main Content with Animated Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FuturisticSettingsColors.DeepSpace)
            .drawBehind {
                val angleRad = Math.toRadians(gradientAngle.toDouble())
                val centerX = size.width / 2
                val centerY = size.height / 2
                val radius = maxOf(size.width, size.height)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            FuturisticSettingsColors.NeonCyan.copy(alpha = 0.08f),
                            FuturisticSettingsColors.NeonPurple.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(
                            centerX + (radius * 0.3f * kotlin.math.cos(angleRad)).toFloat(),
                            centerY + (radius * 0.3f * kotlin.math.sin(angleRad)).toFloat()
                        ),
                        radius = radius * 0.7f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding, vertical = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ═══════════════════════════════════════════════════════════════
            // GENERAL SETTINGS
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = stringResource(R.string.general),
                icon = Icons.Default.Settings,
                accentColor = FuturisticSettingsColors.NeonCyan
            ) {
                FuturisticSwitchItem(
                    title = stringResource(R.string.use_template_title),
                    subtitle = stringResource(R.string.use_template_summary),
                    checked = settingsState.switches.useTemplateEnabled,
                    onCheckedChange = { mainViewModel.setUseTemplateEnabled(it) },
                    icon = Icons.Default.Description
                )

                FuturisticDivider()

                FuturisticDropdownItem(
                    title = stringResource(R.string.theme_title),
                    subtitle = stringResource(id = R.string.theme_summary),
                    selectedValue = when (selectedThemeOption) {
                        ThemeMode.Light -> stringResource(R.string.theme_light)
                        ThemeMode.Dark -> stringResource(R.string.theme_dark)
                        ThemeMode.Auto -> stringResource(R.string.auto)
                    },
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it },
                    icon = Icons.Default.Palette
                ) {
                    themeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            ThemeMode.Light -> R.string.theme_light
                                            ThemeMode.Dark -> R.string.theme_dark
                                            ThemeMode.Auto -> R.string.auto
                                        }
                                    ),
                                    color = Color.White
                                )
                            },
                            onClick = {
                                selectedThemeOption = option
                                mainViewModel.setTheme(option)
                                themeExpanded = false
                            },
                            modifier = Modifier.background(Color(0xFF1A1A2E))
                        )
                    }
                }

                FuturisticDivider()

                FuturisticSwitchItem(
                    title = stringResource(R.string.auto_start_title),
                    subtitle = stringResource(R.string.auto_start_summary),
                    checked = settingsState.switches.autoStart,
                    onCheckedChange = { mainViewModel.setAutoStart(it) },
                    icon = Icons.Default.PlayArrow
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // VPN INTERFACE SETTINGS
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = stringResource(R.string.vpn_interface),
                icon = Icons.Default.VpnKey,
                accentColor = FuturisticSettingsColors.NeonMagenta
            ) {
                FuturisticNavigationItem(
                    title = stringResource(R.string.apps_title),
                    subtitle = stringResource(R.string.apps_summary),
                    onClick = { mainViewModel.navigateToAppList() },
                    icon = Icons.Default.Apps
                )

                FuturisticDivider()

                FuturisticSwitchItem(
                    title = stringResource(R.string.disable_vpn_title),
                    subtitle = stringResource(R.string.disable_vpn_summary),
                    checked = settingsState.switches.disableVpn,
                    onCheckedChange = { mainViewModel.setDisableVpnEnabled(it) },
                    icon = Icons.Default.Block,
                    accentColor = FuturisticSettingsColors.WarningGlow
                )

                FuturisticDivider()

                FuturisticEditableItem(
                    title = stringResource(R.string.socks_port),
                    value = settingsState.socksPort.value,
                    onValueConfirmed = { mainViewModel.updateSocksPort(it) },
                    isError = !settingsState.socksPort.isValid,
                    errorMessage = settingsState.socksPort.error,
                    enabled = !vpnDisabled,
                    keyboardType = KeyboardType.Number,
                    sheetState = sheetState,
                    scope = scope,
                    icon = Icons.Default.SettingsEthernet
                )

                FuturisticDivider()

                FuturisticEditableItem(
                    title = stringResource(R.string.dns_ipv4),
                    value = settingsState.dnsIpv4.value,
                    onValueConfirmed = { mainViewModel.updateDnsIpv4(it) },
                    isError = !settingsState.dnsIpv4.isValid,
                    errorMessage = settingsState.dnsIpv4.error,
                    enabled = !vpnDisabled,
                    keyboardType = KeyboardType.Number,
                    sheetState = sheetState,
                    scope = scope,
                    icon = Icons.Default.Dns
                )

                FuturisticDivider()

                FuturisticEditableItem(
                    title = stringResource(R.string.dns_ipv6),
                    value = settingsState.dnsIpv6.value,
                    onValueConfirmed = { mainViewModel.updateDnsIpv6(it) },
                    isError = !settingsState.dnsIpv6.isValid,
                    errorMessage = settingsState.dnsIpv6.error,
                    enabled = settingsState.switches.ipv6Enabled && !vpnDisabled,
                    keyboardType = KeyboardType.Uri,
                    sheetState = sheetState,
                    scope = scope,
                    icon = Icons.Default.Dns
                )

                FuturisticDivider()

                FuturisticSwitchItem(
                    title = stringResource(R.string.ipv6),
                    subtitle = stringResource(R.string.ipv6_enabled),
                    checked = settingsState.switches.ipv6Enabled,
                    onCheckedChange = { mainViewModel.setIpv6Enabled(it) },
                    enabled = !vpnDisabled,
                    icon = Icons.Default.Language
                )

                FuturisticDivider()

                FuturisticSwitchItem(
                    title = stringResource(R.string.http_proxy_title),
                    subtitle = stringResource(R.string.http_proxy_summary),
                    checked = settingsState.switches.httpProxyEnabled,
                    onCheckedChange = { mainViewModel.setHttpProxyEnabled(it) },
                    enabled = !vpnDisabled,
                    icon = Icons.Default.Http
                )

                FuturisticDivider()

                FuturisticSwitchItem(
                    title = stringResource(R.string.bypass_lan_title),
                    subtitle = stringResource(R.string.bypass_lan_summary),
                    checked = settingsState.switches.bypassLanEnabled,
                    onCheckedChange = { mainViewModel.setBypassLanEnabled(it) },
                    enabled = !vpnDisabled,
                    icon = Icons.Default.Wifi
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // NOTIFICATIONS
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                accentColor = FuturisticSettingsColors.NeonBlue
            ) {
                FuturisticNavigationItem(
                    title = "Telegram Notifications",
                    subtitle = "Configure Telegram bot for notifications and status updates",
                    onClick = { mainViewModel.navigate(ROUTE_TELEGRAM_SETTINGS) },
                    icon = Icons.Default.Send
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // XRAY CORE INSTANCES
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = stringResource(R.string.xray_core_instances_title),
                icon = Icons.Default.Memory,
                accentColor = FuturisticSettingsColors.NeonPurple
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.xray_core_instances_summary),
                        color = Color(0xFFB0B0B0),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(FuturisticSettingsColors.WarningGlow)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.xray_core_instances_warning),
                            color = FuturisticSettingsColors.WarningGlow,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    (1..4).forEach { count ->
                        val isSelected = settingsState.xrayCoreInstanceCount == count
                        FuturisticSegmentButton(
                            text = count.toString(),
                            isSelected = isSelected,
                            onClick = { mainViewModel.setXrayCoreInstanceCount(count) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // RULE FILES
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = stringResource(R.string.rule_files_category_title),
                icon = Icons.Default.FolderOpen,
                accentColor = FuturisticSettingsColors.NeonGreen
            ) {
                FuturisticRuleFileItem(
                    fileName = "geoip.dat",
                    summary = geoipProgress ?: settingsState.info.geoipSummary,
                    isDownloading = geoipProgress != null,
                    isCustom = settingsState.files.isGeoipCustom,
                    onDownloadClick = {
                        ruleFileUrl = settingsState.info.geoipUrl
                        editingRuleFile = "geoip.dat"
                        scope.launch { sheetState.show() }
                    },
                    onCancelClick = { mainViewModel.cancelDownload("geoip.dat") },
                    onImportClick = { geoipFilePickerLauncher.launch(arrayOf("*/*")) },
                    onDeleteClick = { showGeoipDeleteDialog = true }
                )

                FuturisticDivider()

                FuturisticRuleFileItem(
                    fileName = "geosite.dat",
                    summary = geositeProgress ?: settingsState.info.geositeSummary,
                    isDownloading = geositeProgress != null,
                    isCustom = settingsState.files.isGeositeCustom,
                    onDownloadClick = {
                        ruleFileUrl = settingsState.info.geositeUrl
                        editingRuleFile = "geosite.dat"
                        scope.launch { sheetState.show() }
                    },
                    onCancelClick = { mainViewModel.cancelDownload("geosite.dat") },
                    onImportClick = { geositeFilePickerLauncher.launch(arrayOf("*/*")) },
                    onDeleteClick = { showGeositeDeleteDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // PERFORMANCE SETTINGS
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = "Performance & Speed",
                icon = Icons.Default.Speed,
                accentColor = FuturisticSettingsColors.NeonOrange
            ) {
                FuturisticSwitchItem(
                    title = "Aggressive Speed Optimizations",
                    subtitle = "Enable aggressive performance optimizations for maximum speed",
                    checked = settingsState.performance.aggressiveSpeedOptimizations,
                    onCheckedChange = { mainViewModel.setAggressiveSpeedOptimizations(it) },
                    icon = Icons.Default.Bolt,
                    accentColor = FuturisticSettingsColors.NeonOrange
                )

                AnimatedVisibility(
                    visible = settingsState.performance.aggressiveSpeedOptimizations,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Connection Idle Timeout (s)",
                            value = settingsState.performance.connIdleTimeout.value,
                            onValueConfirmed = { mainViewModel.updateConnIdleTimeout(it) },
                            isError = !settingsState.performance.connIdleTimeout.isValid,
                            errorMessage = settingsState.performance.connIdleTimeout.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Timer
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Handshake Timeout (s)",
                            value = settingsState.performance.handshakeTimeout.value,
                            onValueConfirmed = { mainViewModel.updateHandshakeTimeout(it) },
                            isError = !settingsState.performance.handshakeTimeout.isValid,
                            errorMessage = settingsState.performance.handshakeTimeout.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Handshake
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Uplink Buffer (0 = unlimited)",
                            value = settingsState.performance.uplinkOnly.value,
                            onValueConfirmed = { mainViewModel.updateUplinkOnly(it) },
                            isError = !settingsState.performance.uplinkOnly.isValid,
                            errorMessage = settingsState.performance.uplinkOnly.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Upload
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Downlink Buffer (0 = unlimited)",
                            value = settingsState.performance.downlinkOnly.value,
                            onValueConfirmed = { mainViewModel.updateDownlinkOnly(it) },
                            isError = !settingsState.performance.downlinkOnly.isValid,
                            errorMessage = settingsState.performance.downlinkOnly.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Download
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "DNS Cache Size",
                            value = settingsState.performance.dnsCacheSize.value,
                            onValueConfirmed = { mainViewModel.updateDnsCacheSize(it) },
                            isError = !settingsState.performance.dnsCacheSize.isValid,
                            errorMessage = settingsState.performance.dnsCacheSize.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Storage
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "Disable Fake DNS",
                            subtitle = "Disable fake DNS to improve speed",
                            checked = settingsState.performance.disableFakeDns,
                            onCheckedChange = { mainViewModel.setDisableFakeDns(it) },
                            icon = Icons.Default.Dns
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "Optimize Routing Rules",
                            subtitle = "Enable hybrid domain matcher",
                            checked = settingsState.performance.optimizeRoutingRules,
                            onCheckedChange = { mainViewModel.setOptimizeRoutingRules(it) },
                            icon = Icons.Default.Route
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "TCP Fast Open",
                            subtitle = "Faster connection establishment",
                            checked = settingsState.performance.tcpFastOpen,
                            onCheckedChange = { mainViewModel.setTcpFastOpen(it) },
                            icon = Icons.Default.FlashOn
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "HTTP/2 Optimization",
                            subtitle = "Optimize HTTP/2 settings",
                            checked = settingsState.performance.http2Optimization,
                            onCheckedChange = { mainViewModel.setHttp2Optimization(it) },
                            icon = Icons.Default.Http
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // EXTREME OPTIMIZATION
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = "⚡ EXTREME Optimization",
                icon = Icons.Default.Whatshot,
                accentColor = FuturisticSettingsColors.ErrorGlow,
                isWarning = true
            ) {
                FuturisticSwitchItem(
                    title = "🚀 Extreme RAM/CPU Mode",
                    subtitle = "WARNING: Maximum resource utilization. High battery & heat.",
                    checked = settingsState.performance.extreme.extremeRamCpuOptimizations,
                    onCheckedChange = { mainViewModel.setExtremeRamCpuOptimizations(it) },
                    icon = Icons.Default.LocalFireDepartment,
                    accentColor = FuturisticSettingsColors.ErrorGlow
                )

                AnimatedVisibility(
                    visible = settingsState.performance.extreme.extremeRamCpuOptimizations,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Extreme Conn Idle Timeout (s)",
                            value = settingsState.performance.extreme.extremeConnIdleTimeout.value,
                            onValueConfirmed = { mainViewModel.updateExtremeConnIdleTimeout(it) },
                            isError = !settingsState.performance.extreme.extremeConnIdleTimeout.isValid,
                            errorMessage = settingsState.performance.extreme.extremeConnIdleTimeout.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Timer
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Extreme Handshake Timeout (s)",
                            value = settingsState.performance.extreme.extremeHandshakeTimeout.value,
                            onValueConfirmed = { mainViewModel.updateExtremeHandshakeTimeout(it) },
                            isError = !settingsState.performance.extreme.extremeHandshakeTimeout.isValid,
                            errorMessage = settingsState.performance.extreme.extremeHandshakeTimeout.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Handshake
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Extreme Uplink Buffer",
                            value = settingsState.performance.extreme.extremeUplinkOnly.value,
                            onValueConfirmed = { mainViewModel.updateExtremeUplinkOnly(it) },
                            isError = !settingsState.performance.extreme.extremeUplinkOnly.isValid,
                            errorMessage = settingsState.performance.extreme.extremeUplinkOnly.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Upload
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Extreme Downlink Buffer",
                            value = settingsState.performance.extreme.extremeDownlinkOnly.value,
                            onValueConfirmed = { mainViewModel.updateExtremeDownlinkOnly(it) },
                            isError = !settingsState.performance.extreme.extremeDownlinkOnly.isValid,
                            errorMessage = settingsState.performance.extreme.extremeDownlinkOnly.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Download
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Max Concurrent Connections",
                            value = settingsState.performance.extreme.maxConcurrentConnections.value,
                            onValueConfirmed = { mainViewModel.updateMaxConcurrentConnections(it) },
                            isError = !settingsState.performance.extreme.maxConcurrentConnections.isValid,
                            errorMessage = settingsState.performance.extreme.maxConcurrentConnections.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Hub
                        )
                        FuturisticDivider()
                        FuturisticEditableItem(
                            title = "Extreme DNS Cache Size",
                            value = settingsState.performance.extreme.extremeDnsCacheSize.value,
                            onValueConfirmed = { mainViewModel.updateExtremeDnsCacheSize(it) },
                            isError = !settingsState.performance.extreme.extremeDnsCacheSize.isValid,
                            errorMessage = settingsState.performance.extreme.extremeDnsCacheSize.error,
                            keyboardType = KeyboardType.Number,
                            sheetState = sheetState,
                            scope = scope,
                            icon = Icons.Default.Storage
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "Extreme: Disable Fake DNS",
                            subtitle = "Maximum speed (reduces privacy)",
                            checked = settingsState.performance.extreme.extremeDisableFakeDns,
                            onCheckedChange = { mainViewModel.setExtremeDisableFakeDns(it) },
                            icon = Icons.Default.Dns
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "Parallel DNS Queries",
                            subtitle = "Maximum CPU utilization",
                            checked = settingsState.performance.extreme.parallelDnsQueries,
                            onCheckedChange = { mainViewModel.setParallelDnsQueries(it) },
                            icon = Icons.Default.CallSplit
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "Extreme Routing Optimization",
                            subtitle = "Max CPU for routing lookups",
                            checked = settingsState.performance.extreme.extremeRoutingOptimization,
                            onCheckedChange = { mainViewModel.setExtremeRoutingOptimization(it) },
                            icon = Icons.Default.Route
                        )
                        FuturisticDivider()
                        FuturisticSwitchItem(
                            title = "Extreme Proxy Optimization",
                            subtitle = "Maximum throughput",
                            checked = settingsState.performance.extreme.extremeProxyOptimization,
                            onCheckedChange = { mainViewModel.setExtremeProxyOptimization(it) },
                            icon = Icons.Default.Rocket
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // CONNECTIVITY TEST
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = stringResource(R.string.connectivity_test),
                icon = Icons.Default.NetworkCheck,
                accentColor = FuturisticSettingsColors.NeonYellow
            ) {
                FuturisticEditableItem(
                    title = stringResource(R.string.connectivity_test_target),
                    value = settingsState.connectivityTestTarget.value,
                    onValueConfirmed = { mainViewModel.updateConnectivityTestTarget(it) },
                    isError = !settingsState.connectivityTestTarget.isValid,
                    errorMessage = settingsState.connectivityTestTarget.error,
                    keyboardType = KeyboardType.Uri,
                    sheetState = sheetState,
                    scope = scope,
                    icon = Icons.Default.Link
                )
                FuturisticDivider()
                FuturisticEditableItem(
                    title = stringResource(R.string.connectivity_test_timeout),
                    value = settingsState.connectivityTestTimeout.value,
                    onValueConfirmed = { mainViewModel.updateConnectivityTestTimeout(it) },
                    isError = !settingsState.connectivityTestTimeout.isValid,
                    errorMessage = settingsState.connectivityTestTimeout.error,
                    keyboardType = KeyboardType.Number,
                    sheetState = sheetState,
                    scope = scope,
                    icon = Icons.Default.Timer
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════
            // ABOUT
            // ═══════════════════════════════════════════════════════════════
            FuturisticSettingsCard(
                title = stringResource(R.string.about),
                icon = Icons.Default.Info,
                accentColor = FuturisticSettingsColors.NeonCyan
            ) {
                FuturisticAboutItem(
                    title = stringResource(R.string.version),
                    value = settingsState.info.appVersion,
                    isCheckingForUpdates = isCheckingForUpdates,
                    onCheckUpdates = { mainViewModel.checkForUpdates() }
                )
                FuturisticDivider()
                FuturisticInfoItem(
                    title = stringResource(R.string.kernel),
                    value = settingsState.info.kernelVersion,
                    icon = Icons.Default.Memory
                )
                FuturisticDivider()
                FuturisticNavigationItem(
                    title = stringResource(R.string.source),
                    subtitle = stringResource(R.string.open_source),
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.source_url)))
                        context.startActivity(browserIntent)
                    },
                    icon = Icons.Default.Code
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
// FUTURISTIC UI COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun FuturisticSettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color = FuturisticSettingsColors.NeonCyan,
    isWarning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cardGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cardGlowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neonGlow(accentColor.copy(alpha = glowAlpha * 0.5f), 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = if (isWarning) listOf(
                        Color(0xFF1A0A0A).copy(alpha = 0.9f),
                        Color(0xFF0A0505).copy(alpha = 0.7f)
                    ) else listOf(
                        Color(0xFF0A0A1A).copy(alpha = 0.9f),
                        Color(0xFF050510).copy(alpha = 0.7f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.7f),
                        accentColor.copy(alpha = 0.2f),
                        accentColor.copy(alpha = 0.5f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            // Header with icon
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = accentColor
                )
            }
            content()
        }
    }
}

@Composable
private fun FuturisticSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    accentColor: Color = FuturisticSettingsColors.NeonCyan
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accentColor.copy(alpha = 0.7f) else Color.Gray,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) Color.White else Color.Gray,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = if (enabled) Color(0xFF888888) else Color(0xFF555555),
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        FuturisticSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            accentColor = if (checked) accentColor else Color.Gray
        )
    }
}


@Composable
private fun FuturisticSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    accentColor: Color = FuturisticSettingsColors.NeonCyan
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "thumbOffset"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) accentColor.copy(alpha = 0.3f) else Color(0xFF333333),
        animationSpec = tween(200),
        label = "trackColor"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) accentColor else Color(0xFF666666),
        animationSpec = tween(200),
        label = "thumbColor"
    )

    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .border(1.dp, if (checked) accentColor.copy(alpha = 0.5f) else Color(0xFF444444), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
                .then(
                    if (checked) Modifier.neonGlow(accentColor, 4.dp, false) else Modifier
                )
        )
    }
}

@Composable
private fun FuturisticNavigationItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color(0xFF888888), fontSize = 13.sp)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuturisticDropdownItem(
    title: String,
    subtitle: String,
    selectedValue: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color(0xFF888888), fontSize = 13.sp)
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
            Box(
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FuturisticSettingsColors.NeonCyan.copy(alpha = 0.1f))
                    .border(1.dp, FuturisticSettingsColors.NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = selectedValue, color = FuturisticSettingsColors.NeonCyan, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = FuturisticSettingsColors.NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.background(Color(0xFF1A1A2E))
            ) {
                content()
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuturisticEditableItem(
    title: String,
    value: String,
    onValueConfirmed: (String) -> Unit,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    sheetState: SheetState,
    scope: CoroutineScope,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(value) }

    if (showSheet) {
        FuturisticBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                NeonSectionTitle("✏️ $title")
                Spacer(modifier = Modifier.height(16.dp))
                FuturisticTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = title,
                    isError = isError,
                    errorMessage = errorMessage,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FuturisticButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) showSheet = false }
                    }, color = Color.Gray.copy(alpha = 0.5f)) { Text(stringResource(R.string.cancel)) }
                    Spacer(modifier = Modifier.width(12.dp))
                    FuturisticButton(onClick = {
                        onValueConfirmed(tempValue)
                        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) showSheet = false }
                    }) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }

    val displayValue = if (value == "0" && (title.contains("Buffer") || title.contains("unlimited"))) "Unlimited" else value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { tempValue = value; showSheet = true }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) FuturisticSettingsColors.NeonCyan.copy(alpha = 0.7f) else Color.Gray,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) Color.White else Color.Gray,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = displayValue,
                color = if (isError) FuturisticSettingsColors.ErrorGlow else if (enabled) Color(0xFF888888) else Color(0xFF555555),
                fontSize = 13.sp
            )
        }
        if (isError) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = errorMessage,
                tint = FuturisticSettingsColors.ErrorGlow,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) FuturisticSettingsColors.NeonCyan.copy(alpha = 0.5f) else Color.Gray
            )
        }
    }
}

@Composable
private fun FuturisticDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        thickness = 1.dp,
        color = Color(0xFF222233)
    )
}

@Composable
private fun FuturisticSegmentButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) FuturisticSettingsColors.NeonPurple.copy(alpha = 0.25f) else Color(0xFF1A1A2E),
        animationSpec = tween(200),
        label = "segmentBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) FuturisticSettingsColors.NeonPurple else Color(0xFF333344),
        animationSpec = tween(200),
        label = "segmentBorder"
    )

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(if (isSelected) Modifier.neonGlow(FuturisticSettingsColors.NeonPurple, 6.dp, false) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) FuturisticSettingsColors.NeonPurple else Color(0xFF888888),
            fontSize = 18.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}


@Composable
private fun FuturisticRuleFileItem(
    fileName: String,
    summary: String,
    isDownloading: Boolean,
    isCustom: Boolean,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onImportClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = FuturisticSettingsColors.NeonGreen.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = fileName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = summary, color = Color(0xFF888888), fontSize = 13.sp)
        }
        Row {
            if (isDownloading) {
                FuturisticIconButton(onClick = onCancelClick, icon = Icons.Default.Cancel, tint = FuturisticSettingsColors.ErrorGlow)
            } else {
                FuturisticIconButton(onClick = onDownloadClick, icon = Icons.Default.CloudDownload, tint = FuturisticSettingsColors.NeonCyan)
                if (!isCustom) {
                    FuturisticIconButton(onClick = onImportClick, icon = Icons.Default.FileUpload, tint = FuturisticSettingsColors.NeonGreen)
                } else {
                    FuturisticIconButton(onClick = onDeleteClick, icon = Icons.Default.Delete, tint = FuturisticSettingsColors.ErrorGlow)
                }
            }
        }
    }
}

@Composable
private fun FuturisticIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.1f))
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FuturisticAboutItem(
    title: String,
    value: String,
    isCheckingForUpdates: Boolean,
    onCheckUpdates: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Update,
            contentDescription = null,
            tint = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = value, color = Color(0xFF888888), fontSize = 13.sp)
        }
        FuturisticButton(
            onClick = onCheckUpdates,
            enabled = !isCheckingForUpdates,
            small = true
        ) {
            if (isCheckingForUpdates) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = FuturisticSettingsColors.NeonCyan,
                    strokeWidth = 2.dp
                )
            } else {
                Text(stringResource(R.string.check_for_updates), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FuturisticInfoItem(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = value, color = Color(0xFF888888), fontSize = 13.sp)
        }
    }
}


@Composable
private fun FuturisticButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = FuturisticSettingsColors.NeonCyan,
    small: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "btnGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "btnGlowAlpha"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (enabled) Modifier.neonGlow(color.copy(alpha = glowAlpha), 4.dp, true) else Modifier),
        shape = RoundedCornerShape(if (small) 8.dp else 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = color,
            disabledContainerColor = Color.Gray.copy(alpha = 0.1f),
            disabledContentColor = Color.Gray
        ),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    color.copy(alpha = if (enabled) 0.7f else 0.3f),
                    color.copy(alpha = if (enabled) 0.3f else 0.1f)
                )
            )
        ),
        contentPadding = if (small) PaddingValues(horizontal = 12.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
        content = content
    )
}

@Composable
private fun FuturisticTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.7f)) },
        keyboardOptions = keyboardOptions,
        isError = isError,
        supportingText = if (isError && errorMessage != null) {
            { Text(errorMessage, color = FuturisticSettingsColors.ErrorGlow) }
        } else null,
        trailingIcon = trailingIcon,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = FuturisticSettingsColors.NeonCyan,
            unfocusedBorderColor = FuturisticSettingsColors.NeonCyan.copy(alpha = 0.3f),
            errorBorderColor = FuturisticSettingsColors.ErrorGlow,
            cursorColor = FuturisticSettingsColors.NeonCyan,
            focusedContainerColor = Color(0xFF0A0A1A),
            unfocusedContainerColor = Color(0xFF0A0A1A)
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun NeonSectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(FuturisticSettingsColors.NeonCyan, FuturisticSettingsColors.NeonPurple)
                    )
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuturisticBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xFF0A0A1A),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FuturisticSettingsColors.NeonCyan.copy(alpha = 0.5f))
            )
        },
        content = content
    )
}

@Composable
private fun FuturisticAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    accentColor: Color = FuturisticSettingsColors.NeonCyan
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(title, color = accentColor, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text, color = Color(0xFFCCCCCC))
        },
        confirmButton = {
            FuturisticButton(onClick = onConfirm, color = accentColor, small = true) {
                Text(confirmText)
            }
        },
        dismissButton = {
            FuturisticButton(onClick = onDismiss, color = Color.Gray, small = true) {
                Text(dismissText)
            }
        },
        containerColor = Color(0xFF0A0A1A),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp
    )
}
