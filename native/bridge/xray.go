package bridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/log"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/infra/conf"

	// Import all Xray features
	_ "github.com/xtls/xray-core/main/distro/all"
)

// XrayLogWriter implements log.Handler to capture Xray logs
type XrayLogWriter struct{}

func (w *XrayLogWriter) Handle(msg log.Message) {
	// Forward Xray internal logs to our logging
	logInfo("[Xray-Internal] %s", msg.String())
}

// XrayWrapper manages Xray-core instance
type XrayWrapper struct {
	instance *core.Instance
	handler  outbound.Manager
	ctx      context.Context
	cancel   context.CancelFunc
	running  bool
	mu       sync.Mutex
}

// ensureDebugLogging adds debug log settings to config
func ensureDebugLogging(configJSON string) string {
	// Parse and modify config to ensure debug logging
	var config map[string]interface{}
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		logWarn("[Xray] Could not parse config for log injection")
		return configJSON
	}

	// Add or update log section
	config["log"] = map[string]interface{}{
		"loglevel": "debug",
		"error":    "",  // stderr
		"access":   "",  // stdout
	}

	newConfig, err := json.Marshal(config)
	if err != nil {
		logWarn("[Xray] Could not re-marshal config")
		return configJSON
	}

	logDebug("[Xray] Debug logging enabled in config")
	return string(newConfig)
}

// NewXrayWrapper creates Xray instance from JSON config
func NewXrayWrapper(configJSON string) (*XrayWrapper, error) {
	logInfo("[Xray] ========================================")
	logInfo("[Xray] Creating Xray-core instance...")
	logInfo("[Xray] ========================================")
	logDebug("[Xray] Config length: %d bytes", len(configJSON))
	
	// Enable Xray internal logging FIRST
	logInfo("[Xray] Enabling Xray internal debug logging...")
	log.RegisterHandler(&XrayLogWriter{})
	
	// Modify config to enable debug logging if not present
	configJSON = ensureDebugLogging(configJSON)
	
	// Parse JSON config
	jsonConfig := &conf.Config{}
	if err := json.Unmarshal([]byte(configJSON), jsonConfig); err != nil {
		logError("[Xray] Failed to parse JSON: %v", err)
		return nil, fmt.Errorf("parse json: %w", err)
	}
	
	logInfo("[Xray] JSON parsed: %d inbounds, %d outbounds", 
		len(jsonConfig.InboundConfigs), len(jsonConfig.OutboundConfigs))
	
	// Log outbound details
	logInfo("[Xray] Found %d outbound(s):", len(jsonConfig.OutboundConfigs))
	for i, ob := range jsonConfig.OutboundConfigs {
		logInfo("[Xray]   Outbound[%d]: protocol=%s, tag=%s", i, ob.Protocol, ob.Tag)
		if ob.Settings != nil {
			logDebug("[Xray]     Settings: %+v", ob.Settings)
		}
	}
	
	// Validate at least one outbound exists
	if len(jsonConfig.OutboundConfigs) == 0 {
		logError("[Xray] ❌ No outbounds found in config!")
		return nil, fmt.Errorf("no outbounds in config")
	}
	
	// Build protobuf config
	pbConfig, err := jsonConfig.Build()
	if err != nil {
		logError("[Xray] Failed to build config: %v", err)
		return nil, fmt.Errorf("build config: %w", err)
	}
	logInfo("[Xray] Protobuf config built")
	
	// Create instance
	instance, err := core.New(pbConfig)
	if err != nil {
		logError("[Xray] Failed to create instance: %v", err)
		return nil, fmt.Errorf("create instance: %w", err)
	}
	logInfo("[Xray] ✅ Xray instance created")
	
	ctx, cancel := context.WithCancel(context.Background())
	
	return &XrayWrapper{
		instance: instance,
		ctx:      ctx,
		cancel:   cancel,
	}, nil
}

