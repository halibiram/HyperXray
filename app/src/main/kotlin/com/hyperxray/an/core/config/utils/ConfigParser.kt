package com.hyperxray.an.core.config.utils

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Handles parsing and formatting of Xray configuration JSON.
 * Follows Single Responsibility Principle - only handles parsing/formatting logic.
 */
object ConfigParser {
    private const val TAG = "ConfigParser"

    /**
     * Formats configuration content by:
     * - Removing log.access and log.error if not "none"
     * - Removing flow parameter from VLESS users when security is TLS (flow is only for XTLS, not TLS)
     * - Pretty-printing JSON with 2-space indentation
     * - Fixing escaped slashes
     * 
     * @param content The raw configuration content
     * @return Formatted configuration content
     * @throws JSONException if content is not valid JSON
     */
    @Throws(JSONException::class)
    fun formatConfigContent(content: String): String {
        val jsonObject = JSONObject(content)
        
        // Remove log.access and log.error if not "none"
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
        
        // Remove flow parameter from VLESS users when security is TLS (flow is only for XTLS, not TLS)
        val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                val streamSettings = outbound.optJSONObject("streamSettings")
                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "")
                    if (security == "tls") {
                        val settings = outbound.optJSONObject("settings")
                        if (settings != null && settings.has("vnext")) {
                            val vnext = settings.optJSONArray("vnext")
                            if (vnext != null && vnext.length() > 0) {
                                val server = vnext.getJSONObject(0)
                                if (server.has("users")) {
                                    val users = server.getJSONArray("users")
                                    var flowRemoved = false
                                    for (j in 0 until users.length()) {
                                        val user = users.getJSONObject(j)
                                        if (user.has("flow")) {
                                            user.remove("flow")
                                            flowRemoved = true
                                            Log.d(TAG, "Removed flow parameter from VLESS user (flow is only for XTLS, not TLS)")
                                        }
                                    }
                                    if (flowRemoved) {
                                        server.put("users", users)
                                        vnext.put(0, server)
                                        settings.put("vnext", vnext)
                                        outbound.put("settings", settings)
                                        outbounds.put(i, outbound)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (jsonObject.has("outbounds")) {
                jsonObject.put("outbounds", outbounds)
            } else {
                jsonObject.put("outbound", outbounds)
            }
        }
        
        var formattedContent = jsonObject.toString(2)
        formattedContent = formattedContent.replace("\\/", "/")
        return formattedContent
    }

    /**
     * Extracts all port numbers from a JSON configuration.
     * Recursively searches through all JSON objects and arrays to find valid port numbers (1-65535).
     * 
     * @param jsonContent The JSON content to extract ports from
     * @return Set of unique port numbers found in the configuration
     */
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

    /**
     * Recursively extracts port numbers from a JSON object.
     * 
     * @param jsonObject The JSON object to search
     * @param ports The mutable set to add found ports to
     */
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




