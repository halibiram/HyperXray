package bridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/common/log"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/infra/conf"

	// Import all Xray features
	_ "github.com/xtls/xray-core/main/distro/all"
)

// ============================================================================
// BUFFER POOL FOR MEMORY OPTIMIZATION
// ============================================================================
// Using sync.Pool to reduce GC pressure from frequent packet allocations.
// The readLoop allocates a new []byte for every incoming packet, which causes
// massive GC churn under high traffic. This pool reuses buffers efficiently.
// ============================================================================

const (
	// ReadBufferSize is the size for read buffers (max UDP packet size).
	// 65535 is sufficient for WireGuard (MTU ~1420) and standard UDP.
	ReadBufferSize = 65535
	// PacketBufferSize is the size for packet data buffers.
	// 2048 is sufficient for typical WireGuard packets.
	PacketBufferSize = 2048
)

// readBufferPool provides reusable large buffers for reading from connections.
// These are 64KB buffers used in readLoop for conn.Read().
var readBufferPool = sync.Pool{
	New: func() interface{} {
		buf := make([]byte, ReadBufferSize)
		return &buf
	},
}

// packetBufferPool provides reusable buffers for packet data.
// These are smaller buffers (2KB) used for copying data to channels.
var packetBufferPool = sync.Pool{
	New: func() interface{} {
		buf := make([]byte, PacketBufferSize)
		return &buf
	},
}

// getReadBuffer retrieves a large read buffer from the pool.
func getReadBuffer() *[]byte {
	return readBufferPool.Get().(*[]byte)
}

// putReadBuffer returns a large read buffer to the pool.
func putReadBuffer(buf *[]byte) {
	if buf == nil {
		return
	}
	readBufferPool.Put(buf)
}

// getPacketBuffer retrieves a packet buffer from the pool.
func getPacketBuffer() *[]byte {
	return packetBufferPool.Get().(*[]byte)
}

// putPacketBuffer returns a packet buffer to the pool.
// SAFETY: Caller must ensure the buffer is no longer referenced.
func putPacketBuffer(buf *[]byte) {
	if buf == nil {
		return
	}
	// Reset to full capacity for next use
	*buf = (*buf)[:cap(*buf)]
	packetBufferPool.Put(buf)
}

// Global log channel for Xray logs (buffered, non-blocking)
// Exported for access from native/lib.go
var (
	XrayLogChannel = make(chan string, 2000) // Buffer 2000 log entries (doubled)
	XrayLogChannelMu sync.Mutex
	XrayLogChannelClosed = false
	xrayLogWriterCallCount int // Track how many times handler is called
	xrayLogWriterCallCountMu sync.Mutex
	xrayLogWriterInstance *XrayLogWriter // Global instance
)

// init registers XrayLogWriter globally when package is loaded
func init() {
	// Initialize log channel if not already initialized
	XrayLogChannelMu.Lock()
	if XrayLogChannel == nil {
		XrayLogChannel = make(chan string, 2000)
		logInfo("[XrayLogWriter] Created XrayLogChannel (buffer: 2000)")
	}
	XrayLogChannelClosed = false
	XrayLogChannelMu.Unlock()
	
	// Register log handler
	xrayLogWriterInstance = &XrayLogWriter{}
	log.RegisterHandler(xrayLogWriterInstance)
	logInfo("[XrayLogWriter] ✅ Registered globally in package init")
	logInfo("[XrayLogWriter] Log channel initialized and ready (buffer: %d)", 1000)
}

// XrayLogWriter implements log.Handler to capture Xray logs
type XrayLogWriter struct{}

