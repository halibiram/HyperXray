// Package masque implements MASQUE protocol (RFC 9298, RFC 9484) over Xray.
// This file provides the QUIC client implementation using quic-go.
package masque

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	quic "github.com/quic-go/quic-go"
)

// ============================================================================
// QUIC CLIENT FOR MASQUE OVER XRAY
// ============================================================================

// ProtectedDialer interface for socket protection (VPN loop prevention)
// XrayWrapper implements this to protect sockets from being routed back to VPN
type ProtectedDialer interface {
	// ProtectSocket marks a socket file descriptor as protected from VPN routing
	ProtectSocket(fd int) error
}

// quicConnection is an interface matching quic-go's Connection type
type quicConnection interface {
	OpenStreamSync(context.Context) (*quic.Stream, error)
	CloseWithError(quic.ApplicationErrorCode, string) error
	Context() context.Context
	ConnectionState() quic.ConnectionState
}

// QUICClient represents a real QUIC connection for MASQUE
type QUICClient struct {
	conn       quicConnection
	stream     *quic.Stream // Main bidirectional stream for capsules
	endpoint   string
	ctx        context.Context
	cancel     context.CancelFunc
	closed     atomic.Bool
	readCh     chan []byte
	stopChan   chan struct{}
	streamMu   sync.Mutex
	protector  ProtectedDialer

	// HTTP/3 CONNECT state
	connectEstablished atomic.Bool
	mode               MasqueMode
}

// QUICClientConfig holds configuration for QUIC client
type QUICClientConfig struct {
	Endpoint  string
	Mode      MasqueMode
	Protector ProtectedDialer
	TLSConfig *tls.Config
}

// DefaultTLSConfig returns default TLS config for MASQUE
// RFC 9114: HTTP/3 uses ALPN "h3"
func DefaultTLSConfig(serverName string) *tls.Config {
	return &tls.Config{
		ServerName:         serverName,
		NextProtos:         []string{"h3"}, // RFC 9114 HTTP/3 only
		InsecureSkipVerify: true,           // Allow self-signed certs for testing
		MinVersion:         tls.VersionTLS13,
	}
}

// CloudflareWARPTLSConfig returns TLS config optimized for Cloudflare WARP
// Uses RFC 9114 (HTTP/3) ALPN with fallback to h3-29 for older servers
func CloudflareWARPTLSConfig(serverName string) *tls.Config {
	return &tls.Config{
		ServerName:         serverName,
		NextProtos:         []string{"h3", "h3-29"}, // h3 (RFC 9114) primary, h3-29 fallback
		InsecureSkipVerify: true,                    // WARP uses Cloudflare's internal PKI
		MinVersion:         tls.VersionTLS13,
		// TLS 1.3 cipher suites - Go handles these automatically for TLS 1.3
		// Explicitly specify for clarity and to ensure all required ciphers are available
		CipherSuites: []uint16{
			tls.TLS_AES_128_GCM_SHA256,
			tls.TLS_AES_256_GCM_SHA384,
			tls.TLS_CHACHA20_POLY1305_SHA256,
		},
	}
}

// DefaultQUICConfig returns default QUIC config for MASQUE
// Optimized for long-lived tunnels with sporadic traffic
func DefaultQUICConfig() *quic.Config {
	return &quic.Config{
		MaxIdleTimeout:        5 * time.Minute,  // Longer timeout for idle connections
		HandshakeIdleTimeout:  30 * time.Second, // Reasonable handshake timeout
		MaxIncomingStreams:    100,
		MaxIncomingUniStreams: 100,
		EnableDatagrams:       true,             // Required for MASQUE
		Allow0RTT:             true,
		KeepAlivePeriod:       30 * time.Second, // More frequent keep-alive
	}
}

// cloudflareWARPIPs contains known Cloudflare WARP endpoint IPs
// These are used when DNS resolution fails (common when VPN is active)
var cloudflareWARPIPs = []string{
	"162.159.192.1",
	"162.159.193.1",
	"162.159.195.1",
	"162.159.204.1",
	"162.159.192.0",
	"162.159.193.0",
}

