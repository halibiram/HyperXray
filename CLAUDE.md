# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

HyperXray is an Android VPN proxy client built on Xray-core. The key architectural innovation is that **Xray-core runs as a separate child process** (not via JNI), which provides better stability and isolation from the main app.

### Key Architecture Components

1. **Xray-core Process Execution**
   - `libxray.so` is the Xray-core binary (packaged as a shared library)
   - Executed directly via Android linker: `/system/bin/linker64 libxray.so run`
   - No wrapper needed - `TProxyService` launches libxray.so via ProcessBuilder with linker64
   - Located at: `app/src/main/jniLibs/[abi]/libxray.so`

2. **VPN Service Layer**
   - `TProxyService.kt` extends `VpnService` and manages:
     - Spawning the Xray-core process via Android linker
     - TUN interface setup via `ParcelFileDescriptor`
     - Integration with `hev-socks5-tunnel` for tun2socks
     - Process lifecycle (start/stop/reload)
     - Log streaming from Xray stdout/stderr

3. **UI Architecture**
   - Built with Jetpack Compose and Material3
   - MVVM pattern with ViewModels:
     - `MainViewModel`: Connection state, config management, stats collection
     - `ConfigEditViewModel`: Config editing and validation
     - `AppListViewModel`: Per-app proxy (split tunneling)
     - `LogViewModel`: Real-time log display
   - Navigation via Compose Navigation (`AppNavGraph.kt`, `BottomNavGraph.kt`)
   - Main screens: Dashboard, Config list, Settings, Logs, App list

4. **Configuration Format**
   - Native format: Full Xray JSON config
   - Supports `hyperxray://config/[name]/[base64-deflated-content]` custom URI scheme
   - Also supports `vless://` link import
   - Config converters in `app/src/main/kotlin/com/hyperxray/an/common/configFormat/`
   - Configs stored as individual JSON files in app's private directory

5. **Native Components**
   - **hev-socks5-tunnel**: Git submodule providing tun2socks functionality
     - Located at: `app/src/main/jni/hev-socks5-tunnel/`
     - Built via NDK with `Android.mk`
     - Bridges TUN interface to SOCKS5 proxy
   - Built with NDK for arm64-v8a and x86_64 only

6. **IPC and Stats**
   - `CoreStatsClient.kt` uses gRPC to query Xray stats API
   - Protobuf definitions auto-generated in build process
   - Service broadcasts log updates via `Intent` with action `ACTION_LOG_UPDATE`

## Build Commands

### Standard Build
```bash
# Sync and build (debug)
./gradlew assembleDebug

# Build release APKs (universal + per-ABI splits)
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### NDK Build
The NDK build runs automatically during Gradle build for:
- `hev-socks5-tunnel` native library
- `xray-wrapper` executable

### Important Build Details
- **Geo files auto-download**: `geoip.dat` and `geosite.dat` download automatically via `downloadGeoFiles` Gradle task
- **Xray binary**: Must be manually placed at `app/src/main/jniLibs/[abi]/libxray.so` (not in repo due to licensing)
- **xray-wrapper packaging**: The NDK-built `xray-wrapper` executable is automatically copied to `jniLibs` as `libxray-wrapper.so` (with `.so` extension) after NDK build, because Android only packages `.so` files
- **Split APKs**: Build produces `hyperxray-arm64-v8a.apk`, `hyperxray-x86_64.apk`, and `hyperxray-universal.apk`
- **Signing**: Configure `store.properties` in root for release signing

## Development Setup

1. Clone with submodules:
   ```bash
   git clone --recursive https://github.com/halibiram/HyperXray
   ```

2. Add Xray-core binaries (required for build):
   - Download from [XTLS/Xray-core releases](https://github.com/XTLS/Xray-core/releases) (current: v25.10.15)
   - Extract and place at:
     - `app/src/main/jniLibs/arm64-v8a/libxray.so`
     - `app/src/main/jniLibs/x86_64/libxray.so`

3. Environment requirements:
   - Android SDK API 35
   - NDK 28.2.13676358 (specified in `version.properties`)
   - Java 22 (configured in `gradle.properties`)
   - Gradle 8.8

## Code Structure

### Package Organization
- `com.hyperxray.an.activity`: MainActivity only
- `com.hyperxray.an.service`: TProxyService, QuickTileService
- `com.hyperxray.an.viewmodel`: All ViewModels and state classes
- `com.hyperxray.an.ui.screens`: Compose screen implementations
- `com.hyperxray.an.ui.navigation`: Navigation graphs
- `com.hyperxray.an.common`: Utilities, formatters, constants
- `com.hyperxray.an.common.configFormat`: Config import/export converters
- `com.hyperxray.an.data.source`: File management (configs, logs)
- `com.hyperxray.an.prefs`: SharedPreferences wrapper

### Key Files
- `TProxyService.kt`: Core proxy service implementation, process management
- `MainViewModel.kt`: Central state management, connection logic
- `SimpleXrayFormatConverter.kt`: Custom config sharing format
- `xray-wrapper.c`: Native wrapper that executes libxray.so via linker
- `version.properties`: Version tracking for app, Xray-core, geo files, NDK

## Testing Notes

- No automated test suite currently exists
- Manual testing via:
  - Building and installing APK
  - Testing connection with various config types
  - Checking logs in LogScreen for Xray output

## Important Patterns

### Starting/Stopping Xray
The service uses `ProcessBuilder` to execute `libxray-wrapper.so`, which then runs `libxray.so` via Android's linker. The process is managed in `TProxyService.kt` with:
- Process stdout/stderr piped to log display
- Config written to stdin (not as file) for security
- Process termination via `Process.destroy()`
- Note: Despite the `.so` extension, `libxray-wrapper.so` is an executable binary, not a shared library

### Config Management
- Configs are individual JSON files in `FileManager.getConfigDirectory()`
- Active config tracked in SharedPreferences (`prefs.configName`)
- Template-based config generation: base template merged with server-specific config

### State Flow
- UI state managed via `StateFlow<T>` in ViewModels
- Service state broadcast via Intent to MainActivity
- Stats polling happens when connected via gRPC to Xray stats API

## Build Artifacts

Output APKs located at:
- `app/build/outputs/apk/release/hyperxray-arm64-v8a.apk`
- `app/build/outputs/apk/release/hyperxray-x86_64.apk`
- `app/build/outputs/apk/release/hyperxray-universal.apk`
