package com.hyperxray.an.common.configFormat

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.AiLogHelper

/**
 * Interface for config format converters.
 * SimpleXray approach: Simple and direct conversion.
 */
interface ConfigFormatConverter {
    fun detect(content: String): Boolean
    fun convert(context: Context, content: String): Result<DetectedConfig>

    companion object {
        private const val TAG = "ConfigFormatConverter"
        
        /**
         * Known converter implementations.
         * SimpleXray approach: Register converters in order of priority.
         */
        private val knownImplementations = listOf(
            HyperXrayFormatConverter(),
            SimpleXrayFormatConverter(),
        )

        /**
         * Convert content to config using available converters.
         * SimpleXray approach: Try converters in order, fallback to raw content.
         */
        fun convert(context: Context, content: String): Result<DetectedConfig> {
            AiLogHelper.d(TAG, "🔄 CONVERTER: Starting conversion, content length: ${content.length}, preview: ${content.take(50)}...")
            
            // Try known implementations first
            AiLogHelper.d(TAG, "🔄 CONVERTER: Trying ${knownImplementations.size} known implementations...")
            for (implementation in knownImplementations) {
                val detected = implementation.detect(content)
                AiLogHelper.d(TAG, "🔄 CONVERTER: ${implementation.javaClass.simpleName}.detect() = $detected")
                if (detected) {
                    AiLogHelper.d(TAG, "✅ CONVERTER: ${implementation.javaClass.simpleName} detected, calling convert()...")
                    val result = implementation.convert(context, content)
                    result.fold(
                        onSuccess = { config ->
                            AiLogHelper.i(TAG, "✅ CONVERTER: ${implementation.javaClass.simpleName} succeeded: name=${config.name}, content length=${config.content.length}")
                        },
                        onFailure = { e ->
                            AiLogHelper.e(TAG, "❌ CONVERTER: ${implementation.javaClass.simpleName} failed: ${e.message}", e)
                        }
                    )
                    return result
                }
            }
            
            AiLogHelper.d(TAG, "🔄 CONVERTER: No known implementation matched, trying VlessLinkConverter via reflection...")
            // Try VLESS converter from feature module using reflection
            // Feature module has its own interface to avoid circular dependencies
            try {
                val vlessConverterClass = Class.forName("com.hyperxray.an.feature.vless.config.VlessLinkConverter")
                Log.d(TAG, "Found VlessLinkConverter class: ${vlessConverterClass.name}")
                AiLogHelper.d(TAG, "✅ CONVERTER: VlessLinkConverter class found: ${vlessConverterClass.name}")
                val vlessConverter = vlessConverterClass.getDeclaredConstructor().newInstance()
                AiLogHelper.d(TAG, "✅ CONVERTER: VlessLinkConverter instance created")
                
                // Use reflection to call methods (feature module has its own interface)
                val detectMethod = vlessConverterClass.getMethod("detect", String::class.java)
                val detected = detectMethod.invoke(vlessConverter, content) as? Boolean
                Log.d(TAG, "VlessLinkConverter.detect() returned: $detected")
                AiLogHelper.d(TAG, "🔄 CONVERTER: VlessLinkConverter.detect() = $detected")
                
                if (detected == true) {
                    AiLogHelper.d(TAG, "🔄 CONVERTER: VlessLinkConverter detected vless://, calling convert()...")
                    // Find convert method - Kotlin may compile it with mangled name (e.g., convert-gIAlu-s)
                    val convertMethod = try {
                        vlessConverterClass.getMethod("convert", android.content.Context::class.java, String::class.java)
                    } catch (e: NoSuchMethodException) {
                        Log.d(TAG, "getMethod failed, trying declaredMethods")
                        // Try to find method by name (may be mangled) and parameter count
                        val methods = vlessConverterClass.declaredMethods
                        Log.d(TAG, "Found ${methods.size} declared methods")
                        methods.forEach { method ->
                            Log.d(TAG, "Method: ${method.name}, params: ${method.parameterTypes.joinToString { it.simpleName }}")
                        }
                        // Kotlin may mangle method names, so check if name starts with "convert"
                        methods.find { 
                            it.name.startsWith("convert") && it.parameterCount == 2 &&
                            it.parameterTypes[0].isAssignableFrom(android.content.Context::class.java) &&
                            it.parameterTypes[1] == String::class.java
                        } ?: throw e
                    }
                    Log.d(TAG, "Found convert method: ${convertMethod.name}")
                    AiLogHelper.d(TAG, "✅ CONVERTER: Found convert method: ${convertMethod.name}")
                    convertMethod.isAccessible = true
                    val resultObj = convertMethod.invoke(vlessConverter, context, content)
                    Log.d(TAG, "convert() returned: ${resultObj?.javaClass?.name}")
                    AiLogHelper.d(TAG, "🔄 CONVERTER: convert() returned: ${resultObj?.javaClass?.name}")
                    
                    // Check if result is Pair (direct return) or Result<Pair>
                    val resultClass = resultObj?.javaClass
                    if (resultClass != null) {
                        if (resultClass.name == "kotlin.Pair") {
                            // Direct Pair return (feature module may return Pair directly)
                            val pair = resultObj as? Pair<*, *>
                            if (pair != null) {
                                val name = pair.first as? String ?: "imported_vless_${System.currentTimeMillis()}"
                                val configContent = pair.second as? String ?: return Result.failure(IllegalStateException("Empty config content"))
                                Log.d(TAG, "Successfully converted VLESS link to config: $name")
                                AiLogHelper.i(TAG, "✅ CONVERTER: VlessLinkConverter succeeded (Pair): name=$name, content length=${configContent.length}, preview: ${configContent.take(100)}...")
                                return Result.success(DetectedConfig(name, configContent, false))
                            }
                        } else {
                            // Result is kotlin.Result, extract the value
                            try {
                                val isSuccessMethod = resultClass.getMethod("isSuccess")
                                val isSuccess = isSuccessMethod.invoke(resultObj) as? Boolean
                                Log.d(TAG, "Result isSuccess: $isSuccess")
                                AiLogHelper.d(TAG, "🔄 CONVERTER: Result.isSuccess = $isSuccess")
                                
                                if (isSuccess == true) {
                                    // Try to get value from Result using getOrNull
                                    try {
                                        val getOrNullMethod = resultClass.getMethod("getOrNull")
                                        val resultValue = getOrNullMethod.invoke(resultObj)
                                        if (resultValue != null) {
                                            // Handle both Pair and DetectedConfig
                                            val config = when (resultValue) {
                                                is Pair<*, *> -> {
                                                    val name = resultValue.first as? String ?: "imported_vless_${System.currentTimeMillis()}"
                                                    val configContent = resultValue.second as? String ?: return Result.failure(IllegalStateException("Empty config content"))
                                                    DetectedConfig(name, configContent, false)
                                                }
                                                is DetectedConfig -> resultValue
                                                else -> {
                                                    val name = "imported_vless_${System.currentTimeMillis()}"
                                                    val configContent = resultValue.toString()
                                                    DetectedConfig(name, configContent, false)
                                                }
                                            }
                                            Log.d(TAG, "Successfully converted VLESS link to config: ${config.name}")
                                            AiLogHelper.i(TAG, "✅ CONVERTER: VlessLinkConverter succeeded (Result<Pair/Config>): name=${config.name}, content length=${config.content.length}, preview: ${config.content.take(100)}...")
                                            return Result.success(config)
                                        } else {
                                            AiLogHelper.e(TAG, "❌ CONVERTER: VlessLinkConverter returned success but value is null")
                                        }
                                    } catch (e: Exception) {
                                        // Try alternative: get() method
                                        try {
                                            val getMethod = resultClass.getMethod("get")
                                            val resultValue = getMethod.invoke(resultObj)
                                            if (resultValue != null) {
                                                val config = when (resultValue) {
                                                    is Pair<*, *> -> {
                                                        val name = resultValue.first as? String ?: "imported_vless_${System.currentTimeMillis()}"
                                                        val configContent = resultValue.second as? String ?: return Result.failure(IllegalStateException("Empty config content"))
                                                        DetectedConfig(name, configContent, false)
                                                    }
                                                    is DetectedConfig -> resultValue
                                                    else -> {
                                                        val name = "imported_vless_${System.currentTimeMillis()}"
                                                        val configContent = resultValue.toString()
                                                        DetectedConfig(name, configContent, false)
                                                    }
                                                }
                                                AiLogHelper.i(TAG, "✅ CONVERTER: VlessLinkConverter succeeded (Result.get()): name=${config.name}, content length=${config.content.length}")
                                                return Result.success(config)
                                            }
                                        } catch (e2: Exception) {
                                            AiLogHelper.e(TAG, "❌ CONVERTER: Failed to extract value from Result: getOrNull()=${e.message}, get()=${e2.message}")
                                        }
                                    }
                                } else {
                                    // Result is failure, get exception
                                    try {
                                        val exceptionOrNullMethod = resultClass.getMethod("exceptionOrNull")
                                        val exception = exceptionOrNullMethod.invoke(resultObj) as? Throwable
                                        if (exception != null) {
                                            Log.e(TAG, "VlessLinkConverter conversion failed", exception)
                                            AiLogHelper.e(TAG, "❌ CONVERTER: VlessLinkConverter conversion failed: ${exception.message}", exception)
                                            return Result.failure(exception)
                                        } else {
                                            AiLogHelper.e(TAG, "❌ CONVERTER: VlessLinkConverter returned failure but exception is null")
                                        }
                                    } catch (e: Exception) {
                                        AiLogHelper.e(TAG, "❌ CONVERTER: Failed to get exception from Result: ${e.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to extract result from VlessLinkConverter", e)
                                AiLogHelper.e(TAG, "❌ CONVERTER: Failed to extract result from VlessLinkConverter: ${e.message}", e)
                            }
                        }
                    } else {
                        AiLogHelper.e(TAG, "❌ CONVERTER: VlessLinkConverter.convert() returned null")
                    }
                } else {
                    AiLogHelper.d(TAG, "🔄 CONVERTER: VlessLinkConverter.detect() returned false")
                }
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "VlessLinkConverter class not found, feature module may not be available")
                AiLogHelper.w(TAG, "⚠️ CONVERTER: VlessLinkConverter class not found: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading VlessLinkConverter", e)
                AiLogHelper.e(TAG, "❌ CONVERTER: Error loading VlessLinkConverter: ${e.message}", e)
            }
            
            // Fallback: Assume content is raw JSON or will be handled as-is
            // SimpleXray approach: Return raw content if no converter matches
            AiLogHelper.w(TAG, "⚠️ CONVERTER: No converter matched, using fallback (raw content). This means vless:// link was not converted!")
            return Result.success(DetectedConfig("imported_${System.currentTimeMillis()}", content, false))
        }
    }
}