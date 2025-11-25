package com.hyperxray.an.common

import android.util.Log
import com.hyperxray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

object ConfigUtils {
    private const val TAG = "ConfigUtils"

    @Throws(JSONException::class)
    fun formatConfigContent(content: String): String {
        val jsonObject = JSONObject(content)
        (jsonObject["log"] as? JSONObject)?.apply {
            if (has("access") && optString("access") != "none") {
                remove("access")
                Log.d(TAG, "Removed log.access")
            }
            if (has("error") && optString("error") != "none") {
                remove("error")
                Log.d(TAG, "Removed log.error")
            }
        }
        var formattedContent = jsonObject.toString(2)
        formattedContent = formattedContent.replace("\\/", "/")
        return formattedContent
    }

    @Throws(JSONException::class)
    fun injectStatsService(prefs: Preferences, configContent: String): String {
        return injectStatsServiceWithPort(prefs, configContent, prefs.apiPort)
    }

    @Throws(JSONException::class)
    fun injectStatsServiceWithPort(prefs: Preferences, configContent: String, apiPort: Int): String {
        // Use optimized two-phase injection for better performance
        val commonConfig = injectCommonConfig(prefs, configContent)
        return injectApiPort(commonConfig, apiPort)
    }

    /**
     * Injects common configuration settings (without API port).
     * This function performs all config transformations that are identical for all instances.
     * Used for optimization when starting multiple instances - common config is injected once,
     * then each instance only needs API port injection.
     * 
     * @param prefs Preferences instance
     * @param configContent Original config content
     * @return Config JSON string with all common injections applied (API port not included)
     */
    @Throws(JSONException::class)
    fun injectCommonConfig(prefs: Preferences, configContent: String): String {
        val startTime = System.currentTimeMillis()
        val configSize = configContent.length
        Log.d(TAG, "=== Starting common config injection ===")
        Log.d(TAG, "Config size: $configSize bytes")
        Log.d(TAG, "Aggressive optimizations: ${prefs.aggressiveSpeedOptimizations}")
        Log.d(TAG, "Extreme optimizations: ${prefs.extremeRamCpuOptimizations}")
        AiLogHelper.i(TAG, "🔧 CONFIG INJECT: Starting common config injection (size: ${configSize} bytes, aggressive: ${prefs.aggressiveSpeedOptimizations}, extreme: ${prefs.extremeRamCpuOptimizations})")
        
        val jsonObject = JSONObject(configContent)
        
        // CRITICAL FIX: Remove or fix invalid version field
        // Xray-core expects version to be a VersionConfig object, not a string
        // If version exists as string, remove it (Xray will use default)
        if (jsonObject.has("version")) {
            val versionValue = jsonObject.get("version")
            if (versionValue is String) {
                Log.w(TAG, "⚠️ Found invalid version field (string): '$versionValue' - removing (Xray will use default)")
                AiLogHelper.w(TAG, "⚠️ CONFIG INJECT: Found invalid version field (string): '$versionValue' - removing")
                jsonObject.remove("version")
            } else if (versionValue is JSONObject) {
                // Version is already an object - keep it
                Log.d(TAG, "Version field is valid object format, keeping")
                AiLogHelper.d(TAG, "✅ CONFIG INJECT: Version field is valid object format, keeping")
            } else {
                // Unknown format - remove it
                Log.w(TAG, "⚠️ Found invalid version field type: ${versionValue.javaClass.simpleName} - removing")
                AiLogHelper.w(TAG, "⚠️ CONFIG INJECT: Found invalid version field type: ${versionValue.javaClass.simpleName} - removing")
                jsonObject.remove("version")
            }
        } else {
            AiLogHelper.d(TAG, "✅ CONFIG INJECT: No version field found, using Xray default")
        }

        // Stats object (API object will be added later with port-specific injection)
        val statsStartTime = System.currentTimeMillis()
        jsonObject.put("stats", JSONObject())
        val statsDuration = System.currentTimeMillis() - statsStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Stats object added (duration: ${statsDuration}ms)")

        val policyStartTime = System.currentTimeMillis()
        val policyObject = JSONObject()
        val systemObject = JSONObject()
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        policyObject.put("system", systemObject)
        jsonObject.put("policy", policyObject)
        val policyDuration = System.currentTimeMillis() - policyStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Policy object added (duration: ${policyDuration}ms)")

        // Enable debug logging for detailed troubleshooting
        // We need to see all logs to properly diagnose UDP closed pipe issues
        val logStartTime = System.currentTimeMillis()
        val logObject = jsonObject.optJSONObject("log") ?: JSONObject()
        logObject.put("logLevel", "debug")
        jsonObject.put("log", logObject)
        val logDuration = System.currentTimeMillis() - logStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Debug logging enabled (duration: ${logDuration}ms)")

        // Enable sniffing for domain and protocol detection
        val sniffingStartTime = System.currentTimeMillis()
        enableSniffing(jsonObject)
        val sniffingDuration = System.currentTimeMillis() - sniffingStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Sniffing enabled (duration: ${sniffingDuration}ms)")

        // Enable DNS cache and resolver logging
        val dnsStartTime = System.currentTimeMillis()
        enableDnsLogging(jsonObject)
        val dnsDuration = System.currentTimeMillis() - dnsStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: DNS logging enabled (duration: ${dnsDuration}ms)")

        // Apply aggressive speed optimizations
        val speedOptStartTime = System.currentTimeMillis()
        applySpeedOptimizations(prefs, jsonObject)
        val speedOptDuration = System.currentTimeMillis() - speedOptStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Speed optimizations applied (duration: ${speedOptDuration}ms)")

        // Apply extreme RAM/CPU optimizations if enabled
        if (prefs.extremeRamCpuOptimizations) {
            Log.d(TAG, "Extreme RAM/CPU optimizations ENABLED - applying...")
            AiLogHelper.i(TAG, "🔧 CONFIG INJECT: Extreme RAM/CPU optimizations ENABLED - applying...")
            val extremeOptStartTime = System.currentTimeMillis()
            applyExtremeRamCpuOptimizations(prefs, jsonObject)
            val extremeOptDuration = System.currentTimeMillis() - extremeOptStartTime
            AiLogHelper.i(TAG, "✅ CONFIG INJECT: Extreme RAM/CPU optimizations applied (duration: ${extremeOptDuration}ms)")
        } else {
            Log.d(TAG, "Extreme RAM/CPU optimizations DISABLED")
            AiLogHelper.d(TAG, "ℹ️ CONFIG INJECT: Extreme RAM/CPU optimizations DISABLED")
        }

        // Apply bypass domain/IP routing rules
        val bypassStartTime = System.currentTimeMillis()
        applyBypassRoutingRules(prefs, jsonObject)
        val bypassDuration = System.currentTimeMillis() - bypassStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Bypass routing rules applied (duration: ${bypassDuration}ms)")

        // Ensure all outbounds have tags for stats collection
        ensureOutboundTags(jsonObject)
        
        // CRITICAL: Enable UDP support in dokodemo-door inbounds for TUN transparent proxy
        // This ensures Xray can receive both TCP and UDP packets from the TUN interface
        ensureUdpSupportInDokodemoInbounds(jsonObject)
        
        // CRITICAL: Configure UDP timeout settings to prevent closed pipe errors
        // This ensures UDP connections stay alive longer and are closed gracefully
        configureUdpTimeoutSettings(jsonObject)
        
        // NOTE: Port 53 routing rule removal disabled - causes startup issues
        // removePort53DnsRoutingRule(jsonObject)
        
        val serializeStartTime = System.currentTimeMillis()
        val finalConfig = jsonObject.toString(2)
        val serializeDuration = System.currentTimeMillis() - serializeStartTime
        val finalConfigSize = finalConfig.length
        val totalDuration = System.currentTimeMillis() - startTime
        Log.d(TAG, "=== Common config injection completed ===")
        Log.d(TAG, "Final config size: $finalConfigSize bytes")
        Log.d(TAG, "Total injection duration: ${totalDuration}ms")
        AiLogHelper.i(TAG, "✅ CONFIG INJECT COMPLETE: Common config injection completed (final size: ${finalConfigSize} bytes, serialize: ${serializeDuration}ms, total: ${totalDuration}ms)")
        
        // Log a sample of the final config to verify optimizations
        try {
            val finalJson = JSONObject(finalConfig)
            val policy = finalJson.optJSONObject("policy")
            val levels = policy?.optJSONObject("levels")
            val level0 = levels?.optJSONObject("0")
            val connection = level0?.optJSONObject("connection")
            val buffer = level0?.optJSONObject("buffer")
            
            Log.d(TAG, "Config verification:")
            Log.d(TAG, "  - Policy.levels.0.connection: ${connection?.toString()}")
            Log.d(TAG, "  - Policy.levels.0.buffer: ${buffer?.toString()}")
            AiLogHelper.d(TAG, "🔍 CONFIG INJECT: Verification - Policy.levels.0.connection: ${connection?.toString()}, buffer: ${buffer?.toString()}")
            
            val dns = finalJson.optJSONObject("dns")
            val dnsCache = dns?.optJSONObject("cache")
            Log.d(TAG, "  - DNS cache: ${dnsCache?.toString()}")
            AiLogHelper.d(TAG, "🔍 CONFIG INJECT: Verification - DNS cache: ${dnsCache?.toString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying config", e)
            AiLogHelper.e(TAG, "❌ CONFIG INJECT: Error verifying config: ${e.message}", e)
        }

        return finalConfig
    }

