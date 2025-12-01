# Implementation Plan

## Socket Protection Fix

- [x] 1. Enhance JNI Socket Protector with Retry Mechanism




  - [x] 1.1 Add SocketProtectorState struct with retry configuration

    - Add struct with jvm, vpnService, protectMethod, maxRetries, retryDelayMs, initialized, verified fields
    - Initialize default values (maxRetries=3, retryDelayMs=100)
    - _Requirements: 5.1, 5.2_

  - [x] 1.2 Implement socket_protector_callback_with_retry function

    - Add retry loop with configurable max retries
    - Add delay between retries using usleep()
    - Log each retry attempt with fd and attempt number
    - Return false only after all retries exhausted
    - _Requirements: 5.1, 5.2_
  - [ ]* 1.3 Write property test for retry mechanism
    - **Property 9: Retry Mechanism**
    - **Validates: Requirements 5.1, 5.2**
  - [x] 1.4 Add thread re-attachment on JNI_EDETACHED

    - Check GetEnv result for JNI_EDETACHED
    - Re-attach thread and retry protection
    - Log thread attachment status
    - _Requirements: 5.3_





- [x] 2. Add Socket Protection Verification
  - [x] 2.1 Implement verify_socket_protection function in JNI
    - Create test socket (AF_INET, SOCK_STREAM)
    - Call protect callback on test socket
    - Close test socket
    - Return verification result with detailed status
    - _Requirements: 6.1, 6.2_
  - [x] 2.2 Add verification check before tunnel start
    - Call verify_socket_protection() in startHyperTunnel
    - Return error code -35 if verification fails
    - Log verification result
    - _Requirements: 6.4_
  - [ ]* 2.3 Write property test for verification error specificity
    - **Property 10: Verification Error Specificity**
    - **Validates: Requirements 6.3, 6.4**
  - [x] 2.4 Export isSocketProtectorVerified JNI function
    - Add JNI function to check verification status
    - Allow Kotlin code to query protection state
    - _Requirements: 6.1_


- [x] 3. Checkpoint - Ensure JNI changes compile and tests pass

  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Enhance Go ProtectedDialer with Diagnostics
  - [x] 4.1 Add ProtectionResult struct
    - Add Success, Retries, Error, Interface, PhysicalIP fields
    - Add JSON serialization for logging
    - _Requirements: 4.2, 4.3_
  - [x] 4.2 Implement DialTCPWithDiagnostics method
    - Return connection and ProtectionResult
    - Track retry count and timing
    - Log detailed diagnostics on failure
    - _Requirements: 4.2, 4.3_
  - [ ]* 4.3 Write property test for protection before connect
    - **Property 2: Protection Before Connect**
    - **Validates: Requirements 1.2**
  - [x] 4.4 Add protection call ordering verification
    - Add flag to track if protect was called
    - Assert protect called before connect in debug builds
    - _Requirements: 1.2_

- [x] 5. Improve Physical Interface Detection
  - [x] 5.1 Refactor getPhysicalIPFromInterfacesWithIface
    - Add explicit TUN interface filtering (tun*, wg*)
    - Prioritize wlan* over rmnet* interfaces
    - Add detailed logging for each interface checked
    - _Requirements: 3.1, 3.2_
  - [ ]* 5.2 Write property test for interface detection
    - **Property 6: Physical Interface Detection**
    - **Validates: Requirements 3.1, 3.2**
  - [x] 5.3 Add graceful fallback when no physical IP found
    - Return nil IP without error
    - Log warning but continue with protection only
    - _Requirements: 3.3_
  - [ ]* 5.4 Write property test for optional binding resilience
    - **Property 7: Optional Binding Resilience**
    - **Validates: Requirements 3.3**

- [ ] 6. Enhance Physical IP Cache
  - [ ] 6.1 Add InvalidatePhysicalIPCache export function
    - Clear cached IP, timestamp, and interface
    - Log cache invalidation
    - _Requirements: 3.4_
  - [ ] 6.2 Add cache expiration check in GetLocalPhysicalIP
    - Check timestamp against expiration duration
    - Trigger async refresh if expired
    - Return stale value while refreshing
    - _Requirements: 3.4_
  - [ ]* 6.3 Write property test for cache invalidation
    - **Property 8: Cache Invalidation**
    - **Validates: Requirements 3.4**


