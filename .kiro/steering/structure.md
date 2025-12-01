# HyperXray Project Structure

## Module Organization

```
HyperXray/
├── app/                          # Main Android application
│   ├── src/main/
│   │   ├── kotlin/com/hyperxray/an/
│   │   │   ├── activity/         # Activities (MainActivity)
│   │   │   ├── common/           # Shared utilities, config formats
│   │   │   ├── core/             # Core managers (XrayCoreManager)
│   │   │   ├── data/             # Repository pattern (data layer)
│   │   │   ├── domain/           # Use cases (domain layer)
│   │   │   ├── ml/               # ML models (TlsSniModel)
│   │   │   ├── optimizer/        # ONNX inference, on-device learning
│   │   │   ├── runtime/          # Runtime components (BanditRouter)
│   │   │   ├── service/          # Android services, handlers
│   │   │   ├── telemetry/        # AI optimizer, bandit algorithms
│   │   │   ├── ui/               # Compose UI (screens, components, theme)
│   │   │   ├── viewmodel/        # ViewModels (MVVM)
│   │   │   ├── vpn/              # VPN service (HyperVpnService)
│   │   │   └── xray/             # Xray runtime integration
│   │   ├── jni/                  # C/C++ JNI code (Android.mk)
│   │   ├── jniLibs/              # Native .so libraries (generated)
│   │   ├── proto/                # Protobuf definitions
│   │   ├── assets/               # Geo files, ONNX models
│   │   └── res/                  # Android resources
│   └── build.gradle
│
├── native/                       # Go native library (libhyperxray.so)
│   ├── bridge/                   # Go-Android bridge
│   │   ├── bridge.go             # Main tunnel implementation
│   │   ├── xray.go               # Xray wrapper
│   │   ├── xray_dialer.go        # Protected dialer
│   │   └── protector.go          # Socket protection
│   ├── dns/                      # DNS server/cache
│   ├── xray/                     # Xray config, instance management
│   ├── wireguard/                # WireGuard-Xray binding
│   ├── go.mod                    # Go module definition
│   └── lib.go                    # CGO exports
│
├── core/                         # Core library modules
│   ├── core-di/                  # Dependency injection
│   ├── core-network/             # Network utilities, gRPC
│   ├── core-database/            # Local database
│   ├── core-telemetry/           # Telemetry collection
│   └── core-designsystem/        # Design system, theme
│
├── feature/                      # Feature modules
│   ├── feature-dashboard/        # Main dashboard UI
│   ├── feature-profiles/         # Profile management
│   ├── feature-vless/            # VLESS protocol
│   ├── feature-reality/          # Reality protocol
│   ├── feature-hysteria2/        # Hysteria2 protocol
│   ├── feature-warp/             # Cloudflare WARP
│   ├── feature-telegram/         # Telegram bot integration
│   ├── feature-routing/          # Routing rules
│   └── feature-policy-ai/        # AI policy management
│
├── xray/
│   └── xray-runtime-service/     # Xray gRPC service, proto generation
│
├── baselineprofile/              # Baseline profile generation
├── scripts/                      # Build scripts (build-native.bat/sh)
├── tools/                        # Python tools (model training, log collection)
└── xray-patches/                 # Patches for Xray-core customization
```

## Architecture Patterns
- MVVM with ViewModels and Compose UI
- Repository pattern for data access
- Use case pattern for domain logic
- Multi-module Gradle project with feature modules
- Native Go library via CGO for Xray-core integration

## Key Files
- `app/build.gradle`: Main app configuration, native build tasks
- `native/go.mod`: Go dependencies (Xray-core, WireGuard)
- `settings.gradle`: Module includes
- `version.properties`: Version numbers (app, NDK, Go, geo files)
- `store.properties`: Signing configuration (not in repo)
