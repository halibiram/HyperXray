# HyperXray Native Layer

This directory contains the Go-based native layer for WireGuard over Xray tunneling.

## Architecture

The native layer bridges WireGuard and Xray-core:

```
TUN fd → WireGuard Device → XrayBind → Xray Instance → Network
```

## Components

- **lib.go**: Main entry point with JNI exports
- **bridge/bridge.go**: HyperTunnel implementation that coordinates WireGuard and Xray
- **wireguard/xray_bind.go**: Custom WireGuard bind that routes packets through Xray
- **xray/instance.go**: Xray-core instance management
- **xray/config.go**: Xray configuration generation

## Building

### Prerequisites

- **Go 1.23+**: Download from [golang.org](https://go.dev/dl/)
- **Android NDK**: Version 28.2.13676358 or later (install via Android Studio SDK Manager)
- **Environment Variables**: Set `NDK_HOME` or `ANDROID_NDK_HOME` to your NDK installation path

### Build Command

**Windows:**
```bash
scripts\build-native.bat
```

**Linux/Mac:**
```bash
chmod +x scripts/build-native.sh
./scripts/build-native.sh
```

This will build `libhyperxray.so` for supported architectures:
- **arm64-v8a** (ARM 64-bit)
- **x86_64** (Intel 64-bit)

Output will be placed in `app/src/main/jniLibs/[architecture]/libhyperxray.so`.

### Manual Build

If you prefer to build manually:

```bash
cd native

# Initialize Go module (if not already done)
go mod init github.com/hyperxray/native
go mod tidy

# Set environment variables
export GOOS=android
export GOARCH=arm64  # or amd64 for x86_64
export CGO_ENABLED=1
export CC=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang

# Build shared library
go build -buildmode=c-shared \
    -o ../app/src/main/jniLibs/arm64-v8a/libhyperxray.so \
    -trimpath \
    -ldflags="-s -w -buildid=" \
    -v .
```

## JNI Interface

The native library exports the following functions:

- `StartHyperTunnel(tunFd, wgConfigJson, xrayConfigJson)`: Start tunnel
- `StopHyperTunnel()`: Stop tunnel
- `GetTunnelStats()`: Get statistics
- `NativeGeneratePublicKey(privateKeyBase64)`: Generate WireGuard public key

## Implementation Status

✅ **Core Components Implemented**:
- ✅ Go module setup and dependencies
- ✅ JNI interface (`lib.go`)
- ✅ WireGuard + Xray bridge (`bridge/bridge.go`)
- ✅ Custom WireGuard bind (`wireguard/xray_bind.go`)
- ✅ Xray instance management (`xray/instance.go`)
- ✅ Android build scripts (Windows and Unix)
- ✅ Android service integration (`HyperVpnService`)

⚠️ **Note**: The Xray-core process integration is partially implemented. The following may need enhancement:

1. **Xray Instance Management** (`xray/instance.go`):
   - Currently uses placeholder UDP forwarding
   - Full Xray-core process integration can be added
   - Process lifecycle management is ready

2. **UDP Packet Routing** (`wireguard/xray_bind.go`):
   - Basic packet routing implemented
   - Error handling and reconnection logic can be enhanced

3. **Key Conversion** (`bridge/bridge.go`):
   - WireGuard key handling implemented
   - Base64 keys are used directly (WireGuard accepts base64)

## Dependencies

See `go.mod` for full dependency list. Key dependencies:

- `golang.zx2c4.com/wireguard`: WireGuard Go implementation (v0.0.0-20250521234502-f333402bd9cb)
- `golang.org/x/crypto`: Cryptographic functions (v0.37.0) - for Curve25519
- `golang.org/x/net`: Network utilities (v0.39.0)
- `golang.org/x/sys`: System calls (v0.32.0)

## Android Integration

The native library is integrated into the Android app via:

- **HyperVpnService**: Android VPN service that uses the native library
- **HyperVpnHelper**: Convenience helper class for starting/stopping VPN
- **JNI Interface**: Native functions are called from Kotlin code

### Usage Example

```kotlin
// Start VPN with WARP (automatic config)
HyperVpnHelper.startVpnWithWarp(context)

// Start VPN with custom configs
HyperVpnHelper.startVpnWithConfig(
    context,
    wgConfigJson,  // WireGuard JSON
    xrayConfigJson // Xray JSON
)

// Stop VPN
HyperVpnHelper.stopVpn(context)
```

See `app/src/main/kotlin/com/hyperxray/an/vpn/` for implementation details.



