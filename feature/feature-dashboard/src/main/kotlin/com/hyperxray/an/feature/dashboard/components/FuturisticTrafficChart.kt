package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.formatThroughput
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

// 2030 Holographic Color Palette
private object HoloColors {
    // Primary plasma colors
    val PlasmaCyan = Color(0xFF00F5FF)
    val PlasmaViolet = Color(0xFFBF00FF)
    val PlasmaRose = Color(0xFFFF0099)
    val PlasmaGold = Color(0xFFFFD700)
    
    // Electric accents
    val ElectricBlue = Color(0xFF00BFFF)
    val ElectricGreen = Color(0xFF00FF88)
    val ElectricOrange = Color(0xFFFF7700)
    
    // Holographic gradients
    val HoloShift1 = Color(0xFF00FFFF)
    val HoloShift2 = Color(0xFFFF00FF)
    val HoloShift3 = Color(0xFFFFFF00)
    
    // Deep space background
    val VoidBlack = Color(0xFF000010)
    val DeepSpace = Color(0xFF050520)
    val NebulaPurple = Color(0xFF1A0030)
}

// Quantum particle for advanced effects
private data class QuantumParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var energy: Float,
    var phase: Float,
    var size: Float,
    var hue: Float
)

// Energy wave for plasma effect
private data class EnergyWave(
    var position: Float,
    var amplitude: Float,
    var frequency: Float,
    var color: Color
)