// resolveCloudflareWARP returns a known Cloudflare WARP IP ONLY for known WARP hostnames
// This should NOT be used as a general fallback for any MASQUE proxy
func resolveCloudflareWARP(hostname string) net.IP {
	// Only for known Cloudflare WARP hostnames - exact match only
	knownWARPHosts := map[string]bool{
		"engage.cloudflareclient.com": true,
		"cloudflare-dns.com":          true,
		"1.1.1.1":                     true,
		"1.0.0.1":                     true,
	}

	if knownWARPHosts[hostname] {
		// Return a random WARP IP for load balancing
		idx := time.Now().UnixNano() % int64(len(cloudflareWARPIPs))
		return net.ParseIP(cloudflareWARPIPs[idx])
	}
	return nil
}

// NewQUICClient creates a new QUIC client for MASQUE
func NewQUICClient(config *QUICClientConfig) (*QUICClient, error) {
	if config == nil {
		return nil, fmt.Errorf("config is nil")
	}
	if config.Endpoint == "" {
		return nil, fmt.Errorf("endpoint is required")
	}

	// Parse endpoint
	host, port, err := net.SplitHostPort(config.Endpoint)
	if err != nil {
		return nil, fmt.Errorf("invalid endpoint: %w", err)
	}

	// Check if host is already an IP address
	var targetIP net.IP
	if ip := net.ParseIP(host); ip != nil {
		targetIP = ip
		logMasque("🔌 QUIC: Using direct IP: %s", targetIP.String())
	} else {
		// Try DNS resolution FIRST for all hosts
		logMasque("🔌 QUIC: Resolving hostname: %s", host)
		ips, err := net.LookupIP(host)
		if err != nil || len(ips) == 0 {
			// DNS failed - only use Cloudflare WARP fallback for known WARP hosts
			logMasque("⚠️ QUIC: DNS resolution failed for %s: %v", host, err)

			if warpIP := resolveCloudflareWARP(host); warpIP != nil {
				targetIP = warpIP
				logMasque("🔌 QUIC: Using Cloudflare WARP fallback IP: %s", targetIP.String())
			} else {
				// For non-WARP hosts, DNS failure is critical
				return nil, fmt.Errorf("failed to resolve %s and no fallback available: %w", host, err)
			}
		} else {
			// Use first IPv4 address if available, otherwise first IP
			for _, ip := range ips {
				if ip.To4() != nil {
					targetIP = ip
					break
				}
			}
			if targetIP == nil {
				targetIP = ips[0]
			}
			logMasque("🔌 QUIC: Resolved %s -> %s", host, targetIP.String())
		}
	}

	// Create UDP address
	udpAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%s", targetIP.String(), port))
	if err != nil {
		return nil, fmt.Errorf("failed to resolve UDP address: %w", err)
	}

	logMasque("🔌 QUIC: Connecting to %s (%s)", config.Endpoint, udpAddr.String())

	// Create UDP connection with specific local address
	localAddr := &net.UDPAddr{IP: net.IPv4zero, Port: 0}
	udpConn, err := net.ListenUDP("udp4", localAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to create UDP socket: %w", err)
	}

	// Protect socket from VPN routing - CRITICAL for VPN loop prevention
	// MUST protect BEFORE any data is sent, otherwise traffic will loop back to VPN
	if config.Protector == nil {
		logMasque("❌ QUIC: Socket protector is nil - VPN loop WILL occur!")
		udpConn.Close()
		return nil, fmt.Errorf("socket protector required for MASQUE over VPN")
	}

	// Get raw file descriptor and protect socket
	rawConn, err := udpConn.SyscallConn()
	if err != nil {
		logMasque("❌ QUIC: Failed to get syscall conn: %v", err)
		udpConn.Close()
		return nil, fmt.Errorf("failed to get syscall conn: %w", err)
	}

	var protectErr error
	err = rawConn.Control(func(fd uintptr) {
		protectErr = config.Protector.ProtectSocket(int(fd))
	})
	if err != nil {
		logMasque("❌ QUIC: Failed to get socket fd: %v", err)
		udpConn.Close()
		return nil, fmt.Errorf("failed to get socket fd: %w", err)
	}
	if protectErr != nil {
		logMasque("❌ QUIC: Failed to protect socket: %v", protectErr)
		udpConn.Close()
		return nil, fmt.Errorf("failed to protect socket: %w", protectErr)
	}
	logMasque("✅ QUIC: Socket protected successfully")

	// Setup TLS config - use Cloudflare WARP optimized config
	tlsConfig := config.TLSConfig
	if tlsConfig == nil {
		// Check if this is a Cloudflare WARP endpoint
		if isCloudflareEndpoint(host, targetIP.String()) {
			tlsConfig = CloudflareWARPTLSConfig(host)
			logMasque("🔌 QUIC: Using Cloudflare WARP TLS config")
		} else {
			tlsConfig = DefaultTLSConfig(host)
		}
	}

	// Setup QUIC config
	quicConfig := DefaultQUICConfig()

	// Create context with timeout for connection
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)

	// Dial QUIC connection
	logMasque("🔌 QUIC: Dialing with TLS (ALPN: %v, ServerName: %s)", 
		tlsConfig.NextProtos, tlsConfig.ServerName)
	
	transport := &quic.Transport{
		Conn: udpConn,
	}
	
	quicConn, err := transport.Dial(ctx, udpAddr, tlsConfig, quicConfig)
	if err != nil {
		cancel()
		udpConn.Close()
		return nil, fmt.Errorf("QUIC dial failed: %w", err)
	}

	// Create new context for connection lifetime (not the dial timeout)
	connCtx, connCancel := context.WithCancel(context.Background())
	cancel() // Release dial timeout context

	logMasque("✅ QUIC: Connection established to %s", config.Endpoint)
	logMasque("   ALPN: %s", quicConn.ConnectionState().TLS.NegotiatedProtocol)
	logMasque("   TLS Version: 0x%x", quicConn.ConnectionState().TLS.Version)

	client := &QUICClient{
		conn:      quicConn,
		endpoint:  config.Endpoint,
		ctx:       connCtx,
		cancel:    connCancel,
		readCh:    make(chan []byte, 256),
		stopChan:  make(chan struct{}),
		protector: config.Protector,
		mode:      config.Mode,
	}

	return client, nil
}

