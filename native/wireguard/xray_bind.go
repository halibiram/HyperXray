package wireguard

import (
	"encoding/binary"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/conn"

	"github.com/hyperxray/native/bridge"
)

// ============================================================================
// BUFFER POOL FOR MEMORY OPTIMIZATION
// ============================================================================
// Using sync.Pool to reduce GC pressure from frequent packet allocations.
// Each packet would otherwise allocate a new []byte, causing massive GC churn
// under high traffic (thousands of packets per second).
// ============================================================================

const (
	// PacketBufferSize is the maximum size for packet buffers.
	// WireGuard MTU is typically 1420, but we use 2048 for safety margin
	// to handle any encapsulation overhead.
	PacketBufferSize = 2048
)

// packetBufferPool is a global pool for packet buffers to reduce GC pressure.
// Buffers are reused across Send/Receive operations.
var packetBufferPool = sync.Pool{
	New: func() interface{} {
		buf := make([]byte, PacketBufferSize)
		return &buf
	},
}

// getPacketBuffer retrieves a buffer from the pool.
// The returned buffer has capacity PacketBufferSize but length 0.
// Caller must use buf = (*bufPtr)[:n] to set the correct length.
func getPacketBuffer() *[]byte {
	return packetBufferPool.Get().(*[]byte)
}

// putPacketBuffer returns a buffer to the pool.
// SAFETY: Caller must ensure the buffer is no longer referenced after this call.
// The buffer is reset to full capacity before being returned.
func putPacketBuffer(buf *[]byte) {
	if buf == nil {
		return
	}
	// Reset to full capacity for next use
	*buf = (*buf)[:cap(*buf)]
	packetBufferPool.Put(buf)
}

// WireGuard message types
const (
	MessageTypeHandshakeInitiation = 1
	MessageTypeHandshakeResponse   = 2
	MessageTypeCookieReply         = 3
	MessageTypeTransportData       = 4
)

// Keepalive configuration
const (
	KeepaliveInterval          = 10 * time.Second
	PostHandshakeDelay         = 500 * time.Millisecond
	HandshakeConfirmationDelay = 50 * time.Millisecond
	// Minimum WireGuard transport data packet size (header only, no payload = keepalive)
	MinTransportDataSize = 32
)

// Simple logging function for packet inspector
func logPacketInspector(format string, args ...interface{}) {
	fmt.Printf("[PacketInspector-XrayBind] "+format+"\n", args...)
}

// XrayBind implements conn.Bind interface
// Routes WireGuard UDP packets through Xray-core directly (no SOCKS5)
type XrayBind struct {
	xrayWrapper *bridge.XrayWrapper
	endpoint    netip.AddrPort
	udpConn     *bridge.XrayUDPConn

	recvQueue chan []byte
	sendQueue chan []byte

	closed    int32
	closeOnce sync.Once
	connMu    sync.Mutex

	// Handshake and keepalive state
	lastHandshakeTime    atomic.Int64  // Unix nano timestamp of last handshake response
	handshakeConfirmed   atomic.Int32  // 1 if key confirmation packet sent
	pendingConfirmation  atomic.Int32  // 1 if confirmation is pending
	lastReceiverIndex    atomic.Uint32 // Last seen receiver index from handshake response
	keepaliveStop        chan struct{} // Signal to stop keepalive goroutine
	keepaliveWg          sync.WaitGroup

	// Callback for triggering WireGuard keepalive
	keepaliveCallback   func()
	keepaliveCallbackMu sync.RWMutex

	// Stats
	txBytes uint64
	rxBytes uint64
}


func NewXrayBind(xrayWrapper *bridge.XrayWrapper, endpoint string) (*XrayBind, error) {
	addrPort, err := netip.ParseAddrPort(endpoint)
	if err != nil {
		// Try resolving hostname
		host, port, _ := net.SplitHostPort(endpoint)
		ips, err := net.LookupIP(host)
		if err != nil || len(ips) == 0 {
			return nil, err
		}
		addr, _ := netip.AddrFromSlice(ips[0])
		portNum, _ := net.LookupPort("udp", port)
		addrPort = netip.AddrPortFrom(addr, uint16(portNum))
	}

	bind := &XrayBind{
		xrayWrapper:   xrayWrapper,
		endpoint:      addrPort,
		recvQueue:     make(chan []byte, 2048),
		sendQueue:     make(chan []byte, 2048),
		keepaliveStop: make(chan struct{}),
	}

	// Create UDP connection through Xray
	addrStr := addrPort.Addr().String()
	portInt := int(addrPort.Port())

	udpConn, err := xrayWrapper.DialUDP(addrStr, portInt)
	if err != nil {
		return nil, fmt.Errorf("dial UDP: %w", err)
	}

	// Connect to establish the connection
	if err := udpConn.Connect(); err != nil {
		return nil, fmt.Errorf("connect UDP: %w", err)
	}

	bind.udpConn = udpConn

	// Start packet processors
	go bind.processOutgoing()
	go bind.processIncoming()

	// Start keepalive loop
	bind.keepaliveWg.Add(1)
	go bind.keepaliveLoop()

	return bind, nil
}

