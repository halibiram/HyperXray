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
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
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
			return nil, fmt.Errorf("failed to create Xray instance: %w", err)
		} else {
			logInfo("[Tunnel] ✅ Xray instance created")
		}
	} else {
		logError("[Tunnel] ❌ XrayConfig is empty or invalid: '%s' (length: %d)", config.XrayConfig, len(config.XrayConfig))
		return nil, fmt.Errorf("XrayConfig is empty or invalid (must not be empty or '{}')")
	}

	// Create tunnel instance
	tunnel := &HyperTunnel{
		config:       config,
		wgConfig:     &wgConfig,
		tunDevice:    tunDev,
		xrayInstance: xrayInst,
		stopChan:     make(chan struct{}),
	}

	logInfo("HyperTunnel instance created successfully")
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
	if t.running.Load() {
		return fmt.Errorf("already running")
	}
	
	logInfo("[Tunnel] ========================================")
	logInfo("[Tunnel] Starting HyperTunnel with Diagnostics")
	logInfo("[Tunnel] ========================================")
	
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
	connectivityErr := TestConnectivity()
	if connectivityErr != nil {
		logError("[Tunnel] ")
		logError("[Tunnel] ❌ Protected Dialer connectivity test FAILED!")
		logError("[Tunnel]    Error: %v", connectivityErr)
		logError("[Tunnel] ")
		logError("[Tunnel] This indicates:")
		logError("[Tunnel]    1. Protected Dialer may be reporting SUCCESS but blocking traffic")
		logError("[Tunnel]    2. Socket protection is not binding to correct network interface")
		logError("[Tunnel]    3. Firewall is blocking outbound connections")
		logError("[Tunnel]    4. Network connectivity issue")
		logError("[Tunnel] ")
		logError("[Tunnel] ⚠️ Cannot proceed - Protected Dialer is not working!")
		logError("[Tunnel] ⚠️ Xray will not be able to connect to server.")
		logError("[Tunnel] ")
		// Don't fail - continue anyway, but log the issue
		logWarn("[Tunnel] ⚠️ Continuing despite Protected Dialer test failure (may cause issues)")
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
	
	udpTestErr := t.xrayInstance.TestUDPConnectivity(t.wgConfig.Endpoint)
	if udpTestErr != nil {
		logWarn("[Tunnel] ⚠️ UDP test warning: %v", udpTestErr)
		logWarn("[Tunnel] ⚠️ Will proceed but WireGuard may have issues")
		// Don't fail - WireGuard might still work
	} else {
		logInfo("[Tunnel] ✅ UDP path verified!")
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
	
	go t.collectStats()
	go t.monitorHandshake() // NEW: Monitor for handshake success
	
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] ========================================")
	logInfo("[Tunnel] ✅✅✅ TUNNEL FULLY STARTED! ✅✅✅")
	logInfo("[Tunnel] ========================================")
	logInfo("[Tunnel] ")
	logInfo("[Tunnel] Now waiting for WireGuard handshake...")
	logInfo("[Tunnel] If handshake fails after 30s, there may be")
	logInfo("[Tunnel] an issue with UDP routing through Xray.")
	
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

// maskPrivateKey masks private key in log output
func maskPrivateKey(config string) string {
	// Simple masking - replace private_key value
	return config // In production, actually mask it
}

// Stop stops the tunnel
func (t *HyperTunnel) Stop() error {
	logInfo("[Tunnel] Stopping HyperTunnel...")

	if !t.running.Load() {
		return fmt.Errorf("tunnel not running")
	}

	// Signal stop
	close(t.stopChan)

	t.cleanup()

	t.running.Store(false)

	logInfo("[Tunnel] ✅ Tunnel stopped")

	return nil
}

// cleanup cleans up all resources
func (t *HyperTunnel) cleanup() {
	// Stop WireGuard
	if t.wgDevice != nil {
		t.wgDevice.Close()
		t.wgDevice = nil
	}

	// Stop Xray
	if t.xrayInstance != nil {
		t.xrayInstance.Stop()
		t.xrayInstance = nil
	}

	// Close TUN
	if t.tunDevice != nil {
		t.tunDevice.Close()
		t.tunDevice = nil
	}

	// Close bind
	if t.xrayBind != nil {
		t.xrayBind.Close()
		t.xrayBind = nil
	}
}

// GetStats returns tunnel statistics
func (t *HyperTunnel) GetStats() TunnelStats {
	t.statsLock.RLock()
	defer t.statsLock.RUnlock()
	
	stats := t.stats
	stats.Connected = t.running.Load()
	
	return stats
}


// collectStats periodically collects tunnel statistics
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
	// This is a simplified parser
	var lastHandshakeSec int64
	fmt.Sscanf(ipc, "last_handshake_time_sec=%d", &lastHandshakeSec)

	if lastHandshakeSec > 0 {
		t.stats.LastHandshake = lastHandshakeSec
		if t.lastHandshake.IsZero() {
			logInfo("[Tunnel] ✅ First WireGuard handshake completed!")
		}
		t.lastHandshake = time.Unix(lastHandshakeSec, 0)
	}
}

// parseHandshake parses handshake timestamp from WireGuard IPC output
func parseHandshake(ipc string) int64 {
	var lastHandshakeSec int64
	fmt.Sscanf(ipc, "last_handshake_time_sec=%d", &lastHandshakeSec)
	return lastHandshakeSec
}

// monitorHandshake monitors WireGuard handshake status
func (t *HyperTunnel) monitorHandshake() {
	logInfo("[Handshake] Starting handshake monitor...")

	checkInterval := 5 * time.Second
	timeout := 60 * time.Second
	startTime := time.Now()

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
						logInfo("[Handshake] ✅ SUCCESS! Handshake completed at %d", handshake)
						logInfo("[Handshake] ✅ VPN tunnel is now fully operational!")
						return
					}
				}
			}

			elapsed := time.Since(startTime)
			logDebug("[Handshake] Waiting... (%v elapsed)", elapsed.Round(time.Second))

			if elapsed > timeout {
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
