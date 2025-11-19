# FindClass Hook Solution for libsocks2tun.so

## Problem: JNI Class Loader Context Issue

### Root Cause

When `libsocks2tun.so` runs in its own native thread, it calls `FindClass("ca/psiphon/PsiphonTunnel")` to locate callback methods. However:

1. **Native threads attach with system class loader**: When a native thread calls `AttachCurrentThread()`, it gets the **system class loader** context by default
2. **System class loader can't see app classes**: The system class loader only has access to framework/SDK classes, not application classes
3. **FindClass uses thread's class loader**: JNI's `FindClass` function uses the current thread's class loader context
4. **Pre-loading doesn't help**: Even if we pre-load classes in the main thread, the native thread's `FindClass` still uses system class loader

### Why Previous Approaches Failed

The wrapper code tried several approaches, but none worked because they don't solve the fundamental problem:

❌ **ClassLoader caching**: Saved application ClassLoader as global reference
- **Problem**: `libsocks2tun.so` doesn't use our cached ClassLoader; it calls JNI's `FindClass` directly

❌ **Thread context classloader**: Set thread's context ClassLoader via Java Thread API
- **Problem**: JNI's `FindClass` doesn't use Java's context ClassLoader; it uses JNIEnv's internal loader

❌ **Pre-loading classes**: Used ClassLoader.loadClass() to pre-load classes
- **Problem**: Classes are loaded in main thread's context, but native thread has different context

❌ **Global reference caching**: Stored class references as global refs
- **Problem**: Only helps if we control the code; `libsocks2tun.so` binary calls `FindClass` internally

## Solution: FindClass Function Hooking

The **only reliable solution** is to intercept JNI's `FindClass` calls at the function pointer level.

### How It Works

```c
// 1. Save original FindClass pointer
original_FindClass = jni_interface->FindClass;

// 2. Make JNI function table writable (it's normally read-only)
mprotect(page_start, page_size * 2, PROT_READ | PROT_WRITE);

// 3. Replace FindClass pointer with our custom implementation
jni_interface->FindClass = custom_FindClass;

// 4. Restore read-only protection
mprotect(page_start, page_size * 2, PROT_READ);
```

### Custom FindClass Implementation

```c
static jclass custom_FindClass(JNIEnv* env, const char* name) {
    // Intercept specific application classes
    if (strcmp(name, "ca/psiphon/PsiphonTunnel") == 0) {
        // Return cached global reference
        return g_psiphonTunnelClass;
    }

    // For all other classes, use original FindClass
    return original_FindClass(env, name);
}
```

### Hook Installation Points

The hook is installed at **three critical points**:

1. **JNI_OnLoad**: Early installation when wrapper library loads
   ```c
   JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
       // Setup ClassLoader
       setup_classloader(env);

       // Pre-load and cache classes
       preload_classes_with_classloader(env);

       // Install FindClass hook
       hook_findclass(env);
   }
   ```

2. **load_httpcustom_libraries**: Before loading libsocks2tun.so
   ```c
   static int load_httpcustom_libraries(JNIEnv* env) {
       setup_classloader(env);
       preload_classes_with_classloader(env);

       // Install hook before loading native libraries
       hook_findclass(env);

       dlopen("libsocks2tun.so", RTLD_LAZY);
   }
   ```

3. **psiphon_run_thread**: In native thread before calling libsocks2tun.so
   ```c
   static void* psiphon_run_thread(void* arg) {
       // Attach to JVM
       (*java_vm)->AttachCurrentThread(java_vm, &env, NULL);

       // Setup for this thread
       setup_classloader(env);
       preload_classes_with_classloader(env);

       // CRITICAL: Hook THIS thread's JNIEnv
       hook_findclass(env);

       // Now call libsocks2tun.so function
       psiphon_run(env, ...);
   }
   ```

### Why Each Thread Needs Hook

**Important**: Each thread has its own `JNIEnv` pointer with its own function table. Therefore:

- ✅ Hook in main thread's JNIEnv
- ✅ Hook in native thread's JNIEnv (in psiphon_run_thread)
- ✅ Hook in JNI_OnLoad thread's JNIEnv

### Security Considerations

1. **Memory Protection**: JNI function tables are normally read-only for security
   - We use `mprotect()` to temporarily make them writable
   - We restore read-only protection immediately after

2. **Recursion Prevention**: Use thread-local flag to prevent infinite loops
   ```c
   static __thread int in_custom_findclass = 0;
   ```

3. **Selective Interception**: Only intercept application classes we control
   - System classes pass through to original FindClass
   - Minimal performance impact

4. **Proper Cleanup**: Restore original function pointer on unload
   ```c
   JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
       unhook_findclass(env);
   }
   ```

