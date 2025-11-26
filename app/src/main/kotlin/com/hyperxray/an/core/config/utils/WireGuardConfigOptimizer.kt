package com.hyperxray.an.core.config.utils

import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.utils.WarpDefaults
import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random

/**
 * Optimizes WireGuard-specific configurations for Cloudflare WARP compatibility.
 * Follows Single Responsibility Principle - only handles WireGuard-specific optimizations.
 */
object WireGuardConfigOptimizer {
    private const val TAG = "WireGuardConfigOptimizer"

    /**
     * Optimizes WireGuard outbound configurations for Cloudflare WARP compatibility.
     * Fixes handshake failures by:
     * 1. Adding reserved bytes [0,0,0] for WARP peers
     * 2. Setting MTU to 1280 (safe mode for better compatibility)
     * 3. Adding workers: 2 for multi-core performance
     * 4. Randomizing endpoint selection from multiple Cloudflare IPs
     */
    @Throws(JSONException::class)
    fun optimizeWireGuardSettings(jsonObject: JSONObject) {
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray == null) {
            return
        }
        
        var optimizedCount = 0
        for (i in 0 until outboundArray.length()) {
            val outbound = outboundArray.getJSONObject(i)
            val protocol = outbound.optString("protocol", "")
            
            if (protocol != "wireguard") {
                continue
            }
            
            val settings = outbound.optJSONObject("settings")
            if (settings == null) {
                continue
            }
            
            // Check peers array
            val peers = settings.optJSONArray("peers")
            if (peers == null || peers.length() == 0) {
                continue
            }
            
            var hasWarpPeer = false
            var endpointUpdated = false
            
            // Process each peer
            for (j in 0 until peers.length()) {
                val peer = peers.getJSONObject(j)
                val publicKey = peer.optString("publicKey", "")
                
                // Check if this is a Cloudflare WARP peer
                if (publicKey == WarpDefaults.PEER_PUBLIC_KEY) {
                    hasWarpPeer = true
                    
                    // Add reserved bytes for WARP (required for handshake)
                    if (!peer.has("reserved")) {
                        val reservedArray = org.json.JSONArray()
                        reservedArray.put(0)
                        reservedArray.put(0)
                        reservedArray.put(0)
                        peer.put("reserved", reservedArray)
                        Log.d(TAG, "✅ Added reserved bytes [0,0,0] to WARP peer[$j]")
                    }
                    
                    // Randomize endpoint if not already set or if current endpoint might be blocked
                    val currentEndpoint = peer.optString("endpoint", "")
                    if (currentEndpoint.isEmpty() || ConfigValidator.shouldRandomizeEndpoint(currentEndpoint)) {
                        val randomEndpoint = selectRandomWarpEndpoint()
                        peer.put("endpoint", randomEndpoint)
                        endpointUpdated = true
                        Log.i(TAG, "🔄 Randomized WARP endpoint: $currentEndpoint -> $randomEndpoint")
                    }
                }
            }
            
            // Set MTU to 1280 (safe mode for better compatibility)
            if (!settings.has("mtu")) {
                settings.put("mtu", 1280)
                Log.d(TAG, "✅ Set WireGuard MTU to 1280 (safe mode)")
            } else {
                val currentMtu = settings.optInt("mtu", 0)
                if (currentMtu > 1280) {
                    settings.put("mtu", 1280)
                    Log.d(TAG, "✅ Reduced WireGuard MTU from $currentMtu to 1280 (safe mode)")
                }
            }
            
            // Add workers for multi-core performance
            if (!settings.has("workers")) {
                settings.put("workers", 2)
                Log.d(TAG, "✅ Added workers: 2 for WireGuard handshake performance")
            }
            
            // CRITICAL: Remove IPv6 addresses if present (many devices don't have IPv6 connectivity)
            // IPv6 addresses cause "network is unreachable" errors and prevent handshake
            if (settings.has("address")) {
                val addressArray = settings.optJSONArray("address")
                if (addressArray != null && addressArray.length() > 0) {
                    val ipv4OnlyArray = org.json.JSONArray()
                    var ipv6Removed = false
                    
                    for (k in 0 until addressArray.length()) {
                        val address = addressArray.getString(k)
                        // Keep only IPv4 addresses (format: x.x.x.x/xx)
                        if (address.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d+$"))) {
                            ipv4OnlyArray.put(address)
                        } else {
                            ipv6Removed = true
                            Log.d(TAG, "⚠️ Removed IPv6 address from WireGuard: $address (IPv6 not supported)")
                        }
                    }
                    
                    // If no IPv4 addresses remain, add default WARP IPv4 address
                    if (ipv4OnlyArray.length() == 0) {
                        ipv4OnlyArray.put("172.16.0.2/32")
                        Log.d(TAG, "✅ Added default WARP IPv4 address: 172.16.0.2/32")
                    }
                    
                    if (ipv6Removed || ipv4OnlyArray.length() != addressArray.length()) {
                        settings.put("address", ipv4OnlyArray)
                        Log.i(TAG, "✅ WireGuard address optimized: ${addressArray.length()} -> ${ipv4OnlyArray.length()} (IPv4 only)")
                    }
                }
            } else {
                // No address specified, add default WARP IPv4 address
                val addressArray = org.json.JSONArray()
                addressArray.put("172.16.0.2/32")
                settings.put("address", addressArray)
                Log.d(TAG, "✅ Added default WARP IPv4 address: 172.16.0.2/32")
            }
            
            // CRITICAL: Ensure WireGuard outbound has proper tag for routing
            val wireguardTag = outbound.optString("tag", "")
            if (wireguardTag.isEmpty()) {
                outbound.put("tag", "warp-out")
                Log.d(TAG, "✅ Added tag 'warp-out' to WireGuard outbound[$i]")
            }
            
            // CRITICAL: Remove any proxySettings from WireGuard outbound
            // WireGuard must connect directly to WARP endpoints, not through another proxy
            // This is essential for UDP handshake to work properly
            if (outbound.has("proxySettings")) {
                val existingProxySettings = outbound.optJSONObject("proxySettings")
                val proxyTag = existingProxySettings?.optString("tag", "")
                Log.w(TAG, "⚠️ WireGuard outbound[$i] has proxySettings pointing to '$proxyTag' - removing (WireGuard requires direct connection)")
                AiLogHelper.w(TAG, "⚠️ CONFIG INJECT: Removed proxySettings from WireGuard outbound (requires direct connection for UDP handshake)")
                outbound.remove("proxySettings")
            }
            
            // Update settings and outbound
            settings.put("peers", peers)
            outbound.put("settings", settings)
            outboundArray.put(i, outbound)
            
            if (hasWarpPeer) {
                optimizedCount++
                Log.i(TAG, "✅ Optimized WireGuard outbound[$i] for WARP (reserved bytes, MTU 1280, workers 2${if (endpointUpdated) ", endpoint randomized" else ""})")
            }
        }
        
