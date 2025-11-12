package com.hyperxray.an.optimizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/**
 * Test case for ONNX inference verification.
 * 
 * Verifies that:
 * - Inference.run() returns valid RouteDecision
 * - Confidence score is in valid range (0.0 to 1.0)
 * - Service class and route decision are valid indices
 */
class InferenceTest {
    
    @Test
    fun testInferenceRun() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Initialize OrtHolder if not already initialized
        if (!OrtHolder.isReady()) {
            OrtHolder.init(context)
        }
        
        // Skip test if model not available
        if (!OrtHolder.isReady()) {
            println("⚠️ ONNX model not available, skipping inference test")
            return@runBlocking
        }
        
        // Test case: googlevideo.com with sample traffic metadata
        val sni = "googlevideo.com"
        val meta = TrafficMeta(
            latencyMs = 20.0,
            throughputKbps = 850.0
        )
        
        // Run inference
        val result = Inference.run(context, sni, meta)
        
        // Verify result
        assertNotNull("RouteDecision should not be null", result)
        assertTrue("Service class should be in range 0-7", result.svcClass in 0..7)
        assertTrue("Route decision should be in range 0-2", result.routeDecision in 0..2)
        assertTrue("Confidence should be in range 0.0-1.0", result.confidence in 0.0f..1.0f)
        assertTrue("ALPN should be h2 or h3", result.alpn in listOf("h2", "h3"))
        
        println("✅ Inference test passed:")
        println("   SNI: $sni")
        println("   Service Class: ${result.svcClass}")
        println("   Route Decision: ${result.routeDecision}")
        println("   ALPN: ${result.alpn}")
        println("   Confidence: ${result.confidence}")
    }
    
    @Test
    fun testInferenceOptimizeSni() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Initialize OrtHolder if not already initialized
        if (!OrtHolder.isReady()) {
            OrtHolder.init(context)
        }
        
        // Skip test if model not available
        if (!OrtHolder.isReady()) {
            println("⚠️ ONNX model not available, skipping inference test")
            return@runBlocking
        }
        
        // Test case: tiktokcdn.com
        val sni = "tiktokcdn.com"
        val latencyMs = 25.0
        val throughputKbps = 720.0
        
        // Run inference via optimizeSni API
        val result = Inference.optimizeSni(context, sni, latencyMs, throughputKbps)
        
        // Verify result
        assertNotNull("RouteDecision should not be null", result)
        assertTrue("Service class should be in range 0-7", result.svcClass in 0..7)
        assertTrue("Route decision should be in range 0-2", result.routeDecision in 0..2)
        assertTrue("Confidence should be in range 0.0-1.0", result.confidence in 0.0f..1.0f)
        
        println("✅ optimizeSni test passed:")
        println("   SNI: $sni")
        println("   Service Class: ${result.svcClass}")
        println("   Route Decision: ${result.routeDecision}")
        println("   Confidence: ${result.confidence}")
    }
}
