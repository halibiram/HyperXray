package com.hyperxray.an.feature.policyai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ONNX Runtime Mobile routing engine for AI-powered routing decisions.
 * 
 * Features:
 * - Automatic model loading from assets or internal storage
 * - Zero-copy tensor conversion to minimize GC pressure
 * - Thread-safe inference operations
 * - Suspend function API for coroutine integration
 * 
 * Model path priority:
 * 1. Internal storage: files/policy_routing.onnx
 * 2. Assets: models/hyperxray_policy.onnx
 * 3. Assets fallback: models/hyperxray_policy_int8.onnx
 */
object OnnxRuntimeRoutingEngine {
    private const val TAG = "OnnxRuntimeRouting"
    
    // Model paths (priority order)
    private const val INTERNAL_MODEL_NAME = "policy_routing.onnx"
    private const val ASSETS_MODEL_PATH = "models/hyperxray_policy.onnx"
    private const val ASSETS_MODEL_INT8_PATH = "models/hyperxray_policy_int8.onnx"
    
    // Model I/O specifications
    private const val INPUT_SIZE = 32  // 32D feature vector
    private const val OUTPUT_SERVICE_SIZE = 8  // Service class probabilities
    private const val OUTPUT_ROUTING_SIZE = 3  // Routing decision probabilities
    
    private lateinit var env: OrtEnvironment
    private var session: OrtSession? = null
    private val initializationMutex = Mutex()
    private var isInitialized = false
    
    // Reusable array for tensor creation (minimize allocations)
    private val tensorArray = ThreadLocal.withInitial { 
        Array(1) { FloatArray(INPUT_SIZE) }
    }
    
