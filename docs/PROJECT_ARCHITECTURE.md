# HyperXray Project Architecture & Guidelines

## Project Overview

**Name:** HyperXray  
**Description:** Android VPN client integrating WireGuard and Xray-core using a native Go library bridged via JNI.  
**Architecture:** MVVM with Clean Architecture, Multi-module (feature/core layers)

### Critical Components

1. **HyperVpnService** (Kotlin - VPN Service)
   - Location: `app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt`
   - Manages VPN lifecycle, TUN interface, and native library integration

2. **libhyperxray.so** (Go - Native Logic)
   - Location: `native/lib.go`, `native/bridge/bridge.go`
   - Contains WireGuard and Xray-core integration logic

3. **hyperxray-jni.c** (C - JNI Bridge)
   - Location: `app/src/main/jni/hyperxray-jni/hyperxray-jni.c`
   - Bridges Kotlin ↔ Go communication via JNI

## Role: Senior Android Systems Engineer & Go/C++ Interop Specialist

### Capabilities

- Expert in Android VpnService and Socket Protection logic
- Proficient in JNI (Java Native Interface) memory management
- Specialist in Go (Golang) cgo integration with Android
- Deep understanding of Xray-core and WireGuard protocols

## Tech Stack

### Android
- Kotlin
- Jetpack Compose
- Hilt (Dependency Injection)
- Coroutines
- Room (Database)

### Native
- Go 1.23+
- C11
- JNI
- Android NDK

### Protocols
- WireGuard
- VLESS
- VMess
- Trojan
- Reality

## Coding Guidelines

### JNI Bridge Rules

#### Memory Management
- **ALWAYS** check for NULL pointers when converting Java strings to C strings (`GetStringUTFChars`)
- **ALWAYS** release memory using `ReleaseStringUTFChars` to prevent memory leaks in the native layer
- Ensure function signatures in `hyperxray-jni.c` exactly match the `native/lib.go` exports and Kotlin `external fun` definitions
- Handle panic recovery in Go functions exported to C to prevent crashing the entire Android process

#### Implementation Status
✅ **VERIFIED**: `hyperxray-jni.c` properly implements NULL checks and memory release:
- Lines 441-502: All string conversions check for NULL before use
- Lines 520-525: All strings are properly released after use
- Lines 607-613: Proper NULL checks and memory management in `nativeGeneratePublicKey`

### VPN Logic Rules

#### Socket Protection
- **CRITICAL**: All outbound sockets created in Go/Xray MUST be protected using `VpnService.protect()` to avoid VPN loops
- The `protect()` method must be injected from Kotlin → JNI → Go → Xray Dialer
- Verify `tunFd` validity before passing it to the native layer
- Use `AiLogHelper` for unified logging across Kotlin, C, and Go layers

#### Implementation Status
✅ **VERIFIED**: Socket protection is properly implemented:
- `HyperVpnService.kt` lines 302-312: `initSocketProtector()` called in `onCreate()`
- `hyperxray-jni.c` lines 1279-1310: `socket_protector_callback()` properly calls `VpnService.protect()`
- `hyperxray-jni.c` lines 1359-1411: `initSocketProtector()` sets up the callback chain
- `native/lib.go` lines 247-251: Verifies protector is set before starting tunnel
- `native/lib.go` lines 539-544: `SetSocketProtector()` registers the callback

### Kotlin Android Rules

- Use Coroutines for all background operations, never block the main thread
- Follow Clean Architecture: Data → Domain → Presentation
- Handle `DeadObjectException` when communicating with the VPN service

#### Implementation Status
✅ **VERIFIED**: Coroutines are used throughout:
- `HyperVpnService.kt` line 142: `serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())`
- All VPN operations use `serviceScope.launch { }` for async execution

## Memory and Stability

### Instructions

1. **If modifying `lib.go`**: Ensure `C.CString` allocations are freed using `C.free`
   - ✅ **VERIFIED**: `native/lib.go` lines 104-115, 122-133: All `C.CString` allocations use `defer C.free(unsafe.Pointer(...))`

2. **In `HyperVpnService.kt`**: Ensure the native library is loaded exactly once
   - ✅ **VERIFIED**: `HyperVpnService.kt` lines 86-120: Library loading is done in `companion object init` block with proper error handling