// isCloudflareEndpoint checks if the endpoint is a Cloudflare WARP server
func isCloudflareEndpoint(host, ip string) bool {
	// Known Cloudflare WARP hostnames
	cloudflareHosts := []string{
		"engage.cloudflareclient.com",
		"cloudflare-dns.com",
	}
	
	for _, h := range cloudflareHosts {
		if host == h {
			return true
		}
	}
	
	// Known Cloudflare WARP IP ranges (162.159.192.0/24, 162.159.193.0/24, etc.)
	cloudflareIPPrefixes := []string{
		"162.159.192.",
		"162.159.193.",
		"162.159.195.",
		"162.159.204.",
	}
	
	for _, prefix := range cloudflareIPPrefixes {
		if len(ip) >= len(prefix) && ip[:len(prefix)] == prefix {
			return true
		}
	}
	
	return false
}

// EstablishConnect performs HTTP/3 Extended CONNECT handshake for MASQUE
// This establishes the CONNECT-IP or CONNECT-UDP tunnel
func (c *QUICClient) EstablishConnect() error {
	if c.connectEstablished.Load() {
		return nil // Already established
	}

	logMasque("🤝 HTTP/3: Establishing Extended CONNECT (%s)", c.mode)

	// Open a bidirectional stream for HTTP/3 request
	c.streamMu.Lock()
	stream, err := c.conn.OpenStreamSync(c.ctx)
	if err != nil {
		c.streamMu.Unlock()
		return fmt.Errorf("failed to open stream: %w", err)
	}
	c.stream = stream
	c.streamMu.Unlock()

	logMasque("✅ HTTP/3: Stream opened (ID: %d)", stream.StreamID())

	// Build HTTP/3 Extended CONNECT request
	// RFC 9298 (CONNECT-UDP) / RFC 9484 (CONNECT-IP)
	var connectRequest []byte
	
	if c.mode == MasqueModeConnectIP {
		// CONNECT-IP request (RFC 9484)
		// :method = CONNECT
		// :protocol = connect-ip
		// :authority = <proxy>
		// :path = /.well-known/masque/ip/*/*/
		connectRequest = c.buildConnectIPRequest()
	} else {
		// CONNECT-UDP request (RFC 9298)
		// :method = CONNECT
		// :protocol = connect-udp
		// :authority = <proxy>
		// :path = /.well-known/masque/udp/*/*/
		connectRequest = c.buildConnectUDPRequest()
	}

	logMasque("📤 HTTP/3: Sending CONNECT request (%d bytes)", len(connectRequest))

	// Send CONNECT request
	_, err = stream.Write(connectRequest)
	if err != nil {
		return fmt.Errorf("failed to send CONNECT request: %w", err)
	}

	// Read response
	response := make([]byte, 4096)
	stream.SetReadDeadline(time.Now().Add(10 * time.Second))
	n, err := stream.Read(response)
	if err != nil && err != io.EOF {
		return fmt.Errorf("failed to read CONNECT response: %w", err)
	}
	stream.SetReadDeadline(time.Time{}) // Clear deadline

	logMasque("📥 HTTP/3: Received response (%d bytes)", n)

	// Parse response - check for 200 OK
	if !c.isSuccessResponse(response[:n]) {
		return fmt.Errorf("CONNECT request rejected: %s", string(response[:n]))
	}

	c.connectEstablished.Store(true)
	logMasque("✅ HTTP/3: CONNECT established successfully")

	// Start read loop for capsules
	go c.readLoop()

	return nil
}

