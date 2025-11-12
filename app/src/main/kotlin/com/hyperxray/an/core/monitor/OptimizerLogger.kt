package com.hyperxray.an.core.monitor

import android.util.Log

/**
 * OptimizerLogger: Logs TLS SNI routing decisions made by the ONNX optimizer.
 * 
 * Provides structured logging for:
 * - SNI domain
 * - Detected service type
 * - Routing decision
 * - Confidence scores (optional)
 */
object OptimizerLogger {
    private const val TAG = "Optimizer"
    
    // Service type labels (8 classes)
    private val SERVICE_TYPES = listOf(
        "youtube",
        "netflix",
        "twitter_video",
        "instagram",
        "tiktok",
        "twitch",
        "spotify",
        "other"
    )
    
    // Routing decision labels (3 classes)
    private val ROUTING_DECISIONS = listOf(
        "proxy",
        "direct",
        "optimized"
    )
    
    /**
     * Log a routing decision made by the optimizer.
     * 
     * @param sni The Server Name Indication (domain)
     * @param serviceTypeIndex The detected service type index (0-7)
     * @param routingDecisionIndex The routing decision index (0=proxy, 1=direct, 2=optimized)
     * @param confidence Optional confidence score (0.0-1.0)
     */
    fun logDecision(
        sni: String,
        serviceTypeIndex: Int,
        routingDecisionIndex: Int,
        confidence: Float? = null
    ) {
        val serviceType = SERVICE_TYPES.getOrElse(serviceTypeIndex) { "?" }
        val routingDecision = ROUTING_DECISIONS.getOrElse(routingDecisionIndex) { "?" }
        
        val logMessage = buildString {
            append("sni=$sni")
            append(" | service=$serviceType")
            append(" | route=$routingDecision")
            confidence?.let {
                append(" | confidence=${String.format("%.2f", it)}")
            }
        }
        
        Log.i(TAG, "[Optimizer] $logMessage")
        
        // Also print to stdout for compatibility with example output format
        println("[Optimizer] $logMessage")
    }
    
    /**
     * Log a routing decision with detailed information.
     * 
     * @param sni The Server Name Indication (domain)
     * @param serviceTypeIndex The detected service type index
     * @param routingDecisionIndex The routing decision index
     * @param serviceTypeConfidence Confidence for service type prediction
     * @param routingConfidence Confidence for routing decision
     */
    fun logDecisionDetailed(
        sni: String,
        serviceTypeIndex: Int,
        routingDecisionIndex: Int,
        serviceTypeConfidence: Float,
        routingConfidence: Float
    ) {
        val serviceType = SERVICE_TYPES.getOrElse(serviceTypeIndex) { "?" }
        val routingDecision = ROUTING_DECISIONS.getOrElse(routingDecisionIndex) { "?" }
        
        val logMessage = buildString {
            append("sni=$sni")
            append(" | service=$serviceType")
            append(" | route=$routingDecision")
            append(" | service_confidence=${String.format("%.2f", serviceTypeConfidence)}")
            append(" | routing_confidence=${String.format("%.2f", routingConfidence)}")
        }
        
        Log.i(TAG, "[Optimizer] $logMessage")
        println("[Optimizer] $logMessage")
    }
    
    /**
     * Get service type label for a given index.
     */
    fun getServiceTypeLabel(index: Int): String {
        return SERVICE_TYPES.getOrElse(index) { "unknown" }
    }
    
    /**
     * Get routing decision label for a given index.
     */
    fun getRoutingDecisionLabel(index: Int): String {
        return ROUTING_DECISIONS.getOrElse(index) { "unknown" }
    }
}

