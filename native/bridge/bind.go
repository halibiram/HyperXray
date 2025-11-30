package bridge

import (
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/conn"
)

// XrayBind implements conn.Bind to route WireGuard UDP through Xray
type XrayBind struct {
	xray     *XrayWrapper
	endpoint string
	host     string
	port     int
	
	udpConn  *XrayUDPConn
	
	mu       sync.Mutex
	closed   bool
	stopChan chan struct{} // Channel to signal health check goroutine to stop
	
	// Health check
	lastHealthCheck time.Time
	healthCheckMu   sync.Mutex
	
	// Statistics
	txBytes   atomic.Uint64
	rxBytes   atomic.Uint64
	txPackets atomic.Uint64
	rxPackets atomic.Uint64
}

// NewXrayBind creates bind that routes through Xray
func NewXrayBind(xray *XrayWrapper, endpoint string) (*XrayBind, error) {
	logInfo("[XrayBind] ========================================")
	logInfo("[XrayBind] Creating XrayBind")
	logInfo("[XrayBind] Endpoint: %s", endpoint)
	logInfo("[XrayBind] ========================================")
	
	// Parse endpoint
	host, portStr, err := net.SplitHostPort(endpoint)
	if err != nil {
		logError("[XrayBind] Invalid endpoint: %v", err)
		return nil, fmt.Errorf("invalid endpoint: %w", err)
	}
	
	var port int
	fmt.Sscanf(portStr, "%d", &port)
	
	logDebug("[XrayBind] Host: %s, Port: %d", host, port)
	
	return &XrayBind{
		xray:     xray,
		endpoint: endpoint,
		host:     host,
		port:     port,
		stopChan: make(chan struct{}),
	}, nil
}

// Open implements conn.Bind
func (b *XrayBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	
	logInfo("[XrayBind] ========================================")
	logInfo("[XrayBind] Opening bind...")
	logInfo("[XrayBind] Endpoint: %s", b.endpoint)
	logInfo("[XrayBind] ========================================")
	
	// If bind is already open and connected, reuse the connection
	if !b.closed && b.udpConn != nil {
		logDebug("[XrayBind] ✅ Bind already open, reusing connection")
		recvFn := b.makeReceiveFunc()
		return []conn.ReceiveFunc{recvFn}, port, nil
	}
	
	// If bind was closed, reset the closed flag and reopen
	if b.closed {
		logInfo("[XrayBind] Bind was closed, reopening...")
		b.closed = false
	}
	
	// Check Xray is running
	if !b.xray.IsRunning() {
		logError("[XrayBind] ❌ Xray is NOT running! Cannot open bind.")
		return nil, 0, fmt.Errorf("xray not running")
	}
	
	logInfo("[XrayBind] ✅ Xray is confirmed running")
	
	// Create UDP connection through Xray
	logDebug("[XrayBind] Creating UDP connection through Xray...")
	
	var err error
	b.udpConn, err = b.xray.DialUDP(b.host, b.port)
	if err != nil {
		logError("[XrayBind] ❌ DialUDP failed: %v", err)
		return nil, 0, fmt.Errorf("dial udp: %w", err)
	}
	
	logInfo("[XrayBind] ✅ DialUDP successful")
	
	// Connect
	logInfo("[XrayBind] ========================================")
	logInfo("[XrayBind] Calling Connect() to establish connection and start readLoop()...")
	logInfo("[XrayBind] ========================================")
	
	if err := b.udpConn.Connect(); err != nil {
		logError("[XrayBind] ❌ Connect() FAILED: %v", err)
		logError("[XrayBind] ❌ readLoop() will NOT be started due to Connect() failure")
		return nil, 0, fmt.Errorf("connect: %w", err)
	}
	
	logInfo("[XrayBind] ✅ Connect() successful!")
	logInfo("[XrayBind] ✅ readLoop() should be started by Connect()")
	logInfo("[XrayBind] ✅ Connected through Xray!")
	logInfo("[XrayBind] ========================================")
	
	// Start health check goroutine
	go b.healthCheckLoop()
	
	recvFn := b.makeReceiveFunc()
	
	return []conn.ReceiveFunc{recvFn}, port, nil
}

