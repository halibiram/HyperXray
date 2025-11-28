# UDP Packet Forwarding Implementation

**Date**: 2025-11-27
**Status**: ✅ COMPLETE
**Component**: WireGuard over Xray-core tunnel

## Overview

This document describes the completed implementation of UDP packet forwarding for the HyperXray project. The implementation enables WireGuard traffic to be tunneled through Xray-core (VLESS+REALITY), creating a double-layer encryption system that bypasses DPI.

## Architecture Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PACKET FLOW DIAGRAM                                 │
└─────────────────────────────────────────────────────────────────────────────┘

1. OUTGOING (Phone → VPS → WARP)
   ┌─────────────┐
   │ WireGuard   │ (generates UDP packet to WARP endpoint)
   └──────┬──────┘
          │
          v
   ┌─────────────┐
   │  XrayBind   │ (custom conn.Bind implementation)
   │  Send()     │
   └──────┬──────┘
          │
          v
   ┌─────────────┐
   │  Instance   │ (Xray instance manager)
   │  SendUDP()  │
   └──────┬──────┘
          │
          v
   ┌─────────────────┐
   │ UDP Send Queue  │ (buffered channel)
   └──────┬──────────┘
          │
          v
   ┌──────────────────────┐
   │ processOutgoingUDP() │ (goroutine)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ SOCKS5UDPClient      │ (encapsulates in SOCKS5 UDP format)
   │ SendUDP()            │
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ Xray SOCKS5 Inbound  │ (localhost:1080)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ Xray VLESS Outbound  │ (REALITY/TLS transport)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │      VPS Server      │ (VLESS server receives encrypted packet)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │   WARP Endpoint      │ (engage.cloudflareclient.com:2408)
   └──────────────────────┘


2. INCOMING (WARP → VPS → Phone)
   ┌──────────────────────┐
   │   WARP Endpoint      │ (sends response)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │      VPS Server      │ (forwards to Xray client)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ Xray VLESS Outbound  │ (decrypts REALITY/TLS)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ Xray SOCKS5 Inbound  │ (sends to SOCKS5 client)
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ SOCKS5UDPClient      │ (de-encapsulates SOCKS5 format)
   │ ReceiveUDP()         │
   └──────┬───────────────┘
          │
          v
   ┌──────────────────────┐
   │ processIncomingUDP() │ (goroutine)
   └──────┬───────────────┘
          │
          v
   ┌─────────────────┐
   │ UDP Recv Queue  │ (buffered channel)
   └──────┬──────────┘
          │
          v
   ┌─────────────┐
   │  Instance   │ (returns via ReceiveUDP() channel)
   │ ReceiveUDP()│
   └──────┬──────┘
          │
          v
   ┌─────────────┐
   │  XrayBind   │ (custom conn.Bind receives packet)
   │ receiveFunc()│
   └──────┬──────┘
          │
          v
   ┌─────────────┐
   │ WireGuard   │ (processes response packet)
   └─────────────┘
```

## Implementation Details

### 1. Xray Configuration Generator (`native/xray/config.go`)

**Status**: ✅ COMPLETE

**Changes**:
- Fixed REALITY settings (was using `privateKey`, now correctly uses `publicKey`)
- Added proper TLS settings for when `security == "tls"`
- Implemented SOCKS5 inbound on localhost:1080 with UDP support
- Added routing rules to direct SOCKS5 traffic to VLESS outbound
- Separated config generation into modular functions

**Generated Config Structure**:
```json
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "tag": "socks-in",
      "port": 1080,
      "listen": "127.0.0.1",
      "protocol": "socks",
      "settings": {
        "auth": "noauth",
        "udp": true,
        "ip": "127.0.0.1"
      }
    }
  ],
  "outbounds": [
    {
      "tag": "vless-out",
      "protocol": "vless",
      "settings": {
        "vnext": [...]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "fingerprint": "chrome",
          "serverName": "...",
          "publicKey": "...",
          "shortId": "..."
        }
      }
    }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      {
        "type": "field",
        "inboundTag": ["socks-in"],
        "outboundTag": "vless-out"
      }
    ]
  }
}
```

### 2. SOCKS5 UDP Client (`native/xray/socks5_udp.go`)

**Status**: ✅ NEW FILE - COMPLETE

**Implementation**: Full SOCKS5 UDP ASSOCIATE protocol implementation

**Features**:
- **TCP Connection**: Maintains persistent TCP connection to keep UDP association alive
- **UDP Association**: Implements SOCKS5 UDP ASSOCIATE command (CMD=0x03)
- **Packet Encapsulation**: Wraps UDP packets in SOCKS5 UDP request format
- **Packet De-encapsulation**: Extracts UDP packets from SOCKS5 UDP response format
- **IPv4 & IPv6 Support**: Handles both address types (ATYP=0x01 and 0x04)
- **Keep-Alive**: Background goroutine maintains TCP connection
- **Thread-Safe**: Uses mutex for concurrent access

**SOCKS5 UDP Packet Format**:
```
Outgoing (SendUDP):
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
| 2  |  1   |  1   | Variable |    2     | Variable |
+----+------+------+----------+----------+----------+

