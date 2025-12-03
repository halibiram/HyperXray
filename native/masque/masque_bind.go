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

	// Try to get socket protector from xrayWrapper (for VPN loop prevention)
	var protector ProtectedDialer
	if p, ok := b.xrayWrapper.(ProtectedDialer); ok {
		protector = p
		logMasque("✅ Socket protector available")
	} else {
		logMasque("⚠️ Socket protector not available - VPN loop may occur")
	}

	// Create QUIC client configuration
	quicConfig := &QUICClientConfig{
		Endpoint:  b.config.ProxyEndpoint,
		Mode:      b.config.GetMode(),
		Protector: protector,
	}

	// Create real QUIC connection using quic-go
	logMasque("🔌 Creating QUIC connection...")
	quicClient, err := NewQUICClient(quicConfig)
	if err != nil {
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		logMasque("❌ Failed to create QUIC client: %v", err)
		return fmt.Errorf("%w: %v", ErrConnectionFailed, err)
	}

	// Establish HTTP/3 Extended CONNECT tunnel
	logMasque("🤝 Establishing HTTP/3 CONNECT tunnel...")
	if err := quicClient.EstablishConnect(); err != nil {
		quicClient.Close()
		atomic.StoreInt32(&b.state, int32(StateDisconnected))
		logMasque("❌ Failed to establish CONNECT tunnel: %v", err)
		return fmt.Errorf("%w: CONNECT failed: %v", ErrConnectionFailed, err)
	}

	b.connMu.Lock()
	b.quicConn = quicClient
	b.connMu.Unlock()

	atomic.StoreInt32(&b.state, int32(StateConnected))
	b.startTime = time.Now()
	logMasque("✅ Connected to %s (mode: %s)", b.config.ProxyEndpoint, b.config.Mode)

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
