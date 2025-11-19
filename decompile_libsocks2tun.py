#!/usr/bin/env python3
"""
Decompile and analyze libsocks2tun.so
Extracts symbols, strings, JNI functions, and attempts to reverse engineer the library
"""

import subprocess
import os
import re
import sys
from pathlib import Path

LIB_PATH = "httpcustom_v2ray_files/libsocks2tun.so"
OUTPUT_DIR = "libsocks2tun_decompiled"

def run_command(cmd, description):
    """Run a command and return output"""
    print(f"\n{'='*60}")
    print(f"{description}")
    print(f"{'='*60}")
    try:
        result = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            check=False
        )
        if result.stdout:
            print(result.stdout)
        if result.stderr and result.returncode != 0:
            print(f"Error: {result.stderr}")
        return result.stdout
    except Exception as e:
        print(f"Failed to run command: {e}")
        return ""

def extract_strings():
    """Extract readable strings from the binary"""
    print("\nExtracting strings...")
    output = run_command(f"strings -a {LIB_PATH}", "Strings in binary")
    
    # Save to file
    output_file = f"{OUTPUT_DIR}/strings.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(output)
    print(f"Strings saved to {output_file}")
    
    # Filter JNI-related strings
    jni_strings = [line for line in output.split('\n') 
                   if 'Java_' in line or 'JNI' in line or 'psiphon' in line.lower()]
    if jni_strings:
        print("\nJNI-related strings:")
        for s in jni_strings[:50]:
            print(f"  {s}")

def analyze_symbols():
    """Analyze exported and imported symbols"""
    print("\nAnalyzing symbols...")
    
    # Read ELF symbols
    output = run_command(f"readelf -Ws {LIB_PATH}", "ELF Symbols")
    
    # Save to file
    output_file = f"{OUTPUT_DIR}/symbols.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(output)
    print(f"Symbols saved to {output_file}")
    
    # Try nm if available
    run_command(f"nm -D {LIB_PATH} 2>/dev/null || nm {LIB_PATH} 2>/dev/null", "Symbol Table (nm)")

def analyze_sections():
    """Analyze ELF sections"""
    print("\nAnalyzing ELF sections...")
    output = run_command(f"readelf -S {LIB_PATH}", "ELF Sections")
    
    output_file = f"{OUTPUT_DIR}/sections.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(output)

def analyze_imports_exports():
    """Analyze imported and exported functions"""
    print("\nAnalyzing imports and exports...")
    
    # Dynamic symbols (imported functions)
    output = run_command(f"readelf -d {LIB_PATH}", "Dynamic Section")
    
    output_file = f"{OUTPUT_DIR}/dynamic.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(output)
    
    # Imported functions
    output = run_command(f"readelf -r {LIB_PATH}", "Relocations (imports)")
    
    output_file = f"{OUTPUT_DIR}/relocations.txt"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(output)

def disassemble_functions():
    """Disassemble the binary"""
    print("\nDisassembling binary...")
    
    # Full disassembly
    output = run_command(f"objdump -d {LIB_PATH}", "Disassembly")
    
    output_file = f"{OUTPUT_DIR}/disassembly.s"
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(output)
    print(f"Disassembly saved to {output_file}")
    
    # Intel syntax (if available)
    run_command(f"objdump -d -M intel {LIB_PATH} 2>/dev/null", "Disassembly (Intel syntax)")

def extract_jni_functions():
    """Extract JNI function signatures"""
    print("\nExtracting JNI functions...")
    
    # Read binary and search for JNI patterns
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
        b'tun2socks'
    ]
    
    found_jni = []
    for jni_str in jni_strings:
        if jni_str in data:
            found_jni.append(jni_str.decode('utf-8', errors='ignore'))
    
    if found_jni:
        print("\nFound JNI-related strings:")
        for s in found_jni:
            print(f"  {s}")