        // Update outbounds array
        if (jsonObject.has("outbounds")) {
            jsonObject.put("outbounds", outboundArray)
        } else {
            jsonObject.put("outbound", outboundArray)
        }
        
        // CRITICAL: Add routing rules for WireGuard UDP response handling
        // This ensures UDP response packets from WireGuard endpoints are properly routed back to TUN
        if (optimizedCount > 0) {
            ensureWireGuardRoutingRules(jsonObject)
            Log.i(TAG, "✅ WireGuard optimization complete: $optimizedCount outbound(s) optimized for WARP handshake reliability")
        }
    }
    
    /**
     * Ensures WireGuard outbound is properly configured for handshake.
     * CRITICAL FIX: Adds high-priority routing rule for WireGuard endpoint IP to route through 'direct' outbound.
     * This ensures UDP reply packets from WireGuard endpoints are correctly routed back to TUN interface.
     */
    @Throws(JSONException::class)
    fun ensureWireGuardRoutingRules(jsonObject: JSONObject) {
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray == null) {
            return
        }
        
        var wireguardCount = 0
        var proxySettingsRemovedCount = 0
        var streamSettingsRemovedCount = 0
        val endpointIps = mutableSetOf<String>()
        
        // Extract WireGuard endpoint IPs and verify outbound configuration
        for (i in 0 until outboundArray.length()) {
            val outbound = outboundArray.getJSONObject(i)
            if (outbound.optString("protocol", "") == "wireguard") {
                wireguardCount++
                
                // CRITICAL: Remove proxySettings (WireGuard must connect directly)
                if (outbound.has("proxySettings")) {
                    outbound.remove("proxySettings")
                    proxySettingsRemovedCount++
                    Log.w(TAG, "⚠️ WireGuard outbound[$i] still has proxySettings - removing")
                }
                
                // CRITICAL: Remove streamSettings that might interfere with raw UDP transport
                if (outbound.has("streamSettings")) {
                    outbound.remove("streamSettings")
                    streamSettingsRemovedCount++
                    Log.w(TAG, "⚠️ WireGuard outbound[$i] has streamSettings - removing (interferes with raw UDP)")
                }
                
                // Extract endpoint IPs from peers
                val settings = outbound.optJSONObject("settings")
                if (settings != null) {
                    val peers = settings.optJSONArray("peers")
                    if (peers != null) {
                        for (j in 0 until peers.length()) {
                            val peer = peers.getJSONObject(j)
                            val endpoint = peer.optString("endpoint", "")
                            if (endpoint.isNotEmpty()) {
                                // Extract IP from endpoint (format: "IP:port" or "domain:port")
                                val endpointParts = endpoint.split(":")
                                if (endpointParts.isNotEmpty()) {
                                    val endpointHost = endpointParts[0]
                                    // Check if it's an IP address (not a domain)
                                    if (ConfigValidator.isValidIpAddress(endpointHost)) {
                                        endpointIps.add(endpointHost)
                                        Log.d(TAG, "🔍 Found WireGuard endpoint IP: $endpointHost")
                                    } else {
                                        Log.w(TAG, "⚠️ WireGuard endpoint is domain '$endpointHost' (not IP) - routing rule will be skipped. Endpoint should be pre-resolved to IP.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (wireguardCount > 0) {
            // Update outbounds array
            if (jsonObject.has("outbounds")) {
                jsonObject.put("outbounds", outboundArray)
            } else {
                jsonObject.put("outbound", outboundArray)
            }
            
            if (proxySettingsRemovedCount > 0) {
                Log.i(TAG, "✅ Removed proxySettings from $proxySettingsRemovedCount WireGuard outbound(s)")
            }
            if (streamSettingsRemovedCount > 0) {
                Log.i(TAG, "✅ Removed streamSettings from $streamSettingsRemovedCount WireGuard outbound(s)")
            }
            
            // CRITICAL: Add high-priority routing rule for WireGuard endpoint IPs
            if (endpointIps.isNotEmpty()) {
                addWireGuardEndpointRoutingRule(jsonObject, endpointIps)
                Log.i(TAG, "✅ WireGuard configuration verified: $wireguardCount outbound(s), ${endpointIps.size} endpoint IP(s) routed through 'direct'")
            } else {
                Log.w(TAG, "⚠️ WireGuard configuration verified: $wireguardCount outbound(s), but no endpoint IPs found (endpoints may be domains, not pre-resolved)")
            }
        }
    }
    
    /**
     * Adds high-priority routing rule for WireGuard endpoint IPs to route through 'direct' outbound.
     * This ensures UDP reply packets from WireGuard endpoints are correctly routed back to TUN.
     */
    @Throws(JSONException::class)
    private fun addWireGuardEndpointRoutingRule(jsonObject: JSONObject, endpointIps: Set<String>) {
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
        
        // Create routing rule for WireGuard endpoint IPs
        val endpointRule = JSONObject()
        endpointRule.put("type", "field")
        
        val ipArray = org.json.JSONArray()
        endpointIps.forEach { ip ->
            ipArray.put(ip)
        }
        endpointRule.put("ip", ipArray)
        endpointRule.put("outboundTag", "direct")
        
        // CRITICAL: Explicitly allow UDP (and TCP for completeness)
        val networkArray = org.json.JSONArray()
        networkArray.put("udp")
        networkArray.put("tcp")
        endpointRule.put("network", networkArray)
        
        // Insert at index 0 (highest priority) to ensure WireGuard endpoint traffic goes direct
        val newRulesArray = org.json.JSONArray()
        newRulesArray.put(endpointRule)
        for (i in 0 until rulesArray.length()) {
            newRulesArray.put(rulesArray.get(i))
        }
        routingObject.put("rules", newRulesArray)
        jsonObject.put("routing", routingObject)
        
        Log.i(TAG, "✅ Added high-priority routing rule for WireGuard endpoint IPs: ${endpointIps.joinToString(", ")} -> 'direct' outbound")
        AiLogHelper.i(TAG, "✅ CONFIG INJECT: Added WireGuard endpoint routing rule (${endpointIps.size} IP(s))")
    }
    
    /**
     * Selects a random Cloudflare WARP endpoint from available IPs.
     * Prioritizes IPv4 for better compatibility (many devices don't have IPv6 connectivity).
     * Falls back to IPv6 if IPv4 endpoints are not available.
     */
    private fun selectRandomWarpEndpoint(): String {
        // Prioritize IPv4 endpoints for better compatibility
        // Many Android devices don't have IPv6 connectivity, causing "network is unreachable" errors
        val ipv4Endpoints = WarpDefaults.WARP_IPS_V4
        if (ipv4Endpoints.isNotEmpty()) {
            val selected = ipv4Endpoints[Random.nextInt(ipv4Endpoints.size)]
            Log.d(TAG, "Selected IPv4 WARP endpoint: $selected (from ${ipv4Endpoints.size} IPv4 options)")
            return selected
        }
        
        // Fallback to IPv6 if IPv4 not available
        val ipv6Endpoints = WarpDefaults.WARP_IPS_V6
        if (ipv6Endpoints.isNotEmpty()) {
            val selected = ipv6Endpoints[Random.nextInt(ipv6Endpoints.size)]
            Log.d(TAG, "Selected IPv6 WARP endpoint: $selected (IPv4 not available)")
            return selected
        }
        
        // Final fallback to default
        Log.w(TAG, "No WARP endpoints available, using default: ${WarpDefaults.ENDPOINT}")
        return WarpDefaults.ENDPOINT
    }
    
    /**
     * CRITICAL: Disables XTLS-Vision flow parameter when WARP chaining is active.
     * 
     * XTLS-Vision (flow: 'xtls-rprx-vision') requires direct connection to the server.
     * When traffic is chained through WireGuard (WARP), it's no longer a direct connection,
     * causing Xray-core to throw error: "XTLS only supports TLS and REALITY directly".
     * 
     * This function:
     * 1. Checks if WireGuard outbound exists (indicating WARP is enabled)
     * 2. Checks if VLESS/VMess outbounds have proxySettings pointing to WireGuard (chaining active)
     * 3. If chaining is active, removes flow parameter from VLESS/VMess users
     * 
     * Note: This only modifies the generated JSON, not the original config or database settings.
     */
    @Throws(JSONException::class)
    fun disableFlowWhenWarpChaining(jsonObject: JSONObject) {
        val outboundArray = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outboundArray == null) {
            return
        }
        
        // Step 1: Check if WireGuard outbound exists (WARP is enabled)
        var hasWireGuardOutbound = false
        var wireGuardTag: String? = null
        
        for (i in 0 until outboundArray.length()) {
            val outbound = outboundArray.getJSONObject(i)
            val protocol = outbound.optString("protocol", "").lowercase()
            if (protocol == "wireguard") {
                hasWireGuardOutbound = true
                wireGuardTag = outbound.optString("tag", "warp-out")
                if (wireGuardTag.isEmpty()) {
                    wireGuardTag = "warp-out"
                }
                break
            }
        }
        
        // If no WireGuard outbound exists, WARP is not enabled - skip
        if (!hasWireGuardOutbound || wireGuardTag == null) {
            Log.d(TAG, "No WireGuard outbound found - WARP not enabled, skipping flow removal")
            return
        }
        
        Log.d(TAG, "WireGuard outbound found (tag: $wireGuardTag) - checking for WARP chaining")
        
        // Step 2: Check all VLESS/VMess outbounds for proxySettings pointing to WireGuard
        var flowRemovedCount = 0
        
        for (i in 0 until outboundArray.length()) {
            val outbound = outboundArray.getJSONObject(i)
            val protocol = outbound.optString("protocol", "").lowercase()
            
            // Only process VLESS and VMess outbounds
            if (protocol != "vless" && protocol != "vmess") {
                continue
            }
            
            // Check if this outbound has proxySettings pointing to WireGuard (chaining active)
            val proxySettings = outbound.optJSONObject("proxySettings")
            val isChainedToWarp = if (proxySettings != null) {
                val proxyTag = proxySettings.optString("tag", "")
                proxyTag == wireGuardTag || proxyTag == "warp-out"
            } else {
                false
            }
            
            // If not chained to WARP, skip this outbound
            if (!isChainedToWarp) {
                continue
            }
            
            Log.d(TAG, "Found ${protocol.uppercase()} outbound[$i] chained to WARP - removing flow parameter")
            
            // Step 3: Remove flow parameter from users
            val settings = outbound.optJSONObject("settings")
            if (settings == null) {
                continue
            }
            
            // For VLESS: settings.vnext[0].users[]
            if (protocol == "vless" && settings.has("vnext")) {
                val vnext = settings.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    for (j in 0 until vnext.length()) {
                        val server = vnext.getJSONObject(j)
                        if (server.has("users")) {
                            val users = server.getJSONArray("users")
                            for (k in 0 until users.length()) {
                                val user = users.getJSONObject(k)
                                if (user.has("flow")) {
                                    val flowValue = user.optString("flow", "")
                                    // Only remove non-empty flow values (xtls-rprx-vision, etc.)
                                    if (flowValue.isNotEmpty()) {
                                        user.remove("flow")
                                        flowRemovedCount++
                                        Log.d(TAG, "  Removed flow parameter from VLESS user[$k] (flow was: '$flowValue')")
                                        AiLogHelper.d(TAG, "🔧 CONFIG INJECT: WARP Chaining - Removed flow '$flowValue' from VLESS user (XTLS requires direct connection)")
                                    }
                                }
                            }
                            server.put("users", users)
                            vnext.put(j, server)
                        }
                    }
                    settings.put("vnext", vnext)
                    outbound.put("settings", settings)
                }
            }
            
            // For VMess: settings.vnext[0].users[] (same structure as VLESS)
            if (protocol == "vmess" && settings.has("vnext")) {
                val vnext = settings.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    for (j in 0 until vnext.length()) {
                        val server = vnext.getJSONObject(j)
                        if (server.has("users")) {
                            val users = server.getJSONArray("users")
                            for (k in 0 until users.length()) {
                                val user = users.getJSONObject(k)
                                if (user.has("flow")) {
                                    val flowValue = user.optString("flow", "")
                                    if (flowValue.isNotEmpty()) {
                                        user.remove("flow")
                                        flowRemovedCount++
                                        Log.d(TAG, "  Removed flow parameter from VMess user[$k] (flow was: '$flowValue')")
                                        AiLogHelper.d(TAG, "🔧 CONFIG INJECT: WARP Chaining - Removed flow '$flowValue' from VMess user")
                                    }
                                }
                            }
                            server.put("users", users)
                            vnext.put(j, server)
                        }
                    }
                    settings.put("vnext", vnext)
                    outbound.put("settings", settings)
                }
            }
            
            // Update outbound in array
            outboundArray.put(i, outbound)
        }
        
        // Update outbounds array
        if (jsonObject.has("outbounds")) {
            jsonObject.put("outbounds", outboundArray)
        } else {
            jsonObject.put("outbound", outboundArray)
        }
        
        if (flowRemovedCount > 0) {
            Log.i(TAG, "✅ WARP Chaining Fix: Removed flow parameter from $flowRemovedCount user(s) to prevent 'XTLS only supports TLS/REALITY directly' error")
            AiLogHelper.i(TAG, "✅ CONFIG INJECT: WARP Chaining Fix - Removed flow parameter from $flowRemovedCount user(s) (XTLS-Vision requires direct connection, not through WireGuard)")
        } else {
            Log.d(TAG, "No flow parameters found to remove (or WARP chaining not active)")
        }
    }
}




