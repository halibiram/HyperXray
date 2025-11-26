package com.hyperxray.an.utils

import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security

private const val TAG = "WarpUtils"

// Initialize BouncyCastle provider once
private val bouncyCastleProvider: BouncyCastleProvider by lazy {
    val provider = BouncyCastleProvider()
    if (Security.getProvider(provider.name) == null) {
        Security.addProvider(provider)
    }
    provider
}

/**
 * Default Cloudflare WARP configuration values
 */
object WarpDefaults {
    // Cloudflare WARP public key (fixed)
    const val PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    
    // Default WARP endpoint (use IP to avoid DNS issues)
    const val ENDPOINT = "162.159.192.1:2408"
    
    // Alternative endpoint domain (may fail if DNS is blocked)
    const val ENDPOINT_DOMAIN = "engage.cloudflareclient.com:2408"
    
    // Additional WARP IP endpoints (Cloudflare has multiple IPs)
    // IPv4 endpoints
    val WARP_IPS_V4 = listOf(
        "162.159.192.1:2408",
        "162.159.193.1:2408",
        "162.159.193.5:2408",
        "162.159.195.1:2408",
        "162.159.195.10:2408"
    )
    
    // IPv6 endpoints (often less blocked than IPv4)
    val WARP_IPS_V6 = listOf(
        "[2606:4700:d0::a29f:c001]:2408",
        "[2606:4700:d0::a29f:c005]:2408",
        "[2606:4700:d0::a29f:c301]:2408"
    )
    
    // Combined list (IPv6 prioritized for better connectivity)
    val WARP_IPS = WARP_IPS_V6 + WARP_IPS_V4
    
    // Default local address (IPv4 and IPv6)
    const val LOCAL_ADDRESS = "172.16.0.2/32, 2606:4700:110:8f5a:7c46:852e:f45c:6d35/128"
}

/**
 * Utility class for Cloudflare WARP (WireGuard) key generation and configuration.
 * 
 * Uses BouncyCastle for proper Curve25519 (X25519) key generation.
 */
object WarpUtils {
    
    init {
        // Ensure BouncyCastle provider is initialized
        bouncyCastleProvider
    }
    
