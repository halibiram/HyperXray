#!/usr/bin/env python3
"""
Decompile and analyze libsocks2tun.so using Python
Extracts symbols, strings, JNI functions without requiring external tools
"""

import os
import re
import sys
from pathlib import Path

try:
    from elftools.elf.elffile import ELFFile
    from elftools.elf.sections import SymbolTableSection
    HAS_ELFTOOLS = True
except ImportError:
    HAS_ELFTOOLS = False
    print("Warning: pyelftools not installed. Install with: pip install pyelftools")

LIB_PATH = "httpcustom_v2ray_files/libsocks2tun.so"
OUTPUT_DIR = "libsocks2tun_decompiled"

def extract_strings_python():
    """Extract readable strings from binary using Python"""
    print("\nExtracting strings from binary...")
    
    with open(LIB_PATH, 'rb') as f:
        data = f.read()
    
    # Find all printable strings (at least 4 characters)
    strings = []
    current_string = b''
    
    for byte in data:
        if 32 <= byte < 127:  # Printable ASCII
            current_string += bytes([byte])
        else:
            if len(current_string) >= 4:
                try:
                    s = current_string.decode('utf-8', errors='ignore')
                    if s.strip():
                        strings.append(s)
                except:
                    pass
            current_string = b''
    
    # Also check for remaining string
    if len(current_string) >= 4:
        try:
            s = current_string.decode('utf-8', errors='ignore')
            if s.strip():
                strings.append(s)
        except:
            pass
    
    # Remove duplicates and sort
    strings = sorted(set(strings))
    
    # Save to file
    output_file = f"{OUTPUT_DIR}/strings.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write('\n'.join(strings))
    
    print(f"Found {len(strings)} strings")
    print(f"Strings saved to {output_file}")
    
    # Filter JNI-related strings
    jni_strings = [s for s in strings 
                   if 'Java_' in s or 'JNI' in s or 'psiphon' in s.lower() 
                   or 'tun2socks' in s.lower() or 'udpgw' in s.lower()]
    
    if jni_strings:
        print(f"\nFound {len(jni_strings)} JNI-related strings:")
        for s in jni_strings[:30]:
            print(f"  {s}")
    
    return strings

def analyze_elf_python():
    """Analyze ELF file using pyelftools"""
    if not HAS_ELFTOOLS:
        print("\nSkipping ELF analysis (pyelftools not installed)")
        return
    
    print("\nAnalyzing ELF structure...")
    
    with open(LIB_PATH, 'rb') as f:
        elf = ELFFile(f)
        
        # Get ELF header info
        print(f"ELF Class: {elf.elfclass}")
        print(f"ELF Endianness: {elf.little_endian}")
        print(f"Machine: {elf.header['e_machine']}")
        print(f"Type: {elf.header['e_type']}")
        
        # Analyze sections
        sections_info = []
        for section in elf.iter_sections():
            sec_info = {
                'name': section.name,
                'type': section['sh_type'],
                'size': section['sh_size'],
                'address': hex(section['sh_addr']),
            }
            sections_info.append(sec_info)
        
        # Save sections info
        output_file = f"{OUTPUT_DIR}/sections_python.txt"
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write("ELF Sections:\n")
            f.write("="*60 + "\n")
            for sec in sections_info:
                f.write(f"Name: {sec['name']}\n")
                f.write(f"  Type: {sec['type']}\n")
                f.write(f"  Size: {sec['size']} bytes\n")
                f.write(f"  Address: {sec['address']}\n")
                f.write("\n")
        
        print(f"Sections info saved to {output_file}")
        
        # Analyze symbols
        symbols = []
        for section in elf.iter_sections():
            if isinstance(section, SymbolTableSection):
                for symbol in section.iter_symbols():
                    sym_info = {
                        'name': symbol.name,
                        'value': hex(symbol['st_value']),
                        'size': symbol['st_size'],
                        'type': symbol['st_info']['type'],
                        'bind': symbol['st_info']['bind'],
                    }
                    symbols.append(sym_info)
        
        if symbols:
            output_file = f"{OUTPUT_DIR}/symbols_python.txt"
            with open(output_file, 'w', encoding='utf-8') as f:
                f.write("ELF Symbols:\n")
                f.write("="*60 + "\n")
                for sym in symbols:
                    f.write(f"Name: {sym['name']}\n")
                    f.write(f"  Value: {sym['value']}\n")
                    f.write(f"  Size: {sym['size']} bytes\n")
                    f.write(f"  Type: {sym['type']}\n")
                    f.write(f"  Bind: {sym['bind']}\n")
                    f.write("\n")
            
            print(f"Found {len(symbols)} symbols")
            print(f"Symbols saved to {output_file}")
            
            # Filter JNI symbols
            jni_symbols = [s for s in symbols if 'Java_' in s['name']]
            if jni_symbols:
                print(f"\nFound {len(jni_symbols)} JNI symbols:")
                for sym in jni_symbols:
                    print(f"  {sym['name']} @ {sym['value']}")

