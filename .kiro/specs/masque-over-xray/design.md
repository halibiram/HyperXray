# Design Document: MASQUE over Xray

## Overview

MASQUE over Xray, mevcut WireGuard over Xray implementasyonuna alternatif olarak QUIC tabanlı bir tünelleme mekanizması sağlar. Bu tasarım, RFC 9298 (CONNECT-UDP) ve RFC 9484 (CONNECT-IP) standartlarını kullanarak HTTP/3 üzerinden IP/UDP trafiğini Xray proxy'si üzerinden tüneller.

### Neden MASQUE?

| Özellik | WireGuard over Xray | MASQUE over Xray |
|---------|---------------------|------------------|
| Protokol | UDP (custom) | HTTP/3 (QUIC) |
| Sansür Direnci | Orta | Yüksek (HTTPS gibi görünür) |
| CDN Uyumu | Düşük | Yüksek |
| Connection Migration | Yok | QUIC native |
| Multiplexing | Yok | HTTP/3 native |
| Handshake | WireGuard custom | TLS 1.3 |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App                               │
├─────────────────────────────────────────────────────────────────┤
│                      HyperVpnService                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │ TUN Device  │    │ Mode Switch │    │   Stats     │         │
│  │   (fd)      │    │ WG/MASQUE   │    │  Collector  │         │
│  └──────┬──────┘    └──────┬──────┘    └─────────────┘         │
│         │                  │                                     │
│         ▼                  ▼                                     │
│  ┌─────────────────────────────────────────────────────┐       │
│  │                   HyperTunnel                        │       │
│  │  ┌─────────────┐         ┌─────────────┐            │       │
│  │  │  XrayBind   │   OR    │ MasqueBind  │            │       │
│  │  │ (WireGuard) │         │  (MASQUE)   │            │       │
│  │  └──────┬──────┘         └──────┬──────┘            │       │
│  └─────────┼───────────────────────┼───────────────────┘       │
│            │                       │                            │
│            ▼                       ▼                            │
│  ┌─────────────────────────────────────────────────────┐       │
│  │                   XrayWrapper                        │       │
│  │  ┌─────────────┐    ┌─────────────┐                 │       │
│  │  │DialUDP()   │    │DialQUIC()   │  ← NEW          │       │
│  │  │(WireGuard) │    │ (MASQUE)    │                  │       │
│  │  └─────────────┘    └─────────────┘                 │       │
│  └─────────────────────────────────────────────────────┘       │
│                              │                                  │
└──────────────────────────────┼──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Xray-core (libhyperxray.so)                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │   VLESS     │    │   REALITY   │    │  Trojan     │         │
│  │  Outbound   │    │  Outbound   │    │  Outbound   │         │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘         │
└─────────┼──────────────────┼──────────────────┼─────────────────┘
          │                  │                  │
          ▼                  ▼                  ▼
    ┌─────────────────────────────────────────────────┐
    │              Protected Dialer                    │
    │         (VpnService.protect())                   │
    └─────────────────────────────────────────────────┘
                               │
                               ▼
    ┌─────────────────────────────────────────────────┐
    │           MASQUE Proxy Server                    │
    │  (Cloudflare, Custom, etc.)                      │
    └─────────────────────────────────────────────────┘
```

## Components and Interfaces

### 1. MasqueBind (native/masque/masque_bind.go)

`MasqueBind` implements `conn.Bind` interface (same as `XrayBind`) for seamless integration:

```go
type MasqueBind struct {
    xrayWrapper    *bridge.XrayWrapper
    endpoint       string              // MASQUE proxy endpoint
    mode           MasqueMode          // CONNECT_IP or CONNECT_UDP
    
    // QUIC connection (through Xray)
    quicConn       quic.Connection
    stream         quic.Stream         // For CONNECT request
    
    // Packet queues (same pattern as XrayBind)
    recvQueue      chan []byte
    sendQueue      chan []byte
    
    // State management
    closed         int32
    closeOnce      sync.Once
    connMu         sync.RWMutex
    
    // Reconnection
    reconnectMu    sync.Mutex
    reconnectCount int
    lastReconnect  time.Time
    
    // Statistics
    txBytes        uint64
    rxBytes        uint64
    state          ConnectionState
    startTime      time.Time
}

