#!/bin/bash

# Build script for native Go library
# This builds a shared library (.so) for Android

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_DIR="$PROJECT_ROOT/native"
OUTPUT_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Building native Go library for Android...${NC}"

# Check if Go is installed
if ! command -v go &> /dev/null; then
    echo -e "${RED}Error: Go is not installed or not in PATH${NC}"
    echo "Please install Go from https://go.dev/dl/"
    exit 1
fi

# Check Go version
GO_VERSION=$(go version | awk '{print $3}' | sed 's/go//')
echo -e "${GREEN}Found Go version: $GO_VERSION${NC}"

# Check if Android NDK is available
if [ -z "$NDK_HOME" ] && [ -z "$ANDROID_NDK_HOME" ]; then
    echo -e "${YELLOW}Warning: NDK_HOME or ANDROID_NDK_HOME not set${NC}"
    echo "Android NDK is required for CGO builds"
    echo "Please set NDK_HOME or ANDROID_NDK_HOME environment variable"
    exit 1
fi

NDK_HOME="${NDK_HOME:-$ANDROID_NDK_HOME}"
echo -e "${GREEN}Using NDK: $NDK_HOME${NC}"

# Navigate to native directory
cd "$NATIVE_DIR"

# Initialize go.mod if it doesn't exist
if [ ! -f go.mod ]; then
    echo "Initializing go.mod..."
    go mod init github.com/hyperxray/native
fi

# Download dependencies
echo "Downloading Go dependencies..."
go mod download
go mod tidy

# Build for different architectures
ARCHITECTURES=("arm64-v8a" "x86_64")
GO_ARCHS=("arm64" "amd64")
NDK_ARCHS=("aarch64-linux-android" "x86_64-linux-android")

for i in "${!ARCHITECTURES[@]}"; do
    ARCH="${ARCHITECTURES[$i]}"
    GOARCH="${GO_ARCHS[$i]}"
    NDK_ARCH="${NDK_ARCHS[$i]}"
    
    echo -e "${GREEN}Building for $ARCH (GOARCH=$GOARCH)...${NC}"
    
    # Set build environment
    export GOOS=android
    export GOARCH="$GOARCH"
    export CGO_ENABLED=1
    
    # Set compiler
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        CC="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${NDK_ARCH}24-clang"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        CC="$NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/${NDK_ARCH}24-clang"
    elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then
        CC="$NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/${NDK_ARCH}24-clang.cmd"
    else
        echo -e "${RED}Unsupported OS: $OSTYPE${NC}"
        exit 1
    fi
    
    export CC
    
    # Create output directory
    OUTPUT_ARCH_DIR="$OUTPUT_DIR/$ARCH"
    mkdir -p "$OUTPUT_ARCH_DIR"
    
    # Build shared library
    OUTPUT_FILE="$OUTPUT_ARCH_DIR/libhyperxray.so"
    
    echo "Building shared library: $OUTPUT_FILE"
    go build -buildmode=c-shared \
        -o "$OUTPUT_FILE" \
        -trimpath \
        -ldflags="-s -w -buildid=" \
        -v \
        .
    
    if [ -f "$OUTPUT_FILE" ]; then
        echo -e "${GREEN}✓ Successfully built $OUTPUT_FILE${NC}"
    else
        echo -e "${RED}✗ Failed to build $OUTPUT_FILE${NC}"
        exit 1
    fi
done

echo -e "${GREEN}All builds completed successfully!${NC}"
