# Auto-Learning TLS SNI Optimizer v9

## Overview

The Auto-Learning TLS SNI Optimizer v9 provides **on-device continuous learning** of routing preferences based on TLS SNI traffic performance. It adapts in real-time without requiring server retraining.

## Architecture

### Core Components

1. **OrtHolder.kt** - ONNX Runtime management
   - Loads `tls_sni_optimizer_v9.onnx` from assets
   - Provides inference API

2. **SniFeatureEncoder.kt** - Feature encoding
   - 32-dimensional feature vector (identical to Colab)
   - Encodes SNI domain + ALPN to features

3. **LearnerState.kt** - State management
   - Temperature T (confidence control)
   - Service type biases (svcBias[8])
   - Routing decision biases (routeBias[3])
   - Persistent storage in SharedPreferences

4. **OnDeviceLearner.kt** - Learning logic
   - Exponential Moving Average (EMA) updates
   - `updateWithFeedback()` method
   - Adaptive temperature adjustment

5. **Inference.kt** - Inference wrapper
   - `optimizeSni()` public API
   - Temperature scaling
   - Bias correction
   - Returns `RouteDecision`

6. **RealityWorkManager.kt** - Background worker
   - Periodic analysis (30 minutes)
   - Updates biases from feedback logs
   - Writes `xray_reality_policy.json`
   - Triggers Xray reload

7. **LearnerLogger.kt** - Feedback logging
   - JSONL format (`learner_log.jsonl`)
   - Privacy-safe SNI redaction
   - Automatic rotation

## Usage

### Public API

```kotlin
// Optimize SNI and get routing decision
val decision = Inference.optimizeSni(
    context = context,
    sni = "googlevideo.com",
    latencyMs = 20.0,
    throughputKbps = 850.0
)

// decision.svcClass: 0-7 (service type)
// decision.routeDecision: 0-2 (proxy/direct/optimized)
// decision.alpn: "h2" or "h3"
```

### Logging Feedback

```kotlin
LearnerLogger.logFeedback(
    context = context,
    sni = "googlevideo.com",
    svcClass = 0,
    routeDecision = 1,
    success = true,
    latencyMs = 20f,
    throughputKbps = 850f
)
```

## Model File

Place the ONNX model at:
```
app/src/main/assets/tls_sni_optimizer_v9.onnx
```

## Debug Screen

Access via Settings → TLS SNI Optimizer → Learner Debug

Shows:
- Current temperature T
- Service type biases (8 values)
- Routing decision biases (3 values)
- Last feedback success rate
- Reset button

## Learning Process

1. **Inference**: SNI → features → ONNX → predictions
2. **Correction**: Apply temperature + biases
3. **Decision**: Return service class + routing
4. **Feedback**: Log performance metrics
5. **Update**: Background worker updates biases via EMA
6. **Reload**: Xray configuration reloaded

## Files Generated

- `learner_log.jsonl`: Feedback logs (rotating, 10MB max)
- `xray_reality_policy.json`: Updated policy with learner state

## Integration

The optimizer is automatically initialized in `HyperXrayApplication.onCreate()`:
- OrtHolder initialized
- RealityWorkManager scheduled (30 min intervals)
- Integrated with TProxyService SNI processing

## Testing

Run test case:
```kotlin
Inference.run(context, "googlevideo.com", TrafficMeta(20.0, 850.0))
```

Test file: `app/src/test/kotlin/com/hyperxray/an/optimizer/InferenceTest.kt`

## Privacy

- SNI values are redacted in logs (subdomain masking)
- No PII logged
- All learning happens on-device

## Performance

- Inference: ~5-10ms per SNI
- Learning update: ~50-100ms per batch
- Memory: ~5-10MB for model + state
- Storage: ~1-10MB for logs (rotating)

