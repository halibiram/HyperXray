package com.hyperxray.an.common

import android.util.Log
import com.hyperxray.an.telemetry.TelemetryMetrics
import java.time.Instant

/**
 * Test/demo file for Safety & Control components.
 * Demonstrates Safeguard, RolloutGuard, and RealityOptimizer with dummy data.
 */
object SafetyControlTest {
    private const val TAG = "SafetyControlTest"
    
    /**
     * Run all tests and demonstrations
     */
    fun runTests() {
        println("=== Safety & Control Test Suite ===")
        println()
        
        testSafeguard()
        testRolloutGuard()
        testRealityOptimizer()
        
        println()
        println("=== Threshold Logic Summary ===")
        summarizeThresholdLogic()
        
        println()
        println("=== Sample Patched JSON ===")
        printSamplePatchedJson()
        
        println()
        println("=== Remaining TODOs ===")
        printRemainingTodos()
        
        println()
        println("NEXT-STAGE: Shadow Tester")
    }
    
    /**
     * Test Safeguard with various metrics scenarios
     */
    private fun testSafeguard() {
        println("=== Testing Safeguard ===")
        println()
        
        val testCases = listOf(
            "Passing metrics" to TelemetryMetrics(
                handshakeTime = 800.0,  // < 1200ms ✓
                loss = 0.01,            // < 0.03 (1%) ✓
                rttP95 = 150.0,         // < 300ms ✓
                throughput = 10000000.0,
                timestamp = Instant.now()
            ),
            "Failing handshake" to TelemetryMetrics(
                handshakeTime = 1500.0, // >= 1200ms ✗
                loss = 0.01,            // < 0.03 ✓
                rttP95 = 150.0,         // < 300ms ✓
                throughput = 10000000.0,
                timestamp = Instant.now()
            ),
            "Failing loss" to TelemetryMetrics(
                handshakeTime = 800.0,  // < 1200ms ✓
                loss = 0.05,            // >= 0.03 (5%) ✗
                rttP95 = 150.0,         // < 300ms ✓
                throughput = 10000000.0,
                timestamp = Instant.now()
            ),
            "Failing RTT" to TelemetryMetrics(
                handshakeTime = 800.0,  // < 1200ms ✓
                loss = 0.01,            // < 0.03 ✓
                rttP95 = 350.0,         // >= 300ms ✗
                throughput = 10000000.0,
                timestamp = Instant.now()
            ),
            "All failing" to TelemetryMetrics(
                handshakeTime = 2000.0, // >= 1200ms ✗
                loss = 0.10,            // >= 0.03 (10%) ✗
                rttP95 = 500.0,         // >= 300ms ✗
                throughput = 1000000.0,
                timestamp = Instant.now()
            )
        )
        
        testCases.forEach { (name, metrics) ->
            val result = Safeguard.check(metrics)
            val status = if (result.isOk()) "✅ PASS" else "❌ FAIL"
            println("$status: $name")
            println("  Handshake: ${metrics.handshakeTime}ms (threshold: ${Safeguard.getMaxHandshakeMs()}ms)")
            println("  Loss: ${metrics.loss} (${metrics.loss * 100}%, threshold: ${Safeguard.getMaxLoss() * 100}%)")
            println("  RTT P95: ${metrics.rttP95}ms (threshold: ${Safeguard.getMaxRttP95Ms()}ms)")
            if (!result.isOk()) {
                result.violations.forEach { violation ->
                    println("  ⚠️  $violation")
                }
            }
            println()
        }
        
        // Test ok() convenience method
        val passingMetrics = TelemetryMetrics(
            handshakeTime = 500.0,
            loss = 0.005,
            rttP95 = 100.0,
            throughput = 10000000.0,
            timestamp = Instant.now()
        )
        val okResult = Safeguard.ok(passingMetrics)
        println("Safeguard.ok() test: ${if (okResult) "✅ PASS" else "❌ FAIL"}")
        println()
    }
    
    /**
     * Test RolloutGuard with different modes
     */
    private fun testRolloutGuard() {
        println("=== Testing RolloutGuard ===")
        println()
        
        // Test Shadow mode
        val shadowConfig = RolloutGuard.createShadowConfig()
        val shadowDecision = RolloutGuard.shouldUseNewConfig(shadowConfig)
        println("Shadow mode: useNewConfig=${shadowDecision.useNewConfig}")
        println("  Reason: ${shadowDecision.reason}")
        println()
        
        // Test A/B mode with multiple users
        println("A/B test mode (10% split):")
        val testUserIds = listOf(
            "user-001", "user-002", "user-003", "user-004", "user-005",
            "user-006", "user-007", "user-008", "user-009", "user-010",
            "user-011", "user-012", "user-013", "user-014", "user-015"
        )
        
        var treatmentCount = 0
        var controlCount = 0
        
        testUserIds.forEach { userId ->
            val abConfig = RolloutGuard.createAbTestConfig(userId)
            val abDecision = RolloutGuard.shouldUseNewConfig(abConfig)
            if (abDecision.useNewConfig) {
                treatmentCount++
            } else {
                controlCount++
            }
        }
        
        val treatmentPercent = (treatmentCount * 100.0 / testUserIds.size)
        println("  Test users: ${testUserIds.size}")
        println("  Treatment group: $treatmentCount (${String.format("%.1f", treatmentPercent)}%)")
        println("  Control group: $controlCount (${String.format("%.1f", 100 - treatmentPercent)}%)")
        println("  Note: Actual distribution may vary due to hash-based assignment")
        println()
        
        // Test Enforce mode
        val enforceConfig = RolloutGuard.createEnforceConfig()
        val enforceDecision = RolloutGuard.shouldUseNewConfig(enforceConfig)
        println("Enforce mode: useNewConfig=${enforceDecision.useNewConfig}")
        println("  Reason: ${enforceDecision.reason}")
        println()
    }
    
