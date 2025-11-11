package com.hyperxray.an.telemetry

/**
 * AI Optimizer Performance Profile Configuration
 * 
 * Defines execution provider priorities, optimization settings, and performance targets
 * for different device tiers and use cases.
 */
data class AiOptimizerProfile(
    val name: String,
    val targetDevices: List<String>,
    val role: String,
    val goal: String,
    val modelConfiguration: ModelConfiguration,
    val executionProviders: ExecutionProviderConfig,
    val runtimeBehavior: RuntimeBehavior,
    val powerThermalTargets: PowerThermalTargets?,
    val throughputOptimization: ThroughputOptimization?,
    val safeguardFallback: SafeguardFallback?,
    val expectedPerformance: ExpectedPerformance?,
    val validationRules: List<String>?,
    val notes: String?
) {
    data class ModelConfiguration(
        val modelFile: String,
        val precision: String,
        val opset: Int,
        val batchSize: Int,
        val expectedInferenceLatencyMs: String
    )
    
    data class ExecutionProviderConfig(
        val priority: List<String>,
        val kotlinSetup: String
    )
    
    data class RuntimeBehavior(
        val assignment: List<String>,
        val latencyBreakdownMs: List<LatencyBreakdown>
    ) {
        data class LatencyBreakdown(
            val stage: String,
            val nnapi: String?,
            val gpu: String?,
            val cpu: String?
        )
    }
    
    data class PowerThermalTargets(
        val gpuFreqLimitPercent: Int,
        val thermalThrottleCelsius: Int,
        val expectedPowerOverBaselinePercent: Int,
        val thermalRise15minCelsius: String
    )
    
    data class ThroughputOptimization(
        val happyEyeballsDelayMsRange: List<Int>,
        val bufferClassKb: List<Int>,
        val dscp: List<String>,
        val adaptiveSni: String?,
        val rewardScaling: RewardScaling?
    ) {
        data class RewardScaling(
            val throughputWeightMultiplier: Double,
            val rttPenaltyMultiplier: Double
        )
    }
    
    data class SafeguardFallback(
        val fallbackSequence: List<String>,
        val rollbackDelayCycles: Int
    )
    
    data class ExpectedPerformance(
        val midTierReference: PerformanceReference?,
        val highTierReference: PerformanceReference?
    ) {
        data class PerformanceReference(
            val deviceClass: String,
            val inferenceLatencyMs: String,
            val throughputGainPercent: Int,
            val rttImprovementPercent: Int,
            val lossReductionPercent: Int
        )
    }
    
    companion object {
        /**
         * Ultra-Performance AI Optimizer (GPU/NPU Hybrid Profile)
         * For high-tier devices with NPU and GPU acceleration support
         */
        fun ultraPerformance(): AiOptimizerProfile {
            return AiOptimizerProfile(
                name = "Ultra-Performance AI Optimizer (GPU/NPU Hybrid Profile)",
                targetDevices = listOf(
                    "Snapdragon 778G",
                    "Snapdragon 8 Gen 1 / 8+ Gen 1 / 8 Gen 2 / 8s Gen 3",
                    "Dimensity 9200 / 9200+ / 9300",
                    "Google Tensor G2 / G3",
                    "Apple A16/A17 (conceptual; use CoreML on iOS)"
                ),
                role = "Systems Performance Architect",
                goal = "Enable hybrid GPU+NPU accelerated inference for high-tier phones to maximize throughput and minimize latency in HyperXray-AI Optimizer.",
                modelConfiguration = ModelConfiguration(
                    modelFile = "assets/models/hyperxray_policy.onnx", // Use existing model file
                    precision = "FP32 (will use FP16/INT8 if available)",
                    opset = 18,
                    batchSize = 1,
                    expectedInferenceLatencyMs = "1.5–2.5"
                ),
                executionProviders = ExecutionProviderConfig(
                    priority = listOf("NNAPI (NPU)", "GPU (OpenGL/OpenCL/Metal where applicable)", "CPU (fallback)"),
                    kotlinSetup = ""
                ),
                runtimeBehavior = RuntimeBehavior(
                    assignment = listOf(
                        "NPU/NNAPI handles transformer blocks (attention, layernorm) where supported.",
                        "GPU handles dense matmul/softmax if NNAPI offloads differ.",
                        "CPU runs bandit (LinUCB) + RL updates + orchestration.",
                        "Reward recalculated asynchronously to keep UI thread free."
                    ),
                    latencyBreakdownMs = listOf(
                        RuntimeBehavior.LatencyBreakdown("Input preprocessing", null, "0.1", null),
                        RuntimeBehavior.LatencyBreakdown("Transformer (2 blocks)", "0.7", null, null),
                        RuntimeBehavior.LatencyBreakdown("Dense + softmax", null, "0.9", null),
                        RuntimeBehavior.LatencyBreakdown("Bandit update", null, null, "0.4"),
                        RuntimeBehavior.LatencyBreakdown("Total (typical)", "1.6", "2.0", "3.2")
                    )
                ),
                powerThermalTargets = PowerThermalTargets(
                    gpuFreqLimitPercent = 80,
                    thermalThrottleCelsius = 47,
                    expectedPowerOverBaselinePercent = 6,
                    thermalRise15minCelsius = "4–6"
                ),
                throughputOptimization = ThroughputOptimization(
                    happyEyeballsDelayMsRange = listOf(20, 100),
                    bufferClassKb = listOf(512, 768, 1024),
                    dscp = listOf("AF41", "EF (adaptive)"),
                    adaptiveSni = "Latency-weighted rotation among approved aliases",
                    rewardScaling = ThroughputOptimization.RewardScaling(
                        throughputWeightMultiplier = 1.5,
                        rttPenaltyMultiplier = 0.3
                    )
                ),
                safeguardFallback = SafeguardFallback(
                    fallbackSequence = listOf(
                        "If any EP init fails → CPU-only automatically.",
                        "If reward < baseline × 0.6 for 3 consecutive cycles → disable GPU/NPU and revert to Balanced Profile."
                    ),
                    rollbackDelayCycles = 2
                ),
                expectedPerformance = ExpectedPerformance(
                    midTierReference = ExpectedPerformance.PerformanceReference(
                        deviceClass = "Snapdragon 732G / similar",
                        inferenceLatencyMs = "3–4",
                        throughputGainPercent = 35,
                        rttImprovementPercent = -25,
                        lossReductionPercent = -50
                    ),
                    highTierReference = ExpectedPerformance.PerformanceReference(
                        deviceClass = "Snapdragon 8 Gen 1+ / Dimensity 9200+ / Tensor G3",
                        inferenceLatencyMs = "1.5–2.0",
                        throughputGainPercent = 55,
                        rttImprovementPercent = -40,
                        lossReductionPercent = -65
                    )
                ),
                validationRules = listOf(
                    "ONNX session must initialize with NNAPI or GPU EP; otherwise log CPU fallback only.",
                    "Measured inference latency target ≤ 2.5 ms on high-tier devices.",
                    "Throughput improvement ≥ 50% vs CPU-only baseline in sustained LTE/5G tests.",
                    "Thermals maintained under 47 °C; otherwise automatically throttle to CPU-only."
                ),
                notes = "Ensure onnxruntime-mobile dependency ≥ 1.23.2. Mixed-precision model should be exported with FP16 where supported and INT8 for attention paths. Validate operator coverage for NNAPI/OpenGL on target devices before rollout."
            )
        }
        
        /**
         * Balanced Profile (CPU-only fallback)
         * For devices without NPU/GPU acceleration or when hybrid acceleration fails
         */
        fun balanced(): AiOptimizerProfile {
            return AiOptimizerProfile(
                name = "Balanced AI Optimizer (CPU Profile)",
                targetDevices = listOf("All devices (fallback)"),
                role = "Systems Performance Architect",
                goal = "CPU-only inference with optimized threading for mid-tier devices.",
                modelConfiguration = ModelConfiguration(
                    modelFile = "assets/models/hyperxray_policy.onnx",
                    precision = "FP32",
                    opset = 18,
                    batchSize = 1,
                    expectedInferenceLatencyMs = "3–5"
                ),
                executionProviders = ExecutionProviderConfig(
                    priority = listOf("CPU"),
                    kotlinSetup = ""
                ),
                runtimeBehavior = RuntimeBehavior(
                    assignment = listOf("CPU handles all inference, bandit updates, and orchestration."),
                    latencyBreakdownMs = listOf(
                        RuntimeBehavior.LatencyBreakdown("Total (CPU-only)", null, null, "3–5")
                    )
                ),
                powerThermalTargets = null,
                throughputOptimization = null,
                safeguardFallback = null,
                expectedPerformance = null,
                validationRules = null,
                notes = "Default fallback profile when GPU/NPU acceleration is unavailable."
            )
        }
    }
}