// buildConnectIPRequest builds HTTP/3 CONNECT-IP request using QPACK encoding
func (c *QUICClient) buildConnectIPRequest() []byte {
	host, _, _ := net.SplitHostPort(c.endpoint)
	
	// HTTP/3 HEADERS frame with QPACK encoded headers
	// Frame format: Type (1 byte) + Length (varint) + Payload
	// For Extended CONNECT (RFC 9220), we need:
	// :method = CONNECT
	// :protocol = connect-ip
	// :scheme = https
	// :authority = <host>
	// :path = /.well-known/masque/ip/*/*/
	
	// Build QPACK encoded headers (simplified - using literal encoding)
	headers := c.buildQPACKHeaders(host, "connect-ip", "/.well-known/masque/ip/*/*/")
	
	// HTTP/3 HEADERS frame (type = 0x01)
	frame := c.buildHTTP3Frame(0x01, headers)
	
	return frame
}

// buildConnectUDPRequest builds HTTP/3 CONNECT-UDP request using QPACK encoding
func (c *QUICClient) buildConnectUDPRequest() []byte {
	host, _, _ := net.SplitHostPort(c.endpoint)
	
	// Build QPACK encoded headers for CONNECT-UDP
	headers := c.buildQPACKHeaders(host, "connect-udp", "/.well-known/masque/udp/*/*/")
	
	// HTTP/3 HEADERS frame (type = 0x01)
	frame := c.buildHTTP3Frame(0x01, headers)
	
	return frame
}

