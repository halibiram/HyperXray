# TLS SNI Optimizer v5 - Full Integration Guide

## Overview

TLS SNI Optimizer v5 (Max Turbo) provides intelligent routing decisions for TLS traffic based on Server Name Indication (SNI) analysis using ONNX machine learning models.

## Architecture

### Components

1. **TlsSniModel** (`app/src/main/kotlin/com/hyperxray/an/ml/TlsSniModel.kt`)
   - Loads ONNX model (FP32/FP16)
   - Performs MC Dropout inference for uncertainty estimation
   - Returns service type (0-7) and routing decision (0-2)

2. **FeedbackManager** (`app/src/main/kotlin/com/hyperxray/an/runtime/FeedbackManager.kt`)
   - Collects latency (ms) and throughput (kbps) metrics
   - Maintains adaptive thresholds using rolling percentile windows
   - Logs to JSONL format (`tls_v5_runtime_log.jsonl`)

3. **BanditRouter** (`app/src/main/kotlin/com/hyperxray/an/runtime/BanditRouter.kt`)
   - Epsilon-greedy multi-armed bandit algorithm
   - Learns optimal routing decisions from feedback
   - Updates reward estimates using exponential moving average

4. **RealityAdvisor** (`app/src/main/kotlin/com/hyperxray/an/runtime/RealityAdvisor.kt`)
   - Generates `xray_reality_profile_v5.json` and `xray_runtime_policy_v5.json`
   - Includes ALPN/SNI/shortId rotation support
   - Placeholder validation for security

5. **TlsRuntimeWorker** (`app/src/main/kotlin/com/hyperxray/an/workers/TlsRuntimeWorker.kt`)
   - Periodic WorkManager job (15-minute intervals)
   - Processes SNI samples, runs inference, updates bandit
   - Triggers Xray hot-reload when configuration changes

## Model Files

Place ONNX models in `app/src/main/assets/models/`:
- `tls_sni_optimizer_v5_fp32.onnx` (required)
- `tls_sni_optimizer_v5_fp16.onnx` (optional, fallback)

## Configuration

### Environment Variables

Create `.env` file (see `.env.example`):

```bash
# Xray REALITY Configuration
XRAY_PUBLIC_KEY=REPLACE_WITH_PUBLIC_KEY
XRAY_DEST_HOST=www.example.com:443
XRAY_PRIVATE_KEY=REPLACE_WITH_PRIVATE_KEY

# Feature Flags
V5_RUNTIME_ENABLED=true
V5_BANDIT_EPS=0.08
V5_MC_PASSES=5
V5_ADAPT_WINDOW=60
```

### Android BuildConfig

Inject via `local.properties` or `BuildConfig`:
- `V5_RUNTIME_ENABLED`: Enable/disable v5 runtime
- `V5_BANDIT_EPS`: Epsilon for exploration (0.0-1.0)
- `V5_MC_PASSES`: MC Dropout passes (default: 5)
- `V5_ADAPT_WINDOW`: Adaptive threshold window size (default: 60)

## Running on Android

### Development Build

1. Place ONNX models in `app/src/main/assets/models/`
2. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install on device:
   ```bash
   ./gradlew :app:installDebug
   ```
4. Check logs:
   ```bash
   adb logcat | grep -E "TlsSniModel|FeedbackManager|BanditRouter|TlsRuntimeWorker"
   ```

### Logs Location

- Runtime logs: `tls_v5_runtime_log.jsonl` in app's `files/logs/` directory
- Profile/Policy: `xray_reality_profile_v5.json` and `xray_runtime_policy_v5.json` in `files/xray_config/`

Access via:
```bash
adb shell run-as com.hyperxray.an cat files/logs/tls_v5_runtime_log.jsonl
```

## Python Runtime Agent

### Setup

```bash
cd runtime/py
pip install -r requirements.txt
```

### Run

```bash
python tls_sni_v5_runtime.py \
  --model models/tls_sni_optimizer_v5_fp32.onnx \
  --log out/runtime_log.jsonl \
  --steps 200 \
  --mc-passes 5
```

### Output

- `runtime_log.jsonl`: JSONL log with metrics
- `xray_config/xray_reality_profile_v5.json`: REALITY profile
- `xray_config/xray_runtime_policy_v5.json`: Runtime policy

## Google Colab Training

### Single Cell Training

1. Open `colab/tls_sni_v5_colab.ipynb` in Google Colab
2. Copy content from `colab/tls_sni_v5_colab_cell.py` into the cell
3. Run the entire cell
4. Models will auto-save to Google Drive (`/content/drive/MyDrive/hyperxray_v5/`)

