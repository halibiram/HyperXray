package com.hyperxray.an.feature.warp.presentation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.feature.warp.domain.entity.WarpConfigType
import com.hyperxray.an.feature.warp.presentation.viewmodel.WarpViewModel

// Futurist neon colors
private val NeonCyan = Color(0xFF00E5FF)
private val NeonMagenta = Color(0xFFFF00E5)
private val NeonPurple = Color(0xFF7C4DFF)
private val NeonGreen = Color(0xFF00FF94)
private val NeonOrange = Color(0xFFFF6B35)
private val DarkSurface = Color(0xFF0A0A0F)
private val GlassSurface = Color(0xFF12121A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpScreen(
    viewModel: WarpViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Animated gradient background
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradient"
    )
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkSurface)
    ) {
        // Animated background orbs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(NeonCyan.copy(alpha = 0.15f), Color.Transparent),
                            center = Offset(size.width * gradientOffset, size.height * 0.2f),
                            radius = size.width * 0.6f
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(NeonMagenta.copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(size.width * (1f - gradientOffset), size.height * 0.7f),
                            radius = size.width * 0.5f
                        )
                    )
                }
        )
        
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                FuturistTopBar()
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Error message
                uiState.error?.let { error ->
                    ErrorCard(error)
                }
                
                if (uiState.account == null) {
                    RegisterAccountSection(
                        onRegister = { licenseKey ->
                            viewModel.registerAccount(licenseKey.ifEmpty { null })
                        },
                        isLoading = uiState.isLoading
                    )
                } else {
                    uiState.account?.let { account ->
                        AccountInfoSection(
                            account = account,
                            onUpdateLicense = { licenseKey ->
                                viewModel.updateLicense(licenseKey)
                            },
                            isLoading = uiState.isLoading
                        )
                        
                        ConfigGenerationSection(
                            onGenerateConfig = { configType, endpoint ->
                                viewModel.generateConfig(configType, endpoint.ifEmpty { null })
                            },
                            generatedConfig = uiState.generatedConfig,
                            configType = uiState.configType,
                            isLoading = uiState.isLoading,
                            onClearConfig = { viewModel.clearGeneratedConfig() }
                        )
                        
                        DevicesSection(
                            devices = uiState.devices,
                            onLoadDevices = { viewModel.loadDevices() },
                            onRemoveDevice = { deviceId ->
                                viewModel.removeDevice(deviceId)
                            },
                            isLoading = uiState.isLoading
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuturistTopBar() {
    val infiniteTransition = rememberInfiniteTransition(label = "title")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Animated WARP icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(NeonCyan, NeonPurple)
                            ),
                            shape = CircleShape
                        )
                        .graphicsLayer { alpha = glowAlpha },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Column {
                    Text(
                        text = "WARP",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "CLOUDFLARE ZERO TRUST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 2.sp
                        ),
                        color = NeonCyan.copy(alpha = 0.8f)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    glowColor: Color = NeonCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.5f),
                        glowColor.copy(alpha = 0.1f),
                        glowColor.copy(alpha = 0.3f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = GlassSurface.copy(alpha = 0.8f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@Composable
private fun ErrorCard(error: String) {
    GlassCard(glowColor = Color(0xFFFF5252)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = error,
                color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


@Composable
private fun SectionHeader(
    title: String,
    icon: @Composable () -> Unit,
    accentColor: Color = NeonCyan
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            ),
            color = Color.White
        )
    }
}

@Composable
private fun NeonButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    colors: List<Color> = listOf(NeonCyan, NeonPurple)
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        label = "alpha"
    )
    
    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .graphicsLayer { alpha = animatedAlpha },
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(colors),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = text,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun NeonOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    accentColor: Color = NeonCyan
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { 
            Text(
                text = label,
                color = accentColor.copy(alpha = 0.8f)
            ) 
        },
        placeholder = placeholder?.let { 
            { Text(it, color = Color.White.copy(alpha = 0.3f)) } 
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White.copy(alpha = 0.8f),
            focusedBorderColor = accentColor,
            unfocusedBorderColor = accentColor.copy(alpha = 0.3f),
            focusedContainerColor = Color.White.copy(alpha = 0.05f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.02f),
            cursorColor = accentColor
        )
    )
}

