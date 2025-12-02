# Requirements Document

## Introduction

This document specifies the requirements for fixing a race condition in the `XrayBind` struct within `native/wireguard/xray_bind.go`. The race condition occurs when the `reconnect` method replaces the `b.udpConn` pointer while `processIncoming` and `processOutgoing` goroutines are actively performing I/O operations on it. The current implementation uses `sync.Mutex` but releases the lock before performing I/O, allowing the connection to become nil or invalid mid-operation.

## Glossary

- **XrayBind**: A Go struct implementing the WireGuard `conn.Bind` interface that routes UDP packets through Xray-core.
- **udpConn**: A pointer to `bridge.XrayUDPConn` representing the active UDP connection through Xray.
- **connMu**: A mutex protecting access to the `udpConn` field.
- **RWMutex**: A reader/writer mutual exclusion lock that allows multiple concurrent readers or one exclusive writer.
- **Race Condition**: A situation where the behavior of software depends on the relative timing of events, leading to unpredictable results.
- **Hot Path**: Code that executes frequently and is performance-critical (e.g., packet processing loops).
- **Local Copy Pattern**: A concurrency pattern where a shared pointer is copied to a local variable under lock, then used after releasing the lock.

## Requirements

### Requirement 1

**User Story:** As a developer, I want the connection mutex to support concurrent readers, so that multiple goroutines can safely read the connection pointer without blocking each other.

#### Acceptance Criteria

1. WHEN the `XrayBind` struct is defined THEN the system SHALL use `sync.RWMutex` instead of `sync.Mutex` for the `connMu` field.
2. WHEN multiple goroutines need to read `udpConn` simultaneously THEN the system SHALL allow concurrent read access using `RLock()`.
3. WHEN the `reconnect` method needs to replace the connection THEN the system SHALL acquire exclusive write access using `Lock()`.

### Requirement 2

**User Story:** As a developer, I want the `processIncoming` method to safely access the connection, so that it does not panic when the connection is replaced during operation.

#### Acceptance Criteria

1. WHEN `processIncoming` needs to read from the connection THEN the system SHALL acquire a read lock using `RLock()`.
2. WHEN `processIncoming` acquires the read lock THEN the system SHALL copy the `udpConn` pointer to a local variable before releasing the lock.
3. WHEN `processIncoming` releases the read lock THEN the system SHALL perform I/O operations on the local copy, not the shared field.
4. IF the local copy is nil THEN the system SHALL skip the I/O operation and continue the loop safely.

### Requirement 3

**User Story:** As a developer, I want the `processOutgoing` method to safely access the connection, so that it does not panic when the connection is replaced during operation.

#### Acceptance Criteria

1. WHEN `processOutgoing` needs to write to the connection THEN the system SHALL acquire a read lock using `RLock()`.
2. WHEN `processOutgoing` acquires the read lock THEN the system SHALL copy the `udpConn` pointer to a local variable before releasing the lock.
3. WHEN `processOutgoing` releases the read lock THEN the system SHALL perform I/O operations on the local copy, not the shared field.
4. IF the local copy is nil THEN the system SHALL skip the I/O operation and handle the packet appropriately.

### Requirement 4

**User Story:** As a developer, I want the `reconnect` method to safely replace the connection, so that ongoing I/O operations complete on the old connection before it is closed.

#### Acceptance Criteria

1. WHEN `reconnect` needs to replace the connection THEN the system SHALL acquire an exclusive write lock using `Lock()`.
2. WHEN `reconnect` holds the write lock THEN the system SHALL close the old connection and assign the new connection atomically.
3. WHEN `reconnect` completes the connection swap THEN the system SHALL release the write lock using `Unlock()`.
4. WHEN `reconnect` holds the write lock THEN all read operations in other goroutines SHALL block until the lock is released.

### Requirement 5

**User Story:** As a developer, I want other methods that access `udpConn` to use appropriate locking, so that the entire codebase is thread-safe.

#### Acceptance Criteria

1. WHEN `sendRawKeepalivePacket` accesses `udpConn` THEN the system SHALL use `RLock()` and the local copy pattern.
2. WHEN `Close` method accesses `udpConn` THEN the system SHALL use `Lock()` for exclusive access since it modifies the connection.
3. WHEN any method reads `udpConn` for checking nil THEN the system SHALL use `RLock()` and copy to local variable.
