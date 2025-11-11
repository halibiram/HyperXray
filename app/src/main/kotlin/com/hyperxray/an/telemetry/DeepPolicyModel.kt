package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hyperxray.an.common.AiLogHelper
import java.io.IOException

/**
 * DeepPolicyModel: ONNX-based neural network inference for Reality server selection.
 * 
 * Loads and runs an ONNX model to predict optimal RealityArm selection
 * based on normalized context features.
 * 
 * Supports:
 * - Model signature verification (SHA256, Ed25519)
 * - Fallback handler for invalid models
 * - Manifest-based model metadata
 * - GPU/NPU execution providers (NNAPI, OpenGL/OpenCL) via performance profiles
 */
class DeepPolicyModel(
    private val context: Context,
    private val modelPath: String = "models/hyperxray_policy.onnx",
    private val scaler: Scaler = Scaler.default(),
    private val useVerification: Boolean = false,
    private val fallbackHandler: ModelFallbackHandler? = null,
    private val profile: AiOptimizerProfile? = null
) {
    private val TAG = "DeepPolicyModel"
    
    /**
     * ONNX Runtime environment (singleton)
     */
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    
    /**
     * ONNX session (loaded model)
     */
    private var ortSession: OrtSession? = null
    
    /**
     * Active execution providers (for logging/debugging)
     */
    private var activeExecutionProviders: List<String> = emptyList()
    
    /**
     * Whether the model was successfully loaded
     */
    private var isLoaded: Boolean = false
    
    /**
     * Whether model verification passed
     */
    private var isVerified: Boolean = false
    
    /**
     * Model manifest (if verification is enabled)
     */
    private var modelManifest: ModelSignatureVerifier.ModelManifest? = null
    
    /**
     * Input tensor name (default, can be overridden)
     */
    private var inputName: String? = null
    
    /**
     * Output tensor name (default, can be overridden)
     */
    private var outputName: String? = null
    
    init {
        if (useVerification) {
            loadModelWithVerification()
        } else {
            loadModel()
        }
        // printStatus() // Commented out - can be enabled for debugging
    }
    
    /**
     * Load ONNX model from assets with error handling.
     */
    private fun loadModel() {
        try {
            Log.d(TAG, "Loading ONNX model from assets: $modelPath")
            AiLogHelper.d(TAG, "Loading ONNX model from assets: $modelPath")
            
            val modelBytes = context.assets.open(modelPath).use { inputStream ->
                inputStream.readBytes()
            }
            
            Log.d(TAG, "Model file size: ${modelBytes.size} bytes")
            AiLogHelper.d(TAG, "Model file size: ${modelBytes.size} bytes")
            
            if (modelBytes.isEmpty() || modelBytes.size < 100) {
                val errorMsg = if (modelBytes.isEmpty()) {
                    "Model file is empty"
                } else {
                    "Model file is too small (${modelBytes.size} bytes), expected valid ONNX model"
                }
                Log.w(TAG, errorMsg)
                AiLogHelper.w(TAG, errorMsg)
                isLoaded = false
                handleModelLoadFailure(errorMsg)
                return
            }
            
            Log.d(TAG, "Creating ONNX session...")
            AiLogHelper.d(TAG, "Creating ONNX session...")
            val sessionOptions = createSessionOptions()
            ortSession = ortEnv.createSession(modelBytes, sessionOptions)
            
            // Extract input/output names from model metadata
            val inputMetadata = ortSession?.inputNames?.firstOrNull()
            val outputMetadata = ortSession?.outputNames?.firstOrNull()
            
            inputName = inputMetadata
            outputName = outputMetadata
            
            isLoaded = true
            isVerified = false // No verification performed
            Log.i(TAG, "ONNX model loaded successfully")
            AiLogHelper.i(TAG, "ONNX model loaded successfully")
            Log.d(TAG, "Input name: $inputName, Output name: $outputName")
            Log.d(TAG, "Model input count: ${ortSession?.inputNames?.size ?: 0}")
            Log.d(TAG, "Model output count: ${ortSession?.outputNames?.size ?: 0}")
            
        } catch (e: IOException) {
            val errorMsg = "Failed to read model file from assets: $modelPath - ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, errorMsg)
            isLoaded = false
            handleModelLoadFailure("IOException: ${e.message}")
        } catch (e: Exception) {
            val errorMsg = "Failed to load ONNX model: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, errorMsg)
            isLoaded = false
            handleModelLoadFailure("Exception: ${e.message}")
        }
    }
    
    /**
     * Load ONNX model with signature verification.
     */
    private fun loadModelWithVerification() {
        try {
            Log.d(TAG, "Loading ONNX model with verification: $modelPath")
            
            // Step 1: Verify model signature
            val verifier = ModelSignatureVerifier.create(context)
            val verificationResult = verifier.verifyModel(modelPath)
            
            if (!verificationResult.isValid) {
                Log.w(TAG, "Model verification failed: ${verificationResult.error}")
                handleModelVerificationFailure(verificationResult)
                // Continue loading model even if verification failed
                Log.w(TAG, "Continuing with model load despite verification failure")
            } else {
                // Step 2: Load verified model
                modelManifest = verificationResult.manifest
                isVerified = true
                Log.i(TAG, "Model verification passed")
            }
            
            Log.d(TAG, "Loading model from assets: $modelPath")
            AiLogHelper.d(TAG, "Loading model from assets: $modelPath")
            
            val modelBytes = context.assets.open(modelPath).use { inputStream ->
                inputStream.readBytes()
            }
            
            Log.d(TAG, "Model file size: ${modelBytes.size} bytes")
            AiLogHelper.d(TAG, "Model file size: ${modelBytes.size} bytes")
            
            if (modelBytes.isEmpty() || modelBytes.size < 100) {
                val errorMsg = if (modelBytes.isEmpty()) {
                    "Model file is empty"
                } else {
                    "Model file is too small (${modelBytes.size} bytes), expected valid ONNX model"
                }
                Log.w(TAG, errorMsg)
                AiLogHelper.w(TAG, errorMsg)
                isLoaded = false
                handleModelLoadFailure(errorMsg)
                return
            }
            
            Log.d(TAG, "Creating ONNX session...")
            AiLogHelper.d(TAG, "Creating ONNX session...")
            val sessionOptions = createSessionOptions()
            
            try {
                ortSession = ortEnv.createSession(modelBytes, sessionOptions)
                
                // Extract input/output names from model metadata
                val inputMetadata = ortSession?.inputNames?.firstOrNull()
                val outputMetadata = ortSession?.outputNames?.firstOrNull()
                
                inputName = inputMetadata
                outputName = outputMetadata
                
                isLoaded = true
                Log.d(TAG, "ONNX session created successfully")
                AiLogHelper.d(TAG, "ONNX session created successfully")
                
                if (isVerified) {
                    Log.i(TAG, "ONNX model loaded and verified successfully")
                    AiLogHelper.i(TAG, "ONNX model loaded and verified successfully")
                    Log.i(TAG, "Model: ${modelManifest?.model?.name} v${modelManifest?.model?.version}")
                    AiLogHelper.i(TAG, "Model: ${modelManifest?.model?.name} v${modelManifest?.model?.version}")
                } else {
                    Log.i(TAG, "ONNX model loaded successfully (verification skipped)")
                    AiLogHelper.i(TAG, "ONNX model loaded successfully (verification skipped)")
                }
                Log.d(TAG, "Input name: $inputName, Output name: $outputName")
                Log.d(TAG, "Model input count: ${ortSession?.inputNames?.size ?: 0}")
                Log.d(TAG, "Model output count: ${ortSession?.outputNames?.size ?: 0}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create ONNX session: ${e.message}", e)
                AiLogHelper.e(TAG, "Failed to create ONNX session: ${e.message}")
                isLoaded = false
                handleModelLoadFailure("ONNX session creation failed: ${e.message}")
                return
            }
            
        } catch (e: IOException) {
            val errorMsg = "Failed to read model file from assets: $modelPath - ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, errorMsg)
            isLoaded = false
            isVerified = false
            handleModelLoadFailure("IOException: ${e.message}")
        } catch (e: Exception) {
            val errorMsg = "Failed to load ONNX model with verification: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, errorMsg)
            isLoaded = false
            isVerified = false
            handleModelLoadFailure("Exception: ${e.message}")
        }
    }
    
    /**
     * Handle model load failure - activate fallback if available
     */
    private fun handleModelLoadFailure(reason: String) {
        Log.w(TAG, "Model load failed: $reason")
        if (fallbackHandler != null) {
            fallbackHandler.logFallbackActivation(reason = "Model load failure: $reason")
        }
    }
    
    /**
     * Handle model verification failure - activate fallback if available
     */
    private fun handleModelVerificationFailure(verificationResult: ModelSignatureVerifier.VerificationResult) {
        Log.w(TAG, "Model verification failed, activating fallback")
        if (fallbackHandler != null) {
            val reason = verificationResult.error ?: "Verification failed"
            fallbackHandler.logFallbackActivation(reason = reason)
        }
    }
    
    /**
     * Create ONNX session options with execution providers based on profile.
     * 
     * Supports:
     * - NNAPI (NPU) for Android devices
     * - GPU execution providers (OpenGL/OpenCL) where available
     * - CPU fallback
     */
    private fun createSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        val activeEPs = mutableListOf<String>()
        
        // Apply profile-specific execution provider configuration
        if (profile != null) {
            Log.d(TAG, "Applying profile: ${profile.name}")
            AiLogHelper.d(TAG, "Applying profile: ${profile.name}")
            
            // Try to add execution providers in priority order
            for (epName in profile.executionProviders.priority) {
                try {
                    when {
                        epName.contains("NNAPI", ignoreCase = true) || epName.contains("NPU", ignoreCase = true) -> {
                            // Try to add NNAPI execution provider
                            try {
                                // Note: addNnapi() method may not be available in all ONNX Runtime versions
                                // We'll use reflection or check if method exists
                                opts.javaClass.getMethod("addNnapi").invoke(opts)
                                activeEPs.add("NNAPI")
                                Log.i(TAG, "EP: NNAPI enabled")
                                AiLogHelper.i(TAG, "EP: NNAPI enabled")
                            } catch (e: NoSuchMethodException) {
                                Log.w(TAG, "NNAPI unavailable: Method not found in ONNX Runtime version")
                                AiLogHelper.w(TAG, "NNAPI unavailable: Method not found in ONNX Runtime version")
                            } catch (e: Exception) {
                                Log.w(TAG, "NNAPI unavailable: ${e.message}")
                                AiLogHelper.w(TAG, "NNAPI unavailable: ${e.message}")
                            }
                        }
                        epName.contains("GPU", ignoreCase = true) || epName.contains("OpenGL", ignoreCase = true) -> {
                            // Try to add GPU execution providers
                            try {
                                // Try OpenGL EP
                                try {
                                    opts.javaClass.getMethod("addOrtOpenGL").invoke(opts)
                                    activeEPs.add("OpenGL")
                                    Log.i(TAG, "EP: OpenGL enabled")
                                    AiLogHelper.i(TAG, "EP: OpenGL enabled")
                                } catch (e: NoSuchMethodException) {
                                    // Try OpenCL EP
                                    try {
                                        opts.javaClass.getMethod("addOrtOpenCL").invoke(opts)
                                        activeEPs.add("OpenCL")
                                        Log.i(TAG, "EP: OpenCL enabled")
                                        AiLogHelper.i(TAG, "EP: OpenCL enabled")
                                    } catch (e2: NoSuchMethodException) {
                                        Log.w(TAG, "GPU EPs unavailable: Methods not found in ONNX Runtime version")
                                        AiLogHelper.w(TAG, "GPU EPs unavailable: Methods not found in ONNX Runtime version")
                                    } catch (e2: Exception) {
                                        Log.w(TAG, "GPU EPs unavailable: ${e2.message}")
                                        AiLogHelper.w(TAG, "GPU EPs unavailable: ${e2.message}")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "GPU EPs unavailable: ${e.message}")
                                    AiLogHelper.w(TAG, "GPU EPs unavailable: ${e.message}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "GPU execution provider setup failed: ${e.message}")
                                AiLogHelper.w(TAG, "GPU execution provider setup failed: ${e.message}")
                            }
                        }
                        epName.contains("CPU", ignoreCase = true) -> {
                            // CPU is always available as fallback
                            if (activeEPs.isEmpty()) {
                                activeEPs.add("CPU")
                                Log.i(TAG, "EP: CPU (fallback)")
                                AiLogHelper.i(TAG, "EP: CPU (fallback)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to configure EP: $epName - ${e.message}")
                    AiLogHelper.w(TAG, "Failed to configure EP: $epName - ${e.message}")
                }
            }
        } else {
            // No profile specified, use CPU-only
            activeEPs.add("CPU")
            Log.d(TAG, "No profile specified, using CPU-only")
            AiLogHelper.d(TAG, "No profile specified, using CPU-only")
        }
        
        // Set optimization level
        try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set optimization level: ${e.message}")
        }
        
        // Set execution mode to parallel if available
        try {
            opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set execution mode: ${e.message}")
        }
        
        // Set thread counts for CPU execution
        try {
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(2)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set thread counts: ${e.message}")
        }
        
        // Enable profiling if available
        try {
            // enableProfiling requires a profile file path parameter
            // Skip profiling for now to avoid build errors
            // opts.enableProfiling("profile_path")
        } catch (e: Exception) {
            Log.d(TAG, "Profiling not available: ${e.message}")
        }
        
        activeExecutionProviders = activeEPs
        
        if (activeEPs.isNotEmpty()) {
            Log.i(TAG, "ONNX EP active: ${activeEPs.joinToString(" + ")}")
            AiLogHelper.i(TAG, "ONNX EP active: ${activeEPs.joinToString(" + ")}")
        } else {
            Log.w(TAG, "No execution providers configured, using default")
            AiLogHelper.w(TAG, "No execution providers configured, using default")
        }
        
        return opts
    }
    
    /**
     * Run inference on normalized context and return predicted RealityArm.
     * 
     * @param context Normalized context vector (8D or model input size)
     * @param availableArms List of available RealityArm candidates
     * @param banditArm Optional bandit-selected arm for fallback
     * @return Selected RealityArm based on model prediction, or fallback arm if inference fails
     */
    fun infer(
        context: DoubleArray,
        availableArms: List<RealityArm>,
        banditArm: RealityArm? = null
    ): RealityArm? {
        // If model not loaded, use fallback
        if (!isLoaded || ortSession == null) {
            Log.w(TAG, "Model not loaded, using fallback")
            return fallbackHandler?.selectFallbackArm(availableArms, banditArm)
        }
        
        if (availableArms.isEmpty()) {
            Log.w(TAG, "No available arms for inference")
            return null
        }
        
        return try {
            // Normalize context
            val normalizedContext = scaler.normalize(context)
            
            // Create input tensor
            // Shape: [1, context_size] for batch inference
            val inputShape = longArrayOf(1, normalizedContext.size.toLong())
            // Create 2D array for batch inference: [batch=1, features=context_size]
            val inputData = Array(1) { normalizedContext }
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputData)
            
            // Run inference
            val inputs = mapOf((inputName ?: ortSession!!.inputNames.first()) to inputTensor)
            val outputs = ortSession!!.run(inputs)
            
            // Extract output (assuming single output tensor)
            val outputTensor = outputs.get(0)
            val outputValue = outputTensor.value
            
            // Close resources
            inputTensor.close()
            outputs.close()
            
            // Parse output to select arm
            val selectedArm = parseOutput(outputValue, availableArms)
            
            Log.d(TAG, "Inference completed, selected arm: ${selectedArm?.armId}")
            selectedArm ?: fallbackHandler?.selectFallbackArm(availableArms, banditArm)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during ONNX inference, using fallback", e)
            fallbackHandler?.selectFallbackArm(availableArms, banditArm)
        }
    }
    
    /**
     * Run inference and return raw output (for TProxy optimization).
     * 
     * @param context Normalized context vector
     * @return Raw model output as DoubleArray
     */
    fun inferRaw(context: DoubleArray): DoubleArray {
        if (!isLoaded || ortSession == null) {
            Log.w(TAG, "Model not loaded, returning zero array")
            return DoubleArray(5) { 0.0 }
        }
        
        return try {
            // Normalize context
            val normalizedContext = scaler.normalize(context)
            
            // Create input tensor
            val inputShape = longArrayOf(1, normalizedContext.size.toLong())
            val inputData = Array(1) { normalizedContext }
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputData)
            
            // Run inference
            val inputs = mapOf((inputName ?: ortSession!!.inputNames.first()) to inputTensor)
            val outputs = ortSession!!.run(inputs)
            
            // Extract output
            val outputTensor = outputs.get(0)
            val outputValue = outputTensor.value
            
            // Close resources
            inputTensor.close()
            outputs.close()
            
            // Convert output to DoubleArray
            when (outputValue) {
                is Array<*> -> {
                    outputValue.mapNotNull {
                        when (it) {
                            is Float -> it.toDouble()
                            is Double -> it
                            is Number -> it.toDouble()
                            else -> null
                        }
                    }.toDoubleArray()
                }
                is FloatArray -> {
                    outputValue.map { it.toDouble() }.toDoubleArray()
                }
                is DoubleArray -> {
                    outputValue
                }
                else -> {
                    // Try to handle 2D arrays using reflection or type checking
                    // Kotlin doesn't allow is-instance checks for erased generic types
                    // So we try to cast and handle exceptions
                    try {
                        val arrayValue = outputValue as? Array<*>
                        if (arrayValue != null && arrayValue.isNotEmpty()) {
                            val firstElement = arrayValue[0]
                            when (firstElement) {
                                is FloatArray -> {
                                    firstElement.map { it.toDouble() }.toDoubleArray()
                                }
                                is DoubleArray -> {
                                    firstElement
                                }
                                is Array<*> -> {
                                    // Nested array - try to flatten
                                    val flattened = arrayValue.flatMap { row ->
                                        when (row) {
                                            is FloatArray -> row.map { it.toDouble() }
                                            is DoubleArray -> row.toList()
                                            is Array<*> -> row.mapNotNull { 
                                                when (it) {
                                                    is Float -> it.toDouble()
                                                    is Double -> it
                                                    is Number -> it.toDouble()
                                                    else -> null
                                                }
                                            }
                                            else -> emptyList<Double>()
                                        }
                                    }
                                    if (flattened.isNotEmpty()) {
                                        flattened.take(5).toDoubleArray()
                                    } else {
                                        DoubleArray(5) { 0.0 }
                                    }
                                }
                                else -> {
                                    DoubleArray(5) { 0.0 }
                                }
                            }
                        } else {
                            DoubleArray(5) { 0.0 }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling array output: ${e.message}")
                        DoubleArray(5) { 0.0 }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during raw inference", e)
            DoubleArray(5) { 0.0 }
        }
    }
    
    /**
     * Parse ONNX output to select RealityArm.
     * 
     * Output format assumptions:
     * - If output is probabilities array: select arm with highest probability
     * - If output is single index: use as arm index
     * - If output is scores: select arm with highest score
     */
    private fun parseOutput(outputValue: Any?, availableArms: List<RealityArm>): RealityArm? {
        if (outputValue == null || availableArms.isEmpty()) {
            return null
        }
        
        return try {
            when (outputValue) {
                is Array<*> -> {
                    // Array of probabilities/scores
                    val scores = outputValue.mapNotNull { 
                        when (it) {
                            is Float -> it.toDouble()
                            is Double -> it
                            is Number -> it.toDouble()
                            else -> null
                        }
                    }
                    
                    if (scores.isEmpty()) return null
                    
                    val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
                    availableArms.getOrNull(maxIndex.coerceIn(0, availableArms.size - 1))
                }
                is FloatArray -> {
                    // Float array of scores
                    val maxIndex = outputValue.indices.maxByOrNull { outputValue[it] } ?: 0
                    availableArms.getOrNull(maxIndex.coerceIn(0, availableArms.size - 1))
                }
                is DoubleArray -> {
                    // Double array of scores
                    val maxIndex = outputValue.indices.maxByOrNull { outputValue[it] } ?: 0
                    availableArms.getOrNull(maxIndex.coerceIn(0, availableArms.size - 1))
                }
                is Number -> {
                    // Single index value
                    val index = outputValue.toInt().coerceIn(0, availableArms.size - 1)
                    availableArms.getOrNull(index)
                }
                else -> {
                    Log.w(TAG, "Unexpected output type: ${outputValue::class.simpleName}")
                    // Fallback: return first arm
                    availableArms.firstOrNull()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ONNX output", e)
            // Fallback: return first arm
            availableArms.firstOrNull()
        }
    }
    
    /**
     * Print ONNX session status and model information.
     * Includes auto-summary with status, TODOs, and next stage.
     */
    private fun printStatus() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "=== DeepPolicyModel Status Summary ===")
        Log.i(TAG, "========================================")
        
        // ONNX Session Status
        Log.i(TAG, "ONNX Session Status:")
        Log.i(TAG, "  - Model loaded: $isLoaded")
        Log.i(TAG, "  - Model verified: $isVerified")
        Log.i(TAG, "  - Model path: $modelPath")
        
        if (modelManifest != null) {
            Log.i(TAG, "  - Model name: ${modelManifest!!.model.name}")
            Log.i(TAG, "  - Model version: ${modelManifest!!.model.version}")
            Log.i(TAG, "  - Model description: ${modelManifest!!.model.description}")
        }
        
        if (isLoaded && ortSession != null) {
            try {
                // Try to get ONNX Runtime version (may not be available in all versions)
                try {
                    // ONNX Runtime version info is not directly accessible in Android version
                    Log.i(TAG, "  - ONNX Runtime version: (available)")
                } catch (e: Exception) {
                    Log.d(TAG, "  - ONNX Runtime version: (not available)")
                }
                Log.i(TAG, "  - Input names: ${ortSession!!.inputNames.joinToString()}")
                Log.i(TAG, "  - Output names: ${ortSession!!.outputNames.joinToString()}")
                if (activeExecutionProviders.isNotEmpty()) {
                    Log.i(TAG, "  - Execution Providers: ${activeExecutionProviders.joinToString(" + ")}")
                }
                if (profile != null) {
                    Log.i(TAG, "  - Profile: ${profile.name}")
                }
                Log.i(TAG, "  - Status: ✓ READY FOR INFERENCE")
            } catch (e: Exception) {
                Log.w(TAG, "  - Status: ⚠ ERROR READING METADATA: ${e.message}")
            }
        } else {
            Log.w(TAG, "  - Status: ⚠ MODEL NOT LOADED")
            if (fallbackHandler != null) {
                Log.i(TAG, "  - Fallback handler: ✓ AVAILABLE")
                Log.i(TAG, "  - Fallback policy: ${fallbackHandler.getFallbackPolicy()}")
            } else {
                Log.w(TAG, "  - Fallback handler: ⚠ NOT AVAILABLE")
            }
        }
        
        // Unimplemented TODOs
        Log.i(TAG, "")
        Log.i(TAG, "Unimplemented TODOs:")
        Log.i(TAG, "  [ ] Train actual ONNX model with reinforcement learning")
        Log.i(TAG, "  [ ] Implement model versioning and update mechanism")
        Log.i(TAG, "  [ ] Add model performance metrics and monitoring")
        Log.i(TAG, "  [ ] Implement model A/B testing framework")
        Log.i(TAG, "  [ ] Add model explainability/interpretability")
        Log.i(TAG, "  [ ] Implement online learning/adaptation")
        Log.i(TAG, "  [ ] Add model compression/quantization for mobile")
        Log.i(TAG, "  [ ] Implement model caching and lazy loading")
        
        // Next Stage
        Log.i(TAG, "")
        Log.i(TAG, "========================================")
        Log.i(TAG, "NEXT-STAGE: Reinforcement Trainer")
        Log.i(TAG, "========================================")
        Log.i(TAG, "")
    }
    
    /**
     * Check if model is loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean {
        return isLoaded && ortSession != null
    }
    
    /**
     * Check if model is verified (if verification was enabled).
     */
    fun isModelVerified(): Boolean {
        return isVerified
    }
    
    /**
     * Get model manifest (if verification was enabled and successful).
     */
    fun getModelManifest(): ModelSignatureVerifier.ModelManifest? {
        return modelManifest
    }
    
    /**
     * Check if fallback handler is available.
     */
    fun hasFallbackHandler(): Boolean {
        return fallbackHandler != null
    }
    
    /**
     * Get active execution providers.
     */
    fun getActiveExecutionProviders(): List<String> {
        return activeExecutionProviders.toList()
    }
    
    /**
     * Get current profile.
     */
    fun getProfile(): AiOptimizerProfile? {
        return profile
    }
    
    /**
     * Release ONNX session resources.
     */
    fun close() {
        try {
            ortSession?.close()
            ortSession = null
            isLoaded = false
            Log.d(TAG, "ONNX session closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX session", e)
        }
    }
}

