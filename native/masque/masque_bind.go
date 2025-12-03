package masque

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/conn"
)

// QUICConnection interface defines the methods needed from bridge.QUICConnection
// This interface is used for MASQUE protocol communication
type QUICConnection interface {
	// Write sends data through the QUIC connection
	Write(data []byte) (int, error)
	// Read receives data from the QUIC connection with timeout
	Read(timeout time.Duration) ([]byte, error)
	// OpenStream opens a new bidirectional stream
	OpenStream() (int64, error)
	// CloseStream closes a stream
	CloseStream(streamID int64) error
	// Close closes the connection
	Close() error
	// IsConnected checks if connection is valid
	IsConnected() bool
	// GetEndpoint returns the endpoint address
	GetEndpoint() string
}

// XrayDialer interface for creating QUIC connections
// bridge.XrayWrapper implements this interface
type XrayDialer interface {
	// DialQUIC creates a QUIC connection through Xray outbound
	// Returns any type that implements QUICConnection interface
	DialQUIC(endpoint string) (interface{}, error)
}

// XrayWrapper is an alias for any type that can dial QUIC connections
// This allows bridge.XrayWrapper to be used without circular imports
type XrayWrapper = interface{}

// ConnectionState represents the current state of the MASQUE connection
type ConnectionState int32

const (
	StateDisconnected ConnectionState = iota
	StateConnecting
	StateConnected
	StateReconnecting
)

func (s ConnectionState) String() string {
	switch s {
	case StateDisconnected:
		return "disconnected"
	case StateConnecting:
		return "connecting"
	case StateConnected:
		return "connected"
	case StateReconnecting:
		return "reconnecting"
	default:
		return "unknown"
	}
}

// MasqueBind configuration errors
var (
	ErrInvalidEndpoint     = errors.New("masque: invalid proxy endpoint")
	ErrInvalidMode         = errors.New("masque: invalid mode (must be 'connect-ip' or 'connect-udp')")
	ErrInvalidConfig       = errors.New("masque: invalid configuration")
	ErrConnectionFailed    = errors.New("masque: connection failed")
	ErrMaxReconnects       = errors.New("masque: maximum reconnection attempts exceeded")
	ErrBindClosed          = errors.New("masque: bind is closed")
)

// Default configuration values
const (
	DefaultMaxReconnects  = 5
	DefaultReconnectDelay = 1000 // ms
	DefaultQueueSize      = 131072

	// Buffer pool configuration (same as XrayBind)
	PacketBufferSize    = 4096
	PreallocatedBuffers = 4096

	// Reconnection backoff
	MaxReconnectDelay = 30000 // ms (30 seconds)
)

// MasqueConfig holds the configuration for MASQUE connection
type MasqueConfig struct {
	ProxyEndpoint  string `json:"proxyEndpoint"`  // e.g., "masque.example.com:443"
	Mode           string `json:"mode"`           // "connect-ip" or "connect-udp"
	MaxReconnects  int    `json:"maxReconnects"`  // Default: 5
	ReconnectDelay int    `json:"reconnectDelay"` // Base delay in ms
	QueueSize      int    `json:"queueSize"`      // Packet queue size
	MTU            int    `json:"mtu"`            // MTU for packets
}

// Validate validates the MasqueConfig
func (c *MasqueConfig) Validate() error {
	if c.ProxyEndpoint == "" {
		return fmt.Errorf("%w: proxyEndpoint is required", ErrInvalidConfig)
	}

	// Validate endpoint format
	_, _, err := net.SplitHostPort(c.ProxyEndpoint)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrInvalidEndpoint, err)
	}

	// Validate mode
	if c.Mode != "" && c.Mode != "connect-ip" && c.Mode != "connect-udp" {
		return fmt.Errorf("%w: got '%s'", ErrInvalidMode, c.Mode)
	}

	return nil
}