@Composable
private fun RegisterAccountSection(
    onRegister: (String) -> Unit,
    isLoading: Boolean
) {
    var licenseKey by remember { mutableStateOf("") }
    
    GlassCard(glowColor = NeonGreen) {
        SectionHeader(
            title = "REGISTER ACCOUNT",
            icon = {
                Icon(
                    imageVector = Icons.Rounded.PersonAdd,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier.size(20.dp)
                )
            },
            accentColor = NeonGreen
        )
        
        Text(
            text = "Create a new WARP account to access Cloudflare's global network. Optionally provide a license key for WARP+ premium features.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            lineHeight = 22.sp
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        NeonOutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it },
            label = "License Key (Optional)",
            enabled = !isLoading,
            accentColor = NeonGreen
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        NeonButton(
            onClick = { onRegister(licenseKey) },
            text = "REGISTER",
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            isLoading = isLoading,
            colors = listOf(NeonGreen, Color(0xFF00C853))
        )
    }
}

@Composable
private fun AccountInfoSection(
    account: com.hyperxray.an.feature.warp.domain.entity.WarpAccount,
    onUpdateLicense: (String) -> Unit,
    isLoading: Boolean
) {
    var licenseKey by remember { mutableStateOf("") }
    
    GlassCard(glowColor = NeonCyan) {
        SectionHeader(
            title = "ACCOUNT STATUS",
            icon = {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        
        // Status indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = NeonGreen.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(NeonGreen, CircleShape)
            )
            Text(
                text = "CONNECTED",
                color = NeonGreen,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Info grid
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow("Account ID", account.accountId, Icons.Rounded.Fingerprint)
            InfoRow("Type", account.account.accountType?.uppercase() ?: "FREE", Icons.Rounded.Category)
            InfoRow(
                "WARP+", 
                if (account.account.warpPlus) "ACTIVE" else "INACTIVE",
                Icons.Rounded.Bolt,
                if (account.account.warpPlus) NeonGreen else NeonOrange
            )
            InfoRow("IPv4", account.config.interfaceData?.addresses?.v4 ?: "N/A", Icons.Rounded.Language)
            InfoRow("IPv6", account.config.interfaceData?.addresses?.v6 ?: "N/A", Icons.Rounded.Public)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Divider with glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            NeonCyan.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "UPDATE LICENSE",
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = 2.sp
            ),
            color = NeonMagenta
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        NeonOutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it },
            label = "License Key",
            enabled = !isLoading,
            accentColor = NeonMagenta
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        NeonButton(
            onClick = { onUpdateLicense(licenseKey) },
            text = "UPDATE",
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && licenseKey.isNotEmpty(),
            isLoading = isLoading,
            colors = listOf(NeonMagenta, NeonPurple)
        )
    }
}

@Composable
private fun InfoRow(
    label: String, 
    value: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = 0.03f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NeonCyan.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            ),
            color = valueColor
        )
    }
}