@Composable
fun FuturisticTrafficChart(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    modifier: Modifier = Modifier
) {
    val maxPoints = 80
    val updateInterval = 50L
    val maxThroughputBase = 1024 * 1024.0

    var uplinkHistory by remember { mutableStateOf(List(maxPoints) { 0f }) }
    var downlinkHistory by remember { mutableStateOf(List(maxPoints) { 0f }) }
    var currentMax by remember { mutableStateOf(maxThroughputBase) }
    var particles by remember { mutableStateOf(listOf<QuantumParticle>()) }
    var energyWaves by remember { mutableStateOf(listOf<EnergyWave>()) }

    val currentUplink = rememberUpdatedState(uplinkThroughput)
    val currentDownlink = rememberUpdatedState(downlinkThroughput)

    val infiniteTransition = rememberInfiniteTransition(label = "holoChart")
    
    // Holographic color shift
    val holoPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "holoPhase"
    )
    
    // Plasma pulse
    val plasmaPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "plasma"
    )
    
    // Energy field oscillation
    val energyField by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "energy"
    )
    
    // Quantum flicker
    val quantumFlicker by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flicker"
    )
    
    // Scanner beam
    val scannerPos by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanner"
    )
    
    // Data stream flow
    val dataFlow by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dataFlow"
    )

    LaunchedEffect(Unit) {
        while (true) {
            val up = currentUplink.value.toFloat()
            val down = currentDownlink.value.toFloat()
            
            uplinkHistory = (uplinkHistory + up).takeLast(maxPoints)
            downlinkHistory = (downlinkHistory + down).takeLast(maxPoints)

            val maxInWindow = (uplinkHistory + downlinkHistory).maxOrNull()?.toDouble() ?: maxThroughputBase
            val targetMax = maxOf(maxThroughputBase, maxInWindow * 1.2)
            currentMax = currentMax * 0.95 + targetMax * 0.05

            // Generate quantum particles
            val totalThroughput = up + down
            if (totalThroughput > 500 || Random.nextFloat() < 0.1f) {
                val intensity = (totalThroughput / 1000000f).coerceIn(0.1f, 1f)
                val newParticles = (1..Random.nextInt(2, 6)).map {
                    QuantumParticle(
                        x = Random.nextFloat(),
                        y = 1f + Random.nextFloat() * 0.1f,
                        vx = (Random.nextFloat() - 0.5f) * 0.015f,
                        vy = -Random.nextFloat() * 0.025f - 0.01f,
                        energy = Random.nextFloat() * 0.5f + 0.5f,
                        phase = Random.nextFloat() * 360f,
                        size = Random.nextFloat() * 6f + 2f,
                        hue = if (Random.nextBoolean()) 180f else 300f // Cyan or Magenta
                    )
                }
                particles = (particles + newParticles).takeLast(80)
            }
            
            // Update particles with quantum behavior
            particles = particles.mapNotNull { p ->
                p.x += p.vx + sin(p.phase * 0.1f) * 0.002f
                p.y += p.vy
                p.phase += 5f
                p.energy -= 0.015f
                p.size *= 0.995f
                if (p.energy > 0 && p.y > -0.1f) p else null
            }

            // Generate energy waves on high throughput
            if (totalThroughput > 100000 && Random.nextFloat() < 0.3f) {
                energyWaves = (energyWaves + EnergyWave(
                    position = 1f,
                    amplitude = (totalThroughput / 5000000f).coerceIn(0.05f, 0.3f),
                    frequency = Random.nextFloat() * 2f + 1f,
                    color = if (Random.nextBoolean()) HoloColors.PlasmaCyan else HoloColors.PlasmaRose
                )).takeLast(5)
            }
            
            // Update energy waves
            energyWaves = energyWaves.mapNotNull { w ->
                w.position -= 0.03f
                w.amplitude *= 0.97f
                if (w.position > -0.2f && w.amplitude > 0.01f) w else null
            }

            delay(updateInterval)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        HoloColors.DeepSpace,
                        HoloColors.VoidBlack,
                        Color(0xFF000008)
                    )
                )
            )
            .drawBehind {
                // Holographic outer glow
                val glowColors = listOf(
                    HoloColors.PlasmaCyan.copy(alpha = 0.15f * plasmaPulse),
                    HoloColors.PlasmaViolet.copy(alpha = 0.1f),
                    HoloColors.PlasmaRose.copy(alpha = 0.15f * (1f - plasmaPulse)),
                    Color.Transparent
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = glowColors,
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.maxDimension * 0.8f
                    )
                )
            }
            .border(
                width = 2.dp,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        HoloColors.PlasmaCyan.copy(alpha = quantumFlicker),
                        HoloColors.PlasmaViolet.copy(alpha = 0.8f),
                        HoloColors.PlasmaRose.copy(alpha = quantumFlicker),
                        HoloColors.PlasmaGold.copy(alpha = 0.6f),
                        HoloColors.PlasmaCyan.copy(alpha = quantumFlicker)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Holographic Header
            HoloHeader(
                uplinkThroughput = uplinkThroughput,
                downlinkThroughput = downlinkThroughput,
                holoPhase = holoPhase,
                plasmaPulse = plasmaPulse
            )

            // Main Holographic Chart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF000015))
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                HoloColors.PlasmaCyan.copy(alpha = 0.5f),
                                HoloColors.PlasmaViolet.copy(alpha = 0.3f),
                                HoloColors.PlasmaRose.copy(alpha = 0.5f)
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val width = size.width
                    val height = size.height
                    
                    // Draw holographic grid
                    drawHoloGrid(width, height, energyField, quantumFlicker, dataFlow)
                    
                    // Draw scanner beam
                    drawScannerBeam(width, height, scannerPos, plasmaPulse)
                    
                    // Draw energy waves
                    energyWaves.forEach { wave ->
                        drawEnergyWave(width, height, wave)
                    }
                    
                    // Draw plasma traffic visualization
                    drawPlasmaTraffic(
                        uplinkHistory = uplinkHistory,
                        downlinkHistory = downlinkHistory,
                        currentMax = currentMax,
                        width = width,
                        height = height,
                        plasmaPulse = plasmaPulse,
                        energyField = energyField,
                        holoPhase = holoPhase,
                        maxPoints = maxPoints
                    )
                    
                    // Draw quantum particles
                    particles.forEach { p ->
                        drawQuantumParticle(p, width, height)
                    }
                    
                    // Draw holographic frame
                    drawHoloFrame(width, height, quantumFlicker, holoPhase)
                }
            }
            
            // Holographic Stats
            HoloStatsBar(
                uplinkHistory = uplinkHistory,
                downlinkHistory = downlinkHistory,
                plasmaPulse = plasmaPulse,
                holoPhase = holoPhase
            )
        }
    }
}


