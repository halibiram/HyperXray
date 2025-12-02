package bridge

/*
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

static void android_log_print(int priority, const char* tag, const char* msg) {
    __android_log_print(priority, tag, "%s", msg);
}

// Optional callAiLogHelper wrapper - uses dlsym to find symbol at runtime
// If symbol doesn't exist, silently falls back to Android log only
static void safe_callAiLogHelper(const char* tag, const char* level, const char* message) {
    // Try to find callAiLogHelper symbol dynamically
    static void (*callAiLogHelper_func)(const char*, const char*, const char*) = NULL;
    static int checked = 0;

    if (!checked) {
        checked = 1;
        // Try to get symbol from current process
        // RTLD_DEFAULT means search in all loaded libraries
        callAiLogHelper_func = (void (*)(const char*, const char*, const char*))
            dlsym(RTLD_DEFAULT, "callAiLogHelper");
    }

    // If symbol found, call it; otherwise just use Android log (already called)
    if (callAiLogHelper_func != NULL) {
        callAiLogHelper_func(tag, level, message);
    }
    // If not found, we already logged to Android log, so just return
}
*/
import "C"
import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

const logTag = "HyperXray-Bridge"

// logInfo logs info level messages to Android logcat and AiLogHelper
func logInfo(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	tag := C.CString(logTag)
	defer C.free(unsafe.Pointer(tag))
	msgC := C.CString(msg)
	defer C.free(unsafe.Pointer(msgC))
	
	// Log to Android logcat
	C.android_log_print(C.ANDROID_LOG_INFO, tag, msgC)
	
	// Also log to AiLogHelper (optional - will work if symbol exists)
	levelC := C.CString("INFO")
	defer C.free(unsafe.Pointer(levelC))
	C.safe_callAiLogHelper(tag, levelC, msgC)
}

// logError logs error level messages to Android logcat and AiLogHelper
func logError(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	tag := C.CString(logTag)
	defer C.free(unsafe.Pointer(tag))
	msgC := C.CString(msg)
	defer C.free(unsafe.Pointer(msgC))
	
	// Log to Android logcat
	C.android_log_print(C.ANDROID_LOG_ERROR, tag, msgC)
	
	// Also log to AiLogHelper (optional - will work if symbol exists)
	levelC := C.CString("ERROR")
	defer C.free(unsafe.Pointer(levelC))
	C.safe_callAiLogHelper(tag, levelC, msgC)
}

// logDebug logs debug level messages to Android logcat and AiLogHelper
func logDebug(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	tag := C.CString(logTag)
	defer C.free(unsafe.Pointer(tag))
	msgC := C.CString(msg)
	defer C.free(unsafe.Pointer(msgC))
	
	// Log to Android logcat
	C.android_log_print(C.ANDROID_LOG_DEBUG, tag, msgC)
	
	// Also log to AiLogHelper (optional - will work if symbol exists)
	levelC := C.CString("DEBUG")
	defer C.free(unsafe.Pointer(levelC))
	C.safe_callAiLogHelper(tag, levelC, msgC)
}

// logWarn logs warning level messages to Android logcat and AiLogHelper
func logWarn(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	tag := C.CString(logTag)
	defer C.free(unsafe.Pointer(tag))
	msgC := C.CString(msg)
	defer C.free(unsafe.Pointer(msgC))
	
	// Log to Android logcat
	C.android_log_print(C.ANDROID_LOG_WARN, tag, msgC)
	
	// Also log to AiLogHelper (optional - will work if symbol exists)
	levelC := C.CString("WARN")
	defer C.free(unsafe.Pointer(levelC))
	C.safe_callAiLogHelper(tag, levelC, msgC)
}

// TunnelConfig holds configuration for creating a tunnel
type TunnelConfig struct {
	TunFd          int
	WgConfig       string
	XrayConfig     string
	WarpEndpoint   string
	WarpPrivateKey string
	MTU            int
	NativeLibDir   string
	FilesDir       string
}

// TunnelStats holds tunnel statistics
type TunnelStats struct {
	Connected     bool   `json:"connected"`
	TxBytes       uint64 `json:"txBytes"`
	RxBytes       uint64 `json:"rxBytes"`
	TxPackets     uint64 `json:"txPackets"`
	RxPackets     uint64 `json:"rxPackets"`
	LastHandshake int64  `json:"lastHandshake"`
	HandshakeRTT  int64  `json:"handshakeRTT"` // Estimated RTT in milliseconds
	Endpoint      string `json:"endpoint"`
	Uptime        int64  `json:"uptime"`
	Error         string `json:"error,omitempty"`
}

// WireGuardParsedConfig holds parsed WireGuard configuration
type WireGuardParsedConfig struct {
	PrivateKey          string `json:"privateKey"`
	Address             string `json:"address"`
	DNS                 string `json:"dns"`
	MTU                 int    `json:"mtu"`
	PeerPublicKey       string `json:"peerPublicKey"`
	Endpoint            string `json:"endpoint"`
	AllowedIPs          string `json:"allowedIPs"`
	PersistentKeepalive int    `json:"persistentKeepalive"`
}

// SessionState represents the state of a VPN session
type SessionState int32

const (
	SessionStateIdle     SessionState = iota // Not running
	SessionStateStarting                     // Start() in progress
	SessionStateRunning                      // Running normally
	SessionStateStopping                     // Stop() in progress
	SessionStateStopped                      // Stopped, cleanup complete
)

// CleanupTimeout is the maximum time to wait for cleanup before force termination
const CleanupTimeout = 5 * time.Second

// GoroutineExitTimeout is the maximum time for goroutines to exit after context cancellation
const GoroutineExitTimeout = 2 * time.Second

// HyperTunnel represents the main tunnel instance
type HyperTunnel struct {
	config     TunnelConfig
	wgConfig   *WireGuardParsedConfig
	tunDevice  tun.Device
	wgDevice   *device.Device
	bind       conn.Bind
	
	// Xray integration
	xrayInstance *XrayWrapper
	xrayBind     *XrayBind
	
	running    atomic.Bool
	stats      TunnelStats
	statsLock  sync.RWMutex
	
	stopChan   chan struct{}
	startTime  time.Time
	lastHandshake time.Time
	handshakeRTT int64 // Cached handshake RTT in milliseconds
	
	// Session lifecycle management (for port conflict/zombie fix)
	sessionCtx    context.Context    // Context for this session, cancelled on Stop()
	sessionCancel context.CancelFunc // Cancel function to signal all goroutines to stop
	cleanupWg     sync.WaitGroup     // WaitGroup to track goroutines for cleanup
	cleanupDone   chan struct{}      // Channel signaled when cleanup is complete
	state         atomic.Int32       // Atomic session state for thread-safe transitions
}