    /**
     * Generate a valid WireGuard private key using Curve25519 (X25519).
     * 
     * WireGuard uses X25519 for key exchange. This method tries multiple approaches:
     * 1. Android native X25519 (Android 9+ / API 28+)
     * 2. BouncyCastle X25519
     * 3. BouncyCastle EC with Curve25519
     * 
     * @return Base64-encoded private key string (32 bytes)
     * @throws IllegalStateException if key generation fails
     */
    fun generatePrivateKey(): String {
        // Try Android native X25519 first (Android 9+ / API 28+)
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance("X25519")
            val keyPair = keyPairGenerator.generateKeyPair()
            
            // Extract private key bytes
            val privateKeyBytes = extractPrivateKeyBytes(keyPair.private)
            
            // Clamp and encode
            val clampedKey = clampCurve25519Key(privateKeyBytes)
            val privateKey = Base64.encodeToString(clampedKey, Base64.NO_WRAP)
            
            if (privateKey.length == 44) {
                Log.i(TAG, "✅ Generated valid WireGuard private key using Android native X25519 (length: ${privateKey.length})")
                return privateKey
            }
        } catch (e: java.security.NoSuchAlgorithmException) {
            Log.d(TAG, "Android native X25519 not available, trying BouncyCastle: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "Android native X25519 failed, trying BouncyCastle: ${e.message}")
        }
        
        // Try BouncyCastle X25519
        try {
            // Ensure BouncyCastle provider is registered
            if (Security.getProvider("BC") == null) {
                Security.addProvider(bouncyCastleProvider)
                Log.d(TAG, "BouncyCastle provider registered")
            }
            
            // Try BouncyCastle X25519
            try {
                val keyPairGenerator = KeyPairGenerator.getInstance("X25519", "BC")
                val keyPair = keyPairGenerator.generateKeyPair()
                val privateKeyBytes = extractPrivateKeyBytes(keyPair.private)
                val clampedKey = clampCurve25519Key(privateKeyBytes)
                val privateKey = Base64.encodeToString(clampedKey, Base64.NO_WRAP)
                
                if (privateKey.length == 44) {
                    Log.i(TAG, "✅ Generated valid WireGuard private key using BouncyCastle X25519 (length: ${privateKey.length})")
                    return privateKey
                }
            } catch (e: java.security.NoSuchAlgorithmException) {
                Log.d(TAG, "BouncyCastle X25519 not available, trying EC Curve25519: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "BouncyCastle provider setup failed: ${e.message}", e)
        }
        
        // Fallback: Generate using SecureRandom with proper clamping
        // This is not ideal but produces a valid Curve25519 key
        try {
            val random = java.security.SecureRandom()
            val privateKeyBytes = ByteArray(32)
            random.nextBytes(privateKeyBytes)
            
            // Clamp the key to make it valid for Curve25519
            val clampedKey = clampCurve25519Key(privateKeyBytes)
            val privateKey = Base64.encodeToString(clampedKey, Base64.NO_WRAP)
            
            Log.w(TAG, "⚠️ Using SecureRandom fallback for key generation (valid Curve25519 key)")
            return privateKey
        } catch (e: Exception) {
            val errorMsg = "All key generation methods failed. Last error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw IllegalStateException(errorMsg, e)
        }
    }
    
    /**
     * Extract 32-byte private key from Java PrivateKey object.
     */
    private fun extractPrivateKeyBytes(privateKey: java.security.PrivateKey): ByteArray {
        val encoded = privateKey.encoded
        
        // Try BouncyCastle PrivateKeyInfo parsing first
        try {
            val privateKeyInfo = PrivateKeyInfo.getInstance(encoded)
            val keyOctets = privateKeyInfo.parsePrivateKey()
            
            if (keyOctets is org.bouncycastle.asn1.ASN1OctetString) {
                val octets = keyOctets.octets
                if (octets.size == 32) {
                    return octets
                } else if (octets.size > 32) {
                    // Take last 32 bytes if padded
                    return octets.sliceArray(octets.size - 32 until octets.size)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "BouncyCastle PrivateKeyInfo parsing failed, trying direct extraction: ${e.message}")
        }
        
        // Direct extraction: X25519 private key is typically 32 bytes
        // For PKCS#8 format, the key is usually at the end
        if (encoded.size >= 32) {
            // Try last 32 bytes
            val candidate = encoded.sliceArray(encoded.size - 32 until encoded.size)
            if (candidate.size == 32) {
                return candidate
            }
        }
        
        throw IllegalArgumentException("Could not extract 32-byte key from private key (encoded size: ${encoded.size})")
    }
    
    /**
     * Clamp Curve25519 private key according to WireGuard specification.
     * First byte: clear bits 0, 1, 2 (mask 0xF8)
     * Last byte: clear bit 7, set bit 6 (mask 0x7F, then OR 0x40)
     */
    private fun clampCurve25519Key(key: ByteArray): ByteArray {
        if (key.size != 32) {
            throw IllegalArgumentException("Key must be 32 bytes, got ${key.size}")
        }
        
        val clampedKey = key.copyOf()
        clampedKey[0] = (clampedKey[0].toInt() and 0xF8).toByte()
        clampedKey[31] = ((clampedKey[31].toInt() and 0x7F) or 0x40).toByte()
        return clampedKey
    }
    
    /**
     * Generate WARP keys (private key only, public key is fixed for Cloudflare WARP).
     * 
     * Note: For WARP, we only need to generate a private key. The public key
     * is always Cloudflare's fixed WARP public key.
     * 
     * @return Pair of (privateKey, publicKey)
     *         Note: publicKey is always the Cloudflare WARP public key
     */
    fun generateWarpKeys(): Pair<String, String> {
        val privateKey = generatePrivateKey()
        val publicKey = WarpDefaults.PEER_PUBLIC_KEY
        
        if (privateKey.isNotEmpty()) {
            Log.d(TAG, "Generated WARP keys - Private key length: ${privateKey.length}, Public key: ${publicKey.take(20)}...")
        } else {
            Log.e(TAG, "Failed to generate WARP private key")
        }
        
        return Pair(privateKey, publicKey)
    }
    
    /**
     * Validate a WireGuard private key format.
     * 
     * @param privateKey Base64-encoded private key
     * @return true if the key appears to be valid format
     */
    fun isValidPrivateKey(privateKey: String): Boolean {
        if (privateKey.isBlank()) {
            return false
        }
        
        try {
            val decoded = Base64.decode(privateKey, Base64.NO_WRAP)
            // WireGuard private keys must be exactly 32 bytes
            return decoded.size == 32
        } catch (e: Exception) {
            Log.d(TAG, "Invalid private key format: ${e.message}")
            return false
        }
    }
    
    /**
     * Validate a WireGuard public key format.
     * 
     * @param publicKey Base64-encoded public key
     * @return true if the key appears to be valid format
     */
    fun isValidPublicKey(publicKey: String): Boolean {
        if (publicKey.isBlank()) {
            return false
        }
        
        try {
            val decoded = Base64.decode(publicKey, Base64.NO_WRAP)
            // WireGuard public keys must be exactly 32 bytes
            return decoded.size == 32
        } catch (e: Exception) {
            Log.d(TAG, "Invalid public key format: ${e.message}")
            return false
        }
    }
    
    /**
     * Validate endpoint format (host:port or IP:port).
     * 
     * @param endpoint Endpoint string
     * @return true if endpoint format is valid
     */
    fun isValidEndpoint(endpoint: String): Boolean {
        if (endpoint.isBlank()) {
            return false
        }
        
        val parts = endpoint.split(":")
        if (parts.size != 2) {
            return false
        }
        
        val port = parts[1].toIntOrNull()
        return port != null && port in 1..65535
    }
    
    /**
     * Validate local address format (CIDR notation).
     * 
     * @param localAddress Local address string (can be comma-separated for IPv4 and IPv6)
     * @return true if address format is valid
     */
    fun isValidLocalAddress(localAddress: String): Boolean {
        if (localAddress.isBlank()) {
            return false
        }
        
        // Split by comma for multiple addresses
        val addresses = localAddress.split(",").map { it.trim() }
        
        return addresses.all { address ->
            // Basic CIDR format check (IP/CIDR)
            address.contains("/") && address.split("/").size == 2
        }
    }
    
    /**
     * Derive public key from private key using X25519 scalar multiplication.
     * 
     * @param privateKey Base64-encoded private key (32 bytes)
     * @return Base64-encoded public key (32 bytes)
     * @throws IllegalArgumentException if private key is invalid
     * @throws IllegalStateException if key derivation fails
     */
    fun derivePublicKey(privateKey: String): String {
        try {
            // Decode private key
            val privateKeyBytes = Base64.decode(privateKey, Base64.NO_WRAP)
            
            if (privateKeyBytes.size != 32) {
                throw IllegalArgumentException("Private key must be 32 bytes, got ${privateKeyBytes.size}")
            }
            
            // Ensure BouncyCastle provider is registered
            if (Security.getProvider("BC") == null) {
                Security.addProvider(bouncyCastleProvider)
            }
            
            // Derive public key using X25519 scalar multiplication
            val publicKeyBytes = ByteArray(32)
            org.bouncycastle.math.ec.rfc7748.X25519.scalarMultBase(
                privateKeyBytes, 0, publicKeyBytes, 0
            )
            
            val publicKey = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
            
            if (publicKey.length != 44) {
                throw IllegalStateException("Invalid public key length: ${publicKey.length} (expected 44)")
            }
            
            Log.d(TAG, "✅ Derived public key from private key (length: ${publicKey.length})")
            return publicKey
            
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid private key for public key derivation: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive public key: ${e.message}", e)
            throw IllegalStateException("Failed to derive public key: ${e.message}", e)
        }
    }
}

