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
	instance  *core.Instance
	handler   outbound.Manager
	ctx       context.Context
	cancel    context.CancelFunc
	running   bool
	mu        sync.Mutex
	grpcClient *XrayGrpcClient
	apiPort   int
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

// parseApiPortFromConfig extracts API port from Xray config JSON
// Returns 0 if API port is not found (not an error, just means gRPC is not configured)
func parseApiPortFromConfig(configJSON string) (int, error) {
	var config map[string]interface{}
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return 0, fmt.Errorf("failed to parse config: %w", err)
	}

	// Check if "api" section exists
	apiSection, ok := config["api"].(map[string]interface{})
	if !ok {
		logDebug("[Xray] No API section found in config (gRPC not configured)")
		return 0, nil // Not an error, just means gRPC is not configured
	}

	// Get "listen" field
	listen, ok := apiSection["listen"].(string)
	if !ok {
		logDebug("[Xray] No listen field in API section")
		return 0, nil
	}

	// Parse "127.0.0.1:PORT" format
	// Extract port number after the colon
	parts := strings.Split(listen, ":")
	if len(parts) != 2 {
		logWarn("[Xray] Invalid listen format: %s (expected 127.0.0.1:PORT)", listen)
		return 0, nil
	}

	var port int
	if _, err := fmt.Sscanf(parts[1], "%d", &port); err != nil {
		logWarn("[Xray] Failed to parse port from listen: %s, error: %v", listen, err)
		return 0, nil
	}

	if port <= 0 || port > 65535 {
		logWarn("[Xray] Invalid port number: %d", port)
		return 0, nil
	}

	logInfo("[Xray] ✅ Parsed API port from config: %d", port)
	return port, nil
}

