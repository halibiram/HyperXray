package com.hyperxray.an.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.ui.util.bracketMatcherTransformation
import com.hyperxray.an.viewmodel.ConfigEditUiEvent
import com.hyperxray.an.viewmodel.ConfigEditViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditScreen(
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: ConfigEditViewModel
) {
    var showMenu by remember { mutableStateOf(false) }
    val filename by viewModel.filename.collectAsStateWithLifecycle()
    val configTextFieldValue by viewModel.configTextFieldValue.collectAsStateWithLifecycle()
    val filenameErrorMessage by viewModel.filenameErrorMessage.collectAsStateWithLifecycle()
    val hasConfigChanged by viewModel.hasConfigChanged.collectAsStateWithLifecycle()
    val sni by viewModel.sni.collectAsStateWithLifecycle()
    val streamSecurity by viewModel.streamSecurity.collectAsStateWithLifecycle()
    val fingerprint by viewModel.fingerprint.collectAsStateWithLifecycle()
    val alpn by viewModel.alpn.collectAsStateWithLifecycle()
    val allowInsecure by viewModel.allowInsecure.collectAsStateWithLifecycle()
    
    // DPI Evasion (God Mode) settings
    val enableFragment by viewModel.enableFragment.collectAsStateWithLifecycle()
    val fragmentLength by viewModel.fragmentLength.collectAsStateWithLifecycle()
    val fragmentInterval by viewModel.fragmentInterval.collectAsStateWithLifecycle()
    val enableMux by viewModel.enableMux.collectAsStateWithLifecycle()
    val muxConcurrency by viewModel.muxConcurrency.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {}

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is ConfigEditUiEvent.NavigateBack -> {
                    onBackClick()
                }

                is ConfigEditUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(
                        event.message,
                        duration = SnackbarDuration.Short
                    )
                }

                is ConfigEditUiEvent.ShareContent -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.content)
                    }
                    shareLauncher.launch(Intent.createChooser(shareIntent, null))
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Obsidian background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF000000), // Pure obsidian black
                            Color(0xFF0A0A0A),
                            Color(0xFF000000)
                        )
                    )
                )
        )
        
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent, // Transparent to show obsidian background
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            stringResource(id = R.string.config),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp
                            ),
                            color = Color.White
                        ) 
                    }, 
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(
                                    R.string.back
                                )
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            viewModel.saveConfigFile()
                            focusManager.clearFocus()
                        }, enabled = hasConfigChanged) {
                            Icon(
                                painter = painterResource(id = R.drawable.save),
                                contentDescription = stringResource(id = R.string.save)
                            )
                        }
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more)
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.share)) },
                                onClick = {
                                    viewModel.shareConfigFile()
                                    showMenu = false
                                })
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }, 
            content = { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(top = paddingValues.calculateTopPadding())
                        .verticalScroll(scrollState)
                ) {
                    TextField(
                        value = filename,
                        onValueChange = { v ->
                            viewModel.onFilenameChange(v)
                        },
                        label = { 
                            Text(
                                stringResource(id = R.string.filename),
                                color = Color(0xFFB0B0B0)
                            ) 
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent
                        ),
                        isError = filenameErrorMessage != null,
                        supportingText = {
                            filenameErrorMessage?.let { Text(it) }
                        }
                    )

                    // SNI input field - only visible when security is "tls"
                    if (streamSecurity == "tls") {
                        OutlinedTextField(
                            value = sni,
                            onValueChange = { newSni ->
                                viewModel.updateSni(newSni)
                            },
                            label = { 
                                Text(
                                    "TLS SNI (Server Name Indication)",
                                    color = Color(0xFFB0B0B0)
                                ) 
                            },
                            placeholder = { 
                                Text(
                                    "Leave empty to use server address",
                                    color = Color(0xFF808080)
                                ) 
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
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

                        // Advanced TLS Settings Card
                        Card(
                            modifier = Modifier
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
                                    text = "Advanced TLS Settings",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFE0E0E0)
                                )

                                // Fingerprint Dropdown
                                var fingerprintExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = fingerprintExpanded,
                                    onExpandedChange = { fingerprintExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = fingerprint,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { 
                                            Text(
                                                "uTLS Fingerprint",
                                                color = Color(0xFFB0B0B0)
                                            ) 
                                        },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = fingerprintExpanded)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color(0xFFE0E0E0),
                                            unfocusedTextColor = Color(0xFFE0E0E0),
                                            focusedBorderColor = Color(0xFF4A9EFF),
                                            unfocusedBorderColor = Color(0xFF404040),
                                            focusedLabelColor = Color(0xFFB0B0B0),
                                            unfocusedLabelColor = Color(0xFF808080)
                                        )
                                    )
                                    DropdownMenu(
                                        expanded = fingerprintExpanded,
                                        onDismissRequest = { fingerprintExpanded = false }
                                    ) {
                                        listOf("chrome", "firefox", "safari", "ios", "android", "randomized", "360", "qq").forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option, color = Color(0xFFE0E0E0)) },
                                                onClick = {
                                                    viewModel.updateFingerprint(option)
                                                    fingerprintExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // ALPN Dropdown
                                var alpnExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = alpnExpanded,
                                    onExpandedChange = { alpnExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = alpn,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { 
                                            Text(
                                                "ALPN (Application-Layer Protocol Negotiation)",
                                                color = Color(0xFFB0B0B0)
                                            ) 
                                        },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = alpnExpanded)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color(0xFFE0E0E0),
                                            unfocusedTextColor = Color(0xFFE0E0E0),
                                            focusedBorderColor = Color(0xFF4A9EFF),
                                            unfocusedBorderColor = Color(0xFF404040),
                                            focusedLabelColor = Color(0xFFB0B0B0),
                                            unfocusedLabelColor = Color(0xFF808080)
                                        )
                                    )
                                    DropdownMenu(
                                        expanded = alpnExpanded,
                                        onDismissRequest = { alpnExpanded = false }
                                    ) {
                                        listOf("default", "h2", "http/1.1", "h2,http/1.1").forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option, color = Color(0xFFE0E0E0)) },
                                                onClick = {
                                                    viewModel.updateAlpn(option)
                                                    alpnExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Allow Insecure Switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Allow Insecure",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color(0xFFE0E0E0)
                                        )
                                        Text(
                                            text = "Skip certificate verification",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF808080)
                                        )
                                    }
                                    Switch(
                                        checked = allowInsecure,
                                        onCheckedChange = { viewModel.updateAllowInsecure(it) }
                                    )
                                }
                            }
                        }
                    }

                    // DPI Evasion (God Mode) Settings Card
                    Card(
                        modifier = Modifier
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
                                            viewModel.updateFragmentSettings(
                                                enabled = enabled,
                                                length = if (enabled) fragmentLength else "",
                                                interval = if (enabled) fragmentInterval else ""
                                            )
                                        }
                                    )
                                }

                                // Fragment Length Range
                                if (enableFragment) {
                                    OutlinedTextField(
                                        value = fragmentLength,
                                        onValueChange = { newValue ->
                                            viewModel.updateFragmentSettings(
                                                enabled = true,
                                                length = newValue,
                                                interval = fragmentInterval
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
                                            viewModel.updateFragmentSettings(
                                                enabled = true,
                                                length = fragmentLength,
                                                interval = newValue
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
                                            viewModel.updateMuxSettings(
                                                enabled = enabled,
                                                concurrency = muxConcurrency
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
                                                viewModel.updateMuxSettings(
                                                    enabled = true,
                                                    concurrency = newValue.toInt()
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

                    TextField(
                        value = configTextFieldValue,
                        onValueChange = { newTextFieldValue ->
                            val newText = newTextFieldValue.text
                            val oldText = configTextFieldValue.text
                            val cursorPosition = newTextFieldValue.selection.start

                            if (newText.length == oldText.length + 1 &&
                                cursorPosition > 0 &&
                                newText[cursorPosition - 1] == '\n'
                            ) {
                                val pair = viewModel.handleAutoIndent(newText, cursorPosition - 1)
                                viewModel.onConfigContentChange(
                                    TextFieldValue(
                                        text = pair.first,
                                        selection = TextRange(pair.second)
                                    )
                                )
                            } else {
                                viewModel.onConfigContentChange(newTextFieldValue.copy(text = newText))
                            }
                        },
                        visualTransformation = bracketMatcherTransformation(configTextFieldValue),
                        label = { 
                            Text(
                                stringResource(R.string.content),
                                color = Color(0xFFB0B0B0)
                            ) 
                        },
                        modifier = Modifier
                            .padding(bottom = if (isKeyboardOpen) 0.dp else paddingValues.calculateBottomPadding())
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.1.sp,
                            color = Color(0xFFE0E0E0)
                        ),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Text
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent
                        )
                    )
                }
            }
        )
    }
}