// makeReceiveFunc creates WireGuard receive function
func (b *XrayBind) makeReceiveFunc() conn.ReceiveFunc {
	timeoutCount := 0
	successCount := 0
	
	return func(bufs [][]byte, sizes []int, eps []conn.Endpoint) (n int, err error) {
		if len(bufs) == 0 {
			return 0, nil
		}
		
		// Check connection state before reading
		b.mu.Lock()
		connValid := b.udpConn != nil && !b.closed
		connState := "nil"
		if b.udpConn != nil {
			if b.udpConn.IsConnected() {
				connState = "connected"
			} else {
				connState = "not connected"
			}
		}
		b.mu.Unlock()
		
		// If connection is invalid, try to reconnect instead of returning error
		// This prevents WireGuard from closing the connection immediately
		if !connValid {
			logWarn("[XrayBind] makeReceiveFunc: Connection invalid (state: %s), attempting reconnect...", connState)
			
			// Try to reconnect (reconnect() handles its own locking)
			reconnectErr := b.reconnect()
			
			if reconnectErr != nil {
				logError("[XrayBind] makeReceiveFunc: Reconnect failed: %v", reconnectErr)
				// Return timeout error instead of connection invalid error
				// This allows WireGuard to retry instead of closing immediately
				return 0, fmt.Errorf("read timeout (reconnect failed: %v)", reconnectErr)
			}
			
			// Recheck connection after reconnect
			b.mu.Lock()
			connValid = b.udpConn != nil && !b.closed
			if b.udpConn != nil {
				if b.udpConn.IsConnected() {
					connState = "connected"
				} else {
					connState = "not connected"
				}
			}
			b.mu.Unlock()
			
			if !connValid {
				logWarn("[XrayBind] makeReceiveFunc: Connection still invalid after reconnect")
				// Return timeout to allow retry
				return 0, fmt.Errorf("read timeout (connection still invalid)")
			}
			
			logInfo("[XrayBind] makeReceiveFunc: ✅ Reconnected successfully, continuing read...")
		}
		
		// Log read attempt periodically
		if timeoutCount%10 == 0 && timeoutCount > 0 {
			logDebug("[XrayBind] makeReceiveFunc: Waiting for data (timeout: 30s, successCount: %d, timeoutCount: %d)...", successCount, timeoutCount)
		}
		
		// Read with timeout
		data, err := b.udpConn.Read(30 * time.Second)
		if err != nil {
			timeoutCount++
			
			// Check if it's a timeout error (expected for UDP when no data available)
			errStr := err.Error()
			isTimeout := strings.Contains(errStr, "timeout") || strings.Contains(errStr, "deadline")
			
			// Check if connection is still valid
			b.mu.Lock()
			connStillValid := b.udpConn != nil && !b.closed
			if b.udpConn != nil {
				if b.udpConn.IsConnected() {
					connState = "connected"
				} else {
					connState = "not connected"
				}
			}
			b.mu.Unlock()
			
			// If it's a timeout and connection is still valid, this is normal UDP behavior
			if isTimeout && connStillValid {
				// Log timeout periodically to avoid spam
				if timeoutCount%10 == 0 || timeoutCount == 1 {
					logDebug("[XrayBind] makeReceiveFunc: Read timeout #%d (normal UDP behavior, no data available, connState: %s)", 
						timeoutCount, connState)
				}
				// Return timeout error - WireGuard will retry
				return 0, err
			}
			
			// For non-timeout errors or invalid connection, check if we need to reconnect
			if !connStillValid {
				logWarn("[XrayBind] makeReceiveFunc: Connection invalid during read (state: %s), attempting reconnect...", connState)
				if reconnectErr := b.reconnect(); reconnectErr != nil {
					logError("[XrayBind] makeReceiveFunc: Reconnect failed: %v", reconnectErr)
					// Return timeout to allow retry
					return 0, fmt.Errorf("read timeout (reconnect failed: %v)", reconnectErr)
				}
				logInfo("[XrayBind] makeReceiveFunc: ✅ Reconnected successfully, WireGuard will retry read...")
				// Return timeout to allow WireGuard to retry with new connection
				return 0, fmt.Errorf("read timeout (reconnected, retry needed)")
			}
			
			// Log other errors periodically
			if timeoutCount%10 == 0 || timeoutCount == 1 {
				logWarn("[XrayBind] makeReceiveFunc: ⚠️ Read error #%d: %v (successCount: %d, timeoutCount: %d, connState: %s)", 
					timeoutCount, err, successCount, timeoutCount, connState)
			} else {
				logDebug("[XrayBind] makeReceiveFunc: Read error #%d: %v", timeoutCount, err)
			}
			return 0, err
		}
		
		successCount++
		
		// Copy to buffer
		if len(data) > len(bufs[0]) {
			logWarn("[XrayBind] makeReceiveFunc: Data too large (%d > %d), truncating", len(data), len(bufs[0]))
			data = data[:len(bufs[0])]
		}
		copy(bufs[0], data)
		sizes[0] = len(data)
		
		// Update stats
		b.rxBytes.Add(uint64(len(data)))
		b.rxPackets.Add(1)
		
		// Create endpoint
		eps[0] = &xrayEndpoint{addr: b.endpoint}
		
		if successCount%10 == 0 || len(data) > 100 {
			logInfo("[XrayBind] makeReceiveFunc: ✅ ← Received %d bytes (successCount: %d, timeoutCount: %d)", len(data), successCount, timeoutCount)
		} else {
			logDebug("[XrayBind] makeReceiveFunc: ✅ ← Received %d bytes", len(data))
		}
		
		return 1, nil
	}
}

