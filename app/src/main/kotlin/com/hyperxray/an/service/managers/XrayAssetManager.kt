package com.hyperxray.an.service.managers

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import java.io.File

/**
 * Manages and verifies Xray-core critical assets.
 * 
 * This class ensures that all required assets (geoip.dat, geosite.dat, config.json)
 * exist and are accessible before attempting to start the Xray process.
 * 
 * Throws IllegalStateException if assets are missing to prevent silent process failures.
 */
class XrayAssetManager(private val context: Context) {
    
    companion object {
        private const val TAG = "XrayAssetManager"
        private const val MIN_GEOIP_SIZE = 1024L // Minimum expected size for geoip.dat (1KB)
        private const val MIN_GEOSITE_SIZE = 1024L // Minimum expected size for geosite.dat (1KB)
    }
    
    /**
     * Verifies that all critical assets exist and are accessible.
     * 
     * @param configPath Optional path to config file to verify
     * @throws IllegalStateException if any required asset is missing or invalid
     */
    fun checkAssets(configPath: String? = null) {
        val filesDir = context.filesDir
        val startTime = System.currentTimeMillis()
        
        AiLogHelper.i(TAG, "🔍 ASSET CHECK: Starting asset verification")
        
        // Check geoip.dat
        val geoipFile = File(filesDir, "geoip.dat")
        checkAssetFile(
            file = geoipFile,
            assetName = "geoip.dat",
            minSize = MIN_GEOIP_SIZE,
            isRequired = true
        )
        
        // Check geosite.dat
        val geositeFile = File(filesDir, "geosite.dat")
        checkAssetFile(
            file = geositeFile,
            assetName = "geosite.dat",
            minSize = MIN_GEOSITE_SIZE,
            isRequired = true
        )
        
        // Check config file if provided
        if (configPath != null) {
            val configFile = File(configPath)
            checkAssetFile(
                file = configFile,
                assetName = "config.json",
                minSize = 100L, // Minimum config size (100 bytes)
                isRequired = true
            )
        }
        
        val duration = System.currentTimeMillis() - startTime
        AiLogHelper.i(TAG, "✅ ASSET CHECK COMPLETED: All assets verified successfully (duration: ${duration}ms)")
    }
    
    /**
     * Checks a single asset file for existence, readability, and minimum size.
     * 
     * @param file The file to check
     * @param assetName Human-readable name for logging
     * @param minSize Minimum expected file size in bytes
     * @param isRequired Whether this asset is required (throws exception if missing)
     * @throws IllegalStateException if asset is missing or invalid and isRequired=true
     */
    private fun checkAssetFile(
        file: File,
        assetName: String,
        minSize: Long,
        isRequired: Boolean
    ) {
        try {
            if (!file.exists()) {
                val errorMsg = "Required asset '$assetName' not found at: ${file.absolutePath}"
                Log.e(TAG, "❌ $errorMsg")
                AiLogHelper.e(TAG, "❌ ASSET CHECK FAILED: $errorMsg")
                
                if (isRequired) {
                    throw IllegalStateException(errorMsg)
                }
                return
            }
            
            if (!file.isFile) {
                val errorMsg = "Asset '$assetName' exists but is not a file: ${file.absolutePath}"
                Log.e(TAG, "❌ $errorMsg")
                AiLogHelper.e(TAG, "❌ ASSET CHECK FAILED: $errorMsg")
                
                if (isRequired) {
                    throw IllegalStateException(errorMsg)
                }
                return
            }
            
            if (!file.canRead()) {
                val errorMsg = "Asset '$assetName' exists but is not readable: ${file.absolutePath}"
                Log.e(TAG, "❌ $errorMsg")
                AiLogHelper.e(TAG, "❌ ASSET CHECK FAILED: $errorMsg")
                
                if (isRequired) {
                    throw IllegalStateException(errorMsg)
                }
                return
            }
            
            val fileSize = file.length()
            if (fileSize < minSize) {
                val errorMsg = "Asset '$assetName' is too small: ${fileSize} bytes (minimum: ${minSize} bytes)"
                Log.e(TAG, "❌ $errorMsg")
                AiLogHelper.e(TAG, "❌ ASSET CHECK FAILED: $errorMsg")
                
                if (isRequired) {
                    throw IllegalStateException(errorMsg)
                }
                return
            }
            
            Log.d(TAG, "✅ Asset '$assetName' verified: ${fileSize} bytes at ${file.absolutePath}")
            AiLogHelper.d(TAG, "✅ ASSET CHECK: '$assetName' verified (${fileSize} bytes)")
            
        } catch (e: IllegalStateException) {
            // Re-throw IllegalStateException as-is
            throw e
        } catch (e: Exception) {
            val errorMsg = "Error checking asset '$assetName': ${e.message}"
            Log.e(TAG, "❌ $errorMsg", e)
            AiLogHelper.e(TAG, "❌ ASSET CHECK FAILED: $errorMsg", e)
            
            if (isRequired) {
                throw IllegalStateException(errorMsg, e)
            }
        }
    }
    
    /**
     * Gets the asset directory path (filesDir).
     * This is used to set XRAY_LOCATION_ASSET environment variable.
     * 
     * @return Absolute path to the asset directory
     */
    fun getAssetDirectory(): String {
        return context.filesDir.absolutePath
    }
    
    /**
     * Gets the path to a specific asset file.
     * 
     * @param assetName Name of the asset (e.g., "geoip.dat", "geosite.dat")
     * @return File object for the asset, or null if not found
     */
    fun getAssetFile(assetName: String): File? {
        val file = File(context.filesDir, assetName)
        return if (file.exists() && file.isFile && file.canRead()) {
            file
        } else {
            null
        }
    }
}