@Composable
private fun ConfigGenerationSection(
    onGenerateConfig: (WarpConfigType, String) -> Unit,
    generatedConfig: String?,
    configType: WarpConfigType?,
    isLoading: Boolean,
    onClearConfig: () -> Unit
) {
    var endpoint by remember { mutableStateOf("") }
    
    GlassCard(glowColor = NeonPurple) {
        SectionHeader(
            title = "GENERATE CONFIG",
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Code,
                    contentDescription = null,
                    tint = NeonPurple,
                    modifier = Modifier.size(20.dp)
                )
            },
            accentColor = NeonPurple
        )
        
        NeonOutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = "Endpoint (Optional)",
            placeholder = "engage.cloudflareclient.com:2408",
            enabled = !isLoading,
            accentColor = NeonPurple
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Config type buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ConfigTypeButton(
                text = "WireGuard",
                onClick = { onGenerateConfig(WarpConfigType.WIREGUARD, endpoint) },
                enabled = !isLoading,
                colors = listOf(Color(0xFF00D9FF), Color(0xFF00A3CC)),
                modifier = Modifier.weight(1f)
            )
            ConfigTypeButton(
                text = "Xray",
                onClick = { onGenerateConfig(WarpConfigType.XRAY, endpoint) },
                enabled = !isLoading,
                colors = listOf(NeonMagenta, Color(0xFFCC00B8)),
                modifier = Modifier.weight(1f)
            )
            ConfigTypeButton(
                text = "sing-box",
                onClick = { onGenerateConfig(WarpConfigType.SINGBOX, endpoint) },
                enabled = !isLoading,
                colors = listOf(NeonOrange, Color(0xFFCC4400)),
                modifier = Modifier.weight(1f)
            )
        }
        
        generatedConfig?.let { config ->
            Spacer(modifier = Modifier.height(24.dp))
            
            // Config output
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${configType?.name} CONFIG",
                        style = MaterialTheme.typography.labelLarge.copy(
                            letterSpacing = 2.sp
                        ),
                        color = NeonCyan
                    )
                    IconButton(
                        onClick = onClearConfig,
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                color = Color(0xFFFF5252).copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = NeonCyan.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = config,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonGreen.copy(alpha = 0.9f),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigTypeButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(colors),
                    shape = RoundedCornerShape(12.dp),
                    alpha = if (enabled) 1f else 0.4f
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun DevicesSection(
    devices: List<com.hyperxray.an.feature.warp.domain.entity.WarpDevice>,
    onLoadDevices: () -> Unit,
    onRemoveDevice: (String) -> Unit,
    isLoading: Boolean
) {
    GlassCard(glowColor = NeonOrange) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                title = "DEVICES (${devices.size})",
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Devices,
                        contentDescription = null,
                        tint = NeonOrange,
                        modifier = Modifier.size(20.dp)
                    )
                },
                accentColor = NeonOrange
            )
            
            IconButton(
                onClick = onLoadDevices,
                enabled = !isLoading,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = NeonOrange.copy(alpha = 0.15f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = NeonOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.White.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DevicesOther,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No devices found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Tap refresh to load devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                devices.forEach { device ->
                    DeviceItem(
                        device = device,
                        onRemove = { onRemoveDevice(device.id) },
                        isLoading = isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: com.hyperxray.an.feature.warp.domain.entity.WarpDevice,
    onRemove: () -> Unit,
    isLoading: Boolean
) {
    val isActive = device.active
    val borderColor = if (isActive) NeonGreen else Color.White.copy(alpha = 0.1f)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                color = if (isActive) NeonGreen.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.02f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Device icon with status
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isActive) NeonGreen.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (device.type?.lowercase()) {
                        "android" -> Icons.Rounded.PhoneAndroid
                        "ios" -> Icons.Rounded.PhoneIphone
                        "windows", "macos", "linux" -> Icons.Rounded.Computer
                        else -> Icons.Rounded.Devices
                    },
                    contentDescription = null,
                    tint = if (isActive) NeonGreen else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Column {
                Text(
                    text = device.name ?: device.id.take(8),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
                Text(
                    text = "${device.type ?: "Unknown"} • ${device.model ?: "N/A"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Role badge
                    Box(
                        modifier = Modifier
                            .background(
                                color = NeonPurple.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = device.role?.uppercase() ?: "N/A",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonPurple,
                            fontSize = 10.sp
                        )
                    }
                    // Status badge
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isActive) NeonGreen.copy(alpha = 0.2f) else NeonOrange.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isActive) "ACTIVE" else "INACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) NeonGreen else NeonOrange,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        
        if (device.role == "child") {
            IconButton(
                onClick = onRemove,
                enabled = !isLoading,
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = Color(0xFFFF5252).copy(alpha = 0.15f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Remove",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
