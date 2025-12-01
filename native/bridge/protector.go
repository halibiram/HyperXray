package bridge

/*
#include <stdlib.h>

// C socket creator function type - creates a protected socket via JNI
// This bypasses Go runtime's socket management which can interfere with protection
typedef int (*c_socket_creator_func)(int domain, int type, int protocol);

// Global C socket creator callback (set by JNI)
static c_socket_creator_func g_c_socket_creator = NULL;

// Set the C socket creator callback (static to avoid duplicate symbol errors)
static void set_c_socket_creator(c_socket_creator_func creator) {
    g_c_socket_creator = creator;
}

// Create a protected socket via C (returns fd or -1 on error)
// Static to avoid duplicate symbol errors when linking
static int create_protected_socket_via_c(int domain, int type, int protocol) {
    if (g_c_socket_creator == NULL) {
        return -1;
    }
    return g_c_socket_creator(domain, type, protocol);
}

// Check if C socket creator is available
// Static to avoid duplicate symbol errors when linking
static int is_c_socket_creator_available() {
    return g_c_socket_creator != NULL ? 1 : 0;
}
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// ProtectionState represents the current state of socket protection
type ProtectionState int

const (
	ProtectionStateUninitialized ProtectionState = iota
	ProtectionStateInitialized
	ProtectionStateVerified
	ProtectionStateFailed
)

// String returns string representation of ProtectionState
func (s ProtectionState) String() string {
	switch s {
	case ProtectionStateUninitialized:
		return "Uninitialized"
	case ProtectionStateInitialized:
		return "Initialized"
	case ProtectionStateVerified:
		return "Verified"
	case ProtectionStateFailed:
		return "Failed"
	default:
		return "Unknown"
	}
}

// ProtectionResult contains detailed protection outcome for diagnostics
type ProtectionResult struct {
	Success    bool      `json:"success"`
	Retries    int       `json:"retries"`
	Error      string    `json:"error,omitempty"`
	Interface  string    `json:"interface,omitempty"`
	PhysicalIP string    `json:"physicalIP,omitempty"`
	Duration   int64     `json:"durationMs"`
	Timestamp  time.Time `json:"timestamp"`
}

// ToJSON returns JSON representation of ProtectionResult
func (r *ProtectionResult) ToJSON() string {
	data, err := json.Marshal(r)
	if err != nil {
		return "{}"
	}
	return string(data)
}

// DiagnosticLog represents a single diagnostic log entry
type DiagnosticLog struct {
	Timestamp  time.Time `json:"timestamp"`
	Operation  string    `json:"operation"` // "init", "protect", "dial", "verify"
	Fd         int       `json:"fd,omitempty"`
	Success    bool      `json:"success"`
	Error      string    `json:"error,omitempty"`
	Interface  string    `json:"interface,omitempty"`
	PhysicalIP string    `json:"physicalIP,omitempty"`
	Duration   int64     `json:"durationMs"`
}

// Diagnostic log storage
var (
	diagnosticLogs     []DiagnosticLog
	diagnosticLogsMu   sync.RWMutex
	maxDiagnosticLogs  = 100
	protectionState    = ProtectionStateUninitialized
	protectionStateMu  sync.RWMutex
)

// addDiagnosticLog adds a log entry to the diagnostic log buffer
func addDiagnosticLog(log DiagnosticLog) {
	diagnosticLogsMu.Lock()
	defer diagnosticLogsMu.Unlock()
	
	diagnosticLogs = append(diagnosticLogs, log)
	// Keep only last N logs
	if len(diagnosticLogs) > maxDiagnosticLogs {
		diagnosticLogs = diagnosticLogs[len(diagnosticLogs)-maxDiagnosticLogs:]
	}
}

// GetDiagnosticLogs returns recent diagnostic logs as JSON
func GetDiagnosticLogs() string {
	diagnosticLogsMu.RLock()
	defer diagnosticLogsMu.RUnlock()
	
	data, err := json.Marshal(diagnosticLogs)
	if err != nil {
		return "[]"
	}
	return string(data)
}

// GetProtectionState returns current protection state
func GetProtectionState() ProtectionState {
	protectionStateMu.RLock()
	defer protectionStateMu.RUnlock()
	return protectionState
}

// setProtectionState sets the protection state
func setProtectionState(state ProtectionState) {
	protectionStateMu.Lock()
	defer protectionStateMu.Unlock()
	protectionState = state
	logInfo("[Protector] State changed to: %s", state.String())
}

// SocketProtector is a function that protects a socket from VPN routing
// It should call VpnService.protect(fd) on Android
type SocketProtector func(fd int) bool

var (
	globalProtector SocketProtector
	protectorMutex  sync.RWMutex
)

// Physical IP cache structure for non-blocking lookups
var (
	physicalIPCache struct {
		sync.RWMutex
		ip        net.IP
		timestamp time.Time
		iface     string
	}
	cacheExpiration = 30 * time.Second
	lookupTimeout   = 2 * time.Second
	refreshMutex    sync.Mutex // Prevents concurrent refresh operations
)

// SetSocketProtector sets the global socket protector function
func SetSocketProtector(protector SocketProtector) {
	protectorMutex.Lock()
	defer protectorMutex.Unlock()
	globalProtector = protector
	
	if protector != nil {
		setProtectionState(ProtectionStateInitialized)
		logInfo("[Protector] Socket protector set")
	} else {
		setProtectionState(ProtectionStateUninitialized)
		logWarn("[Protector] Socket protector cleared")
	}
	
	// Add diagnostic log
	addDiagnosticLog(DiagnosticLog{
		Timestamp: time.Now(),
		Operation: "init",
		Success:   protector != nil,
	})
}

// C socket creator availability flag
var cSocketCreatorAvailable bool

//export SetCSocketCreator
func SetCSocketCreator(creator unsafe.Pointer) {
	C.set_c_socket_creator(C.c_socket_creator_func(creator))
	cSocketCreatorAvailable = creator != nil
	if creator != nil {
		logInfo("[Protector] C socket creator set - will use C-based socket creation")
	} else {
		logWarn("[Protector] C socket creator cleared")
	}
}

// IsCSocketCreatorAvailable returns true if C socket creator is available
func IsCSocketCreatorAvailable() bool {
	return C.is_c_socket_creator_available() == 1
}

// CreateProtectedSocketViaC creates a protected socket using C (bypasses Go runtime)
// This is the preferred method as it avoids Go runtime's socket management
// Returns fd on success, -1 on error
func CreateProtectedSocketViaC(domain, sockType, protocol int) int {
	fd := C.create_protected_socket_via_c(C.int(domain), C.int(sockType), C.int(protocol))
	return int(fd)
}

// ProtectSocket protects a socket file descriptor from VPN routing
func ProtectSocket(fd int) bool {
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()

	if protector == nil {
		logWarn("[Protector] No protector set, socket %d not protected!", fd)
		return false
	}

	result := protector(fd)
	if result {
		logDebug("[Protector] ✅ Socket %d protected", fd)
	} else {
		logError("[Protector] ❌ Failed to protect socket %d", fd)
	}

	return result
}

// ProtectConn protects a net.Conn from VPN routing
func ProtectConn(conn net.Conn) bool {
	// Get underlying file descriptor
	tcpConn, ok := conn.(*net.TCPConn)
	if !ok {
		// Try UDP
		udpConn, ok := conn.(*net.UDPConn)
		if !ok {
			logWarn("[Protector] Unknown connection type: %T", conn)
			return false
		}

		file, err := udpConn.File()
		if err != nil {
			logError("[Protector] Failed to get UDP file: %v", err)
			return false
		}
		defer file.Close()

		fdInt := int(file.Fd())
		return ProtectSocket(fdInt)
	}

	file, err := tcpConn.File()
	if err != nil {
		logError("[Protector] Failed to get TCP file: %v", err)
		return false
	}
	defer file.Close()

	return ProtectSocket(int(file.Fd()))
}

// GetLocalPhysicalIP returns cached physical IP immediately (non-blocking)
// Triggers async refresh if cache is expired or missing
// On first call (cache miss), attempts quick synchronous lookup with timeout
// Returns nil IP on failure (non-fatal, dial operations continue)
func GetLocalPhysicalIP() (net.IP, error) {
	// Try to get from cache first (fast path)
	physicalIPCache.RLock()
	cachedIP := physicalIPCache.ip
	cachedTimestamp := physicalIPCache.timestamp
	cachedIface := physicalIPCache.iface
	physicalIPCache.RUnlock()

	// Check if cache is valid
	now := time.Now()
	if cachedIP != nil && !cachedTimestamp.IsZero() && now.Sub(cachedTimestamp) < cacheExpiration {
		logDebug("[ProtectedDialer] ✅ Cache hit: %s (interface: %s, age: %v)", 
			cachedIP.String(), cachedIface, now.Sub(cachedTimestamp))
		return cachedIP, nil
	}

	// Cache miss or expired
	// If cache is completely empty (first call), try quick synchronous lookup with timeout
	// This ensures we have an IP on first dial attempt
	if cachedIP == nil || cachedTimestamp.IsZero() {
		logDebug("[ProtectedDialer] Cache empty, attempting quick synchronous lookup...")
		ipChan := make(chan net.IP, 1)
		errChan := make(chan error, 1)
		ifaceChan := make(chan string, 1)

		go func() {
			// Try aggressive lookup first - optimized for VPN scenarios
			ip, iface, err := getLocalPhysicalIPInternalAggressive()
			if err != nil {
				// Fallback to standard lookup if aggressive lookup fails
				logDebug("[ProtectedDialer] Aggressive lookup failed, trying standard lookup...")
				ip, iface, err = getLocalPhysicalIPInternalWithIface()
				if err != nil {
					errChan <- err
					return
				}
			}
			ipChan <- ip
			ifaceChan <- iface
		}()

		// Wait for result with timeout for first call (increased timeout for better success rate)
		select {
		case ip := <-ipChan:
			iface := <-ifaceChan
			// Update cache immediately
			physicalIPCache.Lock()
			physicalIPCache.ip = ip
			physicalIPCache.timestamp = time.Now()
			physicalIPCache.iface = iface
			physicalIPCache.Unlock()
			if ip != nil {
				logInfo("[ProtectedDialer] ✅ Quick lookup succeeded: %s (interface: %s)", ip.String(), iface)
				return ip, nil
			} else {
				logWarn("[ProtectedDialer] ⚠️ Quick lookup returned nil IP (non-fatal)")
			}
		case err := <-errChan:
			logWarn("[ProtectedDialer] ⚠️ Quick lookup failed: %v (non-fatal)", err)
			// Continue to trigger async refresh
		case <-time.After(500 * time.Millisecond): // Reduced timeout for aggressive lookup
			logWarn("[ProtectedDialer] ⚠️ Quick lookup timeout (non-fatal), triggering async refresh...")
			// Continue to trigger async refresh
		}
	} else {
		// Cache expired but has value - trigger async refresh
		logDebug("[ProtectedDialer] Cache expired, triggering async refresh...")
		go refreshPhysicalIPCache()
		// Return stale cache value (better than nil)
		logDebug("[ProtectedDialer] ⚠️ Returning stale cache: %s (expired by %v)", 
			cachedIP.String(), now.Sub(cachedTimestamp))
		return cachedIP, nil
	}

	// Trigger async refresh for future calls
	go refreshPhysicalIPCache()

	// Return nil if quick lookup failed (non-fatal)
	// Binding is optional - socket protection is sufficient
	logWarn("[ProtectedDialer] ⚠️ No cached IP available, returning nil (non-fatal)")
	logWarn("[ProtectedDialer] ⚠️ Socket binding will be skipped - protection is sufficient")
	return nil, nil
}

// GetLocalPhysicalIPSync is the synchronous version for backward compatibility
// Use this only when you need blocking behavior
func GetLocalPhysicalIPSync() (net.IP, error) {
	return getLocalPhysicalIPInternal()
}

// refreshPhysicalIPCache performs background refresh of physical IP cache
// This runs in a goroutine and never blocks dial operations
func refreshPhysicalIPCache() {
	// Prevent concurrent refresh operations
	if !refreshMutex.TryLock() {
		logDebug("[ProtectedDialer] Refresh already in progress, skipping...")
		return
	}
	defer refreshMutex.Unlock()

	logDebug("[ProtectedDialer] Starting background physical IP refresh...")

	// Perform lookup with timeout
	ipChan := make(chan net.IP, 1)
	errChan := make(chan error, 1)
	ifaceChan := make(chan string, 1)

	go func() {
		ip, iface, err := getLocalPhysicalIPInternalWithIface()
		if err != nil {
			errChan <- err
			return
		}
		ipChan <- ip
		ifaceChan <- iface
	}()

	// Wait for result or timeout
	select {
	case ip := <-ipChan:
		iface := <-ifaceChan
		// Update cache atomically
		physicalIPCache.Lock()
		physicalIPCache.ip = ip
		physicalIPCache.timestamp = time.Now()
		physicalIPCache.iface = iface
		physicalIPCache.Unlock()
		logInfo("[ProtectedDialer] ✅ Cache refreshed: %s (interface: %s)", ip.String(), iface)
	case err := <-errChan:
		logWarn("[ProtectedDialer] ⚠️ Cache refresh failed: %v (non-fatal)", err)
	case <-time.After(lookupTimeout):
		logWarn("[ProtectedDialer] ⚠️ Cache refresh timeout after %v (non-fatal)", lookupTimeout)
	}
}

// invalidatePhysicalIPCache clears the physical IP cache
// Call this when network state changes
func InvalidatePhysicalIPCache() {
	physicalIPCache.Lock()
	defer physicalIPCache.Unlock()
	physicalIPCache.ip = nil
	physicalIPCache.timestamp = time.Time{}
	physicalIPCache.iface = ""
	logInfo("[ProtectedDialer] Cache invalidated")
}

// getLocalPhysicalIPInternalAggressive performs aggressive physical IP lookup for VPN scenarios
// This is optimized for when VPN is active and default route is TUN interface
func getLocalPhysicalIPInternalAggressive() (net.IP, string, error) {
	logDebug("[ProtectedDialer] Starting aggressive physical IP lookup...")

	// Skip /proc/net/route check entirely when VPN is likely active
	// Go directly to interface enumeration which is more reliable for VPN scenarios

	// Try common physical interface names first (fast path)
	commonPhysicalInterfaces := []string{
		// WiFi interfaces (most common and reliable)
		"wlan0", "wlan1", "wlan2",
		// Ethernet (less common but fast)
		"eth0", "eth1",
		// Mobile data (may be slower but physical)
		"rmnet0", "rmnet1", "rmnet_data0", "rmnet_data1",
	}

	for _, ifaceName := range commonPhysicalInterfaces {
		ifaceObj, err := net.InterfaceByName(ifaceName)
		if err != nil {
			continue // Interface doesn't exist, try next
		}

		// Skip if interface is down
		if ifaceObj.Flags&net.FlagUp == 0 {
			continue
		}

		// Skip TUN interfaces
		if strings.HasPrefix(ifaceName, "tun") || strings.HasPrefix(ifaceName, "wg") {
			continue
		}

		// Get addresses (skip error checking for speed)
		addrs, err := ifaceObj.Addrs()
		if err != nil {
			continue
		}

		// Find first valid IPv4 address
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			default:
				continue
			}

			// Must be IPv4 Global Unicast
			if ip.To4() != nil && ip.IsGlobalUnicast() {
				logInfo("[ProtectedDialer] ✅ Aggressive lookup found IP: %s (interface: %s)", ip.String(), ifaceName)
				return ip, ifaceName, nil
			}
		}
	}

	// If common interfaces didn't work, return nil (let standard lookup handle it)
	logDebug("[ProtectedDialer] Aggressive lookup found no interfaces, falling back to standard lookup")
	return nil, "", fmt.Errorf("no physical interfaces found in aggressive lookup")
}

// getLocalPhysicalIPInternal performs the actual physical IP lookup
// This is the original blocking implementation
func getLocalPhysicalIPInternal() (net.IP, error) {
	ip, _, err := getLocalPhysicalIPInternalWithIface()
	return ip, err
}

// getLocalPhysicalIPInternalWithIface performs the actual physical IP lookup and returns interface name
func getLocalPhysicalIPInternalWithIface() (net.IP, string, error) {
	logInfo("[ProtectedDialer] Searching for physical network interface IP...")
	
	// Read /proc/net/route to find default route interface
	defaultIface, err := findDefaultRouteInterface()
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to find default route interface: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Falling back to net.Interfaces()...")
		// Fallback to net.Interfaces() if /proc/net/route fails
		ip, iface, err := getPhysicalIPFromInterfacesWithIface()
		return ip, iface, err
	}
	
	logDebug("[ProtectedDialer] Default route interface: %s", defaultIface)
	
	// Skip TUN interfaces (VPN virtual interfaces)
	if strings.HasPrefix(defaultIface, "tun") || strings.HasPrefix(defaultIface, "wg") {
		logWarn("[ProtectedDialer] ⚠️ Default route is TUN interface (%s), searching for physical interface...", defaultIface)
		ip, iface, err := getPhysicalIPFromInterfacesWithIface()
		return ip, iface, err
	}
	
	// Read IP from /proc/net/if_inet for the default interface
	ip, err := getIPFromProcNet(defaultIface)
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to get IP from /proc/net/if_inet: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Falling back to net.Interfaces()...")
		ip, iface, err := getPhysicalIPFromInterfacesWithIface()
		return ip, iface, err
	}
	
	if ip != nil {
		logInfo("[ProtectedDialer] ✅ Selected physical source IP: %s (interface: %s)", ip.String(), defaultIface)
		return ip, defaultIface, nil
	}
	
	// Final fallback
	ip, iface, err := getPhysicalIPFromInterfacesWithIface()
	return ip, iface, err
}

// findDefaultRouteInterface reads /proc/net/route to find the default route interface
func findDefaultRouteInterface() (string, error) {
	data, err := os.ReadFile("/proc/net/route")
	if err != nil {
		return "", fmt.Errorf("read /proc/net/route: %w", err)
	}
	
	lines := strings.Split(string(data), "\n")
	for i, line := range lines {
		if i == 0 {
			continue // Skip header
		}
		if line == "" {
			continue
		}
		
		fields := strings.Fields(line)
		if len(fields) < 8 {
			continue
		}
		
		// Check if this is the default route (destination is 00000000)
		if fields[1] == "00000000" && fields[7] == "0003" {
			// Found default route, return interface name
			return fields[0], nil
		}
	}
	
	return "", fmt.Errorf("default route not found")
}

// getIPFromProcNet gets IP address for a specific interface using net.InterfaceByName
// This is more reliable than reading /proc files directly
func getIPFromProcNet(iface string) (net.IP, error) {
	// Use net.InterfaceByName to get the specific interface
	// This requires less permissions than net.Interfaces()
	ifaceObj, err := net.InterfaceByName(iface)
	if err != nil {
		return nil, fmt.Errorf("interface %s not found: %w", iface, err)
	}
	
	// Get addresses for this interface
	addrs, err := ifaceObj.Addrs()
	if err != nil {
		return nil, fmt.Errorf("failed to get addresses for %s: %w", iface, err)
	}
	
	// Find first valid Global Unicast IPv4 address
	for _, addr := range addrs {
		var ip net.IP
		switch v := addr.(type) {
		case *net.IPNet:
			ip = v.IP
		case *net.IPAddr:
			ip = v.IP
		default:
			continue
		}
		
		// Must be IPv4
		if ip.To4() == nil {
			continue
		}
		
		// Must be Global Unicast (not loopback, multicast, etc.)
		if !ip.IsGlobalUnicast() {
			continue
		}
		
		return ip, nil
	}
	
	return nil, fmt.Errorf("no valid IPv4 address found for interface %s", iface)
}

// getPhysicalIPFromInterfaces fallback method using net.Interfaces()
// This is the primary method on Android since /proc/net/route requires root
func getPhysicalIPFromInterfaces() (net.IP, error) {
	ip, _, err := getPhysicalIPFromInterfacesWithIface()
	return ip, err
}

// getPhysicalIPFromInterfacesWithIface fallback method using net.Interfaces() with interface name
// On Android, tries common interface names first to avoid permission issues
func getPhysicalIPFromInterfacesWithIface() (net.IP, string, error) {
	// On Android, try common interface names first (requires less permissions)
	// This avoids the need for net.Interfaces() which may require special permissions
	// Try multiple variations as Android devices use different naming conventions
	// Prioritize interfaces that are likely to be active when VPN is running

	// Extended list of common Android interface names - ordered by priority
	commonInterfaces := []string{
		// WiFi interfaces (most common and reliable when VPN is active)
		"wlan0", "wlan1", "wlan2", "wlan3", "wlan4",
		// Ethernet interfaces (reliable but less common)
		"eth0", "eth1", "eth2",
		// Mobile data interfaces (older Android) - may be slower
		"rmnet0", "rmnet1", "rmnet2", "rmnet3", "rmnet4", "rmnet5",
		// Mobile data interfaces (newer Android)
		"rmnet_data0", "rmnet_data1", "rmnet_data2", "rmnet_data3", "rmnet_data4",
		// Mobile data interfaces (some devices)
		"rmnet_ipa0", "rmnet_ipa1", "rmnet_ipa2",
		// Alternative mobile data naming
		"ccmni0", "ccmni1", "ccmni2", "ccmni3",
		// Some devices use different naming
		"p2p0", // WiFi Direct
		"ap0",  // Access Point mode
		// Additional interfaces that may appear on various devices
		"tiwlan0", "ra0", "mlan0", // Alternative WiFi names
	}
	
	for _, ifaceName := range commonInterfaces {
		ifaceObj, err := net.InterfaceByName(ifaceName)
		if err != nil {
			continue // Interface doesn't exist, try next
		}

		// Check if interface is up
		if ifaceObj.Flags&net.FlagUp == 0 {
			logDebug("[ProtectedDialer] Interface %s is down, skipping...", ifaceName)
			continue
		}

		// Skip TUN interfaces (VPN virtual interfaces) - double check
		if strings.HasPrefix(ifaceName, "tun") || strings.HasPrefix(ifaceName, "wg") {
			logDebug("[ProtectedDialer] Skipping TUN interface: %s", ifaceName)
			continue
		}

		// Get addresses for this interface
		addrs, err := ifaceObj.Addrs()
		if err != nil {
			logDebug("[ProtectedDialer] Failed to get addresses for %s: %v", ifaceName, err)
			continue
		}

		// Find first valid Global Unicast IPv4 address
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			default:
				continue
			}

			// Must be IPv4
			if ip.To4() == nil {
				continue
			}

			// Must be Global Unicast (not loopback, multicast, etc.)
			if !ip.IsGlobalUnicast() {
				continue
			}

			logInfo("[ProtectedDialer] ✅ Found IP via interface name lookup: %s (interface: %s)", ip.String(), ifaceName)
			return ip, ifaceName, nil
		}
	}
	
	// Fallback to net.Interfaces() if common interface names didn't work
	// On Android, this may fail with permission denied - handle gracefully
	interfaces, err := net.Interfaces()
	if err != nil {
		// Permission denied is common on Android - log warning but don't fail
		logWarn("[ProtectedDialer] ⚠️ net.Interfaces() failed: %v (permission denied is normal on Android)", err)
		logWarn("[ProtectedDialer] ⚠️ Common interface names didn't work, and net.Interfaces() is unavailable")
		logWarn("[ProtectedDialer] ℹ️  This is expected when VPN is active or on restricted Android environments")
		logWarn("[ProtectedDialer] ℹ️  Socket protection will still work, only explicit IP binding is skipped")
		logWarn("[ProtectedDialer] ✅ Returning nil - Xray connections will use default routing (protection active)")
		// Return nil instead of error - binding is optional, protection is critical
		return nil, "", nil
	}
	
	logDebug("[ProtectedDialer] Found %d network interfaces via net.Interfaces()", len(interfaces))
	
	// Prefer WiFi interface (wlan0, wlan1, etc.) over mobile data (rmnet, etc.)
	// WiFi interfaces typically have better connectivity
	var wifiIP net.IP
	var wifiIface string
	var mobileIP net.IP
	var mobileIface string
	
	for _, iface := range interfaces {
		// Skip loopback interfaces
		if iface.Flags&net.FlagLoopback != 0 {
			logDebug("[ProtectedDialer] Skipping loopback interface: %s", iface.Name)
			continue
		}
		
		// Skip TUN interfaces (VPN virtual interfaces)
		if strings.HasPrefix(iface.Name, "tun") || strings.HasPrefix(iface.Name, "wg") {
			logDebug("[ProtectedDialer] Skipping TUN/VPN interface: %s", iface.Name)
			continue
		}
		
		// Skip down interfaces
		if iface.Flags&net.FlagUp == 0 {
			logDebug("[ProtectedDialer] Skipping down interface: %s", iface.Name)
			continue
		}
		
		// Get addresses for this interface
		addrs, err := iface.Addrs()
		if err != nil {
			logDebug("[ProtectedDialer] Failed to get addresses for %s: %v", iface.Name, err)
			continue
		}
		
		// Find first valid Global Unicast IPv4 address
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			default:
				continue
			}
			
			// Must be IPv4
			if ip.To4() == nil {
				logDebug("[ProtectedDialer] Skipping IPv6 address %s on %s", ip.String(), iface.Name)
				continue
			}
			
			// Must be Global Unicast (not loopback, multicast, etc.)
			if !ip.IsGlobalUnicast() {
				logDebug("[ProtectedDialer] Skipping non-global address %s on %s", ip.String(), iface.Name)
				continue
			}
			
			// Prefer WiFi interfaces (wlan0, wlan1, etc.)
			if strings.HasPrefix(iface.Name, "wlan") {
				if wifiIP == nil {
					wifiIP = ip
					wifiIface = iface.Name
					logDebug("[ProtectedDialer] Found WiFi IP: %s (interface: %s)", ip.String(), iface.Name)
				}
			} else {
				// Mobile data or other interfaces
				if mobileIP == nil {
					mobileIP = ip
					mobileIface = iface.Name
					logDebug("[ProtectedDialer] Found mobile IP: %s (interface: %s)", ip.String(), iface.Name)
				}
			}
		}
	}
	
	// Prefer WiFi over mobile data
	if wifiIP != nil {
		logInfo("[ProtectedDialer] ✅ Selected physical source IP: %s (WiFi interface)", wifiIP.String())
		return wifiIP, wifiIface, nil
	}
	
	if mobileIP != nil {
		logInfo("[ProtectedDialer] ✅ Selected physical source IP: %s (mobile interface)", mobileIP.String())
		return mobileIP, mobileIface, nil
	}
	
	return nil, "", fmt.Errorf("no physical network interface found")
}

// ProtectedDialer creates a dialer that protects sockets before connecting
type ProtectedDialer struct {
	Timeout int // seconds
}

// DialTCP dials a protected TCP connection
func (d *ProtectedDialer) DialTCP(network, address string) (net.Conn, error) {
	logInfo("[ProtectedDialer] Dialing %s %s", network, address)

	// Check if socket protector is set
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()
	
	if protector == nil {
		logError("[ProtectedDialer] ❌ Socket protector is NOT SET!")
		logError("[ProtectedDialer] ❌ Cannot protect socket - call SetSocketProtector first!")
		return nil, fmt.Errorf("socket protector not set - call initSocketProtector() first")
	}
	logInfo("[ProtectedDialer] ✅ Socket protector is available")

	// Resolve address first
	tcpAddr, err := net.ResolveTCPAddr(network, address)
	if err != nil {
		logError("[ProtectedDialer] ❌ DNS resolution failed for %s: %v", address, err)
		return nil, fmt.Errorf("resolve address: %w", err)
	}
	logInfo("[ProtectedDialer] ✅ DNS resolved: %s → %s", address, tcpAddr.IP.String())

	var fd int
	var usedCSocket bool

	// Try to create socket via C first (bypasses Go runtime's socket management)
	// This is critical because Go runtime can interfere with socket protection
	if IsCSocketCreatorAvailable() {
		logInfo("[ProtectedDialer] Using C-based socket creation (bypasses Go runtime)")
		fd = CreateProtectedSocketViaC(syscall.AF_INET, syscall.SOCK_STREAM, 0)
		if fd >= 0 {
			usedCSocket = true
			logInfo("[ProtectedDialer] ✅ C socket created and protected: fd=%d", fd)
		} else {
			logWarn("[ProtectedDialer] ⚠️ C socket creation failed, falling back to Go syscall")
		}
	}

	// Fallback to Go syscall if C socket creation failed or unavailable
	if fd < 0 {
		var err error
		fd, err = syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
		if err != nil {
			logError("[ProtectedDialer] ❌ Failed to create socket: %v", err)
			return nil, fmt.Errorf("create socket: %w", err)
		}
		logDebug("[ProtectedDialer] Go socket created: fd=%d", fd)
	}

	// Get physical network IP for source binding (optional)
	physicalIP, err := GetLocalPhysicalIP()
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to get physical IP: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Will proceed without explicit source binding (may use VPN IP)")
	} else if physicalIP != nil {
		// Bind socket to physical IP before connecting
		var bindAddr syscall.SockaddrInet4
		copy(bindAddr.Addr[:], physicalIP.To4())
		bindAddr.Port = 0
		
		err = syscall.Bind(fd, &bindAddr)
		if err != nil {
			logWarn("[ProtectedDialer] ⚠️ Failed to bind to physical IP %s: %v (non-fatal)", physicalIP.String(), err)
		} else {
			logInfo("[ProtectedDialer] ✅ Socket bound to physical IP: %s", physicalIP.String())
		}
	}

	// PROTECT THE SOCKET BEFORE CONNECTING (only if not already protected via C)
	if !usedCSocket {
		if !ProtectSocket(fd) {
			syscall.Close(fd)
			logError("[ProtectedDialer] ❌ Failed to protect socket %d", fd)
			return nil, fmt.Errorf("failed to protect socket %d", fd)
		}
		logInfo("[ProtectedDialer] ✅ Socket %d protected via Go callback", fd)
	}

	// Socket is now protected (either via C or Go callback)
	// CRITICAL: Do NOT call any Go runtime functions that might re-open the socket

	// Convert address
	var sa syscall.SockaddrInet4
	copy(sa.Addr[:], tcpAddr.IP.To4())
	sa.Port = tcpAddr.Port

	// Connect in a goroutine with timeout
	timeout := time.Duration(d.Timeout) * time.Second
	if timeout == 0 {
		timeout = 10 * time.Second
	}

	connectDone := make(chan error, 1)
	go func() {
		// Connect in blocking mode
		err := syscall.Connect(fd, &sa)
		connectDone <- err
	}()

	// Wait for connection or timeout
	select {
	case err := <-connectDone:
		if err != nil {
			syscall.Close(fd)
			logError("[ProtectedDialer] ❌ Connect failed: %v", err)
			return nil, fmt.Errorf("connect: %w", err)
		}
		logInfo("[ProtectedDialer] ✅ Connection established")
	case <-time.After(timeout):
		syscall.Close(fd)
		logError("[ProtectedDialer] ❌ Connection timeout after %v", timeout)
		return nil, fmt.Errorf("connection timeout")
	}

	// Create net.Conn from fd
	// IMPORTANT: os.NewFile takes ownership of fd, so we don't close it manually
	file := os.NewFile(uintptr(fd), "")
	conn, err := net.FileConn(file)
	file.Close() // FileConn dups the fd, so we can close the file

	if err != nil {
		syscall.Close(fd) // If FileConn failed, close the fd
		logError("[ProtectedDialer] ❌ FileConn failed: %v", err)
		return nil, fmt.Errorf("fileconn: %w", err)
	}

	logDebug("[ProtectedDialer] ✅ Connected to %s", address)
	return conn, nil
}

// DialTCPWithDiagnostics dials a protected TCP connection with detailed diagnostics
func (d *ProtectedDialer) DialTCPWithDiagnostics(network, address string) (net.Conn, *ProtectionResult, error) {
	startTime := time.Now()
	result := &ProtectionResult{
		Timestamp: startTime,
	}
	
	logInfo("[ProtectedDialer] DialTCPWithDiagnostics: %s %s", network, address)

	// Check if socket protector is set
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()
	
	if protector == nil {
		result.Success = false
		result.Error = "socket protector not set"
		result.Duration = time.Since(startTime).Milliseconds()
		setProtectionState(ProtectionStateFailed)
		return nil, result, fmt.Errorf("socket protector not set")
	}

	// Resolve address first
	tcpAddr, err := net.ResolveTCPAddr(network, address)
	if err != nil {
		result.Success = false
		result.Error = fmt.Sprintf("DNS resolution failed: %v", err)
		result.Duration = time.Since(startTime).Milliseconds()
		return nil, result, fmt.Errorf("resolve address: %w", err)
	}

	// Create socket
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		result.Success = false
		result.Error = fmt.Sprintf("socket creation failed: %v", err)
		result.Duration = time.Since(startTime).Milliseconds()
		return nil, result, fmt.Errorf("create socket: %w", err)
	}

	// Get physical network IP for source binding
	physicalIP, _ := GetLocalPhysicalIP()
	if physicalIP != nil {
		result.PhysicalIP = physicalIP.String()
		
		// Try to get interface name from cache
		physicalIPCache.RLock()
		result.Interface = physicalIPCache.iface
		physicalIPCache.RUnlock()
		
		// Bind socket to physical IP
		var bindAddr syscall.SockaddrInet4
		copy(bindAddr.Addr[:], physicalIP.To4())
		bindAddr.Port = 0
		syscall.Bind(fd, &bindAddr) // Ignore error - binding is optional
	}

	// PROTECT THE SOCKET
	if !ProtectSocket(fd) {
		syscall.Close(fd)
		result.Success = false
		result.Error = "socket protection failed"
		result.Duration = time.Since(startTime).Milliseconds()
		setProtectionState(ProtectionStateFailed)
		
		// Add diagnostic log
		addDiagnosticLog(DiagnosticLog{
			Timestamp:  time.Now(),
			Operation:  "protect",
			Fd:         fd,
			Success:    false,
			Error:      "protection callback returned false",
			Interface:  result.Interface,
			PhysicalIP: result.PhysicalIP,
			Duration:   result.Duration,
		})
		
		return nil, result, fmt.Errorf("failed to protect socket %d", fd)
	}
	
	setProtectionState(ProtectionStateVerified)

	// Connect
	var sa syscall.SockaddrInet4
	copy(sa.Addr[:], tcpAddr.IP.To4())
	sa.Port = tcpAddr.Port

	timeout := time.Duration(d.Timeout) * time.Second
	if timeout == 0 {
		timeout = 10 * time.Second
	}

	connectDone := make(chan error, 1)
	go func() {
		err := syscall.Connect(fd, &sa)
		connectDone <- err
	}()

	select {
	case err := <-connectDone:
		if err != nil {
			syscall.Close(fd)
			result.Success = false
			result.Error = fmt.Sprintf("connect failed: %v", err)
			result.Duration = time.Since(startTime).Milliseconds()
			return nil, result, fmt.Errorf("connect: %w", err)
		}
	case <-time.After(timeout):
		syscall.Close(fd)
		result.Success = false
		result.Error = "connection timeout"
		result.Duration = time.Since(startTime).Milliseconds()
		return nil, result, fmt.Errorf("connection timeout")
	}

	// Create net.Conn from fd
	file := os.NewFile(uintptr(fd), "")
	conn, err := net.FileConn(file)
	file.Close()

	if err != nil {
		syscall.Close(fd)
		result.Success = false
		result.Error = fmt.Sprintf("FileConn failed: %v", err)
		result.Duration = time.Since(startTime).Milliseconds()
		return nil, result, fmt.Errorf("fileconn: %w", err)
	}

	result.Success = true
	result.Duration = time.Since(startTime).Milliseconds()
	
	// Add diagnostic log
	addDiagnosticLog(DiagnosticLog{
		Timestamp:  time.Now(),
		Operation:  "dial",
		Fd:         fd,
		Success:    true,
		Interface:  result.Interface,
		PhysicalIP: result.PhysicalIP,
		Duration:   result.Duration,
	})

	return conn, result, nil
}

// DialUDP dials a protected UDP connection
func (d *ProtectedDialer) DialUDP(network, address string) (*net.UDPConn, error) {
	logDebug("[ProtectedDialer] Dialing UDP %s %s", network, address)

	// Resolve address
	udpAddr, err := net.ResolveUDPAddr(network, address)
	if err != nil {
		return nil, err
	}

	var fd int
	var usedCSocket bool

	// Try to create socket via C first (bypasses Go runtime's socket management)
	if IsCSocketCreatorAvailable() {
		logInfo("[ProtectedDialer] Using C-based UDP socket creation")
		fd = CreateProtectedSocketViaC(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
		if fd >= 0 {
			usedCSocket = true
			logInfo("[ProtectedDialer] ✅ C UDP socket created and protected: fd=%d", fd)
		} else {
			logWarn("[ProtectedDialer] ⚠️ C UDP socket creation failed, falling back to Go syscall")
		}
	}

	// Fallback to Go syscall if C socket creation failed or unavailable
	if fd < 0 {
		var err error
		fd, err = syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
		if err != nil {
			return nil, fmt.Errorf("create socket: %w", err)
		}
		logDebug("[ProtectedDialer] Go UDP socket created: fd=%d", fd)
	}

	// Get physical network IP for source binding
	physicalIP, err := GetLocalPhysicalIP()
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to get physical IP for UDP: %v", err)
		physicalIP = nil
	}

	// PROTECT THE SOCKET (only if not already protected via C)
	fdInt := fd
	if !usedCSocket {
		if !ProtectSocket(fdInt) {
			syscall.Close(fd)
			return nil, fmt.Errorf("failed to protect socket")
		}
		logInfo("[ProtectedDialer] ✅ UDP socket %d protected via Go callback", fd)
	}

	// Bind to physical IP if available, otherwise bind to any address
	var bindAddr syscall.SockaddrInet4
	if physicalIP != nil {
		copy(bindAddr.Addr[:], physicalIP.To4())
		logInfo("[ProtectedDialer] ✅ UDP socket bound to physical IP: %s", physicalIP.String())
	} else {
		// Bind to any address (0.0.0.0)
		logDebug("[ProtectedDialer] Binding UDP socket to any address")
	}
	bindAddr.Port = 0 // Any port
	err = syscall.Bind(fd, &bindAddr)
	if err != nil {
		syscall.Close(fd)
		return nil, fmt.Errorf("bind: %w", err)
	}

	// Connect
	var sa syscall.SockaddrInet4
	copy(sa.Addr[:], udpAddr.IP.To4())
	sa.Port = udpAddr.Port

	err = syscall.Connect(fd, &sa)
	if err != nil {
		syscall.Close(fd)
		return nil, fmt.Errorf("connect: %w", err)
	}

	// Create UDPConn from fd
	file := os.NewFile(uintptr(fd), "")
	conn, err := net.FileConn(file)
	file.Close()

	if err != nil {
		return nil, fmt.Errorf("fileconn: %w", err)
	}

	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		return nil, fmt.Errorf("not a UDP connection")
	}

	return udpConn, nil
}

// DialContext dials with context (for use with http.Transport)
func (d *ProtectedDialer) DialContext(network, address string) (net.Conn, error) {
	return d.DialTCP(network, address)
}

