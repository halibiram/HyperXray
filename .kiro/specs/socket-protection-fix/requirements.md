# Requirements Document

## Introduction

This document specifies the requirements for fixing the socket protection mechanism in HyperXray VPN application. The current implementation has issues where `VpnService.protect()` is not functioning correctly, causing DNS queries and Xray connections to timeout because traffic is being routed through the wrong network interface (TUN instead of physical interface).

The socket protection mechanism is critical for VPN applications to ensure that the VPN tunnel's own traffic (Xray connections to remote servers) bypasses the VPN routing and uses the physical network interface directly. Without proper socket protection, a routing loop occurs where VPN traffic tries to route through itself.

## Glossary

- **Socket Protection**: The mechanism provided by Android's `VpnService.protect(fd)` method that marks a socket file descriptor to bypass VPN routing
- **TUN Interface**: Virtual network interface created by VPN service (e.g., `tun0`) that captures all device traffic
- **Physical Interface**: Real network interface (e.g., `wlan0`, `rmnet0`) connected to WiFi or mobile data
- **Protected Dialer**: Go component that creates sockets, protects them via JNI callback, and establishes connections
- **JNI Bridge**: C code layer that bridges between Kotlin VPN service and Go native library
- **Xray-core**: Proxy protocol implementation embedded in native Go library
- **WireGuard**: VPN protocol used for WARP tunnel, runs over Xray-core
- **Routing Loop**: Condition where VPN traffic attempts to route through the VPN tunnel itself, causing connection failure

## Requirements

### Requirement 1

**User Story:** As a VPN user, I want socket protection to work reliably, so that my VPN connections can reach remote servers without routing loops.

#### Acceptance Criteria

1. WHEN the VPN service initializes socket protection THEN the System SHALL register the protection callback with the Go native library before any network operations begin
2. WHEN the Go native library creates a socket for Xray connections THEN the System SHALL call `VpnService.protect(fd)` via JNI callback before connecting
3. WHEN `VpnService.protect(fd)` is called THEN the System SHALL return true indicating successful protection
4. WHEN a protected socket attempts to connect THEN the System SHALL route traffic through the physical network interface, not the TUN interface

### Requirement 2

**User Story:** As a VPN user, I want DNS resolution to work correctly, so that domain names can be resolved without timeout.

#### Acceptance Criteria

1. WHEN the System resolves a domain name for Xray server connection THEN the System SHALL use a protected socket for DNS queries
2. WHEN DNS resolution is performed THEN the System SHALL complete within 5 seconds timeout
3. WHEN DNS resolution fails THEN the System SHALL retry with fallback DNS servers (8.8.8.8, 1.1.1.1)
4. WHEN DNS cache contains a valid entry THEN the System SHALL return cached result without network query

### Requirement 3

**User Story:** As a VPN user, I want physical network interface detection to work reliably, so that protected sockets can bind to the correct interface.

#### Acceptance Criteria

1. WHEN the System detects physical network interface THEN the System SHALL identify WiFi (wlan0) or mobile data (rmnet0) interfaces correctly
2. WHEN VPN TUN interface is the default route THEN the System SHALL skip TUN interfaces and find physical interfaces
3. WHEN physical IP lookup fails THEN the System SHALL continue with socket protection only (binding is optional)
4. WHEN network interface changes (WiFi to mobile) THEN the System SHALL invalidate cached physical IP and re-detect

### Requirement 4

**User Story:** As a developer, I want clear diagnostic logging, so that socket protection issues can be debugged effectively.

#### Acceptance Criteria

1. WHEN socket protection is initialized THEN the System SHALL log the initialization status with timestamp
2. WHEN a socket is protected THEN the System SHALL log the file descriptor and protection result
3. WHEN socket protection fails THEN the System SHALL log detailed error information including fd, error code, and stack context
4. WHEN connection timeout occurs THEN the System SHALL log which interface the traffic was routed through

### Requirement 5

**User Story:** As a VPN user, I want the VPN to recover from socket protection failures, so that temporary issues do not require manual restart.

#### Acceptance Criteria

1. WHEN socket protection callback returns false THEN the System SHALL retry protection up to 3 times with 100ms delay
2. WHEN all protection retries fail THEN the System SHALL report error and stop connection attempt
3. WHEN JNI callback fails due to thread detachment THEN the System SHALL re-attach thread and retry
4. WHEN VPN service is recreated THEN the System SHALL re-initialize socket protector before starting tunnel

### Requirement 6

**User Story:** As a developer, I want socket protection state to be verifiable, so that I can confirm protection is working before starting connections.

#### Acceptance Criteria

1. WHEN socket protection is initialized THEN the System SHALL provide a verification method to test protection
2. WHEN verification test runs THEN the System SHALL create a test socket, protect it, and verify routing
3. WHEN verification fails THEN the System SHALL report specific failure reason (callback null, JNI error, protect returned false)
4. WHEN tunnel start is requested THEN the System SHALL verify socket protection before proceeding