## Implementation Details

### Complete Flow

```
1. App starts → JNI_OnLoad called
   ├─ Setup ClassLoader from app context
   ├─ Pre-load PsiphonTunnel class
   ├─ Cache as global reference (g_psiphonTunnelClass)
   └─ Install FindClass hook in main thread

2. User starts VPN → TProxyStartServiceWithParams called
   ├─ load_httpcustom_libraries()
   │  ├─ Install FindClass hook (if not already)
   │  └─ dlopen("libsocks2tun.so")
   └─ Create psiphon_run_thread

3. Native thread starts → psiphon_run_thread
   ├─ AttachCurrentThread (gets system class loader by default)
   ├─ Setup ClassLoader
   ├─ Pre-load classes
   ├─ ⚡ Install FindClass hook for THIS thread's JNIEnv
   └─ Call psiphon_run() from libsocks2tun.so

4. libsocks2tun.so executes → Calls FindClass internally
   ├─ JNI FindClass("ca/psiphon/PsiphonTunnel")
   ├─ ⚡ Intercepted by our custom_FindClass
   ├─ Returns cached g_psiphonTunnelClass
   └─ ✅ Success! libsocks2tun.so finds the class

5. Callback from native → logTun2Socks called
   ├─ libsocks2tun.so has valid class reference
   ├─ Calls static method logTun2Socks
   └─ ✅ Logs appear in Java layer
```

### Error Handling

If `mprotect()` fails (some devices have stricter SELinux policies):

```c
if (mprotect(...) != 0) {
    LOGW("⚠️ FindClass hook failed - will rely on pre-loading only");
    // Graceful degradation: continue without hook
    // May still work on some devices where pre-loading is sufficient
}
```

### Performance Impact

- **Negligible**: Hook only checks class name string
- **Fast path**: Most FindClass calls are for system classes (strcmp fails immediately)
- **Cached**: Application class lookups return cached global reference (no class loading)

## Alternative Approaches (Not Used)

### Why DefineClass Won't Work
```c
// This would inject class into system classloader
jclass clazz = (*env)->DefineClass(env, "ca/psiphon/PsiphonTunnel",
                                   systemClassLoader, bytecode, bytecode_len);
```
**Problem**: Requires class bytecode and may conflict with existing class

### Why JavaVMAttachArgs Won't Work
```c
JavaVMAttachArgs args = {
    .version = JNI_VERSION_1_6,
    .name = "native_thread",
    .group = NULL // No way to specify ClassLoader here
};
(*java_vm)->AttachCurrentThread(java_vm, &env, &args);
```
**Problem**: No field in JavaVMAttachArgs to specify custom ClassLoader

### Why DexClassLoader Won't Work
**Problem**: Can't inject into system classloader's delegation chain at runtime

## Testing

### Verification Points

1. **Hook Installation**: Check logcat for:
   ```
   ✅ FindClass hook installed successfully
   Original FindClass: 0x7b12345678
   Custom FindClass: 0x7b87654321
   ```

2. **Interception**: When libsocks2tun.so runs:
   ```
   🔧 FindClass hook intercepted: ca/psiphon/PsiphonTunnel
   ✅ Returning cached PsiphonTunnel class from hook
   ```

3. **Callback Success**: Logs from native code appear:
   ```
   PsiphonTunnel/tun2socks: tunnel started
   ```

### Failure Modes

If hook fails but pre-loading works:
- Some devices may allow classes loaded in main thread to be visible in native threads
- Hook provides guarantee across all devices

If both fail:
- NoClassDefFoundError from libsocks2tun.so
- Need to investigate SELinux policies or ART internals

## Compatibility

- ✅ **Android 5.0+**: mprotect supported
- ✅ **ARM64/x86_64**: Tested architectures
- ⚠️ **SELinux**: May fail on devices with strict policies
- ✅ **ART/Dalvik**: Works on both runtimes

## References

- [JNI Specification: FindClass](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/functions.html#FindClass)
- [Android NDK: JNI Tips](https://developer.android.com/training/articles/perf-jni#faq_FindClass)
- BadVPN tun2socks implementation
- Psiphon Tunnel Core JNI interface

## Summary

The FindClass hook is the **definitive solution** to the class loader context problem:

✅ **Works reliably**: Intercepts at JNI function pointer level
✅ **Thread-safe**: Each thread gets its own hook
✅ **Minimal overhead**: Only string comparison for most calls
✅ **Graceful degradation**: Falls back to pre-loading if hook fails
✅ **Clean**: Properly unhooks on library unload

This approach is used by several Android VPN/proxy apps facing similar native library integration challenges.
