# Requirements Document

## Introduction

MASQUE (Multiplexed Application Substrate over QUIC Encryption) over Xray özelliği, mevcut WireGuard over Xray implementasyonuna alternatif olarak QUIC tabanlı bir tünelleme mekanizması sağlar. MASQUE, RFC 9298 (Proxying UDP in HTTP) ve RFC 9484 (Proxying IP in HTTP) standartlarına dayanır ve HTTP/3 üzerinden UDP/IP paketlerini proxy'ler. Bu yaklaşım, WireGuard'ın UDP tabanlı protokolüne kıyasla daha iyi sansür direnci ve CDN uyumluluğu sunar.

## Glossary

- **MASQUE**: Multiplexed Application Substrate over QUIC Encryption - QUIC üzerinden UDP ve IP trafiğini proxy'leyen IETF standardı
- **CONNECT-UDP**: HTTP CONNECT yöntemiyle UDP datagramlarını proxy'leme (RFC 9298)
- **CONNECT-IP**: HTTP CONNECT yöntemiyle IP paketlerini proxy'leme (RFC 9484)
- **QUIC**: Quick UDP Internet Connections - UDP üzerinde çalışan multiplexed transport protokolü
- **HTTP/3**: QUIC üzerinde çalışan HTTP protokolünün son versiyonu
- **Xray-core**: Proxy sunucu yazılımı (VLESS, REALITY, vb. protokolleri destekler)
- **TUN Device**: Android VpnService tarafından sağlanan sanal ağ arayüzü
- **Datagram**: UDP veya IP seviyesinde tek bir paket birimi
- **MasqueBind**: MASQUE protokolünü Xray üzerinden yönlendiren Go bileşeni
- **Capsule**: HTTP/3 datagram kapsülleme formatı

## Requirements

### Requirement 1

**User Story:** As a user, I want to use MASQUE protocol over Xray, so that I can bypass network restrictions that block WireGuard UDP traffic.

#### Acceptance Criteria

1. WHEN the user selects MASQUE mode THEN the System SHALL establish a QUIC connection through Xray-core to the MASQUE proxy server
2. WHEN the MASQUE connection is established THEN the System SHALL tunnel all TUN device traffic through the CONNECT-IP or CONNECT-UDP method
3. WHEN network conditions change THEN the System SHALL leverage QUIC's connection migration to maintain connectivity without reconnection
4. IF the MASQUE proxy server is unreachable THEN the System SHALL report a clear error message indicating the connection failure reason
5. WHEN the user switches between MASQUE and WireGuard modes THEN the System SHALL cleanly terminate the previous connection before establishing the new one

### Requirement 2

**User Story:** As a developer, I want a MasqueBind component that implements the same interface as XrayBind, so that I can easily swap between WireGuard and MASQUE tunneling.

#### Acceptance Criteria

1. WHEN MasqueBind is initialized THEN the System SHALL implement the same conn.Bind interface used by XrayBind
2. WHEN MasqueBind.Send is called with IP packets THEN the System SHALL encapsulate packets using HTTP/3 datagram capsules and send through Xray
3. WHEN MasqueBind receives HTTP/3 datagrams THEN the System SHALL decapsulate and deliver IP packets to the receive queue
4. WHEN MasqueBind.Close is called THEN the System SHALL gracefully close the QUIC connection and release all resources
5. WHEN MasqueBind encounters a connection error THEN the System SHALL attempt automatic reconnection with exponential backoff

### Requirement 3

**User Story:** As a user, I want MASQUE to work with existing Xray configurations, so that I can use my current proxy servers without additional setup.

#### Acceptance Criteria

1. WHEN the user provides an Xray configuration with VLESS/REALITY outbound THEN the System SHALL route MASQUE traffic through that outbound
2. WHEN the MASQUE proxy endpoint is specified THEN the System SHALL resolve the endpoint and establish connection through Xray's protected dialer
3. WHEN Xray configuration includes multiple outbounds THEN the System SHALL use the designated outbound for MASQUE traffic based on routing rules
4. IF the Xray configuration is invalid THEN the System SHALL validate and report configuration errors before attempting connection

### Requirement 4

**User Story:** As a user, I want to see MASQUE connection statistics, so that I can monitor the tunnel performance.

#### Acceptance Criteria

1. WHEN the MASQUE tunnel is active THEN the System SHALL track and expose transmitted bytes count
2. WHEN the MASQUE tunnel is active THEN the System SHALL track and expose received bytes count
3. WHEN the MASQUE tunnel is active THEN the System SHALL track and expose connection uptime
4. WHEN the MASQUE tunnel is active THEN the System SHALL track and expose current connection state (connecting, connected, reconnecting, disconnected)
5. WHEN statistics are requested THEN the System SHALL return current values without blocking the data path

### Requirement 5

**User Story:** As a developer, I want MASQUE implementation to use efficient buffer management, so that the tunnel performs well under high traffic.

#### Acceptance Criteria

1. WHEN processing packets THEN the System SHALL use a sync.Pool for buffer allocation to reduce GC pressure
2. WHEN queuing packets THEN the System SHALL use bounded channels to prevent memory exhaustion under burst traffic
3. WHEN a packet queue is full THEN the System SHALL drop the oldest packets and log a warning
4. WHEN buffers are returned to the pool THEN the System SHALL reset buffer state to prevent data leakage
5. WHEN the tunnel is closed THEN the System SHALL drain all queues and return buffers to the pool

### Requirement 6

**User Story:** As a user, I want MASQUE to support both CONNECT-UDP and CONNECT-IP modes, so that I can choose the appropriate encapsulation for my use case.

#### Acceptance Criteria

1. WHEN CONNECT-IP mode is selected THEN the System SHALL encapsulate raw IP packets (Layer 3) for full IP tunneling
2. WHEN CONNECT-UDP mode is selected THEN the System SHALL encapsulate UDP datagrams (Layer 4) for UDP-only applications
3. WHEN the mode is not specified THEN the System SHALL default to CONNECT-IP for full VPN functionality
4. WHEN switching between modes THEN the System SHALL require tunnel restart to apply the new mode

### Requirement 7

**User Story:** As a developer, I want the MASQUE client to handle HTTP/3 datagram framing correctly, so that packets are properly encapsulated and decapsulated.

#### Acceptance Criteria

1. WHEN sending an IP packet THEN the System SHALL frame the packet according to RFC 9484 capsule format
2. WHEN receiving a capsule THEN the System SHALL parse the capsule header and extract the IP packet payload
3. WHEN a malformed capsule is received THEN the System SHALL discard the capsule and log an error without crashing
4. WHEN the capsule payload exceeds MTU THEN the System SHALL fragment or reject the packet based on configuration
5. WHEN serializing capsules THEN the System SHALL produce output that can be deserialized back to the original packet (round-trip correctness)

### Requirement 8

**User Story:** As a user, I want MASQUE connection to recover from network interruptions, so that I don't lose connectivity during brief network changes.

#### Acceptance Criteria

1. WHEN the underlying network changes (WiFi to cellular) THEN the System SHALL attempt QUIC connection migration
2. WHEN connection migration fails THEN the System SHALL fall back to full reconnection with exponential backoff
3. WHEN reconnection is in progress THEN the System SHALL queue outgoing packets up to the queue limit
4. WHEN reconnection succeeds THEN the System SHALL flush queued packets and resume normal operation
5. WHEN maximum reconnection attempts are exceeded THEN the System SHALL report connection failure to the user