type MasqueMode int
const (
    MasqueModeConnectIP  MasqueMode = iota  // RFC 9484 - Full IP tunneling
    MasqueModeConnectUDP                     // RFC 9298 - UDP only
)

type ConnectionState int
const (
    StateDisconnected ConnectionState = iota
    StateConnecting
    StateConnected
    StateReconnecting
)
```

### 2. Capsule Encoder/Decoder (native/masque/capsule.go)

RFC 9484 capsule format implementation:

```go
// Capsule types (RFC 9484)
const (
    CapsuleTypeAddressAssign   = 0x01
    CapsuleTypeAddressRequest  = 0x02
    CapsuleTypeRouteAdvertise  = 0x03
    CapsuleTypeIPDatagram      = 0x00  // IP packet payload
)

type Capsule struct {
    Type    uint64
    Length  uint64
    Payload []byte
}

// CapsuleEncoder handles IP packet → Capsule conversion
type CapsuleEncoder struct {
    mode MasqueMode
    mtu  int
}

// CapsuleDecoder handles Capsule → IP packet conversion
type CapsuleDecoder struct {
    mode MasqueMode
}

func (e *CapsuleEncoder) Encode(ipPacket []byte) ([]byte, error)
func (d *CapsuleDecoder) Decode(capsuleData []byte) ([]byte, error)
```

### 3. XrayWrapper Extensions (native/bridge/xray.go)

New methods for QUIC/HTTP3 support:

```go
// DialQUIC creates a QUIC connection through Xray outbound
func (x *XrayWrapper) DialQUIC(endpoint string) (quic.Connection, error)

// DialHTTP3 creates an HTTP/3 client through Xray outbound
func (x *XrayWrapper) DialHTTP3(endpoint string) (*http3.RoundTripper, error)
```

### 4. Configuration (native/bridge/bridge.go)

Extended TunnelConfig:

```go
type TunnelConfig struct {
    // Existing fields...
    TunFd          int
    WgConfig       string
    XrayConfig     string
    MTU            int
    
    // New MASQUE fields
    TunnelMode     string  // "wireguard" or "masque"
    MasqueConfig   string  // JSON config for MASQUE
}

type MasqueConfig struct {
    ProxyEndpoint   string      `json:"proxyEndpoint"`   // e.g., "masque.example.com:443"
    Mode            string      `json:"mode"`            // "connect-ip" or "connect-udp"
    MaxReconnects   int         `json:"maxReconnects"`   // Default: 5
    ReconnectDelay  int         `json:"reconnectDelay"`  // Base delay in ms
    QueueSize       int         `json:"queueSize"`       // Packet queue size
}
```

### 5. Settings UI Integration (app/src/main/kotlin/.../ui/settings/)

Tunnel tipi seçimi için Settings ekranına yeni bileşenler:

```kotlin
// TunnelMode enum
enum class TunnelMode(val displayName: String) {
    WIREGUARD("WireGuard over Xray"),
    MASQUE("MASQUE over Xray")
}

// SettingsViewModel extension
class SettingsViewModel : ViewModel() {
    private val _tunnelMode = MutableStateFlow(TunnelMode.WIREGUARD)
    val tunnelMode: StateFlow<TunnelMode> = _tunnelMode.asStateFlow()
    
    fun setTunnelMode(mode: TunnelMode) {
        _tunnelMode.value = mode
        // Persist to DataStore
        viewModelScope.launch {
            settingsRepository.setTunnelMode(mode)
        }
    }
}

