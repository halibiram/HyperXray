package com.hyperxray.an

import android.app.Application
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.optimizer.OrtHolder
import com.hyperxray.an.optimizer.RealityWorker
import com.hyperxray.an.telemetry.*
import com.hyperxray.an.workers.TlsRuntimeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Temporary app-level initializer.
 * 
 * TODO: This class should be moved to feature-policy-ai or core-telemetry
 * when telemetry classes are migrated to appropriate modules.
 * 
 * This is a temporary solution to maintain functionality while migrating
 * to the final architecture where app module contains only:
 * - Navigation host
 * - DI initialization  
 * - Global theme
 * - Activity lifecycle
 */
class AppInitializer(
    private val application: Application
) {
    private val TAG = "AppInitializer"
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // AI Optimizer components (temporary - will be moved to feature modules)
    private var deepPolicyModel: DeepPolicyModel? = null
    private var modelVerifier: ModelSignatureVerifier? = null
    private var fallbackHandler: ModelFallbackHandler? = null
    private var profileManager: AiOptimizerProfileManager? = null
    private var optimizerOrchestrator: OptimizerOrchestrator? = null
    private var optimizerReady: Boolean = false

    /**
     * Initialize all app components.
     * Called from HyperXrayApplication.onCreate().
     */
    fun initialize() {
        // Initialize AI Optimizer silently in background
        applicationScope.launch {
            initializeOptimizer()
        }

        // Initialize TLS SNI Optimizer v5
        applicationScope.launch {
            initializeTlsSniOptimizer()
        }

        // Initialize Auto-Learning TLS SNI Optimizer v9
        applicationScope.launch {
            initializeAutoLearningOptimizer()
        }
    }

    /**
     * Initialize AI Optimizer components.
     * TODO: Move to feature-policy-ai when telemetry classes are migrated.
     */
    private suspend fun initializeOptimizer() {
        try {
            Log.d(TAG, "=== HyperXray AI Optimizer Initialization ===")
            AiLogHelper.i(TAG, "=== HyperXray AI Optimizer Initialization ===")
            
            // Step 0: Initialize profile manager and detect device capabilities
            Log.d(TAG, "[Step 0/5] Initializing profile manager...")
            AiLogHelper.d(TAG, "[Step 0/5] Initializing profile manager...")
            profileManager = AiOptimizerProfileManager.create(application)
            val selectedProfile = profileManager?.selectOptimalProfile()
            Log.i(TAG, "Profile selected: ${selectedProfile?.name}")
            AiLogHelper.i(TAG, "Profile selected: ${selectedProfile?.name}")
            
            // Step 1: Initialize signature verifier
            Log.d(TAG, "[Step 1/5] Initializing model signature verifier...")
            AiLogHelper.d(TAG, "[Step 1/5] Initializing model signature verifier...")
            modelVerifier = ModelSignatureVerifier.create(application)
            
            // Step 2: Verify model integrity
            Log.d(TAG, "[Step 2/5] Verifying model integrity...")
            AiLogHelper.d(TAG, "[Step 2/5] Verifying model integrity...")
            val modelPath = "models/hyperxray_policy.onnx"
            val verificationResult = modelVerifier?.verifyModel(modelPath)
            
            if (verificationResult == null || !verificationResult.isValid) {
                Log.w(TAG, "Model verification failed, will use fallback handler")
                AiLogHelper.w(TAG, "Model verification failed, will use fallback handler")
            } else {
                Log.i(TAG, "Model verification passed: ${verificationResult.manifest?.model?.name} v${verificationResult.manifest?.model?.version}")
                AiLogHelper.i(TAG, "Model verification passed: ${verificationResult.manifest?.model?.name} v${verificationResult.manifest?.model?.version}")
            }
            
            // Step 3: Initialize fallback handler
            Log.d(TAG, "[Step 3/5] Initializing fallback handler...")
            AiLogHelper.d(TAG, "[Step 3/5] Initializing fallback handler...")
            fallbackHandler = ModelFallbackHandler.create(application)
            val baselineConfig = fallbackHandler?.loadBaselineConfig()
            if (baselineConfig != null) {
                fallbackHandler?.setFallbackPolicy(baselineConfig.policy)
                Log.d(TAG, "Baseline config loaded: ${baselineConfig.description}")
                AiLogHelper.d(TAG, "Baseline config loaded: ${baselineConfig.description}")
            } else {
                Log.w(TAG, "Baseline config is null, using default policy")
                AiLogHelper.w(TAG, "Baseline config is null, using default policy")
            }
            
            // Step 4: Initialize deep policy model
            Log.d(TAG, "[Step 4/5] Initializing deep policy model...")
            AiLogHelper.d(TAG, "[Step 4/5] Initializing deep policy model...")
            
            val profileModelPath = selectedProfile?.modelConfiguration?.modelFile?.removePrefix("assets/")
                ?: modelPath
            
            val useVerification = verificationResult?.isValid == true
            deepPolicyModel = DeepPolicyModel(
                context = application,
                modelPath = profileModelPath,
                useVerification = useVerification,
                fallbackHandler = fallbackHandler,
                profile = selectedProfile
            )
            
            val modelActuallyLoaded = deepPolicyModel?.isModelLoaded() == true
            val modelActuallyVerified = deepPolicyModel?.isModelVerified() == true
            
            if (!modelActuallyLoaded && !modelActuallyVerified && verificationResult?.isValid != true) {
                fallbackHandler?.logFallbackActivation(
                    reason = if (verificationResult == null) "Model verification failed" 
                            else if (!modelActuallyLoaded) "Model failed to load" 
                            else "Model invalid"
                )
            } else if (modelActuallyLoaded && modelActuallyVerified) {
                Log.i(TAG, "Model successfully loaded and verified - normal mode active")
                AiLogHelper.i(TAG, "Model successfully loaded and verified - normal mode active")
            }
            
            // Step 5: Update BuildTracker
            Log.d(TAG, "[Step 5/5] Updating build tracker...")
            AiLogHelper.d(TAG, "[Step 5/5] Updating build tracker...")
            val activeEPs = deepPolicyModel?.getActiveExecutionProviders() ?: emptyList()
            if (activeEPs.isNotEmpty() && activeEPs.any { it.contains("NNAPI", ignoreCase = true) || it.contains("GPU", ignoreCase = true) }) {
                BuildTracker.update(
                    stage = 13,
                    status = "gpu-npu-hybrid-active",
                    summary = "Hybrid GPU/NPU Ultra Performance profile enabled",
                    nextStage = "runtime-benchmark",
                    todos = listOf(
                        "Run 30 min throughput + thermal stress test",
                        "Validate NNAPI ops coverage for current model",
                        "Record EP selection in telemetry"
                    )
                )
            }
            
            // Step 6: Initialize OptimizerOrchestrator
            Log.d(TAG, "[Step 6/6] Initializing OptimizerOrchestrator...")
            AiLogHelper.d(TAG, "[Step 6/6] Initializing OptimizerOrchestrator...")
            val model = deepPolicyModel
            if (model != null) {
                optimizerOrchestrator = OptimizerOrchestrator(
                    context = application,
                    deepModel = model
                )
                Log.i(TAG, "OptimizerOrchestrator initialized successfully")
                AiLogHelper.i(TAG, "OptimizerOrchestrator initialized successfully")
            } else {
                Log.w(TAG, "DeepPolicyModel not available, OptimizerOrchestrator will use fallback")
                AiLogHelper.w(TAG, "DeepPolicyModel not available, OptimizerOrchestrator will use fallback")
                optimizerOrchestrator = OptimizerOrchestrator(context = application)
            }
            
            val modelLoaded = deepPolicyModel?.isModelLoaded() == true
            optimizerReady = modelLoaded || (fallbackHandler != null)
            
            if (optimizerReady) {
                Log.i(TAG, "========================================")
                Log.i(TAG, "✅ HyperXray AI Optimizer ready")
                Log.i(TAG, "========================================")
                AiLogHelper.i(TAG, "✅ HyperXray AI Optimizer ready")
                
                // Print deployment summary
                ModelDeploymentSummary.runDeploymentSummary(application)
            } else {
                Log.e(TAG, "❌ HyperXray AI Optimizer initialization failed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AI Optimizer", e)
            optimizerReady = false
            
            try {
                fallbackHandler = ModelFallbackHandler.create(application)
                fallbackHandler?.logFallbackActivation(reason = "Initialization exception: ${e.message}")
                optimizerReady = true
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to initialize fallback handler", fallbackError)
            }
        }
    }

    /**
     * Initialize TLS SNI Optimizer v5 components.
     * TODO: Move to appropriate feature module.
     */
    private suspend fun initializeTlsSniOptimizer() {
        try {
            Log.d(TAG, "Initializing TLS SNI Optimizer v5")
            TlsRuntimeWorker.schedule(application)
            Log.i(TAG, "TLS SNI Optimizer v5 initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TLS SNI Optimizer v5: ${e.message}", e)
        }
    }

    /**
     * Initialize Auto-Learning TLS SNI Optimizer v10 components.
     * TODO: Move to appropriate feature module.
     */
    private suspend fun initializeAutoLearningOptimizer() {
        try {
            Log.d(TAG, "Initializing Auto-Learning TLS SNI Optimizer v10")
            OrtHolder.init(application)
            RealityWorker.schedule(application)
            Log.i(TAG, "Auto-Learning TLS SNI Optimizer v10 initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Auto-Learning Optimizer v10: ${e.message}", e)
        }
    }
}