- [ ] 7. Checkpoint - Ensure Go changes compile and tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Add DNS Protection


  - [x] 8.1 Ensure DNS resolver uses ProtectedDialer

    - Verify net.Resolver uses protected dialer
    - Add custom resolver with protected transport



    - _Requirements: 2.1_
  - [ ]* 8.2 Write property test for DNS uses protected socket
    - **Property 3: DNS Uses Protected Socket**

    - **Validates: Requirements 2.1**

  - [ ] 8.3 Add DNS fallback servers
    - Add fallback to 8.8.8.8 and 1.1.1.1 on primary failure
    - Implement retry with fallback servers
    - _Requirements: 2.3_
  - [x]* 8.4 Write property test for DNS fallback



    - **Property 4: DNS Fallback on Failure**

    - **Validates: Requirements 2.3**
  - [x] 8.5 Verify DNS cache round-trip

    - Ensure cached entries are returned without network query
    - Add TTL-based expiration
    - _Requirements: 2.4_

  - [ ]* 8.6 Write property test for DNS cache round trip
    - **Property 5: DNS Cache Round Trip**
    - **Validates: Requirements 2.4**


- [x] 9. Add Initialization Completeness Check
  - [x] 9.1 Add protector state tracking in Go
    - Add ProtectionState enum (Uninitialized, Initialized, Verified, Failed)
    - Track state transitions
    - _Requirements: 1.1_
  - [x] 9.2 Add GetProtectionState export function
    - Return current protection state
    - Allow querying from JNI/Kotlin
    - _Requirements: 1.1_
  - [ ]* 9.3 Write property test for initialization completeness
    - **Property 1: Initialization Completeness**
    - **Validates: Requirements 1.1, 5.4**

- [x] 10. Enhance Diagnostic Logging
  - [x] 10.1 Add structured DiagnosticLog type
    - Include Timestamp, Operation, Fd, Success, Error, Interface, PhysicalIP, Duration
    - Add JSON serialization
    - _Requirements: 4.1, 4.2, 4.3_
  - [x] 10.2 Add log collection for debugging
    - Store last N diagnostic logs in memory
    - Add GetDiagnosticLogs export function
    - _Requirements: 4.1, 4.2, 4.3_
  - [ ] 10.3 Add Kotlin-side log retrieval
    - Add JNI function to get diagnostic logs
    - Display in debug UI or export to file
    - _Requirements: 4.1_

- [ ] 11. Update HyperVpnService Kotlin Code
  - [ ] 11.1 Add verification call before startVpn
    - Call isSocketProtectorVerified() before starting tunnel
    - Show error to user if verification fails
    - _Requirements: 6.4_
  - [ ] 11.2 Add network change listener for cache invalidation
    - Listen for ConnectivityManager network changes
    - Call native cache invalidation on network change
    - _Requirements: 3.4_
  - [ ] 11.3 Add diagnostic log retrieval for debugging
    - Add method to retrieve native diagnostic logs
    - Log to AiLogHelper for debugging
    - _Requirements: 4.1_


- [x] 12. C-Based Socket Creation (Bypass Go Runtime)
  - [x] 12.1 Add C socket creator in JNI
    - Create `create_protected_socket_c()` function that creates socket via C `socket()` call
    - Protect socket immediately after creation via `g_protector` callback
    - Return fd on success, -1 on error
    - _Requirements: 1.2 (Protection Before Connect)_
  - [x] 12.2 Add CGO bridge in Go
    - Add CGO header with `c_socket_creator_func` type
    - Export `SetCSocketCreator` function for JNI to register callback
    - Add `CreateProtectedSocketViaC()` helper function
    - _Requirements: 1.2_
  - [x] 12.3 Update DialTCP to use C socket creator
    - Check if C socket creator is available via `IsCSocketCreatorAvailable()`
    - Use C socket creation as primary method (bypasses Go runtime)
    - Fallback to Go syscall.Socket if C creation fails
    - Skip Go-side protection if socket already protected via C
    - _Requirements: 1.2_
  - [x] 12.4 Update DialUDP to use C socket creator
    - Same pattern as DialTCP for UDP sockets
    - _Requirements: 1.2_
  - [x] 12.5 Register C socket creator in initSocketProtector
    - Call `register_c_socket_creator()` after setting up protector
    - Log registration status
    - _Requirements: 1.1_

- [ ] 13. Final Checkpoint - Ensure all tests pass

  - Ensure all tests pass, ask the user if questions arise.