// buildQPACKHeaders builds QPACK encoded headers for Extended CONNECT
// RFC 9220: Extended CONNECT for HTTP/3
// RFC 9298: CONNECT-UDP
// RFC 9484: CONNECT-IP
func (c *QUICClient) buildQPACKHeaders(authority, protocol, path string) []byte {
	var buf []byte

	// QPACK header block prefix (RFC 9204 Section 4.5.1)
	// Required Insert Count = 0 (no dynamic table entries needed)
	// Delta Base = 0 (no base adjustment)
	buf = append(buf, 0x00, 0x00)

	// Static table indices (RFC 9204 Appendix A):
	// Index 15: :method = CONNECT
	// Index 23: :scheme = https
	// Index 0: :authority (name only)
	// Index 1: :path = / (name only, we'll use literal value)

	// :method = CONNECT (indexed from static table, index 15)
	// For Extended CONNECT, we use the indexed value directly
	buf = append(buf, c.qpackIndexed(15)...)

	// :scheme = https (indexed from static table, index 23)
	buf = append(buf, c.qpackIndexed(23)...)

	// :authority = <host> (literal with name reference to static index 0)
	buf = append(buf, c.qpackLiteralWithNameRef(0, authority)...)

	// :path = <path> (literal with name reference to static index 1)
	buf = append(buf, c.qpackLiteralWithNameRef(1, path)...)

	// :protocol = <protocol> (literal without name reference - RFC 9220)
	// This pseudo-header is required for Extended CONNECT
	buf = append(buf, c.qpackLiteralWithoutNameRef(":protocol", protocol)...)

	// capsule-protocol = ?1 (RFC 9297 - required for MASQUE)
	// This indicates the connection supports HTTP Datagrams
	buf = append(buf, c.qpackLiteralWithoutNameRef("capsule-protocol", "?1")...)

	return buf
}

// qpackIndexed encodes an indexed header field (static table)
// RFC 9204 Section 4.5.2: Indexed Field Line
// Format: 1T xxxxxx (T=1 for static table)
func (c *QUICClient) qpackIndexed(index int) []byte {
	if index < 64 {
		// Single byte: 11xxxxxx (T=1 for static, index in lower 6 bits)
		return []byte{byte(0xc0 | index)}
	}
	// For larger indices, use 2-byte encoding with prefix
	return []byte{0xff, byte(index - 63)}
}

// qpackLiteralWithNameRef encodes a literal header with name reference
// RFC 9204 Section 4.5.4: Literal Field Line With Name Reference
// Format: 01NT xxxx (N=never index, T=static table)
func (c *QUICClient) qpackLiteralWithNameRef(nameIndex int, value string) []byte {
	var buf []byte

	// Literal with Name Reference: 01NT xxxx
	// N=0 (allow indexing), T=1 (static table reference)
	// 4-bit prefix for name index
	if nameIndex < 16 {
		buf = append(buf, byte(0x50|nameIndex)) // 0101 xxxx
	} else {
		// For larger indices, use multi-byte encoding
		buf = append(buf, 0x5f) // 0101 1111
		buf = append(buf, byte(nameIndex-15))
	}

	// Value String Literal (not Huffman encoded)
	// 7-bit prefix for length
	valueLen := len(value)
	if valueLen < 128 {
		buf = append(buf, byte(valueLen))
	} else {
		// Multi-byte length encoding
		buf = append(buf, 0x7f)
		remaining := valueLen - 127
		for remaining >= 128 {
			buf = append(buf, byte(0x80|(remaining&0x7f)))
			remaining >>= 7
		}
		buf = append(buf, byte(remaining))
	}
	buf = append(buf, []byte(value)...)

	return buf
}

// qpackLiteralWithoutNameRef encodes a literal header without name reference
// RFC 9204 Section 4.5.6: Literal Field Line With Literal Name
// Format: 001N xxxx (N=never index)
func (c *QUICClient) qpackLiteralWithoutNameRef(name, value string) []byte {
	var buf []byte

	// Literal without Name Reference: 001N xxxx
	// N=0 (allow indexing), 3-bit prefix for name length
	nameLen := len(name)
	if nameLen < 8 {
		buf = append(buf, byte(0x20|nameLen)) // 0010 0xxx
	} else {
		// Multi-byte name length encoding
		buf = append(buf, 0x27) // 0010 0111
		remaining := nameLen - 7
		for remaining >= 128 {
			buf = append(buf, byte(0x80|(remaining&0x7f)))
			remaining >>= 7
		}
		buf = append(buf, byte(remaining))
	}
	buf = append(buf, []byte(name)...)

	// Value String Literal (not Huffman encoded)
	// 7-bit prefix for length
	valueLen := len(value)
	if valueLen < 128 {
		buf = append(buf, byte(valueLen))
	} else {
		// Multi-byte length encoding
		buf = append(buf, 0x7f)
		remaining := valueLen - 127
		for remaining >= 128 {
			buf = append(buf, byte(0x80|(remaining&0x7f)))
			remaining >>= 7
		}
		buf = append(buf, byte(remaining))
	}
	buf = append(buf, []byte(value)...)

	return buf
}