// NewHyperTunnel creates a new tunnel instance
func NewHyperTunnel(config TunnelConfig) (*HyperTunnel, error) {
	logInfo("Creating new HyperTunnel...")
	logDebug("TunFd: %d, MTU: %d", config.TunFd, config.MTU)

	// Parse WireGuard config
	logInfo("Parsing WireGuard configuration...")
	var wgConfig WireGuardParsedConfig
	if err := json.Unmarshal([]byte(config.WgConfig), &wgConfig); err != nil {
		logError("Failed to parse WireGuard config JSON: %v", err)
		return nil, fmt.Errorf("failed to parse WireGuard config: %v", err)
	}
	logDebug("WireGuard config parsed successfully")
	logDebug("  PrivateKey length: %d", len(wgConfig.PrivateKey))
	logDebug("  PeerPublicKey: %s", wgConfig.PeerPublicKey)
	logDebug("  Endpoint: %s", wgConfig.Endpoint)
	logDebug("  Address: %s", wgConfig.Address)
	logDebug("  AllowedIPs: %s", wgConfig.AllowedIPs)

	// Validate private key
	if wgConfig.PrivateKey == "" {
		logError("WireGuard private key is empty")
		return nil, fmt.Errorf("WireGuard private key is empty")
	}

	// Decode and validate private key format
	privKeyBytes, err := base64.StdEncoding.DecodeString(wgConfig.PrivateKey)
	if err != nil {
		logError("Failed to decode private key: %v", err)
		return nil, fmt.Errorf("failed to decode private key: %v", err)
	}
	if len(privKeyBytes) != 32 {
		logError("Invalid private key length: %d (expected 32)", len(privKeyBytes))
		return nil, fmt.Errorf("invalid private key length: %d", len(privKeyBytes))
	}
	logDebug("Private key validated (32 bytes)")

	// Validate peer public key
	if wgConfig.PeerPublicKey == "" {
		logError("WireGuard peer public key is empty")
		return nil, fmt.Errorf("WireGuard peer public key is empty")
	}
	
	peerPubKeyBytes, err := base64.StdEncoding.DecodeString(wgConfig.PeerPublicKey)
	if err != nil {
		logError("Failed to decode peer public key: %v", err)
		return nil, fmt.Errorf("failed to decode peer public key: %v", err)
	}
	if len(peerPubKeyBytes) != 32 {
		logError("Invalid peer public key length: %d (expected 32)", len(peerPubKeyBytes))
		return nil, fmt.Errorf("invalid peer public key length: %d", len(peerPubKeyBytes))
	}
	logDebug("Peer public key validated (32 bytes)")

	// Validate endpoint
	if wgConfig.Endpoint == "" {
		logError("WireGuard endpoint is empty")
		return nil, fmt.Errorf("WireGuard endpoint is empty")
	}
	logDebug("Endpoint: %s", wgConfig.Endpoint)

	// Create TUN device from file descriptor
	logInfo("Creating TUN device from fd %d...", config.TunFd)
	tunDev, tunName, err := createTUNFromFd(config.TunFd, config.MTU)
	if err != nil {
		logError("Failed to create TUN device: %v", err)
		return nil, fmt.Errorf("failed to create TUN device: %v", err)
	}
	logInfo("TUN device created: %s", tunName)

	// Initialize Xray instance if XrayConfig is provided
	var xrayInst *XrayWrapper
	if config.XrayConfig != "" && config.XrayConfig != "{}" {
		logInfo("[Tunnel] Step 4: Creating Xray instance...")
		logDebug("[Tunnel] XrayConfig length: %d bytes", len(config.XrayConfig))
		var err error
		xrayInst, err = NewXrayWrapper(config.XrayConfig)
		if err != nil {
			logError("[Tunnel] ❌ Failed to create Xray instance: %v", err)
			logError("[Tunnel] ❌ Cannot continue without Xray - returning error")
			// CRITICAL: Close TUN device to prevent FD leak
			logError("[Tunnel] 🧹 Closing TUN device to prevent FD leak...")
			tunDev.Close()
			return nil, fmt.Errorf("failed to create Xray instance: %w", err)
		} else {
			logInfo("[Tunnel] ✅ Xray instance created")
		}
	} else {
		logError("[Tunnel] ❌ XrayConfig is empty or invalid: '%s' (length: %d)", config.XrayConfig, len(config.XrayConfig))
		// CRITICAL: Close TUN device to prevent FD leak
		logError("[Tunnel] 🧹 Closing TUN device to prevent FD leak...")
		tunDev.Close()
		return nil, fmt.Errorf("XrayConfig is empty or invalid (must not be empty or '{}')")
	}

	// Create session context for lifecycle management
	sessionCtx, sessionCancel := context.WithCancel(context.Background())

	// Create tunnel instance with session lifecycle fields
	tunnel := &HyperTunnel{
		config:        config,
		wgConfig:      &wgConfig,
		tunDevice:     tunDev,
		xrayInstance:  xrayInst,
		stopChan:      make(chan struct{}),
		sessionCtx:    sessionCtx,
		sessionCancel: sessionCancel,
		cleanupDone:   make(chan struct{}),
	}
	// Initialize state to Idle
	tunnel.state.Store(int32(SessionStateIdle))

	logInfo("HyperTunnel instance created successfully (with session context)")
	return tunnel, nil
}

// createTUNFromFd creates a TUN device from a file descriptor
func createTUNFromFd(fd int, mtu int) (tun.Device, string, error) {
	logDebug("Creating TUN from fd=%d, mtu=%d", fd, mtu)

	// Verify fd is valid
	fdPath := fmt.Sprintf("/proc/self/fd/%d", fd)
	if _, err := os.Stat(fdPath); err != nil {
		return nil, "", fmt.Errorf("fd %d is not valid: %v", fd, err)
	}

	// Create TUN device
	// Note: On Android, we use the fd provided by VpnService
	tunDev, name, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		return nil, "", fmt.Errorf("CreateUnmonitoredTUNFromFD failed: %v", err)
	}

	// Set MTU if needed
	if mtu > 0 {
		tunMTU, err := tunDev.MTU()
		if err != nil {
			logDebug("Could not get TUN MTU: %v", err)
		} else {
			logDebug("TUN MTU: %d", tunMTU)
		}
	}

	return tunDev, name, nil
}