    /**
     * Initialize ONNX Runtime and load the model.
     * Thread-safe initialization - multiple calls are safe.
     * 
     * @param context Android context
     * @return true if initialized successfully, false otherwise
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            if (isInitialized) {
                Log.d(TAG, "Already initialized")
                return@withLock true
            }
            
            return@withLock try {
                Log.d(TAG, "Initializing ONNX Runtime Mobile environment")
                env = OrtEnvironment.getEnvironment()
                
                // Try loading model from internal storage first, then assets
                val modelBytes = loadModelFromStorage(context) 
                    ?: loadModelFromAssets(context)
                
                if (modelBytes == null || modelBytes.isEmpty() || modelBytes.size < 100) {
                    Log.e(TAG, "Failed to load model (size: ${modelBytes?.size ?: 0})")
                    return@withLock false
                }
                
                Log.d(TAG, "Model loaded successfully (${modelBytes.size} bytes)")
                
                // Create session with optimized options for mobile
                val sessionOptions = OrtSession.SessionOptions().apply {
                    // Use CPU execution provider (best compatibility)
                    // Mobile-specific optimizations are applied by ONNX Runtime Mobile
                    addCPU(true)
                    
                    // Set number of threads (default is optimal for mobile)
                    // setIntraOpNumThreads(2) // Uncomment if needed for specific optimization
                }
                
                session = env.createSession(modelBytes, sessionOptions)
                
                // Validate model I/O
                val inputNames = session!!.inputNames
                val outputNames = session!!.outputNames
                
                Log.d(TAG, "Model inputs: ${inputNames.joinToString()}")
                Log.d(TAG, "Model outputs: ${outputNames.joinToString()}")
                
                if (inputNames.isEmpty() || outputNames.size < 2) {
                    Log.e(TAG, "Invalid model I/O: inputs=${inputNames.size}, outputs=${outputNames.size}")
                    session?.close()
                    session = null
                    return@withLock false
                }
                
                isInitialized = true
                Log.i(TAG, "ONNX Runtime Mobile initialized successfully")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ONNX Runtime: ${e.message}", e)
                try {
                    session?.close()
                } catch (closeError: Exception) {
                    Log.w(TAG, "Error closing session: ${closeError.message}")
                }
                session = null
                isInitialized = false
                false
            }
        }
    }
    
    /**
     * Load model from internal storage (files/policy_routing.onnx).
     */
    private fun loadModelFromStorage(context: Context): ByteArray? {
        return try {
            val internalFile = File(context.filesDir, INTERNAL_MODEL_NAME)
            if (internalFile.exists() && internalFile.length() > 100) {
                Log.d(TAG, "Loading model from internal storage: ${internalFile.absolutePath}")
                internalFile.readBytes()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load model from internal storage: ${e.message}")
            null
        }
    }
    
    /**
     * Load model from assets.
     * Tries multiple paths in order of preference.
     */
    private fun loadModelFromAssets(context: Context): ByteArray? {
        val paths = listOf(ASSETS_MODEL_PATH, ASSETS_MODEL_INT8_PATH)
        
        for (path in paths) {
            try {
                context.assets.open(path).use { inputStream ->
                    Log.d(TAG, "Loading model from assets: $path")
                    val bytes = inputStream.readBytes()
                    if (bytes.isNotEmpty() && bytes.size > 100) {
                        // Optionally copy to internal storage for faster future loads
                        try {
                            val internalFile = File(context.filesDir, INTERNAL_MODEL_NAME)
                            if (!internalFile.exists()) {
                                FileOutputStream(internalFile).use { outputStream ->
                                    outputStream.write(bytes)
                                }
                                Log.d(TAG, "Copied model to internal storage for faster access")
                            }
                        } catch (copyError: Exception) {
                            Log.w(TAG, "Failed to copy model to internal storage: ${copyError.message}")
                        }
                        return bytes
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "Model not found in assets: $path")
                // Continue to next path
            }
        }
        
        return null
    }
    
    /**
     * Evaluate routing decision from features.
     * 
     * Zero-copy tensor conversion: Uses thread-local pre-allocated arrays to minimize GC pressure.
     * 
     * @param features 32-element float array (feature vector)
     * @return RoutingDecision with service class, ALPN, route decision, and confidence
     */
    suspend fun evaluateRouting(features: FloatArray): RoutingDecision = withContext(Dispatchers.Default) {
        if (!isInitialized || session == null) {
            Log.w(TAG, "Not initialized, returning default decision")
            return@withContext RoutingDecision(
                svcClass = 7,
                alpn = "h2",
                routeDecision = 0,
                confidence = 0.0f
            )
        }
        
        if (features.size != INPUT_SIZE) {
            Log.e(TAG, "Invalid features size: ${features.size}, expected $INPUT_SIZE")
            return@withContext RoutingDecision(7, "h2", 0, 0.0f)
        }
        
        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null
        
        try {
            // Zero-copy tensor creation: reuse thread-local array to minimize GC pressure
            // Direct copy to pre-allocated array (single allocation per thread)
            val tensorData = tensorArray.get()
            val inputRow = tensorData[0]
            System.arraycopy(features, 0, inputRow, 0, INPUT_SIZE)
            
            // Create tensor from pre-allocated array (native side uses direct memory mapping)
            // Shape: [1, 32] for batch inference
            inputTensor = OnnxTensor.createTensor(env, tensorData)
            
            // Run inference
            val inputName = session!!.inputNames.firstOrNull() ?: "tls_features"
            val inputs = mapOf(inputName to inputTensor)
            outputs = session!!.run(inputs)
            
            // Extract outputs with zero-copy where possible
            val outputNames = session!!.outputNames
            if (outputNames.size < 2) {
                Log.e(TAG, "Expected 2 outputs, got ${outputNames.size}")
                return@withContext RoutingDecision(7, "h2", 0, 0.0f)
            }
            
            // Parse service type output (first output) - 8 classes
            val serviceTypeTensor = outputs.get(0)
            val serviceTypeArray = extractFloatArray(serviceTypeTensor.value, OUTPUT_SERVICE_SIZE)
            
            // Parse routing decision output (second output) - 3 decisions
            val routingDecisionTensor = outputs.get(1)
            val routingDecisionArray = extractFloatArray(routingDecisionTensor.value, OUTPUT_ROUTING_SIZE)
            
            // Get argmax (predicted class)
            val svcClass = serviceTypeArray.indices.maxByOrNull { serviceTypeArray[it] } ?: 7
            val routeDecision = routingDecisionArray.indices.maxByOrNull { routingDecisionArray[it] } ?: 0
            
            // Compute confidence (max probability)
            val svcConfidence = serviceTypeArray[svcClass]
            val routeConfidence = routingDecisionArray[routeDecision]
            val confidence = (svcConfidence + routeConfidence) / 2f
            
            // Determine ALPN based on service class and routing decision
            val alpn = determineAlpn(svcClass, routeDecision)
            
            Log.d(TAG, "Routing decision: svcClass=$svcClass, route=$routeDecision, alpn=$alpn, confidence=$confidence")
            
            RoutingDecision(
                svcClass = svcClass,
                alpn = alpn,
                routeDecision = routeDecision,
                confidence = confidence
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            RoutingDecision(7, "h2", 0, 0.0f)
        } finally {
            // Clean up resources
            try {
                inputTensor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing input tensor: ${e.message}")
            }
            try {
                outputs?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing outputs: ${e.message}")
            }
        }
    }
    
    /**
     * Extract FloatArray from ONNX output value with zero-copy where possible.
     */
    private fun extractFloatArray(value: Any?, expectedSize: Int): FloatArray {
        return when (value) {
            is FloatArray -> {
                if (value.size == expectedSize) {
                    value // Direct return if size matches
                } else if (value.size > expectedSize) {
                    value.copyOf(expectedSize) // Copy only needed portion
                } else {
                    // Pad with zeros if smaller
                    val padded = FloatArray(expectedSize) { i ->
                        if (i < value.size) value[i] else 0f
                    }
                    Log.w(TAG, "ONNX output size mismatch: expected=$expectedSize, got=${value.size}")
                    padded
                }
            }
            is Array<*> -> {
                // Handle nested arrays: Array<Array<Float>> or Array<FloatArray>
                val first = value.firstOrNull()
                when {
                    first is FloatArray -> {
                        if (first.size == expectedSize) {
                            first
                        } else {
                            first.copyOf(expectedSize)
                        }
                    }
                    first is Array<*> -> {
                        (first as Array<*>).mapNotNull { (it as? Number)?.toFloat() }
                            .take(expectedSize)
                            .let { result ->
                                if (result.size == expectedSize) {
                                    result.toFloatArray()
                                } else {
                                    FloatArray(expectedSize) { i ->
                                        result.getOrNull(i) ?: 0f
                                    }
                                }
                            }
                    }
                    else -> {
                        // Fallback: map all elements
                        value.mapNotNull { (it as? Number)?.toFloat() }
                            .take(expectedSize)
                            .let { result ->
                                if (result.size == expectedSize) {
                                    result.toFloatArray()
                                } else {
                                    FloatArray(expectedSize) { i ->
                                        result.getOrNull(i) ?: 0f
                                    }
                                }
                            }
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unexpected output type: ${value?.javaClass}")
                FloatArray(expectedSize) { 0f }
            }
        }
    }
    
    /**
     * Determine ALPN protocol based on service class and routing decision.
     */
    private fun determineAlpn(svcClass: Int, routeDecision: Int): String {
        // Optimized routing prefers h3
        if (routeDecision == 2) {
            return "h3"
        }
        
        // Video services prefer h2/h3
        if (svcClass in listOf(0, 1, 2, 5)) { // YouTube, Netflix, Twitter, Twitch
            return if (routeDecision == 1) "h3" else "h2"
        }
        
        // Default to h2
        return "h2"
    }
    
    /**
     * Check if the engine is initialized and ready for inference.
     */
    fun isReady(): Boolean = isInitialized && session != null
    
    /**
     * Release resources.
     */
    suspend fun release() = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            try {
                session?.close()
                session = null
                isInitialized = false
                Log.d(TAG, "OnnxRuntimeRoutingEngine released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing engine: ${e.message}", e)
            }
        }
    }
}