// Send implements conn.Bind
func (b *XrayBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	b.mu.Lock()
	conn := b.udpConn
	b.mu.Unlock()
	
	if conn == nil {
		// Attempt to reconnect
		logWarn("[XrayBind] Connection is nil, attempting reconnect...")
		if err := b.reconnect(); err != nil {
			logError("[XrayBind] Reconnect failed: %v", err)
			return fmt.Errorf("not connected: %w", err)
		}
		b.mu.Lock()
		conn = b.udpConn
		b.mu.Unlock()
		if conn == nil {
			return fmt.Errorf("not connected after reconnect")
		}
	}
	
	// Check if connection is still valid
	if !conn.IsConnected() {
		logWarn("[XrayBind] Connection is not valid, attempting reconnect...")
		if err := b.reconnect(); err != nil {
			logError("[XrayBind] Reconnect failed: %v", err)
			return fmt.Errorf("connection invalid: %w", err)
		}
		b.mu.Lock()
		conn = b.udpConn
		b.mu.Unlock()
		if conn == nil {
			return fmt.Errorf("not connected after reconnect")
		}
	}
	
	for _, buf := range bufs {
		n, err := conn.Write(buf)
		if err != nil {
			logError("[XrayBind] Send error: %v, attempting reconnect...", err)
			
			// Attempt to reconnect and retry once
			if reconnectErr := b.reconnect(); reconnectErr != nil {
				logError("[XrayBind] Reconnect failed: %v", reconnectErr)
				return err
			}
			
			b.mu.Lock()
			conn = b.udpConn
			b.mu.Unlock()
			if conn == nil {
				return fmt.Errorf("not connected after reconnect")
			}
			
			// Retry write after reconnect
			n, err = conn.Write(buf)
			if err != nil {
				logError("[XrayBind] Send error after reconnect: %v", err)
				return err
			}
		}
		
		b.txBytes.Add(uint64(n))
		b.txPackets.Add(1)
		
		logDebug("[XrayBind] → Sent %d bytes", n)
	}
	
	return nil
}

// ParseEndpoint implements conn.Bind
func (b *XrayBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	logDebug("[XrayBind] ParseEndpoint: %s", s)
	return &xrayEndpoint{addr: s}, nil
}

// Close implements conn.Bind - IMMEDIATE cleanup
func (b *XrayBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	
	logInfo("[XrayBind] ⚡ IMMEDIATE CLOSE: Closing...")
	
	// IMMEDIATELY mark as closed
	b.closed = true
	
	// IMMEDIATELY signal health check goroutine to stop
	select {
	case <-b.stopChan:
		// Already closed
	default:
		close(b.stopChan)
	}
	
	// IMMEDIATELY close UDP connection (goroutines will exit via context cancel)
	if b.udpConn != nil {
		b.udpConn.Close()
		b.udpConn = nil
	}
	
	logInfo("[XrayBind] ✅ Closed immediately")
	return nil
}

// SetMark implements conn.Bind (no-op for Android)
func (b *XrayBind) SetMark(mark uint32) error {
	return nil
}

// BatchSize implements conn.Bind
func (b *XrayBind) BatchSize() int {
	return 1
}

// GetStats returns traffic statistics
func (b *XrayBind) GetStats() (tx, rx, txP, rxP uint64) {
	return b.txBytes.Load(), b.rxBytes.Load(), 
	       b.txPackets.Load(), b.rxPackets.Load()
}

// reconnect attempts to reconnect the XrayBind connection
func (b *XrayBind) reconnect() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	
	if b.closed {
		return fmt.Errorf("bind is closed")
	}
	
	// Check Xray is running
	if !b.xray.IsRunning() {
		logError("[XrayBind] ❌ Xray is NOT running! Cannot reconnect.")
		return fmt.Errorf("xray not running")
	}
	
	logInfo("[XrayBind] Attempting to reconnect to %s...", b.endpoint)
	
	// Close old connection if exists
	if b.udpConn != nil {
		b.udpConn.Close()
		b.udpConn = nil
	}
	
	// Create new UDP connection through Xray
	var err error
	b.udpConn, err = b.xray.DialUDP(b.host, b.port)
	if err != nil {
		logError("[XrayBind] ❌ Reconnect DialUDP failed: %v", err)
		return fmt.Errorf("reconnect dial udp: %w", err)
	}
	
	logInfo("[XrayBind] ✅ Reconnect DialUDP successful")
	
	// Connect
	if err := b.udpConn.Connect(); err != nil {
		logError("[XrayBind] ❌ Reconnect Connect() failed: %v", err)
		return fmt.Errorf("reconnect connect: %w", err)
	}
	
	logInfo("[XrayBind] ✅ Reconnected successfully!")
	
	// Update health check time
	b.healthCheckMu.Lock()
	b.lastHealthCheck = time.Now()
	b.healthCheckMu.Unlock()
	
	return nil
}