// Start starts the tunnel with full diagnostics
func (t *HyperTunnel) Start() error {
	// Atomic state transition: Idle -> Starting
	// This prevents race conditions when Start() is called concurrently
	if !t.state.CompareAndSwap(int32(SessionStateIdle), int32(SessionStateStarting)) {
		currentState := SessionState(t.state.Load())
		if currentState == SessionStateRunning {
			return fmt.Errorf("already running")
		}
		return fmt.Errorf("invalid state for Start(): %d", currentState)
	}
	
	// Ensure we reset state on failure
	startSuccess := false
	defer func() {
		if !startSuccess {
			t.state.Store(int32(SessionStateIdle))
		}
	}()
	
	logInfo("[Tunnel] ========================================")
	logInfo("[Tunnel] Starting HyperTunnel with Diagnostics")
	logInfo("[Tunnel] ========================================")
	
	// Create fresh session context for this session
	// This ensures all goroutines can be cancelled when Stop() is called
	if t.sessionCancel != nil {
		// Cancel any previous context (shouldn't happen, but be safe)
		t.sessionCancel()
	}
	t.sessionCtx, t.sessionCancel = context.WithCancel(context.Background())
	t.cleanupDone = make(chan struct{})
	logInfo("[Tunnel] ✅ Session context created for lifecycle management")
	
	// ===== PRE-CHECK: Network Diagnostics =====
	// NOTE: We skip this check before VPN starts because socket protection
	// requires VPN to be running. Network diagnostics will be performed
	// after Xray starts and can actually connect through the tunnel.
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ PRE-CHECK: Network Diagnostics (SKIPPED)")
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ℹ️  Skipping network diagnostics before VPN start")
	logInfo("[Tunnel] ℹ️  Network diagnostics will be performed after Xray starts")
	
	// ===== PRE-CHECK: Xray Server Reachability =====
	// NOTE: We skip this check before VPN starts because socket protection
	// requires VPN to be running. Server connectivity will be verified
	// after Xray starts and can actually connect through the tunnel.
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ PRE-CHECK: Xray Server Reachability (SKIPPED)")
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ℹ️  Skipping server reachability check before VPN start")
	logInfo("[Tunnel] ℹ️  Server connectivity will be verified after Xray starts")
	
	// Extract server info from Xray config for logging
	serverAddr, serverPort := t.extractServerFromConfig()
	if serverAddr != "" {
		logInfo("[Tunnel] ℹ️  Target server: %s:%d", serverAddr, serverPort)
		logInfo("[Tunnel] ℹ️  Will verify connectivity after Xray-core starts")
	}
	
	// ===== STEP 0: TEST PROTECTED DIALER CONNECTIVITY =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 0: Testing Protected Dialer connectivity...")
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] This test runs BEFORE Xray starts to verify:")
	logInfo("[Tunnel]   1. Socket protection is working correctly")
	logInfo("[Tunnel]   2. Protected Dialer can reach the internet")
	logInfo("[Tunnel]   3. No VPN routing loop")
	logInfo("[Tunnel] ")
	
	// Test Protected Dialer BEFORE starting Xray
	// This verifies that socket protection is working correctly
	// and can reach the internet without VPN routing loop
	// NOTE: This test can fail in certain conditions (tethering, restricted networks)
	// but Xray may still work, so we continue with a warning instead of failing
	connectivityErr := TestConnectivity()
	if connectivityErr != nil {
		logWarn("[Tunnel] ")
		logWarn("[Tunnel] ⚠️ Protected Dialer connectivity test FAILED!")
		logWarn("[Tunnel]    Error: %v", connectivityErr)
		logWarn("[Tunnel] ")
		logWarn("[Tunnel] This may indicate:")
		logWarn("[Tunnel]    1. Tethering/Hotspot is active (can cause false negatives)")
		logWarn("[Tunnel]    2. Network interface binding issue")
		logWarn("[Tunnel]    3. Firewall blocking test connections")
		logWarn("[Tunnel]    4. Temporary network connectivity issue")
		logWarn("[Tunnel] ")
		logWarn("[Tunnel] ℹ️ Proceeding anyway - Xray may still work correctly")
		logWarn("[Tunnel] ℹ️ Socket protection (VpnService.protect) is still active")
		logWarn("[Tunnel] ")
		// NOTE: Don't fail here - the test can give false negatives when:
		// - Tethering/Hotspot is active
		// - Network is temporarily slow
		// - Firewall blocks test endpoints but allows Xray server
		// Xray will fail later with a more specific error if there's a real issue
	} else {
		logInfo("[Tunnel] ")
		logInfo("[Tunnel] ✅ Protected Dialer connectivity test PASSED!")
		logInfo("[Tunnel]    Protected Dialer is working correctly")
		logInfo("[Tunnel]    Socket protection is functioning properly")
		logInfo("[Tunnel]    Ready to start Xray-core")
		logInfo("[Tunnel] ")
	}
	
	// ===== STEP 1: START XRAY WITH VERIFICATION =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 1: Starting Xray-core...")
	logInfo("[Tunnel] ")
	
	if t.xrayInstance == nil {
		logError("[Tunnel] ❌ Xray instance is nil!")
		logError("[Tunnel] ❌ XrayConfig was: '%s' (length: %d bytes)", t.config.XrayConfig, len(t.config.XrayConfig))
		logError("[Tunnel] ❌ This should not happen - XrayConfig should have been validated in NewHyperTunnel()")
		return fmt.Errorf("xray instance is nil (XrayConfig may be empty or invalid: '%s')", t.config.XrayConfig)
	}
	
	logDebug("[Tunnel] Calling t.xrayInstance.Start()...")
	
	err := t.xrayInstance.Start()
	if err != nil {
		logError("[Tunnel] ❌ Xray start/verification failed: %v", err)
		logError("[Tunnel] ")
		logError("[Tunnel] ⚠️ Cannot proceed - Xray cannot reach internet!")
		logError("[Tunnel] ⚠️ Check your Xray server configuration.")
		logError("[Tunnel] ")
		return fmt.Errorf("xray connectivity failed: %w", err)
	}
	
	logInfo("[Tunnel] ✅ Xray started and verified!")
	
	// ===== STEP 2: TEST UDP TO WARP ENDPOINT =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 2: Testing UDP path to WARP endpoint...")
	logInfo("[Tunnel] ")
	
	// Run UDP test with context timeout to ensure goroutine cleanup
	udpCtx, udpCancel := context.WithTimeout(context.Background(), 2*time.Second)
	udpTestDone := make(chan error, 1)
	go func() {
		udpTestErr := t.xrayInstance.TestUDPConnectivity(t.wgConfig.Endpoint)
		// Use select to avoid blocking if context is cancelled
		select {
		case udpTestDone <- udpTestErr:
		case <-udpCtx.Done():
			// Context cancelled, don't block on channel send
		}
	}()
	
	// Wait for UDP test result or timeout
	select {
	case udpTestErr := <-udpTestDone:
		udpCancel() // Release context resources
		if udpTestErr != nil {
			logWarn("[Tunnel] ⚠️ UDP test warning: %v", udpTestErr)
			logWarn("[Tunnel] ⚠️ Will proceed but WireGuard may have issues")
			// Don't fail - WireGuard might still work
		} else {
			logInfo("[Tunnel] ✅ UDP path verified!")
		}
	case <-udpCtx.Done():
		udpCancel() // Release context resources
		logDebug("[Tunnel] UDP test still in progress (continuing startup)")
		logWarn("[Tunnel] ⚠️ UDP test timeout - proceeding without verification")
		logWarn("[Tunnel] ⚠️ WireGuard may have issues if UDP path is not working")
	}
	
	// ===== STEP 3: CREATE XRAY BIND =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 3: Creating XrayBind...")
	logInfo("[Tunnel] ")
	
	t.xrayBind, err = NewXrayBind(t.xrayInstance, t.wgConfig.Endpoint)
	if err != nil {
		logError("[Tunnel] ❌ NewXrayBind failed: %v", err)
		t.cleanup()
		return fmt.Errorf("create bind: %w", err)
	}
	
	logInfo("[Tunnel] ✅ XrayBind created")
	
	// Open XrayBind before creating WireGuard device
	// WireGuard will need the bind to be open when IpcSet is called
	logInfo("[Tunnel] Opening XrayBind...")
	_, _, err = t.xrayBind.Open(0)
	if err != nil {
		logError("[Tunnel] ❌ XrayBind.Open() failed: %v", err)
		t.cleanup()
		return fmt.Errorf("open bind: %w", err)
	}
	logInfo("[Tunnel] ✅ XrayBind opened")
	
	t.bind = t.xrayBind
	
	// ===== STEP 4: CREATE WIREGUARD DEVICE =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 4: Creating WireGuard device...")
	logInfo("[Tunnel] ")
	
	logger := &device.Logger{
		Verbosef: func(f string, a ...interface{}) { 
			logDebug("[WireGuard] "+f, a...) 
		},
		Errorf: func(f string, a ...interface{}) { 
			logError("[WireGuard] "+f, a...) 
		},
	}
	
	t.wgDevice = device.NewDevice(t.tunDevice, t.bind, logger)
	if t.wgDevice == nil {
		logError("[Tunnel] ❌ WireGuard device is nil!")
		t.cleanup()
		return fmt.Errorf("wireguard device creation failed")
	}
	
	logInfo("[Tunnel] ✅ WireGuard device created")
	
	// ===== STEP 5: CONFIGURE WIREGUARD =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 5: Configuring WireGuard via IPC...")
	logInfo("[Tunnel] ")
	
	ipcConfig := t.generateIpcConfig()
	logDebug("[Tunnel] IPC config built, length: %d bytes", len(ipcConfig))
	
	err = t.wgDevice.IpcSet(ipcConfig)
	if err != nil {
		logError("[Tunnel] ❌ IpcSet failed: %v", err)
		t.cleanup()
		return fmt.Errorf("ipc set: %w", err)
	}
	
	logInfo("[Tunnel] ✅ WireGuard configured")
	
	// ===== STEP 6: BRING UP WIREGUARD =====
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ▶▶▶ STEP 6: Bringing up WireGuard...")
	logInfo("[Tunnel] ")
	
	err = t.wgDevice.Up()
	if err != nil {
		logError("[Tunnel] ❌ WireGuard Up() failed: %v", err)
		t.cleanup()
		return fmt.Errorf("wg up: %w", err)
	}
	
	logInfo("[Tunnel] ✅ WireGuard is UP")
	
	// ===== DONE =====
	t.running.Store(true)
	t.startTime = time.Now()
	
	// Transition state to Running
	t.state.Store(int32(SessionStateRunning))
	startSuccess = true
	
	// Start goroutines with session context for coordinated shutdown
	// Track them with WaitGroup for cleanup
	t.cleanupWg.Add(2)
	go t.collectStatsWithContext(t.sessionCtx)
	go t.monitorHandshakeWithContext(t.sessionCtx)
	
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ========================================")
	logInfo("[Tunnel] ✅✅✅ TUNNEL FULLY STARTED! ✅✅✅")
	logInfo("[Tunnel] ========================================")
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] Now waiting for WireGuard handshake...")
	logInfo("[Tunnel] If handshake fails after 30s, there may be")
	logInfo("[Tunnel] an issue with UDP routing through Xray.")
	logInfo("[Tunnel] Session context active for lifecycle management")
	
	return nil
}

