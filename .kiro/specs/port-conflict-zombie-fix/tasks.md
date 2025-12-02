# Implementation Plan

- [x] 1. Add session context and lifecycle management to HyperTunnel




  - [ ] 1.1 Add session context fields to HyperTunnel struct
    - Add `sessionCtx context.Context` field
    - Add `sessionCancel context.CancelFunc` field
    - Add `cleanupWg sync.WaitGroup` field
    - Add `cleanupDone chan struct{}` field
    - _Requirements: 2.1, 2.2_
  - [x]* 1.2 Write property test for context lifecycle


    - **Property 2: Context Lifecycle**
    - **Validates: Requirements 2.1, 2.2**
  - [x] 1.3 Update Start() to create session context


    - Create new context with cancel at start of Start()
    - Store context and cancel function in struct
    - Pass context to all child goroutines (collectStats, monitorHandshake)




    - _Requirements: 2.1, 2.4_
  - [ ] 1.4 Update Stop() to cancel session context
    - Call sessionCancel() at beginning of Stop()
    - Wait for cleanupWg with timeout
    - Signal cleanupDone channel when complete
    - _Requirements: 2.2_


- [ ] 2. Implement force cleanup on Start
  - [ ] 2.1 Add global session manager variables in lib.go
    - Add `var globalSessionCtx context.Context`
    - Add `var globalSessionCancel context.CancelFunc`
    - Add `var sessionMu sync.Mutex` for thread safety
    - _Requirements: 1.1, 5.1_
  - [x]* 2.2 Write property test for session restart cleanup




    - **Property 1: Session Restart Cleanup**
    - **Validates: Requirements 1.1, 1.4**
  - [ ] 2.3 Implement cleanup wait with timeout in StartHyperTunnel
    - Check if previous session exists
    - Cancel previous session context
    - Wait up to 5 seconds for cleanup


    - Force terminate if timeout
    - _Requirements: 1.1, 1.2, 1.3_


  - [x]* 2.4 Write property test for cleanup timeout enforcement



    - **Property 7: Cleanup Timeout Enforcement**
    - **Validates: Requirements 1.2, 1.3**

- [x] 3. Implement SO_REUSEADDR for socket reuse

  - [ ] 3.1 Create socket utility functions in new file native/bridge/socket.go
    - Implement `SetReuseAddr(fd int) error` using syscall
    - Implement `CreateReuseableListener(network, address string) (net.Listener, error)`
    - Implement `CreateReuseableUDPConn(network, address string) (*net.UDPConn, error)`
    - _Requirements: 4.1, 4.2_
  - [ ]* 3.2 Write property test for port reuse
    - **Property 4: Port Reuse**




    - **Validates: Requirements 4.1, 4.2, 4.3**
  - [ ] 3.3 Update XrayWrapper to use reuseable sockets
    - Modify socket creation in Xray config to use SO_REUSEADDR
    - Update any direct socket creation to use utility functions
    - _Requirements: 4.1, 4.2, 4.3_


- [ ] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.


- [ ] 5. Implement signal handling for graceful shutdown
  - [x] 5.1 Add signal handler in lib.go



    - Create signal channel for SIGTERM, SIGINT
    - Start goroutine to listen for signals
    - Trigger global session cancellation on signal
    - _Requirements: 3.1_
  - [ ] 5.2 Implement graceful shutdown with timeout
    - Wait up to 3 seconds for cleanup after signal
    - Force close if timeout

    - _Requirements: 3.2, 3.3_
  - [ ]* 5.3 Write unit test for signal handling
    - Test SIGTERM triggers cancellation
    - Test timeout behavior
    - _Requirements: 3.1, 3.2, 3.3_


- [ ] 6. Implement atomic state management
  - [ ] 6.1 Add state enum and atomic state field
    - Define SessionState enum (Idle, Starting, Running, Stopping, Stopped)
    - Add `state atomic.Int32` field to HyperTunnel



    - _Requirements: 5.2, 5.3_
  - [ ]* 6.2 Write property test for atomic state management
    - **Property 5: Atomic State Management**
    - **Validates: Requirements 5.2, 5.3, 5.4**
  - [ ] 6.3 Update Start() with atomic state transitions
    - Use CompareAndSwap for state transitions
    - Return error if already running
    - _Requirements: 5.2, 5.4_

  - [ ] 6.4 Update Stop() with atomic state transitions
    - Set state to Stopping before cleanup
    - Set state to Stopped after cleanup
    - _Requirements: 5.3_


- [ ] 7. Implement comprehensive resource cleanup
  - [ ] 7.1 Update cleanup() to track all resources
    - Track UDP connections in map



    - Track cleanup status for each resource
    - Log cleanup progress
    - _Requirements: 6.1, 6.6_
  - [ ]* 7.2 Write property test for resource cleanup completeness
    - **Property 6: Resource Cleanup Completeness**
    - **Validates: Requirements 6.1, 6.3, 6.4, 6.5**
  - [ ] 7.3 Implement ordered cleanup sequence
    - Close UDP connections first
    - Close XrayBind second
    - Stop Xray instance third
    - Close WireGuard device fourth
    - Close TUN device last
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
  - [ ] 7.4 Add cleanup result logging
    - Log each resource closed
    - Log total cleanup duration
    - Log any errors encountered
    - _Requirements: 6.6_

- [ ] 8. Update goroutines to respect session context
  - [ ] 8.1 Update collectStats goroutine
    - Accept session context as parameter
    - Check context.Done() in select
    - Exit gracefully on cancellation
    - Decrement cleanupWg on exit
    - _Requirements: 2.3, 2.4_
  - [ ]* 8.2 Write property test for goroutine cleanup timeout
    - **Property 3: Goroutine Cleanup Timeout**
    - **Validates: Requirements 2.3**
  - [ ] 8.3 Update monitorHandshake goroutine
    - Accept session context as parameter
    - Check context.Done() in select
    - Exit gracefully on cancellation
    - Decrement cleanupWg on exit
    - _Requirements: 2.3, 2.4_
  - [ ] 8.4 Update XrayUDPConn readLoop goroutine
    - Check context.Done() in select
    - Exit gracefully on cancellation
    - _Requirements: 2.3, 2.4_

- [ ] 9. Final Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.
