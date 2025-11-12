package com.hyperxray.an

import android.app.Application
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.telemetry.AiOptimizerProfileManager
import com.hyperxray.an.telemetry.BuildTracker
import com.hyperxray.an.telemetry.DeepPolicyModel
import com.hyperxray.an.telemetry.ModelDeploymentSummary
import com.hyperxray.an.telemetry.ModelFallbackHandler
import com.hyperxray.an.telemetry.ModelSignatureVerifier
import com.hyperxray.an.telemetry.OptimizerOrchestrator
import com.hyperxray.an.telemetry.RealityArm
import com.hyperxray.an.telemetry.RealityContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HyperXrayApplication: Application class for silent boot initialization of AI Optimizer.
 * 
 * Initializes:
 * - Model signature verification
 * - Model loading with fallback handler
 * - Optimizer readiness check
 * 
 * Runs silently on app startup (no UI blocking)
 */
class HyperXrayApplication : Application() {
    private val TAG = "HyperXrayApplication"
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // AI Optimizer components
    private var deepPolicyModel: DeepPolicyModel? = null
    private var modelVerifier: ModelSignatureVerifier? = null
    private var fallbackHandler: ModelFallbackHandler? = null
    private var profileManager: AiOptimizerProfileManager? = null
    private var optimizerOrchestrator: OptimizerOrchestrator? = null
    private var optimizerReady: Boolean = false
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize AiLogHelper
        val logFileManager = LogFileManager(this)
        AiLogHelper.initialize(this, logFileManager)
        
        Log.d(TAG, "HyperXrayApplication onCreate - initializing AI Optimizer")
        AiLogHelper.d(TAG, "HyperXrayApplication onCreate - initializing AI Optimizer")
        