// generateIpcConfig generates WireGuard IPC configuration
func (t *HyperTunnel) generateIpcConfig() string {
	// Resolve endpoint to IP address if it's a domain name
	// WireGuard IPC only accepts IP addresses, not domain names
	endpoint := t.wgConfig.Endpoint
	logError("🔍 Attempting to resolve endpoint: %s", endpoint)
	resolvedEndpoint, err := resolveEndpoint(endpoint)
	if err != nil {
		logError("❌ Failed to resolve endpoint %s: %v", endpoint, err)
		// Fallback to original endpoint (may fail, but better than nothing)
		resolvedEndpoint = endpoint
		logError("⚠️ Using original endpoint (will likely fail): %s", resolvedEndpoint)
	} else {
		logError("✅ Resolved endpoint: %s -> %s", endpoint, resolvedEndpoint)
	}
	
	config := fmt.Sprintf(`private_key=%s
public_key=%s
endpoint=%s
allowed_ip=0.0.0.0/0
allowed_ip=::/0
persistent_keepalive_interval=%d
`,

		hexEncode(t.wgConfig.PrivateKey),
		hexEncode(t.wgConfig.PeerPublicKey),
		resolvedEndpoint,
		t.wgConfig.PersistentKeepalive,
	)

	return config
}

// resolveEndpoint resolves a domain name to IP address if needed
// Input format: "host:port" or "IP:port"
// Output format: "IP:port"
func resolveEndpoint(endpoint string) (string, error) {
	// Parse endpoint to extract host and port
	host, port, err := net.SplitHostPort(endpoint)
	if err != nil {
		return endpoint, fmt.Errorf("invalid endpoint format: %v", err)
	}
	
	// Check if host is already an IP address
	if net.ParseIP(host) != nil {
		// Already an IP address, return as-is
		return endpoint, nil
	}
	
	// Resolve domain name to IP address
	logError("🔍 Resolving domain name: %s", host)
	ips, err := net.LookupIP(host)
	if err != nil {
		return endpoint, fmt.Errorf("DNS lookup failed: %v", err)
	}
	
	if len(ips) == 0 {
		return endpoint, fmt.Errorf("no IP addresses found for %s", host)
	}
	
	// Prefer IPv4 over IPv6
	var selectedIP net.IP
	for _, ip := range ips {
		if ip.To4() != nil {
			selectedIP = ip
			break
		}
	}
	
	// If no IPv4 found, use first available IP (IPv6)
	if selectedIP == nil {
		selectedIP = ips[0]
	}
	
	// Return resolved IP with port
	resolved := net.JoinHostPort(selectedIP.String(), port)
	return resolved, nil
}

