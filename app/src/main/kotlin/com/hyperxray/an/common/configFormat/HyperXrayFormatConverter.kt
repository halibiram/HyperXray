package com.hyperxray.an.common.configFormat

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.FilenameValidator
import com.hyperxray.an.data.source.FileManager.Companion.TAG
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.Inflater

class HyperXrayFormatConverter: ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("hyperxray://config/")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        val parts = content.substring("hyperxray://config/".length).split("/")
        if (parts.size != 2) {
            Log.e(TAG, "Invalid hyperxray URI format")
            return Result.failure(RuntimeException("Invalid hyperxray URI format"))
        }

        val decodedName = URLDecoder.decode(parts[0], "UTF-8")

        val filenameError = FilenameValidator.validateFilename(context, decodedName)
        if (filenameError != null) {
            Log.e(TAG, "Invalid filename in hyperxray URI: $filenameError")
            return Result.failure(RuntimeException("Invalid filename in hyperxray URI: $filenameError"))
        }

        val decodedContent = Base64.getUrlDecoder().decode(parts[1])

        val inflater = Inflater()
        inflater.setInput(decodedContent)
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        inflater.end()
        val decompressed = outputStream.toByteArray().toString(Charsets.UTF_8)

        // Parse enableWarp from config JSON if present
        // This allows HyperXray format to support WARP chaining
        val enableWarp = try {
            val configJson = org.json.JSONObject(decompressed)
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")
            if (outbounds != null) {
                // Check if VLESS outbound has proxySettings pointing to warp-out
                var hasVlessWithWarp = false
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.getJSONObject(i)
                    if (outbound.optString("protocol") == "vless") {
                        val proxySettings = outbound.optJSONObject("proxySettings")
                        if (proxySettings != null && proxySettings.optString("tag") == "warp-out") {
                            hasVlessWithWarp = true
                            break
                        }
                    }
                }
                hasVlessWithWarp
            } else {
                false
            }
        } catch (e: Exception) {
            // If parsing fails, assume enableWarp is false
            false
        }

        return Result.success(DetectedConfig(decodedName, decompressed, enableWarp))
    }
}