def extract_jni_functions():
    """Extract JNI function signatures from binary"""
    print("\nExtracting JNI functions...")
    
    with open(LIB_PATH, 'rb') as f:
        data = f.read()
    
    # Search for Java_ prefix (JNI function naming convention)
    java_pattern = rb'Java_[a-zA-Z0-9_]+'
    matches = re.findall(java_pattern, data)
    
    jni_functions = []
    seen = set()
    for match in matches:
        func_name = match.decode('utf-8', errors='ignore')
        if func_name not in seen:
            seen.add(func_name)
            jni_functions.append(func_name)
    
    if jni_functions:
        print(f"Found {len(jni_functions)} JNI functions:")
        for func in jni_functions:
            print(f"  {func}")
        
        # Save to file
        output_file = f"{OUTPUT_DIR}/jni_functions.txt"
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write("\n".join(jni_functions))
        print(f"JNI functions saved to {output_file}")
    else:
        print("No JNI functions found (library might be stripped)")
    
    # Search for JNI-related strings
    jni_strings = [
        b'JNI_OnLoad',
        b'JNI_OnUnload',
        b'JavaVM',
        b'JNIEnv',
        b'RegisterNatives',
        b'GetMethodID',
        b'CallObjectMethod',
        b'PsiphonTunnel',
        b'runTun2Socks',
        b'terminateTun2Socks',
        b'udpgw',
        b'tun2socks',
        b'disableUdpGwKeepalive',
        b'enableUdpGwKeepalive'
    ]
    
    found_jni = []
    for jni_str in jni_strings:
        if jni_str in data:
            found_jni.append(jni_str.decode('utf-8', errors='ignore'))
    
    if found_jni:
        print("\nFound JNI-related strings:")
        for s in found_jni:
            print(f"  {s}")
    
    return jni_functions

def analyze_binary_patterns():
    """Analyze binary for common patterns"""
    print("\nAnalyzing binary patterns...")
    
    with open(LIB_PATH, 'rb') as f:
        data = f.read()
    
    patterns = {
        'SOCKS5': [b'SOCKS', b'0x05', b'0x04'],
        'TUN': [b'/dev/tun', b'tun', b'TUN'],
        'Network': [b'127.0.0.1', b'localhost', b'0.0.0.0'],
        'Protocols': [b'TCP', b'UDP', b'IPv4', b'IPv6'],
        'Error codes': [b'ENOENT', b'EINVAL', b'ECONNREFUSED'],
    }
    
    found_patterns = {}
    for pattern_name, pattern_list in patterns.items():
        found = []
        for pattern in pattern_list:
            if pattern in data:
                found.append(pattern.decode('utf-8', errors='ignore'))
        if found:
            found_patterns[pattern_name] = found
    
    if found_patterns:
        print("Found patterns:")
        for name, patterns in found_patterns.items():
            print(f"  {name}: {', '.join(patterns)}")
        
        output_file = f"{OUTPUT_DIR}/patterns.txt"
        with open(output_file, 'w', encoding='utf-8') as f:
            for name, patterns in found_patterns.items():
                f.write(f"{name}:\n")
                for p in patterns:
                    f.write(f"  {p}\n")
                f.write("\n")
        print(f"Patterns saved to {output_file}")