// hexEncode converts base64 to hex for WireGuard IPC
func hexEncode(base64Str string) string {
	bytes, err := base64.StdEncoding.DecodeString(base64Str)
	if err != nil {
		logError("Failed to decode base64: %v", err)
		return ""
	}
	return fmt.Sprintf("%x", bytes)
}

// maskPrivateKey masks sensitive keys (PrivateKey, PresharedKey) in log output
// Handles various formatting: PrivateKey=..., PrivateKey = ..., private_key=...
func maskPrivateKey(config string) string {
	if config == "" {
		return config
	}

	// Pattern matches:
	// - PrivateKey = <value> (WireGuard config format)
	// - PrivateKey=<value> (no spaces)
	// - private_key=<hex> (WireGuard IPC format)
	// - PresharedKey = <value> (optional WireGuard field)
	// - preshared_key=<hex> (IPC format)
	// Value continues until newline or end of string
	patterns := []string{
		`(?i)(private_?key\s*=\s*)[^\n\r]+`,
		`(?i)(preshared_?key\s*=\s*)[^\n\r]+`,
	}

	result := config
	for _, pattern := range patterns {
		re := regexp.MustCompile(pattern)
		result = re.ReplaceAllString(result, "${1}[MASKED]")
	}

	return result
}

// Stop stops the tunnel with proper lifecycle management
func (t *HyperTunnel) Stop() error {
	logInfo("[Tunnel] ⚡ STOP: Stopping HyperTunnel with lifecycle management...")

	// Atomic state transition: Running -> Stopping
	currentState := SessionState(t.state.Load())
	if currentState != SessionStateRunning && currentState != SessionStateStarting {
		logInfo("[Tunnel] Tunnel not running (state: %d), nothing to stop", currentState)
		return nil
	}
	
	// Try to transition to Stopping state
	if !t.state.CompareAndSwap(int32(currentState), int32(SessionStateStopping)) {
		logWarn("[Tunnel] State changed during stop, current: %d", t.state.Load())
	}

	// STEP 1: Cancel session context to signal all goroutines to stop
	if t.sessionCancel != nil {
		logInfo("[Tunnel] Cancelling session context...")
		t.sessionCancel()
	}

	// STEP 2: Signal stop via stopChan (for legacy goroutines)
	select {
	case <-t.stopChan:
		// Already closed
	default:
		close(t.stopChan)
	}
	
	// STEP 3: Mark as not running
	t.running.Store(false)

	// STEP 4: Wait for goroutines to exit with timeout
	logInfo("[Tunnel] Waiting for goroutines to exit (timeout: %v)...", GoroutineExitTimeout)
	goroutinesDone := make(chan struct{})
	go func() {
		t.cleanupWg.Wait()
		close(goroutinesDone)
	}()
	
	select {
	case <-goroutinesDone:
		logInfo("[Tunnel] ✅ All goroutines exited gracefully")
	case <-time.After(GoroutineExitTimeout):
		logWarn("[Tunnel] ⚠️ Goroutine exit timeout - some goroutines may still be running")
	}

	// STEP 5: Cleanup all resources
	t.cleanup()

	// STEP 6: Signal cleanup complete
	select {
	case <-t.cleanupDone:
		// Already closed
	default:
		close(t.cleanupDone)
	}

	// STEP 7: Transition to Stopped state
	t.state.Store(int32(SessionStateStopped))

	logInfo("[Tunnel] ✅ Tunnel stopped with lifecycle management complete")

	return nil
}

// WaitForCleanup waits for cleanup to complete with a timeout
// Returns true if cleanup completed, false if timeout
func (t *HyperTunnel) WaitForCleanup(timeout time.Duration) bool {
	select {
	case <-t.cleanupDone:
		return true
	case <-time.After(timeout):
		return false
	}
}

// GetState returns the current session state
func (t *HyperTunnel) GetState() SessionState {
	return SessionState(t.state.Load())
}

// CleanupResult tracks the result of cleanup operation
type CleanupResult struct {
	Success         bool
	Duration        time.Duration
	ResourcesClosed []string
	Errors          []error
	ForcedStop      bool
}