        // Initialize AI Optimizer silently in background
        applicationScope.launch {
            initializeOptimizer()
        }
    }
    
    /**
     * Initialize AI Optimizer components
     */
    private fun initializeOptimizer() {
        try {
            Log.d(TAG, "=== HyperXray AI Optimizer Initialization ===")
            AiLogHelper.i(TAG, "=== HyperXray AI Optimizer Initialization ===")
            
            // Step 0: Initialize profile manager and detect device capabilities
            Log.d(TAG, "[Step 0/5] Initializing profile manager...")
            AiLogHelper.d(TAG, "[Step 0/5] Initializing profile manager...")
            profileManager = AiOptimizerProfileManager.create(this@HyperXrayApplication)
            val selectedProfile = profileManager?.selectOptimalProfile()
            Log.i(TAG, "Profile selected: ${selectedProfile?.name}")
            AiLogHelper.i(TAG, "Profile selected: ${selectedProfile?.name}")
            
            // Step 1: Initialize signature verifier
            Log.d(TAG, "[Step 1/5] Initializing model signature verifier...")
            AiLogHelper.d(TAG, "[Step 1/5] Initializing model signature verifier...")
            modelVerifier = ModelSignatureVerifier.create(this@HyperXrayApplication)
            
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
            fallbackHandler = ModelFallbackHandler.create(this@HyperXrayApplication)
            val baselineConfig = fallbackHandler?.loadBaselineConfig()
            if (baselineConfig != null) {
                fallbackHandler?.setFallbackPolicy(baselineConfig.policy)
                Log.d(TAG, "Baseline config loaded: ${baselineConfig.description}")
                AiLogHelper.d(TAG, "Baseline config loaded: ${baselineConfig.description}")
            } else {
                // Fallback handler always returns a config, but handle null case anyway
                Log.w(TAG, "Baseline config is null, using default policy")
                AiLogHelper.w(TAG, "Baseline config is null, using default policy")
            }
            
            // Step 4: Initialize deep policy model (with fallback support and profile)
            Log.d(TAG, "[Step 4/5] Initializing deep policy model...")
            AiLogHelper.d(TAG, "[Step 4/5] Initializing deep policy model...")
            
            // Get model path from profile if available, otherwise use default
            val profileModelPath = selectedProfile?.modelConfiguration?.modelFile?.removePrefix("assets/")
                ?: modelPath
            
            // Create model (it will handle its own loading and fallback)
            // If verification failed, try loading without verification
            val useVerification = verificationResult?.isValid == true
            deepPolicyModel = DeepPolicyModel(
                context = this@HyperXrayApplication,
                modelPath = profileModelPath,
                useVerification = useVerification,
                fallbackHandler = fallbackHandler,
                profile = selectedProfile
            )
            
            // Check if model actually loaded after initialization
            val modelActuallyLoaded = deepPolicyModel?.isModelLoaded() == true
            val modelActuallyVerified = deepPolicyModel?.isModelVerified() == true
            
            // Only activate fallback if model failed to load AND verification failed
            if (!modelActuallyLoaded && !modelActuallyVerified && verificationResult?.isValid != true) {
                fallbackHandler?.logFallbackActivation(
                    reason = if (verificationResult == null) "Model verification failed" 
                            else if (!modelActuallyLoaded) "Model failed to load" 
                            else "Model invalid"
                )
            } else if (modelActuallyLoaded && modelActuallyVerified) {
                Log.i(TAG, "Model successfully loaded and verified - normal mode active (not using fallback)")
                AiLogHelper.i(TAG, "Model successfully loaded and verified - normal mode active (not using fallback)")
            }
            
            // Step 5: Update BuildTracker with GPU/NPU hybrid status
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
            
            // Step 6: Initialize OptimizerOrchestrator (uses the initialized deepPolicyModel)
            Log.d(TAG, "[Step 6/6] Initializing OptimizerOrchestrator...")
            AiLogHelper.d(TAG, "[Step 6/6] Initializing OptimizerOrchestrator...")
            if (deepPolicyModel != null) {
                optimizerOrchestrator = OptimizerOrchestrator(
                    context = this@HyperXrayApplication,
                    deepModel = deepPolicyModel!!
                )
                Log.i(TAG, "OptimizerOrchestrator initialized successfully")
                AiLogHelper.i(TAG, "OptimizerOrchestrator initialized successfully")
            } else {
                Log.w(TAG, "DeepPolicyModel not available, OptimizerOrchestrator will use fallback")
                AiLogHelper.w(TAG, "DeepPolicyModel not available, OptimizerOrchestrator will use fallback")
                optimizerOrchestrator = OptimizerOrchestrator(context = this@HyperXrayApplication)
            }
            
            // Check if model loaded successfully
            val modelLoaded = deepPolicyModel?.isModelLoaded() == true
            optimizerReady = modelLoaded || (fallbackHandler != null)
            
            // Log completion
            if (optimizerReady) {
                Log.i(TAG, "========================================")
                Log.i(TAG, "✅ HyperXray AI Optimizer ready")
                Log.i(TAG, "========================================")
                Log.i(TAG, "Model loaded: $modelLoaded")
                Log.i(TAG, "Model verified: ${deepPolicyModel?.isModelVerified() == true}")
                
                // Only show fallback policy if model is NOT loaded (fallback mode active)
                if (!modelLoaded) {
                    Log.i(TAG, "Fallback available: ${fallbackHandler != null}")
                    val handler = fallbackHandler
                    if (handler != null) {
                        Log.i(TAG, "Fallback policy: ${handler.getFallbackPolicy()}")
                        AiLogHelper.i(TAG, "⚠️ Fallback mode active - Policy: ${handler.getFallbackPolicy()}")
                    }
                } else {
                    Log.i(TAG, "✅ Normal mode active - Using Bandit + Deep Model fusion")
                    AiLogHelper.i(TAG, "✅ Normal mode active - Using Bandit + Deep Model fusion")
                }
                val activeEPs = deepPolicyModel?.getActiveExecutionProviders()
                if (activeEPs != null && activeEPs.isNotEmpty()) {
                    Log.i(TAG, "Execution Providers: ${activeEPs.joinToString(" + ")}")
                }
                val profile = deepPolicyModel?.getProfile()
                if (profile != null) {
                    Log.i(TAG, "Profile: ${profile.name}")
                }
                Log.i(TAG, "Optimizer status: READY")
                Log.i(TAG, "========================================")
                
                AiLogHelper.i(TAG, "========================================")
                AiLogHelper.i(TAG, "✅ HyperXray AI Optimizer ready")
                AiLogHelper.i(TAG, "========================================")
                AiLogHelper.i(TAG, "Model loaded: $modelLoaded")
                AiLogHelper.i(TAG, "Model verified: ${deepPolicyModel?.isModelVerified() == true}")
                
                // Only show fallback policy if model is NOT loaded (fallback mode active)
                if (!modelLoaded) {
                    AiLogHelper.i(TAG, "Fallback available: ${fallbackHandler != null}")
                    val handlerForLog = fallbackHandler
                    if (handlerForLog != null) {
                        AiLogHelper.i(TAG, "⚠️ Fallback mode active - Policy: ${handlerForLog.getFallbackPolicy()}")
                    }
                } else {
                    AiLogHelper.i(TAG, "✅ Normal mode active - Using Bandit + Deep Model fusion")
                }
                if (activeEPs != null && activeEPs.isNotEmpty()) {
                    AiLogHelper.i(TAG, "Execution Providers: ${activeEPs.joinToString(" + ")}")
                }
                if (profile != null) {
                    AiLogHelper.i(TAG, "Profile: ${profile.name}")
                }
                AiLogHelper.i(TAG, "Optimizer status: READY")
                AiLogHelper.i(TAG, "========================================")
                
                // Console log as requested
                android.util.Log.println(android.util.Log.INFO, TAG, "HyperXray AI Optimizer ready")
                
                // Print deployment summary
                ModelDeploymentSummary.runDeploymentSummary(this@HyperXrayApplication)
                
                // Step 7: Start OptimizerOrchestrator cycle (test with sample arms)
                if (optimizerOrchestrator != null) {
                    Log.d(TAG, "[Step 7/7] Starting OptimizerOrchestrator cycle...")
                    AiLogHelper.d(TAG, "[Step 7/7] Starting OptimizerOrchestrator cycle...")
                    
                    // Start optimizer cycle in background after a short delay
                    applicationScope.launch {
                        delay(2000) // Wait 2 seconds for everything to settle
                        
                        // Create sample Reality arms for testing
                        val sampleArms = createSampleRealityArms()
                        
                        if (sampleArms.isNotEmpty()) {
                            Log.i(TAG, "Running OptimizerOrchestrator cycle with ${sampleArms.size} sample arms")
                            AiLogHelper.i(TAG, "Running OptimizerOrchestrator cycle with ${sampleArms.size} sample arms")
                            
                            try {
                                val result = optimizerOrchestrator!!.runOptimizerCycle(
                                    coreStats = null,
                                    availableArms = sampleArms
                                )
                                
                                if (result.success) {
                                    Log.i(TAG, "OptimizerOrchestrator cycle completed successfully")
                                    Log.i(TAG, "  Selected arm: ${result.selectedArm?.armId}")
                                    Log.i(TAG, "  Safeguard passed: ${result.safeguardPassed}")
                                    Log.i(TAG, "  Reward: ${result.reward}")
                                    AiLogHelper.i(TAG, "OptimizerOrchestrator cycle completed successfully")
                                    AiLogHelper.i(TAG, "  Selected arm: ${result.selectedArm?.armId}")
                                } else {
                                    Log.w(TAG, "OptimizerOrchestrator cycle completed with errors: ${result.error}")
                                    AiLogHelper.w(TAG, "OptimizerOrchestrator cycle completed with errors: ${result.error}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "OptimizerOrchestrator cycle failed", e)
                                AiLogHelper.e(TAG, "OptimizerOrchestrator cycle failed: ${e.message}")
                            }
                        } else {
                            Log.w(TAG, "No sample arms available for OptimizerOrchestrator cycle")
                            AiLogHelper.w(TAG, "No sample arms available for OptimizerOrchestrator cycle")
                        }
                    }
                }
            } else {
                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ HyperXray AI Optimizer initialization failed")
                Log.e(TAG, "========================================")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AI Optimizer", e)
            optimizerReady = false
            
            // Ensure fallback is available even if initialization fails
            try {
                fallbackHandler = ModelFallbackHandler.create(this@HyperXrayApplication)
                fallbackHandler?.logFallbackActivation(reason = "Initialization exception: ${e.message}")
                optimizerReady = true // Fallback is available
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to initialize fallback handler", fallbackError)
            }
        }
    }
    
    /**
     * Get deep policy model instance
     */
    fun getDeepPolicyModel(): DeepPolicyModel? {
        return deepPolicyModel
    }
    
    /**
     * Get fallback handler instance
     */
    fun getFallbackHandler(): ModelFallbackHandler? {
        return fallbackHandler
    }
    
    /**
     * Get model verifier instance
     */
    fun getModelVerifier(): ModelSignatureVerifier? {
        return modelVerifier
    }
    
    /**
     * Get profile manager instance
     */
    fun getProfileManager(): AiOptimizerProfileManager? {
        return profileManager
    }
    
    /**
     * Get OptimizerOrchestrator instance
     */
    fun getOptimizerOrchestrator(): OptimizerOrchestrator? {
        return optimizerOrchestrator
    }
    
    /**
     * Check if optimizer is ready
     */
    fun isOptimizerReady(): Boolean {
        return optimizerReady
    }
    
    /**
     * Get application instance
     */
    companion object {
        @Volatile
        private var instance: HyperXrayApplication? = null
        
        fun getInstance(): HyperXrayApplication? {
            return instance
        }
    }
    
    /**
     * Create sample Reality arms for testing OptimizerOrchestrator
     */
    private fun createSampleRealityArms(): List<RealityArm> {
        return try {
            listOf(
                RealityArm.create(
                    RealityContext(
                        address = "server1.example.com",
                        port = 443,
                        serverName = "cloudflare.com",
                        shortId = "abc123",
                        publicKey = "dGVzdF9wdWJsaWNfa2V5XzEyMzQ1Njc4OTA=",
                        destination = "www.google.com:443",
                        configId = "config-1"
                    )
                ),
                RealityArm.create(
                    RealityContext(
                        address = "server2.example.com",
                        port = 8443,
                        serverName = "microsoft.com",
                        shortId = "def456",
                        publicKey = "YW5vdGhlcl9wdWJsaWNfa2V5XzE5ODc2NTQzMjE=",
                        destination = "www.microsoft.com:443",
                        configId = "config-2"
                    )
                ),
                RealityArm.create(
                    RealityContext(
                        address = "server3.example.com",
                        port = 443,
                        serverName = "github.com",
                        shortId = "ghi789",
                        publicKey = "dGhpcmRfcHVibGljX2tleV8xMTIyMzM0NDU1",
                        destination = "www.github.com:443",
                        configId = "config-3"
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sample Reality arms", e)
            emptyList()
        }
    }
    
    init {
        instance = this
    }
}
