package main

/*
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <android/log.h>
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

// Socket protector callback type
typedef bool (*socket_protector_func)(int fd);

// Global protector function pointer
// CRITICAL: Define here (not extern) so Go library has its own definition
// This will be set by libhyperxray-jni.so via SetSocketProtector
// We use a weak symbol so it can be overridden by libhyperxray-jni.so
socket_protector_func g_protector __attribute__((weak)) = NULL;

// Set the protector function
static void set_protector(socket_protector_func func) {
    g_protector = func;
}

// Call the protector (used by Go)
static bool call_protector(int fd) {
    if (g_protector != NULL) {
        return g_protector(fd);
    }
    return false;
}
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"unsafe"

	"github.com/hyperxray/native/bridge"
	"github.com/hyperxray/native/dns"
	// REMOVED: "github.com/hyperxray/native/xray" - MultiInstanceManager removed
)

var (
	tunnel     *bridge.HyperTunnel
	tunnelLock sync.Mutex
	lastError  string
	
	// REMOVED: Multi-instance manager
	// multiManager and multiManagerLock removed as part of architectural cleanup
	
	// Counter for GetXrayLogs diagnostic logging
	getXrayLogsCallCount int
	getXrayLogsCallCountMu sync.Mutex
)

// Error codes
const (
	ErrorSuccess              = 0
	ErrorTunnelCreationFailed = -1
	ErrorTunnelStartFailed    = -2
	ErrorInvalidTunFd         = -3
	ErrorInvalidWgConfig      = -4
	ErrorInvalidXrayConfig    = -5
	ErrorTunnelAlreadyRunning  = -6
	ErrorTunnelNotRunning     = -7
	ErrorXrayConnectivityFailed = -20
	ErrorXrayServerUnreachable  = -21
	ErrorXrayTLSFailed          = -22
	ErrorPanic                = -99
)

const logTag = "HyperXray-Go"

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
	lastError = msg
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

// WireGuardConfig represents WireGuard configuration
type WireGuardConfig struct {
	PrivateKey string `json:"privateKey"`
	PublicKey  string `json:"publicKey"`
	Address    string `json:"address"`
	AddressV6  string `json:"addressV6,omitempty"`
	DNS        string `json:"dns"`
	MTU        int    `json:"mtu"`
	Endpoint   string `json:"endpoint"`
	PeerPublicKey string `json:"peerPublicKey"`
	AllowedIPs string `json:"allowedIPs"`
	PersistentKeepalive int `json:"persistentKeepalive,omitempty"`
}

// XrayConfig represents Xray configuration (simplified)
type XrayConfig struct {
	Inbounds  []interface{} `json:"inbounds"`
	Outbounds []interface{} `json:"outbounds"`
	Routing   interface{}   `json:"routing,omitempty"`
	Log       interface{}   `json:"log,omitempty"`
}

// validateWireGuardConfig validates WireGuard configuration
func validateWireGuardConfig(jsonStr string) (*WireGuardConfig, error) {
	if jsonStr == "" {
		return nil, fmt.Errorf("WireGuard config is empty")
	}

	var config WireGuardConfig
	if err := json.Unmarshal([]byte(jsonStr), &config); err != nil {
		return nil, fmt.Errorf("failed to parse WireGuard config: %v", err)
	}

	// Validate required fields
	if config.PrivateKey == "" {
		return nil, fmt.Errorf("WireGuard privateKey is missing")
	}
	if config.Endpoint == "" {
		return nil, fmt.Errorf("WireGuard endpoint is missing")
	}
	if config.PeerPublicKey == "" {
		return nil, fmt.Errorf("WireGuard peerPublicKey is missing")
	}
	if config.Address == "" {
		return nil, fmt.Errorf("WireGuard address is missing")
	}

	logDebug("WireGuard config validated: endpoint=%s, address=%s", config.Endpoint, config.Address)
	return &config, nil
}

// validateXrayConfig validates Xray configuration
func validateXrayConfig(jsonStr string) (*XrayConfig, error) {
	if jsonStr == "" {
		return nil, fmt.Errorf("Xray config is empty")
	}

	var config XrayConfig
	if err := json.Unmarshal([]byte(jsonStr), &config); err != nil {
		return nil, fmt.Errorf("failed to parse Xray config: %v", err)
	}

	// Validate required fields
	if len(config.Outbounds) == 0 {
		return nil, fmt.Errorf("Xray outbounds is empty")
	}

	logDebug("Xray config validated: %d inbounds, %d outbounds", len(config.Inbounds), len(config.Outbounds))
	return &config, nil
}

//export StartHyperTunnel
func StartHyperTunnel(tunFd C.int, wgConfigJSON, xrayConfigJSON, warpEndpoint, warpPrivateKey, nativeLibDir, filesDir *C.char) C.int {
	// Recover from panics with proper error handling and cleanup
	defer func() {
		if r := recover(); r != nil {
			stackTrace := string(debug.Stack())
			logError("PANIC in StartHyperTunnel: %v\n%s", r, stackTrace)
			lastError = fmt.Sprintf("panic: %v", r)
			// Ensure tunnel is cleaned up on panic to prevent resource leaks
			tunnelLock.Lock()
			defer tunnelLock.Unlock()
			if tunnel != nil {
				logError("Cleaning up tunnel due to panic")
				tunnel.Stop()
				tunnel = nil
			}
		}
	}()

	logInfo("========================================")
	logInfo("StartHyperTunnel called")
	logInfo("========================================")

	// Verify protector is set
	if C.g_protector == nil {
		logError("Socket protector not set! Call SetSocketProtector first!")
		return -30 // New error code
	}

	// Lock to prevent concurrent access
	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	// Check if tunnel already running
	// If tunnel exists, stop it first to allow restart
	if tunnel != nil {
		logInfo("Tunnel already exists, stopping it first to allow restart...")
		tunnel.Stop()
		tunnel = nil
		logInfo("Previous tunnel stopped, proceeding with new tunnel creation")
	}

	// Validate TUN file descriptor
	tunFdInt := int(tunFd)
	logDebug("TUN file descriptor: %d", tunFdInt)
	
	if tunFdInt < 0 {
		logError("Invalid TUN file descriptor: %d", tunFdInt)
		return ErrorInvalidTunFd
	}

	// Verify TUN fd is valid
	if _, err := os.Stat(fmt.Sprintf("/proc/self/fd/%d", tunFdInt)); err != nil {
		logError("TUN file descriptor %d is not valid: %v", tunFdInt, err)
		return ErrorInvalidTunFd
	}
	logDebug("TUN file descriptor is valid")

	// Convert C strings to Go strings
	wgConfig := C.GoString(wgConfigJSON)
	xrayConfig := C.GoString(xrayConfigJSON)
	endpoint := C.GoString(warpEndpoint)
	privateKey := C.GoString(warpPrivateKey)

	logDebug("WireGuard config length: %d bytes", len(wgConfig))
	logDebug("Xray config length: %d bytes", len(xrayConfig))
	logDebug("WARP endpoint: %s", endpoint)
	logDebug("WARP private key length: %d", len(privateKey))

	// Early validation: Check for empty or null configs
	// This prevents unnecessary processing and provides clear error messages
	if wgConfig == "" || wgConfig == "{}" || wgConfig == "null" {
		logError("WireGuard config is empty, null, or invalid JSON object")
		logError("WireGuard config value: '%s'", wgConfig)
		return ErrorInvalidWgConfig
	}

	if xrayConfig == "" || xrayConfig == "{}" || xrayConfig == "null" {
		logError("Xray config is empty, null, or invalid JSON object")
		logError("Xray config value: '%s'", xrayConfig)
		return ErrorInvalidXrayConfig
	}

	logDebug("Configs are not empty, proceeding with validation...")

	// Validate WireGuard config
	logInfo("Validating WireGuard configuration...")
	wgCfg, err := validateWireGuardConfig(wgConfig)
	if err != nil {
		logError("WireGuard config validation failed: %v", err)
		logError("WireGuard config (first 500 chars): %.500s", wgConfig)
		return ErrorInvalidWgConfig
	}
	logInfo("WireGuard config valid")

	// Validate Xray config
	logInfo("Validating Xray configuration...")
	_, err = validateXrayConfig(xrayConfig)
	if err != nil {
		logError("Xray config validation failed: %v", err)
		logError("Xray config (first 500 chars): %.500s", xrayConfig)
		return ErrorInvalidXrayConfig
	}
	logInfo("Xray config valid")

	// Get native library and files directories with null pointer checks
	logDebug("Checking native library and files directories...")
	
	var nativeDir string
	var filesDirPath string
	
	if nativeLibDir == nil {
		logError("nativeLibDir parameter is NULL - this will cause a crash!")
		lastError = "nativeLibDir is null"
		return ErrorTunnelCreationFailed
	}
	nativeDir = C.GoString(nativeLibDir)
	logDebug("Native lib dir converted: %s", nativeDir)
	
	if filesDir == nil {
		logError("filesDir parameter is NULL - this will cause a crash!")
		lastError = "filesDir is null"
		return ErrorTunnelCreationFailed
	}
	filesDirPath = C.GoString(filesDir)
	logDebug("Files dir converted: %s", filesDirPath)
	
	logDebug("Native lib dir: %s, Files dir: %s", nativeDir, filesDirPath)
	
	// Validate directories are not empty
	if nativeDir == "" {
		logError("Native lib dir is empty after conversion")
		lastError = "nativeLibDir is empty"
		return ErrorTunnelCreationFailed
	}
	if filesDirPath == "" {
		logError("Files dir is empty after conversion")
		lastError = "filesDir is empty"
		return ErrorTunnelCreationFailed
	}

	// Create tunnel configuration
	logInfo("Creating HyperTunnel instance...")
	
	tunnelConfig := bridge.TunnelConfig{
		TunFd:          tunFdInt,
		WgConfig:       wgConfig,
		XrayConfig:     xrayConfig,
		WarpEndpoint:   endpoint,
		WarpPrivateKey: privateKey,
		MTU:            wgCfg.MTU,
		NativeLibDir:   nativeDir,
		FilesDir:       filesDirPath,
	}

	// If MTU is 0, use default
	if tunnelConfig.MTU == 0 {
		tunnelConfig.MTU = 1280
		logDebug("Using default MTU: 1280")
	}

	// Create tunnel instance
	var createErr error
	tunnel, createErr = bridge.NewHyperTunnel(tunnelConfig)
	if createErr != nil {
		logError("Failed to create HyperTunnel: %v", createErr)
		tunnel = nil
		return ErrorTunnelCreationFailed
	}
	logInfo("HyperTunnel instance created successfully")

	// Start tunnel
	logInfo("Starting tunnel...")
	if startErr := tunnel.Start(); startErr != nil {
		errStr := startErr.Error()
		
		// Categorize error for Kotlin
		if strings.Contains(errStr, "connectivity") || strings.Contains(errStr, "connectivity check failed") {
			lastError = "XRAY_CONNECTIVITY_FAILED: " + errStr
			logError("Xray cannot reach internet - check server config")
			tunnel.Stop()
			tunnel = nil
			return ErrorXrayConnectivityFailed
		}
		
		if strings.Contains(errStr, "server unreachable") || strings.Contains(errStr, "cannot reach server") {
			lastError = "XRAY_SERVER_UNREACHABLE: " + errStr
			logError("Cannot connect to Xray server")
			tunnel.Stop()
			tunnel = nil
			return ErrorXrayServerUnreachable
		}
		
		if strings.Contains(errStr, "TLS") || strings.Contains(errStr, "handshake") {
			lastError = "XRAY_TLS_FAILED: " + errStr
			logError("TLS handshake with Xray server failed")
			tunnel.Stop()
			tunnel = nil
			return ErrorXrayTLSFailed
		}
		
		lastError = "Start tunnel: " + errStr
		logError(lastError)
		tunnel.Stop()
		tunnel = nil
		return ErrorTunnelStartFailed
	}

	logInfo("========================================")
	logInfo("Tunnel started successfully!")
	logInfo("========================================")
	
	return ErrorSuccess
}

//export StopHyperTunnel
func StopHyperTunnel() C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in StopHyperTunnel: %v\n%s", r, debug.Stack())
		}
	}()

	logInfo("StopHyperTunnel called")

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	if tunnel == nil {
		logError("No tunnel running")
		return ErrorTunnelNotRunning
	}

	logInfo("Stopping tunnel...")
	tunnel.Stop()
	tunnel = nil
	
	// Save DNS cache to disk on tunnel stop for persistence
	logInfo("Saving DNS cache to disk...")
	cacheManager := dns.GetCacheManager()
	cacheManager.SaveToDisk("")
	
	logInfo("Tunnel stopped successfully")
	return ErrorSuccess
}

//export GetTunnelStats
func GetTunnelStats() *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in GetTunnelStats: %v", r)
		}
	}()

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	if tunnel == nil {
		return C.CString(`{"connected":false,"error":"no tunnel running"}`)
	}

	stats := tunnel.GetStats()
	jsonBytes, err := json.Marshal(stats)
	if err != nil {
		return C.CString(`{"connected":false,"error":"failed to marshal stats"}`)
	}

	return C.CString(string(jsonBytes))
}

//export GetHandshakeRTT
func GetHandshakeRTT() C.longlong {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in GetHandshakeRTT: %v", r)
		}
	}()

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	if tunnel == nil {
		return 50 // Default fallback
	}

	rtt := tunnel.GetHandshakeRTT()
	return C.longlong(rtt)
}

//export GetLastError
func GetLastError() *C.char {
	return C.CString(lastError)
}

//export GetXrayConfig
func GetXrayConfig() *C.char {
	tunnelLock.Lock()
	defer tunnelLock.Unlock()
	
	if tunnel == nil {
		return C.CString("{}")
	}
	
	return C.CString(tunnel.ExportXrayConfig())
}

//export IsXrayGrpcAvailable
func IsXrayGrpcAvailable() C.bool {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in IsXrayGrpcAvailable: %v", r)
		}
	}()

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	// Detailed logging to diagnose which check fails
	if tunnel == nil {
		logDebug("[IsXrayGrpcAvailable] ❌ tunnel is nil - returning false")
		return C.bool(false)
	}
	logDebug("[IsXrayGrpcAvailable] ✅ tunnel exists, checking xrayInstance...")

	xrayInstance := tunnel.GetXrayInstance()
	if xrayInstance == nil {
		logError("[IsXrayGrpcAvailable] ❌ xrayInstance is nil - XrayWrapper was not created or was destroyed")
		logError("[IsXrayGrpcAvailable] This usually means Xray-core failed to start or was stopped")
		return C.bool(false)
	}
	logDebug("[IsXrayGrpcAvailable] ✅ xrayInstance exists, checking grpcClient...")

	// Check if Xray instance is running
	if !xrayInstance.IsRunning() {
		logError("[IsXrayGrpcAvailable] ❌ xrayInstance is not running - Xray-core may have crashed or stopped")
		return C.bool(false)
	}
	logDebug("[IsXrayGrpcAvailable] ✅ xrayInstance is running, checking grpcClient...")

	grpcClient := xrayInstance.GetGrpcClient()
	if grpcClient == nil {
		logError("[IsXrayGrpcAvailable] ❌ grpcClient is nil - gRPC client was not created or failed to initialize")
		logError("[IsXrayGrpcAvailable] Possible causes:")
		logError("[IsXrayGrpcAvailable]   1. API port not configured in Xray config")
		logError("[IsXrayGrpcAvailable]   2. gRPC client creation failed during Xray startup")
		logError("[IsXrayGrpcAvailable]   3. gRPC service not ready yet (timing issue)")
		return C.bool(false)
	}

	logInfo("[IsXrayGrpcAvailable] ✅ All checks passed - tunnel, xrayInstance, and grpcClient all exist - returning true")
	return C.bool(true)
}

//export GetXraySystemStats
func GetXraySystemStats() *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in GetXraySystemStats: %v", r)
		}
	}()

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	if tunnel == nil {
		return C.CString(`{"error":"no tunnel running"}`)
	}

	xrayInstance := tunnel.GetXrayInstance()
	if xrayInstance == nil {
		return C.CString(`{"error":"xray instance not available"}`)
	}

	grpcClient := xrayInstance.GetGrpcClient()
	if grpcClient == nil {
		logError("GetXraySystemStats: gRPC client is nil")
		return C.CString(`{"error":"gRPC client not available"}`)
	}

	logDebug("GetXraySystemStats: Calling grpcClient.GetSystemStats()...")
	stats, err := grpcClient.GetSystemStats()
	if err != nil {
		logError("GetXraySystemStats: Failed to get system stats: %v", err)
		// Return detailed error for debugging
		return C.CString(fmt.Sprintf(`{"error":"failed to get stats: %v"}`, err))
	}

	if stats == nil {
		logError("GetXraySystemStats: GetSystemStats returned nil response")
		return C.CString(`{"error":"stats response is nil"}`)
	}

	logDebug("GetXraySystemStats: Successfully retrieved stats: uptime=%d, goroutines=%d", stats.Uptime, stats.NumGoroutine)

	// Convert to JSON
	result := map[string]interface{}{
		"numGoroutine": stats.NumGoroutine,
		"numGC":        stats.NumGC,
		"alloc":        stats.Alloc,
		"totalAlloc":   stats.TotalAlloc,
		"sys":          stats.Sys,
		"mallocs":      stats.Mallocs,
		"frees":        stats.Frees,
		"liveObjects":  stats.LiveObjects,
		"pauseTotalNs": stats.PauseTotalNs,
		"uptime":       stats.Uptime,
	}

	jsonBytes, err := json.Marshal(result)
	if err != nil {
		logError("Failed to marshal system stats: %v", err)
		return C.CString(`{"error":"failed to marshal stats"}`)
	}

	return C.CString(string(jsonBytes))
}

//export GetXrayTrafficStats
func GetXrayTrafficStats() *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in GetXrayTrafficStats: %v", r)
		}
	}()

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	if tunnel == nil {
		return C.CString(`{"error":"no tunnel running"}`)
	}

	xrayInstance := tunnel.GetXrayInstance()
	if xrayInstance == nil {
		return C.CString(`{"error":"xray instance not available"}`)
	}

	grpcClient := xrayInstance.GetGrpcClient()
	if grpcClient == nil {
		logError("GetXrayTrafficStats: gRPC client is nil")
		return C.CString(`{"error":"gRPC client not available"}`)
	}

	logDebug("GetXrayTrafficStats: Calling grpcClient.QueryTrafficStats()...")
	uplink, downlink, err := grpcClient.QueryTrafficStats()
	if err != nil {
		logError("GetXrayTrafficStats: Failed to get traffic stats: %v", err)
		// Return detailed error for debugging
		return C.CString(fmt.Sprintf(`{"error":"failed to get traffic stats: %v"}`, err))
	}

	logDebug("GetXrayTrafficStats: Successfully retrieved traffic stats: uplink=%d, downlink=%d", uplink, downlink)

	// Convert to JSON
	result := map[string]interface{}{
		"uplink":   uplink,
		"downlink": downlink,
	}

	jsonBytes, err := json.Marshal(result)
	if err != nil {
		logError("Failed to marshal traffic stats: %v", err)
		return C.CString(`{"error":"failed to marshal stats"}`)
	}

	return C.CString(string(jsonBytes))
}

//export NativeGeneratePublicKey
func NativeGeneratePublicKey(privateKeyBase64 *C.char) *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in NativeGeneratePublicKey: %v", r)
		}
	}()

	privateKey := C.GoString(privateKeyBase64)
	if privateKey == "" {
		return C.CString("")
	}

	// Use WireGuard's curve25519 to generate public key
	publicKey, err := bridge.GeneratePublicKey(privateKey)
	if err != nil {
		logError("Failed to generate public key: %v", err)
		return C.CString("")
	}

	return C.CString(publicKey)
}

//export FreeString
func FreeString(str *C.char) {
	if str != nil {
		C.free(unsafe.Pointer(str))
	}
}

// Go callback that calls back to C/JNI
func goSocketProtector(fd int) bool {
	result := C.call_protector(C.int(fd))
	return bool(result)
}

//export SetSocketProtector
func SetSocketProtector(protector C.socket_protector_func) {
	C.set_protector(protector)
	bridge.SetSocketProtector(goSocketProtector)
	logInfo("[JNI] Socket protector registered")
}

// ============================================================================
// REMOVED: Multi-Instance Management Functions
// ============================================================================
// 
// All multi-instance management functions have been removed as part of
// architectural cleanup. Xray-core is now managed directly through
// startHyperTunnel() which embeds Xray-core.
//
// Removed functions:
// - InitMultiInstanceManager
// - StartMultiInstances
// - StopMultiInstance
// - StopAllMultiInstances
// - GetMultiInstanceStatus
// - GetAllMultiInstancesStatus
// - GetMultiInstanceCount
// - IsMultiInstanceRunning

// ============================================================================
// DNS Cache Management Functions
// ============================================================================

//export InitDNSCache
func InitDNSCache(cacheDir *C.char) C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in InitDNSCache: %v\n%s", r, debug.Stack())
		}
	}()

	dir := C.GoString(cacheDir)
	logInfo("InitDNSCache: cacheDir=%s", dir)

	cacheManager := dns.GetCacheManager()
	if err := cacheManager.Initialize(dir); err != nil {
		logError("Failed to initialize DNS cache: %v", err)
		return -1
	}

	logInfo("DNS cache initialized successfully")
	return ErrorSuccess
}

//export DNSCacheLookup
func DNSCacheLookup(hostname *C.char) *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheLookup: %v", r)
		}
	}()

	host := C.GoString(hostname)
	cacheManager := dns.GetCacheManager()

	ips, found := cacheManager.Lookup(host)
	if !found || len(ips) == 0 {
		return C.CString("")
	}

	// Return first IP
	return C.CString(ips[0].String())
}

//export DNSCacheLookupAll
func DNSCacheLookupAll(hostname *C.char) *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheLookupAll: %v", r)
		}
	}()

	host := C.GoString(hostname)
	cacheManager := dns.GetCacheManager()

	ips, found := cacheManager.Lookup(host)
	if !found || len(ips) == 0 {
		return C.CString("[]")
	}

	var ipStrings []string
	for _, ip := range ips {
		ipStrings = append(ipStrings, ip.String())
	}

	jsonBytes, _ := json.Marshal(ipStrings)
	return C.CString(string(jsonBytes))
}

//export DNSCacheSave
func DNSCacheSave(hostname *C.char, ipsJSON *C.char, ttl C.long) {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheSave: %v", r)
		}
	}()

	host := C.GoString(hostname)
	ipsStr := C.GoString(ipsJSON)

	var ipStrings []string
	json.Unmarshal([]byte(ipsStr), &ipStrings)

	cacheManager := dns.GetCacheManager()
	cacheManager.SaveFromStrings(host, ipStrings, int64(ttl))
}

//export DNSCacheGetMetrics
func DNSCacheGetMetrics() *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheGetMetrics: %v", r)
		}
	}()

	cacheManager := dns.GetCacheManager()
	return C.CString(cacheManager.GetMetricsJSON())
}

//export DNSCacheClear
func DNSCacheClear() {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheClear: %v", r)
		}
	}()

	cacheManager := dns.GetCacheManager()
	cacheManager.Clear()
	logInfo("DNS cache cleared")
}

//export DNSCacheCleanupExpired
func DNSCacheCleanupExpired() C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheCleanupExpired: %v", r)
		}
	}()

	cacheManager := dns.GetCacheManager()
	removed := cacheManager.CleanupExpired()
	return C.int(removed)
}

//export DNSCacheSaveToDisk
func DNSCacheSaveToDisk() C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheSaveToDisk: %v", r)
		}
	}()

	cacheManager := dns.GetCacheManager()
	cacheManager.SaveToDisk("")
	logInfo("DNS cache saved to disk (explicit call)")
	return ErrorSuccess
}

//export DNSCacheShutdown
func DNSCacheShutdown() C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSCacheShutdown: %v", r)
		}
	}()

	cacheManager := dns.GetCacheManager()
	cacheManager.Shutdown()
	logInfo("DNS cache shutdown complete")
	return ErrorSuccess
}

// ============================================================================
// DNS Server Functions
// ============================================================================

//export StartDNSServer
func StartDNSServer(port C.int, upstreamDNS *C.char) C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in StartDNSServer: %v\n%s", r, debug.Stack())
		}
	}()

	upstream := C.GoString(upstreamDNS)
	if upstream == "" {
		upstream = "1.1.1.1:53"
	}

	server := dns.GetDNSServer()
	server.SetUpstreamDNS(upstream)
	server.SetLogCallback(func(msg string) {
		logInfo("[DNS] %s", msg)
	})

	if err := server.Start(int(port)); err != nil {
		logError("Failed to start DNS server: %v", err)
		return -1
	}

	logInfo("DNS server started on port %d with upstream %s", server.GetListeningPort(), upstream)
	return C.int(server.GetListeningPort())
}

//export StopDNSServer
func StopDNSServer() C.int {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in StopDNSServer: %v", r)
		}
	}()

	server := dns.GetDNSServer()
	server.Stop()

	logInfo("DNS server stopped")
	return ErrorSuccess
}

//export IsDNSServerRunning
func IsDNSServerRunning() C.int {
	server := dns.GetDNSServer()
	if server.IsRunning() {
		return 1
	}
	return 0
}

//export GetDNSServerPort
func GetDNSServerPort() C.int {
	server := dns.GetDNSServer()
	return C.int(server.GetListeningPort())
}

//export GetDNSServerStats
func GetDNSServerStats() *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in GetDNSServerStats: %v", r)
		}
	}()

	server := dns.GetDNSServer()
	stats := server.GetStats()

	jsonBytes, err := json.Marshal(stats)
	if err != nil {
		return C.CString("{}")
	}

	return C.CString(string(jsonBytes))
}

//export DNSResolve
func DNSResolve(hostname *C.char) *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in DNSResolve: %v", r)
		}
	}()

	host := C.GoString(hostname)
	server := dns.GetDNSServer()

	ips, err := server.Resolve(host)
	if err != nil || len(ips) == 0 {
		return C.CString("")
	}

	var ipStrings []string
	for _, ip := range ips {
		ipStrings = append(ipStrings, ip.String())
	}

	jsonBytes, _ := json.Marshal(ipStrings)
	return C.CString(string(jsonBytes))
}

//export GetXrayLogs
func GetXrayLogs(maxCount C.int) *C.char {
	defer func() {
		if r := recover(); r != nil {
			logError("PANIC in GetXrayLogs: %v", r)
		}
	}()

	tunnelLock.Lock()
	defer tunnelLock.Unlock()

	if tunnel == nil {
		// Log periodically to verify polling is working
		getXrayLogsCallCountMu.Lock()
		getXrayLogsCallCount++
		callCount := getXrayLogsCallCount
		getXrayLogsCallCountMu.Unlock()
		
		if callCount%20 == 0 { // Log every 20th call
			logDebug("[GetXrayLogs] No tunnel running (call #%d) - waiting for tunnel to start", callCount)
		}
		return C.CString(`{"logs":[],"error":"no tunnel running"}`)
	}

	// Verify Xray instance exists
	xrayInstance := tunnel.GetXrayInstance()
	if xrayInstance == nil {
		logError("[GetXrayLogs] ❌ Xray instance is nil - Xray-core may not be running")
		return C.CString(`{"logs":[],"error":"xray instance not available"}`)
	}

	// Verify Xray instance is running
	if !xrayInstance.IsRunning() {
		logError("[GetXrayLogs] ❌ Xray instance is not running - Xray-core may have crashed")
		return C.CString(`{"logs":[],"error":"xray instance not running"}`)
	}

	// Collect logs from channel (non-blocking, up to maxCount)
	logs := make([]string, 0, int(maxCount))
	count := 0
	max := int(maxCount)
	if max <= 0 {
		max = 100 // Default to 100 logs
	}
	if max > 1000 {
		max = 1000 // Cap at 1000 logs
	}

	bridge.XrayLogChannelMu.Lock()
	channelClosed := bridge.XrayLogChannelClosed
	channelLen := len(bridge.XrayLogChannel)
	channelCap := cap(bridge.XrayLogChannel)
	bridge.XrayLogChannelMu.Unlock()

	if channelClosed {
		logError("[GetXrayLogs] ❌ Log channel is closed - logs will not be available")
		logError("[GetXrayLogs] This usually means Xray-core was stopped or crashed")
		return C.CString(`{"logs":[],"error":"log channel closed"}`)
	}

	// Log channel status for diagnostics
	if channelLen > 0 {
		logDebug("[GetXrayLogs] Channel has %d logs available (capacity: %d), requesting up to %d", channelLen, channelCap, max)
	} else {
		// Log periodically when channel is empty to verify it's being polled
		// Use a simple counter to avoid too much logging
		getXrayLogsCallCountMu.Lock()
		getXrayLogsCallCount++
		callCount := getXrayLogsCallCount
		getXrayLogsCallCountMu.Unlock()
		
		if callCount%20 == 0 { // Log every 20th call (roughly every 10 seconds at 500ms polling)
			logWarn("[GetXrayLogs] ⚠️ Channel is empty (call #%d) - XrayLogWriter may not be receiving logs", callCount)
			logWarn("[GetXrayLogs] Possible causes:")
			logWarn("[GetXrayLogs]   1. XrayLogWriter not registered or not receiving log messages")
			logWarn("[GetXrayLogs]   2. Xray-core is not generating logs (check log level)")
			logWarn("[GetXrayLogs]   3. Log channel buffer is full and logs are being dropped")
		}
	}

	// Collect available logs (non-blocking)
	for count < max {
		select {
		case logLine := <-bridge.XrayLogChannel:
			logs = append(logs, logLine)
			count++
		default:
			// No more logs available
			goto done
		}
	}

done:
	// Return logs as JSON array
	result := map[string]interface{}{
		"logs": logs,
		"count": len(logs),
	}

	// Log when logs are returned (first few times to verify)
	if len(logs) > 0 {
		logDebug("[GetXrayLogs] ✅ Returning %d logs to Android", len(logs))
	}

	jsonBytes, err := json.Marshal(result)
	if err != nil {
		logError("Failed to marshal logs: %v", err)
		return C.CString(`{"logs":[],"error":"failed to marshal logs"}`)
	}

	return C.CString(string(jsonBytes))
}

func main() {}
