# Requirements Document

## Introduction

This document specifies the requirements for fixing port conflict and zombie process issues in HyperXray's native Go bridge. When users restart the VPN quickly, or if the app crashes and restarts, the new VPN session fails to start because old Go routines are still running and holding onto network ports (e.g., Xray inbound ports). This results in "bind: address already in use" errors. The solution involves implementing strict lifecycle management and cleanup mechanisms.

## Glossary

- **HyperTunnel**: The main tunnel instance in `native/bridge/bridge.go` that manages WireGuard and Xray integration
- **Zombie Process**: Go routines that continue running after a VPN session should have ended, holding resources
- **Port Conflict**: Error occurring when a new process attempts to bind to a port already in use by a zombie process
- **Session Context**: A `context.Context` with `CancelFunc` that tracks the lifecycle of an entire VPN session
- **SO_REUSEADDR**: Socket option that allows immediate reuse of ports after a socket is closed
- **Protected Dialer**: A dialer that uses `VpnService.protect()` to prevent VPN routing loops
- **XrayWrapper**: The wrapper around Xray-core instance that manages its lifecycle
- **XrayBind**: The WireGuard bind implementation that routes UDP through Xray

## Requirements

### Requirement 1

**User Story:** As a user, I want to restart the VPN quickly without encountering "address already in use" errors, so that I can reconnect seamlessly after disconnection.

#### Acceptance Criteria

1. WHEN a user calls `StartHyperTunnel` while a previous session exists THEN the system SHALL stop the previous session completely before starting the new one
2. WHEN stopping a previous session THEN the system SHALL wait for cleanup completion with a maximum timeout of 5 seconds
3. IF the previous session cleanup times out THEN the system SHALL force-terminate all resources and proceed with the new session
4. WHEN a new session starts THEN the system SHALL verify no zombie goroutines from the previous session are holding ports

### Requirement 2

**User Story:** As a developer, I want a global session context that tracks the VPN lifecycle, so that all goroutines can be properly cancelled when the session ends.

#### Acceptance Criteria

1. WHEN a new VPN session starts THEN the system SHALL create a new `context.Context` with `CancelFunc` for the session
2. WHEN the VPN session stops THEN the system SHALL call the `CancelFunc` to signal all goroutines to terminate
3. WHILE the session context is cancelled THEN all goroutines using that context SHALL exit gracefully within 2 seconds
4. WHEN creating child goroutines THEN the system SHALL pass the session context to enable coordinated shutdown

### Requirement 3

**User Story:** As a system administrator, I want the VPN to handle OS signals gracefully, so that resources are cleaned up properly when the process is terminated.

#### Acceptance Criteria

1. WHEN the process receives SIGTERM or SIGINT THEN the system SHALL trigger the global session cancellation
2. WHEN signal handling triggers cancellation THEN the system SHALL wait up to 3 seconds for graceful cleanup
3. IF graceful cleanup times out after signal THEN the system SHALL force-close all resources

### Requirement 4

**User Story:** As a user, I want ports to be immediately reusable after VPN stops, so that quick restarts work reliably.

#### Acceptance Criteria

1. WHEN creating TCP listeners for Xray inbounds THEN the system SHALL set the `SO_REUSEADDR` socket option
2. WHEN creating UDP sockets THEN the system SHALL set the `SO_REUSEADDR` socket option
3. WHEN a socket is closed THEN the port SHALL be immediately available for rebinding

### Requirement 5

**User Story:** As a developer, I want atomic state management for the tunnel lifecycle, so that race conditions are prevented during start/stop operations.

#### Acceptance Criteria

1. WHEN multiple goroutines access tunnel state THEN the system SHALL use mutex protection for thread safety
2. WHEN `Start()` is called THEN the system SHALL atomically check and set the running state
3. WHEN `Stop()` is called THEN the system SHALL atomically clear the running state before cleanup
4. IF `Start()` is called while already running THEN the system SHALL return an error without side effects

### Requirement 6

**User Story:** As a developer, I want comprehensive cleanup of all resources, so that no zombie goroutines or leaked file descriptors remain after stop.

#### Acceptance Criteria

1. WHEN `Stop()` is called THEN the system SHALL close all UDP connections tracked in `udpConns` map
2. WHEN `Stop()` is called THEN the system SHALL close the XrayBind before stopping Xray instance
3. WHEN `Stop()` is called THEN the system SHALL close the gRPC client if it exists
4. WHEN `Stop()` is called THEN the system SHALL close the WireGuard device
5. WHEN `Stop()` is called THEN the system SHALL close the TUN device
6. WHEN cleanup completes THEN the system SHALL log the cleanup status for debugging
