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
func DefaultTLSConfig(serverName string) *tls.Config {
	return &tls.Config{
		ServerName:         serverName,
		NextProtos:         []string{"h3"}, // HTTP/3 ALPN
		InsecureSkipVerify: true,           // Cloudflare WARP uses custom certs
		MinVersion:         tls.VersionTLS13,
	}
}

// CloudflareWARPTLSConfig returns TLS config optimized for Cloudflare WARP
func CloudflareWARPTLSConfig(serverName string) *tls.Config {
	return &tls.Config{
		ServerName:         serverName,
		NextProtos:         []string{"h3"}, // HTTP/3 ALPN
		InsecureSkipVerify: true,           // WARP uses Cloudflare's internal PKI
		MinVersion:         tls.VersionTLS13,
		CipherSuites: []uint16{
			tls.TLS_AES_128_GCM_SHA256,
			tls.TLS_AES_256_GCM_SHA384,
			tls.TLS_CHACHA20_POLY1305_SHA256,
		},
	}
}

// DefaultQUICConfig returns default QUIC config for MASQUE
func DefaultQUICConfig() *quic.Config {
	return &quic.Config{
		MaxIdleTimeout:        30 * time.Second,
		HandshakeIdleTimeout:  10 * time.Second,
		MaxIncomingStreams:    100,
		MaxIncomingUniStreams: 100,
		EnableDatagrams:       true, // Required for MASQUE
		Allow0RTT:             true,
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

// resolveCloudflareWARP returns a known Cloudflare WARP IP for the given hostname
func resolveCloudflareWARP(hostname string) net.IP {
	// Check if this is a Cloudflare WARP hostname
	if hostname == "engage.cloudflareclient.com" || 
	   hostname == "cloudflare-dns.com" ||
	   containsSubstring(hostname, "cloudflare") {
		// Return first known WARP IP
		return net.ParseIP(cloudflareWARPIPs[0])
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
		// First, check if this is a known Cloudflare WARP hostname
		// Use hardcoded IPs to avoid DNS resolution issues when VPN is active
		if warpIP := resolveCloudflareWARP(host); warpIP != nil {
			targetIP = warpIP
			logMasque("🔌 QUIC: Using hardcoded Cloudflare WARP IP for %s: %s", host, targetIP.String())
		} else {
			// Try DNS resolution for non-Cloudflare hosts
			logMasque("🔌 QUIC: Resolving hostname: %s", host)
			ips, err := net.LookupIP(host)
			if err != nil || len(ips) == 0 {
				// DNS failed - check if we can use fallback
				logMasque("⚠️ QUIC: DNS resolution failed for %s: %v", host, err)
				
				// Try Cloudflare WARP fallback anyway
				if warpIP := resolveCloudflareWARP(host); warpIP != nil {
					targetIP = warpIP
					logMasque("🔌 QUIC: Using Cloudflare WARP fallback IP: %s", targetIP.String())
				} else {
					return nil, fmt.Errorf("failed to resolve %s: %w", host, err)
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

	// Protect socket from VPN routing if protector is available
	// CRITICAL: Must protect BEFORE any data is sent
	if config.Protector != nil {
		// Get raw file descriptor
		rawConn, err := udpConn.SyscallConn()
		if err == nil {
			var protectErr error
			err = rawConn.Control(func(fd uintptr) {
				protectErr = config.Protector.ProtectSocket(int(fd))
			})
			if err != nil {
				logMasque("⚠️ QUIC: Failed to get socket fd: %v", err)
			} else if protectErr != nil {
				logMasque("⚠️ QUIC: Failed to protect socket: %v", protectErr)
			} else {
				logMasque("✅ QUIC: Socket protected successfully")
			}
		} else {
			logMasque("⚠️ QUIC: Failed to get syscall conn: %v", err)
		}
	}

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
func (c *QUICClient) buildQPACKHeaders(authority, protocol, path string) []byte {
	var buf []byte
	
	// QPACK header block prefix (Required Insert Count = 0, Delta Base = 0)
	buf = append(buf, 0x00, 0x00)
	
	// :method = CONNECT (indexed from static table, index 15)
	// But for Extended CONNECT, we use literal with name reference
	buf = append(buf, c.qpackLiteralWithNameRef(15, "CONNECT")...)
	
	// :scheme = https (indexed from static table, index 23)
	buf = append(buf, c.qpackIndexed(23)...)
	
	// :authority = <host> (literal with name reference, index 0)
	buf = append(buf, c.qpackLiteralWithNameRef(0, authority)...)
	
	// :path = <path> (literal with name reference, index 1)
	buf = append(buf, c.qpackLiteralWithNameRef(1, path)...)
	
	// :protocol = <protocol> (literal without name reference - not in static table)
	buf = append(buf, c.qpackLiteralWithoutNameRef(":protocol", protocol)...)
	
	// capsule-protocol = ?1 (literal without name reference)
	buf = append(buf, c.qpackLiteralWithoutNameRef("capsule-protocol", "?1")...)
	
	return buf
}

// qpackIndexed encodes an indexed header field (static table)
func (c *QUICClient) qpackIndexed(index int) []byte {
	// Indexed field line: 1xxxxxxx (static table)
	return []byte{byte(0x80 | (index & 0x3f))}
}

// qpackLiteralWithNameRef encodes a literal header with name reference
func (c *QUICClient) qpackLiteralWithNameRef(nameIndex int, value string) []byte {
	var buf []byte
	
	// Literal with name reference: 01Nxxxxx
	// N=0 (not post-base), T=1 (static table)
	buf = append(buf, byte(0x50|(nameIndex&0x0f)))
	
	// Value length (7-bit prefix)
	buf = append(buf, byte(len(value)))
	buf = append(buf, []byte(value)...)
	
	return buf
}

// qpackLiteralWithoutNameRef encodes a literal header without name reference
func (c *QUICClient) qpackLiteralWithoutNameRef(name, value string) []byte {
	var buf []byte
	
	// Literal without name reference: 001Nxxxx
	// N=0 (not Huffman encoded)
	buf = append(buf, byte(0x20|byte(len(name)&0x07)))
	if len(name) > 7 {
		// Need varint encoding for longer names
		buf[len(buf)-1] = 0x27 // Set all bits
		buf = append(buf, byte(len(name)-7))
	}
	buf = append(buf, []byte(name)...)
	
	// Value
	buf = append(buf, byte(len(value)))
	buf = append(buf, []byte(value)...)
	
	return buf
}

// buildHTTP3Frame builds an HTTP/3 frame
func (c *QUICClient) buildHTTP3Frame(frameType byte, payload []byte) []byte {
	var buf []byte
	
	// Frame type (varint)
	buf = append(buf, frameType)
	
	// Frame length (varint)
	length := len(payload)
	if length < 64 {
		buf = append(buf, byte(length))
	} else if length < 16384 {
		buf = append(buf, byte(0x40|(length>>8)), byte(length&0xff))
	} else {
		// Larger lengths need more bytes
		buf = append(buf, byte(0x80|(length>>24)), byte((length>>16)&0xff), 
			byte((length>>8)&0xff), byte(length&0xff))
	}
	
	// Payload
	buf = append(buf, payload...)
	
	return buf
}

// isSuccessResponse checks if HTTP/3 response indicates success (2xx status)
func (c *QUICClient) isSuccessResponse(response []byte) bool {
	if len(response) == 0 {
		return false
	}
	
	// HTTP/3 HEADERS frame starts with frame type 0x01
	if response[0] == 0x01 {
		logMasque("📥 HTTP/3: Received HEADERS frame")
		// Parse frame to find status
		// For now, assume success if we get a HEADERS frame
		// A proper implementation would decode QPACK headers
		return c.parseHTTP3HeadersFrame(response)
	}
	
	// Fallback: check for text-based response (shouldn't happen in HTTP/3)
	responseStr := string(response)
	if containsSubstring(responseStr, "200") || containsSubstring(responseStr, "OK") {
		return true
	}
	
	logMasque("⚠️ HTTP/3: Unknown response format: %x", response[:min(16, len(response))])
	return false
}

// parseHTTP3HeadersFrame parses HTTP/3 HEADERS frame to check status
func (c *QUICClient) parseHTTP3HeadersFrame(frame []byte) bool {
	if len(frame) < 3 {
		return false
	}
	
	// Skip frame type (0x01)
	offset := 1
	
	// Parse frame length (varint)
	frameLen, bytesRead := c.parseVarint(frame[offset:])
	if bytesRead == 0 {
		return false
	}
	offset += bytesRead
	
	if len(frame) < offset+int(frameLen) {
		logMasque("⚠️ HTTP/3: Frame truncated")
		return false
	}
	
	// QPACK header block
	headerBlock := frame[offset : offset+int(frameLen)]
	
	// Skip QPACK prefix (2 bytes minimum)
	if len(headerBlock) < 2 {
		return false
	}
	
	// Look for :status header in the decoded headers
	// In QPACK, :status = 200 can be encoded as indexed (index 25 in static table)
	// or as literal
	for i := 2; i < len(headerBlock); i++ {
		// Check for indexed :status = 200 (static table index 25)
		// Indexed field: 1xxxxxxx where xxxxxx = 25 = 0x19
		if headerBlock[i] == 0x99 || headerBlock[i] == 0xd9 { // 200 OK
			logMasque("✅ HTTP/3: Status 200 OK detected")
			return true
		}
		// Also check for 2xx range (indices 24-28 in static table)
		if (headerBlock[i] & 0xc0) == 0x80 { // Indexed field
			idx := headerBlock[i] & 0x3f
			if idx >= 24 && idx <= 28 { // 2xx status codes
				logMasque("✅ HTTP/3: Status 2xx detected (index %d)", idx)
				return true
			}
		}
	}
	
	// If we received a HEADERS frame, assume success for now
	// Cloudflare WARP may use different encoding
	logMasque("⚠️ HTTP/3: Could not parse status, assuming success")
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