// Settings UI Composable
@Composable
fun TunnelModeSelector(
    currentMode: TunnelMode,
    onModeSelected: (TunnelMode) -> Unit
) {
    Column {
        Text("Tunnel Mode", style = MaterialTheme.typography.titleMedium)
        TunnelMode.values().forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(mode) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentMode == mode,
                    onClick = { onModeSelected(mode) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(mode.displayName)
                    Text(
                        text = when (mode) {
                            TunnelMode.WIREGUARD -> "UDP tabanlı, düşük gecikme"
                            TunnelMode.MASQUE -> "HTTP/3 tabanlı, yüksek sansür direnci"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
```

### 6. SettingsRepository Extension

```kotlin
// SettingsRepository.kt
interface SettingsRepository {
    // Existing methods...
    
    // New tunnel mode methods
    suspend fun getTunnelMode(): TunnelMode
    suspend fun setTunnelMode(mode: TunnelMode)
    fun observeTunnelMode(): Flow<TunnelMode>
}

// DataStore keys
object SettingsKeys {
    val TUNNEL_MODE = stringPreferencesKey("tunnel_mode")
}
```

### 7. VPN Service Integration

```kotlin
// HyperVpnService.kt
class HyperVpnService : VpnService() {
    
    private suspend fun getTunnelMode(): TunnelMode {
        return settingsRepository.getTunnelMode()
    }
    
    private fun createTunnelConfig(): TunnelConfig {
        val mode = runBlocking { getTunnelMode() }
        
        return TunnelConfig(
            tunFd = tunFd,
            xrayConfig = xrayConfig,
            tunnelMode = mode.name.lowercase(),  // "wireguard" or "masque"
            // Mode-specific config
            wgConfig = if (mode == TunnelMode.WIREGUARD) wgConfigJson else null,
            masqueConfig = if (mode == TunnelMode.MASQUE) masqueConfigJson else null
        )
    }
}
```

## Data Models

### Capsule Format (RFC 9484)

```
+------------+------------+------------+
| Type (vi)  | Length (vi)| Payload    |
+------------+------------+------------+
     1-8B        1-8B       Variable

vi = Variable-length integer (QUIC encoding)
```

### IP Datagram Capsule

```
+------------+------------+------------+
| 0x00 (vi)  | Length (vi)| IP Packet  |
+------------+------------+------------+
```

### Connection State Machine

```
                    ┌──────────────┐
                    │ Disconnected │
                    └──────┬───────┘
                           │ Connect()
                           ▼
                    ┌──────────────┐
              ┌─────│  Connecting  │─────┐
              │     └──────┬───────┘     │
              │            │ Success     │ Failure
              │            ▼             │
              │     ┌──────────────┐     │
              │     │  Connected   │     │
              │     └──────┬───────┘     │
              │            │ Error       │
              │            ▼             │
              │     ┌──────────────┐     │
              └─────│ Reconnecting │─────┘
                    └──────────────┘
                           │ Max retries
                           ▼
                    ┌──────────────┐
                    │ Disconnected │
                    └──────────────┘
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

Based on the prework analysis, the following properties have been identified. Redundant properties have been consolidated.

### Property 1: Capsule Round-Trip Correctness
*For any* valid IP packet, encoding it to a capsule and then decoding the capsule SHALL produce the original IP packet unchanged.
**Validates: Requirements 7.1, 7.2, 7.5**

### Property 2: Statistics Accuracy
*For any* sequence of sent packets, the txBytes counter SHALL equal the sum of all packet sizes sent. Similarly, *for any* sequence of received packets, the rxBytes counter SHALL equal the sum of all packet sizes received.
**Validates: Requirements 4.1, 4.2**

### Property 3: Queue Bounds Enforcement
*For any* packet queue, when the queue reaches its configured maximum size, subsequent packets SHALL be dropped (not queued), and the queue size SHALL never exceed the configured maximum.
**Validates: Requirements 5.2, 5.3**

### Property 4: Buffer Pool Safety
*For any* buffer returned to the pool, the buffer contents SHALL be reset (zeroed or cleared) before being returned, ensuring no data from previous operations is leaked to subsequent users of the buffer.
**Validates: Requirements 5.4**

### Property 5: Malformed Capsule Resilience
*For any* malformed or invalid capsule data, the decoder SHALL return an error without panicking or crashing, and the system SHALL continue processing subsequent valid capsules.
**Validates: Requirements 7.3**

### Property 6: Configuration Validation
*For any* invalid Xray or MASQUE configuration, the system SHALL detect the invalidity and return a descriptive error before attempting any network connection.
**Validates: Requirements 3.4**

### Property 7: Reconnection Backoff
*For any* sequence of failed connection attempts, the delay between attempts SHALL increase exponentially (within configured bounds), and the total number of attempts SHALL not exceed the configured maximum.
**Validates: Requirements 2.5, 8.2, 8.5**

### Property 8: Resource Cleanup on Close
*For any* MasqueBind instance, after Close() is called, all queues SHALL be drained, all buffers SHALL be returned to the pool, and the QUIC connection SHALL be closed.
**Validates: Requirements 2.4, 5.5**

### Property 9: Mode-Specific Encapsulation
*For any* IP packet in CONNECT-IP mode, the encapsulation SHALL preserve the complete IP header and payload. *For any* UDP datagram in CONNECT-UDP mode, the encapsulation SHALL preserve the UDP header and payload without the IP header.
**Validates: Requirements 6.1, 6.2**

### Property 10: State Consistency
*For any* MasqueBind instance, the reported connection state SHALL accurately reflect the actual connection status (connected, disconnected, reconnecting), and state transitions SHALL follow the defined state machine.
**Validates: Requirements 4.4**

## Error Handling

### Connection Errors

| Error Type | Handling Strategy |
|------------|-------------------|
| DNS Resolution Failed | Return error with endpoint details |
| QUIC Handshake Failed | Retry with backoff, report TLS errors |
| CONNECT Request Rejected | Return HTTP status code and reason |
| Stream Closed | Attempt reconnection |
| Network Unreachable | Queue packets, attempt reconnection |

### Capsule Errors

| Error Type | Handling Strategy |
|------------|-------------------|
| Invalid Capsule Type | Log warning, discard capsule |
| Truncated Capsule | Log error, discard capsule |
| Payload Too Large | Fragment (if enabled) or reject |
| Decode Error | Log error, continue processing |

### Resource Exhaustion

| Condition | Handling Strategy |
|-----------|-------------------|
| Queue Full | Drop packet, log warning |
| Buffer Pool Exhausted | Allocate new buffer (fallback) |
| Memory Pressure | Reduce queue sizes dynamically |

## Testing Strategy

### Dual Testing Approach

Both unit tests and property-based tests will be used:
- **Unit tests**: Verify specific examples, edge cases, and integration points
- **Property-based tests**: Verify universal properties across all valid inputs

### Property-Based Testing Framework

**Framework**: `github.com/leanovate/gopter` (Go property-based testing library)

**Configuration**:
- Minimum 100 iterations per property
- Seed-based reproducibility for debugging
- Shrinking enabled for minimal counterexamples

### Test Categories

#### 1. Capsule Encoding/Decoding Tests
- Round-trip property tests for IP packets
- Malformed input handling
- MTU boundary tests
- Variable-length integer encoding

#### 2. Queue Management Tests
- Bounded queue property tests
- Buffer pool safety tests
- Concurrent access tests

#### 3. State Machine Tests
- State transition correctness
- Reconnection backoff verification
- Resource cleanup verification

#### 4. Integration Tests
- End-to-end packet flow through mock MASQUE server
- Xray configuration routing
- Statistics accuracy

### Test File Structure

```
native/masque/
├── masque_bind.go
├── masque_bind_test.go          # Unit tests
├── masque_bind_property_test.go # Property-based tests
├── capsule.go
├── capsule_test.go              # Unit tests
├── capsule_property_test.go     # Property-based tests
└── testutil/
    └── generators.go            # Test data generators
```

### Property Test Annotations

Each property-based test will be annotated with:
```go
// **Feature: masque-over-xray, Property 1: Capsule Round-Trip Correctness**
// **Validates: Requirements 7.1, 7.2, 7.5**
func TestProperty_CapsuleRoundTrip(t *testing.T) {
    // ...
}
```
