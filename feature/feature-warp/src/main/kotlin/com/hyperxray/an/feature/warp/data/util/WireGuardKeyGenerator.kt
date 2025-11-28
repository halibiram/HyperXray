package com.hyperxray.an.feature.warp.data.util

import android.util.Base64
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * WireGuard key pair generator using X25519 (Curve25519)
 * Based on BouncyCastle implementation
 */
object WireGuardKeyGenerator {
    
    /**
     * Generate a WireGuard keypair (X25519)
     * @return Pair of (privateKey, publicKey) in base64 format
     */
    fun generateKeyPair(): Pair<String, String> {
        val random = SecureRandom()
        val keyGen = X25519KeyPairGenerator()
        keyGen.init(X25519KeyGenerationParameters(random))
        
        val keyPair = keyGen.generateKeyPair()
        val privateKeyParams = keyPair.private as X25519PrivateKeyParameters
        val publicKeyParams = keyPair.public as X25519PublicKeyParameters
        
        // Get raw key bytes
        val privateKeyBytes = privateKeyParams.encoded
        val publicKeyBytes = publicKeyParams.encoded
        
        // Encode as base64
        val privateKeyB64 = Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP)
        val publicKeyB64 = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
        
        return Pair(privateKeyB64, publicKeyB64)
    }
}