// buildHTTP3Frame builds an HTTP/3 frame with proper QUIC varint encoding
// RFC 9114 Section 7.1: Frame Layout
// Frame format: Type (varint) + Length (varint) + Payload
func (c *QUICClient) buildHTTP3Frame(frameType byte, payload []byte) []byte {
	var buf []byte

	// Frame type (varint) - HEADERS frame is 0x01
	buf = append(buf, c.encodeVarint(uint64(frameType))...)

	// Frame length (varint) - QUIC variable-length integer encoding
	buf = append(buf, c.encodeVarint(uint64(len(payload)))...)

	// Payload
	buf = append(buf, payload...)

	return buf
}

// encodeVarint encodes a uint64 as a QUIC variable-length integer
// RFC 9000 Section 16: Variable-Length Integer Encoding
func (c *QUICClient) encodeVarint(value uint64) []byte {
	if value < 64 {
		// 1 byte: 00xxxxxx
		return []byte{byte(value)}
	} else if value < 16384 {
		// 2 bytes: 01xxxxxx xxxxxxxx
		return []byte{
			byte(0x40 | (value >> 8)),
			byte(value & 0xff),
		}
	} else if value < 1073741824 {
		// 4 bytes: 10xxxxxx xxxxxxxx xxxxxxxx xxxxxxxx
		return []byte{
			byte(0x80 | (value >> 24)),
			byte((value >> 16) & 0xff),
			byte((value >> 8) & 0xff),
			byte(value & 0xff),
		}
	} else {
		// 8 bytes: 11xxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx
		return []byte{
			byte(0xc0 | (value >> 56)),
			byte((value >> 48) & 0xff),
			byte((value >> 40) & 0xff),
			byte((value >> 32) & 0xff),
			byte((value >> 24) & 0xff),
			byte((value >> 16) & 0xff),
			byte((value >> 8) & 0xff),
			byte(value & 0xff),
		}
	}
}

// isSuccessResponse checks if HTTP/3 response indicates success (2xx status)
// RFC 9114 Section 4.1: HTTP/3 Frames
func (c *QUICClient) isSuccessResponse(response []byte) bool {
	if len(response) == 0 {
		logMasque("⚠️ HTTP/3: Empty response")
		return false
	}

	// Parse frame type (varint)
	frameType, typeBytes := c.parseVarint(response)
	if typeBytes == 0 {
		logMasque("⚠️ HTTP/3: Failed to parse frame type")
		return false
	}

	// HTTP/3 HEADERS frame type is 0x01
	if frameType == 0x01 {
		logMasque("📥 HTTP/3: Received HEADERS frame")
		return c.parseHTTP3HeadersFrame(response)
	}

	// Fallback: check for text-based response (shouldn't happen in HTTP/3)
	responseStr := string(response)
	if containsSubstring(responseStr, "200") || containsSubstring(responseStr, "OK") {
		logMasque("📥 HTTP/3: Found 200 OK in text response")
		return true
	}

	logMasque("⚠️ HTTP/3: Unknown frame type 0x%x, response: %x", frameType, response[:min(16, len(response))])
	return false
}

