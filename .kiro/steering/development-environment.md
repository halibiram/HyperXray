---
inclusion: fileMatch
fileMatchPattern: 'package.json|build.gradle|settings.gradle|go.mod|Dockerfile|docker-compose.yml'
---

# Development Environment Setup

## Prerequisites
- Android Studio Ladybug or newer
- JDK 17 (Temurin/Adoptium recommended)
- Android SDK with API 35
- Android NDK 28.2.13676358
- Go 1.25+ (for native library builds)
- Python 3.10+ (for ML model training)

## Environment Variables
```bash
# Required
ANDROID_HOME=/path/to/android/sdk
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358

# Optional
GO=/path/to/custom/go  # If not using system Go
```

## Local Development Setup
1. Clone repository with submodules: `git clone --recursive`
2. Copy `store.properties.example` to `store.properties` (for signing)
3. Run `./gradlew downloadGeoFiles` to get geoip/geosite data
4. Build native library: `./gradlew buildNativeGo` or `scripts/build-native.bat`

## Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Native Go library only
./gradlew buildNativeGo

# Clean and rebuild
./gradlew clean assembleDebug

# Run tests
./gradlew test
```

## Debugging Native Code
- Use Android Studio's native debugger for JNI/C++ code
- For Go code, use `dlv` with remote debugging
- Check `adb logcat -s GoLog:*` for Go runtime logs

## Common Issues
- **NDK not found**: Ensure `ANDROID_NDK_HOME` is set correctly, or use Gradle task which auto-detects
- **Go build fails**: Verify Go version is 1.25+ with `go version`
- **Signing error**: Check `store.properties` has valid keystore path
- **WireGuard handshake every 2 min**: Native library not rebuilt after fork patch - run `.\gradlew buildNativeGo`
- **Nested wireguard-go-fork folders**: Delete `native/wireguard-go-fork/wireguard-go-fork/` (setup script bug)

## Native Library Build (Windows)
```bash
# Preferred method - auto-detects NDK
.\gradlew buildNativeGo

# Manual method - requires NDK_HOME
set NDK_HOME=C:\Users\halil\AppData\Local\Android\Sdk\ndk\28.2.13676358
scripts\build-native.bat
```

Output: `app/src/main/jniLibs/{arch}/libhyperxray.so`
