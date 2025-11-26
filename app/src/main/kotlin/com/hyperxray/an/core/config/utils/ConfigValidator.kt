package com.hyperxray.an.core.config.utils

import android.util.Log

/**
 * Validates configuration-related values such as IP addresses, ports, and endpoints.
 * Follows Single Responsibility Principle - only handles validation logic.
 */
object ConfigValidator {
    private const val TAG = "ConfigValidator"

    /**
     * Checks if a string is a valid IPv4 address.
     * 
     * @param address The address string to validate
     * @return true if the address is a valid IPv4 address, false otherwise
     */
    fun isValidIpAddress(address: String): Boolean {
        return try {
            val parts = address.split(".")
            if (parts.size != 4) return false
            parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates if a port number is within valid range (1-65535).
     * 
     * @param port The port number to validate
     * @return true if the port is valid, false otherwise
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    /**
     * Determines if endpoint should be randomized (e.g., if it's a known blocked IP or domain).
     * Currently always returns true for better reliability.
     * 
     * @param endpoint The endpoint string to check
     * @return true if endpoint should be randomized
     */
    fun shouldRandomizeEndpoint(endpoint: String): Boolean {
        // Always randomize on first connection attempt for better reliability
        // In future, we could track failed endpoints and randomize those
        return true
    }
}