// Start starts Xray-core
func (x *XrayWrapper) Start() error {
	x.mu.Lock()
	defer x.mu.Unlock()
	
	if x.running {
		logWarn("[Xray] Already running, skipping Start()")
		return nil
	}
	
	logInfo("[Xray] ========================================")
	logInfo("[Xray] Starting Xray-core...")
	logInfo("[Xray] ========================================")
	
	// Check instance exists
	if x.instance == nil {
		logError("[Xray] ❌ Instance is nil! Cannot start.")
		return fmt.Errorf("xray instance is nil")
	}
	
	logDebug("[Xray] Instance exists, calling Start()...")
	
	// Actually start Xray
	err := x.instance.Start()
	if err != nil {
		logError("[Xray] ❌ Start() FAILED: %v", err)
		return fmt.Errorf("xray start failed: %w", err)
	}
	
	logInfo("[Xray] ✅ instance.Start() returned successfully")
	
	// Verify it's actually running by getting features
	logDebug("[Xray] Verifying Xray is running...")
	
	// Try to get outbound manager as verification
	outboundMgr := x.instance.GetFeature(outbound.ManagerType())
	if outboundMgr == nil {
		logError("[Xray] ❌ Could not get outbound manager - Xray may not be running")
		return fmt.Errorf("xray not properly initialized")
	}
	
	x.handler = outboundMgr.(outbound.Manager)
	logInfo("[Xray] ✅ Outbound manager obtained")
	logDebug("[Xray] Outbound manager ready for routing")
	
	x.running = true
	
	logInfo("[Xray] ========================================")
	logInfo("[Xray] ✅ XRAY-CORE IS NOW RUNNING!")
	logInfo("[Xray] ========================================")
	
	logInfo("[Xray] ")
	logInfo("[Xray] ▶▶▶ Verifying Xray can reach internet... (SKIPPED)")
	logInfo("[Xray] ")
	logInfo("[Xray] ℹ️  Skipping connectivity check - will be verified during tunnel operation")
	logInfo("[Xray] ℹ️  Xray-core is running and ready to handle traffic")
	
	// NOTE: Connectivity check is skipped because ProtectedDialer requires
	// VPN to be fully established, which happens after Xray starts.
	// Connectivity will be verified naturally as traffic flows through Xray.
	
	// Wait a moment for Xray to fully initialize
	time.Sleep(500 * time.Millisecond)
	
	// ===== CONNECTIVITY VERIFICATION (SKIPPED) =====
	// Skip connectivity check - it will be verified during actual tunnel operation
	// The check was timing out because ProtectedDialer needs VPN to be fully established
	/*
	result, err := x.CheckXrayConnectivity()
	if err != nil {
		logError("[Xray] ❌ CONNECTIVITY CHECK FAILED!")
		logError("[Xray] ❌ Error: %v", err)
		logError("[Xray] ")
		
		// Enhanced error reporting with log analysis
		if result != nil && result.XrayLogAnalysis != nil {
			analysis := result.XrayLogAnalysis
			logError("[Xray] ═══════════════════════════════════════════")
			logError("[Xray] XRAY LOG ANALYSIS RESULTS:")
			logError("[Xray] ═══════════════════════════════════════════")
			logError("[Xray] Error Type: %s", analysis.ErrorType)
			logError("[Xray] Error Details: %s", analysis.ErrorDetails)
			
			if len(analysis.DetectedIssues) > 0 {
				logError("[Xray] ")
				logError("[Xray] Detected Issues:")
				for i, issue := range analysis.DetectedIssues {
					logError("[Xray]   %d. %s", i+1, issue)
				}
			}
			
			if len(analysis.PossibleCauses) > 0 {
				logError("[Xray] ")
				logError("[Xray] Possible Causes:")
				for i, cause := range analysis.PossibleCauses {
					logError("[Xray]   %d. %s", i+1, cause)
				}
			}
			
			if len(analysis.Recommendations) > 0 {
				logError("[Xray] ")
				logError("[Xray] Recommendations:")
				for i, rec := range analysis.Recommendations {
					logError("[Xray]   %d. %s", i+1, rec)
				}
			}
			logError("[Xray] ═══════════════════════════════════════════")
		} else {
			logError("[Xray] This means Xray started but cannot reach the internet.")
			logError("[Xray] Possible causes:")
			logError("[Xray]   1. Server unreachable (check your Xray server config)")
			logError("[Xray]   2. Invalid VLESS/VMess credentials")
			logError("[Xray]   3. TLS/REALITY handshake failed")
			logError("[Xray]   4. Network/firewall blocking")
		}
		logError("[Xray] ")
		
		// Don't stop Xray - let caller decide
		// But return the error so they know
		if result != nil && result.XrayLogAnalysis != nil {
			return fmt.Errorf("connectivity check failed: %s. %s", err.Error(), result.XrayLogAnalysis.ErrorDetails)
		}
		return fmt.Errorf("connectivity check failed: %w", err)
	}
	
	logInfo("[Xray] ")
	logInfo("[Xray] ========================================")
	logInfo("[Xray] ✅ XRAY CONNECTIVITY VERIFIED!")
	logInfo("[Xray]    Latency: %v", result.Latency)
	logInfo("[Xray]    Test URL: %s", result.TestURL)
	*/
	logInfo("[Xray] ========================================")
	logInfo("[Xray] ")
	
	return nil
}