    /**
     * Injects API port configuration into a pre-processed config.
     * This is a lightweight operation that only modifies the API listen address.
     * Used for optimization when starting multiple instances - common config is injected once,
     * then each instance only needs this quick port injection.
     * 
     * @param configContent Pre-processed config content (from injectCommonConfig)
     * @param apiPort API port to inject
     * @return Config JSON string with API port injected
     */
    @Throws(JSONException::class)
    fun injectApiPort(configContent: String, apiPort: Int): String {
        val startTime = System.currentTimeMillis()
        AiLogHelper.d(TAG, "🔧 CONFIG API PORT: Injecting API port: $apiPort")
        val jsonObject = JSONObject(configContent)
        
        val apiObject = JSONObject()
        apiObject.put("tag", "api")
        apiObject.put("listen", "127.0.0.1:$apiPort")
        val servicesArray = org.json.JSONArray()
        servicesArray.put("StatsService")
        apiObject.put("services", servicesArray)

        jsonObject.put("api", apiObject)

        val finalConfig = jsonObject.toString(2)
        val duration = System.currentTimeMillis() - startTime
        AiLogHelper.d(TAG, "✅ CONFIG API PORT: API port injected (port: $apiPort, duration: ${duration}ms)")
        return finalConfig
    }

    /**
     * Enables sniffing in Xray config for domain and protocol detection.
     * Adds sniffing configuration to all inbound handlers.
     * Sniffing is used to detect the actual destination domain from traffic.
     */
    @Throws(JSONException::class)
    private fun enableSniffing(jsonObject: JSONObject) {
        val sniffingObject = JSONObject()
        sniffingObject.put("enabled", true)
        
        val destOverrideArray = org.json.JSONArray()
        destOverrideArray.put("http")
        destOverrideArray.put("tls")
        destOverrideArray.put("quic")
        destOverrideArray.put("fakedns")
        sniffingObject.put("destOverride", destOverrideArray)
        
        // Add sniffing to all inbound handlers
        // Xray uses "inbounds" (plural) in config
        val inboundArray = jsonObject.optJSONArray("inbounds") ?: jsonObject.optJSONArray("inbound")
        if (inboundArray != null) {
            for (i in 0 until inboundArray.length()) {
                val inbound = inboundArray.getJSONObject(i)
                // Only add sniffing if it doesn't already exist
                if (!inbound.has("sniffing")) {
                    inbound.put("sniffing", sniffingObject)
                } else {
                    // Update existing sniffing config to ensure it's enabled
                    val existingSniffing = inbound.getJSONObject("sniffing")
                    existingSniffing.put("enabled", true)
                    if (!existingSniffing.has("destOverride")) {
                        existingSniffing.put("destOverride", destOverrideArray)
                    }
                }
            }
            // Use the same key that was found
            if (jsonObject.has("inbounds")) {
                jsonObject.put("inbounds", inboundArray)
            } else {
                jsonObject.put("inbound", inboundArray)
            }
            Log.d(TAG, "Sniffing enabled for ${inboundArray.length()} inbound handler(s)")
        } else {
            Log.d(TAG, "No inbound handlers found, skipping sniffing configuration")
        }
    }

    /**
     * Enables DNS cache and resolver logging in Xray config.
     * DNS logs will show DNS queries, cache hits/misses, and resolver operations.
     */
    @Throws(JSONException::class)
    private fun enableDnsLogging(jsonObject: JSONObject) {
        // Get or create DNS configuration
        var dnsObject = jsonObject.optJSONObject("dns")
        if (dnsObject == null) {
            dnsObject = JSONObject()
            jsonObject.put("dns", dnsObject)
        }

        // DNS cache configuration will be set later (disabled to use SystemDnsCacheServer)

        // Enable DNS query logging
        // Note: DNS logs appear in debug log level, which is already set above
        // But we can add queryStrategy for better logging
        if (!dnsObject.has("queryStrategy")) {
            dnsObject.put("queryStrategy", "UseIPv4")
        }

        // Configure DNS servers - prioritize local DNS cache server (port 5353, no root required)
        // Xray-core DNS server format: can be string "8.8.8.8" or object {"address": "127.0.0.1", "port": 5353}
        // We'll use object format for custom port
        // CRITICAL: Disable Xray-core's own DNS cache to force queries to SystemDnsCacheServer
        // This ensures all DNS queries go through our cache server
        val cacheObject = dnsObject.optJSONObject("cache") ?: JSONObject()
        cacheObject.put("enabled", false) // Disable Xray-core DNS cache to use SystemDnsCacheServer
        dnsObject.put("cache", cacheObject)
        Log.d(TAG, "⚠️ Xray-core DNS cache disabled to use SystemDnsCacheServer")
        
        if (!dnsObject.has("servers")) {
            val serversArray = org.json.JSONArray()
            
            // Use ONLY local DNS cache server (port 5353, no root required)
            // SystemDnsCacheServer will handle cache and forward to upstream DNS if needed
            // Xray-core supports DNS server with custom port using object format
            val localDnsServer = org.json.JSONObject()
            localDnsServer.put("address", "127.0.0.1")
            localDnsServer.put("port", 5353)
            serversArray.put(localDnsServer)
            
            // Don't add fallback DNS servers - SystemDnsCacheServer will handle upstream forwarding
            // This ensures ALL DNS queries go through SystemDnsCacheServer for caching
            dnsObject.put("servers", serversArray)
            Log.d(TAG, "✅ DNS servers configured: ONLY localhost:5353 (DNS cache server - handles upstream forwarding)")
        } else {
            // If servers already exist, prepend local DNS cache server as first priority
            val existingServers = dnsObject.optJSONArray("servers")
            if (existingServers != null) {
                val newServersArray = org.json.JSONArray()
                
                // Use ONLY local DNS cache server (port 5353)
                // SystemDnsCacheServer will handle cache and forward to upstream DNS if needed
                val localDnsServer = org.json.JSONObject()
                localDnsServer.put("address", "127.0.0.1")
                localDnsServer.put("port", 5353)
                newServersArray.put(localDnsServer)
                
                // Don't add existing servers - SystemDnsCacheServer will handle upstream forwarding
                // This ensures ALL DNS queries go through SystemDnsCacheServer for caching
                dnsObject.put("servers", newServersArray)
                Log.d(TAG, "✅ DNS servers updated: ONLY localhost:5353 (DNS cache server - handles upstream forwarding)")
            }
        }

        jsonObject.put("dns", dnsObject)
        Log.d(TAG, "DNS cache and resolver logging enabled")
    }

