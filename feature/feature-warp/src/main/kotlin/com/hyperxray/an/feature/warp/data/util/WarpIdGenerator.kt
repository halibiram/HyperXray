package com.hyperxray.an.feature.warp.data.util

import java.security.SecureRandom
import java.util.UUID

/**
 * Utility for generating WARP-related IDs and tokens
 */
object WarpIdGenerator {
    
    private val random = SecureRandom()
    private val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    
    /**
     * Generate a random install ID (22 chars)
     */
    fun generateInstallId(): String {
        return (1..22)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
    
    /**
     * Generate a fake FCM token
     */
    fun generateFcmToken(): String {
        val projectId = (1..11)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
        val tokenPart = "APA91" + (1..134)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
        return "$projectId:$tokenPart"
    }
    
    /**
     * Get current timestamp in ISO format
     */
    fun getTimestamp(): String {
        return java.time.Instant.now().toString()
    }
}










