# Implementation Plan

- [x] 1. Update XrayBind struct to use RWMutex


  - [x] 1.1 Change `connMu` field type from `sync.Mutex` to `sync.RWMutex` in the struct definition


    - Modify the `XrayBind` struct in `native/wireguard/xray_bind.go`
    - _Requirements: 1.1_

- [x] 2. Update processIncoming to use local copy pattern


  - [x] 2.1 Refactor connection access in `processIncoming` method


    - Replace `b.connMu.Lock()` with `b.connMu.RLock()`
    - Replace `b.connMu.Unlock()` with `b.connMu.RUnlock()`
    - Copy `b.udpConn` to local variable `conn` under the read lock
    - Perform all I/O operations on the local `conn` variable
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - [ ]* 2.2 Write property test for processIncoming concurrent reconnect safety
    - **Property 3: processIncoming Survives Concurrent Reconnect**
    - **Validates: Requirements 2.2**

- [x] 3. Update processOutgoingPacket to use local copy pattern


  - [x] 3.1 Refactor connection access in `processOutgoingPacket` method


    - Replace `b.connMu.Lock()` with `b.connMu.RLock()`
    - Replace `b.connMu.Unlock()` with `b.connMu.RUnlock()`
    - Copy `b.udpConn` to local variable `conn` under the read lock
    - Perform all I/O operations on the local `conn` variable
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - [ ]* 3.2 Write property test for processOutgoing concurrent reconnect safety
    - **Property 4: processOutgoing Survives Concurrent Reconnect**
    - **Validates: Requirements 3.2**

- [x] 4. Update reconnect method to use exclusive write lock


  - [x] 4.1 Ensure `reconnect` uses `Lock()` for exclusive access


    - Verify `b.connMu.Lock()` and `defer b.connMu.Unlock()` are used (already correct)
    - Ensure connection close and assignment happen atomically under the lock
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - [ ]* 4.2 Write property test for connection swap atomicity
    - **Property 5: Connection Swap Atomicity**
    - **Validates: Requirements 4.2**

- [x] 5. Update sendRawKeepalivePacket to use local copy pattern


  - [x] 5.1 Refactor connection access in `sendRawKeepalivePacket` method


    - Replace `b.connMu.Lock()` with `b.connMu.RLock()`
    - Replace `b.connMu.Unlock()` with `b.connMu.RUnlock()`
    - Copy `b.udpConn` to local variable `conn` under the read lock
    - _Requirements: 5.1_

- [x] 6. Update Close method to use exclusive write lock

  - [x] 6.1 Ensure `Close` uses `Lock()` for exclusive access


    - Verify `b.connMu.Lock()` and `b.connMu.Unlock()` are used (already correct)
    - _Requirements: 5.2_

- [ ] 7. Checkpoint - Verify implementation compiles


  - Ensure all tests pass, ask the user if questions arise.

- [ ]* 8. Write property tests for RWMutex behavior
  - [ ]* 8.1 Write property test for concurrent readers
    - **Property 1: Concurrent Readers Don't Block**
    - **Validates: Requirements 1.2**
  - [ ]* 8.2 Write property test for readers blocking during write
    - **Property 2: Readers Block During Write**
    - **Validates: Requirements 4.4**

- [ ] 9. Final Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.