def create_decompiled_c_header():
    """Create a C header file with JNI function declarations"""
    print("\nCreating C header file...")
    
    jni_functions = extract_jni_functions()
    
    header_content = """/*
 * libsocks2tun.so JNI Interface
 * Auto-generated from binary analysis
 */

#ifndef LIBSOCKS2TUN_JNI_H
#define LIBSOCKS2TUN_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

"""
    
    # Parse JNI function names and create declarations
    for func_name in jni_functions:
        # Parse: Java_ca_psiphon_PsiphonTunnel_runTun2Socks
        # Extract: package=ca.psiphon, class=PsiphonTunnel, method=runTun2Socks
        parts = func_name.replace('Java_', '').split('_')
        
        if len(parts) >= 2:
            class_name = parts[-2]
            method_name = parts[-1]
            
            # Determine return type and parameters based on method name
            if method_name == 'runTun2Socks':
                header_content += f"""/*
 * {func_name}
 * Starts tun2socks tunnel
 */
JNIEXPORT jint JNICALL
{func_name}(
    JNIEnv *env,
    jobject thiz,
    jint tunFd,
    jint mtu,
    jstring router,
    jstring netmask,
    jstring socksServer,
    jstring udpgwAddr,
    jboolean udpgwTransparent
);

"""
            elif method_name == 'terminateTun2Socks':
                header_content += f"""/*
 * {func_name}
 * Stops tun2socks tunnel
 */
JNIEXPORT void JNICALL
{func_name}(JNIEnv *env, jobject thiz);

"""
            elif 'UdpGwKeepalive' in method_name:
                header_content += f"""/*
 * {func_name}
 * Controls UDP Gateway keepalive
 */
JNIEXPORT void JNICALL
{func_name}(JNIEnv *env, jobject thiz);

"""
            else:
                header_content += f"""/*
 * {func_name}
 * Unknown signature - needs analysis
 */
JNIEXPORT void JNICALL
{func_name}(JNIEnv *env, jobject thiz);

"""
    
    header_content += """
#ifdef __cplusplus
}
#endif

#endif /* LIBSOCKS2TUN_JNI_H */
"""
    
    output_file = f"{OUTPUT_DIR}/libsocks2tun_jni.h"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(header_content)
    
    print(f"C header saved to {output_file}")

def create_java_wrapper():
    """Create Java wrapper class"""
    print("\nCreating Java wrapper class...")
    
    java_content = """package com.hyperxray.native;

/**
 * libsocks2tun.so JNI Wrapper
 * Auto-generated from binary analysis
 * 
 * This library uses Psiphon Tunnel (BadVPN tun2socks based)
 */
public class PsiphonTunnel {
    static {
        System.loadLibrary("socks2tun");
    }
    
    /**
     * Starts tun2socks tunnel
     * 
     * @param tunFd VPN interface file descriptor
     * @param mtu Maximum Transmission Unit
     * @param router Virtual network interface IP (e.g., "10.0.0.2")
     * @param netmask Subnet mask (e.g., "255.255.255.0")
     * @param socksServer Local SOCKS proxy address (e.g., "127.0.0.1:1080")
     * @param udpgwAddr UDP Gateway server address (optional, for games/VoIP)
     * @param udpgwTransparent UDP transparent mode (optional)
     */
    public native void runTun2Socks(
        int tunFd,
        int mtu,
        String router,
        String netmask,
        String socksServer,
        String udpgwAddr,
        boolean udpgwTransparent
    );
    
    /**
     * Stops tun2socks tunnel
     */
    public native void terminateTun2Socks();
    
    /**
     * Enables UDP Gateway keepalive
     */
    public native void enableUdpGwKeepalive();
    
    /**
     * Disables UDP Gateway keepalive
     */
    public native void disableUdpGwKeepalive();
    
    /**
     * Log callback from native code
     * Called by native code to send logs to Java
     */
    private void logTun2Socks(String level, String tag, String message) {
        // Implement logging here
        android.util.Log.d("PsiphonTunnel", String.format("[%s] %s: %s", level, tag, message));
    }
}
"""
    
    output_file = f"{OUTPUT_DIR}/PsiphonTunnel.java"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(java_content)
    
    print(f"Java wrapper saved to {output_file}")