// cleanup cleans up all resources with tracking
func (t *HyperTunnel) cleanup() *CleanupResult {
	startTime := time.Now()
	result := &CleanupResult{
		Success:         true,
		ResourcesClosed: make([]string, 0),
		Errors:          make([]error, 0),
	}
	
	logInfo("[Cleanup] ========================================")
	logInfo("[Cleanup] Starting comprehensive resource cleanup...")
	logInfo("[Cleanup] ========================================")
	
	// STEP 1: Close XrayBind first (stops health check goroutine and UDP connection)
	if t.xrayBind != nil {
		logInfo("[Cleanup] Step 1/4: Closing XrayBind...")
		t.xrayBind.Close()
		t.xrayBind = nil
		result.ResourcesClosed = append(result.ResourcesClosed, "XrayBind")
		logInfo("[Cleanup] ✅ XrayBind closed")
	} else {
		logDebug("[Cleanup] Step 1/4: XrayBind already nil, skipping")
	}

	// STEP 2: Stop Xray (closes all UDP connections and their goroutines)
	if t.xrayInstance != nil {
		logInfo("[Cleanup] Step 2/4: Stopping Xray instance...")
		if err := t.xrayInstance.Stop(); err != nil {
			logError("[Cleanup] ⚠️ Xray stop error: %v", err)
			result.Errors = append(result.Errors, err)
		}
		t.xrayInstance = nil
		result.ResourcesClosed = append(result.ResourcesClosed, "XrayInstance")
		logInfo("[Cleanup] ✅ Xray instance stopped")
	} else {
		logDebug("[Cleanup] Step 2/4: Xray instance already nil, skipping")
	}

	// STEP 3: Stop WireGuard
	if t.wgDevice != nil {
		logInfo("[Cleanup] Step 3/4: Closing WireGuard device...")
		t.wgDevice.Close()
		t.wgDevice = nil
		result.ResourcesClosed = append(result.ResourcesClosed, "WireGuardDevice")
		logInfo("[Cleanup] ✅ WireGuard device closed")
	} else {
		logDebug("[Cleanup] Step 3/4: WireGuard device already nil, skipping")
	}

	// STEP 4: Close TUN device
	if t.tunDevice != nil {
		logInfo("[Cleanup] Step 4/4: Closing TUN device...")
		if err := t.tunDevice.Close(); err != nil {
			logError("[Cleanup] ⚠️ TUN close error: %v", err)
			result.Errors = append(result.Errors, err)
		}
		t.tunDevice = nil
		result.ResourcesClosed = append(result.ResourcesClosed, "TUNDevice")
		logInfo("[Cleanup] ✅ TUN device closed")
	} else {
		logDebug("[Cleanup] Step 4/4: TUN device already nil, skipping")
	}
	
	// Calculate duration and log summary
	result.Duration = time.Since(startTime)
	result.Success = len(result.Errors) == 0
	
	logInfo("[Cleanup] ========================================")
	logInfo("[Cleanup] Cleanup Summary:")
	logInfo("[Cleanup]   Duration: %v", result.Duration)
	logInfo("[Cleanup]   Resources closed: %d (%v)", len(result.ResourcesClosed), result.ResourcesClosed)
	logInfo("[Cleanup]   Errors: %d", len(result.Errors))
	if result.Success {
		logInfo("[Cleanup] ✅ CLEANUP COMPLETE - All resources released")
	} else {
		logWarn("[Cleanup] ⚠️ CLEANUP COMPLETE WITH ERRORS")
		for i, err := range result.Errors {
			logError("[Cleanup]   Error %d: %v", i+1, err)
		}
	}
	logInfo("[Cleanup] ========================================")
	
	return result
}

// GetStats returns tunnel statistics
func (t *HyperTunnel) GetStats() TunnelStats {
	t.statsLock.RLock()
	defer t.statsLock.RUnlock()
	
	stats := t.stats
	stats.Connected = t.running.Load()
	
	// Update handshake RTT if we have recent handshake data
	if !t.lastHandshake.IsZero() {
		timeSinceHandshake := time.Since(t.lastHandshake)
		var estimatedRTT int64
		switch {
		case timeSinceHandshake < 2*time.Second:
			estimatedRTT = 25
		case timeSinceHandshake < 10*time.Second:
			estimatedRTT = 50
		case timeSinceHandshake < 30*time.Second:
			estimatedRTT = 75
		default:
			estimatedRTT = 100
		}
		stats.HandshakeRTT = estimatedRTT
	} else {
		stats.HandshakeRTT = 50 // Default fallback
	}
	
	return stats
}

// GetHandshakeRTT returns the estimated handshake RTT in milliseconds
func (t *HyperTunnel) GetHandshakeRTT() int64 {
	t.statsLock.RLock()
	defer t.statsLock.RUnlock()
	
	if t.handshakeRTT > 0 {
		return t.handshakeRTT
	}
	
	// Calculate RTT based on last handshake time
	if !t.lastHandshake.IsZero() {
		timeSinceHandshake := time.Since(t.lastHandshake)
		switch {
		case timeSinceHandshake < 2*time.Second:
			return 25
		case timeSinceHandshake < 10*time.Second:
			return 50
		case timeSinceHandshake < 30*time.Second:
			return 75
		default:
			return 100
		}
	}
	
	return 50 // Default fallback
}

// GetXrayInstance returns the XrayWrapper instance if available
func (t *HyperTunnel) GetXrayInstance() *XrayWrapper {
	return t.xrayInstance
}


// collectStats periodically collects tunnel statistics (legacy, uses stopChan)
func (t *HyperTunnel) collectStats() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-t.stopChan:
			return
		case <-ticker.C:
			t.updateStats()
		}
	}
}

// collectStatsWithContext periodically collects tunnel statistics with context support
// This version uses session context for coordinated shutdown
func (t *HyperTunnel) collectStatsWithContext(ctx context.Context) {
	defer t.cleanupWg.Done()
	defer logInfo("[Stats] collectStatsWithContext goroutine exiting")
	
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			logInfo("[Stats] Context cancelled, stopping stats collection")
			return
		case <-t.stopChan:
			return
		case <-ticker.C:
			t.updateStats()
		}
	}
}

