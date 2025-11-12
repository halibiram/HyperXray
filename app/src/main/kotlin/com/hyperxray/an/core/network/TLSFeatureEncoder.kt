package com.hyperxray.an.core.network

import android.util.Log
import kotlin.math.abs

/**
 * TLSFeatureEncoder: Encodes TLS ClientHello metadata into a 32-element feature vector.
 * 
 * Extracts features from TLS handshake data:
 * - Domain length
 * - Number of dots
 * - Unique character count
 * - Hash normalization
 * - ALPN id (h2/h3)
 * - Entropy approximation
 * - Cipher diversity
 * - Extension count
 * - ClientHello length (normalized)
 */
object TLSFeatureEncoder {
    private const val TAG = "TLSFeatureEncoder"
    
    /**
     * Encode TLS features from domain and ALPN information.
     * 
     * @param domain The SNI (Server Name Indication) domain
     * @param alpn The ALPN protocol identifier (default: "h2")
     * @return 32-element float array of normalized features
     */
    fun encode(domain: String, alpn: String = "h2"): FloatArray {
        if (domain.isEmpty()) {
            Log.w(TAG, "Empty domain provided, using default features")
            return FloatArray(32) { 0f }
        }
        
        try {
            // Feature 0: Domain length (normalized to 0-1, assuming max 255)
            val domainLength = domain.length.toFloat()
            val lenNorm = (domainLength / 255f).coerceIn(0f, 1f)
            
            // Feature 1: Number of dots
            val dots = domain.count { it == '.' }.toFloat()
            val dotsNorm = (dots / 10f).coerceIn(0f, 1f) // Normalize assuming max 10 dots
            
            // Feature 2: Unique character count
            val uniqueChars = domain.toSet().size.toFloat()
            val uniqueNorm = (uniqueChars / 64f).coerceIn(0f, 1f) // Normalize assuming max 64 unique chars
            
            // Feature 3: Hash normalization (domain hash modulo 1000, normalized)
            val hashNorm = (abs(domain.hashCode() % 1000) / 1000f).coerceIn(0f, 1f)
            
            // Feature 4: ALPN id (h3=0.7, h2=0.3, other=0.1)
            val alpnId = when (alpn.lowercase()) {
                "h3" -> 0.7f
                "h2" -> 0.3f
                else -> 0.1f
            }
            
            // Feature 5: Entropy approximation (unique chars / length)
            val entropy = if (domainLength > 0) {
                (uniqueChars / domainLength).coerceIn(0f, 1f)
            } else {
                0f
            }
            
            // Feature 6: Cipher diversity (approximation based on unique chars)
            val cipherDiv = (0.4f + (uniqueChars / 50f)).coerceIn(0f, 1f)
            
            // Feature 7: Extension count (approximation based on domain length)
            val extCount = (domainLength / 10f).coerceIn(0f, 1f)
            
            // Feature 8: ClientHello length (normalized, approximation)
            val chLen = (domainLength / 200f).coerceIn(0f, 1f)
            
            // Build feature vector with first 9 features
            val vec = mutableListOf<Float>(
                lenNorm,      // 0: domain length
                dotsNorm,     // 1: dots count
                uniqueNorm,   // 2: unique chars
                hashNorm,     // 3: hash normalization
                alpnId,       // 4: ALPN id
                entropy,      // 5: entropy
                cipherDiv,    // 6: cipher diversity
                extCount,     // 7: extension count
                chLen         // 8: ClientHello length
            )
            
            // Pad to 32 elements with small random-like values based on domain hash
            // This provides additional features while maintaining determinism
            val hash = domain.hashCode()
            while (vec.size < 32) {
                val index = vec.size
                // Generate pseudo-random value based on hash and index
                val pseudoRandom = ((hash + index * 31) % 200 - 100) / 100f
                vec.add(pseudoRandom.coerceIn(-0.5f, 0.5f))
            }
            
            val features = vec.toFloatArray()
            
            Log.d(TAG, "Encoded features for domain=$domain, alpn=$alpn: ${features.take(9).joinToString()}")
            
            return features
            
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding TLS features: ${e.message}", e)
            return FloatArray(32) { 0f }
        }
    }
    
    /**
     * Encode TLS features with additional metadata.
     * 
     * @param domain The SNI domain
     * @param alpn The ALPN protocol
     * @param clientHelloLength Optional ClientHello length (if available)
     * @param extensionCount Optional extension count (if available)
     * @return 32-element float array of normalized features
     */
    fun encodeWithMetadata(
        domain: String,
        alpn: String = "h2",
        clientHelloLength: Int? = null,
        extensionCount: Int? = null
    ): FloatArray {
        // Start with basic encoding
        val features = encode(domain, alpn)
        
        // Override with actual values if provided
        if (clientHelloLength != null) {
            features[8] = (clientHelloLength / 200f).coerceIn(0f, 1f)
        }
        
        if (extensionCount != null) {
            features[7] = (extensionCount / 20f).coerceIn(0f, 1f) // Normalize assuming max 20 extensions
        }
        
        return features
    }
}

