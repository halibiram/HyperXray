# libsocks2tun.so Decompilation Report

## Library Information
- **File**: httpcustom_v2ray_files/libsocks2tun.so
- **Type**: ELF 64-bit LSB shared object, ARM aarch64
- **Status**: Stripped (symbols removed)
- **Size**: 196344 bytes

## Analysis Results

### JNI Functions Found
Based on binary analysis, this library uses **Psiphon Tunnel** (BadVPN tun2socks based):

1. `Java_ca_psiphon_PsiphonTunnel_runTun2Socks`
2. `Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks`
3. `Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive`
4. `Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive`

### Function Signatures

#### Java Interface (from decompiled HTTP Custom code)

```java
package ca.psiphon;

public class PsiphonTunnel {
    static {
        System.loadLibrary("socks2tun");
    }
    
    // Starts tun2socks tunnel
    // Actual signature: public static native int runTun2Socks(int, int, String, String, String, String, int);
    public static native int runTun2Socks(
        int tunFd,              // VPN interface file descriptor
        int mtu,                // Maximum Transmission Unit (typically 1500)
        String router,          // Virtual network interface IP (e.g., "10.0.0.2")
        String netmask,        // Subnet mask (e.g., "255.255.255.0")
        String socksServer,    // Local SOCKS proxy address (e.g., "127.0.0.1:1080")
        String udpgwAddr,      // UDP Gateway server address (e.g., "127.0.0.1:7300")
        int udpgwTransparent   // UDP transparent mode (0 = false, 1 = true)
    );
    
    // Stops tun2socks tunnel
    // Actual signature: private static native int terminateTun2Socks();
    private static native int terminateTun2Socks();
    
    // Enables UDP Gateway keepalive
    // Actual signature: private static native int enableUdpGwKeepalive();
    private static native int enableUdpGwKeepalive();
    
    // Disables UDP Gateway keepalive
    // Actual signature: private static native int disableUdpGwKeepalive();
    private static native int disableUdpGwKeepalive();
    
    // Callback from native code
    // Signature: public static void logTun2Socks(String level, String tag, String message)
    public static void logTun2Socks(String level, String tag, String message);
}
```

#### C JNI Signatures (corrected from decompiled code)

```c
#include <jni.h>

// Starts tun2socks tunnel
// Note: All methods are static, so use jclass instead of jobject
// Note: udpgwTransparent is jint (0/1), not jboolean
JNIEXPORT jint JNICALL
Java_ca_psiphon_PsiphonTunnel_runTun2Socks(
    JNIEnv *env,
    jclass clazz,              // static method
    jint tunFd,
    jint mtu,
    jstring router,
    jstring netmask,
    jstring socksServer,
    jstring udpgwAddr,
    jint udpgwTransparent      // int (0 = false, 1 = true)
);

// Stops tun2socks tunnel
JNIEXPORT jint JNICALL
Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks(
    JNIEnv *env,
    jclass clazz               // static method
);

// Enables UDP Gateway keepalive
JNIEXPORT jint JNICALL
Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive(
    JNIEnv *env,
    jclass clazz               // static method
);

// Disables UDP Gateway keepalive
JNIEXPORT jint JNICALL
Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive(
    JNIEnv *env,
    jclass clazz               // static method
);
```

## Generated Files

All analysis results are saved in `libsocks2tun_decompiled/`:
- `strings.txt` - Extracted strings from binary
- `jni_functions.txt` - Found JNI function names
- `libsocks2tun_jni.h` - C header file with JNI declarations
- `PsiphonTunnel.java` - Java wrapper class
- `patterns.txt` - Binary patterns found
- `sections_python.txt` - ELF sections (if pyelftools available)
- `symbols_python.txt` - ELF symbols (if pyelftools available)

## Usage Example

```java
import ca.psiphon.PsiphonTunnel;

// Load library (done automatically in static block)
// System.loadLibrary("socks2tun");

// Start tunnel (static method)
int result = PsiphonTunnel.runTun2Socks(
    tunFd,              // File descriptor from VpnService.Builder
    1500,               // MTU
    "10.0.0.2",         // VPN IP
    "255.255.255.0",    // Netmask
    "127.0.0.1:1080",   // SOCKS5 server
    "127.0.0.1:7300",   // UDP Gateway (default port 7300)
    1                   // UDP transparent mode (1 = true, 0 = false)
);

if (result != 0) {
    // Handle error
}

// Stop tunnel (private static, but can be called via reflection if needed)
// PsiphonTunnel.terminateTun2Socks();
```

## Notes

- The library is **stripped**, so function names are not directly visible in symbol table
- JNI function names follow the pattern: `Java_<package>_<class>_<method>`
- This library is based on **BadVPN tun2socks** (used by Psiphon)
- For full decompilation to C source code, use **Ghidra** or **IDA Pro**
- The library handles SOCKS5 to TUN interface conversion

## References

- **Psiphon Android**: https://github.com/Psiphon-Inc/psiphon-android
- **BadVPN tun2socks**: https://github.com/ambrop72/badvpn
- **Ghidra**: https://ghidra-sre.org/ (for full decompilation)

## Next Steps

1. Use Ghidra or IDA Pro for full decompilation to C source code
2. Analyze the disassembly to understand the internal implementation
3. Compare with Psiphon source code if available
4. Create wrapper classes for integration into HyperXray
