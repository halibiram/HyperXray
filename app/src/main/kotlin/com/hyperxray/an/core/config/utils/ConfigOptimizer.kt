package com.hyperxray.an.core.config.utils

import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Optimizes Xray configuration for speed and extreme RAM/CPU performance.
 * Follows Single Responsibility Principle - only handles optimization logic.
 */
object ConfigOptimizer {
    private const val TAG = "ConfigOptimizer"

    /**
     * Applies aggressive speed optimizations to Xray config based on preferences.
     * These optimizations include buffer sizes, connection pools, routing, and DNS settings.
     */
    @Throws(JSONException::class)
    fun applySpeedOptimizations(prefs: Preferences, jsonObject: JSONObject) {
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

        // Enable Xray-core DNS cache for better performance
        var cacheObject = dnsObject.optJSONObject("cache")
        if (cacheObject == null) {
            cacheObject = JSONObject()
        }
        cacheObject.put("enabled", true)
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

                    // TLS settings auto-fix: If SNI differs from server address, set allowInsecure: true
                    // Also remove flow parameter for TLS (flow is only for XTLS, not standard TLS)
                    val security = streamSettings.optString("security", "")
                    if (security == "tls") {
                        // Remove flow from VLESS users if present (flow is only for XTLS, not TLS)
                        val settings = outbound.optJSONObject("settings")
                        if (settings != null && settings.has("vnext")) {
                            val vnext = settings.optJSONArray("vnext")
                            if (vnext != null && vnext.length() > 0) {
                                val server = vnext.getJSONObject(0)
                                if (server.has("users")) {
                                    val users = server.getJSONArray("users")
                                    for (j in 0 until users.length()) {
                                        val user = users.getJSONObject(j)
                                        if (user.has("flow")) {
                                            user.remove("flow")
                                            Log.d(TAG, "🔧 TLS Auto-fix: Removed flow parameter (flow is only for XTLS, not TLS)")
                                            AiLogHelper.d(TAG, "🔧 CONFIG INJECT: TLS Auto-fix - Removed flow parameter")
                                        }
                                    }
                                    server.put("users", users)
                                    vnext.put(0, server)
                                    settings.put("vnext", vnext)
                                    outbound.put("settings", settings)
                                }
                            }
                        }
                        
                        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings != null) {
                            val serverName = tlsSettings.optString("serverName", "")
                            if (serverName.isNotEmpty()) {
                                // Get server address from outbound settings
                                val serverAddress = when {
                                    settings?.has("vnext") == true -> {
                                        val vnext = settings.optJSONArray("vnext")
                                        vnext?.getJSONObject(0)?.optString("address", "") ?: ""
                                    }
                                    settings?.has("servers") == true -> {
                                        val servers = settings.optJSONArray("servers")
                                        servers?.getJSONObject(0)?.optString("address", "") ?: ""
                                    }
                                    else -> ""
                                }
                                
                                // If SNI differs from server address, allow insecure connections
                                if (serverAddress.isNotEmpty() && serverName != serverAddress) {
                                    tlsSettings.put("allowInsecure", true)
                                    streamSettings.put("tlsSettings", tlsSettings)
                                    Log.d(TAG, "🔧 TLS Auto-fix: SNI ($serverName) differs from server address ($serverAddress), set allowInsecure: true")
                                    AiLogHelper.d(TAG, "🔧 CONFIG INJECT: TLS Auto-fix - SNI ($serverName) differs from server ($serverAddress), allowInsecure: true")
                                }
                            }
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
    fun applyExtremeRamCpuOptimizations(prefs: Preferences, jsonObject: JSONObject) {
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
        var cacheObject = dnsObject.optJSONObject("cache")
        if (cacheObject == null) {
            cacheObject = JSONObject()
        }
        cacheObject.put("enabled", true)
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
}











