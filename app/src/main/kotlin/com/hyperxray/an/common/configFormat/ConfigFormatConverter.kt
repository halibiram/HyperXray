package com.hyperxray.an.common.configFormat

import android.content.Context
import android.util.Log

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
            // Try known implementations first
            for (implementation in knownImplementations) {
                if (implementation.detect(content)) {
                    return implementation.convert(context, content)
                }
            }
            
            // Try VLESS converter from feature module using reflection
            // Feature module has its own interface to avoid circular dependencies
            try {
                val vlessConverterClass = Class.forName("com.hyperxray.an.feature.vless.config.VlessLinkConverter")
                Log.d(TAG, "Found VlessLinkConverter class: ${vlessConverterClass.name}")
                val vlessConverter = vlessConverterClass.getDeclaredConstructor().newInstance()
                
                // Use reflection to call methods (feature module has its own interface)
                val detectMethod = vlessConverterClass.getMethod("detect", String::class.java)
                val detected = detectMethod.invoke(vlessConverter, content) as? Boolean
                Log.d(TAG, "VlessLinkConverter.detect() returned: $detected")
                
                if (detected == true) {
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
                    convertMethod.isAccessible = true
                    val resultObj = convertMethod.invoke(vlessConverter, context, content)
                    Log.d(TAG, "convert() returned: ${resultObj?.javaClass?.name}")
                    
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
                                return Result.success(Pair(name, configContent))
                            }
                        } else {
                            // Result is kotlin.Result, extract the value
                            try {
                                val isSuccessMethod = resultClass.getMethod("isSuccess")
                                val isSuccess = isSuccessMethod.invoke(resultObj) as? Boolean
                                Log.d(TAG, "Result isSuccess: $isSuccess")
                                
                                if (isSuccess == true) {
                                    // Try to get value from Result using getOrNull
                                    val getOrNullMethod = resultClass.getMethod("getOrNull")
                                    val pair = getOrNullMethod.invoke(resultObj) as? Pair<*, *>
                                    if (pair != null) {
                                        val name = pair.first as? String ?: "imported_vless_${System.currentTimeMillis()}"
                                        val configContent = pair.second as? String ?: return Result.failure(IllegalStateException("Empty config content"))
                                        Log.d(TAG, "Successfully converted VLESS link to config: $name")
                                        return Result.success(Pair(name, configContent))
                                    }
                                } else {
                                    // Result is failure, get exception
                                    val exceptionOrNullMethod = resultClass.getMethod("exceptionOrNull")
                                    val exception = exceptionOrNullMethod.invoke(resultObj) as? Throwable
                                    if (exception != null) {
                                        Log.e(TAG, "VlessLinkConverter conversion failed", exception)
                                        return Result.failure(exception)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to extract result from VlessLinkConverter", e)
                            }
                        }
                    }
                }
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "VlessLinkConverter class not found, feature module may not be available")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading VlessLinkConverter", e)
            }
            
            // Fallback: Assume content is raw JSON or will be handled as-is
            // SimpleXray approach: Return raw content if no converter matches
            return Result.success(Pair("imported_${System.currentTimeMillis()}", content))
        }
    }
}