package com.hyperxray.an.core.config.utils

import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Enhances Xray configuration with additional features like sniffing, DNS logging,
 * bypass routing rules, UDP support, and outbound tags.
 * Follows Single Responsibility Principle - only handles configuration enhancements.
 */
object ConfigEnhancer {
    private const val TAG = "ConfigEnhancer"

    /**
     * Enables sniffing in Xray config for domain and protocol detection.
     * Adds sniffing configuration to all inbound handlers.
     * Sniffing is used to detect the actual destination domain from traffic.
     */
    @Throws(JSONException::class)
    fun enableSniffing(jsonObject: JSONObject) {
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
    fun enableDnsLogging(jsonObject: JSONObject) {
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

    /**
     * Applies bypass routing rules for domains and IPs.
     * These domains/IPs will be routed through "direct" outbound (bypassing VPN).
     */
    @Throws(JSONException::class)
    fun applyBypassRoutingRules(prefs: Preferences, jsonObject: JSONObject) {
        val bypassDomains = prefs.bypassDomains.filter { it.isNotBlank() }
        val bypassIps = prefs.bypassIps.filter { it.isNotBlank() }

        if (bypassDomains.isEmpty() && bypassIps.isEmpty()) {
            Log.d(TAG, "No bypass domains/IPs configured, skipping bypass routing rules")
            return
        }

        Log.d(TAG, "Applying bypass routing rules: ${bypassDomains.size} domains, ${bypassIps.size} IPs")

        // Ensure "direct" outbound exists with domainStrategy: UseIP for UDP stability
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray != null) {
            var hasDirectOutbound = false
            var directOutboundIndex = -1
            for (i in 0 until outboundArray.length()) {
                val outbound = outboundArray.getJSONObject(i)
                if (outbound.optString("tag") == "direct") {
                    hasDirectOutbound = true
                    directOutboundIndex = i
                    break
                }
            }
            if (!hasDirectOutbound) {
                val directOutbound = JSONObject()
                directOutbound.put("protocol", "freedom")
                directOutbound.put("tag", "direct")
                // CRITICAL: Add domainStrategy: UseIP for UDP socket state handling on Android
                val settings = JSONObject()
                settings.put("domainStrategy", "UseIP")
                directOutbound.put("settings", settings)
                outboundArray.put(directOutbound)
                if (jsonObject.has("outbounds")) {
                    jsonObject.put("outbounds", outboundArray)
                } else {
                    jsonObject.put("outbound", outboundArray)
                }
                Log.i(TAG, "✅ Added 'direct' outbound with domainStrategy: UseIP for UDP stability")
                AiLogHelper.i(TAG, "✅ CONFIG INJECT: Added 'direct' outbound with domainStrategy: UseIP")
            } else if (directOutboundIndex >= 0) {
                // Update existing direct outbound to ensure domainStrategy: UseIP
                val directOutbound = outboundArray.getJSONObject(directOutboundIndex)
                var settings = directOutbound.optJSONObject("settings")
                if (settings == null) {
                    settings = JSONObject()
                    directOutbound.put("settings", settings)
                }
                if (!settings.has("domainStrategy") || settings.optString("domainStrategy") != "UseIP") {
                    settings.put("domainStrategy", "UseIP")
                    directOutbound.put("settings", settings)
                    outboundArray.put(directOutboundIndex, directOutbound)
                    if (jsonObject.has("outbounds")) {
                        jsonObject.put("outbounds", outboundArray)
                    } else {
                        jsonObject.put("outbound", outboundArray)
                    }
                    Log.i(TAG, "✅ Updated 'direct' outbound with domainStrategy: UseIP for UDP stability")
                    AiLogHelper.i(TAG, "✅ CONFIG INJECT: Updated 'direct' outbound with domainStrategy: UseIP")
                }
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
    fun ensureUdpSupportInDokodemoInbounds(jsonObject: JSONObject) {
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
                    // This ensures UDP response packets are properly routed back to TUN
                    put("followRedirect", true)
                    // CRITICAL: Set userLevel to 0 to use policy level 0 settings
                    // This ensures UDP timeout settings (connIdle=3600s) are applied
                    put("userLevel", 0)
                    // CRITICAL: Enable domain sniffing for better UDP response routing
                    // This helps Xray-core identify and route UDP response packets correctly
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
    fun configureUdpTimeoutSettings(jsonObject: JSONObject) {
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
    fun removePort53DnsRoutingRule(jsonObject: JSONObject) {
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
    fun ensureOutboundTags(jsonObject: JSONObject) {
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

    /**
     * Ensures UDP support in VLESS/Trojan outbounds and disables Mux if present.
     * 
     * CRITICAL: For WireGuard over Xray-core, UDP traffic must be properly forwarded.
     * 
     * VLESS/Trojan outbounds support UDP by default when network is "tcp" (UDP over TCP),
     * but Mux (multiplexing) can cause issues with heavy UDP traffic like WireGuard.
     * 
     * This function:
     * - Verifies outbound streamSettings.network supports TCP (required for UDP over TCP)
     * - Disables Mux if present (Mux causes issues with UDP traffic)
     * - Logs outbound configuration for debugging
     */
    @Throws(JSONException::class)
    fun ensureUdpSupportInOutbounds(jsonObject: JSONObject) {
        Log.d(TAG, "Ensuring UDP support in outbounds for WireGuard...")
        
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray == null || outboundArray.length() == 0) {
            Log.w(TAG, "⚠️ No outbounds found - cannot ensure UDP support")
            return
        }

        var muxDisabledCount = 0
        var udpVerifiedCount = 0
        
        for (i in 0 until outboundArray.length()) {
            val outbound = outboundArray.getJSONObject(i)
            val protocol = outbound.optString("protocol", "").lowercase()
            
            // Only process VLESS, VMESS, and Trojan outbounds
            if (protocol != "vless" && protocol != "vmess" && protocol != "trojan") {
                continue
            }
            
            Log.d(TAG, "Checking outbound[$i] (protocol: $protocol) for UDP support...")
            
            // Check streamSettings
            val streamSettings = outbound.optJSONObject("streamSettings")
            if (streamSettings != null) {
                val network = streamSettings.optString("network", "tcp").lowercase()
                
                // VLESS/Trojan support UDP over TCP, so "tcp" network is correct
                // But we should log it for verification
                if (network == "tcp" || network == "ws" || network == "grpc" || network == "http") {
                    Log.d(TAG, "  ✅ Network '$network' supports UDP over TCP (protocol: $protocol)")
                    udpVerifiedCount++
                } else {
                    Log.w(TAG, "  ⚠️ Network '$network' may not support UDP properly (protocol: $protocol)")
                }
                
                // CRITICAL: Disable Mux if present - Mux causes issues with UDP traffic
                if (streamSettings.has("muxSettings")) {
                    val muxSettings = streamSettings.optJSONObject("muxSettings")
                    val muxEnabled = muxSettings?.optBoolean("enabled", false) ?: false
                    
                    if (muxEnabled) {
                        Log.w(TAG, "  ⚠️ Mux is ENABLED in outbound[$i] - disabling for UDP stability")
                        Log.w(TAG, "     Mux causes issues with heavy UDP traffic like WireGuard")
                        muxSettings.put("enabled", false)
                        streamSettings.put("muxSettings", muxSettings)
                        muxDisabledCount++
                        AiLogHelper.w(TAG, "⚠️ CONFIG INJECT: Disabled Mux in outbound[$i] for UDP stability")
                    } else {
                        Log.d(TAG, "  ✅ Mux is already disabled")
                    }
                } else {
                    Log.d(TAG, "  ✅ No Mux settings found (Mux disabled by default)")
                }
                
                // Log security settings for debugging
                val security = streamSettings.optString("security", "")
                if (security.isNotEmpty()) {
                    Log.d(TAG, "  Security: $security")
                    
                    // Log TLS/REALITY settings for debugging SNI issues
                    if (security == "tls") {
                        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings != null) {
                            val serverName = tlsSettings.optString("serverName", "")
                            val fingerprint = tlsSettings.optString("fingerprint", "")
                            Log.d(TAG, "    TLS serverName (SNI): $serverName")
                            Log.d(TAG, "    TLS fingerprint: $fingerprint")
                        }
                    } else if (security == "reality") {
                        val realitySettings = streamSettings.optJSONObject("realitySettings")
                        if (realitySettings != null) {
                            val serverName = realitySettings.optString("serverName", "")
                            val fingerprint = realitySettings.optString("fingerprint", "")
                            Log.d(TAG, "    REALITY serverName: $serverName")
                            Log.d(TAG, "    REALITY fingerprint: $fingerprint")
                        }
                    }
                }
            } else {
                // No streamSettings - default is TCP which supports UDP over TCP
                Log.d(TAG, "  ✅ No streamSettings (default TCP - supports UDP over TCP)")
                udpVerifiedCount++
            }
            
            // Log server address for debugging
            val settings = outbound.optJSONObject("settings")
            if (settings != null) {
                if (protocol == "vless" || protocol == "vmess") {
                    val vnext = settings.optJSONArray("vnext")
                    if (vnext != null && vnext.length() > 0) {
                        val server = vnext.getJSONObject(0)
                        val address = server.optString("address", "")
                        val port = server.optInt("port", 0)
                        Log.d(TAG, "  Server: $address:$port")
                    }
                } else if (protocol == "trojan") {
                    val servers = settings.optJSONArray("servers")
                    if (servers != null && servers.length() > 0) {
                        val server = servers.getJSONObject(0)
                        val address = server.optString("address", "")
                        val port = server.optInt("port", 0)
                        Log.d(TAG, "  Server: $address:$port")
                    }
                }
            }
        }
        
        // Update outbounds array if modified
        if (muxDisabledCount > 0) {
            if (jsonObject.has("outbounds")) {
                jsonObject.put("outbounds", outboundArray)
            } else {
                jsonObject.put("outbound", outboundArray)
            }
        }
        
        if (udpVerifiedCount > 0 || muxDisabledCount > 0) {
            Log.i(TAG, "✅ UDP support verified in $udpVerifiedCount outbound(s)")
            if (muxDisabledCount > 0) {
                Log.i(TAG, "✅ Disabled Mux in $muxDisabledCount outbound(s) for UDP stability")
                AiLogHelper.i(TAG, "✅ CONFIG INJECT: Disabled Mux in $muxDisabledCount outbound(s) for WireGuard UDP support")
            }
        } else {
            Log.w(TAG, "⚠️ No VLESS/VMESS/Trojan outbounds found to verify UDP support")
        }
    }
}











