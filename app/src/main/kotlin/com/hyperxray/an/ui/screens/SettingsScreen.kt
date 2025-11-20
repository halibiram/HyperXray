package com.hyperxray.an.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.common.ROUTE_TELEGRAM_SETTINGS
import com.hyperxray.an.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    val themeOptions = listOf(
        ThemeMode.Light,
        ThemeMode.Dark,
        ThemeMode.Auto
    )
    var selectedThemeOption by remember { mutableStateOf(settingsState.switches.themeMode) }
    var themeExpanded by remember { mutableStateOf(false) }

    if (editingRuleFile != null) {
        ModalBottomSheet(
            onDismissRequest = { editingRuleFile = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = ruleFileUrl,
                    onValueChange = { ruleFileUrl = it },
                    label = { Text("URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                    trailingIcon = {
                        val clipboardManager = LocalClipboard.current
                        IconButton(onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text
                                    .let {
                                        ruleFileUrl = it.toString()
                                    }
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.paste),
                                contentDescription = "Paste"
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = {
                    ruleFileUrl =
                        if (editingRuleFile == "geoip.dat") context.getString(R.string.geoip_url)
                        else context.getString(R.string.geosite_url)
                }) {
                    Text(stringResource(id = R.string.restore_default_url))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                editingRuleFile = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        mainViewModel.downloadRuleFile(ruleFileUrl, editingRuleFile!!)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                editingRuleFile = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.update))
                    }
                }
            }
        }
    }

    if (showGeoipDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGeoipDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_rule_file_title)) },
            text = { Text(stringResource(R.string.delete_rule_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainViewModel.restoreDefaultGeoip { }
                        showGeoipDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeoipDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showGeositeDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGeositeDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_rule_file_title)) },
            text = { Text(stringResource(R.string.delete_rule_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainViewModel.restoreDefaultGeosite { }
                        showGeositeDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeositeDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (newVersionTag != null) {
        AlertDialog(
            onDismissRequest = { mainViewModel.clearNewVersionAvailable() },
            title = { Text(stringResource(R.string.new_version_available_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.new_version_available_message,
                        newVersionTag!!
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { mainViewModel.downloadNewVersion(newVersionTag!!) }) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { mainViewModel.clearNewVersionAvailable() }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            }
        )
    }

    // Responsive layout
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val horizontalPadding = if (isTablet) 24.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 8.dp

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
        Spacer(modifier = Modifier.height(8.dp))
        
        // General Settings Card
        SettingsCategoryCard(title = stringResource(R.string.general)) {
            ListItem(
                headlineContent = { 
                    Text(
                        stringResource(R.string.use_template_title),
                        color = Color.White
                    ) 
                },
                supportingContent = { 
                    Text(
                        stringResource(R.string.use_template_summary),
                        color = Color(0xFFB0B0B0)
                    ) 
                },
                trailingContent = {
                    Switch(
                        checked = settingsState.switches.useTemplateEnabled,
                        onCheckedChange = {
                            mainViewModel.setUseTemplateEnabled(it)
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.theme_title)) },
                supportingContent = {
                    Text(stringResource(id = R.string.theme_summary))
                },
                trailingContent = {
                    ExposedDropdownMenuBox(
                        expanded = themeExpanded,
                        onExpandedChange = { themeExpanded = it }
                    ) {
                        TextButton(
                            onClick = {},
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(
                                        id = when (selectedThemeOption) {
                                            ThemeMode.Light -> R.string.theme_light
                                            ThemeMode.Dark -> R.string.theme_dark
                                            ThemeMode.Auto -> R.string.auto
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (themeExpanded) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        ExposedDropdownMenu(
                            expanded = themeExpanded,
                            onDismissRequest = { themeExpanded = false }
                        ) {
                            themeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                id = when (option) {
                                                    ThemeMode.Light -> R.string.theme_light
                                                    ThemeMode.Dark -> R.string.theme_dark
                                                    ThemeMode.Auto -> R.string.auto
                                                }
                                            )
                                        )
                                    },
                                    onClick = {
                                        selectedThemeOption = option
                                        mainViewModel.setTheme(option)
                                        themeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // VPN Interface Settings Card
        SettingsCategoryCard(title = stringResource(R.string.vpn_interface)) {
            ListItem(
                modifier = Modifier.clickable {
                    mainViewModel.navigateToAppList()
                },
                headlineContent = { Text(stringResource(R.string.apps_title)) },
                supportingContent = { Text(stringResource(R.string.apps_summary)) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.disable_vpn_title)) },
                supportingContent = { Text(stringResource(R.string.disable_vpn_summary)) },
                trailingContent = {
                    Switch(
                        checked = settingsState.switches.disableVpn,
                        onCheckedChange = {
                            mainViewModel.setDisableVpnEnabled(it)
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            EditableListItemInCard(
                headline = stringResource(R.string.socks_port),
                currentValue = settingsState.socksPort.value,
                onValueConfirmed = { newValue -> mainViewModel.updateSocksPort(newValue) },
                label = stringResource(R.string.socks_port),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !settingsState.socksPort.isValid,
                errorMessage = settingsState.socksPort.error,
                enabled = !vpnDisabled,
                sheetState = sheetState,
                scope = scope
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            EditableListItemInCard(
                headline = stringResource(R.string.dns_ipv4),
                currentValue = settingsState.dnsIpv4.value,
                onValueConfirmed = { newValue -> mainViewModel.updateDnsIpv4(newValue) },
                label = stringResource(R.string.dns_ipv4),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !settingsState.dnsIpv4.isValid,
                errorMessage = settingsState.dnsIpv4.error,
                enabled = !vpnDisabled,
                sheetState = sheetState,
                scope = scope
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            EditableListItemInCard(
                headline = stringResource(R.string.dns_ipv6),
                currentValue = settingsState.dnsIpv6.value,
                onValueConfirmed = { newValue -> mainViewModel.updateDnsIpv6(newValue) },
                label = stringResource(R.string.dns_ipv6),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = !settingsState.dnsIpv6.isValid,
                errorMessage = settingsState.dnsIpv6.error,
                enabled = settingsState.switches.ipv6Enabled && !vpnDisabled,
                sheetState = sheetState,
                scope = scope
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.ipv6)) },
                supportingContent = { Text(stringResource(R.string.ipv6_enabled)) },
                trailingContent = {
                    Switch(
                        checked = settingsState.switches.ipv6Enabled,
                        onCheckedChange = {
                            mainViewModel.setIpv6Enabled(it)
                        },
                        enabled = !vpnDisabled
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.http_proxy_title)) },
                supportingContent = { Text(stringResource(R.string.http_proxy_summary)) },
                trailingContent = {
                    Switch(
                        checked = settingsState.switches.httpProxyEnabled,
                        onCheckedChange = {
                            mainViewModel.setHttpProxyEnabled(it)
                        },
                        enabled = !vpnDisabled
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.bypass_lan_title)) },
                supportingContent = { Text(stringResource(R.string.bypass_lan_summary)) },
                trailingContent = {
                    Switch(
                        checked = settingsState.switches.bypassLanEnabled,
                        onCheckedChange = {
                            mainViewModel.setBypassLanEnabled(it)
                        },
                        enabled = !vpnDisabled
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Notifications Card
        SettingsCategoryCard(title = "Notifications") {
            ListItem(
                modifier = Modifier.clickable {
                    mainViewModel.navigate(ROUTE_TELEGRAM_SETTINGS)
                },
                headlineContent = { Text("Telegram Notifications") },
                supportingContent = { Text("Configure Telegram bot for notifications and status updates") },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Xray Core Performance Settings Card
        SettingsCategoryCard(title = stringResource(R.string.xray_core_instances_title)) {
            ListItem(
                headlineContent = { 
                    Text(
                        stringResource(R.string.xray_core_instances_title),
                        color = Color.White
                    ) 
                },
                supportingContent = { 
                    Column {
                        Text(
                            stringResource(R.string.xray_core_instances_summary),
                            color = Color(0xFFB0B0B0)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.xray_core_instances_warning),
                            color = Color(0xFFFFA726),
                            fontSize = 12.sp
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Segmented button for instance count selection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..4).forEach { count ->
                    val isSelected = settingsState.xrayCoreInstanceCount == count
                    TextButton(
                        onClick = {
                            mainViewModel.setXrayCoreInstanceCount(count)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Rule Files Card
        SettingsCategoryCard(title = stringResource(R.string.rule_files_category_title)) {
            ListItem(
                headlineContent = { Text("geoip.dat") },
                supportingContent = { Text(geoipProgress ?: settingsState.info.geoipSummary) },
                trailingContent = {
                    Row {
                        if (geoipProgress != null) {
                            IconButton(onClick = { mainViewModel.cancelDownload("geoip.dat") }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.cancel),
                                    contentDescription = stringResource(R.string.cancel)
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                ruleFileUrl = settingsState.info.geoipUrl
                                editingRuleFile = "geoip.dat"
                                scope.launch { sheetState.show() }
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.cloud_download),
                                    contentDescription = stringResource(R.string.rule_file_update_url)
                                )
                            }
                            if (!settingsState.files.isGeoipCustom) {
                                IconButton(onClick = { geoipFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.place_item),
                                        contentDescription = stringResource(R.string.import_file)
                                    )
                                }
                            } else {
                                IconButton(onClick = { showGeoipDeleteDialog = true }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.delete),
                                        contentDescription = stringResource(R.string.reset_file)
                                    )
                                }
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text("geosite.dat") },
                supportingContent = { Text(geositeProgress ?: settingsState.info.geositeSummary) },
                trailingContent = {
                    Row {
                        if (geositeProgress != null) {
                            IconButton(onClick = { mainViewModel.cancelDownload("geosite.dat") }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.cancel),
                                    contentDescription = stringResource(R.string.cancel)
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                ruleFileUrl = settingsState.info.geositeUrl
                                editingRuleFile = "geosite.dat"
                                scope.launch { sheetState.show() }
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.cloud_download),
                                    contentDescription = stringResource(R.string.rule_file_update_url)
                                )
                            }
                            if (!settingsState.files.isGeositeCustom) {
                                IconButton(onClick = { geositeFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.place_item),
                                        contentDescription = stringResource(R.string.import_file)
                                    )
                                }
                            } else {
                                IconButton(onClick = { showGeositeDeleteDialog = true }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.delete),
                                        contentDescription = stringResource(R.string.reset_file)
                                    )
                                }
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Performance Settings Card
        SettingsCategoryCard(title = "Performance & Speed Optimization") {
            ListItem(
                headlineContent = { Text("Aggressive Speed Optimizations") },
                supportingContent = { 
                    Text("Enable aggressive performance optimizations for maximum speed. May increase battery usage.")
                },
                trailingContent = {
                    Switch(
                        checked = settingsState.performance.aggressiveSpeedOptimizations,
                        onCheckedChange = {
                            mainViewModel.setAggressiveSpeedOptimizations(it)
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            if (settingsState.performance.aggressiveSpeedOptimizations) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Connection Settings
                EditableListItemInCard(
                    headline = "Connection Idle Timeout (seconds)",
                    currentValue = settingsState.performance.connIdleTimeout.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateConnIdleTimeout(newValue) },
                    label = "Connection Idle Timeout",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.connIdleTimeout.isValid,
                    errorMessage = settingsState.performance.connIdleTimeout.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Handshake Timeout (seconds)",
                    currentValue = settingsState.performance.handshakeTimeout.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateHandshakeTimeout(newValue) },
                    label = "Handshake Timeout",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.handshakeTimeout.isValid,
                    errorMessage = settingsState.performance.handshakeTimeout.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Uplink Only Buffer (0 = unlimited)",
                    currentValue = settingsState.performance.uplinkOnly.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateUplinkOnly(newValue) },
                    label = "Uplink Only (0 = unlimited)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.uplinkOnly.isValid,
                    errorMessage = settingsState.performance.uplinkOnly.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Downlink Only Buffer (0 = unlimited)",
                    currentValue = settingsState.performance.downlinkOnly.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateDownlinkOnly(newValue) },
                    label = "Downlink Only (0 = unlimited)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.downlinkOnly.isValid,
                    errorMessage = settingsState.performance.downlinkOnly.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // DNS Settings
                EditableListItemInCard(
                    headline = "DNS Cache Size",
                    currentValue = settingsState.performance.dnsCacheSize.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateDnsCacheSize(newValue) },
                    label = "DNS Cache Size",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.dnsCacheSize.isValid,
                    errorMessage = settingsState.performance.dnsCacheSize.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ListItem(
                    headlineContent = { Text("Disable Fake DNS") },
                    supportingContent = { 
                        Text("Disable fake DNS to improve speed (may reduce privacy)")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.disableFakeDns,
                            onCheckedChange = {
                                mainViewModel.setDisableFakeDns(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Routing & Protocol Optimizations
                ListItem(
                    headlineContent = { Text("Optimize Routing Rules") },
                    supportingContent = { 
                        Text("Enable hybrid domain matcher for faster routing")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.optimizeRoutingRules,
                            onCheckedChange = {
                                mainViewModel.setOptimizeRoutingRules(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ListItem(
                    headlineContent = { Text("TCP Fast Open") },
                    supportingContent = { 
                        Text("Enable TCP Fast Open for faster connection establishment")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.tcpFastOpen,
                            onCheckedChange = {
                                mainViewModel.setTcpFastOpen(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ListItem(
                    headlineContent = { Text("HTTP/2 Optimization") },
                    supportingContent = { 
                        Text("Optimize HTTP/2 settings for better performance")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.http2Optimization,
                            onCheckedChange = {
                                mainViewModel.setHttp2Optimization(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Extreme RAM/CPU Optimization Card
        SettingsCategoryCard(title = "⚠️ EXTREME RAM/CPU Optimization") {
            ListItem(
                headlineContent = { Text("🚀 Extreme RAM/CPU Mode") },
                supportingContent = { 
                    Text("WARNING: Maximum resource utilization. Significantly increases battery consumption and heat. Use only on high-performance devices with adequate cooling.")
                },
                trailingContent = {
                    Switch(
                        checked = settingsState.performance.extreme.extremeRamCpuOptimizations,
                        onCheckedChange = {
                            mainViewModel.setExtremeRamCpuOptimizations(it)
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            if (settingsState.performance.extreme.extremeRamCpuOptimizations) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Extreme Connection Settings
                EditableListItemInCard(
                    headline = "Extreme Connection Idle Timeout (seconds)",
                    currentValue = settingsState.performance.extreme.extremeConnIdleTimeout.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateExtremeConnIdleTimeout(newValue) },
                    label = "Connection Idle Timeout",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.extreme.extremeConnIdleTimeout.isValid,
                    errorMessage = settingsState.performance.extreme.extremeConnIdleTimeout.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Extreme Handshake Timeout (seconds)",
                    currentValue = settingsState.performance.extreme.extremeHandshakeTimeout.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateExtremeHandshakeTimeout(newValue) },
                    label = "Handshake Timeout",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.extreme.extremeHandshakeTimeout.isValid,
                    errorMessage = settingsState.performance.extreme.extremeHandshakeTimeout.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Extreme Uplink Buffer (0 = unlimited)",
                    currentValue = settingsState.performance.extreme.extremeUplinkOnly.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateExtremeUplinkOnly(newValue) },
                    label = "Uplink Only (0 = unlimited)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.extreme.extremeUplinkOnly.isValid,
                    errorMessage = settingsState.performance.extreme.extremeUplinkOnly.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Extreme Downlink Buffer (0 = unlimited)",
                    currentValue = settingsState.performance.extreme.extremeDownlinkOnly.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateExtremeDownlinkOnly(newValue) },
                    label = "Downlink Only (0 = unlimited)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.extreme.extremeDownlinkOnly.isValid,
                    errorMessage = settingsState.performance.extreme.extremeDownlinkOnly.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                EditableListItemInCard(
                    headline = "Max Concurrent Connections (0 = unlimited)",
                    currentValue = settingsState.performance.extreme.maxConcurrentConnections.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateMaxConcurrentConnections(newValue) },
                    label = "Max Concurrent Connections",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.extreme.maxConcurrentConnections.isValid,
                    errorMessage = settingsState.performance.extreme.maxConcurrentConnections.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Extreme DNS Settings
                EditableListItemInCard(
                    headline = "Extreme DNS Cache Size",
                    currentValue = settingsState.performance.extreme.extremeDnsCacheSize.value,
                    onValueConfirmed = { newValue -> mainViewModel.updateExtremeDnsCacheSize(newValue) },
                    label = "DNS Cache Size",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !settingsState.performance.extreme.extremeDnsCacheSize.isValid,
                    errorMessage = settingsState.performance.extreme.extremeDnsCacheSize.error,
                    sheetState = sheetState,
                    scope = scope
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ListItem(
                    headlineContent = { Text("Extreme: Disable Fake DNS") },
                    supportingContent = { 
                        Text("Disable fake DNS for maximum speed (reduces privacy)")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.extreme.extremeDisableFakeDns,
                            onCheckedChange = {
                                mainViewModel.setExtremeDisableFakeDns(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ListItem(
                    headlineContent = { Text("Parallel DNS Queries") },
                    supportingContent = { 
                        Text("Enable parallel DNS queries for maximum CPU utilization")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.extreme.parallelDnsQueries,
                            onCheckedChange = {
                                mainViewModel.setParallelDnsQueries(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Extreme Protocol Optimizations
                ListItem(
                    headlineContent = { Text("Extreme Routing Optimization") },
                    supportingContent = { 
                        Text("Maximum CPU utilization for routing table lookups")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.extreme.extremeRoutingOptimization,
                            onCheckedChange = {
                                mainViewModel.setExtremeRoutingOptimization(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ListItem(
                    headlineContent = { Text("Extreme Proxy Optimization") },
                    supportingContent = { 
                        Text("Optimize proxy settings for maximum throughput")
                    },
                    trailingContent = {
                        Switch(
                            checked = settingsState.performance.extreme.extremeProxyOptimization,
                            onCheckedChange = {
                                mainViewModel.setExtremeProxyOptimization(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connectivity Test Card
        SettingsCategoryCard(title = stringResource(R.string.connectivity_test)) {
            EditableListItemInCard(
                headline = stringResource(R.string.connectivity_test_target),
                currentValue = settingsState.connectivityTestTarget.value,
                onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestTarget(newValue) },
                label = stringResource(R.string.connectivity_test_target),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = !settingsState.connectivityTestTarget.isValid,
                errorMessage = settingsState.connectivityTestTarget.error,
                sheetState = sheetState,
                scope = scope
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            EditableListItemInCard(
                headline = stringResource(R.string.connectivity_test_timeout),
                currentValue = settingsState.connectivityTestTimeout.value,
                onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestTimeout(newValue) },
                label = stringResource(R.string.connectivity_test_timeout),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !settingsState.connectivityTestTimeout.isValid,
                errorMessage = settingsState.connectivityTestTimeout.error,
                sheetState = sheetState,
                scope = scope
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About Card
        SettingsCategoryCard(title = stringResource(R.string.about)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.version)) },
                supportingContent = { Text(settingsState.info.appVersion) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                mainViewModel.checkForUpdates()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                disabledContainerColor = Color.Transparent
                            ),
                            enabled = !isCheckingForUpdates
                        ) {
                            if (isCheckingForUpdates) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(20.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.check_for_updates))
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.kernel)) },
                supportingContent = { Text(settingsState.info.kernelVersion) },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                modifier = Modifier.clickable {
                    val browserIntent =
                        Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.source_url)))
                    context.startActivity(browserIntent)
                },
                headlineContent = { Text(stringResource(R.string.source)) },
                supportingContent = { Text(stringResource(R.string.open_source)) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableListItemWithBottomSheet(
    headline: String,
    currentValue: String,
    onValueConfirmed: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    sheetState: SheetState,
    scope: CoroutineScope
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(currentValue) }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = { Text(label) },
                    keyboardOptions = keyboardOptions,
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text(text = errorMessage ?: "")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onValueConfirmed(tempValue)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }

    val displayValue = if (currentValue == "0" && (headline.contains("Buffer") || headline.contains("unlimited"))) {
        "Unlimited"
    } else {
        currentValue
    }

    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(displayValue) },
        modifier = Modifier.clickable(enabled = enabled) {
            tempValue = currentValue
            showSheet = true
        },
        trailingContent = {
            if (isError) {
                Icon(
                    painter = painterResource(id = R.drawable.cancel),
                    contentDescription = errorMessage,
                    tint = MaterialTheme.colorScheme.error
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
fun SettingsCategoryCard(
    title: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000).copy(alpha = 0.7f), // Obsidian glass
                        Color(0xFF0A0A0A).copy(alpha = 0.5f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF00E5FF), // Neon Cyan
                        Color(0xFF2979FF), // Electric Blue
                        Color(0xFF651FFF)  // Deep Purple
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                ),
                color = Color(0xFF00E5FF), // Neon Cyan
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableListItemInCard(
    headline: String,
    currentValue: String,
    onValueConfirmed: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    sheetState: SheetState,
    scope: CoroutineScope
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(currentValue) }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = { Text(label) },
                    keyboardOptions = keyboardOptions,
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text(text = errorMessage ?: "")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onValueConfirmed(tempValue)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }

    val displayValue = if (currentValue == "0" && (headline.contains("Buffer") || headline.contains("unlimited"))) {
        "Unlimited"
    } else {
        currentValue
    }

    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(displayValue) },
        modifier = Modifier.clickable(enabled = enabled) {
            tempValue = currentValue
            showSheet = true
        },
        trailingContent = {
            if (isError) {
                Icon(
                    painter = painterResource(id = R.drawable.cancel),
                    contentDescription = errorMessage,
                    tint = MaterialTheme.colorScheme.error
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