### Output Files

- `tls_sni_optimizer_v5_fp32.onnx`: FP32 model
- `tls_sni_optimizer_v5_fp16.onnx`: FP16 model (optional)
- `xray_reality_profile_v5.json`: Profile template
- `xray_runtime_policy_v5.json`: Policy template
- `tls_sni_v5_artifacts.zip`: All artifacts bundled

## Xray Integration

### Hot Reload

The worker automatically writes profile/policy files. To trigger Xray reload:

**Option 1: File Watch** (if Xray supports)
- Place files in watched directory
- Xray auto-reloads on file change

**Option 2: API Call** (if Xray API available)
- Call Xray stats API reload endpoint
- Or send broadcast intent to TProxyService

**Option 3: Manual Reload**
- Restart Xray process via TProxyService

### Profile/Policy Format

**Profile** (`xray_reality_profile_v5.json`):
```json
{
  "version": "v5",
  "serviceType": 0,
  "route": 1,
  "reality": {
    "dest": "REPLACE_WITH_DEST_HOST:443",
    "serverNames": ["youtube.com", "googlevideo.com"],
    "privateKey": "REPLACE_WITH_PRIVATE_KEY",
    "shortIds": ["a1b2c3d4", "e5f6g7h8"],
    "alpn": ["h2", "h3", "http/1.1"]
  }
}
```

**Policy** (`xray_runtime_policy_v5.json`):
```json
{
  "version": "v5",
  "targets": {
    "latencyMs": 200.0,
    "throughputKbps": 5000.0
  },
  "routing": {
    "proxy": {"enabled": true, "priority": 1},
    "direct": {"enabled": true, "priority": 2},
    "optimized": {"enabled": true, "priority": 3}
  }
}
```

⚠️ **Important**: Replace `REPLACE_WITH_*` placeholders before use!

## Feature Encoding

TLS features are encoded to 32D vector (parity between Kotlin and Python):

1. Domain length (normalized 0-1)
2. Number of dots (normalized 0-1)
3. Unique character count (normalized 0-1)
4. Hash normalization (domain hash % 1000 / 1000)
5. ALPN ID (h3=0.7, h2=0.3, other=0.1)
6. Entropy (unique_chars / length)
7. Cipher diversity (approximation)
8. Extension count (approximation)
9. ClientHello length (approximation)
10-32. Padded with deterministic pseudo-random values

## Service Types

- 0: YouTube
- 1: Netflix
- 2: Twitter Video
- 3: Instagram
- 4: TikTok
- 5: Twitch
- 6: Spotify
- 7: Other

## Routing Decisions

- 0: Proxy (route via proxy)
- 1: Direct (bypass proxy)
- 2: Optimized (special handling)

## CI/CD

### GitHub Actions

- **Android Build** (`.github/workflows/android.yml`): Builds APK, checks for ONNX models
- **Python Agent** (`.github/workflows/python.yml`): Lints and tests Python runtime
- **Colab Check** (`.github/workflows/colab_check.yml`): Validates Colab notebook syntax

### Running Locally

```bash
# Android
./gradlew assembleDebug

# Python
cd runtime/py
python tls_sni_v5_runtime.py --model models/tls_sni_optimizer_v5_fp32.onnx --steps 10

# Colab (syntax check)
python -m py_compile colab/tls_sni_v5_colab_cell.py
```

## Testing

### Unit Tests

```bash
# Kotlin
./gradlew test

# Python
cd runtime/py
pytest tests/  # If tests exist
```

### Integration Tests

Run on device with real network traffic:
1. Enable v5 runtime
2. Monitor logs for SNI processing
3. Verify profile/policy generation
4. Check Xray reload behavior

## Troubleshooting

### Model Not Loading

- Check model file exists in `app/src/main/assets/models/`
- Verify model file size > 100 bytes
- Check logcat for ONNX errors

### No SNI Samples

- Ensure Xray logs contain SNI information
- Check log parsing in `TlsRuntimeWorker.collectSniSamples()`
- Verify WorkManager is scheduled

### Profile/Policy Not Generated

- Check `files/xray_config/` directory permissions
- Verify `RealityAdvisor` initialization
- Check logcat for JSON write errors

## Privacy & Security

- SNI values are redacted in logs (subdomain masking)
- No PII logged (privacy-safe)
- Placeholders must be replaced before use
- Model inference runs on-device (no cloud)

## Performance

- Model inference: ~5-10ms per SNI
- MC Dropout: 5 passes = ~25-50ms total
- WorkManager interval: 15 minutes (configurable)
- Memory: ~10-20MB for model + runtime state

## License

Same as HyperXray project license.

