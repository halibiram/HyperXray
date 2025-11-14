# HyperXray Architecture

## Overview

HyperXray is an Android VPN proxy client built on Xray-core. The key architectural innovation is that **Xray-core runs as a separate child process** (not via JNI), which provides better stability and isolation from the main app.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Android App Layer                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    UI Layer (Compose)                     │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │  │
│  │  │Dashboard │  │  Config   │  │  Logs    │  │Settings │ │  │
│  │  │  Screen  │  │  Screen   │  │  Screen  │  │ Screen  │ │  │
│  │  └────┬─────┘  └────┬──────┘  └────┬─────┘  └────┬─────┘ │  │
│  └───────┼──────────────┼──────────────┼────────────┼───────┘  │
│          │              │              │            │           │
│  ┌───────▼──────────────▼──────────────▼────────────▼───────┐  │
│  │              ViewModel Layer (MVVM)                       │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐        │  │
│  │  │MainViewModel│  │LogViewModel│  │ConfigEditVM│        │  │
│  │  └─────┬───────┘  └─────┬──────┘  └─────┬──────┘        │  │
│  └────────┼────────────────┼────────────────┼───────────────┘  │
│           │                │                │                    │
│  ┌────────▼────────────────▼────────────────▼───────────────┐  │
│  │              Service Layer                                 │  │
│  │  ┌────────────────────────────────────────────────────┐ │  │
│  │  │           TProxyService (VpnService)                 │ │  │
│  │  │  • Manages Xray-core process lifecycle               │ │  │
│  │  │  • TUN interface setup                                │ │  │
│  │  │  • Log streaming & broadcasting                       │ │  │
│  │  │  • Stats collection via gRPC                          │ │  │
│  │  └───────────────┬──────────────────────────────────────┘ │  │
│  └──────────────────┼──────────────────────────────────────┘  │
│                     │                                           │
└─────────────────────┼───────────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────────┐
│                    Native Process Layer                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  ProcessBuilder → /system/bin/linker64                     │ │
│  │              ↓                                              │ │
│  │  libxray-wrapper.so (executable, renamed from xray-wrapper)│ │
│  │              ↓                                              │ │
│  │  libxray.so (Xray-core binary as shared library)           │ │
│  │              ↓                                              │ │
│  │  Xray-core Process (separate process)                       │ │
│  └───────────────────┬────────────────────────────────────────┘ │
│                      │                                           │
│  ┌───────────────────▼────────────────────────────────────────┐ │
│  │  hev-socks5-tunnel (tun2socks)                               │ │
│  │  • Bridges TUN interface to SOCKS5 proxy                     │ │
│  │  • Built via NDK (Android.mk)                                │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    AI Optimizer Layer                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Telemetry & AI Components                     │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │  │DeepPolicyModel│  │RealityBandit │  │OptimizerOrch │   │  │
│  │  │  (ONNX)      │  │  (MAB)       │  │  (Coordinator)│   │  │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │  │
│  │         │                │                  │            │  │
│  │  ┌──────▼─────────────────▼──────────────────▼───────┐   │  │
│  │  │         TProxyAiOptimizer / XrayCoreAiOptimizer    │   │  │
│  │  │  • Collects metrics from Xray stats API           │   │  │
│  │  │  • Runs ONNX inference                             │   │  │
│  │  │  • Applies optimized configuration                │   │  │
│  │  └───────────────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              TLS SNI Optimizer v5                          │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │  │TlsSniModel   │  │BanditRouter │  │FeedbackManager│   │  │
│  │  │  (ONNX)      │  │  (MAB)      │  │  (Metrics)   │   │  │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │  │
│  │         │                │                  │            │  │
│  │  ┌──────▼─────────────────▼──────────────────▼───────┐   │  │
│  │  │         TlsRuntimeWorker (WorkManager)              │   │  │
│  │  │  • Periodic SNI analysis (15-min intervals)        │   │  │
│  │  │  • Updates routing decisions                        │   │  │
│  │  │  • Triggers Xray hot-reload                         │   │  │
│  │  └───────────────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

## Component Details

### 1. UI Layer (Jetpack Compose + Material3)

**Location**: `app/src/main/kotlin/com/hyperxray/an/ui/`