@Composable
private fun HoloHeader(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    holoPhase: Float,
    plasmaPulse: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title with holographic effect
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Animated quantum core
            Box(modifier = Modifier.size(36.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2 - 2
                    
                    // Outer plasma ring
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                HoloColors.PlasmaCyan,
                                HoloColors.PlasmaViolet,
                                HoloColors.PlasmaRose,
                                HoloColors.PlasmaCyan
                            ),
                            center = center
                        ),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3f)
                    )
                    
                    // Inner energy core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                HoloColors.PlasmaCyan.copy(alpha = plasmaPulse),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius * 0.6f
                        ),
                        radius = radius * 0.5f,
                        center = center
                    )
                    
                    // Orbiting particles
                    for (i in 0..2) {
                        val angle = Math.toRadians((holoPhase + i * 120).toDouble())
                        val orbitRadius = radius * 0.7f
                        val px = center.x + orbitRadius * cos(angle).toFloat()
                        val py = center.y + orbitRadius * sin(angle).toFloat()
                        drawCircle(
                            color = when (i) {
                                0 -> HoloColors.PlasmaCyan
                                1 -> HoloColors.PlasmaViolet
                                else -> HoloColors.PlasmaRose
                            },
                            radius = 3f,
                            center = Offset(px, py)
                        )
                    }
                }
            }
            
            Column {
                Text(
                    text = "QUANTUM FLUX",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
                Text(
                    text = "NEURAL•STREAM•v3.0",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    ),
                    color = HoloColors.PlasmaViolet.copy(alpha = 0.8f)
                )
            }
        }
        
        // Speed displays
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HoloSpeedDisplay("↑", uplinkThroughput, HoloColors.PlasmaCyan, plasmaPulse)
            HoloSpeedDisplay("↓", downlinkThroughput, HoloColors.PlasmaRose, plasmaPulse)
        }
    }
}

@Composable
private fun HoloSpeedDisplay(
    icon: String,
    value: Double,
    color: Color,
    pulse: Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = color
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.5f + pulse * 0.3f), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = formatThroughput(value),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = color
            )
        }
    }
}

@Composable
private fun HoloStatsBar(
    uplinkHistory: List<Float>,
    downlinkHistory: List<Float>,
    plasmaPulse: Float,
    holoPhase: Float
) {
    val avgUp = uplinkHistory.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
    val avgDown = downlinkHistory.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
    val peakUp = uplinkHistory.maxOrNull()?.toDouble() ?: 0.0
    val peakDown = downlinkHistory.maxOrNull()?.toDouble() ?: 0.0
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0A20))
            .border(
                1.dp,
                Brush.horizontalGradient(
                    colors = listOf(
                        HoloColors.PlasmaCyan.copy(alpha = 0.4f),
                        HoloColors.PlasmaViolet.copy(alpha = 0.3f),
                        HoloColors.PlasmaRose.copy(alpha = 0.4f)
                    )
                ),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        HoloStatItem("AVG↑", formatThroughput(avgUp), HoloColors.PlasmaCyan)
        HoloStatItem("AVG↓", formatThroughput(avgDown), HoloColors.PlasmaRose)
        HoloStatItem("MAX↑", formatThroughput(peakUp), HoloColors.ElectricGreen)
        HoloStatItem("MAX↓", formatThroughput(peakDown), HoloColors.PlasmaViolet)
    }
}

@Composable
private fun HoloStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            ),
            color = color.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = color
        )
    }
}


// ============ Drawing Functions ============