Incoming (ReceiveUDP):
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | SRC.ADDR | SRC.PORT |   DATA   |
+----+------+------+----------+----------+----------+
| 2  |  1   |  1   | Variable |    2     | Variable |
+----+------+------+----------+----------+----------+
```

**Key Methods**:
- `Connect()`: Establishes SOCKS5 TCP connection and UDP association
- `SendUDP()`: Sends UDP packet through SOCKS5 proxy
- `ReceiveUDP()`: Receives UDP packet from SOCKS5 proxy
- `Close()`: Gracefully closes connection
- `keepAlive()`: Maintains TCP connection in background

### 3. Xray Instance Manager (`native/xray/instance.go`)

**Status**: ✅ COMPLETELY REWRITTEN

**Changes**:
- Added `UDPPacket` struct to carry endpoint information with data
- Added `socks5Client` field to Instance struct
- Removed old `udpHandler` (no longer needed)
- Changed `udpSendChan` from `chan []byte` to `chan *UDPPacket`
- Implemented `processOutgoingUDP()` goroutine for sending packets
- Implemented `processIncomingUDP()` goroutine for receiving packets
- Added SOCKS5 client initialization in `Start()`
- Added proper cleanup in `Stop()`

**Packet Flow**:

#### Outgoing Path:
1. `SendUDP(data, endpoint)` called by XrayBind
2. Creates `UDPPacket{Data, Endpoint}` and sends to `udpSendChan`
3. `processOutgoingUDP()` receives packet from channel
4. Calls `socks5Client.SendUDP(packet.Data, packet.Endpoint)`
5. SOCKS5 client encapsulates and sends to Xray's SOCKS5 inbound
6. Xray routes through VLESS outbound to VPS

#### Incoming Path:
1. `processIncomingUDP()` calls `socks5Client.ReceiveUDP(buffer)`
2. SOCKS5 client blocks on UDP read (with timeout)
3. Receives packet from Xray's SOCKS5 inbound
4. De-encapsulates SOCKS5 format
5. Sends data to `udpRecvChan`
6. XrayBind's `receiveFunc()` reads from channel
7. Returns packet to WireGuard

**Timeouts & Error Handling**:
- SOCKS5 connect timeout: 10 seconds
- UDP read timeout: 100 milliseconds (prevents blocking)
- Graceful shutdown with 2-second timeout
- Dropped packets logged when queues are full

### 4. Bridge Integration (`native/bridge/bridge.go`)

**Status**: ✅ NO CHANGES NEEDED

The bridge already correctly passes packets between WireGuard and Xray:
- WireGuard device uses XrayBind as its `conn.Bind`
- XrayBind calls `xrayInstance.SendUDP()` for outgoing
- XrayBind reads from `xrayInstance.ReceiveUDP()` for incoming

### 5. WireGuard Custom Bind (`native/wireguard/xray_bind.go`)

**Status**: ✅ NO CHANGES NEEDED

Already correctly implemented:
- `Send()` → puts packets in `sendQueue`
- `processOutgoing()` → calls `xrayInstance.SendUDP()`
- `processIncoming()` → reads from `xrayInstance.ReceiveUDP()`
- `receiveFunc()` → returns packets to WireGuard from `recvQueue`

## Testing Checklist

Before testing, ensure:

### Build Prerequisites:
- [ ] Go 1.23+ installed
- [ ] Android NDK installed (for CGO cross-compilation)
- [ ] libxray.so built and placed in `app/src/main/jniLibs/arm64-v8a/`

### Build Native Library:
```bash
cd native
go mod tidy
cd ../scripts
./build-native.sh
```

### Test Steps:

#### 1. Basic Library Load
```kotlin
// Should succeed without crashes
System.loadLibrary("hyper")
```

#### 2. WARP Configuration
```kotlin
val warpManager = WarpManager()
val result = warpManager.registerAndGetConfig()
// Should succeed and return WireGuardConfig
```

#### 3. VPN Connection
```kotlin
// Start HyperVpnService
val intent = Intent(context, HyperVpnService::class.java).apply {
    action = HyperVpnService.ACTION_START
}
context.startService(intent)
```

#### 4. Verify Packet Flow
Monitor logs for:
- `✓ Xray-core started`
- `✓ SOCKS5 client connected`
- `✓ WireGuard device up`
- `✓ UDP packets flowing`

#### 5. Test Connectivity
```bash
# On device, verify:
ping 1.1.1.1
curl https://www.cloudflare.com/cdn-cgi/trace/
# Should show: warp=on
```

#### 6. Monitor Statistics
```kotlin
// Native stats should update
val statsJson = getTunnelStats()
// Should show increasing txBytes and rxBytes
```

## Troubleshooting

### Issue: "libxray.so not found"
**Cause**: Xray binary not included in build
**Solution**: Follow build instructions in `app/build.gradle`, task `checkXrayCore`

### Issue: "SOCKS5 connection failed"
**Cause**: Xray not starting or SOCKS5 inbound not binding
**Solution**:
- Check Xray logs
- Verify config file at `filesDir/xray_config.json`
- Ensure port 1080 not already in use

### Issue: "Packets not flowing"
**Cause**: VPS not configured or VLESS credentials wrong
**Solution**:
- Verify Xray server running on VPS
- Check UUID, public key, shortId match
- Test Xray connection without WireGuard first

### Issue: "Connection works but slow"
**Cause**: Double encryption overhead or VPS latency
**Solution**:
- This is expected with double-layer encryption
- Choose VPS closer to your location
- Adjust queue buffer sizes if needed

## Performance Considerations

### Buffer Sizes:
- **UDP Send Queue**: 2048 packets (~8MB at 4KB/packet)
- **UDP Recv Queue**: 2048 packets (~8MB at 4KB/packet)
- **SOCKS5 Send Queue**: 2048 packets (in XrayBind)
- **SOCKS5 Recv Queue**: 2048 packets (in XrayBind)

### Overhead:
- **WireGuard**: ChaCha20-Poly1305 encryption + 32-byte header
- **SOCKS5**: 10-byte header (IPv4) or 22-byte header (IPv6)
- **VLESS**: Minimal overhead (UUID + commands)
- **REALITY/TLS**: TLS 1.3 record overhead (~5 bytes per record)

### Total Overhead per Packet:
```
Original: 1280 bytes (MTU)
+ WireGuard: ~32 bytes
+ SOCKS5: ~10 bytes
+ VLESS: ~40 bytes
+ TLS: ~5 bytes
= ~1367 bytes (7% overhead)
```

## Security Considerations

### Encryption Layers:
1. **Application Data**: Original payload
2. **WireGuard**: ChaCha20-Poly1305 (authenticated encryption)
3. **TLS 1.3**: AES-256-GCM or ChaCha20-Poly1305 (REALITY)

### Attack Resistance:
- **DPI Bypass**: ✅ WireGuard signature hidden in TLS traffic
- **Traffic Analysis**: ✅ REALITY mimics legitimate TLS connections
- **MITM**: ✅ REALITY uses SNI pinning + public key pinning
- **Replay Attacks**: ✅ Both WireGuard and TLS have replay protection

## Future Improvements

### Short-term:
- [ ] Add proper logging instead of fmt.Printf
- [ ] Implement packet drop statistics
- [ ] Add latency measurements
- [ ] Implement automatic reconnection

### Long-term:
- [ ] Support multiple WARP endpoints for failover
- [ ] Implement endpoint selection based on latency
- [ ] Add support for VMess/Trojan in addition to VLESS
- [ ] Optimize buffer sizes based on MTU discovery

## Conclusion

The UDP packet forwarding implementation is **COMPLETE** and ready for testing. All components are in place:

✅ Xray configuration generation
✅ SOCKS5 UDP client
✅ Packet forwarding goroutines
✅ Integration with WireGuard
✅ Error handling and cleanup

The tunnel path is:
```
WireGuard → XrayBind → Instance → SOCKS5Client → Xray → VPS → WARP
```

And reverse for incoming packets.

The **critical gap** identified in `HYPERXRAY_SPEC_IMPLEMENTATION_STATUS.md` has been **CLOSED**.