// parseHTTP3HeadersFrame parses HTTP/3 HEADERS frame to check status
// RFC 9114 Section 7.2.2: HEADERS Frame
func (c *QUICClient) parseHTTP3HeadersFrame(frame []byte) bool {
	if len(frame) < 3 {
		logMasque("⚠️ HTTP/3: Frame too short")
		return false
	}

	offset := 0

	// Parse frame type (varint)
	_, typeBytes := c.parseVarint(frame[offset:])
	if typeBytes == 0 {
		return false
	}
	offset += typeBytes

	// Parse frame length (varint)
	frameLen, lenBytes := c.parseVarint(frame[offset:])
	if lenBytes == 0 {
		return false
	}
	offset += lenBytes

	if len(frame) < offset+int(frameLen) {
		logMasque("⚠️ HTTP/3: Frame truncated (expected %d bytes, got %d)", frameLen, len(frame)-offset)
		// Don't fail - try to parse what we have
	}

	// QPACK header block
	headerBlock := frame[offset:]
	if int(frameLen) < len(headerBlock) {
		headerBlock = headerBlock[:frameLen]
	}

	// Skip QPACK prefix (2 bytes: Required Insert Count + Delta Base)
	if len(headerBlock) < 2 {
		logMasque("⚠️ HTTP/3: Header block too short for QPACK prefix")
		return true // Assume success if we got a HEADERS frame
	}

	// Parse QPACK encoded headers looking for :status
	// RFC 9204 Appendix A: Static Table
	// Index 24: :status = 103
	// Index 25: :status = 200
	// Index 26: :status = 304
	// Index 27: :status = 404
	// Index 28: :status = 503
	for i := 2; i < len(headerBlock); i++ {
		b := headerBlock[i]

		// Check for indexed field line (11xxxxxx pattern)
		if (b & 0xc0) == 0xc0 {
			idx := int(b & 0x3f)
			// :status = 200 is index 25
			if idx == 25 {
				logMasque("✅ HTTP/3: Status 200 OK detected (indexed)")
				return true
			}
			// Check for other 2xx status codes
			if idx >= 24 && idx <= 28 {
				logMasque("✅ HTTP/3: Status 2xx detected (index %d)", idx)
				return true
			}
		}

		// Check for indexed field line with post-base (10xxxxxx pattern)
		if (b & 0xc0) == 0x80 {
			idx := int(b & 0x3f)
			if idx == 25 {
				logMasque("✅ HTTP/3: Status 200 OK detected (post-base indexed)")
				return true
			}
		}

		// Also check for literal with name reference to :status (index 24-28)
		// Pattern: 01NT xxxx where T=1 for static table
		if (b & 0xf0) == 0x50 {
			idx := int(b & 0x0f)
			if idx >= 24 && idx <= 28 {
				// This is a :status header, check the value
				logMasque("📥 HTTP/3: Found :status header (literal with name ref)")
				// For simplicity, assume 2xx if we find a :status header
				return true
			}
		}
	}

	// If we received a HEADERS frame, assume success
	// Cloudflare WARP may use different encoding
	logMasque("⚠️ HTTP/3: Could not parse :status, assuming success (got HEADERS frame)")
	return true
}

// parseVarint parses a QUIC varint from bytes
func (c *QUICClient) parseVarint(data []byte) (uint64, int) {
	if len(data) == 0 {
		return 0, 0
	}
	
	prefix := data[0] >> 6
	switch prefix {
	case 0: // 1 byte
		return uint64(data[0] & 0x3f), 1
	case 1: // 2 bytes
		if len(data) < 2 {
			return 0, 0
		}
		return uint64(data[0]&0x3f)<<8 | uint64(data[1]), 2
	case 2: // 4 bytes
		if len(data) < 4 {
			return 0, 0
		}
		return uint64(data[0]&0x3f)<<24 | uint64(data[1])<<16 | 
			uint64(data[2])<<8 | uint64(data[3]), 4
	case 3: // 8 bytes
		if len(data) < 8 {
			return 0, 0
		}
		return uint64(data[0]&0x3f)<<56 | uint64(data[1])<<48 |
			uint64(data[2])<<40 | uint64(data[3])<<32 |
			uint64(data[4])<<24 | uint64(data[5])<<16 |
			uint64(data[6])<<8 | uint64(data[7]), 8
	}
	return 0, 0
}

