package com.hyperxray.an.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.ui.screens.config.sections.AdvancedConfigSection
import com.hyperxray.an.ui.screens.config.sections.BasicConfigSection
import com.hyperxray.an.ui.screens.config.sections.ConfigContentSection
import com.hyperxray.an.ui.screens.config.sections.StreamSettingsSection
import com.hyperxray.an.ui.screens.config.sections.WireGuardSection
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

    // WARP (WireGuard) settings
    val enableWarp by viewModel.enableWarp.collectAsStateWithLifecycle()
    val warpPrivateKey by viewModel.warpPrivateKey.collectAsStateWithLifecycle()
    val warpPeerPublicKey by viewModel.warpPeerPublicKey.collectAsStateWithLifecycle()
    val warpEndpoint by viewModel.warpEndpoint.collectAsStateWithLifecycle()
    val warpLocalAddress by viewModel.warpLocalAddress.collectAsStateWithLifecycle()
    
    // WARP license binding
    val warpLicenseKey by viewModel.warpLicenseKey.collectAsStateWithLifecycle()
    val warpAccountType by viewModel.warpAccountType.collectAsStateWithLifecycle()
    val warpQuota by viewModel.warpQuota.collectAsStateWithLifecycle()
    val isBindingLicense by viewModel.isBindingLicense.collectAsStateWithLifecycle()

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
                    // Basic Configuration Section
                    BasicConfigSection(
                        filename = filename,
                        filenameErrorMessage = filenameErrorMessage,
                        onFilenameChange = { viewModel.onFilenameChange(it) }
                    )

                    // Stream Settings Section (TLS/Reality)
                    StreamSettingsSection(
                        streamSecurity = streamSecurity,
                        sni = sni,
                        fingerprint = fingerprint,
                        alpn = alpn,
                        allowInsecure = allowInsecure,
                        onSniChange = { viewModel.updateSni(it) },
                        onFingerprintChange = { viewModel.updateFingerprint(it) },
                        onAlpnChange = { viewModel.updateAlpn(it) },
                        onAllowInsecureChange = { viewModel.updateAllowInsecure(it) }
                    )

                    // Advanced Configuration Section (DPI Evasion)
                    AdvancedConfigSection(
                        enableFragment = enableFragment,
                        fragmentLength = fragmentLength,
                        fragmentInterval = fragmentInterval,
                        enableMux = enableMux,
                        muxConcurrency = muxConcurrency,
                        onFragmentSettingsChange = { enabled, length, interval ->
                            viewModel.updateFragmentSettings(
                                enabled = enabled,
                                length = length,
                                interval = interval
                            )
                        },
                        onMuxSettingsChange = { enabled, concurrency ->
                            viewModel.updateMuxSettings(
                                enabled = enabled,
                                concurrency = concurrency
                            )
                        }
                    )

                    // WireGuard over Xray Section
                    WireGuardSection(
                        enableWarp = enableWarp,
                        warpPrivateKey = warpPrivateKey,
                        warpPeerPublicKey = warpPeerPublicKey,
                        warpEndpoint = warpEndpoint,
                        warpLocalAddress = warpLocalAddress,
                        warpLicenseKey = warpLicenseKey,
                        warpAccountType = warpAccountType,
                        warpQuota = warpQuota,
                        isBindingLicense = isBindingLicense,
                        onWarpSettingsChange = { enabled, privateKey, endpoint, localAddress ->
                            viewModel.updateWarpSettings(
                                enabled = enabled,
                                privateKey = privateKey,
                                endpoint = endpoint,
                                localAddress = localAddress
                            )
                        },
                        onLicenseKeyInputChange = { viewModel.updateLicenseKeyInput(it) },
                        onBindLicenseKey = { viewModel.bindLicenseKey() },
                        onGenerateWarpIdentity = { viewModel.generateWarpIdentity() },
                        onCreateFreeIdentity = { viewModel.createFreeIdentity() },
                        onSetWarpPrivateKey = { viewModel.setWarpPrivateKey(it) }
                    )

                    // Config Content Section (Raw JSON Editor)
                    ConfigContentSection(
                        configTextFieldValue = configTextFieldValue,
                        onConfigContentChange = { viewModel.onConfigContentChange(it) },
                        onAutoIndent = { text, position -> viewModel.handleAutoIndent(text, position) }
                    )
                }
            }
        )
    }
}