- **Screens**:
  - `DashboardScreen`: Connection status, stats, quick actions
  - `ConfigScreen`: List and manage Xray configurations
  - `ConfigEditScreen`: Edit/create configurations
  - `LogScreen`: Real-time log display with filtering
  - `SettingsScreen`: App preferences and settings
  - `AppListScreen`: Per-app proxy (split tunneling)
  - `OptimizerScreen`: AI optimizer status and controls
  - `AiInsightsScreen`: AI learner state and feedback

- **Navigation**: 
  - `AppNavGraph.kt`: Main navigation graph
  - `BottomNavGraph.kt`: Bottom navigation bar

- **Theme**: `ExpressiveTheme.kt` with Material3 theming

### 2. ViewModel Layer (MVVM Pattern)

**Location**: `app/src/main/kotlin/com/hyperxray/an/viewmodel/`

- **MainViewModel**: 
  - Connection state management
  - Config file management
  - Stats collection and aggregation
  - Service lifecycle coordination

- **LogViewModel**: 
  - Real-time log buffering and display
  - Log filtering and parsing

- **ConfigEditViewModel**: 
  - Config editing and validation
  - Import/export handling

- **AppListViewModel**: 
  - Per-app proxy management
  - Split tunneling configuration

- **AiInsightsViewModel**: 
  - AI optimizer state display
  - Feedback and policy visualization

### 3. Service Layer

**Location**: `app/src/main/kotlin/com/hyperxray/an/service/`

- **TProxyService** (extends `VpnService`):
  - Spawns Xray-core process via Android linker
  - Manages TUN interface via `ParcelFileDescriptor`
  - Integrates `hev-socks5-tunnel` for tun2socks
  - Streams logs from Xray stdout/stderr
  - Broadcasts log updates via Intent
  - Manages process lifecycle (start/stop/reload)
  - Coordinates with AI optimizers

- **QuickTileService**: Quick settings tile integration

### 4. Native Process Execution

**Key Innovation**: Xray-core runs as a separate child process, not via JNI.

**Process Flow**:
1. `TProxyService` uses `ProcessBuilder` to execute `/system/bin/linker64`
2. Linker loads `libxray-wrapper.so` (executable binary, renamed from `xray-wrapper`)
3. Wrapper executes `libxray.so` (Xray-core binary packaged as shared library)
4. Xray-core runs as independent process with its own memory space

**Files**:
- `app/src/main/jni/xray-wrapper.c`: Native wrapper executable
- `app/src/main/jniLibs/[abi]/libxray-wrapper.so`: Built wrapper (renamed to .so for Android packaging)
- `app/src/main/jniLibs/[abi]/libxray.so`: Xray-core binary (external, not in repo)

### 5. Native Components

**hev-socks5-tunnel**:
- Git submodule at `app/src/main/jni/hev-socks5-tunnel/`
- Built via NDK with `Android.mk`
- Provides tun2socks functionality
- Bridges TUN interface to SOCKS5 proxy

### 6. Configuration Management

**Location**: `app/src/main/kotlin/com/hyperxray/an/common/configFormat/`

- **Formats Supported**:
  - Native: Full Xray JSON config
  - Custom URI: `hyperxray://config/[name]/[base64-deflated-content]`
  - VLESS links: `vless://` import

- **Converters**:
  - `SimpleXrayFormatConverter`: Custom HyperXray format
  - `VlessLinkConverter`: VLESS link parsing
  - `HyperXrayFormatConverter`: Enhanced format with metadata

- **Storage**: Individual JSON files in app's private directory

### 7. Data Layer

**Location**: `app/src/main/kotlin/com/hyperxray/an/data/source/`

- **FileManager**: 
  - Config file operations
  - Geo file (geoip.dat, geosite.dat) management
  - Import/export with compression

- **LogFileManager**: 
  - Log file persistence
  - Log rotation and cleanup

- **StatFileManager**: 
  - Statistics persistence

### 8. AI Optimizer Components

**Location**: `app/src/main/kotlin/com/hyperxray/an/telemetry/`

**Core Components**:
- **DeepPolicyModel**: ONNX model inference for server selection
- **RealityBandit**: Multi-armed bandit algorithm for server selection
- **OptimizerOrchestrator**: Coordinates bandit and deep model
- **TProxyAiOptimizer**: TProxy-specific optimization
- **XrayCoreAiOptimizer**: Xray-core configuration optimization

