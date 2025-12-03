package com.hyperxray.an.vpn

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import com.hyperxray.an.common.AiLogHelper
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.NetworkInterface

/**
 * 🛡️ Conflicting VPN Detector
 * 
 * Detects and handles other VPN applications that may be using the TUN interface.
 * When another VPN app is active, it can prevent HyperXray from establishing
 * a connection. This class provides methods to:
 * 
 * 1. Detect if another VPN is currently active
 * 2. Identify which app is using the VPN
 * 3. Force stop the conflicting app (requires user confirmation)
 * 
 * Note: Force stop requires either:
 * - KILL_BACKGROUND_PROCESSES permission (limited effectiveness)
 * - Device admin privileges
 * - Root access
 * - Or user manually stopping the app via Settings
 */
object ConflictingVpnDetector {
    private const val TAG = "ConflictingVpnDetector"
    
    // Known VPN package names (common VPN apps)
    private val KNOWN_VPN_PACKAGES = setOf(
        // Popular VPN apps
        "com.v2ray.ang",                    // v2rayNG
        "com.github.nicholasng.v2rayng",    // v2rayNG alternative
        "com.v2rayng",                      // v2rayNG variant
        "free.v2ray.proxy.VPN",             // Free v2ray
        "com.wireguard.android",            // WireGuard official
        "com.cloudflare.onedotonedotonedotone", // Cloudflare 1.1.1.1
        "com.cloudflare.warp",              // Cloudflare WARP
        "org.torproject.android",           // Orbot (Tor)
        "com.psiphon3",                     // Psiphon
        "com.psiphon3.subscription",        // Psiphon Pro
        "net.openvpn.openvpn",              // OpenVPN Connect
        "de.blinkt.openvpn",                // OpenVPN for Android
        "com.nordvpn.android",              // NordVPN
        "com.expressvpn.vpn",               // ExpressVPN
        "com.surfshark.vpnclient.android",  // Surfshark
        "com.privateinternetaccess.android", // PIA
        "com.protonvpn.android",            // ProtonVPN
        "com.mullvad.vpn",                  // Mullvad VPN
        "com.tunnelbear.android",           // TunnelBear
        "com.hotspotshield.aff.android",    // Hotspot Shield
        "com.windscribe.vpn",               // Windscribe
        "com.cyberghostvpn.android",        // CyberGhost
        "com.ipvanish.android",             // IPVanish
        "com.vyprvpn.android",              // VyprVPN
        "com.ixolit.ivpn.android",          // IVPN
        "com.adguard.vpn",                  // AdGuard VPN
        "com.kape.vpn.android",             // Private Internet Access
        "com.nekoray.android",              // NekoRay
        "com.sagernet.sing_box",            // sing-box
        "io.nekohasekai.sagernet",          // SagerNet
        "io.nekohasekai.sfa",               // sing-box for Android
        "com.github.nicholasng.clash",      // Clash for Android
        "com.github.nicholasng.clashforandroid", // Clash for Android
        "com.github.nicholasng.cfa",        // CFA
        "com.github.nicholasng.surfboard",  // Surfboard
        "com.neko.v2ray",                   // NekoV2ray
        "moe.matsuri.lite",                 // Matsuri
        "io.github.nicholasng.hiddify",     // Hiddify
        "app.hiddify.com",                  // Hiddify Next
        "com.hiddify.hiddify",              // Hiddify
        "com.github.nicholasng.shadowsocks", // Shadowsocks
        "com.github.nicholasng.shadowsocksr", // ShadowsocksR
        "com.github.nicholasng.ssr",        // SSR
        "free.shadowsocks.proxy.VPN",       // Free Shadowsocks
        "com.kiwibrowser.browser",          // Kiwi (has VPN)
        "com.nicholasng.httpcustom",        // HTTP Custom
        "com.nicholasng.httpcustom.premium", // HTTP Custom Premium
        "com.nicholasng.httpcustom.lite",   // HTTP Custom Lite
        "com.evozi.injector",               // HTTP Injector
        "com.evozi.injector.lite",          // HTTP Injector Lite
        "com.evozi.injector.pro",           // HTTP Injector Pro
        "com.kpn.tunnel.plus",              // KPN Tunnel Plus
        "com.kpn.tunnel.revolution",        // KPN Tunnel Revolution
        "com.anyvpn.android",               // AnyVPN
        "com.ultrasurf.android.ultrasurf",  // Ultrasurf
        "com.lantern.android",              // Lantern
        "org.nicholasng.nicholasng",        // Generic VPN
    )
    
