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
        Log.d(TAG, "=== Starting config injection ===")
        Log.d(TAG, "Aggressive optimizations: ${prefs.aggressiveSpeedOptimizations}")
        Log.d(TAG, "Extreme optimizations: ${prefs.extremeRamCpuOptimizations}")
        
        val jsonObject = JSONObject(configContent)

        val apiObject = JSONObject()
        apiObject.put("tag", "api")
        apiObject.put("listen", "127.0.0.1:${prefs.apiPort}")
        val servicesArray = org.json.JSONArray()
        servicesArray.put("StatsService")
        apiObject.put("services", servicesArray)

        jsonObject.put("api", apiObject)
        jsonObject.put("stats", JSONObject())

        val policyObject = JSONObject()
        val systemObject = JSONObject()
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        policyObject.put("system", systemObject)

        jsonObject.put("policy", policyObject)

        // Enable debug logging for TLS/SSL information
        val logObject = jsonObject.optJSONObject("log") ?: JSONObject()
        logObject.put("logLevel", "debug")
        jsonObject.put("log", logObject)

        // Enable sniffing for domain and protocol detection
        enableSniffing(jsonObject)

        // Enable DNS cache and resolver logging
        enableDnsLogging(jsonObject)

        // Apply aggressive speed optimizations
        applySpeedOptimizations(prefs, jsonObject)

        // Apply extreme RAM/CPU optimizations if enabled
        if (prefs.extremeRamCpuOptimizations) {
            Log.d(TAG, "Extreme RAM/CPU optimizations ENABLED - applying...")
            applyExtremeRamCpuOptimizations(prefs, jsonObject)
        } else {
            Log.d(TAG, "Extreme RAM/CPU optimizations DISABLED")
        }

        // Apply bypass domain/IP routing rules
        applyBypassRoutingRules(prefs, jsonObject)

        // Ensure all outbounds have tags for stats collection
        ensureOutboundTags(jsonObject)

        val finalConfig = jsonObject.toString(2)
        Log.d(TAG, "=== Config injection completed ===")
        Log.d(TAG, "Final config size: ${finalConfig.length} bytes")
        
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
            
            val dns = finalJson.optJSONObject("dns")
            val dnsCache = dns?.optJSONObject("cache")
            Log.d(TAG, "  - DNS cache: ${dnsCache?.toString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying config", e)
        }

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

        // Enable DNS cache (if not already configured)
        if (!dnsObject.has("cache")) {
            val cacheObject = JSONObject()
            cacheObject.put("enabled", true)
            // Cache size: 1000 entries (default)
            cacheObject.put("cacheSize", 1000)
            dnsObject.put("cache", cacheObject)
        } else {
            // Ensure cache is enabled
            val cacheObject = dnsObject.optJSONObject("cache") ?: JSONObject()
            cacheObject.put("enabled", true)
            dnsObject.put("cache", cacheObject)
        }

        // Enable DNS query logging
        // Note: DNS logs appear in debug log level, which is already set above
        // But we can add queryStrategy for better logging
        if (!dnsObject.has("queryStrategy")) {
            dnsObject.put("queryStrategy", "UseIPv4")
        }

        // Ensure DNS servers are configured (add default if none exist)
        if (!dnsObject.has("servers")) {
            val serversArray = org.json.JSONArray()
            serversArray.put("8.8.8.8")
            serversArray.put("8.8.4.4")
            dnsObject.put("servers", serversArray)
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
        // 0 = unlimited buffer (sınırsız)
        if (prefs.uplinkOnly > 0) {
            connectionLimits.put("uplinkOnly", prefs.uplinkOnly)
        }
        if (prefs.downlinkOnly > 0) {
            connectionLimits.put("downlinkOnly", prefs.downlinkOnly)
        }
        level0.put("connection", connectionLimits)
        
        // Buffer size optimizations
        val bufferLimits = JSONObject()
        bufferLimits.put("handshake", prefs.handshakeTimeout)
        bufferLimits.put("connIdle", prefs.connIdleTimeout)
        // 0 = unlimited buffer (sınırsız)
        if (prefs.uplinkOnly > 0) {
            bufferLimits.put("uplinkOnly", prefs.uplinkOnly)
        }
        if (prefs.downlinkOnly > 0) {
            bufferLimits.put("downlinkOnly", prefs.downlinkOnly)
        }
        level0.put("buffer", bufferLimits)
        
        // Enable user-level stats for uplink/downlink tracking
        level0.put("statsUserUplink", true)
        level0.put("statsUserDownlink", true)
        
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        policyObject.put("system", systemObject)
        
        Log.d(TAG, "Policy settings optimized: connIdle=${prefs.connIdleTimeout}, handshake=${prefs.handshakeTimeout}, uplink=${prefs.uplinkOnly} (0=unlimited), downlink=${prefs.downlinkOnly} (0=unlimited)")
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

        // Aggressive DNS cache settings
        var cacheObject = dnsObject.optJSONObject("cache")
        if (cacheObject == null) {
            cacheObject = JSONObject()
        }
        cacheObject.put("enabled", true)
        cacheObject.put("cacheSize", prefs.dnsCacheSize)
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

        // Extreme connection pool settings
        var connectionLimits = level0.optJSONObject("connection") ?: JSONObject()
        connectionLimits.put("connIdle", prefs.extremeConnIdleTimeout)
        connectionLimits.put("handshake", prefs.extremeHandshakeTimeout)
        // 0 = unlimited buffer (sınırsız)
        if (prefs.extremeUplinkOnly > 0) {
            connectionLimits.put("uplinkOnly", prefs.extremeUplinkOnly)
        }
        if (prefs.extremeDownlinkOnly > 0) {
            connectionLimits.put("downlinkOnly", prefs.extremeDownlinkOnly)
        }
        level0.put("connection", connectionLimits)

        // Extreme buffer settings - maximize buffer sizes
        var bufferLimits = level0.optJSONObject("buffer") ?: JSONObject()
        bufferLimits.put("handshake", prefs.extremeHandshakeTimeout)
        bufferLimits.put("connIdle", prefs.extremeConnIdleTimeout)
        // 0 = unlimited buffer (sınırsız)
        if (prefs.extremeUplinkOnly > 0) {
            bufferLimits.put("uplinkOnly", prefs.extremeUplinkOnly)
        }
        if (prefs.extremeDownlinkOnly > 0) {
            bufferLimits.put("downlinkOnly", prefs.extremeDownlinkOnly)
        }
        level0.put("buffer", bufferLimits)
        
        // Enable user-level stats for uplink/downlink tracking
        level0.put("statsUserUplink", true)
        level0.put("statsUserDownlink", true)
        
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        policyObject.put("system", systemObject)
        jsonObject.put("policy", policyObject)

        Log.d(TAG, "EXTREME policy settings: connIdle=${prefs.extremeConnIdleTimeout}, buffers=${prefs.extremeUplinkOnly}/${prefs.extremeDownlinkOnly}")
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

        // Extreme connection pool configuration
        var connectionLimits = level0.optJSONObject("connection") ?: JSONObject()
        
        // Maximize connection limits
        connectionLimits.put("connIdle", prefs.extremeConnIdleTimeout)
        connectionLimits.put("handshake", prefs.extremeHandshakeTimeout)
        // 0 = unlimited buffer (sınırsız)
        if (prefs.extremeUplinkOnly > 0) {
            connectionLimits.put("uplinkOnly", prefs.extremeUplinkOnly)
        }
        if (prefs.extremeDownlinkOnly > 0) {
            connectionLimits.put("downlinkOnly", prefs.extremeDownlinkOnly)
        }
        
        // Add connection concurrency limits if supported
        if (prefs.maxConcurrentConnections > 0) {
            connectionLimits.put("concurrency", prefs.maxConcurrentConnections)
        }

        level0.put("connection", connectionLimits)
        levelsObject.put("0", level0)
        policyObject.put("levels", levelsObject)
        jsonObject.put("policy", policyObject)

        Log.d(TAG, "EXTREME connection pools: maxConcurrent=${prefs.maxConcurrentConnections}")
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
        var cacheObject = dnsObject.optJSONObject("cache")
        if (cacheObject == null) {
            cacheObject = JSONObject()
        }
        cacheObject.put("enabled", true)
        cacheObject.put("cacheSize", prefs.extremeDnsCacheSize)
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