// NewXrayWrapper creates Xray instance from JSON config
func NewXrayWrapper(configJSON string) (*XrayWrapper, error) {
	logInfo("[Xray] ========================================")
	logInfo("[Xray] Creating Xray-core instance...")
	logInfo("[Xray] ========================================")
	logDebug("[Xray] Config length: %d bytes", len(configJSON))
	
	// ===== PRE-FLIGHT VALIDATION =====
	// Step 1: Validate config is not empty
	if len(configJSON) == 0 {
		logError("[Xray] ❌ Config is empty!")
		return nil, fmt.Errorf("config is empty")
	}
	
	// Step 2: Validate config is valid JSON
	var testConfig map[string]interface{}
	if err := json.Unmarshal([]byte(configJSON), &testConfig); err != nil {
		logError("[Xray] ❌ Config is not valid JSON: %v", err)
		return nil, fmt.Errorf("invalid JSON config: %w", err)
	}
	
	// Step 3: Validate config has required fields
	outbounds, hasOutbounds := testConfig["outbounds"].([]interface{})
	outbound, hasOutbound := testConfig["outbound"].([]interface{})
	if !hasOutbounds && !hasOutbound {
		logError("[Xray] ❌ Config missing 'outbounds' or 'outbound' field!")
		return nil, fmt.Errorf("config missing required 'outbounds' or 'outbound' field")
	}
	
	if hasOutbounds && len(outbounds) == 0 {
		logError("[Xray] ❌ Config has empty 'outbounds' array!")
		return nil, fmt.Errorf("config has empty 'outbounds' array")
	}
	if hasOutbound && len(outbound) == 0 {
		logError("[Xray] ❌ Config has empty 'outbound' array!")
		return nil, fmt.Errorf("config has empty 'outbound' array")
	}
	
	logInfo("[Xray] ✅ Config validation passed")
	
	// Enable Xray internal logging FIRST
	logInfo("[Xray] Enabling Xray internal debug logging...")
	log.RegisterHandler(&XrayLogWriter{})
	
	// Modify config to enable debug logging if not present
	configJSON = ensureDebugLogging(configJSON)
	
	// Parse API port from config (for gRPC client)
	apiPort, err := parseApiPortFromConfig(configJSON)
	if err != nil {
		logWarn("[Xray] Failed to parse API port: %v (continuing without gRPC)", err)
		apiPort = 0
	}
	
	// Parse JSON config
	jsonConfig := &conf.Config{}
	if err := json.Unmarshal([]byte(configJSON), jsonConfig); err != nil {
		logError("[Xray] Failed to parse JSON: %v", err)
		return nil, fmt.Errorf("parse json: %w", err)
	}
	
	logInfo("[Xray] JSON parsed: %d inbounds, %d outbounds", 
		len(jsonConfig.InboundConfigs), len(jsonConfig.OutboundConfigs))
	
	// Log outbound details with full parsing info
	logInfo("[Xray] Found %d outbound(s):", len(jsonConfig.OutboundConfigs))
	for i, ob := range jsonConfig.OutboundConfigs {
		logInfo("[Xray]   Outbound[%d]: protocol=%s, tag=%s", i, ob.Protocol, ob.Tag)
		
		// Parse and log VLESS/VMess specific settings
		if ob.Protocol == "vless" || ob.Protocol == "vmess" {
			// Try to extract server address and port from settings
			if ob.Settings != nil {
				// Settings is typically a JSON object, try to parse it
				if settingsBytes, err := json.Marshal(ob.Settings); err == nil {
					var settingsMap map[string]interface{}
					if err := json.Unmarshal(settingsBytes, &settingsMap); err == nil {
						// Check for vnext (VLESS) or servers (VMess)
						if vnext, ok := settingsMap["vnext"].([]interface{}); ok && len(vnext) > 0 {
							if server, ok := vnext[0].(map[string]interface{}); ok {
								address := ""
								port := 0
								if addr, ok := server["address"].(string); ok {
									address = addr
								}
								if p, ok := server["port"].(float64); ok {
									port = int(p)
								}
								logInfo("[Xray]     Server: %s:%d", address, port)
							}
						}
						if servers, ok := settingsMap["servers"].([]interface{}); ok && len(servers) > 0 {
							if server, ok := servers[0].(map[string]interface{}); ok {
								address := ""
								port := 0
								if addr, ok := server["address"].(string); ok {
									address = addr
								}
								if p, ok := server["port"].(float64); ok {
									port = int(p)
								}
								logInfo("[Xray]     Server: %s:%d", address, port)
							}
						}
					}
				}
				logDebug("[Xray]     Settings: %+v", ob.Settings)
			}
		}
		
		// Log stream settings (TLS, REALITY, etc.) from original JSON
		// Parse original config JSON to extract streamSettings
		var originalConfig map[string]interface{}
		if err := json.Unmarshal([]byte(configJSON), &originalConfig); err == nil {
			if outbounds, ok := originalConfig["outbounds"].([]interface{}); ok && i < len(outbounds) {
				if outbound, ok := outbounds[i].(map[string]interface{}); ok {
					if streamSettings, ok := outbound["streamSettings"].(map[string]interface{}); ok {
						network := ""
						security := ""
						if n, ok := streamSettings["network"].(string); ok {
							network = n
						}
						if s, ok := streamSettings["security"].(string); ok {
							security = s
						}
						logInfo("[Xray]     Stream: network=%s, security=%s", network, security)
						
						// Log REALITY settings if present
						if reality, ok := streamSettings["realitySettings"].(map[string]interface{}); ok {
							if serverName, ok := reality["serverName"].(string); ok {
								logInfo("[Xray]     REALITY serverName: %s", serverName)
							}
							if publicKey, ok := reality["publicKey"].(string); ok {
								pkLen := len(publicKey)
								if pkLen > 20 {
									pkLen = 20
								}
								logInfo("[Xray]     REALITY publicKey: %s...", publicKey[:pkLen])
							}
							if shortId, ok := reality["shortId"].(string); ok {
								logInfo("[Xray]     REALITY shortId: %s", shortId)
							}
						}
						
						// Log TLS settings if present
						if tls, ok := streamSettings["tlsSettings"].(map[string]interface{}); ok {
							if serverName, ok := tls["serverName"].(string); ok {
								logInfo("[Xray]     TLS serverName: %s", serverName)
							}
							if fingerprint, ok := tls["fingerprint"].(string); ok {
								logInfo("[Xray]     TLS fingerprint: %s", fingerprint)
							}
						}
					}
				}
			}
		}
	}
	
	// Validate at least one outbound exists
	if len(jsonConfig.OutboundConfigs) == 0 {
		logError("[Xray] ❌ No outbounds found in config!")
		return nil, fmt.Errorf("no outbounds in config")
	}
	
	// CRITICAL: Register protected dialer BEFORE building config
	// This ensures ALL Xray sockets are protected from VPN routing loop
	logInfo("[Xray] Registering protected dialer for socket protection...")
	if err := initXrayProtectedDialer(); err != nil {
		logError("[Xray] ❌ Failed to register protected dialer: %v", err)
		logError("[Xray] ❌ Socket protection loop will occur - sockets will be routed back to VPN!")
		// Don't fail - try to continue, but socket protection won't work
		logWarn("[Xray] ⚠️ Continuing without socket protection (this may cause issues)")
	} else {
		logInfo("[Xray] ✅ Protected dialer registered successfully")
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
		instance:   instance,
		ctx:        ctx,
		cancel:     cancel,
		apiPort:    apiPort,
		grpcClient: nil, // Will be created in Start() if apiPort > 0
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
	
	// ===== PRE-FLIGHT CHECKS =====
	// Check instance exists
	if x.instance == nil {
		logError("[Xray] ❌ Instance is nil! Cannot start.")
		return fmt.Errorf("xray instance is nil")
	}
	
	logDebug("[Xray] Instance exists, calling Start()...")
	
	// Actually start Xray
	// All logs from Xray will be captured via XrayLogWriter and forwarded to Android Logcat
	err := x.instance.Start()
	if err != nil {
		logError("[Xray] ❌ Start() FAILED: %v", err)
		logError("[Xray] ❌ This error should be visible in Android Logcat")
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
	
	// Initialize gRPC client if API port is configured
	if x.apiPort > 0 {
		logInfo("[Xray] Initializing gRPC client for API port %d...", x.apiPort)
		grpcClient, err := NewXrayGrpcClient(x.apiPort)
		if err != nil {
			// gRPC client is optional - log warning but don't fail
			logWarn("[Xray] ⚠️ Failed to create gRPC client: %v (continuing without gRPC stats)", err)
			logWarn("[Xray] ⚠️ gRPC stats will not be available, but Xray-core will continue running")
		} else {
			x.grpcClient = grpcClient
			logInfo("[Xray] ✅ gRPC client initialized successfully")
		}
	} else {
		logDebug("[Xray] No API port configured, skipping gRPC client initialization")
	}
	
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
	
	// Close gRPC client if it exists
	if x.grpcClient != nil {
		logInfo("[Xray] Closing gRPC client...")
		if err := x.grpcClient.Close(); err != nil {
			logWarn("[Xray] Error closing gRPC client: %v", err)
		} else {
			logInfo("[Xray] ✅ gRPC client closed")
		}
		x.grpcClient = nil
	}
	
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

// GetGrpcClient returns the gRPC client if available
func (x *XrayWrapper) GetGrpcClient() *XrayGrpcClient {
	x.mu.Lock()
	defer x.mu.Unlock()
	return x.grpcClient
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
		c.mu.Lock()
		closed := c.closed
		c.mu.Unlock()
		
		if closed {
			logInfo("[XrayUDP] readLoop() exiting: connection closed")
			return
		}
		
		// Check if connection is still valid
		c.mu.Lock()
		conn := c.conn
		c.mu.Unlock()
		
		if conn == nil {
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
		
		// Check connection state before read (conn already defined above)
		c.mu.Lock()
		connValid := c.conn != nil && !c.closed
		conn = c.conn
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
		
		// Read with timeout to prevent blocking forever
		readDeadline := time.Now().Add(30 * time.Second)
		if err := conn.SetReadDeadline(readDeadline); err != nil {
			logWarn("[XrayUDP] readLoop: Failed to set read deadline: %v", err)
		}
		
		n, err := conn.Read(buf)
		if err != nil {
			// Check if closed flag was set (not just connection error)
			c.mu.Lock()
			wasClosed := c.closed
			c.mu.Unlock()
			
			if wasClosed {
				logInfo("[XrayUDP] readLoop() exiting: connection closed during read (closed flag set)")
				return
			}
			
			// Check if it's a timeout (expected for UDP)
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				// Timeout is expected for UDP - continue reading
				if readCount == 0 || readCount%100 == 0 {
					logDebug("[XrayUDP] readLoop: Read timeout (expected for UDP, readCount: %d), continuing...", readCount)
				}
				continue
			}
			
			errorCount++
			
			// Detailed error logging
			errStr := err.Error()
			errType := "unknown"
			if strings.Contains(errStr, "closed pipe") {
				errType = "closed pipe"
			} else if strings.Contains(errStr, "EOF") {
				errType = "EOF"
			} else if strings.Contains(errStr, "connection reset") {
				errType = "connection reset"
			} else if strings.Contains(errStr, "broken pipe") {
				errType = "broken pipe"
			}
			
			logError("[XrayUDP] readLoop: ❌ Read error #%d (type: %s): %v (readCount: %d, errorCount: %d)", errorCount, errType, err, readCount, errorCount)
			
			// For EOF and closed pipe, Xray-core may have closed the connection
			// This is normal for UDP over TCP - connection may close after each packet
			// Try to reconnect and continue
			if errType == "EOF" || errType == "closed pipe" || errType == "connection reset" {
				logWarn("[XrayUDP] readLoop: Connection closed by Xray-core (type: %s), attempting reconnect...", errType)
				if reconnectErr := c.reconnect(); reconnectErr != nil {
					logError("[XrayUDP] readLoop: Reconnect failed: %v", reconnectErr)
					time.Sleep(1 * time.Second)
					continue
				}
				logInfo("[XrayUDP] readLoop: Reconnected successfully, continuing...")
				continue
			}
			
		}
		
		readCount++
		
		// ===== PACKET INSPECTOR =====
		// Log first 4 bytes in HEX format to identify packet type
		// WireGuard Handshake Response: 0x02
		// WireGuard Data packet: 0x04
		// If we see random garbage or ASCII (like "HTTP"), routing is wrong
		var headerHex string
		if n >= 4 {
			headerHex = fmt.Sprintf("[0x%02x, 0x%02x, 0x%02x, 0x%02x]", buf[0], buf[1], buf[2], buf[3])
		} else {
			headerHex = fmt.Sprintf("[%d bytes only]", n)
		}
		
		// Log packet inspector info for every packet (especially important for 1532 byte packets)
		if n >= 1532 || readCount <= 10 || readCount%50 == 0 {
			logInfo("[PacketInspector] RX Len: %d, Header: %s (readCount: %d)", n, headerHex, readCount)
		} else {
			logDebug("[PacketInspector] RX Len: %d, Header: %s", n, headerHex)
		}
		
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
// For UDP over TCP (VLESS), we may need to open a new connection for each packet
// or keep the connection alive. Xray-core handles this internally.
func (c *XrayUDPConn) Write(data []byte) (int, error) {
	if len(data) == 0 {
		return 0, nil
	}
	
	// Check if closed
	c.mu.Lock()
	closed := c.closed
	c.mu.Unlock()
	
	if closed {
		return 0, fmt.Errorf("connection is closed")
	}
	
	// Get or create connection
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	
	// If connection is nil or invalid, reconnect
	if conn == nil {
		logDebug("[XrayUDP] Write: Connection is nil, reconnecting before write...")
		if err := c.reconnect(); err != nil {
			logError("[XrayUDP] Write: Reconnect failed: %v", err)
			return 0, fmt.Errorf("not connected: %w", err)
		}
		c.mu.Lock()
		conn = c.conn
		c.mu.Unlock()
		if conn == nil {
			return 0, fmt.Errorf("connection is nil after reconnect")
		}
	}
	
	// Log write attempt for large packets
	if len(data) > 100 {
		logDebug("[XrayUDP] Write: Attempting to write %d bytes to %s:%d...", len(data), c.address, c.port)
	}
	
	// Attempt write
	n, err := conn.Write(data)
	if err != nil {
		// If write fails, try to reconnect and retry once
		logWarn("[XrayUDP] Write: Write failed: %v, attempting reconnect...", err)
		if reconnectErr := c.reconnect(); reconnectErr != nil {
			logError("[XrayUDP] Write: Reconnect failed: %v", reconnectErr)
			return 0, fmt.Errorf("write failed and reconnect failed: %w", err)
		}
		
		// Retry write after reconnect
		c.mu.Lock()
		conn = c.conn
		c.mu.Unlock()
		if conn == nil {
			return 0, fmt.Errorf("connection is nil after reconnect")
		}
		
		n, err = conn.Write(data)
		if err != nil {
			logError("[XrayUDP] Write: ❌ Write failed after reconnect: %v", err)
			return 0, fmt.Errorf("write failed: %w", err)
		}
	}
	
	if len(data) > 100 {
		logInfo("[XrayUDP] Write: ✅ Sent %d bytes to %s:%d", n, c.address, c.port)
	} else {
		logDebug("[XrayUDP] Write: ✅ Sent %d bytes", n)
	}
	
	return n, nil
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

// IsConnected checks if connection is valid and connected
func (c *XrayUDPConn) IsConnected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn != nil && !c.closed
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
	// Note: readLoop() should already be running in a separate goroutine
	// It will detect the new connection and continue reading
	logInfo("[XrayUDP] ✅ Reconnect complete, readLoop() should continue with new connection")
	
	return nil
}