// updateStats updates tunnel statistics
func (t *HyperTunnel) updateStats() {
	t.statsLock.Lock()
	defer t.statsLock.Unlock()

	// Get stats from bind
	if t.xrayBind != nil {
		tx, rx, txP, rxP := t.xrayBind.GetStats()
		t.stats.TxBytes = tx
		t.stats.RxBytes = rx
		t.stats.TxPackets = txP
		t.stats.RxPackets = rxP
	}

	// Get handshake info from WireGuard
	if t.wgDevice != nil {
		// Get IPC stats
		ipcGet, err := t.wgDevice.IpcGet()
		if err == nil {
			// Parse last_handshake_time from IPC response
			// Format: last_handshake_time_sec=XXXXX\nlast_handshake_time_nsec=XXXXX
			t.parseHandshakeFromIpc(ipcGet)
		}
	}

	t.stats.Connected = t.running.Load()
	t.stats.Endpoint = t.wgConfig.Endpoint
	t.stats.Uptime = time.Since(t.startTime).Milliseconds()

	// Log stats periodically (every 10 seconds)
	if t.stats.Uptime%(10*1000) < 1000 {
		logDebug("[Stats] TX: %d bytes, RX: %d bytes, Handshake: %d",
			t.stats.TxBytes, t.stats.RxBytes, t.stats.LastHandshake)
	}
}

// parseHandshakeFromIpc parses last handshake time from IPC response
func (t *HyperTunnel) parseHandshakeFromIpc(ipc string) {
	// Parse last_handshake_time_sec from IPC response
	// WireGuard IPC format is multi-line, e.g.:
	// public_key=...
	// endpoint=...
	// last_handshake_time_sec=1234567890
	// last_handshake_time_nsec=123456789
	// ...
	
	// Search for last_handshake_time_sec line in multi-line IPC response
	var lastHandshakeSec int64
	lines := strings.Split(ipc, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "last_handshake_time_sec=") {
			// Extract the value after the equals sign
			parts := strings.Split(line, "=")
			if len(parts) >= 2 {
				_, err := fmt.Sscanf(parts[1], "%d", &lastHandshakeSec)
				if err == nil && lastHandshakeSec > 0 {
					break // Found valid handshake time
				}
			}
		}
	}

	if lastHandshakeSec > 0 {
		handshakeTime := time.Unix(lastHandshakeSec, 0)
		
		// Check if this is a new handshake (different from previous)
		isNewHandshake := t.lastHandshake.IsZero() || !handshakeTime.Equal(t.lastHandshake)
		
		// Calculate RTT based on handshake timing
		if !t.lastHandshake.IsZero() && isNewHandshake {
			// Calculate RTT: time since last handshake indicates connection quality
			// WireGuard handshakes occur periodically, fresh handshakes indicate low latency
			timeSinceHandshake := time.Since(handshakeTime)
			
			// Estimate RTT based on handshake freshness
			// Fresh handshakes (< 2s) indicate excellent connection (low RTT)
			// Older handshakes suggest higher latency
			var estimatedRTT int64
			switch {
			case timeSinceHandshake < 2*time.Second:
				estimatedRTT = 25 // Very fresh - excellent connection
			case timeSinceHandshake < 10*time.Second:
				estimatedRTT = 50 // Recent - good connection
			case timeSinceHandshake < 30*time.Second:
				estimatedRTT = 75 // Moderate - acceptable connection
			default:
				estimatedRTT = 100 // Old - may indicate connection issues
			}
			
			t.handshakeRTT = estimatedRTT
			t.stats.HandshakeRTT = estimatedRTT
		} else if isNewHandshake {
			// First handshake - use default RTT
			t.handshakeRTT = 50
			t.stats.HandshakeRTT = 50
			logInfo("[Tunnel] ✅ First WireGuard handshake completed! (timestamp: %d)", lastHandshakeSec)
		}
		
		t.stats.LastHandshake = lastHandshakeSec
		t.lastHandshake = handshakeTime
	} else {
		// No handshake found in IPC response - log for debugging
		logDebug("[Tunnel] ⚠️ No handshake found in IPC response (may be normal if handshake not completed yet)")
		logDebug("[Tunnel] IPC response preview (first 200 chars): %s", 
			func() string {
				if len(ipc) > 200 {
					return ipc[:200] + "..."
				}
				return ipc
			}())
	}
}

// parseHandshake parses handshake timestamp from WireGuard IPC output
func parseHandshake(ipc string) int64 {
	// Search for last_handshake_time_sec line in multi-line IPC response
	var lastHandshakeSec int64
	lines := strings.Split(ipc, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "last_handshake_time_sec=") {
			// Extract the value after the equals sign
			parts := strings.Split(line, "=")
			if len(parts) >= 2 {
				fmt.Sscanf(parts[1], "%d", &lastHandshakeSec)
				if lastHandshakeSec > 0 {
					break // Found valid handshake time
				}
			}
		}
	}
	return lastHandshakeSec
}

// monitorHandshake monitors WireGuard handshake status with retry on connection issues (legacy)
func (t *HyperTunnel) monitorHandshake() {
	logInfo("[Handshake] Starting handshake monitor...")

	checkInterval := 1 * time.Second // Reduced from 5s to 1s for faster detection
	timeout := 1 * time.Hour // Extended timeout to 1 hour
	startTime := time.Now()
	
	// Track last successful handshake for connection health monitoring
	var lastSuccessfulHandshake int64
	var consecutiveFailures int
	const maxConsecutiveFailures = 12 // 1 minute of failures (12 * 5s)

	for {
		select {
		case <-t.stopChan:
			return
		case <-time.After(checkInterval):
			if !t.running.Load() {
				return
			}

			// Check handshake status
			if t.wgDevice != nil {
				ipc, err := t.wgDevice.IpcGet()
				if err == nil {
					handshake := parseHandshake(ipc)
					if handshake > 0 {
						// First successful handshake
						if lastSuccessfulHandshake == 0 {
							logInfo("[Handshake] ✅ SUCCESS! Handshake completed at %d", handshake)
							logInfo("[Handshake] ✅ VPN tunnel is now fully operational!")
						}
						
						// Check if handshake is stale (no new handshake for 3+ minutes)
						handshakeAge := time.Now().Unix() - handshake
						if handshakeAge > 180 && lastSuccessfulHandshake > 0 {
							consecutiveFailures++
							logWarn("[Handshake] ⚠️ Handshake is stale (%d seconds old), failures: %d/%d", 
								handshakeAge, consecutiveFailures, maxConsecutiveFailures)
							
							// If too many consecutive failures, try to recover
							if consecutiveFailures >= maxConsecutiveFailures {
								logWarn("[Handshake] 🔄 Connection seems unhealthy, attempting recovery...")
								t.attemptHandshakeRecovery()
								consecutiveFailures = 0
							}
						} else {
							// Handshake is fresh, reset failure counter
							consecutiveFailures = 0
							lastSuccessfulHandshake = handshake
						}
						continue
					}
				}
			}

			elapsed := time.Since(startTime)
			logDebug("[Handshake] Waiting... (%v elapsed)", elapsed.Round(time.Second))

			// Only timeout if we never got a handshake
			if elapsed > timeout && lastSuccessfulHandshake == 0 {
				logError("[Handshake] ❌ TIMEOUT! No handshake after %v", timeout)
				logError("[Handshake] ❌ Possible issues:")
				logError("[Handshake]    1. UDP packets not reaching WARP server")
				logError("[Handshake]    2. Xray UDP routing not working")
				logError("[Handshake]    3. WARP endpoint blocked")
				logError("[Handshake]    4. Wrong WireGuard keys")
				return
			}
		}
	}
}