    fun extractPortsFromJson(jsonContent: String): Set<Int> {
        val ports = mutableSetOf<Int>()
        try {
            val jsonObject = JSONObject(jsonContent)
            extractPortsRecursive(jsonObject, ports)
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSON for port extraction", e)
        }
        Log.d(TAG, "Extracted ports: $ports")
        return ports
    }

    private fun extractPortsRecursive(jsonObject: JSONObject, ports: MutableSet<Int>) {
        for (key in jsonObject.keys()) {
            when (val value = jsonObject.get(key)) {
                is Int -> {
                    if (value in 1..65535) {
                        ports.add(value)
                    }
                }

                is JSONObject -> {
                    extractPortsRecursive(value, ports)
                }

                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item is JSONObject) {
                            extractPortsRecursive(item, ports)
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies aggressive speed optimizations to Xray config based on preferences.
     * These optimizations include buffer sizes, connection pools, routing, and DNS settings.
     */
    @Throws(JSONException::class)
    private fun applySpeedOptimizations(prefs: Preferences, jsonObject: JSONObject) {
        if (!prefs.aggressiveSpeedOptimizations) {
            Log.d(TAG, "Aggressive speed optimizations disabled")
            return
        }

        Log.d(TAG, "Applying aggressive speed optimizations")

        // Optimize policy settings for maximum performance
        optimizePolicySettings(prefs, jsonObject)

        // Optimize DNS settings
        optimizeDnsSettings(prefs, jsonObject)

        // Optimize routing settings
        optimizeRoutingSettings(prefs, jsonObject)

        // Optimize buffer and connection settings
        optimizeBufferSettings(prefs, jsonObject)
    }

    /**
     * Optimizes policy settings for aggressive performance.
     * Includes connection limits, buffer sizes, and concurrency settings.
     */
    @Throws(JSONException::class)
    private fun optimizePolicySettings(prefs: Preferences, jsonObject: JSONObject) {
        var policyObject = jsonObject.optJSONObject("policy")
        if (policyObject == null) {
            policyObject = JSONObject()
            jsonObject.put("policy", policyObject)
        }

        // System-level policy optimizations
        var systemObject = policyObject.optJSONObject("system")
        if (systemObject == null) {
            systemObject = JSONObject()
            policyObject.put("system", systemObject)
        }

        // Aggressive connection limits
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        
        // Connection pool optimizations
        // Xray requires levels to be an object with level IDs (e.g., "0", "1")
        var levelsObject = policyObject.optJSONObject("levels")
        if (levelsObject == null) {
            levelsObject = JSONObject()
        }
        
        // Get or create level "0" (default level)
        var level0 = levelsObject.optJSONObject("0")
        if (level0 == null) {
            level0 = JSONObject()
        }
        
        val connectionLimits = JSONObject()
        connectionLimits.put("connIdle", prefs.connIdleTimeout)
        connectionLimits.put("handshake", prefs.handshakeTimeout)
        // Set to 0 for unlimited connections to prevent broken pipe errors
        connectionLimits.put("uplinkOnly", 0)  // Unlimited
        connectionLimits.put("downlinkOnly", 0)  // Unlimited
        level0.put("connection", connectionLimits)
        
        // Buffer size optimizations - MAXIMIZED for performance
        val bufferLimits = JSONObject()
        bufferLimits.put("handshake", prefs.handshakeTimeout)
        bufferLimits.put("connIdle", prefs.connIdleTimeout)
        // Set to 0 for unlimited buffer to prevent broken pipe errors
        bufferLimits.put("uplinkOnly", 0)  // Unlimited
        bufferLimits.put("downlinkOnly", 0)  // Unlimited
        // Buffer strategy: maximum throughput
        bufferLimits.put("bufferSize", 512)  // 512KB per connection (maximum Xray supports)
        level0.put("buffer", bufferLimits)
        
        // Enable user-level stats for uplink/downlink tracking
        level0.put("statsUserUplink", true)
        level0.put("statsUserDownlink", true)
        
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        policyObject.put("system", systemObject)
        
        Log.d(TAG, "Policy settings optimized: connIdle=${prefs.connIdleTimeout}, handshake=${prefs.handshakeTimeout}, uplink=UNLIMITED (0), downlink=UNLIMITED (0), bufferSize=512KB")
    }

    /**
     * Optimizes DNS settings for faster resolution and caching.
     */
    @Throws(JSONException::class)
    private fun optimizeDnsSettings(prefs: Preferences, jsonObject: JSONObject) {
        var dnsObject = jsonObject.optJSONObject("dns")
        if (dnsObject == null) {
            dnsObject = JSONObject()
            jsonObject.put("dns", dnsObject)
        }

        // Disable Xray-core DNS cache to use SystemDnsCacheServer
        // SystemDnsCacheServer provides centralized DNS caching for all apps
        var cacheObject = dnsObject.optJSONObject("cache")
        if (cacheObject == null) {
            cacheObject = JSONObject()
        }
        cacheObject.put("enabled", false) // Disable Xray-core DNS cache to use SystemDnsCacheServer
        dnsObject.put("cache", cacheObject)

        // Query strategy for speed
        if (!dnsObject.has("queryStrategy")) {
            dnsObject.put("queryStrategy", "UseIPv4")
        }

        // Disable fake DNS if aggressive mode (can cause slowdowns)
        if (prefs.disableFakeDns) {
            dnsObject.put("disableFakeDns", true)
        }

        jsonObject.put("dns", dnsObject)
        Log.d(TAG, "DNS settings optimized: cacheSize=${prefs.dnsCacheSize}, disableFakeDns=${prefs.disableFakeDns}, queryStrategy=${dnsObject.optString("queryStrategy", "default")}")
    }

    /**
     * Optimizes routing settings for faster packet processing.
     */
    @Throws(JSONException::class)
    private fun optimizeRoutingSettings(prefs: Preferences, jsonObject: JSONObject) {
        var routingObject = jsonObject.optJSONObject("routing")
        if (routingObject == null) {
            routingObject = JSONObject()
            jsonObject.put("routing", routingObject)
        }

        // Domain strategy for speed
        if (!routingObject.has("domainStrategy")) {
            routingObject.put("domainStrategy", "AsIs")
        }

        // Enable routing rules optimization
        if (prefs.optimizeRoutingRules) {
            routingObject.put("domainStrategy", "AsIs")
            routingObject.put("domainMatcher", "hybrid")
        }

        jsonObject.put("routing", routingObject)
        Log.d(TAG, "Routing settings optimized: optimizeRoutingRules=${prefs.optimizeRoutingRules}, domainStrategy=${routingObject.optString("domainStrategy", "default")}, domainMatcher=${routingObject.optString("domainMatcher", "default")}")
    }

    /**
     * Optimizes buffer and connection settings for maximum throughput.
     */
    @Throws(JSONException::class)
    private fun optimizeBufferSettings(prefs: Preferences, jsonObject: JSONObject) {
        // Apply buffer optimizations to all outbound handlers
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray != null) {
            for (i in 0 until outboundArray.length()) {
                val outbound = outboundArray.getJSONObject(i)
                val streamSettings = outbound.optJSONObject("streamSettings")
                
                if (streamSettings != null) {
                    // TCP settings optimization
                    val tcpSettings = streamSettings.optJSONObject("tcpSettings")
                    if (tcpSettings != null && prefs.tcpFastOpen) {
                        val headerObject = tcpSettings.optJSONObject("header") ?: JSONObject()
                        headerObject.put("type", "none")
                        tcpSettings.put("header", headerObject)
                        streamSettings.put("tcpSettings", tcpSettings)
                    }

                    // WebSocket settings optimization
                    val wsSettings = streamSettings.optJSONObject("wsSettings")
                    if (wsSettings != null) {
                        // Optimize WebSocket path and headers
                        if (!wsSettings.has("path")) {
                            wsSettings.put("path", "/")
                        }
                    }

                    // HTTP/2 settings optimization
                    val httpSettings = streamSettings.optJSONObject("httpSettings")
                    if (httpSettings != null && prefs.http2Optimization) {
                        val hostArray = httpSettings.optJSONArray("host")
                        if (hostArray == null || hostArray.length() == 0) {
                            val newHostArray = org.json.JSONArray()
                            newHostArray.put("")
                            httpSettings.put("host", newHostArray)
                        }
                    }

                    outbound.put("streamSettings", streamSettings)
                }
            }
            
            if (jsonObject.has("outbounds")) {
                jsonObject.put("outbounds", outboundArray)
            } else {
                jsonObject.put("outbound", outboundArray)
            }
            
            Log.d(TAG, "Buffer settings optimized for ${outboundArray.length()} outbound(s)")
            Log.d(TAG, "  - TCP Fast Open: ${prefs.tcpFastOpen}, HTTP/2 Optimization: ${prefs.http2Optimization}")
        }
    }

    /**
     * Applies extreme RAM and CPU optimizations to maximize resource utilization.
     * WARNING: These settings can significantly increase battery consumption and heat generation.
     * Use only on high-performance devices with adequate cooling.
     */
    @Throws(JSONException::class)
    private fun applyExtremeRamCpuOptimizations(prefs: Preferences, jsonObject: JSONObject) {
        Log.d(TAG, "Applying EXTREME RAM/CPU optimizations - Maximum resource utilization mode")

        // Extreme policy optimizations
        optimizeExtremePolicySettings(prefs, jsonObject)

        // Extreme buffer and memory optimizations
        optimizeExtremeBufferSettings(prefs, jsonObject)

        // Extreme connection pool optimizations
        optimizeExtremeConnectionPools(prefs, jsonObject)

        // Extreme DNS optimizations
        optimizeExtremeDnsSettings(prefs, jsonObject)

        // Extreme routing optimizations
        optimizeExtremeRoutingSettings(prefs, jsonObject)
    }

    /**
     * Extreme policy settings for maximum RAM/CPU utilization.
     * Sets very high limits to allow maximum concurrent connections and buffers.
     */
    @Throws(JSONException::class)
    private fun optimizeExtremePolicySettings(prefs: Preferences, jsonObject: JSONObject) {
        var policyObject = jsonObject.optJSONObject("policy")
        if (policyObject == null) {
            policyObject = JSONObject()
            jsonObject.put("policy", policyObject)
        }

        var systemObject = policyObject.optJSONObject("system")
        if (systemObject == null) {
            systemObject = JSONObject()
            policyObject.put("system", systemObject)
        }

        // Extreme connection limits - maximize concurrent connections
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        
        // Extreme levels configuration
        // Xray requires levels to be an object with level IDs (e.g., "0", "1")
        var levelsObject = policyObject.optJSONObject("levels")
        if (levelsObject == null) {
            levelsObject = JSONObject()
        }

        // Get or create level "0" (default level)
        var level0 = levelsObject.optJSONObject("0")
        if (level0 == null) {
            level0 = JSONObject()
        }

        // Extreme connection pool settings - MAXIMUM
        var connectionLimits = level0.optJSONObject("connection") ?: JSONObject()
        connectionLimits.put("connIdle", prefs.extremeConnIdleTimeout)
        connectionLimits.put("handshake", prefs.extremeHandshakeTimeout)
        // Set to 0 for unlimited connections - maximum throughput
        connectionLimits.put("uplinkOnly", 0)  // Unlimited
        connectionLimits.put("downlinkOnly", 0)  // Unlimited
        level0.put("connection", connectionLimits)

        // Extreme buffer settings - MAXIMUM buffer sizes
        var bufferLimits = level0.optJSONObject("buffer") ?: JSONObject()
        bufferLimits.put("handshake", prefs.extremeHandshakeTimeout)
        bufferLimits.put("connIdle", prefs.extremeConnIdleTimeout)
        // Set to 0 for unlimited buffer - maximum throughput
        bufferLimits.put("uplinkOnly", 0)  // Unlimited
        bufferLimits.put("downlinkOnly", 0)  // Unlimited
        // Maximum buffer size per connection (512KB is Xray's maximum)
        bufferLimits.put("bufferSize", 512)  // 512KB per connection
        level0.put("buffer", bufferLimits)
        
        // Enable user-level stats for uplink/downlink tracking
        level0.put("statsUserUplink", true)
        level0.put("statsUserDownlink", true)
        
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        policyObject.put("system", systemObject)
        jsonObject.put("policy", policyObject)

        Log.d(TAG, "EXTREME policy settings: connIdle=${prefs.extremeConnIdleTimeout}, uplink=UNLIMITED (0), downlink=UNLIMITED (0), bufferSize=512KB")
    }

    /**
     * Extreme buffer and memory optimizations.
     * Maximizes buffer sizes and memory allocation for maximum throughput.
     */
    @Throws(JSONException::class)
    private fun optimizeExtremeBufferSettings(prefs: Preferences, jsonObject: JSONObject) {
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray != null) {
            for (i in 0 until outboundArray.length()) {
                val outbound = outboundArray.getJSONObject(i)
                val streamSettings = outbound.optJSONObject("streamSettings")
                
                if (streamSettings != null) {
                    // Extreme TCP settings
                    var tcpSettings = streamSettings.optJSONObject("tcpSettings")
                    if (tcpSettings != null) {
                        // Enable TCP keep-alive for maximum connection reuse
                        tcpSettings.put("acceptProxyProtocol", false)
                        val headerObject = tcpSettings.optJSONObject("header") ?: JSONObject()
                        headerObject.put("type", "none")
                        tcpSettings.put("header", headerObject)
                        streamSettings.put("tcpSettings", tcpSettings)
                    }

                    // Extreme WebSocket settings
                    var wsSettings = streamSettings.optJSONObject("wsSettings")
                    if (wsSettings != null) {
                        if (!wsSettings.has("path")) {
                            wsSettings.put("path", "/")
                        }
                        // Optimize WebSocket headers
                        val headersObject = wsSettings.optJSONObject("headers") ?: JSONObject()
                        headersObject.put("Connection", "Upgrade")
                        wsSettings.put("headers", headersObject)
                        streamSettings.put("wsSettings", wsSettings)
                    }

                    // Extreme HTTP/2 settings
                    var httpSettings = streamSettings.optJSONObject("httpSettings")
                    if (httpSettings != null) {
                        val hostArray = httpSettings.optJSONArray("host")
                        if (hostArray == null || hostArray.length() == 0) {
                            val newHostArray = org.json.JSONArray()
                            newHostArray.put("")
                            httpSettings.put("host", newHostArray)
                        }
                        streamSettings.put("httpSettings", httpSettings)
                    }

                    // Extreme QUIC settings
                    var quicSettings = streamSettings.optJSONObject("quicSettings")
                    if (quicSettings != null) {
                        quicSettings.put("security", "none")
                        streamSettings.put("quicSettings", quicSettings)
                    }

                    outbound.put("streamSettings", streamSettings)
                }

                // Add extreme proxy settings if available
                val proxySettings = outbound.optJSONObject("proxySettings")
                if (proxySettings != null && prefs.extremeProxyOptimization) {
                    proxySettings.put("tag", outbound.optString("tag", ""))
                    outbound.put("proxySettings", proxySettings)
                }
            }
            
            if (jsonObject.has("outbounds")) {
                jsonObject.put("outbounds", outboundArray)
            } else {
                jsonObject.put("outbound", outboundArray)
            }
            
            Log.d(TAG, "EXTREME buffer settings applied to ${outboundArray.length()} outbound(s)")
        }
    }

    /**
     * Extreme connection pool optimizations.
     * Maximizes concurrent connections and connection reuse.
     */
    @Throws(JSONException::class)
    private fun optimizeExtremeConnectionPools(prefs: Preferences, jsonObject: JSONObject) {
        var policyObject = jsonObject.optJSONObject("policy")
        if (policyObject == null) {
            policyObject = JSONObject()
            jsonObject.put("policy", policyObject)
        }

        // Xray requires levels to be an object with level IDs (e.g., "0", "1")
        var levelsObject = policyObject.optJSONObject("levels")
        if (levelsObject == null) {
            levelsObject = JSONObject()
            policyObject.put("levels", levelsObject)
        }

        // Get or create level "0" (default level)
        var level0 = levelsObject.optJSONObject("0")
        if (level0 == null) {
            level0 = JSONObject()
        }

        // Extreme connection pool configuration - MAXIMUM
        var connectionLimits = level0.optJSONObject("connection") ?: JSONObject()

        // Maximize connection limits
        connectionLimits.put("connIdle", prefs.extremeConnIdleTimeout)
        connectionLimits.put("handshake", prefs.extremeHandshakeTimeout)
        // Set to 0 for unlimited - prevents connection drops and broken pipe
        connectionLimits.put("uplinkOnly", 0)  // Unlimited
        connectionLimits.put("downlinkOnly", 0)  // Unlimited

        // Add connection concurrency limits if supported (0 = unlimited)
        if (prefs.maxConcurrentConnections > 0) {
            connectionLimits.put("concurrency", prefs.maxConcurrentConnections)
        } else {
            connectionLimits.put("concurrency", 0)  // Unlimited concurrent connections
        }

        level0.put("connection", connectionLimits)
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        jsonObject.put("policy", policyObject)

        Log.d(TAG, "EXTREME connection pools: maxConcurrent=${if (prefs.maxConcurrentConnections > 0) prefs.maxConcurrentConnections else "UNLIMITED (0)"}, uplink=UNLIMITED, downlink=UNLIMITED")
    }

    /**
     * Extreme DNS optimizations for maximum CPU utilization.
     * Aggressive caching and parallel queries.
     */
    @Throws(JSONException::class)
    private fun optimizeExtremeDnsSettings(prefs: Preferences, jsonObject: JSONObject) {
        var dnsObject = jsonObject.optJSONObject("dns")
        if (dnsObject == null) {
            dnsObject = JSONObject()
            jsonObject.put("dns", dnsObject)
        }

        // Extreme DNS cache - maximize cache size
        // Disable Xray-core DNS cache to use SystemDnsCacheServer
        var cacheObject = dnsObject.optJSONObject("cache")
        if (cacheObject == null) {
            cacheObject = JSONObject()
        }
        cacheObject.put("enabled", false) // Disable Xray-core DNS cache to use SystemDnsCacheServer
        dnsObject.put("cache", cacheObject)

        // Parallel DNS queries for maximum CPU utilization
        if (prefs.parallelDnsQueries) {
            dnsObject.put("queryStrategy", "UseIPv4")
            // Enable multiple DNS servers for parallel queries
            if (!dnsObject.has("servers")) {
                val serversArray = org.json.JSONArray()
                serversArray.put("8.8.8.8")
                serversArray.put("8.8.4.4")
                serversArray.put("1.1.1.1")
                serversArray.put("1.0.0.1")
                dnsObject.put("servers", serversArray)
            }
        }

        // Disable fake DNS for maximum speed
        if (prefs.extremeDisableFakeDns) {
            dnsObject.put("disableFakeDns", true)
        }

        jsonObject.put("dns", dnsObject)
        Log.d(TAG, "EXTREME DNS: cacheSize=${prefs.extremeDnsCacheSize}, parallel=${prefs.parallelDnsQueries}")
    }

    /**
     * Extreme routing optimizations for maximum CPU utilization.
     * Optimizes routing table lookups and rule matching.
     */
    @Throws(JSONException::class)
    private fun optimizeExtremeRoutingSettings(prefs: Preferences, jsonObject: JSONObject) {
        var routingObject = jsonObject.optJSONObject("routing")
        if (routingObject == null) {
            routingObject = JSONObject()
            jsonObject.put("routing", routingObject)
        }

        // Extreme routing strategy
        routingObject.put("domainStrategy", "AsIs")
        routingObject.put("domainMatcher", "hybrid")

        // Optimize routing rules for CPU efficiency
        if (prefs.extremeRoutingOptimization) {
            routingObject.put("domainMatcher", "hybrid")
            routingObject.put("domainStrategy", "AsIs")
        }

        jsonObject.put("routing", routingObject)
        Log.d(TAG, "EXTREME routing optimization enabled")
    }

    /**
     * Applies bypass routing rules for domains and IPs.
     * These domains/IPs will be routed through "direct" outbound (bypassing VPN).
     */
    @Throws(JSONException::class)
    private fun applyBypassRoutingRules(prefs: Preferences, jsonObject: JSONObject) {
        val bypassDomains = prefs.bypassDomains.filter { it.isNotBlank() }
        val bypassIps = prefs.bypassIps.filter { it.isNotBlank() }

        if (bypassDomains.isEmpty() && bypassIps.isEmpty()) {
            Log.d(TAG, "No bypass domains/IPs configured, skipping bypass routing rules")
            return
        }

        Log.d(TAG, "Applying bypass routing rules: ${bypassDomains.size} domains, ${bypassIps.size} IPs")

        // Ensure "direct" outbound exists
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray != null) {
            var hasDirectOutbound = false
            for (i in 0 until outboundArray.length()) {
                val outbound = outboundArray.getJSONObject(i)
                if (outbound.optString("tag") == "direct") {
                    hasDirectOutbound = true
                    break
                }
            }
            if (!hasDirectOutbound) {
                val directOutbound = JSONObject()
                directOutbound.put("protocol", "freedom")
                directOutbound.put("tag", "direct")
                outboundArray.put(directOutbound)
                if (jsonObject.has("outbounds")) {
                    jsonObject.put("outbounds", outboundArray)
                } else {
                    jsonObject.put("outbound", outboundArray)
                }
                Log.d(TAG, "Added 'direct' outbound for bypass routing")
            }
        }

        // Get or create routing object
        var routingObject = jsonObject.optJSONObject("routing")
        if (routingObject == null) {
            routingObject = JSONObject()
            jsonObject.put("routing", routingObject)
        }

        // Get or create rules array
        var rulesArray = routingObject.optJSONArray("rules")
        if (rulesArray == null) {
            rulesArray = org.json.JSONArray()
            routingObject.put("rules", rulesArray)
        }

        // Add domain bypass rules (insert at the beginning for priority)
        // Supports both regular domains and geosite: tags (e.g., geosite:youtube)
        // This allows users to bypass all YouTube domains by adding "geosite:youtube" to bypass domains list
        if (bypassDomains.isNotEmpty()) {
            val domainRule = JSONObject()
            val domainArray = org.json.JSONArray()
            bypassDomains.forEach { domain: String ->
                val trimmedDomain = domain.trim()
                // Support geosite: tags (e.g., geosite:youtube) for automatic domain lists
                // Xray will automatically resolve geosite: tags from geosite.dat file
                domainArray.put(trimmedDomain)
            }
            domainRule.put("domain", domainArray)
            domainRule.put("outboundTag", "direct")
            // Insert at the beginning so bypass rules take priority
            val newRulesArray = org.json.JSONArray()
            newRulesArray.put(domainRule)
            for (i in 0 until rulesArray.length()) {
                newRulesArray.put(rulesArray.get(i))
            }
            routingObject.put("rules", newRulesArray)
            val geositeCount = bypassDomains.count { it.trim().startsWith("geosite:") }
            Log.d(TAG, "Added bypass routing rule for ${bypassDomains.size} domain(s)${if (geositeCount > 0) " (${geositeCount} geosite tag(s) like geosite:youtube)" else ""}")
        }

        // Add IP bypass rules (insert at the beginning for priority)
        if (bypassIps.isNotEmpty()) {
            val ipRule = JSONObject()
            val ipArray = org.json.JSONArray()
            bypassIps.forEach { ip: String ->
                ipArray.put(ip.trim())
            }
            ipRule.put("ip", ipArray)
            ipRule.put("outboundTag", "direct")
            // Insert at the beginning so bypass rules take priority
            val currentRulesArray = routingObject.optJSONArray("rules") ?: org.json.JSONArray()
            val newRulesArray = org.json.JSONArray()
            newRulesArray.put(ipRule)
            for (i in 0 until currentRulesArray.length()) {
                newRulesArray.put(currentRulesArray.get(i))
            }
            routingObject.put("rules", newRulesArray)
            Log.d(TAG, "Added bypass routing rule for ${bypassIps.size} IP(s)")
        }

        jsonObject.put("routing", routingObject)
        Log.d(TAG, "Bypass routing rules applied successfully")
    }

