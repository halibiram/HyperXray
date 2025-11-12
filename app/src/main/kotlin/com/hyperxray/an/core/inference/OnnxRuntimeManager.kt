package com.hyperxray.an.core.inference

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException

/**
 * OnnxRuntimeManager: Manages ONNX model loading and inference for TLS SNI optimization.
 * 
 * Loads tls_sni_optimizer_v2.onnx from assets and provides inference capabilities
 * for routing decisions based on TLS features.
 * 
 * Model I/O:
 * - Input: tls_features [batch, 32] float32
 * - Output: service_type [batch, 8] (softmax)
 * - Output: routing_decision [batch, 3] (softmax)
 */
object OnnxRuntimeManager {
    private const val TAG = "OnnxRuntimeManager"
    private const val MODEL_PATH = "tls_sni_optimizer_v2.onnx"
    
    private lateinit var env: OrtEnvironment
    private var session: OrtSession? = null
    private var isInitialized = false
    
    /**
     * Initialize ONNX Runtime and load the TLS SNI optimizer model.
     * Should be called at app startup.
     */
    fun init(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "OnnxRuntimeManager already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing ONNX Runtime environment")
            env = OrtEnvironment.getEnvironment()
            
            Log.d(TAG, "Loading TLS SNI optimizer model from assets: $MODEL_PATH")
            val modelBytes = try {
                context.assets.open(MODEL_PATH).use { inputStream ->
                    inputStream.readBytes()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read model file from assets: $MODEL_PATH", e)
                return
            }
            
            if (modelBytes.isEmpty() || modelBytes.size < 100) {
                val errorMsg = if (modelBytes.isEmpty()) {
                    "Model file is empty"
                } else {
                    "Model file is too small (${modelBytes.size} bytes), expected valid ONNX model"
                }
                Log.e(TAG, errorMsg)
                return
            }
            
            Log.d(TAG, "Model file size: ${modelBytes.size} bytes")
            
            // Create session with default options
            val sessionOptions = OrtSession.SessionOptions()
            session = env.createSession(modelBytes, sessionOptions)
            
            isInitialized = true
            Log.i(TAG, "TLS SNI optimizer model loaded successfully")
            Log.d(TAG, "Model inputs: ${session?.inputNames?.joinToString()}")
            Log.d(TAG, "Model outputs: ${session?.outputNames?.joinToString()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OnnxRuntimeManager: ${e.message}", e)
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
    fun predict(features: FloatArray): Pair<Int, Int> {
        if (!isInitialized || session == null) {
            Log.w(TAG, "OnnxRuntimeManager not initialized, returning default routing")
            return Pair(7, 0) // Default: other service, proxy routing
        }
        
        if (features.size != 32) {
            Log.e(TAG, "Invalid features array size: ${features.size}, expected 32")
            return Pair(7, 0)
        }
        
        return try {
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
                // Expected outputs: service_type [batch, 8] and routing_decision [batch, 3]
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
                val serviceTypeArray = when (serviceTypeValue) {
                    is Array<*> -> {
                        // Handle Array<FloatArray> or Array<Array<Float>>
                        (serviceTypeValue[0] as? FloatArray) ?: 
                        (serviceTypeValue[0] as? Array<*>)?.map { (it as? Number)?.toFloat() ?: 0f }?.toFloatArray() ?:
                        FloatArray(8) { 0f }
                    }
                    is FloatArray -> serviceTypeValue
                    else -> {
                        Log.w(TAG, "Unexpected service_type output type: ${serviceTypeValue.javaClass}")
                        FloatArray(8) { 0f }
                    }
                }
                
                val routingDecisionArray = when (routingDecisionValue) {
                    is Array<*> -> {
                        // Handle Array<FloatArray> or Array<Array<Float>>
                        (routingDecisionValue[0] as? FloatArray) ?:
                        (routingDecisionValue[0] as? Array<*>)?.map { (it as? Number)?.toFloat() ?: 0f }?.toFloatArray() ?:
                        FloatArray(3) { 0f }
                    }
                    is FloatArray -> routingDecisionValue
                    else -> {
                        Log.w(TAG, "Unexpected routing_decision output type: ${routingDecisionValue.javaClass}")
                        FloatArray(3) { 0f }
                    }
                }
                
                // Find index with maximum value (argmax)
                val serviceTypeIndex = serviceTypeArray.indices.maxByOrNull { serviceTypeArray[it] } ?: 7
                val routingDecisionIndex = routingDecisionArray.indices.maxByOrNull { routingDecisionArray[it] } ?: 0
                
                // Clean up resources
                inputTensor.close()
                outputs.close()
                
                Log.d(TAG, "Inference completed: service=$serviceTypeIndex, route=$routingDecisionIndex")
                Pair(serviceTypeIndex, routingDecisionIndex)
                
            } catch (e: Exception) {
                inputTensor.close()
                Log.e(TAG, "Error during inference: ${e.message}", e)
                Pair(7, 0)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating input tensor: ${e.message}", e)
            Pair(7, 0)
        }
    }
    
    /**
     * Check if the manager is initialized and ready for inference.
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
            Log.d(TAG, "OnnxRuntimeManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing OnnxRuntimeManager: ${e.message}", e)
        }
    }
}

