package com.hyperxray.an.core.config.utils

import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Main configuration injection orchestrator.
 * Coordinates all config transformations and injections.
 * Follows Single Responsibility Principle - only handles injection orchestration.
 */
object ConfigInjector {
    private const val TAG = "ConfigInjector"

    /**
     * Injects stats service with default API port from preferences.
     * 
     * @param prefs Preferences instance
     * @param configContent Original config content
     * @return Config JSON string with stats service injected
     * @throws JSONException if config is not valid JSON
     */
    @Throws(JSONException::class)
    fun injectStatsService(prefs: Preferences, configContent: String): String {
        return injectStatsServiceWithPort(prefs, configContent, prefs.apiPort)
    }

    /**
     * Injects stats service with specified API port.
     * Uses optimized two-phase injection for better performance.
     * 
     * @param prefs Preferences instance
     * @param configContent Original config content
     * @param apiPort API port to inject
     * @return Config JSON string with stats service injected
     * @throws JSONException if config is not valid JSON
     */
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
     * @throws JSONException if config is not valid JSON
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
        ConfigEnhancer.enableSniffing(jsonObject)
        val sniffingDuration = System.currentTimeMillis() - sniffingStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Sniffing enabled (duration: ${sniffingDuration}ms)")

        // Enable DNS cache and resolver logging
        val dnsStartTime = System.currentTimeMillis()
        ConfigEnhancer.enableDnsLogging(jsonObject)
        val dnsDuration = System.currentTimeMillis() - dnsStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: DNS logging enabled (duration: ${dnsDuration}ms)")

        // Apply aggressive speed optimizations
        val speedOptStartTime = System.currentTimeMillis()
        ConfigOptimizer.applySpeedOptimizations(prefs, jsonObject)
        val speedOptDuration = System.currentTimeMillis() - speedOptStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Speed optimizations applied (duration: ${speedOptDuration}ms)")

        // Apply extreme RAM/CPU optimizations if enabled
        if (prefs.extremeRamCpuOptimizations) {
            Log.d(TAG, "Extreme RAM/CPU optimizations ENABLED - applying...")
            AiLogHelper.i(TAG, "🔧 CONFIG INJECT: Extreme RAM/CPU optimizations ENABLED - applying...")
            val extremeOptStartTime = System.currentTimeMillis()
            ConfigOptimizer.applyExtremeRamCpuOptimizations(prefs, jsonObject)
            val extremeOptDuration = System.currentTimeMillis() - extremeOptStartTime
            AiLogHelper.i(TAG, "✅ CONFIG INJECT: Extreme RAM/CPU optimizations applied (duration: ${extremeOptDuration}ms)")
        } else {
            Log.d(TAG, "Extreme RAM/CPU optimizations DISABLED")
            AiLogHelper.d(TAG, "ℹ️ CONFIG INJECT: Extreme RAM/CPU optimizations DISABLED")
        }

        // Apply bypass domain/IP routing rules
        val bypassStartTime = System.currentTimeMillis()
        ConfigEnhancer.applyBypassRoutingRules(prefs, jsonObject)
        val bypassDuration = System.currentTimeMillis() - bypassStartTime
        AiLogHelper.d(TAG, "✅ CONFIG INJECT: Bypass routing rules applied (duration: ${bypassDuration}ms)")

        // Ensure all outbounds have tags for stats collection
        ConfigEnhancer.ensureOutboundTags(jsonObject)
        
        // CRITICAL: Optimize WireGuard configurations for WARP handshake reliability
        WireGuardConfigOptimizer.optimizeWireGuardSettings(jsonObject)
        
        // CRITICAL: Disable XTLS-Vision flow when WARP chaining is active
        // XTLS only supports TLS and REALITY directly, not through proxy chaining (WireGuard)
        WireGuardConfigOptimizer.disableFlowWhenWarpChaining(jsonObject)
        
        // CRITICAL: Enable UDP support in dokodemo-door inbounds for TUN transparent proxy
        // This ensures Xray can receive both TCP and UDP packets from the TUN interface
        ConfigEnhancer.ensureUdpSupportInDokodemoInbounds(jsonObject)
        
        // CRITICAL: Configure UDP timeout settings to prevent closed pipe errors
        // This ensures UDP connections stay alive longer and are closed gracefully
        ConfigEnhancer.configureUdpTimeoutSettings(jsonObject)
        
        // CRITICAL: Ensure UDP support in outbounds and disable Mux for WireGuard
        // Mux causes issues with heavy UDP traffic like WireGuard handshake
        ConfigEnhancer.ensureUdpSupportInOutbounds(jsonObject)
        
        // NOTE: Port 53 routing rule removal disabled - causes startup issues
        // ConfigEnhancer.removePort53DnsRoutingRule(jsonObject)
        
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
            
            // Log TLS settings for debugging SNI issues
            val outbounds = finalJson.optJSONArray("outbounds") ?: finalJson.optJSONArray("outbound")
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                val streamSettings = outbound.optJSONObject("streamSettings")
                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "")
                    Log.d(TAG, "  - Stream security: $security")
                    AiLogHelper.d(TAG, "🔍 CONFIG INJECT: Stream security: $security")
                    
                    if (security == "tls") {
                        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings != null) {
                            val serverName = tlsSettings.optString("serverName", "")
                            val fingerprint = tlsSettings.optString("fingerprint", "")
                            Log.d(TAG, "  - TLS serverName (SNI): $serverName")
                            Log.d(TAG, "  - TLS fingerprint: $fingerprint")
                            AiLogHelper.d(TAG, "🔍 CONFIG INJECT: TLS serverName (SNI): $serverName, fingerprint: $fingerprint")
                        } else {
                            Log.w(TAG, "  - ⚠️ TLS security but no tlsSettings found!")
                            AiLogHelper.w(TAG, "⚠️ CONFIG INJECT: TLS security but no tlsSettings found!")
                        }
                    }
                }
            }
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
     * @throws JSONException if config is not valid JSON
     */
    @Throws(JSONException::class)
    fun injectApiPort(configContent: String, apiPort: Int): String {
        val startTime = System.currentTimeMillis()
        
        // Use default port if apiPort is 0 or invalid
        val effectivePort = if (apiPort > 0 && apiPort <= 65535) {
            apiPort
        } else {
            // Default API port for Xray-core gRPC StatsService
            val defaultPort = 65276
            AiLogHelper.w(TAG, "⚠️ CONFIG API PORT: Invalid API port ($apiPort), using default: $defaultPort")
            defaultPort
        }
        
        AiLogHelper.d(TAG, "🔧 CONFIG API PORT: Injecting API port: $effectivePort")
        val jsonObject = JSONObject(configContent)
        
        val apiObject = JSONObject()
        apiObject.put("tag", "api")
        apiObject.put("listen", "127.0.0.1:$effectivePort")
        val servicesArray = org.json.JSONArray()
        servicesArray.put("StatsService")
        apiObject.put("services", servicesArray)

        jsonObject.put("api", apiObject)

        val finalConfig = jsonObject.toString(2)
        val duration = System.currentTimeMillis() - startTime
        AiLogHelper.d(TAG, "✅ CONFIG API PORT: API port injected (port: $effectivePort, duration: ${duration}ms)")
        return finalConfig
    }
}




