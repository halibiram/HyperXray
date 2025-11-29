package com.hyperxray.an.service.utils

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.core.network.dns.SystemDnsCacheServer
import com.hyperxray.an.ui.screens.log.extractDnsQuery
import com.hyperxray.an.ui.screens.log.extractSniffedDomain
import com.hyperxray.an.ui.screens.log.extractSNI
// DEPRECATED: MultiXrayCoreManager removed - using single instance XrayCoreManager
// import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
// import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.common.Socks5ReadinessChecker
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import com.hyperxray.an.core.network.TLSFeatureEncoder
import com.hyperxray.an.core.monitor.OptimizerLogger
import com.xray.app.stats.command.SysStatsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.lang.Process
import kotlin.math.abs

/**
 * Utility functions for VPN service operations.
 * Contains all helper methods that do not directly call Android Lifecycle methods.
 */
object TProxyUtils {
    private const val TAG = "TProxyUtils"
    
    // ============================================================================
    // Port Utilities
    // ============================================================================
    
    /**
     * Find an available port in the range 10000-65535, excluding specified ports.
     * 
     * @param excludedPorts Set of ports to exclude from search
     * @return Available port number, or null if none found
     */
    fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        return (10000..65535)
            .shuffled()
            .firstOrNull { port ->
                port !in excludedPorts && runCatching {
                    java.net.ServerSocket(port).use { socket ->
                        socket.reuseAddress = true
                    }
                    true
                }.getOrDefault(false)
            }
    }
    
    // ============================================================================
    // Logging & Broadcasting Utilities
    // ============================================================================
    
    /**
     * Broadcasts a batch of logs to the UI.
     */
    fun broadcastLogsBatch(
        context: Context,
        broadcastBuffer: MutableList<String>,
        reusableBroadcastList: ArrayList<String>
    ) {
        if (broadcastBuffer.isEmpty()) return
        
        val logUpdateIntent = Intent("com.hyperxray.an.LOG_UPDATE")
        logUpdateIntent.setPackage(context.packageName)
        
        reusableBroadcastList.clear()
        if (broadcastBuffer.size > reusableBroadcastList.size) {
            reusableBroadcastList.ensureCapacity(broadcastBuffer.size)
        }
        reusableBroadcastList.addAll(broadcastBuffer)
        logUpdateIntent.putStringArrayListExtra("log_data", reusableBroadcastList)
        context.sendBroadcast(logUpdateIntent)
        broadcastBuffer.clear()
        Log.d(TAG, "Broadcasted a batch of logs.")
    }
    
    // ============================================================================
    // Config Management Utilities
    // ============================================================================
    
    /**
     * Validates that a config file path is within the app's private directory.
     * Prevents path traversal attacks and ensures config files are secure.
     */
    fun validateConfigPath(context: Context, configPath: String?): File? {
        if (configPath == null) {
            Log.e(TAG, "Config path is null")
            return null
        }
        
        try {
            val configFile = File(configPath)
            
            if (!configFile.exists()) {
                Log.e(TAG, "Config file does not exist: $configPath")
                return null
            }
            
            if (!configFile.isFile) {
                Log.e(TAG, "Config path is not a file: $configPath")
                return null
            }
            
            val canonicalConfigPath = configFile.canonicalPath
            val privateDir = context.filesDir
            val canonicalPrivateDir = privateDir.canonicalPath
            
            if (!canonicalConfigPath.startsWith(canonicalPrivateDir)) {
                Log.e(TAG, "Config file is outside private directory: $canonicalConfigPath (private dir: $canonicalPrivateDir)")
                return null
            }
            
            if (!configFile.canRead()) {
                Log.e(TAG, "Config file is not readable: $canonicalConfigPath")
                return null
            }
            
            return configFile
        } catch (e: Exception) {
            Log.e(TAG, "Error validating config path: $configPath", e)
            return null
        }
    }
    
    /**
     * Reads config content securely after validation.
     * This prevents TOCTOU (Time-of-Check-Time-of-Use) race conditions.
     */
    fun readConfigContentSecurely(configFile: File): String? {
        return try {
            if (!configFile.exists() || !configFile.isFile || !configFile.canRead()) {
                Log.e(TAG, "Config file validation failed during read: ${configFile.canonicalPath}")
                return null
            }
            
            configFile.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading config file: ${configFile.canonicalPath}", e)
            null
        }
    }
    
    /**
     * Ensures that all required directories for Xray operation exist.
     */
    fun ensureXrayDirectories(context: Context) {
        val filesDir = context.filesDir
        val directories = listOf(
            File(filesDir, "logs"),
            File(filesDir, "frames"),
            File(filesDir, "xray_config")
        )
        
        directories.forEach { dir ->
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (created) {
                    Log.d(TAG, "Created directory: ${dir.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to create directory: ${dir.absolutePath}")
                }
            }
        }
    }
    
    /**
     * Extract server address from Xray config JSON (outbounds[0].settings.vnext[0].address)
     * Supports VLESS, VMess, Trojan, Shadowsocks protocols
     */
    fun extractServerAddressFromConfig(configContent: String): String? {
        return try {
            val jsonObject = org.json.JSONObject(configContent)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: return null
            if (outbounds.length() == 0) return null
            
            val outbound = outbounds.getJSONObject(0)
            val protocol = outbound.optString("protocol", "").lowercase()
            val settings = outbound.optJSONObject("settings") ?: return null
            
            if (protocol == "vless" || protocol == "vmess") {
                val vnext = settings.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    val vnextServer = vnext.getJSONObject(0)
                    return vnextServer.optString("address", "").takeIf { it.isNotEmpty() }
                }
            }
            
            if (protocol == "trojan") {
                val servers = settings.optJSONArray("servers")
                if (servers != null && servers.length() > 0) {
                    val server = servers.getJSONObject(0)
                    return server.optString("address", "").takeIf { it.isNotEmpty() }
                }
            }
            
            if (protocol == "shadowsocks" || protocol == "shadowsocks2022") {
                val servers = settings.optJSONArray("servers")
                if (servers != null && servers.length() > 0) {
                    val server = servers.getJSONObject(0)
                    return server.optString("address", "").takeIf { it.isNotEmpty() }
                }
            }
            
            // Handle WireGuard protocol
            if (protocol == "wireguard") {
                val peers = settings.optJSONArray("peers")
                if (peers != null && peers.length() > 0) {
                    val peer = peers.getJSONObject(0)
                    val endpoint = peer.optString("endpoint", "")
                    if (endpoint.isNotEmpty()) {
                        // Extract domain from endpoint (format: "domain.com:port")
                        val endpointParts = endpoint.split(":")
                        if (endpointParts.isNotEmpty()) {
                            return endpointParts[0]
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract server address from config: ${e.message}")
            null
        }
    }
    
    // ============================================================================
    // Process Management Utilities
    // ============================================================================
    
    // ============================================================================
    // DNS & Network Utilities
    // ============================================================================
    
    /**
     * Intercept DNS queries from Xray-core logs and cache them.
     * This allows browser and other apps to benefit from DNS cache.
     * Root is NOT required - works by parsing Xray DNS logs.
     */
    fun interceptDnsFromXrayLogs(
        context: Context,
        logLine: String,
        dnsCacheInitialized: Boolean,
        systemDnsCacheServer: SystemDnsCacheServer?,
        serviceScope: CoroutineScope
    ): Boolean {
        var initialized = dnsCacheInitialized
        if (!initialized) {
            try {
                DnsCacheManager.initialize(context)
                initialized = true
                Log.d(TAG, "DnsCacheManager initialized in interceptDnsFromXrayLogs()")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize DNS cache in interceptDnsFromXrayLogs(): ${e.message}", e)
                return false
            }
        }
        
        try {
            val dnsQuery = extractDnsQuery(logLine)
            if (dnsQuery != null) {
                val cached = DnsCacheManager.getFromCache(dnsQuery)
                if (cached != null && cached.isNotEmpty()) {
                    Log.d(TAG, "✅ DNS CACHE HIT (Xray log): $dnsQuery -> ${cached.map { it.hostAddress }}")
                    return true
                }
                
                serviceScope.launch {
                    try {
                        val resolvedAddresses = forwardDnsQueryToSystemCacheServer(
                            context = context,
                            domain = dnsQuery,
                            systemDnsCacheServer = systemDnsCacheServer
                        )
                        if (resolvedAddresses.isNotEmpty()) {
                            DnsCacheManager.saveToCache(dnsQuery, resolvedAddresses)
                            Log.i(TAG, "✅ DNS resolved via SystemDnsCacheServer (Xray patch): $dnsQuery -> ${resolvedAddresses.map { it.hostAddress }}")
                        } else {
                            Log.d(TAG, "⚠️ DNS CACHE MISS (Xray log): $dnsQuery (SystemDnsCacheServer couldn't resolve)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error forwarding DNS query to SystemDnsCacheServer: $dnsQuery", e)
                    }
                }
            }
            
            val sniffedDomain = extractSniffedDomain(logLine)
            if (sniffedDomain != null) {
                val cached = DnsCacheManager.getFromCache(sniffedDomain)
                if (cached != null && cached.isNotEmpty()) {
                    Log.d(TAG, "✅ DNS CACHE HIT (Xray sniffing): $sniffedDomain -> ${cached.map { it.hostAddress }} (served from cache)")
                    return true
                }
                
                serviceScope.launch {
                    try {
                        val addresses = forwardDnsQueryToSystemCacheServer(
                            context = context,
                            domain = sniffedDomain,
                            systemDnsCacheServer = systemDnsCacheServer
                        )
                        if (addresses.isNotEmpty()) {
                            DnsCacheManager.saveToCache(sniffedDomain, addresses)
                            Log.d(TAG, "💾 DNS cached from Xray sniffing (via SystemDnsCacheServer): $sniffedDomain -> ${addresses.map { it.hostAddress }}")
                        } else {
                            Log.w(TAG, "⚠️ DNS resolution failed for $sniffedDomain (SystemDnsCacheServer with DoH fallback)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error caching DNS from sniffing: $sniffedDomain", e)
                    }
                }
            }
            
            val dnsResponsePattern = Regex("""(?:A\s+record|resolved|answer).*?([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,}).*?(\d+\.\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)
            dnsResponsePattern.find(logLine)?.let { matchResult ->
                val domain = matchResult.groupValues[1]
                val ip = matchResult.groupValues[2]
                
                if (domain.isNotEmpty() && ip.isNotEmpty()) {
                    serviceScope.launch {
                        try {
                            val addresses = forwardDnsQueryToSystemCacheServer(
                                context = context,
                                domain = domain,
                                systemDnsCacheServer = systemDnsCacheServer
                            )
                            if (addresses.isNotEmpty()) {
                                DnsCacheManager.saveToCache(domain, addresses)
                                Log.d(TAG, "💾 DNS cached from Xray DNS response (via SystemDnsCacheServer): $domain -> ${addresses.map { it.hostAddress }}")
                            } else {
                                val address = InetAddress.getByName(ip)
                                DnsCacheManager.saveToCache(domain, listOf(address))
                                Log.d(TAG, "💾 DNS cached from Xray DNS response (direct IP fallback): $domain -> $ip")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error caching DNS response: $domain -> $ip", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fail - DNS cache integration should not break logging
        }
        
        return initialized
    }
    
    /**
     * Forward DNS query to SystemDnsCacheServer for resolution.
     */
    suspend fun forwardDnsQueryToSystemCacheServer(
        context: Context,
        domain: String,
        systemDnsCacheServer: SystemDnsCacheServer?
    ): List<InetAddress> {
        return try {
            var server = systemDnsCacheServer
            if (server?.isRunning() != true) {
                Log.d(TAG, "SystemDnsCacheServer not running, attempting to start...")
                if (server == null) {
                    server = SystemDnsCacheServer.getInstance(context)
                }
                server?.start()
                
                delay(100)
                
                if (server?.isRunning() != true) {
                    Log.w(TAG, "SystemDnsCacheServer failed to start, cannot forward DNS query: $domain")
                    return server?.resolveDomain(domain) ?: emptyList()
                }
            }
            
            // Increased timeout to 5000ms to accommodate Happy Eyeballs algorithm
            // which may need to try multiple DNS servers with wave delays (400ms)
            // and adaptive timeouts (max 3000ms per server)
            val maxWaitTimeMs = 5000L
            val startTime = System.currentTimeMillis()
            
            val result = withTimeoutOrNull(maxWaitTimeMs) {
                server?.resolveDomain(domain) ?: emptyList()
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            if (result == null) {
                Log.w(TAG, "⚠️ DNS resolution timeout for $domain after ${elapsedTime}ms (max: ${maxWaitTimeMs}ms)")
                return emptyList()
            }
            
            if (result.isNotEmpty()) {
                Log.d(TAG, "✅ DNS resolved for $domain in ${elapsedTime}ms -> ${result.map { it.hostAddress }}")
            } else {
                Log.w(TAG, "⚠️ DNS resolution returned empty result for $domain after ${elapsedTime}ms")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding DNS query to SystemDnsCacheServer: $domain", e)
            emptyList()
        }
    }
    
    /**
     * Check if a string is a valid IP address (IPv4 or IPv6).
     */
    fun isValidIpAddress(address: String): Boolean {
        return try {
            val addr = InetAddress.getByName(address)
            address.split(".").size == 4 && address.split(".").all { it.toIntOrNull() in 0..255 } ||
            address.contains(":")
        } catch (e: Exception) {
            false
        }
    }
    
    // ============================================================================
    // UDP Error Handling Utilities
    // ============================================================================
    
    /**
     * UDP error categories for better tracking and analysis.
     */
    enum class UdpErrorCategory {
        IDLE_TIMEOUT,
        SHUTDOWN,
        NORMAL_OPERATION,
        UNKNOWN
    }
    
    /**
     * UDP error tracking data class.
     */
    data class UdpErrorRecord(
        val timestamp: Long,
        val category: UdpErrorCategory,
        val logEntry: String,
        val context: UdpErrorContext
    )
    
    /**
     * Context information for UDP errors.
     */
    data class UdpErrorContext(
        val isShuttingDown: Boolean,
        val serviceUptime: Long,
        val timeSinceLastError: Long,
        val errorCountInWindow: Int
    )
    
    /**
     * UDP error pattern analysis.
     */
    data class UdpErrorPattern(
        val totalErrors: Int,
        val errorsByCategory: Map<UdpErrorCategory, Int>,
        val averageTimeBetweenErrors: Double,
        val errorRate: Double,
        val lastErrorTime: Long,
        val isRecovering: Boolean
    )
    
    /**
     * Detects UDP closed pipe errors from Xray logs with enhanced tracking and categorization.
     */
    fun detectUdpClosedPipeErrors(
        logEntry: String,
        isStopping: Boolean,
        serviceStartTime: Long,
        lastUdpErrorTime: Long,
        udpErrorCount: Int,
        udpErrorHistory: MutableList<UdpErrorRecord>,
        maxErrorHistorySize: Int,
        onErrorDetected: (UdpErrorRecord, UdpErrorPattern) -> Unit
    ): Long {
        val upperEntry = logEntry.uppercase()
        
        if (upperEntry.contains("TRANSPORT/INTERNET/UDP") && 
            (upperEntry.contains("FAILED TO WRITE") || upperEntry.contains("FAILED TO HANDLE")) &&
            (upperEntry.contains("CLOSED PIPE") || upperEntry.contains("READ/WRITE ON CLOSED"))) {
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastError = if (lastUdpErrorTime > 0) currentTime - lastUdpErrorTime else Long.MAX_VALUE
            
            val category = categorizeUdpError(timeSinceLastError, isStopping)
            
            val context = UdpErrorContext(
                isShuttingDown = isStopping,
                serviceUptime = if (serviceStartTime > 0) currentTime - serviceStartTime else 0L,
                timeSinceLastError = timeSinceLastError,
                errorCountInWindow = udpErrorCount
            )
            
            val errorRecord = UdpErrorRecord(
                timestamp = currentTime,
                category = category,
                logEntry = logEntry,
                context = context
            )
            
            synchronized(udpErrorHistory) {
                udpErrorHistory.add(errorRecord)
                if (udpErrorHistory.size > maxErrorHistorySize) {
                    udpErrorHistory.removeAt(0)
                }
            }
            
            val newErrorCount = if (timeSinceLastError < 12000) {
                udpErrorCount + 1
            } else {
                1
            }
            
            val pattern = analyzeUdpErrorPattern(
                udpErrorHistory = udpErrorHistory,
                lastUdpErrorTime = currentTime
            )
            
            onErrorDetected(errorRecord, pattern)
            
            return currentTime
        }
        
        return lastUdpErrorTime
    }
    
    /**
     * Categorizes UDP error based on context.
     */
    fun categorizeUdpError(timeSinceLastError: Long, isStopping: Boolean): UdpErrorCategory {
        return when {
            isStopping -> UdpErrorCategory.SHUTDOWN
            timeSinceLastError > 50000 && timeSinceLastError < 65000 -> UdpErrorCategory.IDLE_TIMEOUT
            timeSinceLastError < 12000 -> UdpErrorCategory.NORMAL_OPERATION
            else -> UdpErrorCategory.UNKNOWN
        }
    }
    
    /**
     * Analyzes UDP error pattern from history.
     */
    fun analyzeUdpErrorPattern(
        udpErrorHistory: MutableList<UdpErrorRecord>,
        lastUdpErrorTime: Long
    ): UdpErrorPattern {
        synchronized(udpErrorHistory) {
            if (udpErrorHistory.isEmpty()) {
                return UdpErrorPattern(
                    totalErrors = 0,
                    errorsByCategory = emptyMap(),
                    averageTimeBetweenErrors = 0.0,
                    errorRate = 0.0,
                    lastErrorTime = 0L,
                    isRecovering = false
                )
            }
            
            val now = System.currentTimeMillis()
            val oneMinuteAgo = now - 60000L
            val recentErrors = udpErrorHistory.filter { it.timestamp > oneMinuteAgo }
            
            val errorsByCategory = recentErrors.groupingBy { it.category }.eachCount()
            
            val timeDeltas = if (recentErrors.size > 1) {
                recentErrors.sortedBy { it.timestamp }.zipWithNext { a, b ->
                    b.timestamp - a.timestamp
                }
            } else {
                emptyList()
            }
            val avgTimeBetween = if (timeDeltas.isNotEmpty()) {
                timeDeltas.average()
            } else {
                0.0
            }
            
            val errorRate = if (recentErrors.isNotEmpty()) {
                val oldestError = recentErrors.minByOrNull { it.timestamp }?.timestamp ?: now
                val timeSpan = now - oldestError
                if (timeSpan > 0) {
                    (recentErrors.size * 60000.0) / timeSpan
                } else {
                    recentErrors.size.toDouble()
                }
            } else {
                0.0
            }
            
            val thirtySecondsAgo = now - 30000L
            val isRecovering = recentErrors.none { it.timestamp > thirtySecondsAgo }
            
            return UdpErrorPattern(
                totalErrors = recentErrors.size,
                errorsByCategory = errorsByCategory,
                averageTimeBetweenErrors = avgTimeBetween,
                errorRate = errorRate,
                lastErrorTime = lastUdpErrorTime,
                isRecovering = isRecovering
            )
        }
    }
    
    /**
     * Handles UDP error based on pattern analysis.
     */
    fun handleUdpErrorWithPattern(
        errorRecord: UdpErrorRecord,
        pattern: UdpErrorPattern,
        isStopping: Boolean,
        telegramNotificationManager: TelegramNotificationManager?,
        serviceScope: CoroutineScope,
        onRecoveryTriggered: (UdpErrorRecord, UdpErrorPattern) -> Unit
    ) {
        when {
            pattern.errorRate > 10 && !isStopping -> {
                Log.e(TAG, "🚨 CRITICAL: Very high UDP error rate (${pattern.errorRate.toInt()}/min) - ${errorRecord.category}")
                Log.e(TAG, "Error details: ${errorRecord.logEntry}")
                Log.e(TAG, "Pattern: ${pattern.errorsByCategory}")
                
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "🚨 Critical UDP Error Rate\n\n" +
                            "Error rate: ${pattern.errorRate.toInt()}/min\n" +
                            "Category: ${errorRecord.category}\n" +
                            "Errors: ${pattern.errorsByCategory}\n\n" +
                            "Recent error: ${errorRecord.logEntry.take(200)}"
                        )
                    }
                }
            }
            
            pattern.errorRate > 5 && !isStopping -> {
                Log.w(TAG, "⚠️ FREQUENT UDP CLOSED PIPE ERRORS (${pattern.errorRate.toInt()}/min) - ${errorRecord.category}")
                Log.w(TAG, "Most recent: ${errorRecord.logEntry}")
                Log.w(TAG, "Pattern: ${pattern.errorsByCategory}")
            }
            
            isStopping -> {
                Log.d(TAG, "UDP error during shutdown (expected): ${errorRecord.category}")
            }
            
            else -> {
                Log.d(TAG, "UDP closed pipe error (normal race condition): ${errorRecord.category}")
            }
        }
        
        if (pattern.errorRate > 5 && !isStopping && !pattern.isRecovering) {
            onRecoveryTriggered(errorRecord, pattern)
        }
    }
    
    /**
     * Notifies telemetry about UDP error.
     */
    fun notifyUdpErrorToTelemetry(errorRecord: UdpErrorRecord, pattern: UdpErrorPattern) {
        if (pattern.errorRate > 5) {
            Log.d(TAG, "UDP error telemetry: rate=${pattern.errorRate.toInt()}/min, " +
                    "category=${errorRecord.category}, " +
                    "totalErrors=${pattern.totalErrors}, " +
                    "byCategory=${pattern.errorsByCategory}")
        }
    }
    
    /**
     * Calculate exponential backoff delay for recovery attempts.
     */
    fun calculateRecoveryBackoff(attempt: Int): Long {
        val backoffSeconds = (1 shl attempt).coerceAtMost(30)
        return backoffSeconds * 1000L
    }
    
    
    /**
     * UDP statistics from native tunnel.
     */
    data class UdpStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * UDP connection health status.
     */
    data class UdpConnectionHealth(
        val isHealthy: Boolean,
        val packetRate: Double,
        val byteRate: Double,
        val timeSinceLastActivity: Long,
        val isIdle: Boolean,
        val recommendation: String
    )
    
    
    /**
     * Analyze UDP connection health from statistics.
     */
    fun analyzeUdpConnectionHealth(
        currentStats: UdpStats,
        lastStats: UdpStats?,
        lastStatsTime: Long
    ): Pair<UdpConnectionHealth, Pair<UdpStats, Long>> {
        val now = System.currentTimeMillis()
        
        val packetRate: Double
        val byteRate: Double
        val timeSinceLastActivity: Long
        
        if (lastStats != null && lastStatsTime > 0) {
            val timeDelta = (now - lastStatsTime) / 1000.0
            
            if (timeDelta > 0) {
                val packetDelta = (currentStats.txPackets + currentStats.rxPackets) - 
                                 (lastStats.txPackets + lastStats.rxPackets)
                val byteDelta = (currentStats.txBytes + currentStats.rxBytes) - 
                               (lastStats.txBytes + lastStats.rxBytes)
                
                packetRate = packetDelta / timeDelta
                byteRate = byteDelta / timeDelta
                
                timeSinceLastActivity = if (packetDelta == 0L && byteDelta == 0L) {
                    now - lastStatsTime
                } else {
                    0L
                }
            } else {
                packetRate = 0.0
                byteRate = 0.0
                timeSinceLastActivity = 0L
            }
        } else {
            packetRate = 0.0
            byteRate = 0.0
            timeSinceLastActivity = 0L
        }
        
        val isIdle = timeSinceLastActivity > 40000L
        
        val isHealthy = when {
            isIdle && timeSinceLastActivity > 55000L -> false
            packetRate < 0 -> false
            else -> true
        }
        
        val recommendation = when {
            isIdle && timeSinceLastActivity > 55000L -> "Connection idle for ${timeSinceLastActivity / 1000}s, cleanup imminent"
            isIdle -> "Connection idle for ${timeSinceLastActivity / 1000}s, monitor for cleanup"
            packetRate > 0 && byteRate > 0 -> "Active: ${packetRate.toInt()} pkt/s, ${(byteRate / 1024).toInt()} KB/s"
            else -> "Connection established, waiting for activity"
        }
        
        val health = UdpConnectionHealth(
            isHealthy = isHealthy,
            packetRate = packetRate,
            byteRate = byteRate,
            timeSinceLastActivity = timeSinceLastActivity,
            isIdle = isIdle,
            recommendation = recommendation
        )
        
        return Pair(health, Pair(currentStats, now))
    }
    
    
    /**
     * Start proactive UDP connection monitoring.
     */
    fun startUdpMonitoring(
        isStopping: Boolean,
        serviceScope: CoroutineScope,
        isActive: Boolean,
        onMonitoringCycle: suspend (UdpStats?) -> Unit
    ): kotlinx.coroutines.Job {
        val job = serviceScope.launch {
            while (isActive && !isStopping) {
                try {
                    delay(15000L)
                    ensureActive()
                    
                    // Native UDP stats no longer available (TProxy removed)
                    Log.d(TAG, "UDP monitoring: Native stats unavailable (TProxy removed)")
                    continue
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in UDP monitoring: ${e.message}", e)
                    }
                }
            }
        }
        
        Log.d(TAG, "Started proactive UDP connection monitoring (every 15 seconds)")
        return job
    }
    
    /**
     * Stop UDP monitoring.
     */
    fun stopUdpMonitoring(
        udpMonitoringJob: kotlinx.coroutines.Job?,
        onStopped: () -> Unit
    ) {
        udpMonitoringJob?.cancel()
        onStopped()
        Log.d(TAG, "Stopped UDP monitoring")
    }
    
    /**
     * Handle idle UDP connection proactively.
     */
    fun handleIdleUdpConnection(health: UdpConnectionHealth) {
        if (health.timeSinceLastActivity > 55000L && health.timeSinceLastActivity < 65000L) {
            Log.d(TAG, "UDP connection near timeout (${health.timeSinceLastActivity / 1000}s idle), " +
                    "preparing for potential cleanup race condition")
            
            // Native tunnel notification removed (TProxy removed)
        }
    }
    
    // ============================================================================
    // Connection Reset Error Utilities
    // ============================================================================
    
    /**
     * Detect connection reset errors in Xray-core logs.
     */
    fun detectConnectionResetErrors(
        context: Context,
        logEntry: String,
        connectionResetErrorCount: Int,
        lastConnectionResetTime: Long,
        connectionResetThreshold: Int,
        connectionResetWindowMs: Long,
        serviceScope: CoroutineScope,
        onThresholdExceeded: (Int, Long) -> Unit
    ): Pair<Int, Long> {
        val upperEntry = logEntry.uppercase()
        
        val isConnectionReset = (upperEntry.contains("CONNECTION RESET") || 
                               upperEntry.contains("RESET BY PEER") ||
                               (upperEntry.contains("FAILED TO TRANSFER") && upperEntry.contains("REQUEST PAYLOAD"))) &&
                               (upperEntry.contains("OUTBOUND") || upperEntry.contains("PROXY/VLESS") || 
                                upperEntry.contains("PROXY/VMESS") || upperEntry.contains("PROXY/TROJAN"))
        
        if (isConnectionReset) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastError = if (lastConnectionResetTime > 0) {
                currentTime - lastConnectionResetTime
            } else {
                connectionResetWindowMs + 1
            }
            
            val newCount: Int
            val newTime: Long
            
            if (timeSinceLastError > connectionResetWindowMs) {
                newCount = 1
                newTime = currentTime
                Log.w(TAG, "⚠️ Connection reset detected: $logEntry")
            } else {
                newCount = connectionResetErrorCount + 1
                newTime = currentTime
                
                if (newCount >= connectionResetThreshold) {
                    Log.e(TAG, "❌ FREQUENT CONNECTION RESET ERRORS (count: $newCount in last minute)")
                    Log.e(TAG, "Xray-core is having trouble connecting to SOCKS5 tunnel")
                    Log.e(TAG, "Most recent error: $logEntry")
                    
                    val errorIntent = Intent("com.hyperxray.an.ERROR")
                    errorIntent.setPackage(context.packageName)
                    errorIntent.putExtra(
                        "error_message",
                        "Connection reset errors detected ($newCount in last minute). " +
                        "Xray-core is having trouble connecting to SOCKS5 tunnel. " +
                        "Attempting automatic recovery..."
                    )
                    errorIntent.putExtra("error_type", "connection_reset")
                    errorIntent.putExtra("error_count", newCount)
                    context.sendBroadcast(errorIntent)
                    
                    onThresholdExceeded(newCount, newTime)
                } else {
                    Log.w(TAG, "⚠️ Connection reset error (count: $newCount/$connectionResetThreshold): $logEntry")
                }
            }
            
            return Pair(newCount, newTime)
        }
        
        return Pair(connectionResetErrorCount, lastConnectionResetTime)
    }
    
    // ============================================================================
    // Performance & Stats Utilities
    // ============================================================================
    
    /**
     * Calculates adaptive polling interval based on traffic state.
     */
    fun calculateAdaptivePollingInterval(stats: CoreStatsState?): Long {
        if (stats == null) {
            return 60000L
        }
        
        val totalThroughput = stats.uplinkThroughput + stats.downlinkThroughput
        val highTrafficThreshold = 100_000.0
        val lowTrafficThreshold = 10_000.0
        
        return when {
            totalThroughput > highTrafficThreshold -> 10000L
            totalThroughput > lowTrafficThreshold -> 30000L
            else -> 60000L
        }
    }
    
    /**
     * Broadcast instance status to MainViewModel for dashboard updates.
     * 
     * DEPRECATED: Multi-instance support removed. This function is kept for compatibility
     * but does nothing. Use XrayCoreManager for single-instance status.
     */
    @Deprecated("Multi-instance support removed. Use XrayCoreManager instead.", ReplaceWith(""))
    fun broadcastInstanceStatus(
        context: Context,
        multiXrayCoreManager: Any? // Changed to Any? to avoid import
    ) {
        // No-op: Multi-instance support removed
        Log.d(TAG, "broadcastInstanceStatus() called but multi-instance support is removed")
    }
    
    /**
     * Estimate jitter from latency (simple variance approximation).
     */
    fun estimateJitter(currentLatency: Double, lastLatency: Double?): Pair<Double?, Double> {
        return try {
            val jitter = if (lastLatency != null) {
                abs(currentLatency - lastLatency)
            } else {
                null
            }
            Pair(jitter, currentLatency)
        } catch (e: Exception) {
            Log.w(TAG, "Error estimating jitter: ${e.message}")
            Pair(null, currentLatency)
        }
    }
    
    /**
     * Estimate latency from CoreStatsState (heuristic based on goroutines and memory).
     */
    fun estimateLatencyFromStats(stats: CoreStatsState): Double {
        var latency = 50.0
        
        if (stats.numGoroutine > 100) {
            latency += (stats.numGoroutine - 100) * 0.5
        }
        
        if (stats.alloc > 100 * 1024 * 1024) {
            latency += ((stats.alloc - 100 * 1024 * 1024) / (1024 * 1024)) * 0.1
        }
        
        return latency.coerceIn(10.0, 2000.0)
    }
    
    /**
     * Estimate latency from system stats (heuristic).
     */
    fun estimateLatencyFromSystemStats(systemStats: SysStatsResponse): Double {
        var latency = 50.0
        
        val numGoroutine = systemStats.numGoroutine
        if (numGoroutine > 100) {
            latency += (numGoroutine - 100) * 0.5
        }
        
        val alloc = systemStats.alloc
        if (alloc > 100 * 1024 * 1024) {
            latency += ((alloc - 100 * 1024 * 1024) / (1024 * 1024)) * 0.1
        }
        
        return latency.coerceIn(10.0, 2000.0)
    }
    
    // ============================================================================
    // SNI & TLS Processing Utilities
    // ============================================================================
    
    /**
     * Process SNI from Xray logs and make routing decisions using ONNX model.
     */
    fun processSNIFromLog(
        context: Context,
        logEntry: String,
        serviceScope: CoroutineScope,
        onnxRuntimeManagerReady: Boolean,
        coreStatsState: CoreStatsState?,
        lastLatency: Double?,
        onLatencyUpdated: (Double?) -> Unit,
        applyRoutingDecision: (String, Int) -> Unit
    ) {
        val sni = try {
            extractSNI(logEntry)
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting SNI: ${e.message}")
            null
        }
        
        if (sni == null || sni.isEmpty()) {
            if (logEntry.contains("instagram", ignoreCase = true) || 
                logEntry.contains("tiktok", ignoreCase = true) ||
                logEntry.contains(".ig.", ignoreCase = true) ||
                logEntry.contains("i.instagram", ignoreCase = true) ||
                logEntry.contains("api.instagram", ignoreCase = true) ||
                logEntry.contains("graph.instagram", ignoreCase = true)) {
                Log.d(TAG, "Found Instagram/TikTok keyword in log but SNI not extracted: ${logEntry.take(200)}")
            }
            return
        }
        
        Log.d(TAG, "Processing SNI: $sni from log entry")
        
        try {
            if (com.hyperxray.an.optimizer.OrtHolder.isReady() || 
                com.hyperxray.an.optimizer.OrtHolder.init(context)) {
                processSNIWithAutoLearner(
                    context = context,
                    sni = sni,
                    logEntry = logEntry,
                    coreStatsState = coreStatsState,
                    serviceScope = serviceScope,
                    getRealTimeMetrics = { Triple(0.0, 0.0, false) },
                    getNetworkContext = { null },
                    estimateLatencyFromStats = { 0.0 },
                    estimateJitter = { null },
                    onFeedbackLogged = {}
                )
                Log.d(TAG, "SNI processed with auto-learner: $sni")
                return
            } else {
                Log.d(TAG, "Auto-learner not ready, falling back to OnnxRuntimeManager")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-learner processing failed, falling back to OnnxRuntimeManager: ${e.message}")
        }
        
        if (!onnxRuntimeManagerReady) {
            Log.w(TAG, "OnnxRuntimeManager not ready, skipping SNI processing for: $sni")
            return
        }
        
        try {
            val alpn = extractALPN(logEntry) ?: "h2"
            Log.d(TAG, "SNI: $sni, ALPN: $alpn")
            
            val features = TLSFeatureEncoder.encode(sni, alpn)
            Log.d(TAG, "Encoded TLS features for SNI: $sni")
            
            val (serviceTypeIndex, routingDecisionIndex) = OnnxRuntimeManager.predict(features)
            Log.d(TAG, "ONNX inference result for SNI $sni: service=$serviceTypeIndex, route=$routingDecisionIndex")
            
            OptimizerLogger.logDecision(sni, serviceTypeIndex, routingDecisionIndex)
            
            applyRoutingDecision(sni, routingDecisionIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SNI from log: ${e.message}", e)
        }
    }
    
    /**
     * Process SNI using auto-learning optimizer (v9).
     */
    fun processSNIWithAutoLearner(
        context: Context,
        sni: String,
        logEntry: String,
        coreStatsState: CoreStatsState?,
        serviceScope: CoroutineScope,
        getRealTimeMetrics: suspend () -> Triple<Double, Double, Boolean>,
        getNetworkContext: () -> String?,
        estimateLatencyFromStats: (CoreStatsState) -> Double,
        estimateJitter: (Double) -> Double?,
        onFeedbackLogged: () -> Unit
    ) {
        try {
            if (!com.hyperxray.an.optimizer.OrtHolder.isReady()) {
                com.hyperxray.an.optimizer.OrtHolder.init(context)
            }
            
            if (!com.hyperxray.an.optimizer.OrtHolder.isReady()) {
                return
            }
            
            serviceScope.launch {
                val (latencyMs, throughputKbps, success) = getRealTimeMetrics()
                
                val decision = com.hyperxray.an.optimizer.Inference.optimizeSni(
                    context = context,
                    sni = sni,
                    latencyMs = latencyMs,
                    throughputKbps = throughputKbps
                )
                
                Log.d(TAG, "Auto-learner decision: sni=$sni, svc=${decision.svcClass}, route=${decision.routeDecision}, alpn=${decision.alpn}, latency=${latencyMs}ms, throughput=${throughputKbps}kbps")
                
                val networkContext = getNetworkContext()
                val rtt = coreStatsState?.let { estimateLatencyFromStats(it) } ?: latencyMs
                val jitter = estimateJitter(latencyMs)
                
                com.hyperxray.an.optimizer.LearnerLogger.logFeedback(
                    context = context,
                    sni = sni,
                    svcClass = decision.svcClass,
                    routeDecision = decision.routeDecision,
                    success = success,
                    latencyMs = latencyMs.toFloat(),
                    throughputKbps = throughputKbps.toFloat(),
                    alpn = decision.alpn,
                    rtt = rtt,
                    jitter = jitter,
                    networkType = networkContext
                )
                
                onFeedbackLogged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in auto-learner processing: ${e.message}", e)
        }
    }
    
    /**
     * Extract ALPN protocol from log entry.
     */
    fun extractALPN(logEntry: String): String? {
        val alpnPatterns = listOf(
            Regex("""alpn\s*[=:]\s*([^\s,\]]+)""", RegexOption.IGNORE_CASE),
            Regex("""ALPN\s*[=:]\s*([^\s,\]]+)""", RegexOption.IGNORE_CASE),
            Regex("""protocol\s*[=:]\s*(h2|h3|http/1\.1)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in alpnPatterns) {
            pattern.find(logEntry)?.let {
                val alpn = it.groupValues[1].trim().lowercase()
                if (alpn.isNotEmpty() && (alpn == "h2" || alpn == "h3" || alpn == "http/1.1")) {
                    return when (alpn) {
                        "h3" -> "h3"
                        "h2" -> "h2"
                        else -> "h2"
                    }
                }
            }
        }
        
        return null
    }
    
    // ============================================================================
    // Network Context Utilities
    // ============================================================================
    
    /**
     * Get network context (WiFi/4G/5G).
     */
    fun getNetworkContext(context: Context): String? {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork ?: return null
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
            
            when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val has5G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val nrCapability = NetworkCapabilities::class.java.getField("NET_CAPABILITY_NR")
                            val nrValue = nrCapability.getInt(null)
                            networkCapabilities.hasCapability(nrValue)
                        } catch (e: Exception) {
                            val downstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
                            downstreamKbps > 100000
                        }
                    } else {
                        val downstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
                        downstreamKbps > 100000
                    }
                    if (has5G) "5G" else "4G"
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting network context: ${e.message}")
            null
        }
    }
    
    // ============================================================================
    // SOCKS5 Health Utilities
    // ============================================================================
    
    /**
     * Start periodic SOCKS5 health check to ensure it remains ready.
     */
    fun startPeriodicSocks5HealthCheck(
        context: Context,
        prefs: Preferences,
        isStopping: Boolean,
        socks5ReadinessChecked: Boolean,
        systemDnsCacheServer: SystemDnsCacheServer?,
        serviceScope: CoroutineScope,
        isActive: Boolean,
        onReadinessChanged: (Boolean) -> Unit,
        checkSocks5Readiness: suspend (Preferences) -> Unit
    ) {
        serviceScope.launch {
            while (isActive && !isStopping) {
                try {
                    delay(30000L)
                    ensureActive()
                    
                    val socksPort = prefs.socksPort
                    val socksAddress = prefs.socksAddress
                    
                    val isReady = Socks5ReadinessChecker.isSocks5Ready(
                        context = context,
                        address = socksAddress,
                        port = socksPort
                    )
                    
                    if (!isReady && socks5ReadinessChecked) {
                        Log.w(TAG, "⚠️ SOCKS5 became unavailable during periodic check - attempting recovery")
                        onReadinessChanged(false)
                        checkSocks5Readiness(prefs)
                    } else if (isReady && !socks5ReadinessChecked) {
                        Log.i(TAG, "✅ SOCKS5 recovered - updating readiness state")
                        onReadinessChanged(true)
                        
                        systemDnsCacheServer?.setSocks5Proxy(socksAddress, socksPort)
                        Log.d(TAG, "✅ SOCKS5 UDP proxy set for DNS (after recovery): $socksAddress:$socksPort")
                        
                        val readyIntent = Intent("com.hyperxray.an.SOCKS5_READY")
                        readyIntent.setPackage(context.packageName)
                        readyIntent.putExtra("socks_address", socksAddress)
                        readyIntent.putExtra("socks_port", socksPort)
                        readyIntent.putExtra("is_ready", true)
                        context.sendBroadcast(readyIntent)
                    }
                } catch (e: CancellationException) {
                    // Job cancellation is expected when service is stopping
                    // Re-throw to properly propagate cancellation
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in periodic SOCKS5 health check: ${e.message}", e)
                    }
                }
            }
        }
        
        Log.d(TAG, "Started periodic SOCKS5 health check (every 30 seconds)")
    }
    
    // ============================================================================
    // Routing Utilities
    // ============================================================================
    
    /**
     * Apply routing decision based on ONNX model output.
     */
    fun applyRoutingDecision(sni: String, routingDecisionIndex: Int) {
        when (routingDecisionIndex) {
            0 -> {
                Log.d(TAG, "Routing decision: proxy for $sni")
            }
            1 -> {
                Log.d(TAG, "Routing decision: direct for $sni")
            }
            2 -> {
                Log.d(TAG, "Routing decision: optimized for $sni")
            }
        }
    }
    
    /**
     * Process sticky routing from Xray logs.
     */
    /**
     * Process sticky routing from log line.
     * 
     * DEPRECATED: Multi-instance sticky routing removed. This function is kept for compatibility
     * but does nothing since we now use a single instance.
     */
    @Deprecated("Multi-instance sticky routing removed. Single instance does not need sticky routing.", ReplaceWith(""))
    fun processStickyRoutingFromLog(
        logLine: String,
        prefs: Preferences,
        multiXrayCoreManager: Any?, // Changed to Any? to avoid import
        serviceScope: CoroutineScope,
        isActive: Boolean
    ) {
        // No-op: Sticky routing not needed for single instance
        Log.d(TAG, "processStickyRoutingFromLog() called but sticky routing is removed (single instance)")
    }
    
    /**
     * Update sticky routing cache for domain-IP mapping.
     * 
     * DEPRECATED: Multi-instance sticky routing removed. This function is kept for compatibility
     * but does nothing since we now use a single instance.
     */
    @Deprecated("Multi-instance sticky routing removed. Single instance does not need sticky routing.", ReplaceWith(""))
    fun updateStickyRoutingForDomainIp(
        domain: String,
        ip: String,
        prefs: Preferences,
        multiXrayCoreManager: Any?, // Changed to Any? to avoid import
        serviceScope: CoroutineScope
    ) {
        // No-op: Sticky routing not needed for single instance
        Log.d(TAG, "updateStickyRoutingForDomainIp() called but sticky routing is removed (single instance)")
    }
    
    /**
     * Periodic cleanup of stale routing cache entries.
     * 
     * DEPRECATED: Multi-instance routing cache removed. This function is kept for compatibility
     * but does nothing since we now use a single instance.
     */
    @Deprecated("Multi-instance routing cache removed. Single instance does not need routing cache cleanup.", ReplaceWith(""))
    fun startRoutingCacheCleanup(
        prefs: Preferences,
        multiXrayCoreManager: Any?, // Changed to Any? to avoid import
        isStopping: Boolean,
        serviceScope: CoroutineScope,
        isActive: Boolean
    ) {
        // No-op: Routing cache cleanup not needed for single instance
        Log.d(TAG, "startRoutingCacheCleanup() called but routing cache cleanup is removed (single instance)")
    }
    
    // ============================================================================
    // Notification & Exit Utilities
    // ============================================================================
    
    /**
     * Exit handler - broadcasts stop intent and stops service.
     */
    fun exit(
        context: Context,
        telegramNotificationManager: TelegramNotificationManager?,
        serviceScope: CoroutineScope,
        stopSelf: () -> Unit
    ) {
        val stopIntent = Intent("com.hyperxray.an.STOP")
        stopIntent.setPackage(context.packageName)
        context.sendBroadcast(stopIntent)
        
        telegramNotificationManager?.let { manager ->
            serviceScope.launch {
                manager.notifyVpnStatus(false)
            }
        }
        
        stopSelf()
    }


    // ============================================================================
    // Connection Reset Recovery Utilities
    // ============================================================================
    
    suspend fun handleConnectionResetRecovery(
        context: Context,
        serviceScope: CoroutineScope,
        isStopping: Boolean,
        prefs: Preferences,
        onRecoverySuccess: () -> Unit,
        onRecoveryFailure: (String) -> Unit
    ) {
        if (isStopping) {
            return
        }
        
        try {
            Log.i(TAG, "Attempting connection reset recovery...")
            // Recovery logic can be implemented here
            // For now, just log and call success callback
            onRecoverySuccess()
        } catch (e: Exception) {
            val errorMsg = "Connection reset recovery failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            onRecoveryFailure(errorMsg)
        }
    }

    // ============================================================================
    // UDP Error Recovery Utilities
    // ============================================================================

    suspend fun triggerUdpErrorRecovery(
        errorRecord: UdpErrorRecord,
        pattern: UdpErrorPattern,
        isStopping: Boolean,
        udpRecoveryAttempts: Int,
        maxRecoveryAttempts: Int,
        recoveryCooldownMs: Long,
        lastUdpRecoveryTime: Long,
        serviceScope: CoroutineScope,
        xrayProcess: Process?,
        udpErrorHistory: MutableList<UdpErrorRecord>,
        onUdpRecoveryAttemptsUpdated: (Int) -> Unit,
        onLastUdpRecoveryTimeUpdated: (Long) -> Unit,
        onUdpErrorCountReset: () -> Unit,
        onCriticalRecoveryTriggered: (UdpErrorPattern) -> Unit
    ) {
        val now = System.currentTimeMillis()
        
        if (lastUdpRecoveryTime > 0 && (now - lastUdpRecoveryTime) < recoveryCooldownMs) {
            val remainingCooldown = recoveryCooldownMs - (now - lastUdpRecoveryTime)
            Log.d(TAG, "UDP recovery cooldown active, ${remainingCooldown / 1000}s remaining")
            return
        }
        
        if (udpRecoveryAttempts >= maxRecoveryAttempts) {
            Log.w(TAG, "⚠️ UDP recovery: Max attempts ($maxRecoveryAttempts) reached, considering critical recovery")
            if (pattern.errorRate > 20 && !isStopping) {
                onCriticalRecoveryTriggered(pattern)
            }
            return
        }
        
        val backoffDelay = calculateRecoveryBackoff(udpRecoveryAttempts)
        Log.d(TAG, "Triggering UDP error recovery (attempt ${udpRecoveryAttempts + 1}/$maxRecoveryAttempts): " +
                "rate=${pattern.errorRate.toInt()}/min, category=${errorRecord.category}, backoff=${backoffDelay / 1000}s")
        
        delay(backoffDelay)
        
        if (isStopping) {
            return
        }
        
        try {
            val recoverySuccess = attemptUdpErrorRecovery(
                errorRecord = errorRecord,
                xrayProcess = xrayProcess,
                udpErrorHistory = udpErrorHistory,
                onUdpErrorCountReset = onUdpErrorCountReset
            )
            
            if (recoverySuccess) {
                onUdpRecoveryAttemptsUpdated(0)
                Log.i(TAG, "✅ UDP recovery successful")
            } else {
                onUdpRecoveryAttemptsUpdated(udpRecoveryAttempts + 1)
                onLastUdpRecoveryTimeUpdated(System.currentTimeMillis())
                Log.w(TAG, "⚠️ UDP recovery failed (attempt ${udpRecoveryAttempts + 1}/$maxRecoveryAttempts)")
            }
        } catch (e: Exception) {
            onUdpRecoveryAttemptsUpdated(udpRecoveryAttempts + 1)
            onLastUdpRecoveryTimeUpdated(System.currentTimeMillis())
            Log.e(TAG, "Error during UDP recovery: ${e.message}", e)
        }
    }

    suspend fun attemptUdpErrorRecovery(
        errorRecord: UdpErrorRecord,
        xrayProcess: Process?,
        udpErrorHistory: MutableList<UdpErrorRecord>,
        onUdpErrorCountReset: () -> Unit
    ): Boolean {
        Log.d(TAG, "Attempting UDP error recovery: category=${errorRecord.category}")
        
        try {
            // Native tunnel notification removed (TProxy removed)
            delay(500)
            
            if (xrayProcess == null || !xrayProcess.isAlive) {
                Log.w(TAG, "UDP recovery: Xray process is not alive, recovery not applicable")
                return false
            }
            
            synchronized(udpErrorHistory) {
                if (udpErrorHistory.size > 10) {
                    udpErrorHistory.subList(0, udpErrorHistory.size - 10).clear()
                }
            }
            
            onUdpErrorCountReset()
            // Native tunnel notification removed (TProxy removed)
            
            Log.i(TAG, "✅ UDP recovery steps completed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP recovery attempt: ${e.message}", e)
            return false
        }
    }

    suspend fun attemptCriticalUdpRecovery(
        pattern: UdpErrorPattern,
        isStopping: Boolean,
        serviceScope: CoroutineScope,
        stopXray: (String) -> Unit,
        startXray: () -> Unit,
        telegramNotificationManager: TelegramNotificationManager?,
        onUdpRecoveryAttemptsReset: () -> Unit
    ) {
        Log.e(TAG, "🚨 CRITICAL UDP recovery: Error rate ${pattern.errorRate.toInt()}/min, attempting Xray restart")
        
        if (pattern.errorRate > 20 && !isStopping) {
            try {
                onUdpRecoveryAttemptsReset()
                stopXray("UDP error rate too high (${pattern.errorRate}%), restarting service")
                delay(3000)
                startXray()
                
                Log.i(TAG, "✅ Critical UDP recovery: Xray restarted")
                
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "🚨 Critical UDP Recovery\n\n" +
                            "Xray restarted due to critical UDP errors.\n" +
                            "Error rate: ${pattern.errorRate.toInt()}/min\n" +
                            "Recovery action: Xray process restarted"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during critical UDP recovery: ${e.message}", e)
                
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "🚨 UDP Recovery Failed\n\n" +
                            "Failed to recover from critical UDP errors.\n" +
                            "Error: ${e.message}\n" +
                            "Error rate: ${pattern.errorRate.toInt()}/min"
                        )
                    }
                }
            }
        } else {
            Log.w(TAG, "Critical UDP recovery skipped: errorRate=${pattern.errorRate.toInt()}, isStopping=$isStopping")
        }
    }
}

