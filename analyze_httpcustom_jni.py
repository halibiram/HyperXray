#!/usr/bin/env python3
"""
HTTP Custom JNI Interface Analyzer
Analyses APK and native libraries to find JNI function signatures
"""

import zipfile
import re
import sys
import os


def analyze_apk(apk_path):
    """Analyze APK for JNI interface clues"""
    print(f"Analyzing APK: {apk_path}")

    with zipfile.ZipFile(apk_path, 'r') as z:
        files = z.namelist()

        # Find DEX files
        dex_files = [f for f in files if f.endswith('.dex')]
        print(f"\nFound {len(dex_files)} DEX file(s)")

        # Find native libraries
        native_libs = [f for f in files if f.startswith(
            'lib/') and f.endswith('.so')]
        print(f"\nFound {len(native_libs)} native library(ies):")
        for lib in native_libs:
            print(f"  {lib}")

        # Find V2Ray related files
        v2ray_files = [f for f in files if 'v2ray' in f.lower(
        ) or 'socks' in f.lower() or 'tun' in f.lower()]
        print(f"\nFound {len(v2ray_files)} V2Ray/SOCKS related file(s):")
        for f in v2ray_files[:20]:
            print(f"  {f}")

        # Try to read classes.dex as binary and search for strings
        if 'classes.dex' in files:
            print("\nAnalyzing classes.dex for JNI clues...")
            dex_data = z.read('classes.dex')

            # Search for loadLibrary calls
            load_lib_pattern = rb'loadLibrary'
            matches = list(re.finditer(load_lib_pattern, dex_data))
            if matches:
                print(f"  Found {len(matches)} 'loadLibrary' references")
                for i, match in enumerate(matches[:5]):
                    start = max(0, match.start() - 50)
                    end = min(len(dex_data), match.end() + 50)
                    context = dex_data[start:end]
                    # Try to extract readable strings
                    try:
                        readable = ''.join(
                            chr(b) if 32 <= b < 127 else '.' for b in context)
                        print(f"    Match {i+1}: {readable[:100]}")
                    except:
                        pass

            # Search for native method indicators
            native_pattern = rb'native'
            matches = list(re.finditer(native_pattern, dex_data))
            if matches:
                print(f"  Found {len(matches)} 'native' references")

        # Read AndroidManifest
        if 'AndroidManifest.xml' in files:
            print("\nAndroidManifest.xml content (first 2000 chars):")
            manifest = z.read('AndroidManifest.xml')
            try:
                # Try to decode (might be binary XML)
                print(manifest[:2000].decode('utf-8', errors='ignore'))
            except:
                print("  (Binary XML, cannot display)")


def analyze_native_lib(lib_path):
    """Analyze native library for exported symbols"""
    print(f"\nAnalyzing native library: {lib_path}")

    if not os.path.exists(lib_path):
        print(f"  File not found: {lib_path}")
        return

    # Try to read as binary and search for JNI patterns
    with open(lib_path, 'rb') as f:
        data = f.read()

        # Search for Java_ prefix (JNI function naming convention)
        java_pattern = rb'Java_[a-zA-Z0-9_]+'
        matches = re.findall(java_pattern, data)
        if matches:
            print(f"  Found {len(matches)} potential JNI functions:")
            seen = set()
            for match in matches[:20]:
                func_name = match.decode('utf-8', errors='ignore')
                if func_name not in seen:
                    seen.add(func_name)
                    print(f"    {func_name}")
        else:
            print("  No Java_ prefixed functions found (might be stripped)")

        # Search for common JNI strings
        jni_strings = [b'JNI_OnLoad', b'JNI_OnUnload', b'JavaVM', b'JNIEnv']
        for jni_str in jni_strings:
            if jni_str in data:
                print(f"  Found JNI indicator: {jni_str.decode('utf-8')}")


if __name__ == '__main__':
    apk_path = 'httpcustom_base.apk'
    if os.path.exists(apk_path):
        analyze_apk(apk_path)

    # Analyze native libraries
    libs = [
        'httpcustom_v2ray_files/libsocks.so',
        'httpcustom_v2ray_files/libsocks2tun.so',
        'httpcustom_v2ray_files/libtun2socks.so'
    ]

    for lib in libs:
        analyze_native_lib(lib)