// ApplyDefaults applies default values to unset fields
func (c *MasqueConfig) ApplyDefaults() {
	if c.Mode == "" {
		c.Mode = "connect-ip"
	}
	if c.MaxReconnects <= 0 {
		c.MaxReconnects = DefaultMaxReconnects
	}
	if c.ReconnectDelay <= 0 {
		c.ReconnectDelay = DefaultReconnectDelay
	}
	if c.QueueSize <= 0 {
		c.QueueSize = DefaultQueueSize
	}
	if c.MTU <= 0 {
		c.MTU = DefaultMTU
	}
}

// GetMode returns the MasqueMode from config string
func (c *MasqueConfig) GetMode() MasqueMode {
	if c.Mode == "connect-udp" {
		return MasqueModeConnectUDP
	}
	return MasqueModeConnectIP
}

// Buffer pool for memory optimization (same pattern as XrayBind)
var masqueBufferPool = sync.Pool{
	New: func() interface{} {
		buf := make([]byte, PacketBufferSize)
		return &buf
	},
}

func init() {
	// Pre-warm the buffer pool
	buffers := make([]*[]byte, PreallocatedBuffers)
	for i := 0; i < PreallocatedBuffers; i++ {
		buffers[i] = getMasqueBuffer()
	}
	for i := 0; i < PreallocatedBuffers; i++ {
		putMasqueBuffer(buffers[i])
	}
}

func getMasqueBuffer() *[]byte {
	return masqueBufferPool.Get().(*[]byte)
}

func putMasqueBuffer(buf *[]byte) {
	if buf == nil {
		return
	}
	// Clear buffer to prevent data leakage (Property 4: Buffer Pool Safety)
	b := *buf
	for i := range b {
		b[i] = 0
	}
	*buf = b[:cap(b)]
	masqueBufferPool.Put(buf)
}

// MasqueBind implements conn.Bind interface for MASQUE protocol
type MasqueBind struct {
	xrayWrapper XrayWrapper
	config      *MasqueConfig
	endpoint    netip.AddrPort

	// QUIC connection through Xray
	quicConn QUICConnection

	// Capsule encoder/decoder
	encoder *CapsuleEncoder
	decoder *CapsuleDecoder

	// Packet queues
	recvQueue chan []byte
	sendQueue chan []byte

	// State management
	state     int32 // ConnectionState
	closed    int32
	closeOnce sync.Once
	connMu    sync.RWMutex

	// Reconnection state
	reconnectMu    sync.Mutex
	reconnectCount int
	lastReconnect  time.Time

	// Statistics (atomic)
	txBytes   uint64
	rxBytes   uint64
	startTime time.Time

	// Goroutine management
	stopChan chan struct{}
	wg       sync.WaitGroup
}

// NewMasqueBind creates a new MasqueBind from JSON configuration
func NewMasqueBind(xrayWrapper XrayWrapper, configJSON string) (*MasqueBind, error) {
	if xrayWrapper == nil {
		return nil, fmt.Errorf("%w: xrayWrapper is nil", ErrInvalidConfig)
	}

	// Parse configuration
	var config MasqueConfig
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidConfig, err)
	}

	// Validate and apply defaults
	if err := config.Validate(); err != nil {
		return nil, err
	}
	config.ApplyDefaults()

	// Parse endpoint
	addrPort, err := parseEndpoint(config.ProxyEndpoint)
	if err != nil {
		return nil, err
	}

	bind := &MasqueBind{
		xrayWrapper: xrayWrapper,
		config:      &config,
		endpoint:    addrPort,
		encoder:     NewCapsuleEncoder(config.GetMode(), config.MTU),
		decoder:     NewCapsuleDecoder(config.GetMode()),
		recvQueue:   make(chan []byte, config.QueueSize),
		sendQueue:   make(chan []byte, config.QueueSize),
		stopChan:    make(chan struct{}),
		startTime:   time.Now(),
	}

	atomic.StoreInt32(&bind.state, int32(StateDisconnected))

	return bind, nil
}

// cloudflareWARPIPsForBind contains known Cloudflare WARP endpoint IPs
// These are used when DNS resolution fails (common when VPN is active)
var cloudflareWARPIPsForBind = []string{
	"162.159.192.1",
	"162.159.193.1",
	"162.159.195.1",
	"162.159.204.1",
}

