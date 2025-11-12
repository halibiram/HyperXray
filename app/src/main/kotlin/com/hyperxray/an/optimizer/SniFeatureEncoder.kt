package com.hyperxray.an.optimizer

import android.util.Log
import kotlin.math.abs

/**
 * SniFeatureEncoder: Encodes SNI domain to 32-dimensional feature vector.
 * 
 * Identical implementation to Colab version for consistency.
 * 
 * Features:
 * - Domain length (normalized)
 * - Number of dots
 * - Digit count
 * - Unique character count
 * - Hash normalization
 * - ALPN id (h2/h3)
 * - Entropy approximation
 * - Latency normalization (if provided)
 * - Throughput normalization (if provided)
 * - Cipher diversity
 * - Extension count
 * - ClientHello length
 * - Padding to 32D
 */
object SniFeatureEncoder {
    private const val TAG = "SniFeatureEncoder"
    
    /**
     * Encode SNI domain to 32D feature vector.
     * 
     * @param sni The Server Name Indication domain
     * @param alpn The ALPN protocol (default: "h2")
     * @param latencyMs Optional latency in milliseconds (for normalization)
     * @param throughputKbps Optional throughput in kbps (for normalization)
     * @param timestamp Optional timestamp for temporal features (milliseconds since epoch)
     * @return 32-element float array
     */
    fun encode(
        sni: String, 
        alpn: String = "h2",
        latencyMs: Double? = null,
        throughputKbps: Double? = null,
        timestamp: Long? = null
    ): FloatArray {
        if (sni.isEmpty()) {
            Log.w(TAG, "Empty SNI provided, using default features")
            return FloatArray(32) { 0f }
        }
        
        try {
            // Feature 0: Domain length (normalized to 0-1, max 255)
            val domainLength = sni.length.toFloat()
            val lenNorm = (domainLength / 255f).coerceIn(0f, 1f)
            
            // Feature 1: Number of dots
            val dots = sni.count { it == '.' }.toFloat()
            val dotsNorm = (dots / 10f).coerceIn(0f, 1f)
            
            // Feature 2: Digit count
            val digitCount = sni.count { it.isDigit() }.toFloat()
            val digitNorm = (digitCount / 20f).coerceIn(0f, 1f) // Normalize assuming max 20 digits
            
            // Feature 3: Unique character count
            val uniqueChars = sni.toSet().size.toFloat()
            val uniqueNorm = (uniqueChars / 64f).coerceIn(0f, 1f)
            
            // Feature 4: Hash normalization (domain hash modulo 1000, normalized)
            val hashNorm = (abs(sni.hashCode() % 1000) / 1000f).coerceIn(0f, 1f)
            
            // Feature 5: ALPN id (h3=0.7, h2=0.3, other=0.1)
            val alpnId = when (alpn.lowercase()) {
                "h3" -> 0.7f
                "h2" -> 0.3f
                else -> 0.1f
            }
            
            // Feature 6: Entropy approximation (unique chars / length)
            val entropy = if (domainLength > 0) {
                (uniqueChars / domainLength).coerceIn(0f, 1f)
            } else {
                0f
            }
            
            // Feature 7: Latency normalization (if provided)
            val latencyNorm = if (latencyMs != null) {
                (latencyMs / 2000.0).coerceIn(0.0, 1.0).toFloat() // Normalize 0-2000ms
            } else {
                0.5f // Default middle value
            }
            
            // Feature 8: Throughput normalization (if provided)
            val throughputNorm = if (throughputKbps != null) {
                (throughputKbps / 10000.0).coerceIn(0.0, 1.0).toFloat() // Normalize 0-10000 kbps
            } else {
                0.5f // Default middle value
            }
            
            // Feature 9: Cipher diversity (approximation)
            val cipherDiv = (0.4f + (uniqueChars / 50f)).coerceIn(0f, 1f)
            
            // Feature 10: Extension count (approximation)
            val extCount = (domainLength / 10f).coerceIn(0f, 1f)
            
            // Feature 11: ClientHello length (normalized, approximation)
            val chLen = (domainLength / 200f).coerceIn(0f, 1f)
            
            // Feature 12: Hour of day (0-23, normalized to 0-1)
            val hourOfDay = if (timestamp != null) {
                val calendar = java.util.Calendar.getInstance()
                calendar.timeInMillis = timestamp
                (calendar.get(java.util.Calendar.HOUR_OF_DAY) / 23f).coerceIn(0f, 1f)
            } else {
                0.5f // Default middle value (noon)
            }
            
            // Feature 13: Day of week (1-7, normalized to 0-1)
            val dayOfWeek = if (timestamp != null) {
                val calendar = java.util.Calendar.getInstance()
                calendar.timeInMillis = timestamp
                // Calendar.DAY_OF_WEEK: 1=Sunday, 7=Saturday
                ((calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1) / 6f).coerceIn(0f, 1f)
            } else {
                0.5f // Default middle value (Wednesday)
            }
            
            // Build feature vector with first 13 features (expanded from 11)
            val vec = mutableListOf<Float>(
                lenNorm,         // 0: domain length
                dotsNorm,        // 1: dots count
                digitNorm,       // 2: digit count
                uniqueNorm,      // 3: unique chars
                hashNorm,        // 4: hash normalization
                alpnId,          // 5: ALPN id
                entropy,         // 6: entropy
                latencyNorm,     // 7: latency normalization
                throughputNorm,  // 8: throughput normalization
                cipherDiv,       // 9: cipher diversity
                extCount,        // 10: extension count
                chLen,           // 11: ClientHello length
                hourOfDay,       // 12: hour of day (temporal)
                dayOfWeek        // 13: day of week (temporal)
            )
            
            // Pad to 32 elements with deterministic values (same as Colab)
            val hash = sni.hashCode()
            while (vec.size < 32) {
                val index = vec.size
                val pseudoRandom = ((hash + index * 31) % 200 - 100) / 100f
                vec.add(pseudoRandom.coerceIn(-0.5f, 0.5f))
            }
            
            val features = vec.toFloatArray()
            
            Log.d(TAG, "Encoded SNI: $sni -> 32D features")
            
            return features
            
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding SNI features: ${e.message}", e)
            return FloatArray(32) { 0f }
        }
    }
}

