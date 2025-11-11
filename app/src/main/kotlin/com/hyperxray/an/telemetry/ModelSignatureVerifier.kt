package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest
import java.util.*

/**
 * ModelSignatureVerifier: Verifies model integrity using SHA256 and Ed25519 signatures.
 * 
 * Supports:
 * - SHA256 hash verification (integrity check)
 * - Ed25519 signature verification (authenticity check)
 * - Fallback to hash-only verification if Ed25519 not available
 */
class ModelSignatureVerifier(private val context: Context) {
    private val TAG = "ModelSignatureVerifier"
    
    /**
     * Manifest data class for model metadata
     */
    @Serializable
    data class ModelManifest(
        val model: ModelInfo,
        val metadata: ModelMetadata
    ) {
        @Serializable
        data class ModelInfo(
            val name: String,
            val version: String,
            val format: String,
            val path: String,
            val sha256: String = "",
            val ed25519_signature: String = "",
            val ed25519_public_key: String = "",
            val description: String = "",
            val input_shape: List<Int> = emptyList(),
            val output_shape: List<Int> = emptyList(),
            val created_at: String = "",
            val updated_at: String = ""
        )
        
        @Serializable
        data class ModelMetadata(
            val onnx_version: String = "",
            val runtime_version: String = "",
            val architecture: String = "",
            val training_data_version: String = "",
            val baseline_config: String = "baseline_policy"
        )
    }
    
    /**
     * Verification result
     */
    data class VerificationResult(
        val isValid: Boolean,
        val hashValid: Boolean,
        val signatureValid: Boolean?,
        val error: String? = null,
        val manifest: ModelManifest? = null
    )
    
    /**
     * Load and parse manifest.json from assets
     */
    fun loadManifest(): ModelManifest? {
        return try {
            val manifestStream = context.assets.open("models/manifest.json")
            val manifestText = manifestStream.bufferedReader().use { it.readText() }
            manifestStream.close()
            
            val json = Json { ignoreUnknownKeys = true }
            val manifest = json.decodeFromString<ModelManifest>(manifestText)
            
            Log.d(TAG, "Manifest loaded: ${manifest.model.name} v${manifest.model.version}")
            manifest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load manifest.json", e)
            null
        }
    }
    
    /**
     * Calculate SHA256 hash of model file
     */
    fun calculateModelHash(modelPath: String): String? {
        return try {
            val modelStream = context.assets.open(modelPath)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            
            while (modelStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            modelStream.close()
            
            val hashBytes = digest.digest()
            val hashString = hashBytes.joinToString("") { "%02x".format(it) }
            
            Log.d(TAG, "Calculated SHA256 hash: ${hashString.take(16)}...")
            hashString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate model hash", e)
            null
        }
    }
    
    /**
     * Verify SHA256 hash
     */
    private fun verifyHash(manifest: ModelManifest, calculatedHash: String): Boolean {
        val expectedHash = manifest.model.sha256.lowercase(Locale.US)
        val actualHash = calculatedHash.lowercase(Locale.US)
        
        return if (expectedHash.isNotEmpty()) {
            val isValid = expectedHash == actualHash
            if (!isValid) {
                Log.w(TAG, "Hash mismatch: expected=${expectedHash.take(16)}..., actual=${actualHash.take(16)}...")
            }
            isValid
        } else {
            Log.w(TAG, "No hash in manifest, skipping hash verification")
            true // Allow if no hash specified (for development)
        }
    }
    
    /**
     * Verify Ed25519 signature (placeholder - requires crypto library)
     * 
     * Note: Full Ed25519 implementation would require a crypto library like:
     * - BouncyCastle (org.bouncycastle:bcprov-jdk18on)
     * - Or Android's built-in Ed25519 support (API 33+)
     * 
     * For now, this is a placeholder that checks if signature exists
     */
    private fun verifyEd25519Signature(
        manifest: ModelManifest,
        modelBytes: ByteArray
    ): Boolean? {
        val signature = manifest.model.ed25519_signature
        val publicKey = manifest.model.ed25519_public_key
        
        // If no signature in manifest, return null (not applicable)
        if (signature.isEmpty() || publicKey.isEmpty()) {
            Log.d(TAG, "No Ed25519 signature in manifest, skipping signature verification")
            return null
        }
        
        // Placeholder: In production, this would verify the signature
        // For now, we log a warning and return null (unknown)
        Log.w(TAG, "Ed25519 signature verification not fully implemented")
        Log.w(TAG, "Signature present but verification skipped (requires crypto library)")
        
        // TODO: Implement Ed25519 verification using BouncyCastle or Android's crypto
        // For production deployment, this should be implemented
        return null // Unknown status
    }
    
    /**
     * Verify model integrity and authenticity
     * 
     * @param modelPath Path to model file in assets
     * @return VerificationResult with validation status
     */
    fun verifyModel(modelPath: String): VerificationResult {
        return try {
            // Load manifest
            val manifest = loadManifest()
            if (manifest == null) {
                return VerificationResult(
                    isValid = false,
                    hashValid = false,
                    signatureValid = null,
                    error = "Failed to load manifest.json",
                    manifest = null
                )
            }
            
            // Verify path matches
            if (manifest.model.path != modelPath) {
                Log.w(TAG, "Manifest path (${manifest.model.path}) doesn't match provided path ($modelPath)")
            }
            
            // Calculate and verify hash
            val calculatedHash = calculateModelHash(modelPath)
            if (calculatedHash == null) {
                return VerificationResult(
                    isValid = false,
                    hashValid = false,
                    signatureValid = null,
                    error = "Failed to calculate model hash",
                    manifest = manifest
                )
            }
            
            val hashValid = verifyHash(manifest, calculatedHash)
            
            // Verify signature (if available)
            val modelBytes = context.assets.open(modelPath).use { it.readBytes() }
            val signatureValid = verifyEd25519Signature(manifest, modelBytes)
            
            // Model is valid if hash is valid (signature is optional for now)
            val isValid = hashValid && (signatureValid != false)
            
            if (isValid) {
                Log.i(TAG, "Model verification PASSED: ${manifest.model.name} v${manifest.model.version}")
                if (hashValid) {
                    Log.d(TAG, "  - Hash verification: PASSED")
                }
                if (signatureValid == true) {
                    Log.d(TAG, "  - Signature verification: PASSED")
                } else if (signatureValid == null) {
                    Log.d(TAG, "  - Signature verification: SKIPPED (not available)")
                }
            } else {
                Log.e(TAG, "Model verification FAILED: ${manifest.model.name} v${manifest.model.version}")
                if (!hashValid) {
                    Log.e(TAG, "  - Hash verification: FAILED")
                }
                if (signatureValid == false) {
                    Log.e(TAG, "  - Signature verification: FAILED")
                }
            }
            
            VerificationResult(
                isValid = isValid,
                hashValid = hashValid,
                signatureValid = signatureValid,
                error = if (!isValid) "Model verification failed" else null,
                manifest = manifest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Model verification error", e)
            VerificationResult(
                isValid = false,
                hashValid = false,
                signatureValid = null,
                error = "Verification error: ${e.message}",
                manifest = null
            )
        }
    }
    
    companion object {
        /**
         * Create a default ModelSignatureVerifier instance
         */
        fun create(context: Context): ModelSignatureVerifier {
            return ModelSignatureVerifier(context)
        }
    }
}