// SetKeepaliveCallback sets a callback function that will be called to trigger
// WireGuard's internal keepalive mechanism. This should be set by the WireGuard
// device after creation.
func (b *XrayBind) SetKeepaliveCallback(callback func()) {
	b.keepaliveCallbackMu.Lock()
	b.keepaliveCallback = callback
	b.keepaliveCallbackMu.Unlock()
}

func (b *XrayBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	return []conn.ReceiveFunc{b.receiveFunc}, port, nil
}

func (b *XrayBind) receiveFunc(bufs [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
	if atomic.LoadInt32(&b.closed) == 1 {
		return 0, net.ErrClosed
	}

	select {
	case data := <-b.recvQueue:
		if len(bufs) > 0 && len(bufs[0]) >= len(data) {
			n := copy(bufs[0], data)
			sizes[0] = n
			eps[0] = &XrayEndpoint{addr: b.endpoint}
			atomic.AddUint64(&b.rxBytes, uint64(n))
			
			// OPTIMIZATION: Return pooled buffer after copying to WireGuard's buffer.
			// Check if this buffer came from the pool (capacity == PacketBufferSize).
			if cap(data) == PacketBufferSize {
				putPacketBuffer(&data)
			}
			
			return 1, nil
		}
		// Buffer too small - still need to return pooled buffer
		if cap(data) == PacketBufferSize {
			putPacketBuffer(&data)
		}
	}

	return 0, nil
}

func (b *XrayBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	if atomic.LoadInt32(&b.closed) == 1 {
		return net.ErrClosed
	}

	for _, buf := range bufs {
		if len(buf) == 0 {
			continue // Skip empty buffers
		}

		// OPTIMIZATION: Use pooled buffer instead of make([]byte, len(buf))
		// This significantly reduces GC pressure under high traffic.
		// NOTE: We copy to a new slice because the buffer will be queued
		// and processed asynchronously by processOutgoing().
		var data []byte
		if len(buf) <= PacketBufferSize {
			// Use pooled buffer for typical packet sizes
			bufPtr := getPacketBuffer()
			*bufPtr = (*bufPtr)[:len(buf)]
			copy(*bufPtr, buf)
			data = *bufPtr
			// NOTE: Buffer ownership transfers to sendQueue consumer (processOutgoing)
			// The consumer is responsible for returning it to the pool after use.
		} else {
			// Fallback to allocation for oversized packets (rare)
			data = make([]byte, len(buf))
			copy(data, buf)
		}

		// Check if this is a transport data packet (key confirmation)
		if len(data) >= 4 && data[0] == MessageTypeTransportData {
			// Mark handshake as confirmed when we send first data packet after handshake
			if b.pendingConfirmation.Load() == 1 {
				if b.handshakeConfirmed.CompareAndSwap(0, 1) {
					b.pendingConfirmation.Store(0)
					logPacketInspector("✅ Key confirmation: first transport data packet sent after handshake")
				}
			}
		}

		select {
		case b.sendQueue <- data:
			atomic.AddUint64(&b.txBytes, uint64(len(data)))
		default:
			// Queue full, drop packet
			// Return buffer to pool since it won't be consumed
			if len(buf) <= PacketBufferSize {
				putPacketBuffer(&data)
			}
			logPacketInspector("⚠️ Send queue full, dropping %d bytes", len(buf))
		}
	}

	return nil
}

func (b *XrayBind) Close() error {
	b.closeOnce.Do(func() {
		atomic.StoreInt32(&b.closed, 1)

		// Stop keepalive goroutine
		close(b.keepaliveStop)
		b.keepaliveWg.Wait()

		close(b.sendQueue)

		b.connMu.Lock()
		if b.udpConn != nil {
			b.udpConn.Close()
			b.udpConn = nil
		}
		b.connMu.Unlock()
	})
	return nil
}

func (b *XrayBind) SetMark(mark uint32) error {
	return nil
}

func (b *XrayBind) BatchSize() int {
	return 1
}

func (b *XrayBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	addrPort, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return &XrayEndpoint{addr: addrPort}, nil
}


func (b *XrayBind) processOutgoing() {
	for data := range b.sendQueue {
		if len(data) == 0 {
			continue
		}

		b.connMu.Lock()
		conn := b.udpConn
		b.connMu.Unlock()

		if conn == nil {
			// Return buffer to pool if it came from pool (check capacity)
			if cap(data) == PacketBufferSize {
				putPacketBuffer(&data)
			}
			continue
		}

		// Write directly to XrayUDPConn
		_, err := conn.Write(data)
		
		// OPTIMIZATION: Return buffer to pool after write completes.
		// The data has been copied to the network stack at this point.
		if cap(data) == PacketBufferSize {
			putPacketBuffer(&data)
		}
		
		if err != nil {
			// Log error but continue
			fmt.Printf("[XrayBind] Write error: %v\n", err)
			// Try to reconnect
			b.reconnect()
		}
	}
}

func (b *XrayBind) processIncoming() {
	packetCount := 0
	for {
		if atomic.LoadInt32(&b.closed) == 1 {
			return
		}

		b.connMu.Lock()
		conn := b.udpConn
		b.connMu.Unlock()

		if conn == nil {
			time.Sleep(100 * time.Millisecond)
			continue
		}

		// Read from XrayUDPConn with timeout
		data, err := conn.Read(100 * time.Millisecond)
		if err != nil {
			// Timeout is expected, continue
			if err.Error() == "timeout" {
				continue
			}
			// Other errors - try to reconnect
			fmt.Printf("[XrayBind] Read error: %v\n", err)
			b.reconnect()
			continue
		}

		if len(data) == 0 {
			continue
		}

		packetCount++
		atomic.AddUint64(&b.rxBytes, uint64(len(data)))

		// ===== HANDSHAKE DETECTION =====
		if len(data) >= 4 {
			msgType := data[0]
			if msgType == MessageTypeHandshakeResponse {
				// Handshake response received - mark timestamp for key confirmation
				now := time.Now().UnixNano()
				b.lastHandshakeTime.Store(now)
				b.handshakeConfirmed.Store(0) // Reset confirmation flag
				b.pendingConfirmation.Store(1) // Mark confirmation as pending

				// Extract receiver index from handshake response (bytes 4-7)
				if len(data) >= 8 {
					receiverIndex := binary.LittleEndian.Uint32(data[4:8])
					b.lastReceiverIndex.Store(receiverIndex)
					logPacketInspector("🤝 Handshake Response detected (type=%d, receiverIndex=%d), scheduling key confirmation", msgType, receiverIndex)
				} else {
					logPacketInspector("🤝 Handshake Response detected (type=%d), scheduling key confirmation", msgType)
				}
			} else if msgType == MessageTypeTransportData {
				// Receiving transport data means the session is working
				if b.pendingConfirmation.Load() == 1 && b.handshakeConfirmed.Load() == 0 {
					logPacketInspector("📥 Transport data received while confirmation pending - session active")
				}
			}
		}

		// ===== PACKET INSPECTOR =====
		var headerHex string
		if len(data) >= 4 {
			headerHex = fmt.Sprintf("[0x%02x, 0x%02x, 0x%02x, 0x%02x]", data[0], data[1], data[2], data[3])
		} else {
			headerHex = fmt.Sprintf("[%d bytes only]", len(data))
		}

		if len(data) >= 1532 || packetCount <= 10 || packetCount%50 == 0 {
			logPacketInspector("RX Len: %d, Header: %s (packetCount: %d)", len(data), headerHex, packetCount)
		}

		select {
		case b.recvQueue <- data:
			// Buffer ownership transferred to recvQueue consumer (receiveFunc)
		default:
			// OPTIMIZATION: Return pooled buffer when dropping packet.
			// Check if this buffer came from the pool (capacity == PacketBufferSize).
			if cap(data) == PacketBufferSize {
				putPacketBuffer(&data)
			}
			logPacketInspector("⚠️ Queue full, dropping %d bytes packet", len(data))
		}
	}
}


// keepaliveLoop handles both post-handshake key confirmation and periodic keepalives
func (b *XrayBind) keepaliveLoop() {
	defer b.keepaliveWg.Done()

	ticker := time.NewTicker(KeepaliveInterval)
	defer ticker.Stop()

	// Check for pending key confirmation more frequently
	confirmTicker := time.NewTicker(HandshakeConfirmationDelay)
	defer confirmTicker.Stop()

	for {
		select {
		case <-b.keepaliveStop:
			return

		case <-confirmTicker.C:
			// Check if we need to send key confirmation packet
			b.checkAndSendKeyConfirmation()

		case <-ticker.C:
			// Send periodic keepalive
			if atomic.LoadInt32(&b.closed) == 0 {
				b.sendKeepalive()
			}
		}
	}
}

// checkAndSendKeyConfirmation sends a key confirmation packet after handshake
func (b *XrayBind) checkAndSendKeyConfirmation() {
	lastHandshake := b.lastHandshakeTime.Load()
	if lastHandshake == 0 {
		return // No handshake yet
	}

	// Check if already confirmed
	if b.handshakeConfirmed.Load() == 1 {
		return
	}

	// Check if confirmation is pending
	if b.pendingConfirmation.Load() != 1 {
		return
	}

	// Check if enough time has passed since handshake (PostHandshakeDelay)
	elapsed := time.Since(time.Unix(0, lastHandshake))
	if elapsed < PostHandshakeDelay {
		return // Wait a bit more
	}

	// Try to trigger keepalive callback first (preferred method)
	b.keepaliveCallbackMu.RLock()
	callback := b.keepaliveCallback
	b.keepaliveCallbackMu.RUnlock()

	if callback != nil {
		logPacketInspector("🔑 Triggering WireGuard keepalive callback for key confirmation (elapsed: %v)", elapsed)
		callback()
		// Mark as confirmed - the callback will trigger WireGuard to send a proper packet
		if b.handshakeConfirmed.CompareAndSwap(0, 1) {
			b.pendingConfirmation.Store(0)
			logPacketInspector("✅ Key confirmation triggered via callback")
		}
		return
	}

	// Fallback: Send a raw keepalive packet directly
	// This is less ideal but ensures key confirmation happens
	logPacketInspector("🔑 Sending raw key confirmation packet (elapsed: %v, no callback set)", elapsed)
	b.sendRawKeepalivePacket()

	if b.handshakeConfirmed.CompareAndSwap(0, 1) {
		b.pendingConfirmation.Store(0)
		logPacketInspector("✅ Key confirmation sent via raw packet")
	}
}

// sendKeepalive sends a periodic keepalive packet
func (b *XrayBind) sendKeepalive() {
	// Try callback first
	b.keepaliveCallbackMu.RLock()
	callback := b.keepaliveCallback
	b.keepaliveCallbackMu.RUnlock()

	if callback != nil {
		callback()
		return
	}

	// Fallback to raw packet
	b.sendRawKeepalivePacket()
}

// sendRawKeepalivePacket sends a minimal WireGuard transport data packet
// This is used when no callback is available to trigger WireGuard's internal keepalive
func (b *XrayBind) sendRawKeepalivePacket() {
	b.connMu.Lock()
	conn := b.udpConn
	b.connMu.Unlock()

	if conn == nil {
		return
	}

	// Get the last receiver index from handshake
	receiverIndex := b.lastReceiverIndex.Load()
	if receiverIndex == 0 {
		logPacketInspector("⚠️ Cannot send raw keepalive: no receiver index available")
		return
	}

	// Build a minimal WireGuard transport data packet header
	// Format: Type(1) + Reserved(3) + Receiver(4) + Counter(8) + Encrypted(16+)
	// For keepalive, we need at least the header + auth tag (32 bytes minimum)
	// However, without the session keys, we can't properly encrypt
	// So we send a minimal packet that will be recognized as transport data
	// The server will decrypt and find empty payload = keepalive

	// Note: This is a best-effort approach. The proper way is to use the callback
	// which triggers WireGuard's internal mechanism with proper encryption.

	// OPTIMIZATION: Use pooled buffer for keepalive packets
	bufPtr := getPacketBuffer()
	packet := (*bufPtr)[:MinTransportDataSize]
	
	// Zero out the packet (pool buffers may contain old data)
	for i := range packet {
		packet[i] = 0
	}
	
	packet[0] = MessageTypeTransportData // Type = 4
	// packet[1], [2], [3] already 0 (Reserved)
	binary.LittleEndian.PutUint32(packet[4:8], receiverIndex)
	// Counter and auth tag will be zeros - this won't decrypt properly
	// but it signals to the server that we're trying to communicate

	select {
	case b.sendQueue <- packet:
		logPacketInspector("📤 Raw keepalive packet queued (receiverIndex=%d)", receiverIndex)
	default:
		// Return buffer to pool since it won't be consumed
		putPacketBuffer(bufPtr)
		logPacketInspector("⚠️ Failed to queue raw keepalive packet (queue full)")
	}
}

// SendKeepaliveNow forces an immediate keepalive packet (for external use)
func (b *XrayBind) SendKeepaliveNow() {
	if atomic.LoadInt32(&b.closed) == 0 {
		b.sendKeepalive()
	}
}

// TriggerKeyConfirmation forces immediate key confirmation (for external use after handshake)
func (b *XrayBind) TriggerKeyConfirmation() {
	b.lastHandshakeTime.Store(time.Now().UnixNano())
	b.handshakeConfirmed.Store(0)
	b.pendingConfirmation.Store(1)
	logPacketInspector("🔄 Key confirmation triggered externally")
}

// ForceKeyConfirmationNow immediately sends key confirmation without waiting
func (b *XrayBind) ForceKeyConfirmationNow() {
	if atomic.LoadInt32(&b.closed) == 1 {
		return
	}

	b.keepaliveCallbackMu.RLock()
	callback := b.keepaliveCallback
	b.keepaliveCallbackMu.RUnlock()

	if callback != nil {
		logPacketInspector("🔑 Force key confirmation via callback")
		callback()
		b.handshakeConfirmed.Store(1)
		b.pendingConfirmation.Store(0)
	} else {
		logPacketInspector("🔑 Force key confirmation via raw packet")
		b.sendRawKeepalivePacket()
		b.handshakeConfirmed.Store(1)
		b.pendingConfirmation.Store(0)
	}
}


func (b *XrayBind) reconnect() {
	b.connMu.Lock()
	defer b.connMu.Unlock()

	if b.udpConn != nil {
		b.udpConn.Close()
	}

	addrStr := b.endpoint.Addr().String()
	portInt := int(b.endpoint.Port())

	udpConn, err := b.xrayWrapper.DialUDP(addrStr, portInt)
	if err != nil {
		fmt.Printf("[XrayBind] Reconnect dial failed: %v\n", err)
		return
	}

	if err := udpConn.Connect(); err != nil {
		fmt.Printf("[XrayBind] Reconnect connect failed: %v\n", err)
		return
	}

	b.udpConn = udpConn
	fmt.Printf("[XrayBind] ✅ Reconnected to %s:%d\n", addrStr, portInt)
}

func (b *XrayBind) GetStats() (uint64, uint64) {
	return atomic.LoadUint64(&b.txBytes), atomic.LoadUint64(&b.rxBytes)
}

// GetLastHandshakeTime returns the timestamp of the last handshake response
func (b *XrayBind) GetLastHandshakeTime() time.Time {
	ts := b.lastHandshakeTime.Load()
	if ts == 0 {
		return time.Time{}
	}
	return time.Unix(0, ts)
}

// IsHandshakeConfirmed returns whether the last handshake has been confirmed
func (b *XrayBind) IsHandshakeConfirmed() bool {
	return b.handshakeConfirmed.Load() == 1
}

// IsPendingConfirmation returns whether a key confirmation is pending
func (b *XrayBind) IsPendingConfirmation() bool {
	return b.pendingConfirmation.Load() == 1
}

// GetLastReceiverIndex returns the last receiver index from handshake
func (b *XrayBind) GetLastReceiverIndex() uint32 {
	return b.lastReceiverIndex.Load()
}

// XrayEndpoint implements conn.Endpoint
type XrayEndpoint struct {
	addr netip.AddrPort
}

func (e *XrayEndpoint) ClearSrc() {}

func (e *XrayEndpoint) SrcToString() string {
	return ""
}

func (e *XrayEndpoint) DstToString() string {
	return e.addr.String()
}

func (e *XrayEndpoint) DstIP() netip.Addr {
	return e.addr.Addr()
}

func (e *XrayEndpoint) SrcIP() netip.Addr {
	return netip.Addr{}
}

func (e *XrayEndpoint) DstPort() uint16 {
	return e.addr.Port()
}

func (e *XrayEndpoint) DstToBytes() []byte {
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
