package com.hyperxray.an.service.managers

import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Handles writing Xray configuration to process STDIN.
 * 
 * This class securely writes the JSON configuration to the started process's
 * standard input using UTF-8 encoding. It gracefully handles stream errors
 * such as "Broken pipe" or "Stream closed" exceptions.
 */
class XrayConfigWriter {
    
    companion object {
        private const val TAG = "XrayConfigWriter"
    }
    
    /**
     * Writes configuration content to process STDIN.
     * 
     * @param processOutputStream The process's STDIN OutputStream
     * @param configContent The JSON configuration content to write
     * @throws IOException if writing fails and stream is not closed
     */
    fun writeConfig(
        processOutputStream: OutputStream,
        configContent: String
    ) {
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "📝 CONFIG WRITE: Starting configuration write (size: ${configContent.length} bytes)")
        
        try {
            // Validate config content
            if (configContent.isBlank()) {
                val errorMsg = "Configuration content is empty"
                Log.e(TAG, "❌ $errorMsg")
                AiLogHelper.e(TAG, "❌ CONFIG WRITE FAILED: $errorMsg")
                throw IllegalArgumentException(errorMsg)
            }
            
            // Convert to UTF-8 bytes
            val configBytes = configContent.toByteArray(StandardCharsets.UTF_8)
            Log.d(TAG, "Writing ${configBytes.size} bytes to process STDIN")
            AiLogHelper.d(TAG, "📝 CONFIG WRITE: Writing ${configBytes.size} bytes to process STDIN")
            
            // Write config to STDIN
            processOutputStream.write(configBytes)
            processOutputStream.flush()
            
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "✅ Configuration written successfully (${configBytes.size} bytes in ${duration}ms)")
            AiLogHelper.i(TAG, "✅ CONFIG WRITE COMPLETED: Configuration written successfully (${configBytes.size} bytes, duration: ${duration}ms)")
            
        } catch (e: IOException) {
            // Check if this is a "Broken pipe" or "Stream closed" error
            val errorMessage = e.message ?: "Unknown IO error"
            val isStreamClosed = errorMessage.contains("Broken pipe", ignoreCase = true) ||
                                errorMessage.contains("Stream closed", ignoreCase = true) ||
                                errorMessage.contains("write end dead", ignoreCase = true)
            
            if (isStreamClosed) {
                // Process may have closed STDIN or terminated - this is often expected
                val warningMsg = "Process STDIN closed during config write (process may have terminated): ${e.message}"
                Log.w(TAG, "⚠️ $warningMsg")
                AiLogHelper.w(TAG, "⚠️ CONFIG WRITE WARNING: $warningMsg")
                // Don't throw - this might be expected if process reads config quickly
            } else {
                // Other IO errors are unexpected
                val errorMsg = "Failed to write configuration to process STDIN: ${e.message}"
                Log.e(TAG, "❌ $errorMsg", e)
                AiLogHelper.e(TAG, "❌ CONFIG WRITE FAILED: $errorMsg", e)
                throw IOException(errorMsg, e)
            }
        } catch (e: Exception) {
            val errorMsg = "Unexpected error writing configuration: ${e.message}"
            Log.e(TAG, "❌ $errorMsg", e)
            AiLogHelper.e(TAG, "❌ CONFIG WRITE FAILED: $errorMsg", e)
            throw IOException(errorMsg, e)
        }
    }
    
    /**
     * Closes the process STDIN stream gracefully.
     * 
     * @param processOutputStream The process's STDIN OutputStream to close
     */
    fun closeStream(processOutputStream: OutputStream) {
        try {
            processOutputStream.close()
            Log.d(TAG, "Process STDIN stream closed")
            AiLogHelper.d(TAG, "📝 CONFIG WRITE: Process STDIN stream closed")
        } catch (e: Exception) {
            // Ignore errors when closing - stream may already be closed
            Log.d(TAG, "Error closing process STDIN (may already be closed): ${e.message}")
        }
    }
}



