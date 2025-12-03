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
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.state.ServiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture

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
     * @param sessionState Session state containing dnsCacheInitialized, etc.
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
        val establishStartTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🔧 TUN ESTABLISH START: Beginning VPN interface establishment")
        
        synchronized(tunFdLock) {
            if (tunFdRef.get() != null) {
                Log.d(TAG, "VPN interface already established, skipping establish()")
                AiLogHelper.d(TAG, "✅ TUN ESTABLISH: VPN interface already established, reusing existing")
                val existingFd = tunFdRef.get()
                // Update session state for backward compatibility
                synchronized(sessionState.tunFdLock) {
                    sessionState.tunFd = existingFd
                }
                return existingFd
            }
        }
        
        // DNS cache removed - using Google DNS (8.8.8.8) directly
        AiLogHelper.d(TAG, "✅ TUN ESTABLISH: Using Google DNS directly (no cache)")
        
        // VPN permission should already be granted at Activity/ViewModel level
        // This check is just for logging - let builder.establish() handle actual permission errors
        val permissionCheckStartTime = System.currentTimeMillis()
        AiLogHelper.d(TAG, "🔧 TUN ESTABLISH: Verifying VPN permission...")
        val vpnPrepareIntent = VpnService.prepare(vpnService)
        if (vpnPrepareIntent != null) {
            val errorMsg = "VPN permission not granted. VpnService.prepare() returned non-null intent."
            Log.w(TAG, errorMsg) // Changed to WARNING, not ERROR
            AiLogHelper.w(TAG, "⚠️ TUN ESTABLISH: $errorMsg - This should not happen if permission was granted. Continuing anyway...")
            // Continue anyway - let builder.establish() handle the actual error
        } else {
            val permissionCheckDuration = System.currentTimeMillis() - permissionCheckStartTime
            Log.d(TAG, "VPN permission check passed. Proceeding to establish VPN interface...")
            AiLogHelper.i(TAG, "✅ TUN ESTABLISH: VPN permission check passed (duration: ${permissionCheckDuration}ms)")
        }
        
        // Build VPN interface configuration
        val buildStartTime = System.currentTimeMillis()
        Log.d(TAG, "Building VPN interface configuration...")
        AiLogHelper.d(TAG, "🔧 TUN ESTABLISH: Building VPN interface configuration (MTU=${prefs.tunnelMtu}, IPv4=${prefs.ipv4}, IPv6=${prefs.ipv6})...")
        val builder = buildVpnInterface(prefs)
        val buildDuration = System.currentTimeMillis() - buildStartTime
        AiLogHelper.d(TAG, "✅ TUN ESTABLISH: VPN interface configuration built (duration: ${buildDuration}ms)")
        
        // Establish VPN interface with timeout protection
        val establishFdStartTime = System.currentTimeMillis()
        Log.d(TAG, "Attempting to establish VPN interface (TUN)...")
        AiLogHelper.d(TAG, "🔧 TUN ESTABLISH: Calling builder.establish() with 10s timeout protection...")
        
        // Run establish() on background thread with timeout to prevent hanging
        val newTunFd = try {
            val future = CompletableFuture.supplyAsync({
                builder.establish()
            }, java.util.concurrent.Executors.newSingleThreadExecutor())
            
            // Wait with timeout
            future.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.e(TAG, "❌ TUN ESTABLISH TIMEOUT: builder.establish() took longer than 10 seconds!", e)
            AiLogHelper.e(TAG, "❌ TUN ESTABLISH TIMEOUT: builder.establish() took longer than 10 seconds - possible system-level hang")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ TUN ESTABLISH EXCEPTION: ${e.message}", e)
            AiLogHelper.e(TAG, "❌ TUN ESTABLISH EXCEPTION: ${e.message}")
            null
        }
        
        val establishFdDuration = System.currentTimeMillis() - establishFdStartTime
        
        Log.d(TAG, "VPN interface establish() result: ${if (newTunFd != null) "SUCCESS" else "FAILED (null)"} (duration: ${establishFdDuration}ms)")
        if (newTunFd != null) {
            AiLogHelper.i(TAG, "✅ TUN ESTABLISH: VPN interface established successfully (fd=${newTunFd.fd}, duration: ${establishFdDuration}ms)")
        } else {
            if (establishFdDuration >= 10_000) {
                AiLogHelper.e(TAG, "❌ TUN ESTABLISH FAILED: Timeout after ${establishFdDuration}ms - builder.establish() hung")
            } else {
                AiLogHelper.e(TAG, "❌ TUN ESTABLISH FAILED: builder.establish() returned null (duration: ${establishFdDuration}ms)")
            }
        }
        
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
            AiLogHelper.e(TAG, "❌ TUN ESTABLISH FAILED: builder.establish() returned null")
            AiLogHelper.e(TAG, "🔍 TUN ESTABLISH: Possible causes: VPN permission not granted, another VPN is active, or system-level TUN creation error")
            
            // Check if another VPN is active
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                
                if (hasVpn) {
                    Log.e(TAG, "Another VPN is currently active. Cannot establish new VPN interface.")
                    AiLogHelper.e(TAG, "❌ TUN ESTABLISH: Another VPN is currently active. Cannot establish new VPN interface.")
                } else {
                    AiLogHelper.d(TAG, "✅ TUN ESTABLISH: No other VPN detected")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check for active VPN: ${e.message}", e)
                AiLogHelper.w(TAG, "⚠️ TUN ESTABLISH: Could not check for active VPN: ${e.message}")
            }
            
            val errorMessage = "Failed to establish VPN interface (TUN). Please check VPN permissions or disable other VPN services."
            onError?.invoke(errorMessage)
            return null
        }
        
        val totalDuration = System.currentTimeMillis() - establishStartTime
        Log.d(TAG, "VPN interface established successfully. Waiting for Xray to start and SOCKS5 to become ready...")
        AiLogHelper.i(TAG, "✅ TUN ESTABLISH SUCCESS: VPN interface established successfully (total duration: ${totalDuration}ms)")
        return newTunFd
    }
    
    /**
     * Build VPN interface configuration.
     * Matches getVpnBuilder() logic exactly from TProxyService.
     * 
     * @param prefs Preferences for VPN configuration
     * @return Configured VPN Builder
     */
    private fun buildVpnInterface(
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