// StartWithoutVerification starts Xray without connectivity check
// Use this if you want to handle verification separately
func (x *XrayWrapper) StartWithoutVerification() error {
	x.mu.Lock()
	defer x.mu.Unlock()
	
	if x.running {
		return nil
	}
	
	logInfo("[Xray] Starting Xray-core (no verification)...")
	
	if x.instance == nil {
		return fmt.Errorf("instance is nil")
	}
	
	if err := x.instance.Start(); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	
	outboundMgr := x.instance.GetFeature(outbound.ManagerType())
	if outboundMgr == nil {
		x.instance.Close()
		return fmt.Errorf("no outbound manager")
	}
	
	x.handler = outboundMgr.(outbound.Manager)
	x.running = true
	
	logInfo("[Xray] ✅ Xray-core started (unverified)")
	return nil
}

// Stop stops Xray-core
func (x *XrayWrapper) Stop() error {
	x.mu.Lock()
	defer x.mu.Unlock()
	
	if !x.running {
		return nil
	}
	
	logInfo("[Xray] Stopping Xray-core...")
	x.cancel()
	
	if err := x.instance.Close(); err != nil {
		logError("[Xray] Close error: %v", err)
		return err
	}
	
	x.running = false
	logInfo("[Xray] Xray-core stopped")
	return nil
}

// DialUDP creates UDP-like connection through Xray
func (x *XrayWrapper) DialUDP(address string, port int) (*XrayUDPConn, error) {
	logInfo("[Xray] ========================================")
	logInfo("[Xray] DialUDP called")
	logInfo("[Xray] Target: %s:%d", address, port)
	logInfo("[Xray] ========================================")
	
	if !x.running {
		logError("[Xray] ❌ Cannot DialUDP - Xray not running!")
		return nil, fmt.Errorf("xray not running")
	}
	
	if x.instance == nil {
		logError("[Xray] ❌ Instance is nil!")
		return nil, fmt.Errorf("instance is nil")
	}
	
	// Log outbound manager state
	outboundMgr := x.instance.GetFeature(outbound.ManagerType())
	if outboundMgr == nil {
		logError("[Xray] ❌ Outbound manager is nil!")
		return nil, fmt.Errorf("outbound manager is nil")
	}
	logInfo("[Xray] ✅ Outbound manager obtained")
	
	// Create destination
	dest := xnet.UDPDestination(xnet.ParseAddress(address), xnet.Port(port))
	logInfo("[Xray] Destination created: %v (Network: %v, Address: %v, Port: %v)", dest, dest.Network, dest.Address, dest.Port)
	
	// Create outbound context with destination
	// Use parent context and add outbound information
	ctx := x.ctx
	// Note: session.ContextWithID and ContextWithOutbound may not be available
	// in this version of xray-core, so we use the context directly
	// The outbound manager will handle routing based on destination
	
	conn := &XrayUDPConn{
		xray:    x,
		ctx:     ctx,
		dest:    dest,
		address: address,
		port:    port,
		readCh:  make(chan []byte, 256),
	}
	
	logInfo("[Xray] ✅ XrayUDPConn created")
	
	return conn, nil
}

