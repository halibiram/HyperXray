package com.hyperxray.an.service.managers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.common.Socks5ReadinessChecker
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.core.network.dns.SystemDnsCacheServer
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.service.state.ServiceSessionState
import com.hyperxray.an.service.utils.TProxyUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages TUN interface establishment and lifecycle.
 * Handles VPN Builder configuration, ParcelFileDescriptor management, and thread-safe access.
 */
class TunInterfaceManager(private val vpnService: VpnService) {
    private val tunFdRef = AtomicReference<ParcelFileDescriptor?>(null)
    private val tunFdLock = Any() // Synchronization lock for tunFd access
    
    companion object {
        private const val TAG = "TunInterfaceManager"
    }
    
    /**
     * Establish VPN interface (TUN) with full setup including DNS cache server, TProxy config, etc.
     * This method encapsulates all VPN interface setup logic previously in TProxyService.establishVpnInterface().
     * 
     * @param prefs Preferences for VPN configuration
     * @param sessionState Session state containing systemDnsCacheServer, dnsCacheInitialized, etc.
     * @param context Context for cache directory and other operations
     * @param serviceScope CoroutineScope for async operations
     * @param onError Callback for error notifications (Intent broadcast, Telegram, etc.)
     * @return ParcelFileDescriptor if successful, null otherwise
     */
    fun establish(
        prefs: Preferences,
        sessionState: ServiceSessionState,
        context: Context,
        serviceScope: CoroutineScope,
        onError: ((String) -> Unit)? = null
    ): ParcelFileDescriptor? {
        synchronized(tunFdLock) {
            if (tunFdRef.get() != null) {
                Log.d(TAG, "VPN interface already established, skipping establish()")
                val existingFd = tunFdRef.get()
                // Update session state for backward compatibility
                synchronized(sessionState.tunFdLock) {
                    sessionState.tunFd = existingFd
                }
                return existingFd
            }
        }
        
        // Initialize DnsCacheManager first (before starting DNS cache server)
        if (!sessionState.dnsCacheInitialized) {
            try {
                DnsCacheManager.initialize(context)
                sessionState.dnsCacheInitialized = true
                Log.d(TAG, "DnsCacheManager initialized")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize DnsCacheManager: ${e.message}", e)
            }
        }
        
        // Start system DNS cache server before building VPN (needed for DNS configuration)
        try {
            if (sessionState.systemDnsCacheServer == null) {
                sessionState.systemDnsCacheServer = SystemDnsCacheServer.getInstance(context)
            }
            sessionState.systemDnsCacheServer?.start()
            Log.d(TAG, "DNS cache server started successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error starting DNS cache server: ${e.message}", e)
        }
        
        // Check VPN permission before attempting to establish VPN interface
        val vpnPrepareIntent = VpnService.prepare(vpnService)
        if (vpnPrepareIntent != null) {
            Log.e(TAG, "VPN permission not granted. VpnService.prepare() returned non-null intent.")
            onError?.invoke("VPN permission not granted. Please grant VPN permission.")
            return null
        }
        
        Log.d(TAG, "VPN permission check passed. Proceeding to establish VPN interface...")
        
        Log.d(TAG, "Building VPN interface configuration...")
        val builder = buildVpnInterface(prefs, sessionState.systemDnsCacheServer)
        
        Log.d(TAG, "Attempting to establish VPN interface (TUN)...")
        val newTunFd = builder.establish()
        
        Log.d(TAG, "VPN interface establish() result: ${if (newTunFd != null) "SUCCESS" else "FAILED (null)"}")
        
        synchronized(tunFdLock) {
            tunFdRef.set(newTunFd)
        }
        
        // Update session state for backward compatibility
        synchronized(sessionState.tunFdLock) {
            sessionState.tunFd = newTunFd
        }
        
        if (newTunFd == null) {
            Log.e(TAG, "Failed to establish VPN interface (TUN). builder.establish() returned null.")
            Log.e(TAG, "Possible causes: VPN permission not granted, another VPN is active, or system-level TUN creation error.")
            
            // Check if another VPN is active
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                
                if (hasVpn) {
                    Log.e(TAG, "Another VPN is currently active. Cannot establish new VPN interface.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check for active VPN: ${e.message}", e)
            }
            
            val errorMessage = "Failed to establish VPN interface (TUN). Please check VPN permissions or disable other VPN services."
            onError?.invoke(errorMessage)
            return null
        }
        
        // Set VPN interface IP for DNS socket binding (ensures DNS queries go through VPN)
        if (newTunFd != null && prefs.ipv4) {
            sessionState.systemDnsCacheServer?.setVpnInterfaceIp(prefs.tunnelIpv4Address)
            Log.i(TAG, "✅ VPN interface IP set for DNS: ${prefs.tunnelIpv4Address} (direct UDP DNS queries will be routed through VPN)")
        } else {
            if (newTunFd == null) {
                Log.w(TAG, "⚠️ VPN interface not established, DNS queries may not route through VPN")
            }
            if (!prefs.ipv4) {
                Log.w(TAG, "⚠️ IPv4 disabled, VPN interface IP not set for DNS")
            }
        }
        
        // Try to set SOCKS5 proxy early if already ready (before checkSocks5Readiness)
        // This ensures DNS queries can use SOCKS5 fallback immediately
        val socksAddress = prefs.socksAddress
        val socksPort = prefs.socksPort
        serviceScope.launch {
            try {
                // Quick check if SOCKS5 is already ready
                if (Socks5ReadinessChecker.isSocks5Ready(context, socksAddress, socksPort)) {
                    sessionState.systemDnsCacheServer?.setSocks5Proxy(socksAddress, socksPort)
                    Log.d(TAG, "✅ SOCKS5 UDP proxy set for DNS (early check): $socksAddress:$socksPort")
                } else {
                    Log.d(TAG, "⏳ SOCKS5 not ready yet, will be set after readiness check")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in early SOCKS5 proxy check: ${e.message}")
            }
        }
        
        // Generate and write TProxy config file
        val tproxyFile = File(context.cacheDir, "tproxy.conf")
        try {
            tproxyFile.createNewFile()
            FileOutputStream(tproxyFile, false).use { fos ->
                val tproxyConf = TProxyUtils.getTproxyConf(prefs)
                fos.write(tproxyConf.toByteArray())
            }
            Log.d(TAG, "TProxy config file created successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write TProxy config file: ${e.message}", e)
            
            val errorMessage = "Failed to write TProxy config file: ${e.message}"
            onError?.invoke(errorMessage)
            // Don't return null here - VPN interface is established, config file failure is non-critical
            // but we should still notify about it
        }
        
        Log.d(TAG, "VPN interface established successfully. Waiting for Xray to start and SOCKS5 to become ready...")
        return newTunFd
    }
    
    /**
     * Build VPN interface configuration.
     * Matches getVpnBuilder() logic exactly from TProxyService.
     * 
     * @param prefs Preferences for VPN configuration
     * @param dnsCacheServer Optional DNS cache server for DNS configuration
     * @return Configured VPN Builder
     */
    private fun buildVpnInterface(
        prefs: Preferences,
        dnsCacheServer: SystemDnsCacheServer?
    ): VpnService.Builder = vpnService.Builder().apply {
        setBlocking(false)
        setMtu(prefs.tunnelMtu)

        if (prefs.bypassLan) {
            addRoute("10.0.0.0", 8)
            addRoute("172.16.0.0", 12)
            addRoute("192.168.0.0", 16)
        }
        if (prefs.httpProxyEnabled) {
            setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", prefs.socksPort))
        }
        if (prefs.ipv4) {
            addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
            addRoute("0.0.0.0", 0)
            
            // Use DNS cache server if available (started in establishVpnInterface())
            // Note: DNS cache server is started before getVpnBuilder is called
            val listeningPort = dnsCacheServer?.getListeningPort()
            if (listeningPort != null) {
                if (listeningPort == 53) {
                    // SystemDnsCacheServer port 53'te çalışıyor - VpnService DNS'i 127.0.0.1 olarak ayarla
                    try {
                        addDnsServer("127.0.0.1") // Use local DNS cache server on port 53
                        Log.d(TAG, "✅ VpnService DNS set to 127.0.0.1:53 (SystemDnsCacheServer)")
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Failed to set VpnService DNS to 127.0.0.1: ${e.message}", e)
                        // Fallback to custom DNS
                        prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
                    }
                } else {
                    // SystemDnsCacheServer is running on port 5353 (port 53 not available)
                    // VpnService DNS trafiğini yakalayıp SystemDnsCacheServer'a yönlendirmek için
                    // VpnService DNS'i 127.0.0.1 olarak ayarlamayı deniyoruz, ancak VpnService port 53'e gidecek
                    // Bu yüzden custom DNS kullanıyoruz ve SystemDnsCacheServer SOCKS5 üzerinden DNS sorgularını yapabilir
                    Log.i(TAG, "⚠️ SystemDnsCacheServer on port $listeningPort - VpnService DNS will use custom DNS (SystemDnsCacheServer available via SOCKS5)")
                    // Use custom DNS - SystemDnsCacheServer SOCKS5 üzerinden DNS sorgularını yapabilir
                    prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
                }
            } else {
                // DNS cache server not available, use custom DNS
                prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }
        }
        if (prefs.ipv6) {
            addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
            addRoute("::", 0)
            // For IPv6, use custom DNS server or localhost if DNS cache server is running
            if (dnsCacheServer?.isRunning() == true) {
                addDnsServer("::1") // IPv6 localhost
            } else {
                prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }
        }

        prefs.apps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
        }
        if (prefs.bypassSelectedApps || prefs.apps.isNullOrEmpty())
            addDisallowedApplication(BuildConfig.APPLICATION_ID)
    }
    
