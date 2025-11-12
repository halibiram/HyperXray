package com.hyperxray.an.optimizer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln

/**
 * Inference: Wraps ONNX inference with temperature and bias correction.
 * 
 * Provides the main API for SNI optimization:
 * - Encodes SNI to features
 * - Runs ONNX model
 * - Applies temperature scaling
 * - Applies bias correction
 * - Returns service class and routing decision
 */

data class TrafficMeta(
    val latencyMs: Double,
    val throughputKbps: Double
)

data class RouteDecision(
    val svcClass: Int,
    val alpn: String,
    val routeDecision: Int,
    val confidence: Float = 0.5f // Confidence score (0.0 to 1.0)
)

object Inference {
    private const val TAG = "Inference"
    
    /**
     * Run inference on SNI with traffic metadata.
     * 
     * @param context Android context
     * @param sni Server Name Indication
     * @param trafficMeta Traffic metadata (latency, throughput)
     * @return RouteDecision with service class, ALPN, and routing decision
     */
    suspend fun run(
        context: Context,
        sni: String,
        trafficMeta: TrafficMeta
    ): RouteDecision = withContext(Dispatchers.Default) {
        try {
            // Ensure OrtHolder is initialized
            if (!OrtHolder.isReady()) {
                OrtHolder.init(context)
            }
            
            if (!OrtHolder.isReady()) {
                Log.w(TAG, "OrtHolder not ready, returning default decision")
                return@withContext RouteDecision(
                    svcClass = 7, // Other
                    alpn = "h2",
                    routeDecision = 0, // Proxy
                    confidence = 0.0f
                )
            }
            
            // Encode SNI to features (include latency/throughput and temporal features in encoding)
            val features = SniFeatureEncoder.encode(
                sni = sni,
                alpn = "h2",
                latencyMs = trafficMeta.latencyMs,
                throughputKbps = trafficMeta.throughputKbps,
                timestamp = System.currentTimeMillis()
            )
            
            // Run ONNX inference
            val (serviceTypeArray, routingDecisionArray) = OrtHolder.runInference(features)
                ?: return@withContext RouteDecision(7, "h2", 0, 0.0f)
            
            // Log raw ONNX output for debugging
            Log.d(TAG, "ONNX routingDecisionArray: [${routingDecisionArray.joinToString(", ")}]")
            
            // Get learner state
            val learnerState = LearnerState(context)
            val temperature = learnerState.getTemperature()
            val svcBiases = learnerState.getSvcBiases()
            val routeBiases = learnerState.getRouteBiases()
            
            // Log route biases
            Log.d(TAG, "Route biases: [${routeBiases.joinToString(", ")}]")
            
            // Apply temperature scaling (softmax with temperature)
            val scaledServiceType = applyTemperature(serviceTypeArray, temperature)
            val scaledRouting = applyTemperature(routingDecisionArray, temperature)
            
            // Log scaled routing
            Log.d(TAG, "Scaled routing: [${scaledRouting.joinToString(", ")}]")
            
            // Apply bias correction
            val biasedServiceType = applyBiases(scaledServiceType, svcBiases)
            val biasedRouting = applyBiases(scaledRouting, routeBiases)
            
            // Log biased routing
            Log.d(TAG, "Biased routing: [${biasedRouting.joinToString(", ")}]")
            
            // Get argmax (predicted class)
            val svcClass = biasedServiceType.indices.maxByOrNull { biasedServiceType[it] } ?: 7
            val routeDecision = biasedRouting.indices.maxByOrNull { biasedRouting[it] } ?: 0
            
            // Compute confidence (max probability)
            val svcConfidence = biasedServiceType[svcClass]
            val routeConfidence = biasedRouting[routeDecision]
            val confidence = (svcConfidence + routeConfidence) / 2f // Average confidence
            
            // Determine ALPN based on service class and routing
            val alpn = determineAlpn(svcClass, routeDecision)
            
            Log.d(TAG, "Inference result: sni=$sni, svcClass=$svcClass, route=$routeDecision, alpn=$alpn, confidence=$confidence")
            
            RouteDecision(
                svcClass = svcClass,
                alpn = alpn,
                routeDecision = routeDecision,
                confidence = confidence
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            RouteDecision(7, "h2", 0, 0.0f) // Default fallback
        }
    }
    
    /**
     * Apply temperature scaling to logits (softmax with temperature).
     */
    private fun applyTemperature(logits: FloatArray, temperature: Float): FloatArray {
        if (temperature <= 0f) {
            return logits
        }
        
        // Scale logits by temperature
        val scaled = logits.map { it / temperature }.toFloatArray()
        
        // Apply softmax
        val max = scaled.maxOrNull() ?: 0f
        val expValues = scaled.map { exp(it - max) }
        val sum = expValues.sum()
        
        return expValues.map { (it / sum).toFloat() }.toFloatArray()
    }
    
    /**
     * Apply bias correction to probabilities.
     * 
     * Note: Biases are added to logits before softmax, not after.
     * This ensures that even small biases can shift the decision.
     */
    private fun applyBiases(probs: FloatArray, biases: FloatArray): FloatArray {
        if (probs.size != biases.size) {
            Log.w(TAG, "Size mismatch: probs=${probs.size}, biases=${biases.size}")
            return probs
        }
        
        // Convert probabilities back to logits (log space)
        // Add epsilon to avoid log(0)
        val epsilon = 1e-10f
        val logits = probs.map { ln((it + epsilon).coerceIn(epsilon, 1f - epsilon)) }
        
        // Add biases to logits (biases act as logit offsets)
        // Route decision 2 (optimized) gets 10x scaling to encourage exploration
        // ONNX model gives route 2 very low scores (-1.8 to -2.0), so we need very aggressive scaling
        // With 0.6 bias and 10x scale = 6.0 logit boost, route 2 should become competitive or preferred
        val biasedLogits = logits.mapIndexed { i, logit -> 
            val scale = when {
                i == 2 && biases.size > 2 -> 10.0f // 10x for route 2 (optimized) - extremely aggressive exploration
                else -> 2.0f // 2x for others
            }
            val biased = logit + biases[i] * scale
            if (i == 2) {
                Log.d(TAG, "Route 2 logit: $logit, bias: ${biases[i]}, scale: $scale, biased: $biased")
            }
            biased
        }
        
        // Log biased logits for debugging
        Log.d(TAG, "Biased logits (routing): [${biasedLogits.joinToString(", ")}]")
        
        // Apply softmax to get new probabilities
        val max = biasedLogits.maxOrNull() ?: 0f
        val expValues = biasedLogits.map { exp(it - max) }
        val sum = expValues.sum()
        
        return if (sum > 0f) {
            expValues.map { (it / sum).toFloat() }.toFloatArray()
        } else {
            probs // Fallback to original
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
     * Public API: Optimize SNI and return route decision.
     * 
     * @param context Android context
     * @param sni Server Name Indication
     * @param latencyMs Latency in milliseconds
     * @param throughputKbps Throughput in kbps
     * @return RouteDecision
     */
    suspend fun optimizeSni(
        context: Context,
        sni: String,
        latencyMs: Double,
        throughputKbps: Double
    ): RouteDecision {
        return run(context, sni, TrafficMeta(latencyMs, throughputKbps))
    }
}