func (w *XrayLogWriter) Handle(msg log.Message) {
	// Format log message - msg.String() already includes level info
	logLine := msg.String()
	
	// Track call count to verify handler is being called
	xrayLogWriterCallCountMu.Lock()
	xrayLogWriterCallCount++
	callCount := xrayLogWriterCallCount
	xrayLogWriterCallCountMu.Unlock()
	
	// Always log first 10 messages to verify handler is being called
	if callCount <= 10 {
		logInfo("[XrayLogWriter] Handler called (count: %d): %s", callCount, logLine)
	}
	
	// Send to channel for Android retrieval (non-blocking)
	XrayLogChannelMu.Lock()
	if !XrayLogChannelClosed {
		select {
		case XrayLogChannel <- logLine:
			// Successfully sent to channel
			// Log first few messages to verify it's working
			if callCount <= 10 {
				logDebug("[XrayLogWriter] Sent log to channel (queue size: %d): %s", len(XrayLogChannel), logLine)
			}
		default:
			// Channel full, drop oldest entry to make room
			select {
			case <-XrayLogChannel:
				// Removed oldest entry
				select {
				case XrayLogChannel <- logLine:
					// Successfully sent after making room
				default:
					// Still full, drop this entry (silently)
				}
			default:
				// Channel empty but still couldn't send (shouldn't happen)
			}
		}
	}
	XrayLogChannelMu.Unlock()
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
	udpConns  map[*XrayUDPConn]struct{} // Track active UDP connections
	connsMu   sync.Mutex
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
// 
// Xray-core gRPC API uses dokodemo-door inbound with tag "api-inbound" or "api_inbound"
// The port is extracted from this inbound's "port" field
func parseApiPortFromConfig(configJSON string) (int, error) {
	var config map[string]interface{}
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return 0, fmt.Errorf("failed to parse config: %w", err)
	}

	// Check if "api" section exists (required for gRPC to work)
	_, hasApi := config["api"].(map[string]interface{})
	if !hasApi {
		logDebug("[Xray] No API section found in config (gRPC not configured)")
		return 0, nil // Not an error, just means gRPC is not configured
	}

	// Look for api-inbound in inbounds array
	// This is the dokodemo-door inbound that receives gRPC requests
	inbounds, ok := config["inbounds"].([]interface{})
	if !ok {
		logDebug("[Xray] No inbounds array found in config")
		return 0, nil
	}

	for _, inbound := range inbounds {
		inboundMap, ok := inbound.(map[string]interface{})
		if !ok {
			continue
		}

		tag, _ := inboundMap["tag"].(string)
		if tag == "api-inbound" || tag == "api_inbound" {
			// Found the API inbound, extract port
			port, ok := inboundMap["port"].(float64)
			if !ok {
				logWarn("[Xray] api-inbound found but no valid port field")
				return 0, nil
			}

			portInt := int(port)
			if portInt <= 0 || portInt > 65535 {
				logWarn("[Xray] Invalid API port number: %d", portInt)
				return 0, nil
			}

			logInfo("[Xray] ✅ Parsed API port from api-inbound: %d", portInt)
			return portInt, nil
		}
	}

	logDebug("[Xray] No api-inbound found in inbounds array")
	return 0, nil
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
	
	// XrayLogWriter is already registered globally in package init()
	// Just verify it's registered
	logInfo("[Xray] XrayLogWriter should already be registered (checking...)")
	
	// Modify config to enable debug logging if not present
	configJSON = ensureDebugLogging(configJSON)
	logInfo("[Xray] ✅ Config updated with debug logging (loglevel: debug)")
	
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
	
	// Re-register log handler after instance creation to ensure it captures all logs
	// (Xray may reset log handlers during instance creation)
	if xrayLogWriterInstance != nil {
		log.RegisterHandler(xrayLogWriterInstance)
		logInfo("[Xray] ✅ XrayLogWriter re-registered after instance creation")
		
		// Verify log channel is ready
		XrayLogChannelMu.Lock()
		channelReady := XrayLogChannel != nil && !XrayLogChannelClosed
		channelCap := 0
		if XrayLogChannel != nil {
			channelCap = cap(XrayLogChannel)
		}
		XrayLogChannelMu.Unlock()
		
		if channelReady {
			logInfo("[Xray] ✅ Log channel is ready (capacity: %d)", channelCap)
		} else {
			logError("[Xray] ❌ Log channel is not ready - logs may not be captured")
			logError("[Xray] ❌ Channel state: nil=%v, closed=%v", XrayLogChannel == nil, XrayLogChannelClosed)
		}
	} else {
		logError("[Xray] ❌ XrayLogWriter instance is nil - logs will not be captured!")
		logError("[Xray] ❌ This indicates XrayLogWriter was not initialized in package init()")
	}
	
	ctx, cancel := context.WithCancel(context.Background())
	
	return &XrayWrapper{
		instance:   instance,
		ctx:        ctx,
		cancel:     cancel,
		apiPort:    apiPort,
		grpcClient: nil, // Will be created in Start() if apiPort > 0
		udpConns:   make(map[*XrayUDPConn]struct{}),
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
		
		// Wait a bit for Xray's gRPC service to be ready
		// Xray needs time to start the gRPC server after instance.Start()
		// Reduced from 1 second to 500ms to speed up startup
		logDebug("[Xray] Waiting 500ms for gRPC service to be ready...")
		time.Sleep(500 * time.Millisecond)
		
		// Retry gRPC client creation with exponential backoff
		// This handles cases where Xray's gRPC service takes longer to start
		var grpcClient *XrayGrpcClient
		var err error
		maxRetries := 3
		retryDelay := 500 * time.Millisecond
		
		for attempt := 1; attempt <= maxRetries; attempt++ {
			logDebug("[Xray] Attempting to create gRPC client (attempt %d/%d)...", attempt, maxRetries)
			grpcClient, err = NewXrayGrpcClient(x.apiPort)
			if err == nil {
				logInfo("[Xray] ✅ gRPC client created successfully on attempt %d", attempt)
				break
			}
			
			if attempt < maxRetries {
				logWarn("[Xray] ⚠️ gRPC client creation failed (attempt %d/%d): %v", attempt, maxRetries, err)
				logWarn("[Xray] ⚠️ Retrying in %v...", retryDelay)
				time.Sleep(retryDelay)
				retryDelay *= 2 // Exponential backoff
			} else {
				logError("[Xray] ❌ Failed to create gRPC client after %d attempts: %v", maxRetries, err)
			}
		}
		
		if err != nil {
			// gRPC client is optional - log warning but don't fail
			logWarn("[Xray] ⚠️ Failed to create gRPC client after all retries: %v (continuing without gRPC stats)", err)
			logWarn("[Xray] ⚠️ gRPC stats will not be available, but Xray-core will continue running")
			logWarn("[Xray] ⚠️ Possible causes:")
			logWarn("[Xray] ⚠️   1. Xray gRPC service not started yet (timing issue)")
			logWarn("[Xray] ⚠️   2. Port %d is not listening (check Xray config)", x.apiPort)
			logWarn("[Xray] ⚠️   3. gRPC service crashed or failed to start")
			x.grpcClient = nil // Ensure it's nil on failure
		} else {
			// CRITICAL: Store gRPC client with mutex protection to ensure thread safety
			// NOTE: We're already inside x.mu.Lock() from Start() function, so we can directly assign
			logInfo("[Xray] Storing gRPC client (mutex already locked by Start())...")
			x.grpcClient = grpcClient
			logInfo("[Xray] ✅ gRPC client created and stored successfully (port: %d)", x.apiPort)
			
			// Verify the client is accessible (mutex already locked, no need to lock again)
			logInfo("[Xray] Verifying gRPC client is accessible...")
			verifyClient := x.grpcClient
			if verifyClient == nil {
				logError("[Xray] ❌ CRITICAL: gRPC client was stored but is nil!")
				logError("[Xray] ❌ This indicates a memory corruption issue")
			} else {
				logInfo("[Xray] ✅ Verified: gRPC client is non-nil (port: %d)", verifyClient.port)
			}
			
			// NON-BLOCKING: Verify connection in background goroutine without waiting
			// This allows Xray startup to continue immediately without blocking on gRPC verification
			logInfo("[Xray] Starting gRPC connection verification in background (fully non-blocking)...")
			go func() {
				// Wait longer for gRPC service to be fully ready
				// Xray API server needs time to start listening
				time.Sleep(1 * time.Second)
				
				// Retry verification with exponential backoff
				maxRetries := 5
				retryDelay := 500 * time.Millisecond
				
				for attempt := 1; attempt <= maxRetries; attempt++ {
					stats, err := grpcClient.GetSystemStats()
					if err == nil {
						logInfo("[Xray] ✅ gRPC connection verified (attempt %d/%d) - uptime=%ds, goroutines=%d, numGC=%d", 
							attempt, maxRetries, stats.Uptime, stats.NumGoroutine, stats.NumGC)
						logInfo("[Xray] ✅ gRPC client is fully operational and ready for stats queries")
						return
					}
					
					if attempt < maxRetries {
						logDebug("[Xray] ⚠️ gRPC connection verification failed (attempt %d/%d): %v (retrying in %v...)", 
							attempt, maxRetries, err, retryDelay)
						time.Sleep(retryDelay)
						retryDelay *= 2 // Exponential backoff
					} else {
						logWarn("[Xray] ⚠️ gRPC connection verification failed after %d attempts: %v (will retry on first use)", 
							maxRetries, err)
						logWarn("[Xray] ⚠️ This is normal if Xray gRPC service is still starting up")
						logWarn("[Xray] ⚠️ Client will retry connection on first actual use")
					}
				}
			}()
			logInfo("[Xray] gRPC verification started in background, continuing Xray startup immediately")
		}
	} else {
		logWarn("[Xray] ⚠️ No API port configured (apiPort=%d), skipping gRPC client initialization", x.apiPort)
		logWarn("[Xray] ⚠️ gRPC stats will not be available - add 'api' section to Xray config")
		x.grpcClient = nil // Ensure it's nil when API port is not configured
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

// Stop stops Xray-core IMMEDIATELY (aggressive cleanup)
func (x *XrayWrapper) Stop() error {
	x.mu.Lock()
	defer x.mu.Unlock()
	
	if !x.running {
		return nil
	}
	
	logInfo("[Xray] ⚡ IMMEDIATE STOP: Stopping Xray-core...")
	
	// IMMEDIATELY cancel context to signal all goroutines to stop
	x.cancel()
	
	// IMMEDIATELY mark as not running (before cleanup)
	x.running = false
	
	// IMMEDIATELY close all active UDP connections to prevent zombie goroutines
	x.connsMu.Lock()
	connCount := len(x.udpConns)
	if connCount > 0 {
		logInfo("[Xray] ⚡ Closing %d active UDP connection(s) immediately...", connCount)
		for conn := range x.udpConns {
			// Close without waiting - goroutines will exit via context cancel
			conn.Close()
		}
		x.udpConns = make(map[*XrayUDPConn]struct{})
		logInfo("[Xray] ✅ All UDP connections closed")
	}
	x.connsMu.Unlock()
	
	// Close gRPC client if it exists
	if x.grpcClient != nil {
		logInfo("[Xray] Closing gRPC client...")
		x.grpcClient.Close() // Don't wait for error
		x.grpcClient = nil
	}
	
	// Close Xray instance
	if err := x.instance.Close(); err != nil {
		logError("[Xray] Close error: %v", err)
		// Don't return error - continue cleanup
	}
	
	logInfo("[Xray] ✅ Xray-core stopped immediately")
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
		xray:     x,
		ctx:      ctx,
		dest:     dest,
		address:  address,
		port:     port,
		readCh:   make(chan []byte, 512), // Doubled from 256 for better buffering
		stopChan: make(chan struct{}),
	}
	
	logInfo("[Xray] ✅ XrayUDPConn created")
	
	// Track this connection
	x.connsMu.Lock()
	x.udpConns[conn] = struct{}{}
	x.connsMu.Unlock()
	
	return conn, nil
}

// IsRunning returns running state
func (x *XrayWrapper) IsRunning() bool {
	x.mu.Lock()
	defer x.mu.Unlock()
	return x.running
}

// GetGrpcClient returns the gRPC client if available
// Thread-safe access with mutex protection
func (x *XrayWrapper) GetGrpcClient() *XrayGrpcClient {
	x.mu.Lock()
	defer x.mu.Unlock()
	
	// Log when client is nil to help diagnose issues
	if x.grpcClient == nil {
		// Only log periodically to avoid log spam
		// Log every 10th call (roughly every 5 seconds at 500ms polling)
		if time.Now().Unix()%10 == 0 {
			logWarn("[Xray] GetGrpcClient() called but grpcClient is nil (apiPort=%d, running=%v)", x.apiPort, x.running)
			if x.apiPort > 0 {
				logWarn("[Xray] gRPC client should exist - possible causes:")
				logWarn("[Xray]   1. gRPC client creation failed during startup")
				logWarn("[Xray]   2. gRPC client was closed or destroyed")
				logWarn("[Xray]   3. Xray instance was restarted but gRPC client not recreated")
			} else {
				logDebug("[Xray] No API port configured (apiPort=0), gRPC client not available")
			}
		}
	} else {
		logDebug("[Xray] GetGrpcClient() returning non-nil client (port=%d)", x.grpcClient.port)
	}
	
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
	stopChan chan struct{} // Channel to signal goroutine shutdown
	readLoopRunning atomic.Bool // Track if readLoop goroutine is running
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
	
	// Log connection type first
	connType := fmt.Sprintf("%T", conn)
	logInfo("[XrayUDP] Connection type: %s", connType)
	
	// Note: For Xray-core internal connections (*cnc.connection), addresses may be 0.0.0.0:0
	// This is normal and doesn't indicate a problem - the connection is still valid
	if strings.Contains(connType, "cnc.connection") {
		logDebug("[XrayUDP] Internal connection type, 0.0.0.0:0 addresses are normal for Xray-core internal connections")
	} else {
		// For other connection types, validate addresses
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
	}
	
	// Start read goroutine
	logInfo("[XrayUDP] Starting readLoop() goroutine...")
	c.readLoopRunning.Store(true)
	go c.readLoop()
	logInfo("[XrayUDP] ✅ readLoop() goroutine started")
	
	logInfo("[XrayUDP] ✅ Connection established through Xray!")
	
	return nil
}

// readLoop reads from Xray connection
func (c *XrayUDPConn) readLoop() {
	defer c.readLoopRunning.Store(false)
	
	logInfo("[XrayUDP] ========================================")
	logInfo("[XrayUDP] readLoop() started for %s:%d", c.address, c.port)
	logInfo("[XrayUDP] ========================================")
	
	// OPTIMIZATION: Use pooled buffer for reading instead of allocating per-loop.
	// This buffer is reused for all reads in this goroutine's lifetime.
	bufPtr := getReadBuffer()
	defer putReadBuffer(bufPtr)
	buf := *bufPtr
	
	readCount := 0
	errorCount := 0
	
	for {
		// Check if context is cancelled (XrayWrapper.Stop() was called)
		select {
		case <-c.ctx.Done():
			logInfo("[XrayUDP] readLoop() exiting: context cancelled (Xray stopped)")
			return
		case <-c.stopChan:
			logInfo("[XrayUDP] readLoop() exiting: stop channel signalled")
			return
		default:
		}
		
		// Check connection state once (optimized: single lock)
		c.mu.Lock()
		closed := c.closed
		conn := c.conn
		connValid := conn != nil && !closed
		c.mu.Unlock()
		
		if closed {
			logInfo("[XrayUDP] readLoop() exiting: connection closed")
			return
		}
		
		if !connValid || conn == nil {
			logWarn("[XrayUDP] readLoop: Connection invalid before read (conn: %v, closed: %v)", conn != nil, c.closed)
			if err := c.reconnect(); err != nil {
				errorCount++
				logError("[XrayUDP] readLoop: Reconnect failed (error #%d): %v", errorCount, err)
				time.Sleep(1 * time.Second)
				continue
			}
			logInfo("[XrayUDP] readLoop: Reconnected successfully, exiting old goroutine (new one started)")
			// CRITICAL: Exit this goroutine after reconnect - new goroutine was started
			return
		}
		
		// Read with timeout to prevent blocking forever
		// 10 seconds is optimal: short enough to catch WireGuard keepalives (25s)
		// but long enough to avoid excessive timeout handling
		readDeadline := time.Now().Add(10 * time.Second)
		if err := conn.SetReadDeadline(readDeadline); err != nil {
			logWarn("[XrayUDP] readLoop: Failed to set read deadline: %v", err)
		}
		
		n, err := conn.Read(buf)
		if err != nil {
			// Quick check if closed (single lock)
			c.mu.Lock()
			wasClosed := c.closed
			c.mu.Unlock()
			
			if wasClosed {
				logInfo("[XrayUDP] readLoop() exiting: connection closed during read (closed flag set)")
				return
			}
			
			// Check if it's a timeout (expected for UDP when no data available)
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				// Timeout is expected for UDP - continue reading
				// Quick check connection is still valid (single lock)
				c.mu.Lock()
				connStillValid := c.conn != nil && !c.closed
				c.mu.Unlock()
				
				if connStillValid {
					// Normal timeout - no data available, continue reading
					if readCount == 0 || readCount%100 == 0 {
						logDebug("[XrayUDP] readLoop: Read timeout (expected for UDP, no data available, readCount: %d), continuing...", readCount)
					}
					continue
				} else {
					// Connection invalid during timeout - try reconnect
					logWarn("[XrayUDP] readLoop: Connection invalid during timeout, attempting reconnect...")
				if reconnectErr := c.reconnect(); reconnectErr != nil {
					errorCount++
					logError("[XrayUDP] readLoop: Reconnect failed (error #%d): %v", errorCount, reconnectErr)
					time.Sleep(500 * time.Millisecond)
					continue
				}
				logInfo("[XrayUDP] readLoop: ✅ Reconnected successfully, exiting old goroutine (new one started)")
				// CRITICAL: Exit this goroutine after reconnect - new goroutine was started
				return
				}
			}
			
			errorCount++
			
			// Detailed error logging and handling
			errStr := err.Error()
			errType := "unknown"
			isConnectionClosed := false
			
			if strings.Contains(errStr, "closed pipe") {
				errType = "closed pipe"
				isConnectionClosed = true
			} else if strings.Contains(errStr, "broken pipe") {
				errType = "broken pipe"
				isConnectionClosed = true
			} else if strings.Contains(errStr, "use of closed network connection") {
				errType = "closed network connection"
				isConnectionClosed = true
			} else if strings.Contains(errStr, "EOF") {
				errType = "EOF"
				isConnectionClosed = true
			} else if strings.Contains(errStr, "connection reset") {
				errType = "connection reset"
				isConnectionClosed = true
			}
			
			if isConnectionClosed {
				logWarn("[XrayUDP] readLoop: ❌ Read error #%d (type: %s): %v (readCount: %d, errorCount: %d) - Connection was closed by Xray-core", 
					errorCount, errType, err, readCount, errorCount)
				logWarn("[XrayUDP] readLoop: Connection closed by Xray-core (type: %s), attempting reconnect...", errType)
				
				// Immediately reconnect instead of exiting
				if reconnectErr := c.reconnect(); reconnectErr != nil {
					logError("[XrayUDP] readLoop: Reconnect failed (error #%d): %v", errorCount, reconnectErr)
					// Wait a bit before retrying reconnect
					time.Sleep(500 * time.Millisecond)
					continue
				}
				
				logInfo("[XrayUDP] readLoop: ✅ Reconnected successfully, exiting old goroutine (new one started)")
				// CRITICAL: Exit this goroutine after reconnect - new goroutine was started
				return
			}
			
			// For other errors, log and continue
			logError("[XrayUDP] readLoop: ❌ Read error #%d (type: %s): %v (readCount: %d, errorCount: %d)", errorCount, errType, err, readCount, errorCount)
			// Wait a bit before retrying
			time.Sleep(100 * time.Millisecond)
			continue
			
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
		
		// OPTIMIZATION: Use pooled buffer for packet data instead of make([]byte, n).
		// This significantly reduces GC pressure under high traffic.
		// NOTE: Buffer ownership transfers to readCh consumer (Read method).
		// Consumer must return buffer to pool after processing.
		var data []byte
		if n <= PacketBufferSize {
			// Use pooled buffer for typical packet sizes
			dataPtr := getPacketBuffer()
			*dataPtr = (*dataPtr)[:n]
			copy(*dataPtr, buf[:n])
			data = *dataPtr
		} else {
			// Fallback to allocation for oversized packets (rare, >2KB)
			data = make([]byte, n)
			copy(data, buf[:n])
		}
		
		select {
		case c.readCh <- data:
			if readCount%10 == 0 || n > 100 {
				logInfo("[XrayUDP] readLoop: ✅ Received %d bytes (readCount: %d, errorCount: %d)", n, readCount, errorCount)
			} else {
				logDebug("[XrayUDP] readLoop: ✅ Received %d bytes", n)
			}
		default:
			// Return buffer to pool since it won't be consumed
			if n <= PacketBufferSize {
				putPacketBuffer(&data)
			}
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
	case data, ok := <-c.readCh:
		if !ok {
			// Channel closed
			return nil, fmt.Errorf("connection closed")
		}
		return data, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("read timeout")
	case <-c.ctx.Done():
		return nil, fmt.Errorf("context cancelled")
	case <-c.stopChan:
		return nil, fmt.Errorf("connection closed")
	}
}

// Close closes the connection IMMEDIATELY
func (c *XrayUDPConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	if c.closed {
		return nil // Already closed
	}
	
	// IMMEDIATELY mark as closed
	c.closed = true
	
	// Remove from XrayWrapper tracking
	if c.xray != nil {
		c.xray.connsMu.Lock()
		delete(c.xray.udpConns, c)
		c.xray.connsMu.Unlock()
	}
	
	// IMMEDIATELY signal goroutine to stop
	select {
	case <-c.stopChan:
		// Already closed
	default:
		close(c.stopChan)
	}
	
	// IMMEDIATELY close read channel to unblock any waiting readers
	select {
	case <-c.readCh:
		// Channel already closed
	default:
		close(c.readCh)
	}
	
	// IMMEDIATELY close connection
	if c.conn != nil {
		c.conn.Close() // Don't wait for error
		c.conn = nil
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
	
	// CRITICAL: Stop old readLoop goroutine to prevent goroutine leak
	// Signal old goroutine to stop
	select {
	case <-c.stopChan:
		// Already closed, need to create new one
		c.stopChan = make(chan struct{})
	default:
		close(c.stopChan)
		c.stopChan = make(chan struct{})
	}
	
	// CRITICAL: Close and recreate readCh to prevent channel leak
	// Drain any remaining data from old channel
	drained := 0
	for {
		select {
		case <-c.readCh:
			drained++
		default:
			goto drainDone
		}
	}
drainDone:
	if drained > 0 {
		logDebug("[XrayUDP] Drained %d packets from old readCh during reconnect", drained)
	}
	
	// Close old channel and create new one
	select {
	case <-c.readCh:
		// Already closed
	default:
		close(c.readCh)
	}
	c.readCh = make(chan []byte, 200) // Buffer size: 200 packets (doubled)
	
	// Close old connection if exists (with lock protection)
	c.mu.Lock()
	oldConn := c.conn
	c.conn = nil // Clear before closing to prevent race conditions
	c.mu.Unlock()
	
	if oldConn != nil {
		oldConn.Close()
	}
	
	// Wait a bit for old readLoop goroutine to exit
	time.Sleep(50 * time.Millisecond)
	
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
	
	// Note: For Xray-core internal connections (*cnc.connection), addresses may be 0.0.0.0:0
	// This is normal and doesn't indicate a problem - the connection is still valid
	connType := fmt.Sprintf("%T", conn)
	logInfo("[XrayUDP] Reconnect - Connection type: %s", connType)
	
	// For *cnc.connection (Xray-core internal), 0.0.0.0:0 addresses are expected
	if strings.Contains(connType, "cnc.connection") {
		logDebug("[XrayUDP] Reconnect - Internal connection type, 0.0.0.0:0 addresses are normal")
	} else {
		// For other connection types, validate addresses
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
	}
	
	// CRITICAL: Start new readLoop goroutine for new connection
	logInfo("[XrayUDP] Starting new readLoop() goroutine after reconnect...")
	go c.readLoop()
	logInfo("[XrayUDP] ✅ New readLoop() goroutine started after reconnect")
	
	return nil
}


// ============================================================================
// QUIC SUPPORT FOR MASQUE OVER XRAY
// ============================================================================

// QUICConnection represents a QUIC connection through Xray
// This is used by MasqueBind for MASQUE over Xray tunneling
type QUICConnection struct {
	xray       *XrayWrapper
	ctx        context.Context
	cancel     context.CancelFunc
	endpoint   string
	conn       net.Conn
	streams    map[int64]net.Conn // Stream ID -> Stream connection
	streamsMu  sync.Mutex
	nextStream int64
	closed     atomic.Bool
	readCh     chan []byte
	stopChan   chan struct{}
}

// MasqueQUICConnection interface matches masque.QUICConnection
// This allows bridge.QUICConnection to be used by masque.MasqueBind
type MasqueQUICConnection interface {
	Write(data []byte) (int, error)
	Read(timeout time.Duration) ([]byte, error)
	OpenStream() (int64, error)
	CloseStream(streamID int64) error
	Close() error
	IsConnected() bool
	GetEndpoint() string
}

// DialQUIC creates a QUIC-like connection through Xray outbound
// This is the main entry point for MASQUE over Xray
// 
// The connection is established through Xray's outbound (VLESS/VMess/etc)
// and provides a QUIC-like interface for HTTP/3 CONNECT-IP/CONNECT-UDP
//
// Parameters:
//   - endpoint: The MASQUE proxy endpoint (e.g., "masque.example.com:443")
//
// Returns:
//   - interface{}: A QUIC-like connection that implements masque.QUICConnection
//     (returned as interface{} to avoid circular import with masque package)
//   - error: Any error that occurred during connection
func (x *XrayWrapper) DialQUIC(endpoint string) (interface{}, error) {
	logInfo("[XrayQUIC] ========================================")
	logInfo("[XrayQUIC] DialQUIC called")
	logInfo("[XrayQUIC] Endpoint: %s", endpoint)
	logInfo("[XrayQUIC] ========================================")

	if !x.running {
		logError("[XrayQUIC] ❌ Cannot DialQUIC - Xray not running!")
		return nil, fmt.Errorf("xray not running")
	}

	if x.instance == nil {
		logError("[XrayQUIC] ❌ Instance is nil!")
		return nil, fmt.Errorf("instance is nil")
	}

	// Parse endpoint to get address and port
	host, portStr, err := net.SplitHostPort(endpoint)
	if err != nil {
		logError("[XrayQUIC] ❌ Invalid endpoint format: %v", err)
		return nil, fmt.Errorf("invalid endpoint: %w", err)
	}

	port := 443 // Default QUIC port
	if portStr != "" {
		fmt.Sscanf(portStr, "%d", &port)
	}

	logInfo("[XrayQUIC] Parsed endpoint: host=%s, port=%d", host, port)

	// Create UDP destination for QUIC (QUIC runs over UDP)
	// Xray will route this through the configured outbound with QUIC transport
	dest := xnet.UDPDestination(xnet.ParseAddress(host), xnet.Port(port))
	logInfo("[XrayQUIC] Destination created (UDP for QUIC): %v", dest)

	// Create context with cancellation
	ctx, cancel := context.WithCancel(x.ctx)

	// Dial through Xray's routing
	logInfo("[XrayQUIC] Calling core.Dial()...")
	conn, err := core.Dial(ctx, x.instance, dest)
	if err != nil {
		cancel()
		logError("[XrayQUIC] ❌ core.Dial() FAILED: %v", err)
		return nil, fmt.Errorf("dial: %w", err)
	}

	if conn == nil {
		cancel()
		logError("[XrayQUIC] ❌ core.Dial() returned nil connection!")
		return nil, fmt.Errorf("dial returned nil")
	}

	logInfo("[XrayQUIC] ✅ core.Dial() successful!")
	logInfo("[XrayQUIC] Connection type: %T", conn)

	qc := &QUICConnection{
		xray:       x,
		ctx:        ctx,
		cancel:     cancel,
		endpoint:   endpoint,
		conn:       conn,
		streams:    make(map[int64]net.Conn),
		nextStream: 0,
		readCh:     make(chan []byte, 256),
		stopChan:   make(chan struct{}),
	}

	// Start read goroutine
	go qc.readLoop()

	logInfo("[XrayQUIC] ✅ QUICConnection created successfully")
	return qc, nil
}

// readLoop reads from the underlying connection
func (qc *QUICConnection) readLoop() {
	logInfo("[XrayQUIC] readLoop started for %s", qc.endpoint)
	defer logInfo("[XrayQUIC] readLoop exited for %s", qc.endpoint)

	buf := make([]byte, 65535)
	for {
		select {
		case <-qc.ctx.Done():
			return
		case <-qc.stopChan:
			return
		default:
		}

		if qc.closed.Load() {
			return
		}

		// Set read deadline to allow periodic checks
		qc.conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))

		n, err := qc.conn.Read(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue // Timeout is expected, continue loop
			}
			if qc.closed.Load() {
				return
			}
			logWarn("[XrayQUIC] Read error: %v", err)
			continue
		}

		if n > 0 {
			data := make([]byte, n)
			copy(data, buf[:n])

			select {
			case qc.readCh <- data:
				logDebug("[XrayQUIC] Received %d bytes", n)
			default:
				logWarn("[XrayQUIC] Read buffer full, dropping %d bytes", n)
			}
		}
	}
}

