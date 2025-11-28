# HyperXray - Quick Start Guide

## 🚀 Prerequisites

- **Go 1.23+** - For building native library
- **Android SDK** - API 29+ (Android 10)
- **Android NDK** - r26+ (for native builds)
- **JDK 17** - For Gradle builds

## 📦 Build Native Library

### Windows (PowerShell)

```powershell
# Set environment (if NDK installed)
$env:ANDROID_NDK_HOME = "C:\Android\sdk\ndk\26.1.10909125"

# Build native library
cd native
go mod download
go mod tidy

# Manual build for testing (arm64-v8a)
$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:CGO_ENABLED = "1"

go build -buildmode=c-shared `
    -trimpath `
    -ldflags="-s -w" `
    -o ../app/src/main/jniLibs/arm64-v8a/libhyper.so `
    ./lib.go
```

### Linux/Mac

```bash
# Use the provided script
./scripts/build-native.sh
```

## 🔧 Development Workflow

### 1. Verify Go Dependencies

```powershell
cd native
go mod verify
go mod tidy
```

### 2. Build APK

```powershell
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

### 3. Install on Device

```powershell
# Install debug APK
adb install -r app\build\outputs\apk\debug\hyperxray-universal.apk

# Check logs
adb logcat | Select-String "HyperVpnService|WarpManager|HyperTunnel"
```

## 🧪 Testing

### WARP Registration Test

```powershell
# Monitor WARP registration
adb logcat | Select-String "WarpManager"

# Expected output:
# WarpManager: Starting WARP registration
# WarpManager: Registration response received
# WarpManager: Config generated successfully
```

### VPN Connection Test

```powershell
# Monitor VPN startup
adb logcat | Select-String "HyperVpnService"

# Expected logs:
# HyperVpnService: TUN interface established (fd=XXX)
# HyperVpnService: HyperTunnel started successfully
# HyperVpnService: DNS cache server started on port 53
```

### Architecture Verification (NO SOCKS5)

```powershell
# Verify no SOCKS5 usage
adb logcat | Select-String "socks5"
# Should be EMPTY!

# Verify XrayBind usage
adb logcat | Select-String "XrayBind|conn.Bind"
# Should show XrayBind initialization
```

## 📱 APK Outputs

After build, APKs are in:
- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

With variants:
- `hyperxray-arm64-v8a.apk` - ARM64 devices
- `hyperxray-x86_64.apk` - Emulators
- `hyperxray-universal.apk` - All ABIs (larger size)

## 🐛 Debugging

### Check Native Library

```powershell
# Verify libhyper.so exists
Get-ChildItem -Recurse app\src\main\jniLibs\*\libhyper.so

# Check library info
adb shell "dumpsys package com.hyperxray.an | Select-String 'libhyper'"
```

### Monitor Native Logs

```powershell
# Filter for native layer logs
adb logcat | Select-String "HyperTunnel|XrayBind|WireGuard"
```

### DNS Cache Logs

```powershell
adb logcat | Select-String "SystemDnsCacheServer|DnsCacheManager"
```

## 🔑 Key Files

| Path | Description |
|------|-------------|
| `native/` | Go source code for libhyper.so |
| `app/src/main/kotlin/com/hyperxray/an/util/WarpManager.kt` | WARP integration |
| `app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt` | VPN service |
| `scripts/build-native.sh` | Native build script |

## 🎯 Common Issues

### Issue: libhyper.so not found

**Solution:**
```powershell
# Build native library manually
cd native
go build -buildmode=c-shared -o ../app/src/main/jniLibs/arm64-v8a/libhyper.so ./lib.go
```

### Issue: Go modules not found

**Solution:**
```powershell
cd native
go mod download
go mod tidy
```

### Issue: VPN permission denied

**Solution:**
- Grant VPN permission in Android settings
- Or uninstall and reinstall app

### Issue: DNS cache not starting

**Check logs:**
```powershell
adb logcat | Select-String "SystemDnsCacheServer"
```

Port 53 may be taken, server will fallback to port 5353.

## 📚 Documentation

- [Implementation Plan](file:///C:/Users/halil/.gemini/antigravity/brain/567a7702-0e4e-4c34-b992-46ff6e079506/implementation_plan.md)
- [Walkthrough](file:///C:/Users/halil/.gemini/antigravity/brain/567a7702-0e4e-4c34-b992-46ff6e079506/walkthrough.md)
- [Task List](file:///C:/Users/halil/.gemini/antigravity/brain/567a7702-0e4e-4c34-b992-46ff6e079506/task.md)

---

**Happy coding! 🚀**
