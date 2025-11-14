package com.hyperxray.an.common.configFormat

import android.content.Context

/**
 * Interface for config format converters.
 * Converters are provided by feature modules (e.g., feature-vless).
 */
interface ConfigFormatConverter {
    fun detect(content: String): Boolean
    fun convert(context: Context, content: String): Result<DetectedConfig>

    companion object {
        /**
         * Known converter implementations.
         * Note: VlessLinkConverter is now in feature-vless module.
         * This list should be populated by feature modules via dependency injection
         * or registry pattern in future refactor.
         */
        val knownImplementations = listOf(
            HyperXrayFormatConverter(),
            // VlessLinkConverter moved to feature-vless
            // TODO: Register feature converters via DI or registry
        )

        fun convertOrNull(context: Context, content: String): Result<DetectedConfig>? {
            for (implementation in knownImplementations) {
                if (implementation.detect(content)) return implementation.convert(context, content)
            }
            
            // Try VLESS converter from feature module
            // TODO: Use proper DI/registry instead of direct dependency
            try {
                val vlessConverter = Class.forName("com.hyperxray.an.feature.vless.config.VlessLinkConverter")
                    .getDeclaredConstructor().newInstance() as? ConfigFormatConverter
                vlessConverter?.let {
                    if (it.detect(content)) return it.convert(context, content)
                }
            } catch (e: Exception) {
                // Feature module not available, continue
            }
            
            return null
        }

        fun convert(context: Context, content: String): Result<DetectedConfig> {
            return convertOrNull(context, content) ?: run {
                return Result.success(Pair("imported_share_" + System.currentTimeMillis(), content))
            }
        }
    }
}