// Write sends data through the QUIC connection
func (qc *QUICConnection) Write(data []byte) (int, error) {
	if qc.closed.Load() {
		return 0, fmt.Errorf("connection closed")
	}

	n, err := qc.conn.Write(data)
	if err != nil {
		logError("[XrayQUIC] Write error: %v", err)
		return 0, err
	}

	logDebug("[XrayQUIC] Wrote %d bytes", n)
	return n, nil
}

// Read receives data from the QUIC connection
func (qc *QUICConnection) Read(timeout time.Duration) ([]byte, error) {
	select {
	case data, ok := <-qc.readCh:
		if !ok {
			return nil, fmt.Errorf("connection closed")
		}
		return data, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("read timeout")
	case <-qc.ctx.Done():
		return nil, fmt.Errorf("context cancelled")
	case <-qc.stopChan:
		return nil, fmt.Errorf("connection closed")
	}
}

// OpenStream opens a new bidirectional stream (for HTTP/3 requests)
func (qc *QUICConnection) OpenStream() (int64, error) {
	if qc.closed.Load() {
		return 0, fmt.Errorf("connection closed")
	}

	qc.streamsMu.Lock()
	defer qc.streamsMu.Unlock()

	streamID := qc.nextStream
	qc.nextStream++

	// For now, streams share the underlying connection
	// In a full QUIC implementation, each stream would be multiplexed
	qc.streams[streamID] = qc.conn

	logInfo("[XrayQUIC] Opened stream %d", streamID)
	return streamID, nil
}