// healthCheckLoop periodically checks connection health and reconnects if needed
func (b *XrayBind) healthCheckLoop() {
	logInfo("[XrayBind] Health check loop started")
	ticker := time.NewTicker(10 * time.Second) // Check every 10 seconds
	defer ticker.Stop()
	
	lastRxBytes := uint64(0)
	lastRxPackets := uint64(0)
	noDataCount := 0
	
	for {
		select {
		case <-b.stopChan:
			logInfo("[XrayBind] Health check loop exiting: stop channel signalled")
			return
		case <-ticker.C:
			if b.closed {
				logInfo("[XrayBind] Health check loop exiting: bind closed")
				return
			}
			
			b.mu.Lock()
			conn := b.udpConn
			currentRxBytes := b.rxBytes.Load()
			currentRxPackets := b.rxPackets.Load()
			currentTxBytes := b.txBytes.Load()
			currentTxPackets := b.txPackets.Load()
			b.mu.Unlock()
			
			// Check if connection is valid
			if conn == nil || !conn.IsConnected() {
				logWarn("[XrayBind] Health check: Connection is invalid (conn: %v, connected: %v), attempting reconnect...", 
					conn != nil, conn != nil && conn.IsConnected())
				if err := b.reconnect(); err != nil {
					logError("[XrayBind] Health check reconnect failed: %v", err)
				} else {
					logInfo("[XrayBind] Health check: Reconnected successfully")
					// Reset counters after reconnect
					lastRxBytes = 0
					lastRxPackets = 0
					noDataCount = 0
				}
			} else {
				// Check if we're receiving data
				if currentRxBytes == lastRxBytes && currentRxPackets == lastRxPackets {
					noDataCount++
					if noDataCount >= 3 {
						logWarn("[XrayBind] Health check: ⚠️ No data received for %d checks (txBytes: %d, txPackets: %d, rxBytes: %d, rxPackets: %d)", 
							noDataCount, currentTxBytes, currentTxPackets, currentRxBytes, currentRxPackets)
						logWarn("[XrayBind] Health check: Connection appears healthy but no data is being received")
						logWarn("[XrayBind] Health check: This may indicate readLoop() is not receiving data from Xray-core")
					} else {
						logDebug("[XrayBind] Health check: Connection is healthy (no data yet, check #%d)", noDataCount)
					}
				} else {
					// Data is being received
					noDataCount = 0
					rxBytesDiff := currentRxBytes - lastRxBytes
					rxPacketsDiff := currentRxPackets - lastRxPackets
					logInfo("[XrayBind] Health check: ✅ Connection is healthy (rxBytes: +%d, rxPackets: +%d, total: %d bytes, %d packets)", 
						rxBytesDiff, rxPacketsDiff, currentRxBytes, currentRxPackets)
				}
				
				lastRxBytes = currentRxBytes
				lastRxPackets = currentRxPackets
				
				b.healthCheckMu.Lock()
				b.lastHealthCheck = time.Now()
				b.healthCheckMu.Unlock()
			}
		}
	}
}

// xrayEndpoint implements conn.Endpoint
type xrayEndpoint struct {
	addr string
}

func (e *xrayEndpoint) ClearSrc()           {}
func (e *xrayEndpoint) SrcToString() string { return "" }
func (e *xrayEndpoint) DstToString() string { return e.addr }

func (e *xrayEndpoint) DstIP() netip.Addr {
	host, _, _ := net.SplitHostPort(e.addr)
	addr, _ := netip.ParseAddr(host)
	return addr
}

func (e *xrayEndpoint) SrcIP() netip.Addr {
	return netip.Addr{}
}

func (e *xrayEndpoint) DstToBytes() []byte {
	host, _, _ := net.SplitHostPort(e.addr)
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return nil
	}
	if addr.Is4() {
		ip4 := addr.As4()
		return ip4[:]
	} else if addr.Is6() {
		ip6 := addr.As16()
		return ip6[:]
	}
	return nil
}

func (e *xrayEndpoint) DstPort() uint16 {
	_, portStr, err := net.SplitHostPort(e.addr)
	if err != nil {
		return 0
	}
	var port uint16
	fmt.Sscanf(portStr, "%d", &port)
	return port
}
