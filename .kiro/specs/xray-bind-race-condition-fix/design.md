# Design Document: XrayBind Race Condition Fix

## Overview

This design addresses a race condition in the `XrayBind` struct where the `reconnect` method can replace the `udpConn` pointer while `processIncoming` and `processOutgoing` goroutines are actively performing I/O operations. The fix implements a thread-safe connection swapping mechanism using `sync.RWMutex` with the local copy pattern to minimize lock contention while ensuring safety.

## Architecture

The solution uses a reader-writer lock pattern:

```
┌─────────────────────────────────────────────────────────────────┐
│                         XrayBind                                 │
├─────────────────────────────────────────────────────────────────┤
│  connMu: sync.RWMutex                                           │
│  udpConn: *bridge.XrayUDPConn                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │processIncoming│  │processOutgoing│  │  reconnect   │          │
│  │   (Reader)    │  │   (Reader)    │  │  (Writer)    │          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘          │
│         │                  │                  │                  │
│         ▼                  ▼                  ▼                  │
│    ┌─────────────────────────────────────────────────┐          │
│    │              sync.RWMutex                        │          │
│    │  • Multiple RLock() allowed concurrently        │          │
│    │  • Lock() blocks all readers and other writers  │          │
│    └─────────────────────────────────────────────────┘          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Local Copy Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│  Hot Path (processIncoming/processOutgoing)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. b.connMu.RLock()           ◄── Acquire read lock            │
│  2. conn := b.udpConn          ◄── Copy pointer to local var    │
│  3. b.connMu.RUnlock()         ◄── Release lock immediately     │
│  4. if conn != nil {                                            │
│       conn.Read/Write(...)     ◄── I/O on local copy (safe)     │
│     }                                                            │
│                                                                  │
│  Benefits:                                                       │
│  • Lock held for nanoseconds, not milliseconds                  │
│  • I/O operations don't block other goroutines                  │
│  • Even if reconnect() runs, local copy remains valid           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### Modified XrayBind Struct

```go
type XrayBind struct {
    xrayWrapper *bridge.XrayWrapper
    endpoint    netip.AddrPort
    udpConn     *bridge.XrayUDPConn

    recvQueue chan []byte
    sendQueue chan []byte

    closed    int32
    closeOnce sync.Once
    connMu    sync.RWMutex  // Changed from sync.Mutex

    // ... rest of fields unchanged
}
```

### Method Changes

| Method | Lock Type | Pattern |
|--------|-----------|---------|
| `processIncoming` | `RLock()` | Local copy, then I/O |
| `processOutgoing` | `RLock()` | Local copy, then I/O |
| `processOutgoingPacket` | `RLock()` | Local copy, then I/O |
| `reconnect` | `Lock()` | Exclusive access for swap |
| `Close` | `Lock()` | Exclusive access for cleanup |
| `sendRawKeepalivePacket` | `RLock()` | Local copy, then I/O |

## Data Models

No changes to data models. The fix only affects synchronization primitives.

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Concurrent Readers Don't Block

*For any* number of concurrent reader goroutines (up to a reasonable limit), when all acquire read locks simultaneously, none SHALL block waiting for another reader.

**Validates: Requirements 1.2**

### Property 2: Readers Block During Write

*For any* reader goroutine attempting to acquire a read lock while a writer holds the write lock, the reader SHALL block until the writer releases the lock.

**Validates: Requirements 4.4**

### Property 3: processIncoming Survives Concurrent Reconnect

*For any* execution where `processIncoming` is reading from the connection and `reconnect` is called concurrently, the `processIncoming` goroutine SHALL NOT panic and SHALL either complete its operation on the old connection or safely skip if the connection was nil.

**Validates: Requirements 2.2**

### Property 4: processOutgoing Survives Concurrent Reconnect

*For any* execution where `processOutgoing` is writing to the connection and `reconnect` is called concurrently, the `processOutgoing` goroutine SHALL NOT panic and SHALL either complete its operation on the old connection or safely skip if the connection was nil.

**Validates: Requirements 3.2**

### Property 5: Connection Swap Atomicity

*For any* reader acquiring a read lock during or after a reconnect operation, the reader SHALL observe either the old connection (before swap) or the new connection (after swap), never an intermediate or corrupted state.

**Validates: Requirements 4.2**

## Error Handling

| Scenario | Handling |
|----------|----------|
| `udpConn` is nil when local copy made | Skip I/O, continue loop |
| I/O error on local copy | Trigger reconnect (existing behavior) |
| Reconnect fails | Log error, leave `udpConn` as nil |
| Deadlock (shouldn't happen with RWMutex) | Go runtime will detect and panic |

## Testing Strategy

### Dual Testing Approach

The fix will be validated using both unit tests and property-based tests:

1. **Unit Tests**: Verify specific scenarios and edge cases
2. **Property-Based Tests**: Verify universal properties hold across many concurrent executions

### Property-Based Testing Framework

- **Library**: Use Go's built-in `testing/quick` package or `github.com/leanovate/gopter` for property-based testing
- **Iterations**: Minimum 100 iterations per property test
- **Tag Format**: `**Feature: xray-bind-race-condition-fix, Property {number}: {property_text}**`

### Test Cases

#### Unit Tests

1. **Test RWMutex Type**: Verify `connMu` is `sync.RWMutex`
2. **Test Nil Connection Handling**: Verify graceful handling when `udpConn` is nil
3. **Test Local Copy Pattern**: Verify local copy is used for I/O

#### Property-Based Tests

Each correctness property will have a corresponding property-based test:

1. **Property 1 Test**: Spawn N reader goroutines, verify all complete without blocking each other
2. **Property 2 Test**: Hold write lock, spawn reader, verify reader blocks
3. **Property 3 Test**: Run processIncoming while triggering reconnect, verify no panic
4. **Property 4 Test**: Run processOutgoing while triggering reconnect, verify no panic
5. **Property 5 Test**: Read connection during reconnect, verify valid state observed

### Test Implementation Notes

- Use `sync.WaitGroup` to coordinate goroutines
- Use channels to signal completion and detect blocking
- Use `recover()` to catch panics in property tests
- Use timeouts to detect deadlocks
- Mock `XrayUDPConn` for controlled testing