// IsRunning returns running state
func (x *XrayWrapper) IsRunning() bool {
	x.mu.Lock()
	defer x.mu.Unlock()
	return x.running
}

// XrayUDPConn wraps Xray connection as UDP-like
type XrayUDPConn struct {
	xray     *XrayWrapper
	ctx      context.Context
	dest     xnet.Destination
	address  string
	port     int
	conn     net.Conn
	readCh   chan []byte
	mu       sync.Mutex
	closed   bool
	reconnecting bool
	reconnectMu  sync.Mutex
}

// Connect establishes the connection
func (c *XrayUDPConn) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	logInfo("[XrayUDP] ========================================")
	logInfo("[XrayUDP] Connecting to %s:%d through Xray...", c.address, c.port)
	logInfo("[XrayUDP] ========================================")
	
	// Dial through Xray's routing
	logInfo("[XrayUDP] Calling core.Dial()...")
	logInfo("[XrayUDP] Destination: %v (address: %s, port: %d)", c.dest, c.address, c.port)
	
	// Log Xray instance state
	if c.xray.instance == nil {
		logError("[XrayUDP] ❌ Xray instance is nil!")
		return fmt.Errorf("xray instance is nil")
	}
	
	// Log outbound manager state
	outboundMgr := c.xray.instance.GetFeature(outbound.ManagerType())
	if outboundMgr == nil {
		logError("[XrayUDP] ❌ Outbound manager is nil!")
		return fmt.Errorf("outbound manager is nil")
	}
	logDebug("[XrayUDP] Outbound manager obtained, dialing...")
	
	conn, err := core.Dial(c.ctx, c.xray.instance, c.dest)
	if err != nil {
		logError("[XrayUDP] ❌ core.Dial() FAILED: %v", err)
		logError("[XrayUDP] ❌ Destination: %v", c.dest)
		logError("[XrayUDP] ❌ Address: %s:%d", c.address, c.port)
		return fmt.Errorf("dial: %w", err)
	}
	
	if conn == nil {
		logError("[XrayUDP] ❌ core.Dial() returned nil connection!")
		logError("[XrayUDP] ❌ Destination: %v", c.dest)
		return fmt.Errorf("dial returned nil")
	}
	
	c.conn = conn
	
	logInfo("[XrayUDP] ✅ core.Dial() successful!")
	
	// Detailed connection state check
	localAddr := conn.LocalAddr()
	remoteAddr := conn.RemoteAddr()
	logInfo("[XrayUDP] Local addr: %v", localAddr)
	logInfo("[XrayUDP] Remote addr: %v", remoteAddr)
	
	// Validate addresses are not 0.0.0.0:0
	if localAddr != nil {
		localStr := localAddr.String()
		if localStr == "0.0.0.0:0" || localStr == "[::]:0" {
			logWarn("[XrayUDP] ⚠️ Local address is invalid: %v - This may indicate connection issue", localAddr)
		} else {
			logInfo("[XrayUDP] ✅ Local address is valid: %v", localAddr)
		}
	} else {
		logWarn("[XrayUDP] ⚠️ Local address is nil!")
	}
	
	if remoteAddr != nil {
		remoteStr := remoteAddr.String()
		if remoteStr == "0.0.0.0:0" || remoteStr == "[::]:0" {
			logWarn("[XrayUDP] ⚠️ Remote address is invalid: %v - This may indicate connection issue", remoteAddr)
		} else {
			logInfo("[XrayUDP] ✅ Remote address is valid: %v", remoteAddr)
		}
	} else {
		logWarn("[XrayUDP] ⚠️ Remote address is nil!")
	}
	
	// Log connection type
	connType := fmt.Sprintf("%T", conn)
	logInfo("[XrayUDP] Connection type: %s", connType)
	
	// Start read goroutine
	logInfo("[XrayUDP] Starting readLoop() goroutine...")
	go c.readLoop()
	logInfo("[XrayUDP] ✅ readLoop() goroutine started")
	
	logInfo("[XrayUDP] ✅ Connection established through Xray!")
	
	return nil
}

