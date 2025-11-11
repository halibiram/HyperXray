# Safety & Control Implementation Summary

## Overview

This document summarizes the implementation of the Safety & Control system for HyperXray, including Safeguard, RolloutGuard, and RealityOptimizer components.

## Components

### 1. Safeguard.kt

**Location:** `app/src/main/kotlin/com/hyperxray/an/common/Safeguard.kt`

**Purpose:** Monitors connection metrics and enforces safety thresholds to prevent rollout of configurations that exceed acceptable performance limits.

**Thresholds:**
- **Handshake time:** < 1200ms
- **Packet loss:** < 0.03 (3%)
- **RTT P95:** < 300ms

**Key Methods:**
- `check(metrics: TelemetryMetrics): SafeguardResult` - Checks if metrics pass all thresholds
- `ok(metrics: TelemetryMetrics): Boolean` - Convenience method returning true if all thresholds pass
- `getMaxHandshakeMs(): Double` - Returns maximum allowed handshake time
- `getMaxLoss(): Double` - Returns maximum allowed packet loss rate
- `getMaxRttP95Ms(): Double` - Returns maximum allowed RTT P95

**Usage:**
```kotlin
val metrics = TelemetryMetrics(
    handshakeTime = 800.0,
    loss = 0.01,
    rttP95 = 150.0,
    throughput = 10000000.0,
    timestamp = Instant.now()
)

val result = Safeguard.check(metrics)
if (result.isOk()) {
    // Proceed with rollout
} else {
    // Handle violations
    result.violations.forEach { violation ->
        Log.w(TAG, "Violation: $violation")
    }
}
```

### 2. RolloutGuard.kt

**Location:** `app/src/main/kotlin/com/hyperxray/an/common/RolloutGuard.kt`

**Purpose:** Manages safe configuration rollout strategies with support for shadow testing, A/B testing, and enforced rollout modes.

**Modes:**
- **SHADOW:** Test configuration without affecting production traffic
- **AB:** A/B test with configurable split (default 10%)
- **ENFORCE:** Enforce configuration for all traffic

**Key Methods:**
- `shouldUseNewConfig(config: RolloutConfig): RolloutDecision` - Determines if user should use new config
- `createAbTestConfig(userId: String): RolloutConfig` - Creates A/B test config with 10% split
- `createShadowConfig(): RolloutConfig` - Creates shadow test config
- `createEnforceConfig(): RolloutConfig` - Creates enforce config

**Usage:**
```kotlin
// A/B testing with 10% split
val abConfig = RolloutGuard.createAbTestConfig(userId = "user-123")
val decision = RolloutGuard.shouldUseNewConfig(abConfig)

if (decision.useNewConfig) {
    // Use new configuration
} else {
    // Use existing configuration
}

// Shadow testing
val shadowConfig = RolloutGuard.createShadowConfig()
val shadowDecision = RolloutGuard.shouldUseNewConfig(shadowConfig)
// shadowDecision.useNewConfig will always be false
```

**A/B Testing Logic:**
- Uses hash-based deterministic assignment
- User ID is hashed and modulo 100 to get bucket (0-99)
- Users in buckets 0 to (abSplitPercent - 1) get new config
- Ensures consistent assignment for same user ID

### 3. RealityOptimizer.kt

**Location:** `app/src/main/kotlin/com/hyperxray/an/common/RealityOptimizer.kt`

**Purpose:** Patches placeholders in VLESS Reality base template with actual configuration values.

**Supported Placeholders:**
- `address`: Server address
- `port`: Server port (default: 443)
- `id`: User UUID
- `flow`: Flow control (optional, e.g., "xtls-rprx-vision")
- `dest`: Reality destination
- `serverNames`: Array of server names for SNI
- `privateKey`: Reality private key
- `shortIds`: Array of short IDs
- `spiderX`: SpiderX parameter (optional)

**Key Methods:**
- `patchTemplate(templateJson: String, params: RealityParams): OptimizeResult` - Patches template with parameters
- `validatePatchedJson(patchedJson: String): Boolean` - Validates patched JSON structure

**Usage:**
```kotlin
val templateJson = // Load from assets/templates/vless_reality_base.json

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

val result = RealityOptimizer.patchTemplate(templateJson, params)
if (result.success && result.patchedJson != null) {
    // Validate patched JSON
    val isValid = RealityOptimizer.validatePatchedJson(result.patchedJson)
    if (isValid) {
        // Use patched JSON configuration
    }
}
```

## Testing

### SafetyControlTest.kt

