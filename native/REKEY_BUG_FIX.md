# WireGuard Rekey Bug Fix

## Problem Description

**Bug**: VPN connection drops during WireGuard rekey phase every 2-3 minutes.

**Root Cause**: WireGuard-go uses default timer constants that are too aggressive for connections routed through Xray:
- `RekeyAfterTime` = 120 seconds (2 minutes) - triggers rekey handshake
- `RejectAfterTime` = 180 seconds (3 minutes) - rejects old keys if new handshake not complete
- `KeepaliveTimeout` = 10 seconds

When routing through Xray with potential network latency, the rekey handshake can take longer than expected. If the new handshake takes more than 60 seconds (180s - 120s window), the old keys get rejected and the connection drops.

## Why the Original Code Didn't Work

The file `native/wireguard/timers_override.go` **defines** custom timer constants but they were **never used**!

```go
// These constants are DEFINED but NOT USED by WireGuard-go
const (
    RekeyAfterTime = 2 * time.Hour  // ❌ NOT APPLIED
    RejectAfterTime = 3 * time.Hour // ❌ NOT APPLIED
)
```

The file even has a comment explaining this:
```go
// Note: These constants cannot directly override WireGuard-go's internal values
// because they are defined as package-level constants in device/timers.go.
```

WireGuard-go's timer constants are **package-level** constants defined in `golang.zx2c4.com/wireguard/device/timers.go`. They cannot be overridden from external packages - you must modify the source code directly.

## Solution

We need to patch WireGuard-go's source code to use extended timer values:

### Step 1: Create WireGuard-go Fork

Run the setup script:
```bash
cd native
.\setup-wireguard-fork.bat
```

This script:
1. Clones WireGuard-go from the official repository
2. Checks out the specific version we're using (f333402bd9cb)
3. Patches `device/timers.go` with extended timer values
4. Verifies the patches were applied correctly

### Step 2: Apply go.mod Replace Directive

Already done in `native/go.mod`:
```go
// Replace WireGuard-go with our forked version
replace golang.zx2c4.com/wireguard => ../wireguard-go-fork
```

This tells Go to use our patched fork instead of the upstream version.

### Step 3: Rebuild Native Library

```bash
cd native
.\scripts\build-native.bat
```

## Patched Timer Values

| Timer | Original | Patched | Reason |
|-------|----------|---------|--------|
| **RekeyAfterTime** | 120s (2 min) | 7200s (2 hours) | Reduce rekey frequency dramatically |
| **RejectAfterTime** | 180s (3 min) | 10800s (3 hours) | Give plenty of time for rekey handshake |
| **KeepaliveTimeout** | 10s | 25s | Match Android persistent keepalive setting |

### Why These Values?

1. **RekeyAfterTime = 2 hours**: 
   - Reduces handshake overhead
   - Long-lived mobile connections rarely need frequent rekeys
   - WireGuard is already very secure

2. **RejectAfterTime = 3 hours**:
   - Must be > RekeyAfterTime (WireGuard requirement)
   - Gives 1-hour window for rekey handshake to complete
   - Prevents connection drops even with high latency

3. **KeepaliveTimeout = 25s**:
   - Matches the persistent_keepalive_interval set in IPC config
   - Prevents NAT timeout on mobile networks

## Verification

After rebuilding and running, check the logs:
```bash
adb logcat -s "HyperTunnel:*" "Handshake:*"
```

You should see:
- ✅ Initial handshake succeeds
- ✅ Connection stays stable for hours
- ✅ No "Handshake is stale" warnings every 2-3 minutes

To verify the patched version is being used:
```bash
cd native/wireguard-go-fork
grep -n "7200" device/timers.go
# Should output: RekeyAfterTime = time.Second * 7200
```

## Alternative Solutions (Not Recommended)

1. **Disable rekey entirely**: Not secure - keys should be rotated eventually
2. **Increase keepalive frequency**: Wastes battery and bandwidth
3. **Modify Android VpnService**: Doesn't fix the underlying WireGuard issue

## Files Modified

- ✅ `native/go.mod` - Added replace directive
- ✅ `native/setup-wireguard-fork.bat` - Fork setup script
- ✅ `native/wireguard-go-fork/device/timers.go` - Patched timer constants (generated)

## Testing

1. Connect VPN
2. Let it run for 5+ minutes
3. Old behavior: Connection drops at ~2-3 min mark
4. New behavior: Connection stays stable indefinitely

## References

- WireGuard-go source: https://git.zx2c4.com/wireguard-go
- WireGuard protocol spec: https://www.wireguard.com/protocol/
- Related timers documentation: https://www.wireguard.com/papers/wireguard.pdf (Section 5.4.6)