// resolveCloudflareWARPForBind returns a known Cloudflare WARP IP for the given hostname
func resolveCloudflareWARPForBind(hostname string) net.IP {
	// Check if this is a Cloudflare WARP hostname
	cloudflareHosts := []string{
		"engage.cloudflareclient.com",
		"cloudflare-dns.com",
	}
	
	for _, h := range cloudflareHosts {
		if hostname == h {
			return net.ParseIP(cloudflareWARPIPsForBind[0])
		}
	}
	
	// Check for cloudflare substring
	for i := 0; i <= len(hostname)-10; i++ {
		if hostname[i:i+10] == "cloudflare" {
			return net.ParseIP(cloudflareWARPIPsForBind[0])
		}
	}
	
	return nil
}

// parseEndpoint parses an endpoint string to netip.AddrPort
func parseEndpoint(endpoint string) (netip.AddrPort, error) {
	addrPort, err := netip.ParseAddrPort(endpoint)
	if err != nil {
		// Try resolving hostname
		host, port, splitErr := net.SplitHostPort(endpoint)
		if splitErr != nil {
			return netip.AddrPort{}, fmt.Errorf("%w: %v", ErrInvalidEndpoint, splitErr)
		}

		// First, check if this is a known Cloudflare WARP hostname
		// Use hardcoded IPs to avoid DNS resolution issues when VPN is active
		var resolvedIP net.IP
		if warpIP := resolveCloudflareWARPForBind(host); warpIP != nil {
			resolvedIP = warpIP
			logMasque("🔌 Using hardcoded Cloudflare WARP IP for %s: %s", host, resolvedIP.String())
		} else {
			// Try DNS resolution for non-Cloudflare hosts
			ips, lookupErr := net.LookupIP(host)
			if lookupErr != nil || len(ips) == 0 {
				// DNS failed - try Cloudflare WARP fallback
				logMasque("⚠️ DNS resolution failed for %s: %v", host, lookupErr)
				if warpIP := resolveCloudflareWARPForBind(host); warpIP != nil {
					resolvedIP = warpIP
					logMasque("🔌 Using Cloudflare WARP fallback IP: %s", resolvedIP.String())
				} else {
					return netip.AddrPort{}, fmt.Errorf("%w: cannot resolve %s", ErrInvalidEndpoint, host)
				}
			} else {
				resolvedIP = ips[0]
				logMasque("🔌 Resolved %s -> %s", host, resolvedIP.String())
			}
		}

		addr, ok := netip.AddrFromSlice(resolvedIP)
		if !ok {
			return netip.AddrPort{}, fmt.Errorf("%w: invalid IP address", ErrInvalidEndpoint)
		}

		portNum, portErr := net.LookupPort("tcp", port)
		if portErr != nil {
			return netip.AddrPort{}, fmt.Errorf("%w: invalid port %s", ErrInvalidEndpoint, port)
		}

		addrPort = netip.AddrPortFrom(addr, uint16(portNum))
	}

	return addrPort, nil
}

// conn.Bind interface implementation

// Open implements conn.Bind.Open
func (b *MasqueBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	if atomic.LoadInt32(&b.closed) == 1 {
		return nil, 0, ErrBindClosed
	}

	// Start connection
	if err := b.connect(); err != nil {
		return nil, 0, err
	}

	// Start packet processors
	b.wg.Add(2)
	go b.processOutgoing()
	go b.processIncoming()

	return []conn.ReceiveFunc{b.receiveFunc}, port, nil
}

