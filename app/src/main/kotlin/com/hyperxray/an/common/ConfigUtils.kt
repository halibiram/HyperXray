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

        return jsonObject.toString(2)
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
}

