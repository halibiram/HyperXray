package com.hyperxray.an.telemetry

import kotlinx.serialization.Serializable

/**
 * Context information for Reality protocol configuration.
 * Used to identify and track different Reality server configurations.
 */
@Serializable
data class RealityContext(
    /**
     * Server address (hostname or IP)
     */
    val address: String,
    
    /**
     * Server port
     */
    val port: Int,
    
    /**
     * Reality server name (SNI)
     */
    val serverName: String,
    
    /**
     * Reality short ID
     */
    val shortId: String,
    
    /**
     * Reality public key (base64 encoded)
     */
    val publicKey: String,
    
    /**
     * Reality destination (dest)
     */
    val destination: String,
    
    /**
     * Unique identifier for this Reality configuration
     */
    val configId: String
) {
    /**
     * Generate a unique identifier from context fields
     */
    fun toIdentifier(): String {
        return "${address}:${port}:${serverName}:${shortId}"
    }
    
    companion object {
        /**
         * Create RealityContext from identifier string
         */
        fun fromIdentifier(identifier: String): RealityContext? {
            val parts = identifier.split(":")
            if (parts.size != 4) return null
            
            return RealityContext(
                address = parts[0],
                port = parts[1].toIntOrNull() ?: return null,
                serverName = parts[2],
                shortId = parts[3],
                publicKey = "",
                destination = "",
                configId = identifier
            )
        }
    }
}



