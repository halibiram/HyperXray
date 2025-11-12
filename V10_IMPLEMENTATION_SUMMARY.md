# Auto-Learning TLS Optimizer v10 - Implementation Summary

## Overview

Successfully implemented **Auto-Learning TLS Optimizer v10** for HyperXray Android app. This module provides continuous on-device learning from real traffic without cloud retraining, dynamically adjusting routing policies for Xray-core (REALITY) based on feedback.

## Key Features

### 1. ONNX Inference
- Loads `tls_sni_optimizer_v9.onnx` from assets using `onnxruntime-android`
- Performs local inference for every TLS SNI observation
- Inputs: 32 float features (identical to Colab encoder)
- Outputs: service logits (8) + routing logits (3)

### 2. Enhanced Feature Encoder
- Kotlin implementation identical to Colab version
- Features include:
  - Domain length, dot count, digit count, unique chars, hash norm
  - Entropy, ALPN (h2/h3), latency, throughput normalization
  - Padding up to 32 floats
- Helper data class: `TrafficMeta(latencyMs, throughputKbps)`

### 3. Enhanced Learner State
- Maintains in `SharedPreferences`:
  - `temperature: Float`
  - `svcBias[8]: FloatArray`
  - `routeBias[3]: FloatArray`
  - `successCount, failCount: Int` (NEW in v10)
- Exposes functions:
  - `save()`, `reset()`
  - `updateWithFeedback(success, throughputKbps, svcIdx, routeIdx)`
  - `getSuccessRate()` (NEW in v10)
- Exponential moving average (EMA) updates:
  ```
  bias += α * (target - bias)
  temperature = clamp(T ± Δ)
  ```

### 4. Enhanced Inference Pipeline
- `Inference.run(context, sni, meta)`:
  - Encodes SNI with latency/throughput normalization
  - Runs ONNX model
  - Applies temperature & bias correction
  - Returns `RouteDecision(svcClass, routeDecision, alpn, confidence)` (confidence NEW in v10)

### 5. Real-Time Feedback Recording
- `FeedbackLogger.log(sni, latency, throughput, success)`:
  - Logs to `/data/data/<pkg>/files/learner_log.jsonl`
  - Updates learner state counters atomically
  - Privacy-safe SNI redaction
  - Automatic log rotation (10MB limit)

### 6. Continuous Feedback Loop (Background Learning)
- `RealityWorker` via `WorkManager`:
  - Scheduled every 30 minutes
  - Can run when idle/charging (flexible constraints)
  - Reads `learner_log.jsonl`
  - Aggregates success/failure metrics
  - Calls `LearnerState.updateWithFeedback(...)` to refine biases
  - Rebuilds `xray_reality_policy.json`
  - Sends Xray-core reload command via broadcast intent:
    ```
    com.hyperxray.REALITY_RELOAD
    com.hyperxray.an.RELOAD_CONFIG
    ```

### 7. Enhanced Compose Debug UI
- `LearnerDebugScreen` displays:
  - Current temperature T
  - Service type biases (svcBias[8])
  - Routing decision biases (routeBias[3])
  - Success/fail counts and success rate (NEW in v10)
  - "Reset Learner" button
  - "Force Reload Xray" button (NEW in v10)
  - "Refresh" button

## File Structure

```
com.hyperxray.an.optimizer/
├── OrtHolder.kt              # ONNX Runtime environment & session
├── SniFeatureEncoder.kt      # 32D feature encoding (enhanced with latency/throughput)
├── LearnerState.kt           # Persistent state (enhanced with success/fail counts)
├── OnDeviceLearner.kt        # EMA-based learning updates
├── Inference.kt              # Inference pipeline (enhanced with confidence)
├── FeedbackLogger.kt         # Real-time feedback recording (NEW)
├── RealityWorker.kt          # Background learning worker (NEW, replaces RealityWorkManager)
└── ui/DebugLearnerScreen.kt  # Compose debug UI (enhanced)

assets/
└── tls_sni_optimizer_v9.onnx  # ONNX model file

app/src/test/kotlin/com/hyperxray/an/optimizer/
└── InferenceTest.kt         # Test case for ONNX inference verification
```

## Usage Example

```kotlin
// Run inference during network routing decision
val result = Inference.run(
    context = context,
    sni = "tiktokcdn.com",
    meta = TrafficMeta(latencyMs = 25.0, throughputKbps = 720.0)
)

// After request completes, log feedback
FeedbackLogger.log(
    context = context,
    sni = "tiktokcdn.com",
    latency = 25.0,
    throughput = 780.0,
    success = true
)
```

## Test Case

Run the test case to verify ONNX inference:

```kotlin
// From InferenceTest.kt
Inference.run(context, "googlevideo.com", TrafficMeta(20.0, 850.0))
```

Expected output:
- Valid `RouteDecision` with `svcClass` (0-7), `routeDecision` (0-2), `confidence` (0.0-1.0)

## Integration Points

1. **App Initialization** (`HyperXrayApplication.kt`):
   - Initializes `OrtHolder` at startup
   - Schedules `RealityWorker` for periodic learning

2. **Network Monitor** (to be integrated):
   - Call `FeedbackLogger.log()` after each connection attempt
   - Use `Inference.run()` for routing decisions

3. **Xray Reload**:
   - `RealityWorker` triggers reload via broadcast intent
   - Debug UI provides manual "Force Reload" button

## Dependencies

- `onnxruntime-android:1.20.0` (already in build.gradle)
- `work-runtime-ktx:2.9.0` (already in build.gradle)

## Build & Test

```bash
# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Install on device
./gradlew :app:installDebug
```

## Next Steps

1. **Integration with Network Monitor**:
   - Hook `FeedbackLogger.log()` into actual network connection results
   - Use `Inference.run()` for routing decisions in `TProxyService`

2. **Optional Cloud Sync** (future extension):
   - If Wi-Fi and charging → upload last 24h of feedback logs
   - Endpoint: `https://api.hyperxray.net/feedback/upload`
   - Include device ID and model version in JSON payload

3. **Enhanced Aggregation**:
   - Improve `RealityWorker.aggregateMetrics()` to group by actual svc/route from inference
   - Currently uses default aggregation for simplicity

## Commit Message

```
feat: add Auto-Learning TLS Optimizer v10 (on-device continuous learning + REALITY integration)

- Enhanced LearnerState with successCount/failCount tracking
- Added FeedbackLogger for real-time feedback recording
- Created RealityWorker (WorkManager) for periodic learning updates
- Enhanced Inference with confidence score in RouteDecision
- Updated SniFeatureEncoder with latency/throughput normalization
- Enhanced LearnerDebugScreen with success/fail stats and Force Reload
- Added InferenceTest for ONNX inference verification
```

## Status

✅ All core components implemented and tested
✅ Gradle build passes
✅ Test case created for inference verification
✅ Ready for integration with network monitor

