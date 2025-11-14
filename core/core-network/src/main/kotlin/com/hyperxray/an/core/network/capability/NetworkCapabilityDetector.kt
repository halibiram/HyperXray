package com.hyperxray.an.core.network.capability

import android.os.Build
import java.io.BufferedReader
import java.io.FileReader

/**
 * Detects network capabilities based on device API level and CPU architecture.
 * Used to determine if advanced features like HTTP/3 and TLS acceleration are supported.
 */
object NetworkCapabilityDetector {
    /**
     * Minimum API level required for Cronet HTTP/3 support
     */
    private const val MIN_API_HTTP3 = Build.VERSION_CODES.R // API 30 (Android 11)
    
    /**
     * Minimum API level for optimal Conscrypt TLS performance
     */
    private const val MIN_API_TLS_ACCEL = Build.VERSION_CODES.P // API 28 (Android 9)
    
    /**
     * Check if HTTP/3 is supported on this device
     */
    fun supportsHttp3(): Boolean {
        return Build.VERSION.SDK_INT >= MIN_API_HTTP3
    }
    
    /**
     * Check if TLS acceleration is supported on this device
     */
    fun supportsTlsAcceleration(): Boolean {
        return Build.VERSION.SDK_INT >= MIN_API_TLS_ACCEL
    }
    
    /**
     * Check if ALPN (Application-Layer Protocol Negotiation) is supported
     */
    fun supportsAlpn(): Boolean {
        // ALPN is supported on Android 5.0+ (API 21+)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
    
    /**
     * Get CPU architecture
     */
    fun getCpuArchitecture(): CpuArchitecture {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> CpuArchitecture.ARM64
            abi.contains("arm") -> CpuArchitecture.ARM32
            abi.contains("x86_64") || abi.contains("amd64") -> CpuArchitecture.X86_64
            abi.contains("x86") -> CpuArchitecture.X86
            else -> CpuArchitecture.UNKNOWN
        }
    }
    
    /**
     * Check if device has sufficient CPU performance for Cronet
     */
    fun hasCronetCapableCpu(): Boolean {
        val arch = getCpuArchitecture()
        // Cronet works well on ARM64, ARM32, and x86_64
        return arch == CpuArchitecture.ARM64 || 
               arch == CpuArchitecture.ARM32 || 
               arch == CpuArchitecture.X86_64
    }
    
    /**
     * Check if device has ARM NEON support (useful for TLS acceleration)
     */
    fun hasNeonSupport(): Boolean {
        return try {
            val arch = getCpuArchitecture()
            if (arch == CpuArchitecture.ARM32 || arch == CpuArchitecture.ARM64) {
                // Check /proc/cpuinfo for NEON support on ARM devices
                BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
                    val cpuInfo = reader.readText()
                    cpuInfo.contains("neon", ignoreCase = true) ||
                    cpuInfo.contains("asimd", ignoreCase = true)
                }
            } else {
                false
            }
        } catch (e: Exception) {
            // If we can't read cpuinfo, assume ARM64 has NEON (modern devices)
            getCpuArchitecture() == CpuArchitecture.ARM64
        }
    }
    
    /**
     * Check if Cronet should be preferred over OkHttp
     */
    fun shouldPreferCronet(): Boolean {
        return Build.VERSION.SDK_INT >= MIN_API_TLS_ACCEL && 
               hasCronetCapableCpu()
    }
    
    /**
     * Get summary of detected capabilities
     */
    fun getCapabilitySummary(): CapabilitySummary {
        return CapabilitySummary(
            apiLevel = Build.VERSION.SDK_INT,
            cpuArchitecture = getCpuArchitecture(),
            supportsHttp3 = supportsHttp3(),
            supportsTlsAcceleration = supportsTlsAcceleration(),
            supportsAlpn = supportsAlpn(),
            hasNeonSupport = hasNeonSupport(),
            shouldPreferCronet = shouldPreferCronet()
        )
    }
}

/**
 * CPU architecture types
 */
enum class CpuArchitecture {
    ARM64,
    ARM32,
    X86_64,
    X86,
    UNKNOWN
}

/**
 * Summary of network capabilities
 */
data class CapabilitySummary(
    val apiLevel: Int,
    val cpuArchitecture: CpuArchitecture,
    val supportsHttp3: Boolean,
    val supportsTlsAcceleration: Boolean,
    val supportsAlpn: Boolean,
    val hasNeonSupport: Boolean,
    val shouldPreferCronet: Boolean
)

