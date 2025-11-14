package com.hyperxray.an.feature.policyai

/**
 * Routing decision result from AI model inference.
 * 
 * @param svcClass Service class index (0-7)
 * @param alpn ALPN protocol ("h2", "h3", etc.)
 * @param routeDecision Routing decision index (0=Proxy, 1=Direct, 2=Optimized)
 * @param confidence Confidence score (0.0 to 1.0)
 */
data class RoutingDecision(
    val svcClass: Int,
    val alpn: String,
    val routeDecision: Int,
    val confidence: Float = 0.5f
)