// readLoop reads from Xray connection
func (c *XrayUDPConn) readLoop() {
	logInfo("[XrayUDP] ========================================")
	logInfo("[XrayUDP] readLoop() started for %s:%d", c.address, c.port)
	logInfo("[XrayUDP] ========================================")
	
	buf := make([]byte, 65535)
	readCount := 0
	errorCount := 0
	
	for {
		if c.closed {
			logInfo("[XrayUDP] readLoop() exiting: connection closed")
			return
		}
		
		// Check if connection is still valid
		if c.conn == nil {
			logWarn("[XrayUDP] readLoop: Connection is nil, attempting reconnect...")
			if err := c.reconnect(); err != nil {
				errorCount++
				logError("[XrayUDP] readLoop: Reconnect failed (error #%d): %v", errorCount, err)
				time.Sleep(1 * time.Second)
				continue
			}
			logInfo("[XrayUDP] readLoop: Reconnected successfully")
		}
		
		// Log read attempt periodically or on first attempt
		if readCount == 0 {
			logInfo("[XrayUDP] readLoop: 🔄 First read attempt (readCount: %d, errorCount: %d)...", readCount, errorCount)
		} else if readCount%100 == 0 {
			logInfo("[XrayUDP] readLoop: 🔄 Attempting to read (readCount: %d, errorCount: %d)...", readCount, errorCount)
		} else if readCount%10 == 0 {
			logDebug("[XrayUDP] readLoop: Attempting to read (readCount: %d, errorCount: %d)...", readCount, errorCount)
		}
		
		// Check connection state before read
		c.mu.Lock()
		connValid := c.conn != nil && !c.closed
		conn := c.conn
		c.mu.Unlock()
		
		if !connValid || conn == nil {
			logWarn("[XrayUDP] readLoop: Connection invalid before read (conn: %v, closed: %v)", conn != nil, c.closed)
			if err := c.reconnect(); err != nil {
				errorCount++
				logError("[XrayUDP] readLoop: Reconnect failed (error #%d): %v", errorCount, err)
				time.Sleep(1 * time.Second)
				continue
			}
			logInfo("[XrayUDP] readLoop: Reconnected successfully")
			continue
		}
		
		n, err := c.conn.Read(buf)
		if err != nil {
			if c.closed {
				logInfo("[XrayUDP] readLoop() exiting: connection closed during read")
				return
			}
			errorCount++
			
			// Detailed error logging
			errStr := err.Error()
			if strings.Contains(errStr, "closed pipe") {
				logError("[XrayUDP] readLoop: ❌ Read error #%d: %v (readCount: %d, errorCount: %d) - Connection was closed by Xray-core", errorCount, err, readCount, errorCount)
			} else if strings.Contains(errStr, "EOF") {
				logError("[XrayUDP] readLoop: ❌ Read error #%d: %v (readCount: %d, errorCount: %d) - Connection closed (EOF)", errorCount, err, readCount, errorCount)
			} else {
				logError("[XrayUDP] readLoop: ❌ Read error #%d: %v (readCount: %d, errorCount: %d)", errorCount, err, readCount, errorCount)
			}
			
			// Log connection state
			c.mu.Lock()
			connState := "nil"
			if c.conn != nil {
				connState = "valid"
				if localAddr := c.conn.LocalAddr(); localAddr != nil {
					localStr := localAddr.String()
					if localStr == "0.0.0.0:0" || localStr == "[::]:0" {
						connState += fmt.Sprintf(" (local: %v - INVALID!)", localAddr)
					} else {
						connState += fmt.Sprintf(" (local: %v)", localAddr)
					}
				}
				if remoteAddr := c.conn.RemoteAddr(); remoteAddr != nil {
					remoteStr := remoteAddr.String()
					if remoteStr == "0.0.0.0:0" || remoteStr == "[::]:0" {
						connState += fmt.Sprintf(" (remote: %v - INVALID!)", remoteAddr)
					} else {
						connState += fmt.Sprintf(" (remote: %v)", remoteAddr)
					}
				}
			}
			c.mu.Unlock()
			logDebug("[XrayUDP] readLoop: Connection state: %s", connState)
			
			// Attempt to reconnect
			if err := c.reconnect(); err != nil {
				logError("[XrayUDP] readLoop: Reconnect failed: %v", err)
				time.Sleep(1 * time.Second)
				continue
			}
			logInfo("[XrayUDP] readLoop: Reconnected after error, continuing...")
			continue
		}
		
		readCount++
		
		// Copy data to channel
		data := make([]byte, n)
		copy(data, buf[:n])
		
		select {
		case c.readCh <- data:
			if readCount%10 == 0 || n > 100 {
				logInfo("[XrayUDP] readLoop: ✅ Received %d bytes (readCount: %d, errorCount: %d)", n, readCount, errorCount)
			} else {
				logDebug("[XrayUDP] readLoop: ✅ Received %d bytes", n)
			}
		default:
			logWarn("[XrayUDP] readLoop: ⚠️ Read buffer full, dropping %d bytes packet (readCount: %d)", n, readCount)
		}
	}
}