private fun DrawScope.drawHoloGrid(
    width: Float,
    height: Float,
    energyField: Float,
    flicker: Float,
    dataFlow: Float
) {
    val gridX = 16
    val gridY = 10
    val cellW = width / gridX
    val cellH = height / gridY
    
    // Perspective grid with energy flow
    for (i in 0..gridY) {
        val y = cellH * i
        val waveOffset = sin(energyField + i * 0.3f) * 3f
        val alpha = 0.15f + sin(energyField * 2 + i * 0.5f) * 0.05f
        
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    HoloColors.PlasmaCyan.copy(alpha = alpha * 0.5f),
                    HoloColors.PlasmaViolet.copy(alpha = alpha * flicker),
                    HoloColors.PlasmaRose.copy(alpha = alpha * 0.5f)
                )
            ),
            start = Offset(0f, y + waveOffset),
            end = Offset(width, y + waveOffset),
            strokeWidth = 1f
        )
    }
    
    // Vertical lines with data flow effect
    for (i in 0..gridX) {
        val x = cellW * i
        val flowOffset = ((dataFlow + i * 10) % 100) / 100f
        
        // Data stream particles on vertical lines
        if (i % 2 == 0) {
            val particleY = height * flowOffset
            drawCircle(
                color = HoloColors.PlasmaCyan.copy(alpha = 0.4f * flicker),
                radius = 2f,
                center = Offset(x, particleY)
            )
        }
        
        drawLine(
            color = HoloColors.PlasmaViolet.copy(alpha = 0.1f + abs(gridX / 2 - i) * 0.01f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        )
    }
    
    // Central energy axis
    val centerY = height / 2
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                HoloColors.PlasmaViolet.copy(alpha = 0.3f * flicker),
                HoloColors.PlasmaCyan.copy(alpha = 0.4f * flicker),
                HoloColors.PlasmaViolet.copy(alpha = 0.3f * flicker),
                Color.Transparent
            )
        ),
        start = Offset(0f, centerY),
        end = Offset(width, centerY),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawScannerBeam(width: Float, height: Float, pos: Float, pulse: Float) {
    val x = width * pos
    val beamWidth = 60f
    
    // Scanner beam gradient
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                HoloColors.PlasmaCyan.copy(alpha = 0.1f * pulse),
                HoloColors.PlasmaCyan.copy(alpha = 0.25f * pulse),
                HoloColors.PlasmaCyan.copy(alpha = 0.1f * pulse),
                Color.Transparent
            ),
            startX = x - beamWidth,
            endX = x + beamWidth
        ),
        topLeft = Offset(x - beamWidth, 0f),
        size = Size(beamWidth * 2, height)
    )
    
    // Scanner line
    drawLine(
        brush = Brush.verticalGradient(
            colors = listOf(
                HoloColors.PlasmaCyan.copy(alpha = 0.8f),
                HoloColors.ElectricBlue.copy(alpha = 0.6f),
                HoloColors.PlasmaCyan.copy(alpha = 0.8f)
            )
        ),
        start = Offset(x, 0f),
        end = Offset(x, height),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawEnergyWave(width: Float, height: Float, wave: EnergyWave) {
    val path = Path()
    val startX = width * wave.position
    
    for (i in 0..50) {
        val progress = i / 50f
        val x = startX + progress * width * 0.3f
        val y = height / 2 + sin(progress * wave.frequency * PI.toFloat() * 4) * height * wave.amplitude
        
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    
    drawPath(
        path = path,
        color = wave.color.copy(alpha = wave.amplitude * 2f),
        style = Stroke(width = 3f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawQuantumParticle(p: QuantumParticle, width: Float, height: Float) {
    val x = p.x * width
    val y = p.y * height
    val color = Color.hsv(p.hue, 0.8f, 1f)
    
    // Quantum blur effect
    drawCircle(
        color = color.copy(alpha = p.energy * 0.2f),
        radius = p.size * 4f,
        center = Offset(x, y)
    )
    
    // Core particle
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = p.energy),
                color.copy(alpha = p.energy * 0.8f),
                Color.Transparent
            ),
            center = Offset(x, y),
            radius = p.size * 2f
        ),
        radius = p.size * 2f,
        center = Offset(x, y)
    )
    
    // Bright center
    drawCircle(
        color = Color.White.copy(alpha = p.energy * 0.9f),
        radius = p.size * 0.5f,
        center = Offset(x, y)
    )
}

private fun DrawScope.drawPlasmaTraffic(
    uplinkHistory: List<Float>,
    downlinkHistory: List<Float>,
    currentMax: Double,
    width: Float,
    height: Float,
    plasmaPulse: Float,
    energyField: Float,
    holoPhase: Float,
    maxPoints: Int
) {
    val stepX = width / (maxPoints - 1)
    val chartHeight = height * 0.8f
    val bottomPadding = height * 0.1f
    
    fun createPlasmaPath(data: List<Float>): Path {
        val path = Path()
        if (data.isEmpty()) return path
        
        val points = data.mapIndexed { index, value ->
            val x = index * stepX
            val normalized = (value / currentMax.toFloat()).coerceIn(0f, 1f)
            // Add subtle wave distortion for plasma effect
            val waveDistort = sin(energyField + index * 0.1f) * 2f * normalized
            val y = height - bottomPadding - (normalized * chartHeight) + waveDistort
            Offset(x, y)
        }
        
        if (points.isNotEmpty()) {
            path.moveTo(points.first().x, points.first().y)
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val cp1x = p0.x + (p1.x - p0.x) * 0.4f
                val cp2x = p1.x - (p1.x - p0.x) * 0.4f
                path.cubicTo(cp1x, p0.y, cp2x, p1.y, p1.x, p1.y)
            }
        }
        return path
    }
    
    // Download plasma stream
    val downPath = createPlasmaPath(downlinkHistory)
    if (downlinkHistory.isNotEmpty()) {
        // Plasma fill
        val fillPath = Path().apply {
            addPath(downPath)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    HoloColors.PlasmaRose.copy(alpha = 0.5f * plasmaPulse),
                    HoloColors.PlasmaViolet.copy(alpha = 0.25f),
                    HoloColors.PlasmaRose.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        )
        
        // Outer glow
        drawPath(
            path = downPath,
            color = HoloColors.PlasmaRose.copy(alpha = 0.3f),
            style = Stroke(width = 16f, cap = StrokeCap.Round)
        )
        
        // Main plasma line
        drawPath(
            path = downPath,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    HoloColors.PlasmaRose.copy(alpha = 0.6f),
                    HoloColors.PlasmaViolet,
                    HoloColors.PlasmaRose
                )
            ),
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
        
        // Inner bright core
        drawPath(
            path = downPath,
            color = Color.White.copy(alpha = 0.4f * plasmaPulse),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round)
        )
    }
    
    // Upload plasma stream
    val upPath = createPlasmaPath(uplinkHistory)
    if (uplinkHistory.isNotEmpty()) {
        // Plasma fill
        val fillPath = Path().apply {
            addPath(upPath)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    HoloColors.PlasmaCyan.copy(alpha = 0.45f * plasmaPulse),
                    HoloColors.ElectricBlue.copy(alpha = 0.2f),
                    HoloColors.PlasmaCyan.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        )
        
        // Outer glow
        drawPath(
            path = upPath,
            color = HoloColors.PlasmaCyan.copy(alpha = 0.35f),
            style = Stroke(width = 16f, cap = StrokeCap.Round)
        )
        
        // Main plasma line
        drawPath(
            path = upPath,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    HoloColors.PlasmaCyan.copy(alpha = 0.6f),
                    HoloColors.ElectricBlue,
                    HoloColors.PlasmaCyan
                )
            ),
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
        
        // Inner bright core
        drawPath(
            path = upPath,
            color = Color.White.copy(alpha = 0.5f * plasmaPulse),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round)
        )
    }
    
    // Endpoint indicators with holographic effect
    if (uplinkHistory.isNotEmpty()) {
        val lastVal = (uplinkHistory.last() / currentMax.toFloat()).coerceIn(0f, 1f)
        val lastY = height - bottomPadding - (lastVal * chartHeight)
        drawHoloEndpoint(width, lastY, HoloColors.PlasmaCyan, plasmaPulse, holoPhase)
    }
    
    if (downlinkHistory.isNotEmpty()) {
        val lastVal = (downlinkHistory.last() / currentMax.toFloat()).coerceIn(0f, 1f)
        val lastY = height - bottomPadding - (lastVal * chartHeight)
        drawHoloEndpoint(width, lastY, HoloColors.PlasmaRose, plasmaPulse, holoPhase + 180f)
    }
}

