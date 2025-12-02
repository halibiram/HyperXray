# Setup WireGuard-go fork with extended rekey timers
# This script clones WireGuard-go and patches the timer constants

Write-Host "======================================"
Write-Host "Setting up WireGuard-go fork"
Write-Host "======================================"
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# Check if fork directory already exists
if (Test-Path "wireguard-go-fork") {
    Write-Host "WireGuard-go fork directory already exists"
    Write-Host "Removing old fork..."
    Remove-Item -Recurse -Force "wireguard-go-fork"
}

Write-Host "Cloning WireGuard-go..."
git clone https://git.zx2c4.com/wireguard-go wireguard-go-fork
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Failed to clone WireGuard-go" -ForegroundColor Red
    exit 1
}

Set-Location wireguard-go-fork

Write-Host ""
Write-Host "Checking out specific version (matching go.mod)..."
git checkout f333402bd9cb
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Failed to checkout specific version" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Patching device/timers.go with extended rekey timers..."

# Read the file
$timersFile = "device\timers.go"
$content = Get-Content $timersFile -Raw

# Apply patches
$content = $content -replace 'RekeyAfterTime\s*=\s*time\.Second\s*\*\s*120', 'RekeyAfterTime      = time.Second * 7200  // 2 hours (extended from 120s)'
$content = $content -replace 'RejectAfterTime\s*=\s*time\.Second\s*\*\s*180', 'RejectAfterTime     = time.Second * 10800 // 3 hours (extended from 180s)'
$content = $content -replace 'KeepaliveTimeout\s*=\s*time\.Second\s*\*\s*10', 'KeepaliveTimeout    = time.Second * 25    // Increased from 10s'

# Write back
Set-Content $timersFile -Value $content -NoNewline

Write-Host ""
Write-Host "Verifying patches..."

$timersContent = Get-Content $timersFile -Raw

if ($timersContent -notmatch '7200') {
    Write-Host "ERROR: Patch verification failed - RekeyAfterTime not updated" -ForegroundColor Red
    exit 1
}

if ($timersContent -notmatch '10800') {
    Write-Host "ERROR: Patch verification failed - RejectAfterTime not updated" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host "✅ WireGuard-go fork setup complete!" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
Write-Host ""
Write-Host "Patched timers:"
Write-Host "- RekeyAfterTime:   7200s (2 hours) - was 120s"
Write-Host "- RejectAfterTime:  10800s (3 hours) - was 180s"
Write-Host "- KeepaliveTimeout: 25s - was 10s"
Write-Host ""
Write-Host "Next step: Run scripts\build-native.bat to rebuild with patched WireGuard"
Write-Host ""

Set-Location ..