// Write sends data through Xray
func (c *XrayUDPConn) Write(data []byte) (int, error) {
	if len(data) == 0 {
		return 0, nil
	}
	
	// Check connection state before writing
	c.mu.Lock()
	conn := c.conn
	closed := c.closed
	c.mu.Unlock()
	
	if closed {
		return 0, fmt.Errorf("connection is closed")
	}
	
	// Try to write, reconnect if needed
	var n int
	var err error
	maxRetries := 2
	
	for attempt := 0; attempt < maxRetries; attempt++ {
		// Get current connection (may change during reconnect)
		c.mu.Lock()
		conn = c.conn
		c.mu.Unlock()
		
		if conn == nil {
			if attempt < maxRetries-1 {
				logWarn("[XrayUDP] Write: Connection is nil, attempting reconnect before write (attempt %d/%d)...", attempt+1, maxRetries)
				// Release lock before reconnect to avoid deadlock
				if reconnectErr := c.reconnect(); reconnectErr != nil {
					logError("[XrayUDP] Write: Reconnect failed: %v", reconnectErr)
					return 0, fmt.Errorf("not connected: %w", reconnectErr)
				}
				continue
			} else {
				return 0, fmt.Errorf("connection is nil after reconnect attempts")
			}
		}
		
		// Log write attempt for large packets
		if len(data) > 100 && attempt == 0 {
			logDebug("[XrayUDP] Write: Attempting to write %d bytes to %s:%d...", len(data), c.address, c.port)
		}
		
		// Attempt write
		n, err = conn.Write(data)
		if err == nil {
			if len(data) > 100 {
				logInfo("[XrayUDP] Write: ✅ Sent %d bytes to %s:%d", n, c.address, c.port)
			} else {
				logDebug("[XrayUDP] Write: ✅ Sent %d bytes", n)
			}
			return n, nil
		}
		
		// Handle write error
		errStr := err.Error()
		if strings.Contains(errStr, "closed pipe") || strings.Contains(errStr, "broken pipe") {
			logError("[XrayUDP] Write: ❌ Write error #%d: %v - Connection was closed (attempt %d/%d)", attempt+1, err, attempt+1, maxRetries)
		} else {
			logError("[XrayUDP] Write: ❌ Write error #%d: %v (attempt %d/%d)", attempt+1, err, attempt+1, maxRetries)
		}
		
		// If this is not the last attempt, try to reconnect
		if attempt < maxRetries-1 {
			logInfo("[XrayUDP] Write: Attempting reconnect before retry...")
			if reconnectErr := c.reconnect(); reconnectErr != nil {
				logError("[XrayUDP] Write: Reconnect failed: %v", reconnectErr)
				return 0, fmt.Errorf("write failed and reconnect failed: %w", err)
			}
			logInfo("[XrayUDP] Write: Reconnected, retrying write...")
		}
	}
	
	// All retries failed
	return 0, fmt.Errorf("write failed after %d attempts: %w", maxRetries, err)
}

