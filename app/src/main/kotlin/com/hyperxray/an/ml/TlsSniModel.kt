package com.hyperxray.an.ml

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException

/**
 * TlsSniModel: ONNX-based TLS SNI optimizer v5 inference engine.
 * 
 * Loads tls_sni_optimizer_v5_fp32.onnx (fallback to FP16) and provides
 * inference with MC Dropout support for uncertainty estimation.
 * 
 * Model I/O:
 * - Input: tls_features [batch, 32] float32
 * - Output: service_type [batch, 8] (softmax)
 * - Output: routing_decision [batch, 3] (softmax)
 */
class TlsSniModel(
    private val context: Context,
    private val mcPasses: Int = 5
) {
    private val TAG = "TlsSniModel"
    
    private val FP32_MODEL_PATH = "models/tls_sni_optimizer_v5_fp32.onnx"
    private val FP16_MODEL_PATH = "models/tls_sni_optimizer_v5_fp16.onnx"
    private val V9_MODEL_PATH = "tls_sni_optimizer_v9.onnx" // Fallback to v9 if v5 not available
    
    private lateinit var env: OrtEnvironment
    private var session: OrtSession? = null
    private var isInitialized = false
    private var modelPath: String = FP32_MODEL_PATH
    
    /**
     * Initialize ONNX Runtime and load the TLS SNI optimizer model.
     * Tries FP32 first, falls back to FP16 if available.
     */
    fun init() {
        if (isInitialized) {
            Log.w(TAG, "TlsSniModel already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing ONNX Runtime environment")
            env = OrtEnvironment.getEnvironment()
            
            // Try FP32 first, then FP16, then fallback to v9
            var modelBytes: ByteArray? = null
            try {
                modelBytes = context.assets.open(FP32_MODEL_PATH).use { it.readBytes() }
                modelPath = FP32_MODEL_PATH
                Log.i(TAG, "Loaded FP32 model: $FP32_MODEL_PATH")
            } catch (e: IOException) {
                Log.w(TAG, "FP32 model not found, trying FP16: ${e.message}")
                try {
                    modelBytes = context.assets.open(FP16_MODEL_PATH).use { it.readBytes() }
                    modelPath = FP16_MODEL_PATH
                    Log.i(TAG, "Loaded FP16 model: $FP16_MODEL_PATH")
                } catch (e2: IOException) {
                    Log.w(TAG, "FP16 model not found, trying v9 fallback: ${e2.message}")
                    try {
                        modelBytes = context.assets.open(V9_MODEL_PATH).use { it.readBytes() }
                        modelPath = V9_MODEL_PATH
                        Log.i(TAG, "Loaded v9 fallback model: $V9_MODEL_PATH")
                    } catch (e3: IOException) {
                        Log.e(TAG, "No TLS SNI model found (tried FP32, FP16, and v9). TLS SNI optimization will be disabled.", e3)
                        return
                    }
                }
            }
            
            if (modelBytes == null || modelBytes.isEmpty() || modelBytes.size < 100) {
                Log.e(TAG, "Model file is invalid (size: ${modelBytes?.size ?: 0})")
                return
            }
            
            Log.d(TAG, "Model file size: ${modelBytes.size} bytes")
            
            // Create session with default options
            val sessionOptions = OrtSession.SessionOptions()
            session = env.createSession(modelBytes, sessionOptions)
            
            isInitialized = true
            Log.i(TAG, "TLS SNI optimizer v5 model loaded successfully from $modelPath")
            Log.d(TAG, "Model inputs: ${session?.inputNames?.joinToString()}")
            Log.d(TAG, "Model outputs: ${session?.outputNames?.joinToString()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TlsSniModel: ${e.message}", e)
            isInitialized = false
        }
    }
    
    /**
     * Run inference on TLS features and return routing decision.
     * 
     * @param features 32-element float array of TLS features
     * @return Pair of (serviceTypeIndex, routingDecisionIndex)
     *         - serviceTypeIndex: 0-7 (YouTube, Netflix, Twitch, etc.)
     *         - routingDecisionIndex: 0=proxy, 1=direct, 2=optimized
     */
    fun infer(features: FloatArray): Pair<Int, Int> {
        if (!isInitialized || session == null) {
            Log.w(TAG, "TlsSniModel not initialized, returning default routing")
            return Pair(7, 0) // Default: other service, proxy routing
        }
        
        if (features.size != 32) {
            Log.e(TAG, "Invalid features array size: ${features.size}, expected 32")
            return Pair(7, 0)
        }
        
        return try {
            // MC Dropout: run multiple passes and average (simulate uncertainty)
            val serviceTypePredictions = mutableListOf<Int>()
            val routingPredictions = mutableListOf<Int>()
            
            repeat(mcPasses) {
                val (svc, route) = runSingleInference(features)
                serviceTypePredictions.add(svc)
                routingPredictions.add(route)
            }
            
            // Majority vote for final prediction
            val serviceTypeIndex = serviceTypePredictions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 7
            val routingDecisionIndex = routingPredictions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
            
            Log.d(TAG, "MC Dropout inference (passes=$mcPasses): service=$serviceTypeIndex, route=$routingDecisionIndex")
            Pair(serviceTypeIndex, routingDecisionIndex)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            Pair(7, 0)
        }
    }
    
    /**
     * Run single inference pass (used for MC Dropout).
     */
    private fun runSingleInference(features: FloatArray): Pair<Int, Int> {
        // Create input tensor: shape [1, 32] for batch inference
        val inputData = Array(1) { features }
        val inputTensor = OnnxTensor.createTensor(env, inputData)
        
        try {
            // Get input name (default to first input if not specified)
            val inputName = session!!.inputNames.firstOrNull() ?: "tls_features"
            
            // Run inference
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session!!.run(inputs)
            
            // Extract outputs
            val outputNames = session!!.outputNames
            
            if (outputNames.size < 2) {
                Log.e(TAG, "Expected 2 outputs, got ${outputNames.size}")
                inputTensor.close()
                outputs.close()
                return Pair(7, 0)
            }
            
            // Get service_type output (first output)
            val serviceTypeTensor = outputs.get(0)
            val serviceTypeValue = serviceTypeTensor.value
            
            // Get routing_decision output (second output)
            val routingDecisionTensor = outputs.get(1)
            val routingDecisionValue = routingDecisionTensor.value
            
            // Parse outputs
            val serviceTypeArray = parseOutputArray(serviceTypeValue, 8)
            val routingDecisionArray = parseOutputArray(routingDecisionValue, 3)
            
            // Find index with maximum value (argmax)
            val serviceTypeIndex = serviceTypeArray.indices.maxByOrNull { serviceTypeArray[it] } ?: 7
            val routingDecisionIndex = routingDecisionArray.indices.maxByOrNull { routingDecisionArray[it] } ?: 0
            
            // Clean up resources
            inputTensor.close()
            outputs.close()
            
            return Pair(serviceTypeIndex, routingDecisionIndex)
            
        } catch (e: Exception) {
            try {
                inputTensor.close()
            } catch (closeError: Exception) {
                // Ignore close errors
            }
            Log.e(TAG, "Error during inference: ${e.message}", e)
            return Pair(7, 0) // Return default on error
        }
    }
    
    /**
     * Parse ONNX output to FloatArray.
     */
    private fun parseOutputArray(value: Any?, expectedSize: Int): FloatArray {
        return when (value) {
            is Array<*> -> {
                // Handle Array<FloatArray> or Array<Array<Float>>
                (value[0] as? FloatArray) ?: 
                (value[0] as? Array<*>)?.map { (it as? Number)?.toFloat() ?: 0f }?.toFloatArray() ?:
                FloatArray(expectedSize) { 0f }
            }
            is FloatArray -> value
            else -> {
                Log.w(TAG, "Unexpected output type: ${value?.javaClass}")
                FloatArray(expectedSize) { 0f }
            }
        }
    }
    
    /**
     * Check if the model is initialized and ready for inference.
     */
    fun isReady(): Boolean {
        return isInitialized && session != null
    }
    
    /**
     * Release resources and close the ONNX session.
     */
    fun release() {
        try {
            session?.close()
            session = null
            isInitialized = false
            Log.d(TAG, "TlsSniModel released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TlsSniModel: ${e.message}", e)
        }
    }
}

