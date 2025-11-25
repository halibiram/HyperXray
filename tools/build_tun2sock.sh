#!/bin/bash
set -e

# Configuration
REPO_URL="https://github.com/xjasonlyu/tun2sock"
BUILD_DIR="build/tun2sock"
OUTPUT_DIR="app/src/main/jniLibs"

# Ensure directories exist
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

# NDK Detection
if [ -z "$ANDROID_NDK_HOME" ]; then
    # Try common locations
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(find "$HOME/Android/Sdk/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
    elif [ -d "/usr/local/lib/android/sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(find "/usr/local/lib/android/sdk/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
    fi
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "WARNING: ANDROID_NDK_HOME not set and not found in common locations."
    echo "Build might fail if CC is not set correctly."
else
    echo "Using NDK: $ANDROID_NDK_HOME"
fi

# Clone or update repository
if [ -d "$BUILD_DIR/.git" ]; then
    echo "Updating tun2sock repository..."
    cd "$BUILD_DIR"
    git pull
else
    echo "Cloning tun2sock repository..."
    rm -rf "$BUILD_DIR"
    git clone --depth 1 "$REPO_URL" "$BUILD_DIR"
    cd "$BUILD_DIR"
fi

# Create the Go wrapper file with real implementation
cat > mobile.go <<EOF
package main

import (
	"C"
	"io"
	"os"

	"github.com/xjasonlyu/tun2sock/v2/core/device"
	"github.com/xjasonlyu/tun2sock/v2/engine"
)

// FileDevice implements device.Device interface
type FileDevice struct {
	f   *os.File
    mtu int
}

func NewFileDevice(fd uintptr, mtu int) *FileDevice {
	return &FileDevice{
		f:   os.NewFile(fd, "tun"),
        mtu: mtu,
	}
}

func (d *FileDevice) Close() error {
    // managed by Android
	return nil
}

func (d *FileDevice) Read(p []byte) (n int, err error) {
	return d.f.Read(p)
}

func (d *FileDevice) Write(p []byte) (n int, err error) {
	return d.f.Write(p)
}

func (d *FileDevice) Name() string {
    return "tun0"
}

func (d *FileDevice) MTU() int {
    return d.mtu
}

var key *engine.Key

//export Start
func Start(fd C.int, proxyUrl *C.char) {
	if key != nil {
		return
	}

	goFd := uintptr(fd)
	goProxyUrl := C.GoString(proxyUrl)

    // Default MTU 1500, could be passed as arg
    dev := NewFileDevice(goFd, 1500)

    // Initialize engine
    k, err := engine.Insert(&engine.Key{
        Device:   dev,
        Proxy:    goProxyUrl,
        LogLevel: "info",
    })
    if err != nil {
        return
    }

    k.Open()
    key = k
}

//export Stop
func Stop() {
	if key != nil {
		key.Close()
		key = nil
	}
}

func main() {}
EOF

# Initialize module
if [ ! -f "go.mod" ]; then
    go mod init mobile
fi
go get github.com/xjasonlyu/tun2sock/v2
go mod tidy

# Helper to find CC
find_cc() {
    local ARCH=$1
    local API=$2
    local HOST_TAG="linux-x86_64" # Assume linux host

    if [ -n "$ANDROID_NDK_HOME" ]; then
        local TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
        local CC_BIN="$TOOLCHAIN/${ARCH}${API}-clang"
        echo "$CC_BIN"
    else
        echo ""
    fi
}

# Function to build for a specific ABI
build_abi() {
    local ABI=$1
    local GOARCH=$2
    local GOARM=$3
    local TARGET_TRIPLE=$4 # e.g. aarch64-linux-android
    local API_LEVEL=21

    echo "Building for $ABI..."

    local TARGET_DIR="../../$OUTPUT_DIR/$ABI"
    mkdir -p "$TARGET_DIR"

    export CGO_ENABLED=1
    export GOOS=android
    export GOARCH=$GOARCH
    if [ -n "$GOARM" ]; then
        export GOARM=$GOARM
    else
        unset GOARM
    fi

    local CC_PATH=$(find_cc "$TARGET_TRIPLE" "$API_LEVEL")
    if [ -n "$CC_PATH" ] && [ -x "$CC_PATH" ]; then
        export CC="$CC_PATH"
        echo "Using CC: $CC"
    else
        if [ -n "$CC" ]; then
            echo "Using provided CC: $CC"
        else
            echo "Error: CC not found for $TARGET_TRIPLE. Please set ANDROID_NDK_HOME or CC."
            # Allow continuing if detection fails, assuming env might be set manually
        fi
    fi

    go build -buildmode=c-shared -o "$TARGET_DIR/libtun2sock.so" mobile.go || echo "Build failed for $ABI"

    if [ -f "$TARGET_DIR/libtun2sock.h" ] && [ ! -f "../../app/src/main/jni/include/tun2sock.h" ]; then
        mkdir -p "../../app/src/main/jni/include"
        cp "$TARGET_DIR/libtun2sock.h" "../../app/src/main/jni/include/tun2sock.h"
    fi
    rm -f "$TARGET_DIR/libtun2sock.h"
}

# Build for supported ABIs
build_abi "arm64-v8a" "arm64" "" "aarch64-linux-android"
build_abi "armeabi-v7a" "arm" "7" "armv7a-linux-androideabi"
build_abi "x86_64" "amd64" "" "x86_64-linux-android"
build_abi "x86" "386" "" "i686-linux-android"

echo "Build complete."