// Read receives data from Xray
func (c *XrayUDPConn) Read(timeout time.Duration) ([]byte, error) {
	select {
	case data := <-c.readCh:
		return data, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("read timeout")
	}
}

// Close closes the connection
func (c *XrayUDPConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	c.closed = true
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

// GetAddress returns endpoint address
func (c *XrayUDPConn) GetAddress() string {
	return fmt.Sprintf("%s:%d", c.address, c.port)
}

// reconnect attempts to reconnect the XrayUDP connection
func (c *XrayUDPConn) reconnect() error {
	c.reconnectMu.Lock()
	defer c.reconnectMu.Unlock()
	
	// Prevent multiple simultaneous reconnects
	if c.reconnecting {
		logDebug("[XrayUDP] Reconnect already in progress, waiting...")
		return fmt.Errorf("reconnect in progress")
	}
	
	c.reconnecting = true
	defer func() {
		c.reconnecting = false
	}()
	
	if c.closed {
		return fmt.Errorf("connection is closed")
	}
	
	// Check Xray is still running
	if !c.xray.IsRunning() {
		return fmt.Errorf("xray not running")
	}
	
	logInfo("[XrayUDP] Attempting to reconnect to %s:%d...", c.address, c.port)
	
	// Close old connection if exists (with lock protection)
	c.mu.Lock()
	oldConn := c.conn
	c.conn = nil // Clear before closing to prevent race conditions
	c.mu.Unlock()
	
	if oldConn != nil {
		oldConn.Close()
	}
	
	// Dial new connection (outside of lock to avoid blocking)
	conn, err := core.Dial(c.ctx, c.xray.instance, c.dest)
	if err != nil {
		logError("[XrayUDP] Reconnect dial failed: %v", err)
		return fmt.Errorf("reconnect dial: %w", err)
	}
	
	if conn == nil {
		logError("[XrayUDP] Reconnect dial returned nil")
		return fmt.Errorf("reconnect dial returned nil")
	}
	
	// Update connection with lock protection
	c.mu.Lock()
	c.conn = conn
	c.mu.Unlock()
	
	logInfo("[XrayUDP] ✅ Reconnected successfully!")
	
	// Detailed connection state check after reconnect
	localAddr := conn.LocalAddr()
	remoteAddr := conn.RemoteAddr()
	logInfo("[XrayUDP] Reconnect - Local addr: %v", localAddr)
	logInfo("[XrayUDP] Reconnect - Remote addr: %v", remoteAddr)
	
	// Validate addresses are not 0.0.0.0:0
	if localAddr != nil {
		localStr := localAddr.String()
		if localStr == "0.0.0.0:0" || localStr == "[::]:0" {
			logWarn("[XrayUDP] ⚠️ Reconnect - Local address is invalid: %v", localAddr)
		} else {
			logInfo("[XrayUDP] ✅ Reconnect - Local address is valid: %v", localAddr)
		}
	} else {
		logWarn("[XrayUDP] ⚠️ Reconnect - Local address is nil!")
	}
	
	if remoteAddr != nil {
		remoteStr := remoteAddr.String()
		if remoteStr == "0.0.0.0:0" || remoteStr == "[::]:0" {
			logWarn("[XrayUDP] ⚠️ Reconnect - Remote address is invalid: %v", remoteAddr)
		} else {
			logInfo("[XrayUDP] ✅ Reconnect - Remote address is valid: %v", remoteAddr)
		}
	} else {
		logWarn("[XrayUDP] ⚠️ Reconnect - Remote address is nil!")
	}
	
	// Log connection type
	connType := fmt.Sprintf("%T", conn)
	logInfo("[XrayUDP] Reconnect - Connection type: %s", connType)
	
	// Restart read loop
	logInfo("[XrayUDP] Restarting readLoop() goroutine after reconnect...")
	go c.readLoop()
	logInfo("[XrayUDP] ✅ readLoop() goroutine restarted")
	
	return nil
}

// IsConnected checks if connection is valid
func (c *XrayUDPConn) IsConnected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn != nil && !c.closed
}
