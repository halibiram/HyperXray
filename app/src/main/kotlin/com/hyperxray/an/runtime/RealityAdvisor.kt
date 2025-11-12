package com.hyperxray.an.runtime

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * RealityAdvisor: Generates Xray REALITY profile and policy JSON files.
 * 
 * Creates xray_reality_profile_v5.json and xray_runtime_policy_v5.json
 * with ALPN/SNI/shortId rotation and placeholder support.
 */
class RealityAdvisor(
    private val outputDir: File
) {
    private val TAG = "RealityAdvisor"
    
    private val PROFILE_FILE = "xray_reality_profile_v5.json"
    private val POLICY_FILE = "xray_runtime_policy_v5.json"
    
    /**
     * Generate REALITY profile for a service type and routing decision.
     * 
     * @param serviceType Service type index (0-7)
     * @param route Routing decision (0=proxy, 1=direct, 2=optimized)
     * @return Profile JSON object
     */
    fun getProfile(serviceType: Int, route: Int): JSONObject {
        val profile = JSONObject().apply {
            put("version", "v5")
            put("serviceType", serviceType)
            put("route", route)
            put("timestamp", System.currentTimeMillis())
            
            // REALITY configuration
            put("reality", JSONObject().apply {
                put("dest", "REPLACE_WITH_DEST_HOST:443") // Placeholder
                put("serverNames", generateServerNames(serviceType))
                put("privateKey", "REPLACE_WITH_PRIVATE_KEY") // Placeholder
                put("shortIds", generateShortIds())
                put("alpn", generateAlpnList(route))
            })
        }
        
        return profile
    }
    
    /**
     * Write policy JSON with latency and throughput targets.
     * 
     * @param latTargetMs Target latency in milliseconds
     * @param tputTargetKbps Target throughput in kbps
     */
    fun writePolicy(latTargetMs: Float, tputTargetKbps: Float) {
        try {
            val policy = JSONObject().apply {
                put("version", "v5")
                put("timestamp", System.currentTimeMillis())
                
                put("targets", JSONObject().apply {
                    put("latencyMs", latTargetMs)
                    put("throughputKbps", tputTargetKbps)
                })
                
                put("routing", JSONObject().apply {
                    put("proxy", JSONObject().apply {
                        put("enabled", true)
                        put("priority", 1)
                    })
                    put("direct", JSONObject().apply {
                        put("enabled", true)
                        put("priority", 2)
                    })
                    put("optimized", JSONObject().apply {
                        put("enabled", true)
                        put("priority", 3)
                    })
                })
                
                val adaptiveObj = JSONObject().apply {
                    put("enabled", true)
                    put("windowSize", 60)
                    put("updateInterval", 30)
                }
                put("adaptive", adaptiveObj)
            }
            
            val policyFile = File(outputDir, POLICY_FILE)
            outputDir.mkdirs()
            
            FileWriter(policyFile).use { writer ->
                writer.write(policy.toString()) // JSON string
                writer.flush()
            }
            
            Log.i(TAG, "Written policy to ${policyFile.absolutePath}")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing policy file: ${e.message}", e)
        }
    }
    
    /**
     * Write profile JSON for a service type and route.
     */
    fun writeProfile(serviceType: Int, route: Int) {
        try {
            val profile = getProfile(serviceType, route)
            val profileFile = File(outputDir, PROFILE_FILE)
            outputDir.mkdirs()
            
            FileWriter(profileFile).use { writer ->
                writer.write(profile.toString()) // JSON string
                writer.flush()
            }
            
            Log.i(TAG, "Written profile to ${profileFile.absolutePath}")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing profile file: ${e.message}", e)
        }
    }
    
    /**
     * Generate server names based on service type.
     */
    private fun generateServerNames(serviceType: Int): List<String> {
        val serviceDomains = mapOf(
            0 to listOf("youtube.com", "googlevideo.com", "ytimg.com"),
            1 to listOf("netflix.com", "nflximg.net", "nflxvideo.net"),
            2 to listOf("twimg.com", "twitter.com", "t.co"),
            3 to listOf("instagram.com", "cdninstagram.com", "fbcdn.net"),
            4 to listOf("tiktok.com", "tiktokcdn.com", "tiktokv.com"),
            5 to listOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
            6 to listOf("spotify.com", "spotifycdn.com", "scdn.co"),
            7 to listOf("example.com", "cloudflare.com", "google.com")
        )
        
        return serviceDomains[serviceType] ?: serviceDomains[7]!!
    }
    
    /**
     * Generate short IDs for REALITY (rotation support).
     */
    private fun generateShortIds(): List<String> {
        // Generate 3 short IDs for rotation
        return listOf(
            generateRandomShortId(),
            generateRandomShortId(),
            generateRandomShortId()
        )
    }
    
    /**
     * Generate random short ID (8 hex characters).
     */
    private fun generateRandomShortId(): String {
        val chars = "0123456789abcdef"
        return (1..8).map { chars.random() }.joinToString("")
    }
    
    /**
     * Generate ALPN list based on routing decision.
     */
    private fun generateAlpnList(route: Int): List<String> {
        return when (route) {
            0 -> listOf("h2", "http/1.1") // Proxy: standard ALPN
            1 -> listOf("h2", "h3", "http/1.1") // Direct: all protocols
            2 -> listOf("h3", "h2") // Optimized: prefer h3
            else -> listOf("h2", "http/1.1")
        }
    }
    
    /**
     * Validate that placeholders are replaced before use.
     */
    fun validateProfile(profileFile: File): Boolean {
        return try {
            val content = profileFile.readText()
            if (content.contains("REPLACE_WITH") || content.contains("REPLACE")) {
                Log.w(TAG, "Profile contains placeholders - must be replaced before use")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating profile: ${e.message}", e)
            false
        }
    }
}

