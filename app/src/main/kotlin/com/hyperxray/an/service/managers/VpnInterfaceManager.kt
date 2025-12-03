package com.hyperxray.an.service.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.prefs.Preferences
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages VPN interface (TUN) establishment and lifecycle.
 * Handles VPN Builder configuration and thread-safe TUN FileDescriptor access.
 */
class VpnInterfaceManager(private val vpnService: VpnService) {
    private val tunFdRef = AtomicReference<ParcelFileDescriptor?>(null)
    private val tunFdLock = Any() // Synchronization lock for tunFd access
    
    companion object {
        private const val TAG = "VpnInterfaceManager"
    }
    
    /**
     * Establish VPN interface (TUN).
     * 
     * @param prefs Preferences for VPN configuration
     * @return ParcelFileDescriptor if successful, null otherwise
     */
    fun establish(
        prefs: Preferences
    ): ParcelFileDescriptor? {
        synchronized(tunFdLock) {
            if (tunFdRef.get() != null) {
                Log.d(TAG, "VPN interface already established, skipping establish()")
                return tunFdRef.get()
            }
        }
        
        // VPN permission should already be granted at Activity/ViewModel level
        // This check is just for logging - let builder.establish() handle actual permission errors
        val vpnPrepareIntent = VpnService.prepare(vpnService)
        if (vpnPrepareIntent != null) {
            Log.w(TAG, "VPN permission check failed - but continuing anyway. This should not happen if permission was granted.") // WARNING, not ERROR
            // Continue - let builder.establish() handle the actual error
        } else {
            Log.d(TAG, "VPN permission check passed. Proceeding to establish VPN interface...")
        }
        
        Log.d(TAG, "Building VPN interface configuration...")
        val builder = getVpnBuilder(prefs)
        
        Log.d(TAG, "Attempting to establish VPN interface (TUN)...")
        val newTunFd = builder.establish()
        
        Log.d(TAG, "VPN interface establish() result: ${if (newTunFd != null) "SUCCESS" else "FAILED (null)"}")
        
        synchronized(tunFdLock) {
            tunFdRef.set(newTunFd)
        }
        
        if (newTunFd == null) {
            Log.e(TAG, "Failed to establish VPN interface (TUN). builder.establish() returned null.")
            Log.e(TAG, "Possible causes: VPN permission not granted, another VPN is active, or system-level TUN creation error.")
            
            // Check if another VPN is active
            try {
                val connectivityManager = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                
                if (hasVpn) {
                    Log.e(TAG, "Another VPN is currently active. Cannot establish new VPN interface.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check for active VPN: ${e.message}", e)
            }
            
            return null
        }
        
        Log.d(TAG, "VPN interface established successfully.")
        return newTunFd
    }
    
    /**
     * Get VPN Builder with configuration.
     * 
     * @param prefs Preferences for VPN configuration
     * @return Configured VPN Builder
     */
    fun getVpnBuilder(
        prefs: Preferences
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
            // Use custom DNS from preferences (DNS handled by native Go library)
            prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }
        if (prefs.ipv6) {
            addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
            addRoute("::", 0)
            // Use custom DNS from preferences (DNS handled by native Go library)
            prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }
        
        prefs.apps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (ignored: android.content.pm.PackageManager.NameNotFoundException) {
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
     */
    fun closeTunFd() {
        synchronized(tunFdLock) {
            tunFdRef.get()?.close()
            tunFdRef.set(null)
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

