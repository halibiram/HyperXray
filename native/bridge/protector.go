package bridge

import (
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"
)

// SocketProtector is a function that protects a socket from VPN routing
// It should call VpnService.protect(fd) on Android
type SocketProtector func(fd int) bool

var (
	globalProtector SocketProtector
	protectorMutex  sync.RWMutex
)

// SetSocketProtector sets the global socket protector function
func SetSocketProtector(protector SocketProtector) {
	protectorMutex.Lock()
	defer protectorMutex.Unlock()
	globalProtector = protector
	logInfo("[Protector] Socket protector set")
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

// GetLocalPhysicalIP finds the physical network interface IP address
// Uses /proc/net/route to find default route interface, then reads IP from /proc/net/if_inet
// This avoids permission issues with net.Interfaces() on Android
func GetLocalPhysicalIP() (net.IP, error) {
	logInfo("[ProtectedDialer] Searching for physical network interface IP...")
	
	// Read /proc/net/route to find default route interface
	defaultIface, err := findDefaultRouteInterface()
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to find default route interface: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Falling back to net.Interfaces()...")
		// Fallback to net.Interfaces() if /proc/net/route fails
		return getPhysicalIPFromInterfaces()
	}
	
	logDebug("[ProtectedDialer] Default route interface: %s", defaultIface)
	
	// Skip TUN interfaces (VPN virtual interfaces)
	if strings.HasPrefix(defaultIface, "tun") || strings.HasPrefix(defaultIface, "wg") {
		logWarn("[ProtectedDialer] ⚠️ Default route is TUN interface (%s), searching for physical interface...", defaultIface)
		return getPhysicalIPFromInterfaces()
	}
	
	// Read IP from /proc/net/if_inet for the default interface
	ip, err := getIPFromProcNet(defaultIface)
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to get IP from /proc/net/if_inet: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Falling back to net.Interfaces()...")
		return getPhysicalIPFromInterfaces()
	}
	
	if ip != nil {
		logInfo("[ProtectedDialer] ✅ Selected physical source IP: %s (interface: %s)", ip.String(), defaultIface)
		return ip, nil
	}
	
	// Final fallback
	return getPhysicalIPFromInterfaces()
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
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("enumerate interfaces: %w", err)
	}
	
	logDebug("[ProtectedDialer] Found %d network interfaces", len(interfaces))
	
	// Prefer WiFi interface (wlan0, wlan1, etc.) over mobile data (rmnet, etc.)
	// WiFi interfaces typically have better connectivity
	var wifiIP net.IP
	var mobileIP net.IP
	
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
					logDebug("[ProtectedDialer] Found WiFi IP: %s (interface: %s)", ip.String(), iface.Name)
				}
			} else {
				// Mobile data or other interfaces
				if mobileIP == nil {
					mobileIP = ip
					logDebug("[ProtectedDialer] Found mobile IP: %s (interface: %s)", ip.String(), iface.Name)
				}
			}
		}
	}
	
	// Prefer WiFi over mobile data
	if wifiIP != nil {
		logInfo("[ProtectedDialer] ✅ Selected physical source IP: %s (WiFi interface)", wifiIP.String())
		return wifiIP, nil
	}
	
	if mobileIP != nil {
		logInfo("[ProtectedDialer] ✅ Selected physical source IP: %s (mobile interface)", mobileIP.String())
		return mobileIP, nil
	}
	
	return nil, fmt.Errorf("no physical network interface found")
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

	// Create socket
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		logError("[ProtectedDialer] ❌ Failed to create socket: %v", err)
		return nil, fmt.Errorf("create socket: %w", err)
	}
	logDebug("[ProtectedDialer] Socket created: fd=%d", fd)

	// Get physical network IP for source binding
	physicalIP, err := GetLocalPhysicalIP()
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to get physical IP: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Will proceed without explicit source binding (may use VPN IP)")
		// Continue without binding - let OS choose source IP
	} else {
		// Bind socket to physical IP before connecting
		// This ensures packets use physical interface IP, not VPN virtual IP
		var bindAddr syscall.SockaddrInet4
		copy(bindAddr.Addr[:], physicalIP.To4())
		bindAddr.Port = 0 // Let OS choose port
		
		err = syscall.Bind(fd, &bindAddr)
		if err != nil {
			syscall.Close(fd)
			logError("[ProtectedDialer] ❌ Failed to bind to physical IP %s: %v", physicalIP.String(), err)
			return nil, fmt.Errorf("bind to physical IP: %w", err)
		}
		logInfo("[ProtectedDialer] ✅ Socket bound to physical IP: %s", physicalIP.String())
	}

	// PROTECT THE SOCKET BEFORE CONNECTING!
	// This bypasses VPN routing policy
	if !ProtectSocket(fd) {
		syscall.Close(fd)
		logError("[ProtectedDialer] ❌ Failed to protect socket %d", fd)
		return nil, fmt.Errorf("failed to protect socket %d", fd)
	}
	logInfo("[ProtectedDialer] ✅ Socket %d protected successfully", fd)

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

// DialUDP dials a protected UDP connection
func (d *ProtectedDialer) DialUDP(network, address string) (*net.UDPConn, error) {
	logDebug("[ProtectedDialer] Dialing UDP %s %s", network, address)

	// Resolve address
	udpAddr, err := net.ResolveUDPAddr(network, address)
	if err != nil {
		return nil, err
	}

	// Create socket
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return nil, fmt.Errorf("create socket: %w", err)
	}

	// Get physical network IP for source binding
	physicalIP, err := GetLocalPhysicalIP()
	if err != nil {
		logWarn("[ProtectedDialer] ⚠️ Failed to get physical IP for UDP: %v", err)
		logWarn("[ProtectedDialer] ⚠️ Will proceed without explicit source binding (may use VPN IP)")
		// Continue without binding - let OS choose source IP
		physicalIP = nil
	}

	// PROTECT THE SOCKET!
	// Convert fd to int (on Windows, syscall.Socket returns Handle which is uintptr)
	fdInt := int(fd)
	if fdInt < 0 {
		// Handle Windows uintptr case
		fdInt = int(uintptr(fd))
	}
	if !ProtectSocket(fdInt) {
		syscall.Close(fd)
		return nil, fmt.Errorf("failed to protect socket")
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