// CloseStream closes a stream
func (qc *QUICConnection) CloseStream(streamID int64) error {
	qc.streamsMu.Lock()
	defer qc.streamsMu.Unlock()

	if _, exists := qc.streams[streamID]; !exists {
		return fmt.Errorf("stream %d not found", streamID)
	}

	delete(qc.streams, streamID)
	logInfo("[XrayQUIC] Closed stream %d", streamID)
	return nil
}

// Close closes the QUIC connection
func (qc *QUICConnection) Close() error {
	if qc.closed.Swap(true) {
		return nil // Already closed
	}

	logInfo("[XrayQUIC] Closing connection to %s", qc.endpoint)

	// Signal goroutines to stop
	close(qc.stopChan)

	// Cancel context
	qc.cancel()

	// Close underlying connection
	if qc.conn != nil {
		qc.conn.Close()
	}

	// Close read channel
	close(qc.readCh)

	logInfo("[XrayQUIC] ✅ Connection closed")
	return nil
}

// LocalAddr returns the local address
func (qc *QUICConnection) LocalAddr() net.Addr {
	if qc.conn != nil {
		return qc.conn.LocalAddr()
	}
	return nil
}

// RemoteAddr returns the remote address
func (qc *QUICConnection) RemoteAddr() net.Addr {
	if qc.conn != nil {
		return qc.conn.RemoteAddr()
	}
	return nil
}

// GetEndpoint returns the endpoint address
func (qc *QUICConnection) GetEndpoint() string {
	return qc.endpoint
}

// IsConnected checks if the connection is still valid
func (qc *QUICConnection) IsConnected() bool {
	return !qc.closed.Load() && qc.conn != nil
}

// ProtectSocket implements masque.ProtectedDialer interface
// This allows MASQUE QUIC client to protect its UDP socket from VPN routing
func (x *XrayWrapper) ProtectSocket(fd int) error {
	logInfo("[XrayWrapper] ProtectSocket called for fd=%d", fd)
	
	if !ProtectSocket(fd) {
		logError("[XrayWrapper] ❌ Failed to protect socket fd=%d", fd)
		return fmt.Errorf("failed to protect socket fd=%d", fd)
	}
	
	logInfo("[XrayWrapper] ✅ Socket fd=%d protected successfully", fd)
	return nil
}
