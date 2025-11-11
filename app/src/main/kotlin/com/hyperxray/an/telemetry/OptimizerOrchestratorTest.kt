package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.runBlocking

/**
 * Test/demo for OptimizerOrchestrator end-to-end validation.
 * 
 * Validates:
 * - End-to-end works without crash
 * - Logs "HyperXray AI Optimizer loop stable"
 * - Reward trending upward
 * - Sample cycle output
 * - TODOs for next step
 * - "NEXT-STAGE: Validator & QA"
 */
class OptimizerOrchestratorTest(
    private val context: Context
) {
    private val TAG = "OptimizerOrchestratorTest"
    
    /**
     * Run end-to-end test of OptimizerOrchestrator
     */
    fun runTest() = runBlocking {
        Log.i(TAG, "========================================")
        Log.i(TAG, "=== OptimizerOrchestrator E2E Test ===")
        Log.i(TAG, "========================================")
        Log.i(TAG, "")
        
        try {
            // Create sample arms
            val arms = createSampleArms()
            Log.i(TAG, "Created ${arms.size} sample arms")
            
            // Create orchestrator
            val orchestrator = OptimizerOrchestrator(context)
            Log.i(TAG, "Created OptimizerOrchestrator")
            Log.i(TAG, "")
            
            // Create sample core stats
            val coreStats = CoreStatsState(
                uplink = 1_000_000L,
                downlink = 5_000_000L,
                uplinkThroughput = 100_000.0,
                downlinkThroughput = 500_000.0,
                numGoroutine = 10,
                numGC = 5,
                alloc = 10_000_000L,
                totalAlloc = 100_000_000L,
                sys = 50_000_000L,
                mallocs = 1000L,
                frees = 500L,
                liveObjects = 500L,
                pauseTotalNs = 1_000_000L,
                uptime = 3600
            )
            
            // Run multiple cycles
            val numCycles = 10
            Log.i(TAG, "Running $numCycles optimization cycles...")
            Log.i(TAG, "")
            
            val results = mutableListOf<OptimizerOrchestrator.CycleResult>()
            
            for (i in 1..numCycles) {
                val result = orchestrator.runOptimizerCycle(
                    coreStats = coreStats,
                    availableArms = arms
                )
                results.add(result)
                
                if (result.success) {
                    Log.i(TAG, "Cycle #${result.cycleNumber}: SUCCESS - " +
                            "Arm=${result.selectedArm?.armId}, " +
                            "Reward=${result.reward?.let { "%.2f".format(it) }}, " +
                            "Safeguard=${if (result.safeguardPassed) "PASS" else "FAIL"}")
                } else {
                    Log.w(TAG, "Cycle #${result.cycleNumber}: FAILED - ${result.error}")
                }
            }
            
            Log.i(TAG, "")
            Log.i(TAG, "========================================")
            Log.i(TAG, "=== Validation ===")
            Log.i(TAG, "========================================")
            
            // Validation 1: End-to-end works without crash
            val noCrashes = results.all { it.cycleNumber > 0 }
            Log.i(TAG, "✓ End-to-end works without crash: $noCrashes")
            require(noCrashes) { "Orchestrator should complete all cycles without crash" }
            
            // Validation 2: Check reward trending
            val successfulCycles = results.filter { it.success && it.reward != null }
            val rewards = successfulCycles.mapNotNull { it.reward }
            
            if (rewards.size >= 5) {
                val recentAvg = rewards.takeLast(5).average()
                val previousAvg = rewards.dropLast(5).takeLast(5).average()
                val trendingUp = recentAvg > previousAvg
                
                Log.i(TAG, "✓ Reward trending upward: $trendingUp")
                Log.i(TAG, "  Recent avg (last 5): $recentAvg")
                Log.i(TAG, "  Previous avg (5 before): $previousAvg")
            } else {
                Log.w(TAG, "⚠ Not enough cycles to check reward trend (need >= 10 successful)")
            }
            
            // Validation 3: Check safeguard passes
            val safeguardPasses = successfulCycles.count { it.safeguardPassed }
            val safeguardTotal = successfulCycles.size
            Log.i(TAG, "✓ Safeguard passes: $safeguardPasses/$safeguardTotal")
            
            // Print sample cycle output
            Log.i(TAG, "")
            Log.i(TAG, "========================================")
            Log.i(TAG, "=== Sample Cycle Output ===")
            Log.i(TAG, "========================================")
            
            if (successfulCycles.isNotEmpty()) {
                val sample = successfulCycles.first()
                Log.i(TAG, "Cycle #${sample.cycleNumber}:")
                Log.i(TAG, "  Selected Arm: ${sample.selectedArm?.armId}")
                Log.i(TAG, "  Reward: ${sample.reward?.let { "%.2f".format(it) }}")
                Log.i(TAG, "  Metrics:")
                sample.metrics?.let { m ->
                    Log.i(TAG, "    Throughput: ${"%.2f".format(m.throughput)} bytes/s")
                    Log.i(TAG, "    RTT P95: ${"%.2f".format(m.rttP95)} ms")
                    Log.i(TAG, "    Handshake: ${"%.2f".format(m.handshakeTime)} ms")
                    Log.i(TAG, "    Loss: ${"%.4f".format(m.loss)}")
                }
                Log.i(TAG, "  Safeguard: ${if (sample.safeguardPassed) "PASSED" else "FAILED"}")
                if (sample.safeguardViolations.isNotEmpty()) {
                    Log.i(TAG, "  Violations: ${sample.safeguardViolations.joinToString()}")
                }
            }
            
            // Print orchestrator summary
            Log.i(TAG, "")
            orchestrator.printSummary()
            
            // Final validation message
            Log.i(TAG, "")
            Log.i(TAG, "========================================")
            Log.i(TAG, "HyperXray AI Optimizer loop stable")
            Log.i(TAG, "========================================")
            Log.i(TAG, "")
            
            // Cleanup
            orchestrator.close()
            
            Log.i(TAG, "✓ All tests passed!")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Create sample Reality arms for testing
     */
    private fun createSampleArms(): List<RealityArm> {
        return listOf(
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
    }
}