3. **Watch out for `fatal error: fault` in Go logs**: This usually indicates a nil pointer dereference in the native bridge
   - ✅ **MITIGATED**: `native/lib.go` lines 226-241: Panic recovery is implemented in all exported functions

## Known Issues to Avoid

### Socket Loop
**Issue:** Xray-core establishing connections that loop back into the Tun interface  
**Fix:** Ensure Dialer hooks call `protect()`  
**Status:** ✅ **FIXED** - Socket protector is initialized before tunnel start and verified in Go code

### Read Loop Closed Pipe
**Issue:** Xray connection closing immediately after open  
**Fix:** Check outbound routing and socket protection  
**Status:** ✅ **MITIGATED** - Socket protection is properly set up, and TUN fd validation is performed

### JNI Crash
**Issue:** Segmentation faults during config parsing  
**Fix:** Validate JSON strings before passing to Go  
**Status:** ✅ **FIXED** - `hyperxray-jni.c` validates all string parameters and `native/lib.go` validates configs before parsing

## Code Quality Checklist

### JNI Bridge (`hyperxray-jni.c`)
- [x] NULL pointer checks for all `GetStringUTFChars` calls
- [x] All strings released with `ReleaseStringUTFChars`
- [x] Function signatures match Kotlin `external fun` declarations
- [x] Error handling for all JNI operations

### VPN Service (`HyperVpnService.kt`)
- [x] Native library loaded exactly once
- [x] Socket protector initialized before any network operations
- [x] TUN fd validated before passing to native layer
- [x] Coroutines used for all background operations
- [x] Proper error handling and recovery mechanisms

### Go Native Library (`native/lib.go`, `native/bridge/bridge.go`)
- [x] Panic recovery in all exported functions
- [x] `C.CString` allocations freed with `C.free`
- [x] Socket protector verified before tunnel start
- [x] TUN fd validation before use
- [x] Proper error codes returned to Kotlin layer

## Architecture Flow

```
┌─────────────────┐
│  HyperVpnService│ (Kotlin)
│  (VPN Service)  │
└────────┬────────┘
         │ JNI
         ▼
┌─────────────────┐
│ hyperxray-jni.c │ (C - JNI Bridge)
│  (JNI Wrapper)  │
└────────┬────────┘
         │ dlopen/dlsym
         ▼
┌─────────────────┐
│ libhyperxray.so │ (Go)
│  (Native Logic) │
└────────┬────────┘
         │
         ├─► WireGuard (via golang.zx2c4.com/wireguard)
         └─► Xray-core (embedded in Go binary)
```

## Socket Protection Flow

```
Kotlin (HyperVpnService)
  │
  │ initSocketProtector()
  ▼
JNI (hyperxray-jni.c)
  │
  │ g_protector = socket_protector_callback
  │ go_SetSocketProtector(socket_protector_callback)
  ▼
Go (lib.go)
  │
  │ bridge.SetSocketProtector(goSocketProtector)
  ▼
Xray Dialer
  │
  │ protect(fd) called for all outbound sockets
  ▼
VpnService.protect(fd)
```

## Best Practices

1. **Always validate inputs** before passing to native layer
2. **Always release JNI resources** (strings, references) after use
3. **Always handle panics** in Go code exported to C
4. **Always protect sockets** before use in Xray/Go code
5. **Always verify TUN fd** validity before passing to native layer
6. **Use unified logging** (`AiLogHelper`) across all layers

## Testing Checklist

- [ ] Test VPN connection with valid WireGuard config
- [ ] Test VPN connection with valid Xray config
- [ ] Test socket protection (verify no VPN loops)
- [ ] Test TUN fd validation (invalid fd should fail gracefully)
- [ ] Test memory leaks (JNI string release)
- [ ] Test panic recovery (Go panic should not crash app)
- [ ] Test error handling (invalid configs should return proper error codes)

## References

- [Android VpnService Documentation](https://developer.android.com/reference/android/net/VpnService)
- [JNI Best Practices](https://developer.android.com/training/articles/perf-jni)
- [Go CGO Documentation](https://pkg.go.dev/cmd/cgo)
- [WireGuard Go Implementation](https://git.zx2c4.com/wireguard-go)
- [Xray-core Documentation](https://xtls.github.io/)

