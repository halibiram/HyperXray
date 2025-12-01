# HyperXray Tech Stack

## Build System
- Gradle 8.13+ with Groovy DSL
- Android Gradle Plugin 8.13.1
- Kotlin 2.2.21
- Java 17 (source/target compatibility)

## Languages
- Kotlin (Android app, UI, business logic)
- Go 1.25+ (native library - libhyperxray.so)
- C/C++ (JNI bridge via NDK)
- Protobuf (gRPC service definitions)

## Core Dependencies
- Xray-core v25.10.15 (embedded in native Go library)
- WireGuard-go (golang.zx2c4.com/wireguard)
- gRPC 1.74.0 (stats API, service communication)
- Protobuf 3.25.x

## Android Stack
- Jetpack Compose (UI) with Material 3
- Compose BOM 2025.08.00
- AndroidX Core KTX 1.16.0
- Lifecycle components 2.8.7
- Kotlin Coroutines 1.9.0

## Networking
- OkHttp 4.12.0 (HTTP client, DNS-over-HTTPS)
- Cronet 119.x (HTTP/3 support)
- Conscrypt 2.5.3 (TLS acceleration)

## ML/AI
- ONNX Runtime (on-device inference)
- PyTorch (model training - offline)

## NDK
- NDK version: 28.2.13676358
- Min SDK: 29 (Android 10)
- Target SDK: 35

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build native Go library (libhyperxray.so)
./gradlew buildNativeGo
# Or use scripts:
scripts/build-native.bat   # Windows
scripts/build-native.sh    # Linux/Mac

# Download geo files (geoip.dat, geosite.dat)
./gradlew downloadGeoFiles

# Train ONNX policy model (optional)
./gradlew trainOnnxModel

# Run tests
./gradlew test

# Generate baseline profile
./gradlew :baselineprofile:generateBaselineProfile

# Clean build
./gradlew clean
```

## Environment Variables
- `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`: Path to Android NDK
- `ANDROID_HOME` or `ANDROID_SDK_ROOT`: Path to Android SDK
- `GO`: Custom Go binary path (optional)