    /**
     * CRITICAL FIX: Ensures UDP support is enabled in dokodemo-door inbounds.
     * 
     * For TUN transparent proxy, dokodemo-door must accept both TCP and UDP traffic.
     * This is required for:
     * - DNS over UDP (port 53)
     * - QUIC/HTTP3 (UDP-based)
     * - All UDP-based applications
     * 
     * The network setting must be in the settings object (not streamSettings) as:
     * "network": ["tcp", "udp"]
     * 
     * Also ensures followRedirect is enabled for proper transparent proxy operation.
     * 
     * IMPORTANT: If no dokodemo-door inbound exists, this function will automatically
     * create one for TUN transparent proxy operation.
     */
    @Throws(JSONException::class)
    private fun ensureUdpSupportInDokodemoInbounds(jsonObject: JSONObject) {
        var inboundArray = jsonObject.optJSONArray("inbounds") ?: jsonObject.optJSONArray("inbound")
        
        // Create inbounds array if it doesn't exist
        if (inboundArray == null) {
            inboundArray = org.json.JSONArray()
            jsonObject.put("inbounds", inboundArray)
            Log.i(TAG, "Created new inbounds array in config")
        }

        // First pass: Check if dokodemo-door inbound exists and configure existing ones
        var dokodemoFound = false
        var dokodemoCount = 0
        for (i in 0 until inboundArray.length()) {
            val inbound = inboundArray.getJSONObject(i)
            val protocol = inbound.optString("protocol", "").lowercase()
            
            // Only process dokodemo-door inbounds
            if (protocol != "dokodemo-door" && protocol != "dokodemo" && protocol != "tunnel") {
                continue
            }
            
            dokodemoFound = true
            dokodemoCount++
            Log.d(TAG, "Configuring dokodemo-door inbound #$dokodemoCount for UDP support")
            
            // Get or create settings object
            var settings = inbound.optJSONObject("settings")
            if (settings == null) {
                settings = JSONObject()
                inbound.put("settings", settings)
            }
            
            // Ensure network includes both TCP and UDP
            val networkValue = settings.opt("network")
            when {
                networkValue == null -> {
                    // No network setting - add both TCP and UDP
                    val networkArray = org.json.JSONArray()
                    networkArray.put("tcp")
                    networkArray.put("udp")
                    settings.put("network", networkArray)
                    Log.d(TAG, "  Added network: [tcp, udp] to dokodemo-door inbound")
                }
                networkValue is String -> {
                    // Single string value - check if it's "tcp" or "udp" only
                    val networkStr = networkValue as String
                    when (networkStr.lowercase()) {
                        "tcp", "udp" -> {
                            // Replace with array containing both
                            val networkArray = org.json.JSONArray()
                            networkArray.put("tcp")
                            networkArray.put("udp")
                            settings.put("network", networkArray)
                            Log.d(TAG, "  Updated network from '$networkStr' to [tcp, udp]")
                        }
                        else -> {
                            // Unknown value - add both TCP and UDP as array
                            val networkArray = org.json.JSONArray()
                            networkArray.put("tcp")
                            networkArray.put("udp")
                            settings.put("network", networkArray)
                            Log.d(TAG, "  Replaced network '$networkStr' with [tcp, udp]")
                        }
                    }
                }
                networkValue is org.json.JSONArray -> {
                    // Array value - ensure both tcp and udp are present
                    val networkArray = networkValue as org.json.JSONArray
                    val networks = mutableSetOf<String>()
                    for (j in 0 until networkArray.length()) {
                        val net = networkArray.optString(j, "").lowercase()
                        if (net.isNotEmpty()) {
                            networks.add(net)
                        }
                    }
                    
                    // Add both TCP and UDP if not already present
                    var updated = false
                    if (!networks.contains("tcp")) {
                        networkArray.put("tcp")
                        networks.add("tcp")
                        updated = true
                    }
                    if (!networks.contains("udp")) {
                        networkArray.put("udp")
                        networks.add("udp")
                        updated = true
                    }
                    
                    if (updated) {
                        Log.d(TAG, "  Updated network array to include both tcp and udp")
                    } else {
                        Log.d(TAG, "  Network array already contains tcp and udp")
                    }
                }
            }
            
            // Ensure followRedirect is enabled for transparent proxy
            if (!settings.has("followRedirect")) {
                settings.put("followRedirect", true)
                Log.d(TAG, "  Added followRedirect: true to dokodemo-door inbound")
            } else {
                val followRedirect = settings.optBoolean("followRedirect", false)
                if (!followRedirect) {
                    settings.put("followRedirect", true)
                    Log.d(TAG, "  Updated followRedirect to true")
                }
            }
            
            // Remove incorrect streamSettings.network if present (this is for outbounds, not inbounds)
            val streamSettings = inbound.optJSONObject("streamSettings")
            if (streamSettings != null && streamSettings.has("network")) {
                val streamNetwork = streamSettings.optString("network", "")
                if (streamNetwork.lowercase() == "tcp" || streamNetwork.lowercase() == "udp") {
                    Log.w(TAG, "  WARNING: Removing streamSettings.network='$streamNetwork' from dokodemo-door inbound (network should be in settings, not streamSettings)")
                    streamSettings.remove("network")
                    if (streamSettings.length() == 0) {
                        inbound.remove("streamSettings")
                    }
                }
            }
        }
        
        if (jsonObject.has("inbounds")) {
            jsonObject.put("inbounds", inboundArray)
        } else {
            jsonObject.put("inbound", inboundArray)
        }
        
        // If no dokodemo-door inbound found, create one for TUN transparent proxy
        if (!dokodemoFound) {
            Log.w(TAG, "⚠️ No dokodemo-door inbound found in config - creating one for TUN transparent proxy")
            
            val dokodemoInbound = JSONObject().apply {
                put("protocol", "dokodemo-door")
                put("port", 10808) // Standard port for transparent proxy
                put("tag", "transparent")
                
                val settings = JSONObject().apply {
                    // Address can be any, will be overridden by followRedirect
                    put("address", "8.8.8.8")
                    // CRITICAL: Enable both TCP and UDP for transparent proxy
                    val networkArray = org.json.JSONArray()
                    networkArray.put("tcp")
                    networkArray.put("udp")
                    put("network", networkArray)
                    // CRITICAL: Enable followRedirect for transparent proxy (SO_ORIGINAL_DST)
                    put("followRedirect", true)
                    // CRITICAL: Set userLevel to 0 to use policy level 0 settings
                    // This ensures UDP timeout settings (connIdle=1800s) are applied
                    put("userLevel", 0)
                }
                put("settings", settings)
                
                // CRITICAL: Add allocator configuration to prevent UDP connection closure
                // This helps prevent "closed pipe" errors by keeping UDP connections alive longer
                val allocatorObject = JSONObject().apply {
                    put("strategy", "always")
                    put("concurrency", 3)
                }
                put("allocator", allocatorObject)
                
                // Enable sniffing for domain detection
                val sniffing = JSONObject().apply {
                    put("enabled", true)
                    val destOverride = org.json.JSONArray()
                    destOverride.put("http")
                    destOverride.put("tls")
                    destOverride.put("quic")
                    destOverride.put("fakedns")
                    put("destOverride", destOverride)
                }
                put("sniffing", sniffing)
            }
            
            inboundArray.put(dokodemoInbound)
            dokodemoCount = 1
            dokodemoFound = true
            
            Log.i(TAG, "✅ Created dokodemo-door inbound for TUN transparent proxy with UDP support")
            Log.i(TAG, "  - Protocol: dokodemo-door")
            Log.i(TAG, "  - Port: 10808")
            Log.i(TAG, "  - Network: [tcp, udp]")
            Log.i(TAG, "  - followRedirect: true")
            
            // Ensure routing rule exists to route traffic from dokodemo-door to first outbound
            try {
                var routingObject = jsonObject.optJSONObject("routing")
                if (routingObject == null) {
                    routingObject = JSONObject()
                    jsonObject.put("routing", routingObject)
                }
                
                var rulesArray = routingObject.optJSONArray("rules")
                if (rulesArray == null) {
                    rulesArray = org.json.JSONArray()
                    routingObject.put("rules", rulesArray)
                }
                
                // Check if routing rule for dokodemo-door already exists
                var routingRuleExists = false
                for (i in 0 until rulesArray.length()) {
                    val rule = rulesArray.getJSONObject(i)
                    val inboundTag = rule.optJSONArray("inboundTag")
                    if (inboundTag != null) {
                        for (j in 0 until inboundTag.length()) {
                            val tag = inboundTag.optString(j, "")
                            if (tag == "transparent" || tag.lowercase().contains("dokodemo") || tag.lowercase().contains("tunnel")) {
                                routingRuleExists = true
                                break
                            }
                        }
                    }
                }
                
                // Add routing rule if it doesn't exist
                if (!routingRuleExists) {
                    // Find first outbound tag
                    val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
                    var outboundTag: String? = null
                    if (outboundArray != null && outboundArray.length() > 0) {
                        val firstOutbound = outboundArray.getJSONObject(0)
                        val existingTag = firstOutbound.optString("tag", "")
                        if (existingTag.isNotEmpty()) {
                            outboundTag = existingTag
                        } else {
                            // Generate a tag if none exists
                            val protocol = firstOutbound.optString("protocol", "proxy")
                            outboundTag = "${protocol}_0"
                            firstOutbound.put("tag", outboundTag)
                        }
                    }
                    
                    if (outboundTag != null) {
                        val routingRule = JSONObject().apply {
                            put("type", "field")
                            val inboundTagArray = org.json.JSONArray()
                            inboundTagArray.put("transparent")
                            put("inboundTag", inboundTagArray)
                            put("outboundTag", outboundTag)
                        }
                        // Insert at the beginning of rules array so it takes priority
                        val newRulesArray = org.json.JSONArray()
                        newRulesArray.put(routingRule)
                        for (i in 0 until rulesArray.length()) {
                            newRulesArray.put(rulesArray.getJSONObject(i))
                        }
                        routingObject.put("rules", newRulesArray)
                        
                        Log.i(TAG, "✅ Added routing rule: transparent inbound → $outboundTag outbound")
                    } else {
                        Log.w(TAG, "⚠️ No outbound found, cannot create routing rule for dokodemo-door")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error configuring routing rules for dokodemo-door: ${e.message}")
            }
        }
        
        if (dokodemoCount > 0) {
            Log.i(TAG, "✅ UDP support enabled in $dokodemoCount dokodemo-door inbound(s)")
            
            // Log the actual config for verification
            try {
                val inboundsArray = jsonObject.optJSONArray("inbounds") ?: jsonObject.optJSONArray("inbound")
                if (inboundsArray != null) {
                    for (i in 0 until inboundsArray.length()) {
                        val inbound = inboundsArray.getJSONObject(i)
                        val protocol = inbound.optString("protocol", "").lowercase()
                        if (protocol == "dokodemo-door" || protocol == "dokodemo" || protocol == "tunnel") {
                            val settings = inbound.optJSONObject("settings")
                            val network = settings?.opt("network")
                            val followRedirect = settings?.optBoolean("followRedirect", false)
                            Log.i(TAG, "  ✅ dokodemo-door inbound #${i+1}: network=$network, followRedirect=$followRedirect")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error logging config details: ${e.message}")
            }
        } else {
            Log.w(TAG, "⚠️ No dokodemo-door inbounds found to configure - UDP may not work!")
        }
    }

    /**
     * Configures UDP timeout settings to prevent closed pipe errors.
     * 
     * UDP closed pipe errors occur when:
     * 1. UDP connections are closed while packets are still being processed
     * 2. UDP timeout is too short, causing premature connection closure
     * 3. UDP dispatcher tries to write to a closed pipe
     * 
     * This function:
     * - Increases UDP connection idle timeout to prevent premature closure
     * - Ensures UDP connections stay alive longer for better reliability
     * - Prevents race conditions between UDP packet processing and connection cleanup
     */
    @Throws(JSONException::class)
    private fun configureUdpTimeoutSettings(jsonObject: JSONObject) {
        Log.d(TAG, "Configuring UDP timeout settings to prevent closed pipe errors")
        
        // Get or create policy object
        var policyObject = jsonObject.optJSONObject("policy")
        if (policyObject == null) {
            policyObject = JSONObject()
            jsonObject.put("policy", policyObject)
        }
        
        // Get or create levels object
        var levelsObject = policyObject.optJSONObject("levels")
        if (levelsObject == null) {
            levelsObject = JSONObject()
        }
        
        // Get or create level "0" (default level)
        var level0 = levelsObject.optJSONObject("0")
        if (level0 == null) {
            level0 = JSONObject()
        }
        
        // Get or create connection settings
        var connectionSettings = level0.optJSONObject("connection")
        if (connectionSettings == null) {
            connectionSettings = JSONObject()
        }
        
        // CRITICAL: Increase connIdle timeout for UDP connections
        // Default is 300 seconds (5 minutes), but UDP connections need MUCH longer timeout
        // to prevent closed pipe errors during packet processing
        // Set to 1800 seconds (30 minutes) to allow UDP sessions to stay alive much longer
        // This prevents premature closure of UDP dispatcher connections which causes
        // "failed to write/handle UDP input > io: read/write on closed pipe" errors
        // OPTIMIZATION: Increased to 3600 seconds (60 minutes) to further reduce race conditions
        // with Xray's hardcoded 1-minute inactivity timer
        val currentConnIdle = connectionSettings.optInt("connIdle", 300)
        val udpConnIdle = 3600 // 60 minutes - even longer for UDP stability and error reduction
        if (currentConnIdle < udpConnIdle) {
            connectionSettings.put("connIdle", udpConnIdle)
            Log.d(TAG, "  Increased connIdle timeout from ${currentConnIdle}s to ${udpConnIdle}s for UDP stability (60 min)")
        } else {
            Log.d(TAG, "  connIdle timeout already sufficient: ${currentConnIdle}s")
        }
        
        // CRITICAL: Set uplinkOnly and downlinkOnly to longer timeout for UDP
        // UDP operations can be bidirectional and need longer timeout on both directions
        // OPTIMIZATION: Increased to 1800 seconds (30 minutes) to match connection timeout better
        // This ensures UDP packets can flow in both directions for extended periods
        // and reduces race conditions during bidirectional UDP communication
        val udpDirectionTimeout = 1800 // 30 minutes for uplink/downlink - optimized for UDP stability
        val currentUplinkOnly = connectionSettings.optInt("uplinkOnly", 0)
        val currentDownlinkOnly = connectionSettings.optInt("downlinkOnly", 0)
        
        if (currentUplinkOnly == 0 || (currentUplinkOnly > 0 && currentUplinkOnly < udpDirectionTimeout)) {
            // Only set if unlimited (0) or less than required
            if (currentUplinkOnly == 0) {
                // Keep unlimited for maximum flexibility - UDP can be bursty
                Log.d(TAG, "  Keeping uplinkOnly unlimited (0) for UDP flexibility")
            } else {
                connectionSettings.put("uplinkOnly", udpDirectionTimeout)
                Log.d(TAG, "  Increased uplinkOnly timeout from ${currentUplinkOnly}s to ${udpDirectionTimeout}s (15 min)")
            }
        }
        
        if (currentDownlinkOnly == 0 || (currentDownlinkOnly > 0 && currentDownlinkOnly < udpDirectionTimeout)) {
            // Only set if unlimited (0) or less than required
            if (currentDownlinkOnly == 0) {
                // Keep unlimited for maximum flexibility - UDP can be bursty
                Log.d(TAG, "  Keeping downlinkOnly unlimited (0) for UDP flexibility")
            } else {
                connectionSettings.put("downlinkOnly", udpDirectionTimeout)
                Log.d(TAG, "  Increased downlinkOnly timeout from ${currentDownlinkOnly}s to ${udpDirectionTimeout}s (15 min)")
            }
        }
        
        // CRITICAL: Also configure buffer settings for UDP to prevent premature closure
        // Buffer settings affect how long UDP data can be buffered before connection closes
        var bufferSettings = level0.optJSONObject("buffer")
        if (bufferSettings == null) {
            bufferSettings = JSONObject()
        }
        
        // Set buffer timeout to match connection timeout
        // This ensures UDP buffers stay active as long as connections do
        // OPTIMIZATION: Buffer settings also increased to prevent premature buffer cleanup
        val currentBufferConnIdle = bufferSettings.optInt("connIdle", 300)
        if (currentBufferConnIdle < udpConnIdle) {
            bufferSettings.put("connIdle", udpConnIdle)
            Log.d(TAG, "  Increased buffer connIdle timeout from ${currentBufferConnIdle}s to ${udpConnIdle}s (60 min)")
            Log.d(TAG, "  Increased buffer connIdle timeout from ${currentBufferConnIdle}s to ${udpConnIdle}s for UDP")
        }
        
        // Set buffer size to maximum for UDP to prevent buffer full errors
        val currentBufferSize = bufferSettings.optInt("bufferSize", 512)
        val maxBufferSize = 512 // 512KB maximum buffer per connection
        if (currentBufferSize < maxBufferSize) {
            bufferSettings.put("bufferSize", maxBufferSize)
            Log.d(TAG, "  Increased buffer size from ${currentBufferSize}KB to ${maxBufferSize}KB for UDP")
        }
        
        level0.put("connection", connectionSettings)
        level0.put("buffer", bufferSettings)
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        jsonObject.put("policy", policyObject)
        
        Log.i(TAG, "✅ UDP timeout settings configured: connIdle=${connectionSettings.optInt("connIdle", 300)}s, uplinkOnly=${connectionSettings.optInt("uplinkOnly", 0)}, downlinkOnly=${connectionSettings.optInt("downlinkOnly", 0)}, bufferSize=${bufferSettings.optInt("bufferSize", 512)}KB")
    }
    
    /**
     * CRITICAL: Remove port 53 routing rule that redirects DNS queries to dns-out outbound.
     * This rule prevents Xray-core from using SystemDnsCacheServer (localhost:5353).
     * We need Xray-core to use its DNS resolver (which uses SystemDnsCacheServer) instead of routing to dns-out.
     * 
     * This function must be called AFTER all other routing rules are processed to ensure
     * the port 53 rule is removed even if other functions modify routing.
     */
    @Throws(JSONException::class)
    private fun removePort53DnsRoutingRule(jsonObject: JSONObject) {
        val routingObject = jsonObject.optJSONObject("routing")
        if (routingObject == null) {
            Log.d(TAG, "No routing object found, skipping port 53 rule removal")
            return
        }
        
        val rulesArray = routingObject.optJSONArray("rules")
        if (rulesArray == null || rulesArray.length() == 0) {
            Log.d(TAG, "No routing rules found, skipping port 53 rule removal")
            return
        }
        
        val newRulesArray = org.json.JSONArray()
        var removedPort53Rule = false
        var removedCount = 0
        
        for (i in 0 until rulesArray.length()) {
            val rule = rulesArray.optJSONObject(i)
            if (rule != null) {
                // Check if this is a port 53 rule that routes to dns-out
                val port = rule.optInt("port", -1)
                val outboundTag = rule.optString("outboundTag", "")
                
                if (port == 53 && (outboundTag == "dns-out" || outboundTag == "dns")) {
                    // Skip this rule - it prevents SystemDnsCacheServer from being used
                    removedPort53Rule = true
                    removedCount++
                    Log.d(TAG, "⚠️ Removed port 53 routing rule (outboundTag: $outboundTag) to allow SystemDnsCacheServer usage")
                    continue
                }
                
                newRulesArray.put(rule)
            } else {
                // Keep non-object rules (shouldn't happen, but be safe)
                newRulesArray.put(rulesArray.get(i))
            }
        }
        
        if (removedPort53Rule) {
            routingObject.put("rules", newRulesArray)
            jsonObject.put("routing", routingObject)
            Log.i(TAG, "✅ Port 53 routing rule(s) removed ($removedCount rule(s)) - Xray-core will now use SystemDnsCacheServer for DNS resolution")
        } else {
            Log.d(TAG, "No port 53 routing rule found to remove")
        }
    }
    
    /**
     * Ensures all outbounds have tags for stats collection.
     * Xray stats format: outbound>>>[tag]>>>traffic>>>uplink/downlink
     * Without tags, stats cannot be collected properly.
     */
    @Throws(JSONException::class)
    private fun ensureOutboundTags(jsonObject: JSONObject) {
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray == null) {
            Log.d(TAG, "No outbounds found, skipping tag assignment")
            return
        }

        var tagCount = 0
        for (i in 0 until outboundArray.length()) {
            val outbound = outboundArray.getJSONObject(i)
            val existingTag = outbound.optString("tag", "")
            
            if (existingTag.isBlank()) {
                // Generate a unique tag based on protocol and index
                val protocol = outbound.optString("protocol", "unknown")
                val tag = when {
                    protocol == "freedom" -> "direct"
                    protocol == "blackhole" -> "blackhole"
                    protocol == "dns" -> "dns"
                    else -> {
                        // For proxy outbounds, use protocol + index
                        val tagName = "${protocol}_${i}"
                        tagName.replace("-", "_").lowercase()
                    }
                }
                outbound.put("tag", tag)
                tagCount++
                Log.d(TAG, "Added tag '$tag' to outbound[$i] (protocol: $protocol)")
            } else {
                Log.d(TAG, "Outbound[$i] already has tag: $existingTag")
            }
        }

        // Update the outbounds array
        if (jsonObject.has("outbounds")) {
            jsonObject.put("outbounds", outboundArray)
        } else {
            jsonObject.put("outbound", outboundArray)
        }

        if (tagCount > 0) {
            Log.d(TAG, "Added tags to $tagCount outbound(s) for stats collection")
        } else {
            Log.d(TAG, "All outbounds already have tags")
        }
    }
}

