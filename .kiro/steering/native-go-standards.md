---
inclusion: fileMatch
fileMatchPattern: '*.go|go.mod|go.sum|Android.mk|*.c|*.h'
---

# Native Go & JNI Standards

## Go Code Style
- Follow `gofmt` and `go vet` standards
- Use meaningful package names
- Keep functions small and focused
- Document exported functions with godoc comments
- Handle errors explicitly, never ignore them

## CGO/JNI Guidelines
- Minimize CGO boundary crossings (expensive)
- Use `//export` for functions called from Java/Kotlin
- Always check for null pointers from JNI
- Release JNI references properly to avoid memory leaks
- Use `C.CString` carefully, always free with `C.free`

## Memory Management
```go
// Always free C strings
cstr := C.CString(goString)
defer C.free(unsafe.Pointer(cstr))

// Use sync.Pool for frequently allocated objects
var bufferPool = sync.Pool{
    New: func() interface{} {
        return make([]byte, 4096)
    },
}
```

## Xray-core Integration
- Use `xray.core` package for instance management
- Configure via JSON or protobuf
- Handle connection lifecycle properly
- Implement proper shutdown with context cancellation

## Socket Protection
- All sockets must be protected via VpnService.protect()
- Use the protector callback from Android
- Handle protection failures gracefully

## Build Configuration
- Target architectures: arm64-v8a, armeabi-v7a, x86_64
- Use `-ldflags="-s -w"` for smaller binaries
- Enable CGO with proper NDK toolchain
- Test on all target architectures before release

## Native Build Commands
```bash
# Build native Go library (recommended - uses Gradle task)
.\gradlew buildNativeGo

# Alternative: Direct script (requires NDK_HOME env var)
set NDK_HOME=C:\Users\halil\AppData\Local\Android\Sdk\ndk\28.2.13676358
scripts\build-native.bat
```

## WireGuard Fork (CRITICAL)
The project uses a patched WireGuard-go fork at `native/wireguard-go-fork/` with extended rekey timers:
- `RekeyAfterTime`: 2 hours (default: 120s)
- `RejectAfterTime`: 3 hours (default: 180s)

**Important rules:**
1. NEVER delete or modify `native/wireguard-go-fork/` without understanding the rekey patch
2. Timer constants are in `native/wireguard-go-fork/device/constants.go`
3. The `go.mod` has a `replace` directive pointing to this fork
4. If you see nested `wireguard-go-fork/wireguard-go-fork/` folders, delete the inner one - it's a setup script bug
5. After any WireGuard fork changes, MUST rebuild native library with `.\gradlew buildNativeGo`

## NDK Configuration
- NDK path: `C:\Users\halil\AppData\Local\Android\Sdk\ndk\28.2.13676358`
- The Gradle task `buildNativeGo` auto-detects NDK from `local.properties` or environment
- If build fails with "NDK not found", set `NDK_HOME` environment variable

## Debugging
- Use `log.Printf` for Go-side logging
- Check `adb logcat -s GoLog:*` for output
- Use delve for remote debugging when needed
- Profile with `pprof` for performance issues

## Common Pitfalls
- Don't pass Go pointers to C that outlive the call
- Avoid goroutine leaks in long-running operations
- Handle JNI exceptions before returning to Java
- Test with race detector: `go test -race`
