package com.hyperxray.an.optimizer

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException

/**
 * OrtHolder: Manages ONNX Runtime environment and session for TLS SNI optimizer v9.
 * 
 * Loads tls_sni_optimizer_v9.onnx from assets and provides inference capabilities.
 */
object OrtHolder {
    private const val TAG = "OrtHolder"
    private const val MODEL_PATH = "tls_sni_optimizer_v9.onnx"
    
    private lateinit var env: OrtEnvironment
    private var session: OrtSession? = null
    private var isInitialized = false
    
    /**
     * Initialize ONNX Runtime and load the model.
     */
    fun init(context: Context): Boolean {
        if (isInitialized) {
            Log.w(TAG, "OrtHolder already initialized")
            return true
        }
        
        return try {
            Log.d(TAG, "Initializing ONNX Runtime environment")
            env = OrtEnvironment.getEnvironment()
            
            Log.d(TAG, "Loading TLS SNI optimizer model from assets: $MODEL_PATH")
            val modelBytes = try {
                context.assets.open(MODEL_PATH).use { it.readBytes() }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read model file from assets: $MODEL_PATH", e)
                return false
            }
            
            if (modelBytes.isEmpty() || modelBytes.size < 100) {
                Log.e(TAG, "Model file is invalid (size: ${modelBytes.size})")
                return false
            }
            
            Log.d(TAG, "Model file size: ${modelBytes.size} bytes")
            
            // Create session with default options
            val sessionOptions = OrtSession.SessionOptions()
            session = env.createSession(modelBytes, sessionOptions)
            
            isInitialized = true
            Log.i(TAG, "TLS SNI optimizer v9 model loaded successfully")
            Log.d(TAG, "Model inputs: ${session?.inputNames?.joinToString()}")
            Log.d(TAG, "Model outputs: ${session?.outputNames?.joinToString()}")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OrtHolder: ${e.message}", e)
            false
        }
    }
    
    /**
     * Run inference on features.
     * 
     * @param features 32-element float array
     * @return Pair of (serviceTypeArray[8], routingDecisionArray[3])
     */
    fun runInference(features: FloatArray): Pair<FloatArray, FloatArray>? {
        if (!isInitialized || session == null) {
            Log.w(TAG, "OrtHolder not initialized")
            return null
        }
        
        if (features.size != 32) {
            Log.e(TAG, "Invalid features array size: ${features.size}, expected 32")
            return null
        }
        
        return try {
            // Create input tensor: shape [1, 32]
            val inputData = Array(1) { features }
            val inputTensor = OnnxTensor.createTensor(env, inputData)
            
            try {
                val inputName = session!!.inputNames.firstOrNull() ?: "tls_features"
                val inputs = mapOf(inputName to inputTensor)
                val outputs = session!!.run(inputs)
                
                // Extract outputs
                val outputNames = session!!.outputNames
                if (outputNames.size < 2) {
                    Log.e(TAG, "Expected 2 outputs, got ${outputNames.size}")
                    inputTensor.close()
                    outputs.close()
                    return null
                }
                
                // Parse service_type output (first output)
                val serviceTypeTensor = outputs.get(0)
                val serviceTypeValue = serviceTypeTensor.value
                val serviceTypeArray = parseOutputArray(serviceTypeValue, 8)
                
                // Parse routing_decision output (second output)
                val routingDecisionTensor = outputs.get(1)
                val routingDecisionValue = routingDecisionTensor.value
                val routingDecisionArray = parseOutputArray(routingDecisionValue, 3)
                
                inputTensor.close()
                outputs.close()
                
                Pair(serviceTypeArray, routingDecisionArray)
                
            } catch (e: Exception) {
                inputTensor.close()
                throw e
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            null
        }
    }
    
    /**
     * Parse ONNX output to FloatArray.
     */
    private fun parseOutputArray(value: Any?, expectedSize: Int): FloatArray {
        return when (value) {
            is Array<*> -> {
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
     * Check if initialized.
     */
    fun isReady(): Boolean = isInitialized && session != null
    
    /**
     * Release resources.
     */
    fun release() {
        try {
            session?.close()
            session = null
            isInitialized = false
            Log.d(TAG, "OrtHolder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing OrtHolder: ${e.message}", e)
        }
    }
}

