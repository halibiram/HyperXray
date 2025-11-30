# gRPC Architecture and Flow

This document explains how gRPC stats and logs flow from Xray-core to Android.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Android (Kotlin) Layer                       │
├─────────────────────────────────────────────────────────────────┤
│  XrayStatsManager                                               │
│    ├─ isXrayGrpcAvailableNative() ───────────────┐              │
│    ├─ getXraySystemStatsNative() ───────────────┤              │
│    └─ getXrayTrafficStatsNative() ──────────────┤              │
│                                                   │              │
│  XrayLogManager                                   │              │
│    └─ getXrayLogsNative() ───────────────────────┼──────────────┤
│                                                   │              │
└───────────────────────────────────────────────────┼──────────────┘
                                                     │
                                    JNI Bridge (C)   │
                                                     │
┌───────────────────────────────────────────────────┼──────────────┐
│                    Native (Go) Layer               │              │
├───────────────────────────────────────────────────┼──────────────┤
│  native/lib.go                                     │              │
│    ├─ IsXrayGrpcAvailable() ──────────────────────┘              │
│    ├─ GetXraySystemStats()                                        │
│    ├─ GetXrayTrafficStats()                                       │
│    └─ GetXrayLogs() ──────────────────────────────────────────────┼─┐
│                                                                   │ │
│  bridge/bridge.go                                                │ │
│    └─ HyperTunnel                                                │ │
│         └─ GetXrayInstance() ────────────────────┐               │ │
│                                                   │               │ │
│  bridge/xray.go                                   │               │ │
│    └─ XrayWrapper                                 │               │ │
│         ├─ Start()                                 │               │ │
│         │   └─ Creates gRPC client ───────────────┼───────────────┼─┤
│         │       └─ NewXrayGrpcClient()           │               │ │
│         │           └─ Stores in x.grpcClient    │               │ │
│         └─ GetGrpcClient() ──────────────────────┘               │ │
│                                                                   │ │
│  bridge/xray_grpc.go                                              │ │
│    └─ XrayGrpcClient                                              │ │
│         ├─ GetSystemStats() ──────────────────────┐             │ │
│         └─ QueryTrafficStats()                      │             │ │
│                                                      │             │ │
│  bridge/xray.go (Log Channel)                       │             │ │
│    └─ XrayLogWriter (implements log.Handler)        │             │ │
│         └─ Handle() ────────────────────────────────┼─────────────┘ │
│             └─ Sends to XrayLogChannel ─────────────┘               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                                                                      
┌─────────────────────────────────────────────────────────────────────┐
│                    Xray-core (Go)                                   │
├─────────────────────────────────────────────────────────────────────┤
│  core.Instance                                                      │
│    ├─ Start() ── Starts gRPC server on configured API port          │
│    └─ Emits logs via log.Handler interface                         │
│         └─ XrayLogWriter.Handle() receives all logs                 │
└─────────────────────────────────────────────────────────────────────┘
```

## Lifecycle Flow

### 1. Tunnel Startup

1. **HyperTunnel.Start()** is called
2. **XrayWrapper.Start()** is called:
   - `x.instance.Start()` starts Xray-core
   - Xray-core starts gRPC server on configured API port
   - Wait 1 second for gRPC server to be ready
   - `NewXrayGrpcClient(apiPort)` creates gRPC client
   - Store client in `x.grpcClient` (with mutex protection)
   - Verify connection with `GetSystemStats()`

### 2. gRPC Availability Check

1. **Kotlin:** `XrayStatsManager.isXrayGrpcAvailableNative()` is called
2. **JNI:** Calls Go `IsXrayGrpcAvailable()`
3. **Go:** 
   - Locks `tunnelLock`
   - Checks if `tunnel != nil`
   - Calls `tunnel.GetXrayInstance()`
   - Calls `xrayInstance.GetGrpcClient()`
   - Returns `true` if all checks pass

### 3. Stats Collection

1. **Kotlin:** `XrayStatsManager.getXraySystemStatsNative()` is called
2. **JNI:** Calls Go `GetXraySystemStats()`
3. **Go:**
   - Gets `xrayInstance` from tunnel
   - Gets `grpcClient` from xrayInstance
   - Calls `grpcClient.GetSystemStats()`
   - Returns JSON with stats

### 4. Log Collection

1. **Xray-core** emits log via `log.Handler` interface
2. **XrayLogWriter.Handle()** receives log message
3. Log is sent to `XrayLogChannel` (buffered channel, size 1000)
4. **Kotlin:** `XrayLogManager.getXrayLogsNative()` is called periodically
5. **JNI:** Calls Go `GetXrayLogs()`
6. **Go:** Reads from `XrayLogChannel` (non-blocking, up to maxCount)
7. Returns JSON array of logs

## Key Components

### HyperTunnel
- Main tunnel instance
- Manages XrayWrapper lifecycle
- Provides `GetXrayInstance()` to access XrayWrapper

### XrayWrapper
- Manages Xray-core instance
- Creates and stores gRPC client in `Start()`
- Thread-safe access via mutex (`x.mu`)
- Provides `GetGrpcClient()` for stats queries

### XrayGrpcClient
- Wraps gRPC connection to Xray-core StatsService
- Handles connection state management
- Provides `GetSystemStats()` and `QueryTrafficStats()`

### XrayLogWriter
- Implements `log.Handler` interface
- Registered globally in `init()`
- Receives all Xray-core logs
- Forwards logs to `XrayLogChannel` for Android retrieval

### XrayLogChannel
- Buffered channel (size 1000)
- Non-blocking writes (drops oldest if full)
- Non-blocking reads (returns available logs)

## Thread Safety

- **HyperTunnel:** Protected by `tunnelLock` in `native/lib.go`
- **XrayWrapper:** Protected by `x.mu` mutex
- **XrayLogChannel:** Protected by `XrayLogChannelMu` mutex
- **gRPC Client:** Access via `GetGrpcClient()` which uses mutex

## Timing Considerations

1. **gRPC Server Startup:** Xray-core needs ~1 second after `instance.Start()` to start gRPC server
2. **Grace Period:** Kotlin side allows 10 seconds for gRPC to become available before marking as failed
3. **Connection Verification:** gRPC client waits up to 2 seconds for connection to be ready

## Error Handling

1. **gRPC Client Creation Failure:** Logged as warning, tunnel continues without stats
2. **gRPC Connection Failure:** Retried on first use, falls back to CoreStatsClient if needed
3. **Log Channel Full:** Oldest logs are dropped to make room for new ones
4. **Log Channel Closed:** Returns error in JSON, Kotlin side handles gracefully

## State Machine (Kotlin)

```
LIB_NOT_LOADED → TUNNEL_NOT_RUNNING → GRPC_NOT_READY_YET → GRPC_AVAILABLE
                                                              ↓
                                                         GRPC_FAILED
```

- **LIB_NOT_LOADED:** Native library not loaded
- **TUNNEL_NOT_RUNNING:** No active tunnel
- **GRPC_NOT_READY_YET:** Tunnel running, gRPC not available yet (within 10s grace period)
- **GRPC_AVAILABLE:** gRPC is available and working
- **GRPC_FAILED:** gRPC failed after grace period