    /**
     * Get TUN FileDescriptor (thread-safe).
     * 
     * @return ParcelFileDescriptor if established, null otherwise
     */
    fun getTunFd(): ParcelFileDescriptor? {
        return synchronized(tunFdLock) {
            tunFdRef.get()
        }
    }
    
    /**
     * Close TUN FileDescriptor (thread-safe).
     * Also updates session state for backward compatibility.
     * 
     * @param sessionState Optional session state to update
     */
    fun closeTunFd(sessionState: ServiceSessionState? = null) {
        synchronized(tunFdLock) {
            tunFdRef.get()?.close()
            tunFdRef.set(null)
        }
        // Update session state for backward compatibility
        sessionState?.let {
            synchronized(it.tunFdLock) {
                it.tunFd?.close()
                it.tunFd = null
            }
        }
    }
    
    /**
     * Check if VPN interface is established.
     * 
     * @return true if established, false otherwise
     */
    fun isEstablished(): Boolean {
        return synchronized(tunFdLock) {
            tunFdRef.get() != null
        }
    }
    
    /**
     * Get TUN FileDescriptor file descriptor integer (for native code).
     * Thread-safe access.
     * 
     * @return File descriptor integer, or null if not established
     */
    fun getTunFdInt(): Int? {
        return synchronized(tunFdLock) {
            try {
                tunFdRef.get()?.fd
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing tunFd.fd: ${e.message}", e)
                null
            }
        }
    }
}