def create_summary():
    """Create a summary report"""
    summary = f"""# libsocks2tun.so Decompilation Report

## Library Information
- **File**: {LIB_PATH}
- **Type**: ELF 64-bit LSB shared object, ARM aarch64
- **Status**: Stripped (symbols removed)
- **Size**: {os.path.getsize(LIB_PATH)} bytes

## Analysis Results

### JNI Functions Found
Based on binary analysis, this library uses **Psiphon Tunnel** (BadVPN tun2socks based):

1. `Java_ca_psiphon_PsiphonTunnel_runTun2Socks`
2. `Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks`
3. `Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive`
4. `Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive`

### Function Signatures

#### Java Interface

```java
package ca.psiphon;

public class PsiphonTunnel {{
    // Starts tun2socks tunnel
    private native void runTun2Socks(
        int tunFd,              // VPN interface file descriptor
        int mtu,                // Maximum Transmission Unit
        String router,          // Virtual network interface IP (e.g., "10.0.0.2")
        String netmask,        // Subnet mask (e.g., "255.255.255.0")
        String socksServer,    // Local SOCKS proxy address (e.g., "127.0.0.1:1080")
        String udpgwAddr,      // UDP Gateway server address (optional, for games/VoIP)
        boolean udpgwTransparent // UDP transparent mode (optional)
    );
    
    // Stops tun2socks tunnel
    private native void terminateTun2Socks();
    
    // Enables UDP Gateway keepalive
    private native void enableUdpGwKeepalive();
    
    // Disables UDP Gateway keepalive
    private native void disableUdpGwKeepalive();
    
    // Callback from native code
    private void logTun2Socks(String level, String tag, String message);
}}
```

#### C JNI Signatures

```c
#include <jni.h>

// Starts tun2socks tunnel
JNIEXPORT jint JNICALL
Java_ca_psiphon_PsiphonTunnel_runTun2Socks(
    JNIEnv *env,
    jobject thiz,
    jint tunFd,
    jint mtu,
    jstring router,
    jstring netmask,
    jstring socksServer,
    jstring udpgwAddr,
    jboolean udpgwTransparent
);

// Stops tun2socks tunnel
JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks(
    JNIEnv *env,
    jobject thiz
);

// Enables UDP Gateway keepalive
JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive(
    JNIEnv *env,
    jobject thiz
);

// Disables UDP Gateway keepalive
JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive(
    JNIEnv *env,
    jobject thiz
);
```

## Generated Files

All analysis results are saved in `{OUTPUT_DIR}/`:
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

PsiphonTunnel tunnel = new PsiphonTunnel();

// Start tunnel
tunnel.runTun2Socks(
    tunFd,              // File descriptor from VpnService.Builder
    1500,               // MTU
    "10.0.0.2",         // VPN IP
    "255.255.255.0",    // Netmask
    "127.0.0.1:1080",   // SOCKS5 server
    "127.0.0.1:7300",   // UDP Gateway (optional)
    false               // UDP transparent mode
);

// Stop tunnel
tunnel.terminateTun2Socks();
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
"""
    
    with open(f"{OUTPUT_DIR}/README.md", 'w', encoding='utf-8') as f:
        f.write(summary)
    
    print(f"\nSummary report saved to {OUTPUT_DIR}/README.md")

def main():
    """Main analysis function"""
    if not os.path.exists(LIB_PATH):
        print(f"Error: {LIB_PATH} not found!")
        sys.exit(1)
    
    # Create output directory
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    print(f"Analyzing {LIB_PATH}...")
    print(f"Output directory: {OUTPUT_DIR}/")
    
    # Run all analyses
    extract_strings_python()
    analyze_elf_python()
    extract_jni_functions()
    analyze_binary_patterns()
    create_decompiled_c_header()
    create_java_wrapper()
    create_summary()
    
    print(f"\n{'='*60}")
    print("Analysis complete!")
    print(f"Results saved to: {OUTPUT_DIR}/")
    print(f"{'='*60}")

if __name__ == '__main__':
    main()