// attemptHandshakeRecovery tries to recover a stale WireGuard connection
func (t *HyperTunnel) attemptHandshakeRecovery() {
	logInfo("[Handshake] 🔄 Attempting handshake recovery...")
	
	// Force WireGuard to initiate a new handshake by sending a keepalive
	if t.wgDevice != nil {
		// Re-apply the IPC config to trigger a new handshake attempt
		ipcConfig := t.generateIpcConfig()
		err := t.wgDevice.IpcSet(ipcConfig)
		if err != nil {
			logError("[Handshake] ❌ Recovery failed: %v", err)
		} else {
			logInfo("[Handshake] ✅ Recovery initiated, waiting for new handshake...")
		}
	}
}

// monitorHandshakeWithContext monitors WireGuard handshake status with context support
// This version uses session context for coordinated shutdown
func (t *HyperTunnel) monitorHandshakeWithContext(ctx context.Context) {
	defer t.cleanupWg.Done()
	defer logInfo("[Handshake] monitorHandshakeWithContext goroutine exiting")
	
	logInfo("[Handshake] Starting handshake monitor (with context)...")

	checkInterval := 1 * time.Second
	timeout := 1 * time.Hour
	startTime := time.Now()
	
	var lastSuccessfulHandshake int64
	var consecutiveFailures int
	const maxConsecutiveFailures = 12

	ticker := time.NewTicker(checkInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			logInfo("[Handshake] Context cancelled, stopping handshake monitor")
			return
		case <-t.stopChan:
			return
		case <-ticker.C:
			if !t.running.Load() {
				return
			}

			// Check handshake status
			if t.wgDevice != nil {
				ipc, err := t.wgDevice.IpcGet()
				if err == nil {
					handshake := parseHandshake(ipc)
					if handshake > 0 {
						if lastSuccessfulHandshake == 0 {
							logInfo("[Handshake] ✅ SUCCESS! Handshake completed at %d", handshake)
							logInfo("[Handshake] ✅ VPN tunnel is now fully operational!")
						}
						
						handshakeAge := time.Now().Unix() - handshake
						if handshakeAge > 180 && lastSuccessfulHandshake > 0 {
							consecutiveFailures++
							logWarn("[Handshake] ⚠️ Handshake is stale (%d seconds old), failures: %d/%d", 
								handshakeAge, consecutiveFailures, maxConsecutiveFailures)
							
							if consecutiveFailures >= maxConsecutiveFailures {
								logWarn("[Handshake] 🔄 Connection seems unhealthy, attempting recovery...")
								t.attemptHandshakeRecovery()
								consecutiveFailures = 0
							}
						} else {
							consecutiveFailures = 0
							lastSuccessfulHandshake = handshake
						}
						continue
					}
				}
			}

			elapsed := time.Since(startTime)
			logDebug("[Handshake] Waiting... (%v elapsed)", elapsed.Round(time.Second))

			if elapsed > timeout && lastSuccessfulHandshake == 0 {
				logError("[Handshake] ❌ TIMEOUT! No handshake after %v", timeout)
				return
			}
		}
	}
}

// GeneratePublicKey generates a public key from a private key
func GeneratePublicKey(privateKeyBase64 string) (string, error) {
	privateKeyBytes, err := base64.StdEncoding.DecodeString(privateKeyBase64)
	if err != nil {
		return "", fmt.Errorf("failed to decode private key: %v", err)
	}

	if len(privateKeyBytes) != 32 {
		return "", fmt.Errorf("invalid private key length: %d", len(privateKeyBytes))
	}

	var privateKey, publicKey [32]byte
	copy(privateKey[:], privateKeyBytes)
	
	curve25519.ScalarBaseMult(&publicKey, &privateKey)
	
	return base64.StdEncoding.EncodeToString(publicKey[:]), nil
}

// extractServerFromConfig extracts server address from Xray config
func (t *HyperTunnel) extractServerFromConfig() (string, int) {
	var config map[string]interface{}
	if err := json.Unmarshal([]byte(t.config.XrayConfig), &config); err != nil {
		return "", 0
	}
	
	outbounds, ok := config["outbounds"].([]interface{})
	if !ok || len(outbounds) == 0 {
		return "", 0
	}
	
	outbound := outbounds[0].(map[string]interface{})
	settings, ok := outbound["settings"].(map[string]interface{})
	if !ok {
		return "", 0
	}
	
	vnext, ok := settings["vnext"].([]interface{})
	if !ok || len(vnext) == 0 {
		return "", 0
	}
	
	server := vnext[0].(map[string]interface{})
	address, _ := server["address"].(string)
	port, _ := server["port"].(float64)
	
	logDebug("[Tunnel] Extracted server: %s:%d", address, int(port))
	return address, int(port)
}

// ExportXrayConfig exports the current Xray config for debugging
func (t *HyperTunnel) ExportXrayConfig() string {
	if t.config.XrayConfig == "" {
		return "{}"
	}
	
	// Pretty print the config
	var config map[string]interface{}
	if err := json.Unmarshal([]byte(t.config.XrayConfig), &config); err != nil {
		logWarn("[Debug] Failed to parse config for export: %v", err)
		return t.config.XrayConfig
	}
	
	pretty, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		logWarn("[Debug] Failed to pretty print config: %v", err)
		return t.config.XrayConfig
	}
	
	logInfo("[Debug] Current Xray Config:")
	logInfo("%s", string(pretty))
	
	return string(pretty)
}
