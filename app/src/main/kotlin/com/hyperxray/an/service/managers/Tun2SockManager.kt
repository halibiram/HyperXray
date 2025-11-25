package com.hyperxray.an.service.managers

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hyperxray.an.common.Socks5ReadinessChecker
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.telemetry.TProxyAiOptimizer
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the Go-based tun2sock implementation.
 * Replaces HevSocksManager for handling TUN interface traffic.
 */
class Tun2SockManager(private val context: Context) {
    private val isRunningRef = AtomicBoolean(false)
    private val lock = Any()

    companion object {
        private const val TAG = "Tun2SockManager"

        init {
            try {
                System.loadLibrary("tun2sock")
                System.loadLibrary("tun2sock-wrapper")
                Log.d(TAG, "tun2sock libraries loaded successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load tun2sock libraries", e)
            }
        }

        @JvmStatic
        private external fun start(tunFd: Int, proxyUrl: String)

        @JvmStatic
        private external fun stop()
    }

    /**
     * Start tun2sock with the given TUN file descriptor and SOCKS5 port.
     */
    fun start(tunFd: ParcelFileDescriptor, socksPort: Int): Boolean {
        synchronized(lock) {
            if (isRunningRef.get()) return true

            return try {
                Log.d(TAG, "Starting tun2sock on port $socksPort")
                start(tunFd.fd, "socks5://127.0.0.1:$socksPort")
                isRunningRef.set(true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error starting tun2sock", e)
                false
            }
        }
    }

    /**
     * Stop tun2sock.
     */
    fun stop() {
        synchronized(lock) {
            if (!isRunningRef.get()) return
            try {
                Log.d(TAG, "Stopping tun2sock")
                stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tun2sock", e)
            } finally {
                isRunningRef.set(false)
            }
        }
    }

    // Methods for TProxyService integration

    fun stopNativeTProxy(waitForUdpCleanup: Boolean = false, udpCleanupDelayMs: Long = 0) {
        stop()
        if (waitForUdpCleanup && udpCleanupDelayMs > 0) {
            try {
                Thread.sleep(udpCleanupDelayMs)
            } catch (e: InterruptedException) {
                 Thread.currentThread().interrupt()
            }
        }
    }

    suspend fun startNativeTProxy(
        tunInterfaceManager: TunInterfaceManager,
        prefs: Preferences,
        isStoppingCallback: () -> Boolean,
        stopXrayCallback: (String) -> Unit,
        serviceScope: CoroutineScope,
        tproxyAiOptimizer: TProxyAiOptimizer? = null,
        coreStatsState: CoreStatsState? = null,
        lastTProxyRestartTime: () -> Long,
        setLastTProxyRestartTime: (Long) -> Unit,
        onStartComplete: (() -> Unit)? = null,
        notificationManager: Any? = null
    ): Boolean {
        if (isStoppingCallback()) return false

        val tunFd = tunInterfaceManager.getTunFd()
        if (tunFd == null) {
            stopXrayCallback("TUN FD is null")
            return false
        }

        if (start(tunFd, prefs.socksPort)) {
            onStartComplete?.invoke()
            return true
        }
        return false
    }

    suspend fun checkSocks5Readiness(
        prefs: Preferences,
        serviceScope: CoroutineScope,
        socks5ReadinessChecked: Boolean,
        onSocks5StatusChanged: (Boolean, Boolean) -> Unit,
        systemDnsCacheServer: Any?,
        startNativeTProxyCallback: suspend () -> Unit
    ) {
        Log.i(TAG, "Checking SOCKS5 readiness on ${prefs.socksAddress}:${prefs.socksPort}")

        val isReady = Socks5ReadinessChecker.waitUntilSocksReady(
            context = context,
            address = prefs.socksAddress,
            port = prefs.socksPort,
            maxWaitTimeMs = 30000L,
            retryIntervalMs = 500L
        )

        if (isReady) {
             Log.i(TAG, "SOCKS5 Ready")
             if (!socks5ReadinessChecked) {
                 onSocks5StatusChanged(true, false)
                 // Set DNS cache proxy if needed
                 if (systemDnsCacheServer is com.hyperxray.an.core.network.dns.SystemDnsCacheServer) {
                     systemDnsCacheServer.setSocks5Proxy(prefs.socksAddress, prefs.socksPort)
                 }
                 startNativeTProxyCallback()
             }
        } else {
             Log.w(TAG, "SOCKS5 not ready")
             if (socks5ReadinessChecked) {
                 onSocks5StatusChanged(false, true)
             }
        }
    }

    fun isRunning(): Boolean {
        return isRunningRef.get()
    }
}