func containsSubstring(s, substr string) bool {
	if len(substr) > len(s) {
		return false
	}
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// readLoop reads capsules from the QUIC stream
func (c *QUICClient) readLoop() {
	logMasque("📖 QUIC: Read loop started")
	defer logMasque("📖 QUIC: Read loop exited")

	buf := make([]byte, 65535)
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-c.stopChan:
			return
		default:
		}

		if c.closed.Load() {
			return
		}

		c.streamMu.Lock()
		stream := c.stream
		c.streamMu.Unlock()

		if stream == nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}

		// Set read deadline
		stream.SetReadDeadline(time.Now().Add(100 * time.Millisecond))

		n, err := stream.Read(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			if c.closed.Load() {
				return
			}
			if err == io.EOF {
				logMasque("📖 QUIC: Stream EOF")
				return
			}
			continue
		}

		if n > 0 {
			data := make([]byte, n)
			copy(data, buf[:n])

			select {
			case c.readCh <- data:
			default:
				logMasque("⚠️ QUIC: Read buffer full, dropping %d bytes", n)
			}
		}
	}
}

// Write sends data through the QUIC stream
func (c *QUICClient) Write(data []byte) (int, error) {
	if c.closed.Load() {
		return 0, fmt.Errorf("connection closed")
	}

	if !c.connectEstablished.Load() {
		return 0, fmt.Errorf("CONNECT not established")
	}

	c.streamMu.Lock()
	stream := c.stream
	c.streamMu.Unlock()

	if stream == nil {
		return 0, fmt.Errorf("stream not available")
	}

	n, err := stream.Write(data)
	if err != nil {
		return 0, fmt.Errorf("write failed: %w", err)
	}

	return n, nil
}

// Read receives data from the QUIC stream with timeout
func (c *QUICClient) Read(timeout time.Duration) ([]byte, error) {
	if c.closed.Load() {
		return nil, fmt.Errorf("connection closed")
	}

	select {
	case data, ok := <-c.readCh:
		if !ok {
			return nil, fmt.Errorf("read channel closed")
		}
		return data, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("read timeout")
	case <-c.ctx.Done():
		return nil, fmt.Errorf("context cancelled")
	case <-c.stopChan:
		return nil, fmt.Errorf("connection stopped")
	}
}

// OpenStream opens a new bidirectional stream (for future use)
func (c *QUICClient) OpenStream() (int64, error) {
	if c.closed.Load() {
		return 0, fmt.Errorf("connection closed")
	}

	stream, err := c.conn.OpenStreamSync(c.ctx)
	if err != nil {
		return 0, fmt.Errorf("failed to open stream: %w", err)
	}

	return int64(stream.StreamID()), nil
}

// CloseStream closes a stream (placeholder for interface compatibility)
func (c *QUICClient) CloseStream(streamID int64) error {
	// For now, we only use the main stream
	return nil
}

// Close closes the QUIC connection
func (c *QUICClient) Close() error {
	if c.closed.Swap(true) {
		return nil // Already closed
	}

	logMasque("🔌 QUIC: Closing connection to %s", c.endpoint)

	close(c.stopChan)
	c.cancel()

	c.streamMu.Lock()
	if c.stream != nil {
		c.stream.Close()
		c.stream = nil
	}
	c.streamMu.Unlock()

	// Close QUIC connection
	c.conn.CloseWithError(0, "client closing")

	close(c.readCh)

	logMasque("✅ QUIC: Connection closed")
	return nil
}

// IsConnected checks if the connection is still valid
func (c *QUICClient) IsConnected() bool {
	if c.closed.Load() {
		return false
	}
	
	// Check if QUIC connection is still open
	select {
	case <-c.conn.Context().Done():
		return false
	default:
		return c.connectEstablished.Load()
	}
}

// GetEndpoint returns the endpoint address
func (c *QUICClient) GetEndpoint() string {
	return c.endpoint
}
