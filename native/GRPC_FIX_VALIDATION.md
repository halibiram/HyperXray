# gRPC Availability Fix - Validation Steps

This document describes how to validate that the gRPC availability fix is working correctly.

## Expected Behavior After Fix

### 1. When Tunnel Starts

**Go Side Logs:**
```
[Xray] Initializing gRPC client for API port 10000...
[Xray] ✅ gRPC client created and stored successfully (port: 10000)
[Xray] ✅ Verified: GetGrpcClient() returns non-nil client
[Xray] ✅ gRPC connection verified - uptime=Xs, goroutines=Y, numGC=Z
```

**Kotlin Side Logs:**
```
XrayStatsManager: Checking native gRPC availability...
XrayStatsManager: ✅ Native gRPC available, state: GRPC_AVAILABLE
XrayStatsManager: Using native gRPC client for stats
XrayStatsManager: Native stats received - systemStats: true, trafficStats: true
```

### 2. When gRPC is Not Ready Yet (Grace Period)

**Kotlin Side Logs:**
```
XrayStatsManager: ⏳ gRPC not ready yet, starting grace period (state: GRPC_NOT_READY_YET)
XrayStatsManager: ⏳ gRPC not ready yet, grace period: 8500ms remaining (state: GRPC_NOT_READY_YET)
```

**Expected:** Stats manager will retry for 10 seconds before marking as failed.

### 3. When gRPC Fails After Grace Period

**Kotlin Side Logs:**
```
XrayStatsManager: ⚠️ gRPC failed after grace period (15000ms > 10000ms), state: GRPC_FAILED
XrayStatsManager: ⚠️ Native process not available (state: GRPC_FAILED) - invalidating client
```

**Expected:** Client is invalidated and fallback to CoreStatsClient is attempted.

### 4. Diagnostic Logs from IsXrayGrpcAvailable

**Go Side Logs (when checking availability):**
```
[IsXrayGrpcAvailable] tunnel exists, checking xrayInstance...
[IsXrayGrpcAvailable] xrayInstance exists, checking grpcClient...
[IsXrayGrpcAvailable] grpcClient is nil - returning false
```

OR (when successful):
```
[IsXrayGrpcAvailable] tunnel exists, checking xrayInstance...
[IsXrayGrpcAvailable] xrayInstance exists, checking grpcClient...
[IsXrayGrpcAvailable] ✅ All checks passed - tunnel, xrayInstance, and grpcClient all exist - returning true
```

## Validation Commands

### 1. Check gRPC Availability Logs

```bash
adb logcat | grep -E "(IsXrayGrpcAvailable|XrayStatsManager|gRPC)"
```

**Expected Output:**
- `IsXrayGrpcAvailable` logs showing which check passes/fails
- `XrayStatsManager` logs showing state transitions
- gRPC client creation and verification logs

### 2. Check Go Runtime Stats

```bash
adb logcat | grep -E "(GetXraySystemStats|Go runtime stats)"
```

**Expected Output:**
- `GetXraySystemStats: Successfully retrieved stats: uptime=X, goroutines=Y`
- Non-zero values for `alloc`, `totalAlloc`, `sys`, etc.

### 3. Check Log Channel Status

```bash
adb logcat | grep -E "(GetXrayLogs|XrayLogManager|XrayLogWriter)"
```

**Expected Output:**
- `XrayLogWriter: Handler called (count: X)` - confirms handler is receiving logs
- `XrayLogManager: Received X logs from native channel` - confirms logs are being retrieved
- `GetXrayLogs: Channel has X logs available` - confirms channel has data

### 4. Monitor State Transitions

```bash
adb logcat | grep -E "(state:|NativeAvailabilityState|GRPC_)"
```

**Expected Output:**
- State transitions: `LIB_NOT_LOADED` → `GRPC_NOT_READY_YET` → `GRPC_AVAILABLE`
- Or: `GRPC_NOT_READY_YET` → `GRPC_FAILED` (if gRPC doesn't become available)

## Success Criteria

1. **gRPC Availability:**
   - `isXrayGrpcAvailableNative()` returns `true` within 10 seconds of tunnel start
   - `IsXrayGrpcAvailable` logs show all checks passing

2. **Go Runtime Stats:**
   - `getXraySystemStatsNative()` returns non-zero values for:
     - `alloc` (memory allocated)
     - `totalAlloc` (total memory allocated)
     - `sys` (system memory)
     - `numGoroutine` (number of goroutines)
     - `numGC` (number of GC cycles)

3. **Log Channel:**
   - `getXrayLogsNative()` returns logs when Xray-core emits them
   - `XrayLogWriter` handler is called and logs are forwarded to channel

4. **State Machine:**
   - State transitions correctly from `GRPC_NOT_READY_YET` to `GRPC_AVAILABLE`
   - Grace period (10 seconds) is respected before marking as failed

## Troubleshooting

### If gRPC is Still Not Available

1. **Check API Port Configuration:**
   ```bash
   adb logcat | grep -E "(API port|apiPort|parseApiPort)"
   ```
   - Should show: `[Xray] ✅ Parsed API port from config: 10000` (or your configured port)

2. **Check gRPC Client Creation:**
   ```bash
   adb logcat | grep -E "(XrayGrpc|gRPC client)"
   ```
   - Should show: `[XrayGrpc] ✅ gRPC client created for 127.0.0.1:10000`

3. **Check GetGrpcClient() Calls:**
   ```bash
   adb logcat | grep "GetGrpcClient"
   ```
   - Should show: `[Xray] GetGrpcClient() returning non-nil client`

### If Logs Are Still Empty

1. **Check XrayLogWriter Registration:**
   ```bash
   adb logcat | grep "XrayLogWriter"
   ```
   - Should show: `[XrayLogWriter] ✅ Registered globally in package init`

2. **Check Handler Calls:**
   ```bash
   adb logcat | grep "Handler called"
   ```
   - Should show: `[XrayLogWriter] Handler called (count: X)` for first 10 logs

3. **Check Channel Status:**
   ```bash
   adb logcat | grep "GetXrayLogs"
   ```
   - Should show: `[GetXrayLogs] Channel has X logs available` when logs exist

## Notes

- The grace period (10 seconds) allows time for Xray-core's gRPC service to start after `instance.Start()` is called
- If gRPC is not available after grace period, the system falls back to `CoreStatsClient` (Kotlin-side gRPC client)
- Log channel is initialized in `init()` and should be ready before any logs are emitted
- All diagnostic logs use `logDebug()` to avoid spam in production, but can be enabled for troubleshooting

