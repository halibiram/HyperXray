@echo off
REM Build script for native Go library on Windows
REM This builds a shared library (.so) for Android

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set NATIVE_DIR=%PROJECT_ROOT%\native
set OUTPUT_DIR=%PROJECT_ROOT%\app\src\main\jniLibs

echo Building native Go library for Android...

REM Check if Go is installed
where go >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Error: Go is not installed or not in PATH
    echo Please install Go from https://go.dev/dl/
    exit /b 1
)

REM Check Go version
for /f "tokens=3" %%i in ('go version') do set GO_VERSION=%%i
echo Found Go version: %GO_VERSION%

REM Check if Android NDK is available
if "%NDK_HOME%"=="" (
    if "%ANDROID_NDK_HOME%"=="" (
        echo Warning: NDK_HOME or ANDROID_NDK_HOME not set
        echo Android NDK is required for CGO builds
        echo Please set NDK_HOME or ANDROID_NDK_HOME environment variable
        exit /b 1
    ) else (
        set NDK_HOME=%ANDROID_NDK_HOME%
    )
) else (
    set NDK_HOME=%NDK_HOME%
)

echo Using NDK: %NDK_HOME%

REM Navigate to native directory
cd /d "%NATIVE_DIR%"

REM Initialize go.mod if it doesn't exist
if not exist go.mod (
    echo Initializing go.mod...
    go mod init github.com/hyperxray/native
)

REM Download dependencies
echo Downloading Go dependencies...
go mod download
go mod tidy

REM Build for arm64-v8a
echo Building for arm64-v8a (GOARCH=arm64)...
set GOOS=android
set GOARCH=arm64
set CGO_ENABLED=1
set CC=%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang.cmd

set OUTPUT_ARCH_DIR=%OUTPUT_DIR%\arm64-v8a
if not exist "%OUTPUT_ARCH_DIR%" mkdir "%OUTPUT_ARCH_DIR%"

set OUTPUT_FILE=%OUTPUT_ARCH_DIR%\libhyperxray-go.so

echo Building shared library: %OUTPUT_FILE%
go build -buildmode=c-shared -o "%OUTPUT_FILE%" -trimpath -ldflags="-s -w -buildid=" -v .

if exist "%OUTPUT_FILE%" (
    echo Successfully built %OUTPUT_FILE%
) else (
    echo Failed to build %OUTPUT_FILE%
    exit /b 1
)

REM Build for x86_64
echo Building for x86_64 (GOARCH=amd64)...
set GOOS=android
set GOARCH=amd64
set CGO_ENABLED=1
set CC=%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin\x86_64-linux-android24-clang.cmd

set OUTPUT_ARCH_DIR=%OUTPUT_DIR%\x86_64
if not exist "%OUTPUT_ARCH_DIR%" mkdir "%OUTPUT_ARCH_DIR%"

set OUTPUT_FILE=%OUTPUT_ARCH_DIR%\libhyperxray-go.so

echo Building shared library: %OUTPUT_FILE%
go build -buildmode=c-shared -o "%OUTPUT_FILE%" -trimpath -ldflags="-s -w -buildid=" -v .

if exist "%OUTPUT_FILE%" (
    echo Successfully built %OUTPUT_FILE%
) else (
    echo Failed to build %OUTPUT_FILE%
    exit /b 1
)

echo All builds completed successfully!

endlocal