**Location:** `app/src/main/kotlin/com/hyperxray/an/common/SafetyControlTest.kt`

**Purpose:** Comprehensive test suite demonstrating all three components with dummy data.

**Test Coverage:**
1. **Safeguard Tests:**
   - Passing metrics
   - Failing handshake time
   - Failing packet loss
   - Failing RTT P95
   - All thresholds failing

2. **RolloutGuard Tests:**
   - Shadow mode (always returns false)
   - A/B test mode with 10% split
   - Enforce mode (always returns true)

3. **RealityOptimizer Tests:**
   - Full parameter patching
   - Minimal parameter patching
   - JSON validation
   - Error handling

**Run Tests:**
```kotlin
SafetyControlTest.runTests()
```

## Validation

### ✅ Valid JSON Produced
- RealityOptimizer produces valid JSON with proper formatting
- All placeholders are correctly replaced
- JSON structure matches Xray-core requirements

### ✅ Safeguard ok() Triggers Correctly
- Returns `true` when all thresholds pass
- Returns `false` when any threshold fails
- Violations are properly logged and reported

### ✅ No String Replacement Errors
- Uses JSONObject API for safe JSON manipulation
- Proper error handling for malformed templates
- Validation ensures required fields are present

## Threshold Logic Summary

### Safeguard Thresholds

All thresholds must pass for `Safeguard.ok()` to return `true`:

1. **Handshake Time:** < 1200ms
   - Measures TLS handshake duration
   - Critical for connection establishment performance

2. **Packet Loss:** < 0.03 (3%)
   - Measures percentage of lost packets
   - Critical for connection stability

3. **RTT P95:** < 300ms
   - Measures 95th percentile round-trip time
   - Critical for latency-sensitive applications

**Logic:**
- If any threshold is exceeded, the check fails
- All violations are collected and reported
- Check returns `SafeguardResult` with detailed violation information

## Sample Patched JSON

```json
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
            "address": "server.example.com",
            "port": 443,
            "users": [
              {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "encryption": "none",
                "flow": "xtls-rprx-vision"
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
          "dest": "www.microsoft.com:443",
          "xver": 0,
          "serverNames": ["www.microsoft.com"],
          "privateKey": "example-private-key-base64",
          "shortIds": ["01234567"],
          "spiderX": "/path?key=value"
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
```

## Remaining TODOs

- [ ] Integrate Safeguard with telemetry collection system
- [ ] Integrate RolloutGuard with configuration management
- [ ] Integrate RealityOptimizer with config import/export
- [ ] Add persistent storage for rollout decisions
- [ ] Add metrics collection for rollout success rates
- [ ] Add shadow testing infrastructure
- [ ] Add A/B test analytics dashboard
- [ ] Add automatic rollback on safeguard failures
- [ ] Add configuration validation before patching
- [ ] Add support for multiple outbound configurations

## Next Stage

**NEXT-STAGE: Shadow Tester**

The next stage should implement shadow testing infrastructure that:
1. Routes traffic through new configuration without affecting production
2. Collects metrics for comparison with production configuration
3. Automatically triggers rollback if safeguard thresholds are exceeded
4. Provides analytics dashboard for shadow test results

## Integration Points

### With Telemetry System
- Safeguard should be called after collecting telemetry metrics
- Metrics should be aggregated over time windows
- Violations should trigger alerts and automatic rollback

### With Configuration Management
- RolloutGuard should be integrated with config selection logic
- User IDs should be stable across app sessions
- Rollout decisions should be cached for consistency

### With Config Import/Export
- RealityOptimizer should be used when importing VLESS Reality configs
- Template should be loaded from assets during import
- Patched configs should be validated before saving

## Files Created

1. `app/src/main/kotlin/com/hyperxray/an/common/Safeguard.kt`
2. `app/src/main/kotlin/com/hyperxray/an/common/RolloutGuard.kt`
3. `app/src/main/kotlin/com/hyperxray/an/common/RealityOptimizer.kt`
4. `app/src/main/kotlin/com/hyperxray/an/common/SafetyControlTest.kt`

## Dependencies

- `com.hyperxray.an.telemetry.TelemetryMetrics` - For metric types
- `org.json.JSONObject` - For JSON manipulation
- `android.util.Log` - For logging
- `java.time.Instant` - For timestamps
- `java.util.UUID` - For user ID generation

## Notes

- All components are thread-safe (object singletons)
- Error handling is comprehensive with proper logging
- JSON manipulation uses safe APIs to prevent injection
- Validation ensures data integrity before use
- Test suite provides comprehensive coverage with dummy data



