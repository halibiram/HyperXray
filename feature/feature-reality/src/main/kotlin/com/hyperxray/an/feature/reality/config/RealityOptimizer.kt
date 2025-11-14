package com.hyperxray.an.feature.reality.config

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * RealityOptimizer patches placeholders in VLESS Reality base template.
 * Replaces empty strings and empty arrays with actual configuration values.
 *
 * Supported placeholders:
 * - address: Server address
 * - port: Server port (default: 443)
 * - id: User UUID
 * - flow: Flow control (optional)
 * - dest: Reality destination
 * - serverNames: Array of server names for SNI
 * - privateKey: Reality private key
 * - shortIds: Array of short IDs
 * - spiderX: SpiderX parameter (optional)
 */
object RealityOptimizer {
    private const val TAG = "RealityOptimizer"
    
    /**
     * Reality configuration parameters
     */
    data class RealityParams(
        val address: String,
        val port: Int = 443,
        val id: String,
        val flow: String = "",
        val dest: String,
        val serverNames: List<String> = emptyList(),
        val privateKey: String,
        val shortIds: List<String> = emptyList(),
        val spiderX: String = ""
    )
    
    /**
     * Result of optimization/patching
     */
    data class OptimizeResult(
        val success: Boolean,
        val patchedJson: String?,
        val error: String?
    )
    
    /**
     * Patches the VLESS Reality base template with actual configuration values.
     * 
     * @param templateJson Base template JSON string
     * @param params Reality configuration parameters
     * @return OptimizeResult with patched JSON or error
     */
    fun patchTemplate(templateJson: String, params: RealityParams): OptimizeResult {
        return try {
            val jsonObject = JSONObject(templateJson)
            
            // Patch outbounds configuration
            val outbounds = jsonObject.optJSONArray("outbounds")
            if (outbounds == null || outbounds.length() == 0) {
                return OptimizeResult(
                    success = false,
                    patchedJson = null,
                    error = "No outbounds found in template"
                )
            }
            
            val outbound = outbounds.getJSONObject(0)
            
            // Patch settings.vnext[0].address
            val settings = outbound.getJSONObject("settings")
            val vnext = settings.getJSONArray("vnext")
            val vnextServer = vnext.getJSONObject(0)
            vnextServer.put("address", params.address)
            vnextServer.put("port", params.port)
            
            // Patch settings.vnext[0].users[0]
            val users = vnextServer.getJSONArray("users")
            val user = users.getJSONObject(0)
            user.put("id", params.id)
            if (params.flow.isNotEmpty()) {
                user.put("flow", params.flow)
            } else {
                user.put("flow", "")
            }
            
            // Patch streamSettings.realitySettings
            val streamSettings = outbound.getJSONObject("streamSettings")
            val realitySettings = streamSettings.getJSONObject("realitySettings")
            
            realitySettings.put("dest", params.dest)
            realitySettings.put("privateKey", params.privateKey)
            
            // Patch serverNames array
            if (params.serverNames.isNotEmpty()) {
                val serverNamesArray = JSONArray()
                params.serverNames.forEach { serverNamesArray.put(it) }
                realitySettings.put("serverNames", serverNamesArray)
            } else {
                realitySettings.put("serverNames", JSONArray())
            }
            
            // Patch shortIds array
            if (params.shortIds.isNotEmpty()) {
                val shortIdsArray = JSONArray()
                params.shortIds.forEach { shortIdsArray.put(it) }
                realitySettings.put("shortIds", shortIdsArray)
            } else {
                realitySettings.put("shortIds", JSONArray())
            }
            
            // Patch spiderX (optional)
            if (params.spiderX.isNotEmpty()) {
                realitySettings.put("spiderX", params.spiderX)
            } else {
                realitySettings.put("spiderX", "")
            }
            
            // Generate formatted JSON
            val patchedJson = jsonObject.toString(2)
            
            Log.d(TAG, "Successfully patched Reality template")
            Log.d(TAG, "  Address: ${params.address}:${params.port}")
            Log.d(TAG, "  User ID: ${params.id.take(8)}...")
            Log.d(TAG, "  Dest: ${params.dest}")
            Log.d(TAG, "  Server names: ${params.serverNames.size}")
            Log.d(TAG, "  Short IDs: ${params.shortIds.size}")
            
            OptimizeResult(
                success = true,
                patchedJson = patchedJson,
                error = null
            )
            
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error while patching template", e)
            OptimizeResult(
                success = false,
                patchedJson = null,
                error = "JSON parsing error: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while patching template", e)
            OptimizeResult(
                success = false,
                patchedJson = null,
                error = "Unexpected error: ${e.message}"
            )
        }
    }
    
    /**
     * Validates that patched JSON is valid and contains required fields.
     */
    fun validatePatchedJson(patchedJson: String): Boolean {
        return try {
            val jsonObject = JSONObject(patchedJson)
            
            // Check outbounds
            val outbounds = jsonObject.optJSONArray("outbounds") ?: return false
            if (outbounds.length() == 0) return false
            
            val outbound = outbounds.getJSONObject(0)
            
            // Check settings
            val settings = outbound.optJSONObject("settings") ?: return false
            val vnext = settings.optJSONArray("vnext") ?: return false
            if (vnext.length() == 0) return false
            
            val vnextServer = vnext.getJSONObject(0)
            val address = vnextServer.optString("address", "")
            val id = vnextServer.optJSONArray("users")?.getJSONObject(0)?.optString("id", "") ?: ""
            
            // Check streamSettings
            val streamSettings = outbound.optJSONObject("streamSettings") ?: return false
            val realitySettings = streamSettings.optJSONObject("realitySettings") ?: return false
            val dest = realitySettings.optString("dest", "")
            val privateKey = realitySettings.optString("privateKey", "")
            
            // Validate required fields are not empty
            val isValid = address.isNotEmpty() && 
                         id.isNotEmpty() && 
                         dest.isNotEmpty() && 
                         privateKey.isNotEmpty()
            
            if (!isValid) {
                Log.w(TAG, "Validation failed: address=$address, id=${id.take(8)}..., dest=$dest, privateKey=${privateKey.take(8)}...")
            }
            
            isValid
            
        } catch (e: Exception) {
            Log.e(TAG, "Error validating patched JSON", e)
            false
        }
    }
}

