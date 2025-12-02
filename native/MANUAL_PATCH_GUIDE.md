# Manual WireGuard Rekey Timer Patch

## Quick Fix Without Forking

If you can't run the fork setup script, you can manually download and patch WireGuard-go:

### Option 1: Manual Download & Patch

1. **Download WireGuard-go:**
   ```bash
   cd C:\Users\halil\Desktop\hadila
   git clone https://git.zx2c4.com/wireguard-go wireguard-go-fork
   cd wireguard-go-fork
   git checkout f333402bd9cb
   ```

2. **Edit `device/timers.go` manually:**
   
   Open `wireguard-go-fork/device/timers.go` and find these lines around line 14-20:

   **BEFORE:**
   ```go
   const (
       RekeyAfterTime      = time.Second * 120
       RejectAfterTime     = time.Second * 180
       RekeyAttemptTime    = time.Second * 90
       RekeyTimeout        = time.Second * 5
       KeepaliveTimeout    = time.Second * 10
   ```

   **AFTER:**
   ```go
   const (
       RekeyAfterTime      = time.Second * 7200  // 2 hours (extended from 120s)
       RejectAfterTime     = time.Second * 10800 // 3 hours (extended from 180s)
       RekeyAttemptTime    = time.Second * 90
       RekeyTimeout        = time.Second * 5
       KeepaliveTimeout    = time.Second * 25    // Increased from 10s
   ```

3. **Save the file**

4. **Verify the go.mod replace directive exists:**
   Check that `native/go.mod` contains:
   ```go
   replace golang.zx2c4.com/wireguard => ../wireguard-go-fork
   ```
   (Already added ✅)

5. **Rebuild:**
   ```bash
   cd C:\Users\halil\Desktop\hadila\native
   .\scripts\build-native.bat
   ```

### Option 2: Use Pre-built Patch File

Create a file `wireguard-timers.patch` with this content:

```diff
diff --git a/device/timers.go b/device/timers.go
index 1234567..abcdefg 100644
--- a/device/timers.go
+++ b/device/timers.go
@@ -11,11 +11,11 @@ import (
 )
 
 const (
-	RekeyAfterTime      = time.Second * 120
-	RejectAfterTime     = time.Second * 180
+	RekeyAfterTime      = time.Second * 7200  // 2 hours (extended from 120s)
+	RejectAfterTime     = time.Second * 10800 // 3 hours (extended from 180s)
 	RekeyAttemptTime    = time.Second * 90
 	RekeyTimeout        = time.Second * 5
-	KeepaliveTimeout    = time.Second * 10
+	KeepaliveTimeout    = time.Second * 25    // Increased from 10s
 	CookieRefreshTime   = time.Second * 120
 	HandshakeInitiation = time.Second * 0
 	HandshakeResponse   = time.Second * 0
```

Then apply:
```bash
cd wireguard-go-fork
git apply ../wireguard-timers.patch
```

### Option 3: PowerShell One-Liner Patch

If WireGuard-go is already cloned to `wireguard-go-fork`:

```powershell
cd C:\Users\halil\Desktop\hadila\wireguard-go-fork
(Get-Content device\timers.go) -replace 'RekeyAfterTime\s*=\s*time\.Second\s*\*\s*120', 'RekeyAfterTime      = time.Second * 7200  // 2 hours' `
  -replace 'RejectAfterTime\s*=\s*time\.Second\s*\*\s*180', 'RejectAfterTime     = time.Second * 10800 // 3 hours' `
  -replace 'KeepaliveTimeout\s*=\s*time\.Second\s*\*\s*10', 'KeepaliveTimeout    = time.Second * 25    // Increased from 10s' | Set-Content device\timers.go
```

### Verification

Check that the patch was applied:
```bash
cd wireguard-go-fork
grep "7200" device/timers.go
grep "10800" device/timers.go
```

Should output:
```
RekeyAfterTime      = time.Second * 7200  // 2 hours (extended from 120s)
RejectAfterTime     = time.Second * 10800 // 3 hours (extended from 180s)
```

### Then Rebuild

```bash
cd C:\Users\halil\Desktop\hadila\native
.\scripts\build-native.bat
```

This will compile the patched WireGuard-go into your native library.