private fun DrawScope.drawHoloEndpoint(x: Float, y: Float, color: Color, pulse: Float, phase: Float) {
    // Outer pulse ring
    drawCircle(
        color = color.copy(alpha = 0.2f * pulse),
        radius = 25f * pulse,
        center = Offset(x, y)
    )
    
    // Rotating ring
    val ringPath = Path()
    for (i in 0..3) {
        val angle = Math.toRadians((phase + i * 90).toDouble())
        val arcStart = (phase + i * 90) % 360
        drawArc(
            color = color.copy(alpha = 0.6f),
            startAngle = arcStart,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = Offset(x - 12f, y - 12f),
            size = Size(24f, 24f),
            style = Stroke(width = 2f, cap = StrokeCap.Round)
        )
    }
    
    // Middle glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White,
                color,
                color.copy(alpha = 0.3f),
                Color.Transparent
            ),
            center = Offset(x, y),
            radius = 10f
        ),
        radius = 10f,
        center = Offset(x, y)
    )
    
    // Bright core
    drawCircle(
        color = Color.White,
        radius = 4f,
        center = Offset(x, y)
    )
}

private fun DrawScope.drawHoloFrame(width: Float, height: Float, flicker: Float, phase: Float) {
    val cornerLen = 25f
    val strokeW = 2f
    
    // Animated corner brackets with color shift
    val colors = listOf(
        HoloColors.PlasmaCyan,
        HoloColors.PlasmaViolet,
        HoloColors.PlasmaRose,
        HoloColors.ElectricGreen
    )
    
    // Top-left
    drawLine(colors[0].copy(alpha = flicker), Offset(0f, cornerLen), Offset(0f, 0f), strokeW)
    drawLine(colors[0].copy(alpha = flicker), Offset(0f, 0f), Offset(cornerLen, 0f), strokeW)
    
    // Top-right
    drawLine(colors[1].copy(alpha = flicker), Offset(width - cornerLen, 0f), Offset(width, 0f), strokeW)
    drawLine(colors[1].copy(alpha = flicker), Offset(width, 0f), Offset(width, cornerLen), strokeW)
    
    // Bottom-left
    drawLine(colors[2].copy(alpha = flicker * 0.8f), Offset(0f, height - cornerLen), Offset(0f, height), strokeW)
    drawLine(colors[2].copy(alpha = flicker * 0.8f), Offset(0f, height), Offset(cornerLen, height), strokeW)
    
    // Bottom-right
    drawLine(colors[3].copy(alpha = flicker * 0.8f), Offset(width - cornerLen, height), Offset(width, height), strokeW)
    drawLine(colors[3].copy(alpha = flicker * 0.8f), Offset(width, height - cornerLen), Offset(width, height), strokeW)
    
    // Corner dots with glow
    listOf(
        Offset(0f, 0f) to colors[0],
        Offset(width, 0f) to colors[1],
        Offset(0f, height) to colors[2],
        Offset(width, height) to colors[3]
    ).forEach { (pos, color) ->
        drawCircle(color.copy(alpha = 0.3f), 6f, pos)
        drawCircle(color, 3f, pos)
    }
}