**TLS SNI Optimizer v5**:
- **Location**: `app/src/main/kotlin/com/hyperxray/an/ml/`, `runtime/`, `optimizer/`
- **TlsSniModel**: ONNX model for SNI-based routing decisions
- **BanditRouter**: Epsilon-greedy multi-armed bandit for routing
- **FeedbackManager**: Metrics collection and adaptive thresholds
- **RealityAdvisor**: Profile and policy generation
- **TlsRuntimeWorker**: Periodic WorkManager job for optimization

### 9. IPC and Communication

- **gRPC**: `CoreStatsClient.kt` queries Xray stats API
- **Protobuf**: Auto-generated from `app/src/main/proto/`
- **Intents**: Service broadcasts log updates with `ACTION_LOG_UPDATE`
- **SharedPreferences**: User preferences via `Preferences.kt`

### 10. Utilities

**Location**: `app/src/main/kotlin/com/hyperxray/an/common/`

- **Formatter.kt**: Byte/number/uptime/throughput formatting
- **ConfigUtils.kt**: Configuration manipulation utilities
- **FilenameValidator.kt**: Filename validation
- **AiLogHelper.kt**: AI log capture and broadcasting
- **ValidationUtils.kt**: Input validation helpers

## Data Flow

### Connection Flow

1. User taps connect in UI
2. `MainViewModel` sends Intent to `TProxyService`
3. `TProxyService`:
   - Sets up TUN interface
   - Spawns Xray-core process via linker
   - Starts `hev-socks5-tunnel`
   - Begins log streaming
4. Service broadcasts connection state to UI
5. `MainViewModel` starts stats polling via gRPC
6. UI updates with real-time stats

### AI Optimization Flow

1. `TProxyService` starts AI optimizers on connection
2. Optimizers collect metrics from Xray stats API
3. ONNX models run inference on collected metrics
4. Optimizers generate optimized configuration
5. Configuration applied to Xray-core (hot-reload)
6. Feedback collected and used for learning

### Log Flow

1. Xray-core writes to stdout/stderr
2. `TProxyService` reads process output
3. Logs buffered and broadcasted via Intent
4. `LogViewModel` receives broadcasts
5. UI displays logs in real-time
6. Logs also persisted to file via `LogFileManager`

## Package Structure

```
com.hyperxray.an/
├── activity/          # MainActivity
├── service/           # TProxyService, QuickTileService
├── viewmodel/         # All ViewModels
├── ui/
│   ├── screens/       # Compose screens
│   ├── navigation/    # Navigation graphs
│   ├── scaffold/      # AppScaffold
│   └── theme/         # Theming
├── common/            # Utilities, formatters, constants
│   └── configFormat/  # Config converters
├── data/
│   └── source/        # File management
├── prefs/             # SharedPreferences wrapper
├── telemetry/         # AI optimizer components
├── ml/                # ML models (TlsSniModel)
├── runtime/           # Runtime components (BanditRouter, etc.)
├── optimizer/         # Optimizer components
├── core/              # Core inference and monitoring
└── workers/           # WorkManager workers
```

## Build System

- **Gradle**: 8.8
- **Kotlin**: 2.2.21
- **Android SDK**: API 35
- **NDK**: 28.2.13676358
- **Java**: 22

**Key Build Tasks**:
- `downloadGeoFiles`: Downloads geoip.dat and geosite.dat
- `trainOnnxModel`: Trains ONNX policy model (optional)
- NDK build: Automatically builds native components

## Dependencies

**Core**:
- Jetpack Compose + Material3
- Navigation Compose
- gRPC + Protobuf
- ONNX Runtime

**UI**:
- LazyColumnScrollbar
- Reorderable lists

**Background**:
- WorkManager (for periodic optimization tasks)

## Security Considerations

- Config written to stdin (not file) for security
- Xray-core runs in isolated process
- No PII in telemetry
- SNI redaction in logs
- Placeholder validation before use

## Notes

- All drawable resources are actively used
- Package naming standardized to `com.hyperxray.an`
- No unused dependencies (cleaned in Phase 1)
- Architecture supports future modularization