    /**
     * Data class representing a conflicting VPN app
     */
    data class ConflictingVpnApp(
        val packageName: String,
        val appName: String,
        val isRunning: Boolean,
        val isVpnActive: Boolean
    )
    
    /**
     * Check if another VPN is currently active on the device.
     * 
     * @param context Android context
     * @return true if another VPN is active
     */
    fun isOtherVpnActive(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            
            val hasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            
            if (hasVpnTransport) {
                AiLogHelper.w(TAG, "⚠️ Another VPN is currently active on the device")
            }
            
            hasVpnTransport
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error checking VPN status: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if TUN interface is currently active on the device.
     * TUN interfaces are typically named tun0, tun1, etc.
     * 
     * @return TunInterfaceInfo with details about active TUN interface, or null if none
     */
    fun getTunInterfaceInfo(): TunInterfaceInfo? {
        return try {
            // Method 1: Check via NetworkInterface API
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces?.hasMoreElements() == true) {
                val iface = networkInterfaces.nextElement()
                val name = iface.name.lowercase()
                
                // TUN interfaces are typically named tun0, tun1, etc.
                // Also check for ppp (point-to-point) interfaces used by some VPNs
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("tap")) {
                    if (iface.isUp) {
                        val addresses = iface.inetAddresses.toList()
                        val ipAddresses = addresses.map { it.hostAddress ?: "unknown" }
                        
                        AiLogHelper.w(TAG, "⚠️ Active TUN interface found: ${iface.name}, IPs: $ipAddresses")
                        
                        return TunInterfaceInfo(
                            name = iface.name,
                            isUp = true,
                            ipAddresses = ipAddresses,
                            mtu = iface.mtu
                        )
                    }
                }
            }
            
            // Method 2: Check /proc/net/dev for tun interfaces (fallback)
            val tunFromProc = checkTunFromProcNetDev()
            if (tunFromProc != null) {
                return tunFromProc
            }
            
            // Method 3: Check /sys/class/net for tun interfaces (fallback)
            val tunFromSys = checkTunFromSysClassNet()
            if (tunFromSys != null) {
                return tunFromSys
            }
            
            AiLogHelper.d(TAG, "✅ No active TUN interface found")
            null
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error checking TUN interface: ${e.message}", e)
            null
        }
    }
    
    /**
     * Check if any TUN interface is active.
     * 
     * @return true if a TUN interface is active
     */
    fun isTunInterfaceActive(): Boolean {
        return getTunInterfaceInfo() != null
    }
    
    /**
     * Check /proc/net/dev for TUN interfaces.
     */
    private fun checkTunFromProcNetDev(): TunInterfaceInfo? {
        return try {
            val procNetDev = File("/proc/net/dev")
            if (!procNetDev.exists()) return null
            
            BufferedReader(FileReader(procNetDev)).use { reader ->
                reader.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    val ifaceName = trimmed.substringBefore(":").trim().lowercase()
                    
                    if (ifaceName.startsWith("tun") || ifaceName.startsWith("ppp") || ifaceName.startsWith("tap")) {
                        // Parse stats to check if interface has traffic
                        val parts = trimmed.substringAfter(":").trim().split("\\s+".toRegex())
                        val rxBytes = parts.getOrNull(0)?.toLongOrNull() ?: 0
                        val txBytes = parts.getOrNull(8)?.toLongOrNull() ?: 0
                        
                        // If interface has any traffic, it's likely active
                        if (rxBytes > 0 || txBytes > 0) {
                            AiLogHelper.w(TAG, "⚠️ TUN interface from /proc/net/dev: $ifaceName (rx=$rxBytes, tx=$txBytes)")
                            return TunInterfaceInfo(
                                name = ifaceName,
                                isUp = true,
                                ipAddresses = emptyList(),
                                mtu = 0,
                                rxBytes = rxBytes,
                                txBytes = txBytes
                            )
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            AiLogHelper.d(TAG, "Could not read /proc/net/dev: ${e.message}")
            null
        }
    }
    
    /**
     * Check /sys/class/net for TUN interfaces.
     */
    private fun checkTunFromSysClassNet(): TunInterfaceInfo? {
        return try {
            val sysClassNet = File("/sys/class/net")
            if (!sysClassNet.exists() || !sysClassNet.isDirectory) return null
            
            sysClassNet.listFiles()?.forEach { ifaceDir ->
                val ifaceName = ifaceDir.name.lowercase()
                
                if (ifaceName.startsWith("tun") || ifaceName.startsWith("ppp") || ifaceName.startsWith("tap")) {
                    // Check if interface is up
                    val operStateFile = File(ifaceDir, "operstate")
                    val operState = if (operStateFile.exists()) {
                        operStateFile.readText().trim().lowercase()
                    } else {
                        "unknown"
                    }
                    
                    if (operState == "up" || operState == "unknown") {
                        // Get MTU
                        val mtuFile = File(ifaceDir, "mtu")
                        val mtu = if (mtuFile.exists()) {
                            mtuFile.readText().trim().toIntOrNull() ?: 0
                        } else {
                            0
                        }
                        
                        AiLogHelper.w(TAG, "⚠️ TUN interface from /sys/class/net: $ifaceName (state=$operState, mtu=$mtu)")
                        return TunInterfaceInfo(
                            name = ifaceName,
                            isUp = operState == "up",
                            ipAddresses = emptyList(),
                            mtu = mtu
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            AiLogHelper.d(TAG, "Could not read /sys/class/net: ${e.message}")
            null
        }
    }
    
    /**
     * Data class representing TUN interface information
     */
    data class TunInterfaceInfo(
        val name: String,
        val isUp: Boolean,
        val ipAddresses: List<String>,
        val mtu: Int,
        val rxBytes: Long = 0,
        val txBytes: Long = 0
    )

    
    /**
     * Check if VpnService.prepare() returns null (meaning we have VPN permission)
     * or returns an Intent (meaning another VPN has the permission or user needs to grant)
     * 
     * @param context Android context
     * @return true if VPN permission is available (no other VPN blocking)
     */
    fun isVpnPermissionAvailable(context: Context): Boolean {
        return try {
            val prepareIntent = VpnService.prepare(context)
            val isAvailable = prepareIntent == null
            
            if (!isAvailable) {
                AiLogHelper.w(TAG, "⚠️ VPN permission not available - another VPN may be active or permission needed")
            }
            
            isAvailable
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error checking VPN permission: ${e.message}", e)
            false
        }
    }
    
    /**
     * Find all installed VPN apps on the device.
     * 
     * @param context Android context
     * @return List of installed VPN app package names
     */
    fun findInstalledVpnApps(context: Context): List<String> {
        val installedVpnApps = mutableListOf<String>()
        val packageManager = context.packageManager
        
        try {
            // Check known VPN packages
            for (packageName in KNOWN_VPN_PACKAGES) {
                try {
                    packageManager.getPackageInfo(packageName, 0)
                    installedVpnApps.add(packageName)
                    AiLogHelper.d(TAG, "Found installed VPN app: $packageName")
                } catch (e: PackageManager.NameNotFoundException) {
                    // App not installed, skip
                }
            }
            
            // Also check for apps with VPN permission
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
            
            for (appInfo in installedApps) {
                // Skip our own app
                if (appInfo.packageName == context.packageName) continue
                
                // Skip already found apps
                if (installedVpnApps.contains(appInfo.packageName)) continue
                
                // Check if app has BIND_VPN_SERVICE permission (indicates VPN capability)
                try {
                    val packageInfo = packageManager.getPackageInfo(
                        appInfo.packageName,
                        PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
                    )
                    
                    val hasVpnPermission = packageInfo.requestedPermissions?.any { 
                        it == "android.permission.BIND_VPN_SERVICE" 
                    } == true
                    
                    if (hasVpnPermission) {
                        installedVpnApps.add(appInfo.packageName)
                        AiLogHelper.d(TAG, "Found VPN-capable app: ${appInfo.packageName}")
                    }
                } catch (e: Exception) {
                    // Skip apps we can't inspect
                }
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error finding VPN apps: ${e.message}", e)
        }
        
        return installedVpnApps
    }
    
    /**
     * Get detailed information about conflicting VPN apps.
     * 
     * @param context Android context
     * @return List of ConflictingVpnApp with details
     */
    fun getConflictingVpnApps(context: Context): List<ConflictingVpnApp> {
        val conflictingApps = mutableListOf<ConflictingVpnApp>()
        val packageManager = context.packageManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isVpnActive = isOtherVpnActive(context)
        
        // Get running processes
        val runningProcesses = try {
            @Suppress("DEPRECATION")
            activityManager.runningAppProcesses ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        
        val runningPackages = runningProcesses.map { it.processName }.toSet()
        
        // Check installed VPN apps
        val installedVpnApps = findInstalledVpnApps(context)
        
        for (packageName in installedVpnApps) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                val isRunning = runningPackages.any { it.startsWith(packageName) }
                
                conflictingApps.add(
                    ConflictingVpnApp(
                        packageName = packageName,
                        appName = appName,
                        isRunning = isRunning,
                        isVpnActive = isVpnActive && isRunning
                    )
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // App not found, skip
            }
        }
        
        return conflictingApps.sortedByDescending { it.isRunning }
    }

    
    /**
     * Attempt to force stop a conflicting VPN app.
     * 
     * Note: This has limited effectiveness without root access.
     * - KILL_BACKGROUND_PROCESSES only kills background processes, not foreground services
     * - VPN services typically run as foreground services
     * - Best approach is to guide user to Settings to force stop manually
     * 
     * @param context Android context
     * @param packageName Package name of the app to stop
     * @return true if force stop was attempted (not guaranteed to work)
     */
    fun forceStopApp(context: Context, packageName: String): Boolean {
        AiLogHelper.i(TAG, "🛑 Attempting to force stop: $packageName")
        
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // Method 1: killBackgroundProcesses (limited - only kills background processes)
            @Suppress("DEPRECATION")
            activityManager.killBackgroundProcesses(packageName)
            AiLogHelper.d(TAG, "Called killBackgroundProcesses for: $packageName")
            
            // Method 2: Try to use ActivityManager.forceStopPackage via reflection (requires system permission)
            try {
                val method = activityManager.javaClass.getMethod("forceStopPackage", String::class.java)
                method.invoke(activityManager, packageName)
                AiLogHelper.i(TAG, "✅ forceStopPackage called successfully for: $packageName")
                return true
            } catch (e: Exception) {
                AiLogHelper.d(TAG, "forceStopPackage not available (expected): ${e.message}")
            }
            
            // Method 3: Send broadcast to stop (if the app listens for it)
            try {
                val stopIntent = Intent("$packageName.STOP_VPN")
                stopIntent.setPackage(packageName)
                context.sendBroadcast(stopIntent)
                AiLogHelper.d(TAG, "Sent STOP_VPN broadcast to: $packageName")
            } catch (e: Exception) {
                AiLogHelper.d(TAG, "Failed to send stop broadcast: ${e.message}")
            }
            
            true
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error force stopping app: ${e.message}", e)
            false
        }
    }
    
    /**
     * Open system settings to allow user to force stop the app manually.
     * This is the most reliable method as it uses system UI.
     * 
     * @param context Android context
     * @param packageName Package name of the app
     * @return true if settings was opened successfully
     */
    fun openAppSettings(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AiLogHelper.i(TAG, "✅ Opened app settings for: $packageName")
            true
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to open app settings: ${e.message}", e)
            false
        }
    }
    
    /**
     * Force stop all running VPN apps.
     * 
     * @param context Android context
     * @return Number of apps that were attempted to be stopped
     */
    fun forceStopAllVpnApps(context: Context): Int {
        val conflictingApps = getConflictingVpnApps(context)
        var stoppedCount = 0
        
        for (app in conflictingApps) {
            if (app.isRunning) {
                AiLogHelper.i(TAG, "🛑 Force stopping VPN app: ${app.appName} (${app.packageName})")
                if (forceStopApp(context, app.packageName)) {
                    stoppedCount++
                }
            }
        }
        
        AiLogHelper.i(TAG, "Force stop attempted for $stoppedCount VPN apps")
        return stoppedCount
    }
    
    /**
     * 🚀 AGGRESSIVE TUN CLEANUP - "Otoban gibi bomboş olsun!"
     * 
     * Bu fonksiyon TUN interface'i tamamen temizler:
     * 1. Tüm çalışan VPN uygulamalarını force stop yapar
     * 2. TUN interface'in kapanmasını bekler
     * 3. Birden fazla deneme yapar
     * 4. Başarısız olursa bile devam eder
     * 
     * @param context Android context
     * @param maxRetries Maximum retry count (default: 5)
     * @param retryDelayMs Delay between retries in ms (default: 300)
     * @return true if TUN is clean, false if still active after all retries
     */
    fun aggressiveCleanup(
        context: Context,
        maxRetries: Int = 5,
        retryDelayMs: Long = 300
    ): Boolean {
        AiLogHelper.i(TAG, "🧹 AGGRESSIVE CLEANUP BAŞLADI - TUN interface temizleniyor...")
        
        // İlk kontrol - zaten temiz mi?
        if (!isOtherVpnActive(context) && !isTunInterfaceActive()) {
            AiLogHelper.i(TAG, "✅ TUN zaten temiz, otoban gibi bomboş!")
            return true
        }
        
        // Tüm VPN uygulamalarını bul
        val allVpnApps = getConflictingVpnApps(context)
        val runningApps = allVpnApps.filter { it.isRunning }
        
        AiLogHelper.w(TAG, "🔍 ${runningApps.size} çalışan VPN uygulaması bulundu")
        
        // Her bir çalışan uygulamayı durdur
        for (app in runningApps) {
            AiLogHelper.i(TAG, "🛑 Force stopping: ${app.appName} (${app.packageName})")
            forceStopApp(context, app.packageName)
        }
        
        // Retry loop - TUN'un kapanmasını bekle
        for (attempt in 1..maxRetries) {
            Thread.sleep(retryDelayMs)
            
            val vpnStillActive = isOtherVpnActive(context)
            val tunStillActive = isTunInterfaceActive()
            
            AiLogHelper.d(TAG, "🔄 Deneme $attempt/$maxRetries - VPN: $vpnStillActive, TUN: $tunStillActive")
            
            if (!vpnStillActive && !tunStillActive) {
                AiLogHelper.i(TAG, "✅ TUN TEMİZLENDİ! Otoban gibi bomboş! (Deneme: $attempt)")
                return true
            }
            
            // Hala aktif uygulamalar varsa tekrar durdur
            if (attempt < maxRetries) {
                val stillRunning = getConflictingVpnApps(context).filter { it.isRunning }
                for (app in stillRunning) {
                    AiLogHelper.w(TAG, "🔄 Tekrar durduruluyor: ${app.appName}")
                    forceStopApp(context, app.packageName)
                }
            }
        }
        
        // Son durum kontrolü
        val finalVpnActive = isOtherVpnActive(context)
        val finalTunActive = isTunInterfaceActive()
        
        if (!finalVpnActive && !finalTunActive) {
            AiLogHelper.i(TAG, "✅ TUN TEMİZLENDİ! Otoban gibi bomboş!")
            return true
        }
        
        AiLogHelper.e(TAG, "❌ TUN temizlenemedi! VPN: $finalVpnActive, TUN: $finalTunActive")
        AiLogHelper.e(TAG, "⚠️ Kullanıcının manuel olarak diğer VPN'i kapatması gerekiyor")
        return false
    }
    
    /**
     * 🚀 NÜKLEER SEÇENEK - Tüm VPN uygulamalarını zorla durdur (çalışsın çalışmasın)
     * 
     * Bu fonksiyon TÜM yüklü VPN uygulamalarını force stop yapar,
     * çalışıyor olsun ya da olmasın. En agresif temizlik yöntemi.
     * 
     * @param context Android context
     * @return Number of apps force stopped
     */
    fun nuclearCleanup(context: Context): Int {
        AiLogHelper.w(TAG, "☢️ NÜKLEER TEMİZLİK BAŞLADI - Tüm VPN uygulamaları durduruluyor!")
        
        val allVpnApps = findInstalledVpnApps(context)
        var stoppedCount = 0
        
        for (packageName in allVpnApps) {
            try {
                AiLogHelper.i(TAG, "☢️ Force stopping: $packageName")
                if (forceStopApp(context, packageName)) {
                    stoppedCount++
                }
            } catch (e: Exception) {
                AiLogHelper.e(TAG, "Error stopping $packageName: ${e.message}")
            }
        }
        
        // Biraz bekle
        Thread.sleep(500)
        
        val vpnActive = isOtherVpnActive(context)
        val tunActive = isTunInterfaceActive()
        
        AiLogHelper.i(TAG, "☢️ NÜKLEER TEMİZLİK TAMAMLANDI - $stoppedCount uygulama durduruldu")
        AiLogHelper.i(TAG, "📊 Sonuç: VPN aktif: $vpnActive, TUN aktif: $tunActive")
        
        return stoppedCount
    }
    
    /**
     * Get the currently active VPN app (if any).
     * 
     * @param context Android context
     * @return ConflictingVpnApp if found, null otherwise
     */
    fun getActiveVpnApp(context: Context): ConflictingVpnApp? {
        if (!isOtherVpnActive(context)) {
            return null
        }
        
        val conflictingApps = getConflictingVpnApps(context)
        return conflictingApps.firstOrNull { it.isRunning }
    }
    
    /**
     * Check and handle conflicting VPN before starting our VPN.
     * Returns a result indicating what action should be taken.
     * 
     * This method checks:
     * 1. If another VPN is active via ConnectivityManager
     * 2. If a TUN interface is active (tun0, tun1, ppp0, etc.)
     * 
     * 🚀 AGRESİF MOD: TUN'u otoban gibi bomboş bırakır!
     * 
     * @param context Android context
     * @return ConflictCheckResult with recommended action
     */
    fun checkAndHandleConflicts(context: Context): ConflictCheckResult {
        AiLogHelper.d(TAG, "🔍 Checking for conflicting VPN apps and TUN interfaces...")
        
        // Check 1: Is another VPN active via ConnectivityManager?
        val isVpnActive = isOtherVpnActive(context)
        
        // Check 2: Is a TUN interface active?
        val tunInfo = getTunInterfaceInfo()
        val isTunActive = tunInfo != null
        
        AiLogHelper.d(TAG, "VPN active: $isVpnActive, TUN active: $isTunActive" + 
            if (tunInfo != null) " (${tunInfo.name})" else "")
        
        // If neither VPN nor TUN is active, we're good
        if (!isVpnActive && !isTunActive) {
            AiLogHelper.i(TAG, "✅ No conflicting VPN or TUN interface detected - Otoban gibi bomboş!")
            return ConflictCheckResult.NoConflict
        }
        
        // 🚀 AGRESİF TEMİZLİK BAŞLAT!
        AiLogHelper.w(TAG, "⚠️ VPN/TUN conflict detected! Agresif temizlik başlatılıyor...")
        
        // Find the active VPN app for reporting
        val activeVpnApp = getActiveVpnApp(context)
        
        if (activeVpnApp != null) {
            AiLogHelper.w(TAG, "⚠️ Conflicting VPN detected: ${activeVpnApp.appName} (${activeVpnApp.packageName})")
        }
        if (tunInfo != null) {
            AiLogHelper.w(TAG, "⚠️ TUN interface in use: ${tunInfo.name} (MTU: ${tunInfo.mtu})")
        }
        
        // 🧹 AGRESİF CLEANUP - Birden fazla deneme ile TUN'u temizle
        val cleanupSuccess = aggressiveCleanup(context, maxRetries = 5, retryDelayMs = 300)
        
        if (cleanupSuccess) {
            AiLogHelper.i(TAG, "✅ AGRESİF TEMİZLİK BAŞARILI! TUN otoban gibi bomboş!")
            return if (activeVpnApp != null) {
                ConflictCheckResult.ConflictResolved(activeVpnApp)
            } else {
                ConflictCheckResult.NoConflict
            }
        }
        
        // Agresif cleanup başarısız oldu, NÜKLEER SEÇENEK!
        AiLogHelper.w(TAG, "☢️ Agresif cleanup başarısız, NÜKLEER SEÇENEK deneniyor...")
        nuclearCleanup(context)
        
        // Son kontrol
        Thread.sleep(500)
        val finalVpnActive = isOtherVpnActive(context)
        val finalTunActive = isTunInterfaceActive()
        
        if (!finalVpnActive && !finalTunActive) {
            AiLogHelper.i(TAG, "✅ NÜKLEER TEMİZLİK BAŞARILI! TUN otoban gibi bomboş!")
            return if (activeVpnApp != null) {
                ConflictCheckResult.ConflictResolved(activeVpnApp)
            } else {
                ConflictCheckResult.NoConflict
            }
        }
        
        // Hiçbir şey işe yaramadı - kullanıcı müdahalesi gerekli
        AiLogHelper.e(TAG, "❌ Tüm temizlik yöntemleri başarısız! Kullanıcı müdahalesi gerekli.")
        
        if (activeVpnApp != null) {
            return ConflictCheckResult.NeedsUserIntervention(activeVpnApp)
        }
        
        // TUN aktif ama uygulama bulunamadı
        val currentTunInfo = getTunInterfaceInfo()
        if (currentTunInfo != null) {
            return ConflictCheckResult.TunActiveUnknownApp(currentTunInfo)
        }
        
        return ConflictCheckResult.UnknownConflict
    }
    
    /**
     * Result of conflict check
     */
    sealed class ConflictCheckResult {
        /** No conflicting VPN detected */
        object NoConflict : ConflictCheckResult()
        
        /** Conflict was detected and resolved automatically */
        data class ConflictResolved(val app: ConflictingVpnApp) : ConflictCheckResult()
        
        /** Conflict detected but needs user to manually stop the app */
        data class NeedsUserIntervention(val app: ConflictingVpnApp) : ConflictCheckResult()
        
        /** VPN is active but couldn't identify which app */
        object UnknownConflict : ConflictCheckResult()
        
        /** TUN interface is active but couldn't identify which app is using it */
        data class TunActiveUnknownApp(val tunInfo: TunInterfaceInfo) : ConflictCheckResult()
    }
}
