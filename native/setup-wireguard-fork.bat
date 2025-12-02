@echo off
REM Setup WireGuard-go fork with extended rekey timers
REM This script clones WireGuard-go and patches the timer constants

echo ======================================
echo Setting up WireGuard-go fork
echo ======================================
echo.

cd /d "%~dp0"

REM Check if fork directory already exists
if exist "wireguard-go-fork" (
    echo WireGuard-go fork directory already exists
    echo Removing old fork...
    rmdir /s /q wireguard-go-fork
)

echo Cloning WireGuard-go...
git clone https://git.zx2c4.com/wireguard-go wireguard-go-fork
if errorlevel 1 (
    echo ERROR: Failed to clone WireGuard-go
    exit /b 1
)

cd wireguard-go-fork

echo.
echo Checking out specific version (matching go.mod)...
git checkout f333402bd9cb
if errorlevel 1 (
    echo ERROR: Failed to checkout specific version
    exit /b 1
)

echo.
echo Patching device/timers.go with extended rekey timers...

REM Create a patch file
(
echo diff --git a/device/timers.go b/device/timers.go
echo --- a/device/timers.go
echo +++ b/device/timers.go
echo @@ -11,11 +11,14 @@ import ^(
echo  ^)
echo  
echo  const ^(
echo -	RekeyAfterTime      = time.Second * 120
echo -	RejectAfterTime     = time.Second * 180
echo +	// Extended rekey timers to prevent connection drops during rekey
echo +	// Original: RekeyAfterTime = 120s ^(2 min^), RejectAfterTime = 180s ^(3 min^)
echo +	// Modified: RekeyAfterTime = 7200s ^(2 hours^), RejectAfterTime = 10800s ^(3 hours^)
echo +	RekeyAfterTime      = time.Second * 7200  // 2 hours
echo +	RejectAfterTime     = time.Second * 10800 // 3 hours
echo  	RekeyAttemptTime    = time.Second * 90
echo  	RekeyTimeout        = time.Second * 5
echo -	KeepaliveTimeout    = time.Second * 10
echo +	KeepaliveTimeout    = time.Second * 25 // Increased from 10s to 25s
echo  	CookieRefreshTime   = time.Second * 120
echo  	HandshakeInitiation = time.Second * 0
echo  	HandshakeResponse   = time.Second * 0
) > rekey_timers.patch

REM Apply the patch
git apply rekey_timers.patch
if errorlevel 1 (
    echo ERROR: Failed to apply patch
    echo Trying manual edit...
    
    REM Fallback: manual edit using PowerShell
    powershell -Command "(Get-Content device\timers.go) -replace 'RekeyAfterTime\s+=\s+time\.Second \* 120', 'RekeyAfterTime      = time.Second * 7200  // 2 hours (extended from 120s)' | Set-Content device\timers.go"
    powershell -Command "(Get-Content device\timers.go) -replace 'RejectAfterTime\s+=\s+time\.Second \* 180', 'RejectAfterTime     = time.Second * 10800 // 3 hours (extended from 180s)' | Set-Content device\timers.go"
    powershell -Command "(Get-Content device\timers.go) -replace 'KeepaliveTimeout\s+=\s+time\.Second \* 10', 'KeepaliveTimeout    = time.Second * 25 // Increased from 10s' | Set-Content device\timers.go"
)

echo.
echo Verifying patches...
findstr /C:"7200" device\timers.go >nul
if errorlevel 1 (
    echo ERROR: Patch verification failed - RekeyAfterTime not updated
    exit /b 1
)

findstr /C:"10800" device\timers.go >nul
if errorlevel 1 (
    echo ERROR: Patch verification failed - RejectAfterTime not updated
    exit /b 1
)

echo.
echo ======================================
echo ✅ WireGuard-go fork setup complete!
echo ======================================
echo.
echo Patched timers:
echo - RekeyAfterTime:   7200s (2 hours) - was 120s
echo - RejectAfterTime:  10800s (3 hours) - was 180s  
echo - KeepaliveTimeout: 25s - was 10s
echo.
echo Next step: Run build-native.bat to rebuild with patched WireGuard
echo.

cd ..
exit /b 0