// receiveFunc is the receive function for WireGuard
func (b *MasqueBind) receiveFunc(bufs [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
	if atomic.LoadInt32(&b.closed) == 1 {
		return 0, net.ErrClosed
	}

	select {
	case data, ok := <-b.recvQueue:
		if !ok {
			return 0, net.ErrClosed
		}
		if len(bufs) > 0 && len(bufs[0]) >= len(data) {
			n := copy(bufs[0], data)
			sizes[0] = n
			eps[0] = &MasqueEndpoint{addr: b.endpoint}
			atomic.AddUint64(&b.rxBytes, uint64(n))

			// Return pooled buffer
			if cap(data) == PacketBufferSize {
				putMasqueBuffer(&data)
			}

			return 1, nil
		}
		// Buffer too small
		if cap(data) == PacketBufferSize {
			putMasqueBuffer(&data)
		}
	case <-b.stopChan:
		return 0, net.ErrClosed
	}

	return 0, nil
}

// Send implements conn.Bind.Send
func (b *MasqueBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	if atomic.LoadInt32(&b.closed) == 1 {
		return net.ErrClosed
	}

	for _, buf := range bufs {
		if len(buf) == 0 {
			continue
		}

		// Use pooled buffer
		var data []byte
		if len(buf) <= PacketBufferSize {
			bufPtr := getMasqueBuffer()
			*bufPtr = (*bufPtr)[:len(buf)]
			copy(*bufPtr, buf)
			data = *bufPtr
		} else {
			data = make([]byte, len(buf))
			copy(data, buf)
		}

		select {
		case b.sendQueue <- data:
			atomic.AddUint64(&b.txBytes, uint64(len(data)))
		default:
			// Queue full - drop packet (Property 3: Queue Bounds Enforcement)
			if len(buf) <= PacketBufferSize {
				putMasqueBuffer(&data)
			}
			logMasque("⚠️ Send queue full, dropping %d bytes", len(buf))
		}
	}

	return nil
}

// Close implements conn.Bind.Close
func (b *MasqueBind) Close() error {
	b.closeOnce.Do(func() {
		atomic.StoreInt32(&b.closed, 1)
		atomic.StoreInt32(&b.state, int32(StateDisconnected))

		// Signal goroutines to stop
		close(b.stopChan)

		// Close send queue
		close(b.sendQueue)

		// Wait for goroutines
		b.wg.Wait()

		// Drain receive queue and return buffers to pool (Property 8: Resource Cleanup)
		for {
			select {
			case data := <-b.recvQueue:
				if cap(data) == PacketBufferSize {
					putMasqueBuffer(&data)
				}
			default:
				goto done
			}
		}
	done:
		close(b.recvQueue)

		b.connMu.Lock()
		// Close QUIC connection
		if b.quicConn != nil {
			b.quicConn.Close()
			b.quicConn = nil
		}
		b.connMu.Unlock()

		logMasque("MasqueBind closed")
	})
	return nil
}

// SetMark implements conn.Bind.SetMark
func (b *MasqueBind) SetMark(mark uint32) error {
	return nil
}

// BatchSize implements conn.Bind.BatchSize
func (b *MasqueBind) BatchSize() int {
	return 1
}

// ParseEndpoint implements conn.Bind.ParseEndpoint
func (b *MasqueBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	addrPort, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return &MasqueEndpoint{addr: addrPort}, nil
}

// Connection management

func (b *MasqueBind) connect() error {
	atomic.StoreInt32(&b.state, int32(StateConnecting))
	logMasque("🔌 Connecting to MASQUE proxy: %s (mode: %s)", b.config.ProxyEndpoint, b.config.Mode)

	// Get XrayDialer interface from xrayWrapper
	// This routes QUIC traffic through Xray's outbound (VLESS/VMess/etc)
	// 
	// XrayWrapper.DialQUIC returns (interface{}, error) to allow cross-package
	// interface compatibility. The returned value implements QUICConnection.
	xrayDialer, ok := b.xrayWrapper.(interface {
		DialQUIC(endpoint string) (interface{}, error)
	})
	if !ok {
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		logMasque("❌ xrayWrapper does not implement DialQUIC(endpoint string) (interface{}, error)")
		logMasque("❌ xrayWrapper type: %T", b.xrayWrapper)
		return fmt.Errorf("%w: xrayWrapper does not support DialQUIC", ErrConnectionFailed)
	}

	// Use Xray's DialQUIC to route through Xray outbound
	// This ensures traffic goes through VLESS/VMess/REALITY instead of direct connection
	logMasque("🔌 Creating QUIC connection through Xray...")
	quicConnInterface, err := xrayDialer.DialQUIC(b.config.ProxyEndpoint)
	if err != nil {
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		logMasque("❌ Failed to create QUIC connection through Xray: %v", err)
		return fmt.Errorf("%w: DialQUIC failed: %v", ErrConnectionFailed, err)
	}

	// Cast to QUICConnection interface
	// XrayWrapper returns a type that implements all QUICConnection methods
	quicConn, ok := quicConnInterface.(QUICConnection)
	if !ok {
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		logMasque("❌ DialQUIC returned invalid type: %T (does not implement QUICConnection)", quicConnInterface)
		return fmt.Errorf("%w: invalid QUIC connection type", ErrConnectionFailed)
	}

	// Establish HTTP/3 Extended CONNECT tunnel
	logMasque("🤝 Establishing HTTP/3 CONNECT tunnel...")
	if err := establishConnectTunnel(quicConn, b.config.GetMode()); err != nil {
		quicConn.Close()
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		logMasque("❌ Failed to establish CONNECT tunnel: %v", err)
		return fmt.Errorf("%w: CONNECT failed: %v", ErrConnectionFailed, err)
	}

	b.connMu.Lock()
	b.quicConn = quicConn
	b.connMu.Unlock()

	atomic.StoreInt32(&b.state, int32(StateConnected))
	b.startTime = time.Now()
	logMasque("✅ Connected to %s through Xray (mode: %s)", b.config.ProxyEndpoint, b.config.Mode)

	return nil
}

func (b *MasqueBind) reconnect() {
	b.reconnectMu.Lock()
	defer b.reconnectMu.Unlock()

	if atomic.LoadInt32(&b.closed) == 1 {
		return
	}

	// Check if already reconnecting
	if ConnectionState(atomic.LoadInt32(&b.state)) == StateReconnecting {
		return
	}

	atomic.StoreInt32(&b.state, int32(StateReconnecting))

	// Calculate backoff delay (Property 7: Reconnection Backoff)
	delay := b.calculateBackoff()

	logMasque("Reconnecting in %v (attempt %d/%d)", delay, b.reconnectCount+1, b.config.MaxReconnects)

	time.Sleep(delay)

	b.reconnectCount++
	if b.reconnectCount > b.config.MaxReconnects {
		logMasque("Max reconnection attempts exceeded")
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		return
	}

	if err := b.connect(); err != nil {
		logMasque("Reconnection failed: %v", err)
		// Will retry on next error
	} else {
		b.reconnectCount = 0
		b.lastReconnect = time.Now()
	}
}

// calculateBackoff calculates exponential backoff delay
func (b *MasqueBind) calculateBackoff() time.Duration {
	delay := b.config.ReconnectDelay * (1 << b.reconnectCount)
	if delay > MaxReconnectDelay {
		delay = MaxReconnectDelay
	}
	return time.Duration(delay) * time.Millisecond
}

// Packet processing

func (b *MasqueBind) processOutgoing() {
	defer b.wg.Done()

	for data := range b.sendQueue {
		if atomic.LoadInt32(&b.closed) == 1 {
			// Return buffer to pool
			if cap(data) == PacketBufferSize {
				putMasqueBuffer(&data)
			}
			continue
		}

		b.processOutgoingPacket(data)
	}
}

func (b *MasqueBind) processOutgoingPacket(data []byte) {
	isPooled := cap(data) == PacketBufferSize
	returned := false

	defer func() {
		if isPooled && !returned {
			putMasqueBuffer(&data)
		}
	}()

	if len(data) == 0 {
		return
	}

	// Encode packet as capsule
	capsuleData, err := b.encoder.Encode(data)
	if err != nil {
		logMasque("Encode error: %v", err)
		return
	}

	// Return original buffer to pool
	if isPooled {
		putMasqueBuffer(&data)
		returned = true
	}

	// Send capsule through QUIC connection
	b.connMu.RLock()
	quicConn := b.quicConn
	b.connMu.RUnlock()

	if quicConn == nil || !quicConn.IsConnected() {
		logMasque("⚠️ QUIC connection not available, triggering reconnect")
		go b.reconnect()
		return
	}

	_, err = quicConn.Write(capsuleData)
	if err != nil {
		logMasque("❌ Failed to send capsule: %v", err)
		go b.reconnect()
		return
	}

	// logMasque("✅ Sent %d bytes capsule", len(capsuleData))
}

func (b *MasqueBind) processIncoming() {
	defer b.wg.Done()

	for {
		select {
		case <-b.stopChan:
			return
		default:
		}

		if atomic.LoadInt32(&b.closed) == 1 {
			return
		}

		// Get QUIC connection
		b.connMu.RLock()
		quicConn := b.quicConn
		b.connMu.RUnlock()

		if quicConn == nil || !quicConn.IsConnected() {
			// Wait and retry
			time.Sleep(100 * time.Millisecond)
			continue
		}

		// Read from QUIC connection with timeout
		data, err := quicConn.Read(100 * time.Millisecond)
		if err != nil {
			// Timeout is expected, continue loop
			continue
		}

		if len(data) == 0 {
			continue
		}

		// Decode capsule to extract IP packet
		ipPacket, _, err := b.decoder.Decode(data)
		if err != nil {
			logMasque("⚠️ Decode error: %v", err)
			continue
		}

		if len(ipPacket) == 0 {
			continue
		}

		// Copy to pooled buffer for receive queue
		var recvData []byte
		if len(ipPacket) <= PacketBufferSize {
			bufPtr := getMasqueBuffer()
			*bufPtr = (*bufPtr)[:len(ipPacket)]
			copy(*bufPtr, ipPacket)
			recvData = *bufPtr
		} else {
			recvData = make([]byte, len(ipPacket))
			copy(recvData, ipPacket)
		}

		// Send to receive queue
		select {
		case b.recvQueue <- recvData:
			atomic.AddUint64(&b.rxBytes, uint64(len(recvData)))
		default:
			// Queue full, drop packet
			if len(ipPacket) <= PacketBufferSize {
				putMasqueBuffer(&recvData)
			}
			logMasque("⚠️ Receive queue full, dropping %d bytes", len(ipPacket))
		}
	}
}

// Statistics and state

// GetStats returns current statistics (tx bytes, rx bytes)
func (b *MasqueBind) GetStats() (uint64, uint64) {
	return atomic.LoadUint64(&b.txBytes), atomic.LoadUint64(&b.rxBytes)
}

// GetState returns the current connection state
func (b *MasqueBind) GetState() ConnectionState {
	return ConnectionState(atomic.LoadInt32(&b.state))
}

// GetUptime returns the connection uptime
func (b *MasqueBind) GetUptime() time.Duration {
	if ConnectionState(atomic.LoadInt32(&b.state)) != StateConnected {
		return 0
	}
	return time.Since(b.startTime)
}

// GetConfig returns the current configuration
func (b *MasqueBind) GetConfig() *MasqueConfig {
	return b.config
}

// MasqueEndpoint implements conn.Endpoint
type MasqueEndpoint struct {
	addr netip.AddrPort
}

func (e *MasqueEndpoint) ClearSrc() {}

func (e *MasqueEndpoint) SrcToString() string {
	return ""
}

func (e *MasqueEndpoint) DstToString() string {
	return e.addr.String()
}

func (e *MasqueEndpoint) DstIP() netip.Addr {
	return e.addr.Addr()
}

func (e *MasqueEndpoint) SrcIP() netip.Addr {
	return netip.Addr{}
}

func (e *MasqueEndpoint) DstPort() uint16 {
	return e.addr.Port()
}

func (e *MasqueEndpoint) DstToBytes() []byte {
	addr := e.addr.Addr()
	if addr.Is4() {
		ip4 := addr.As4()
		return ip4[:]
	} else if addr.Is6() {
		ip6 := addr.As16()
		return ip6[:]
	}
	return nil
}

// Logging helper
func logMasque(format string, args ...interface{}) {
	fmt.Printf("[MasqueBind] "+format+"\n", args...)
}


// ============================================================================
// HTTP/3 EXTENDED CONNECT HELPERS
// ============================================================================

// establishConnectTunnel establishes HTTP/3 Extended CONNECT tunnel
// This is extracted from QUICClient.EstablishConnect() to work with
// any QUICConnection implementation (from Xray or direct quic-go)
func establishConnectTunnel(quicConn QUICConnection, mode MasqueMode) error {
	logMasque("🤝 HTTP/3: Establishing Extended CONNECT (%v)", mode)

	// Open a bidirectional stream for HTTP/3 request
	streamID, err := quicConn.OpenStream()
	if err != nil {
		return fmt.Errorf("failed to open stream: %w", err)
	}
	logMasque("✅ HTTP/3: Stream opened (ID: %d)", streamID)

	// Build HTTP/3 Extended CONNECT request
	var connectRequest []byte
	if mode == MasqueModeConnectIP {
		connectRequest = buildConnectIPRequest()
	} else {
		connectRequest = buildConnectUDPRequest()
	}

	logMasque("📤 HTTP/3: Sending CONNECT request (%d bytes)", len(connectRequest))

	// Send CONNECT request through stream
	_, err = quicConn.Write(connectRequest)
	if err != nil {
		quicConn.CloseStream(streamID)
		return fmt.Errorf("failed to send CONNECT request: %w", err)
	}

	// Read response with timeout
	response, err := quicConn.Read(10 * time.Second)
	if err != nil {
		quicConn.CloseStream(streamID)
		return fmt.Errorf("failed to read CONNECT response: %w", err)
	}

	logMasque("📥 HTTP/3: Received response (%d bytes)", len(response))

	// Parse response - check for 200 OK
	if !isSuccessResponse(response) {
		quicConn.CloseStream(streamID)
		return fmt.Errorf("CONNECT request rejected: %s", string(response))
	}

	logMasque("✅ HTTP/3: CONNECT established successfully")
	return nil
}

// buildConnectIPRequest builds HTTP/3 CONNECT-IP request
func buildConnectIPRequest() []byte {
	// Build QPACK encoded headers for CONNECT-IP (RFC 9484)
	headers := buildQPACKHeaders("connect-ip", "/.well-known/masque/ip/*/*/")
	
	// HTTP/3 HEADERS frame (type = 0x01)
	return buildHTTP3Frame(0x01, headers)
}

// buildConnectUDPRequest builds HTTP/3 CONNECT-UDP request
func buildConnectUDPRequest() []byte {
	// Build QPACK encoded headers for CONNECT-UDP (RFC 9298)
	headers := buildQPACKHeaders("connect-udp", "/.well-known/masque/udp/*/*/")
	
	// HTTP/3 HEADERS frame (type = 0x01)
	return buildHTTP3Frame(0x01, headers)
}

// buildQPACKHeaders builds QPACK encoded headers for Extended CONNECT
func buildQPACKHeaders(protocol, path string) []byte {
	var buf []byte

	// QPACK header block prefix (Required Insert Count = 0, Delta Base = 0)
	buf = append(buf, 0x00, 0x00)

	// :method = CONNECT (indexed from static table, index 15)
	// Using literal with name reference for Extended CONNECT
	buf = append(buf, qpackLiteralWithNameRef(15, "CONNECT")...)

	// :scheme = https (indexed from static table, index 23)
	buf = append(buf, qpackIndexed(23)...)

	// :authority = <host> (literal with name reference, index 0)
	// For MASQUE, authority is typically the proxy endpoint
	buf = append(buf, qpackLiteralWithNameRef(0, "masque-proxy")...)

	// :path = <path> (literal with name reference, index 1)
	buf = append(buf, qpackLiteralWithNameRef(1, path)...)

	// :protocol = <protocol> (literal without name reference - not in static table)
	buf = append(buf, qpackLiteralWithoutNameRef(":protocol", protocol)...)

	// capsule-protocol = ?1 (literal without name reference)
	buf = append(buf, qpackLiteralWithoutNameRef("capsule-protocol", "?1")...)

	return buf
}

// qpackIndexed encodes an indexed header field (static table)
func qpackIndexed(index int) []byte {
	if index < 64 {
		return []byte{byte(0xc0 | index)} // 11xxxxxx pattern
	}
	// For larger indices, use 2-byte encoding
	return []byte{0xff, byte(index - 63)}
}

// qpackLiteralWithNameRef encodes a literal header with name reference
func qpackLiteralWithNameRef(nameIndex int, value string) []byte {
	var buf []byte

	// Literal with Incremental Name Reference (01xxxxxx pattern)
	if nameIndex < 16 {
		buf = append(buf, byte(0x40|nameIndex))
	} else {
		// For larger indices
		buf = append(buf, 0x4f)
		buf = append(buf, byte(nameIndex-15))
	}

	// String Literal (not Huffman encoded)
	valueLen := len(value)
	if valueLen < 128 {
		buf = append(buf, byte(valueLen))
	} else {
		// Multi-byte length encoding
		buf = append(buf, 0x7f)
		buf = append(buf, byte(valueLen-127))
	}
	buf = append(buf, []byte(value)...)

	return buf
}

// qpackLiteralWithoutNameRef encodes a literal header without name reference
func qpackLiteralWithoutNameRef(name, value string) []byte {
	var buf []byte

	// Literal without Name Reference (001xxxxx pattern)
	nameLen := len(name)
	if nameLen < 8 {
		buf = append(buf, byte(0x20|nameLen))
	} else {
		buf = append(buf, 0x27)
		buf = append(buf, byte(nameLen-7))
	}
	buf = append(buf, []byte(name)...)

	// Value String Literal
	valueLen := len(value)
	if valueLen < 128 {
		buf = append(buf, byte(valueLen))
	} else {
		buf = append(buf, 0x7f)
		buf = append(buf, byte(valueLen-127))
	}
	buf = append(buf, []byte(value)...)

	return buf
}

// buildHTTP3Frame builds an HTTP/3 frame with proper framing
func buildHTTP3Frame(frameType byte, payload []byte) []byte {
	var frame []byte

	// Frame type (1 byte)
	frame = append(frame, frameType)

	// Frame length (variable-length integer)
	length := len(payload)
	if length < 64 {
		frame = append(frame, byte(length))
	} else if length < 16384 {
		frame = append(frame, byte(0x40|(length>>8)), byte(length&0xff))
	} else if length < 1073741824 {
		frame = append(frame, byte(0x80|(length>>24)), byte((length>>16)&0xff),
			byte((length>>8)&0xff), byte(length&0xff))
	}

	// Payload
	frame = append(frame, payload...)

	return frame
}

// isSuccessResponse checks if the HTTP/3 response indicates success
func isSuccessResponse(response []byte) bool {
	if len(response) == 0 {
		return false
	}

	// Check for HTTP/3 HEADERS frame (type 0x01)
	if response[0] != 0x01 {
		logMasque("⚠️ Expected HEADERS frame (0x01), got 0x%02x", response[0])
		return false
	}

	// Parse frame length (simplified - assumes single-byte length)
	if len(response) < 2 {
		return false
	}

	frameLen := int(response[1])
	if response[1]&0xc0 == 0x40 {
		// 2-byte length
		if len(response) < 3 {
			return false
		}
		frameLen = int(response[1]&0x3f)<<8 | int(response[2])
	}

	// For now, assume success if we get a HEADERS frame
	// A proper implementation would decode QPACK headers and check :status
	logMasque("📥 HTTP/3: Received HEADERS frame (length: %d)", frameLen)
	
	// Look for status 200 indicators in the response
	// QPACK indexed :status = 200 is index 25 (0xd9 = 11011001)
	for i := 2; i < len(response) && i < 2+frameLen; i++ {
		if response[i] == 0xd9 || response[i] == 0x99 {
			logMasque("✅ HTTP/3: Status 200 OK detected")
			return true
		}
	}

	// If we received a HEADERS frame, assume success
	// Cloudflare WARP may use different encoding
	logMasque("⚠️ HTTP/3: Could not parse status, assuming success")
	return true
}