def analyze_with_ghidra():
    """Attempt to use Ghidra headless for decompilation"""
    print("\nAttempting Ghidra decompilation...")
    
    # Check if Ghidra is available
    ghidra_paths = [
        "/usr/local/bin/ghidra",
        "/opt/ghidra/ghidra",
        "C:/ghidra/ghidra",
        os.path.expanduser("~/ghidra/ghidra")
    ]
    
    ghidra_found = False
    for path in ghidra_paths:
        if os.path.exists(path):
            ghidra_found = True
            print(f"Found Ghidra at {path}")
            break
    
    if not ghidra_found:
        print("Ghidra not found. Install Ghidra for full decompilation.")
        print("You can download it from: https://ghidra-sre.org/")
        return
    
    # Create Ghidra script for headless analysis
    ghidra_script = f"""
import ghidra.app.script.GhidraScript
import ghidra.program.model.listing.FunctionManager

class DecompileLib(GhidraScript):
    def run(self):
        output_file = "{OUTPUT_DIR}/ghidra_decompiled.c"
        with open(output_file, 'w') as f:
            func_manager = currentProgram.getFunctionManager()
            functions = func_manager.getFunctions(True)
            for func in functions:
                decompiled = decompile(func)
                if decompiled:
                    f.write(f"// Function: {{func.getName()}}\\n")
                    f.write(decompile(func).getDecompiledFunction().getC())
                    f.write("\\n\\n")
        print(f"Decompilation saved to {{output_file}}")
"""
    
    script_file = f"{OUTPUT_DIR}/ghidra_script.java"
    with open(script_file, 'w') as f:
        f.write(ghidra_script)
    
    print(f"Ghidra script created at {script_file}")
    print("Run Ghidra headless manually:")
    print(f"  analyzeHeadless . libsocks2tun_project -import {LIB_PATH} -scriptPath {OUTPUT_DIR} -postScript ghidra_script.java")

def create_summary():
    """Create a summary report"""
    summary = f"""
# libsocks2tun.so Decompilation Report

## Library Information
- **File**: {LIB_PATH}
- **Type**: ELF 64-bit LSB shared object, ARM aarch64
- **Status**: Stripped (symbols removed)

## Analysis Results

### JNI Functions Found
Based on binary analysis, this library uses Psiphon Tunnel:
- `Java_ca_psiphon_PsiphonTunnel_runTun2Socks`
- `Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks`
- `Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive`
- `Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive`

### Function Signatures (from HTTPCUSTOM_JNI_INTERFACE.md)

```java
// runTun2Socks
private native void runTun2Socks(
    int tunFd,              // VPN interface file descriptor
    int mtu,                // Maximum Transmission Unit
    String router,          // Virtual network interface IP (e.g., "10.0.0.2")
    String netmask,        // Subnet mask (e.g., "255.255.255.0")
    String socksServer,    // Local SOCKS proxy address (e.g., "127.0.0.1:1080")
    String udpgwAddr,      // UDP Gateway server address (optional, for games/VoIP)
    boolean udpgwTransparent // UDP transparent mode (optional)
);

// terminateTun2Socks
private native void terminateTun2Socks();

// UDP Gateway Keepalive
private native void enableUdpGwKeepalive();
private native void disableUdpGwKeepalive();
```

### C JNI Signatures

```c
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

JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive(JNIEnv *env, jobject thiz);
```

## Generated Files

All analysis results are saved in `{OUTPUT_DIR}/`:
- `strings.txt` - Extracted strings
- `symbols.txt` - ELF symbols
- `sections.txt` - ELF sections
- `dynamic.txt` - Dynamic linking information
- `relocations.txt` - Relocation entries
- `disassembly.s` - Full disassembly
- `jni_functions.txt` - Found JNI functions

## Notes

- The library is stripped, so function names are not directly visible
- JNI function names follow the pattern: `Java_<package>_<class>_<method>`
- This library is based on BadVPN tun2socks (used by Psiphon)
- For full decompilation, use Ghidra or IDA Pro

## References

- Psiphon Android: https://github.com/Psiphon-Inc/psiphon-android
- BadVPN tun2socks: https://github.com/ambrop72/badvpn
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
    extract_strings()
    analyze_symbols()
    analyze_sections()
    analyze_imports_exports()
    disassemble_functions()
    extract_jni_functions()
    analyze_with_ghidra()
    create_summary()
    
    print(f"\n{'='*60}")
    print("Analysis complete!")
    print(f"Results saved to: {OUTPUT_DIR}/")
    print(f"{'='*60}")

if __name__ == '__main__':
    main()