    /**
     * Test RealityOptimizer with dummy data
     */
    private fun testRealityOptimizer() {
        println("=== Testing RealityOptimizer ===")
        println()
        
        // Load template (in real usage, this would be loaded from assets)
        val templateJson = """
        {
          "log": {
            "loglevel": "warning"
          },
          "inbounds": [
            {
              "port": 10808,
              "protocol": "dokodemo-door",
              "settings": {
                "address": "8.8.8.8"
              },
              "streamSettings": {
                "network": "tcp"
              },
              "tag": "in"
            }
          ],
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "",
                    "port": 443,
                    "users": [
                      {
                        "id": "",
                        "encryption": "none",
                        "flow": ""
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "show": false,
                  "dest": "",
                  "xver": 0,
                  "serverNames": [],
                  "privateKey": "",
                  "shortIds": [],
                  "spiderX": ""
                }
              },
              "tag": "out"
            }
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              {
                "type": "field",
                "inboundTag": ["in"],
                "outboundTag": "out"
              }
            ]
          }
        }
        """.trimIndent()
        
        // Create dummy Reality parameters
        val params = RealityOptimizer.RealityParams(
            address = "example.com",
            port = 443,
            id = "12345678-1234-1234-1234-123456789abc",
            flow = "xtls-rprx-vision",
            dest = "www.microsoft.com:443",
            serverNames = listOf("www.microsoft.com", "microsoft.com"),
            privateKey = "dummy-private-key-1234567890abcdef",
            shortIds = listOf("01234567", "89abcdef"),
            spiderX = "/path?key=value"
        )
        
        // Patch template
        val result = RealityOptimizer.patchTemplate(templateJson, params)
        
        if (result.success && result.patchedJson != null) {
            println("✅ Template patching: SUCCESS")
            println()
            
            // Validate patched JSON
            val isValid = RealityOptimizer.validatePatchedJson(result.patchedJson)
            println("✅ JSON validation: ${if (isValid) "PASS" else "FAIL"}")
            println()
            
            // Print sample of patched JSON
            println("Patched JSON preview (first 500 chars):")
            println(result.patchedJson.take(500))
            println("...")
            println()
        } else {
            println("❌ Template patching: FAILED")
            println("  Error: ${result.error}")
            println()
        }
        
        // Test with minimal parameters
        println("Testing with minimal parameters:")
        val minimalParams = RealityOptimizer.RealityParams(
            address = "minimal.example.com",
            port = 8443,
            id = "minimal-uuid-12345678",
            dest = "www.google.com:443",
            privateKey = "minimal-private-key"
        )
        
        val minimalResult = RealityOptimizer.patchTemplate(templateJson, minimalParams)
        if (minimalResult.success) {
            println("✅ Minimal params: SUCCESS")
            val isValid = RealityOptimizer.validatePatchedJson(minimalResult.patchedJson!!)
            println("✅ Validation: ${if (isValid) "PASS" else "FAIL"}")
        } else {
            println("❌ Minimal params: FAILED - ${minimalResult.error}")
        }
        println()
    }
    
    /**
     * Summarize threshold logic
     */
    private fun summarizeThresholdLogic() {
        println("Safeguard Thresholds:")
        println("  - Handshake time: < ${Safeguard.getMaxHandshakeMs()}ms")
        println("  - Packet loss: < ${Safeguard.getMaxLoss() * 100}% (${Safeguard.getMaxLoss()})")
        println("  - RTT P95: < ${Safeguard.getMaxRttP95Ms()}ms")
        println()
        println("All thresholds must pass for Safeguard.ok() to return true.")
        println("Any violation will cause the check to fail.")
    }
    
    /**
     * Print sample patched JSON
     */
    private fun printSamplePatchedJson() {
        val templateJson = """
        {
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "",
                    "port": 443,
                    "users": [{"id": "", "flow": ""}]
                  }
                ]
              },
              "streamSettings": {
                "security": "reality",
                "realitySettings": {
                  "dest": "",
                  "serverNames": [],
                  "privateKey": "",
                  "shortIds": []
                }
              }
            }
          ]
        }
        """.trimIndent()
        
        val params = RealityOptimizer.RealityParams(
            address = "server.example.com",
            port = 443,
            id = "550e8400-e29b-41d4-a716-446655440000",
            flow = "xtls-rprx-vision",
            dest = "www.microsoft.com:443",
            serverNames = listOf("www.microsoft.com"),
            privateKey = "example-private-key-base64",
            shortIds = listOf("01234567")
        )
        
        val result = RealityOptimizer.patchTemplate(templateJson, params)
        if (result.success && result.patchedJson != null) {
            println(result.patchedJson)
        } else {
            println("Error generating sample JSON: ${result.error}")
        }
    }
    
    /**
     * Print remaining TODOs
     */
    private fun printRemainingTodos() {
        println("  - [ ] Integrate Safeguard with telemetry collection")
        println("  - [ ] Integrate RolloutGuard with configuration management")
        println("  - [ ] Integrate RealityOptimizer with config import/export")
        println("  - [ ] Add persistent storage for rollout decisions")
        println("  - [ ] Add metrics collection for rollout success rates")
        println("  - [ ] Add shadow testing infrastructure")
        println("  - [ ] Add A/B test analytics dashboard")
        println("  - [ ] Add automatic rollback on safeguard failures")
        println("  - [ ] Add configuration validation before patching")
        println("  - [ ] Add support for multiple outbound configurations")
    }
}



