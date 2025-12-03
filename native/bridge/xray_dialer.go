package bridge

import (
	"context"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"
	"unsafe"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

// SO_BINDTODEVICE constant for Linux/Android
// This binds the socket to a specific network interface
const SO_BINDTODEVICE = 25

// Google DNS configuration
// These are hardcoded IPs used to resolve VPN server hostname BEFORE tunnel is established
var bootstrapDNSServers = []string{
	"8.8.8.8:53",      // Google DNS Primary
	"8.8.4.4:53",      // Google DNS Secondary
}

// Physical interface names to try for SO_BINDTODEVICE
// Ordered by priority (WiFi first, then mobile data)
var physicalInterfaceNames = []string{
	// WiFi interfaces (most common)
	"wlan0", "wlan1", "wlan2",
	// Ethernet
	"eth0", "eth1",
	// Mobile data (various Android naming conventions)
	"rmnet0", "rmnet1", "rmnet_data0", "rmnet_data1",
	"rmnet_ipa0", "ccmni0", "ccmni1",
}

// ProtectedXrayDialer implements Xray's internet.SystemDialer interface
// It ensures all sockets are protected before connecting
type ProtectedXrayDialer struct{}

// Dial implements internet.SystemDialer interface
// This is called by Xray-core for ALL outbound connections
func (d *ProtectedXrayDialer) Dial(ctx context.Context, source xnet.Address, dest xnet.Destination, sockopt *internet.SocketConfig) (net.Conn, error) {
	logInfo("[XrayDialer] ========================================")
	logInfo("[XrayDialer] Opening socket for Xray outbound connection...")
	logInfo("[XrayDialer] Source: %v, Destination: %v (Network: %v, Address: %v, Port: %v)",
		source, dest, dest.Network, dest.Address, dest.Port)
	logInfo("[XrayDialer] ========================================")

	// Check if protector is available
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()

	if protector == nil {
		logError("[XrayDialer] ❌ Socket protector is NOT SET!")
		logError("[XrayDialer] ❌ Cannot protect socket - call SetSocketProtector first!")
		return nil, fmt.Errorf("socket protector not set - call SetSocketProtector first")
	}
	logInfo("[XrayDialer] ✅ Socket protector is available")

	// Determine network type
	var network string
	var address string
	if dest.Network == xnet.Network_TCP {
		network = "tcp"
	} else if dest.Network == xnet.Network_UDP {
		network = "udp"
	} else {
		logError("[XrayDialer] ❌ Unsupported network type: %v", dest.Network)
		return nil, fmt.Errorf("unsupported network: %v", dest.Network)
	}

	// Build address string
	if dest.Address.Family().IsIP() {
		address = fmt.Sprintf("%s:%d", dest.Address.IP().String(), dest.Port.Value())
	} else {
		// Domain name - resolve it first using protected DNS resolver
		domain := dest.Address.Domain()
		logInfo("[XrayDialer] Resolving domain: %s (using protected DNS resolver)", domain)
		
		// Use protected DNS resolver to avoid VPN routing loop
		// This ensures DNS queries go through physical network, not VPN
		selectedIP, err := resolveDomainProtected(domain)
		if err != nil {
			logError("[XrayDialer] ❌ DNS resolution failed for %s: %v", domain, err)
			logError("[XrayDialer] ❌ This may be due to:")
			logError("[XrayDialer]    1. DNS server unreachable")
			logError("[XrayDialer]    2. Domain name invalid or expired")
			logError("[XrayDialer]    3. Network connectivity issue")
			return nil, fmt.Errorf("dns resolution failed: %w", err)
		}
		
		logInfo("[XrayDialer] ✅ DNS resolved: %s → %s", domain, selectedIP.String())
		address = fmt.Sprintf("%s:%d", selectedIP.String(), dest.Port.Value())
	}

	logInfo("[XrayDialer] Opening socket for %s %s", network, address)

	// Get physical network IP for source binding
	// This ensures packets use physical interface IP, not VPN virtual IP
	physicalIP, err := GetLocalPhysicalIP()
	if err != nil {
		logWarn("[XrayDialer] ⚠️ Failed to get physical IP: %v", err)
		logWarn("[XrayDialer] ⚠️ Will proceed without explicit source binding (may use VPN IP)")
		physicalIP = nil
	} else if physicalIP != nil {
		logInfo("[XrayDialer] ✅ Physical IP found: %s", physicalIP.String())
	} else {
		logWarn("[XrayDialer] ⚠️ Physical IP is nil (cache miss) - VPN may be active")
		logWarn("[XrayDialer] ⚠️ This is normal when VPN is running, proceeding without binding")
		logInfo("[XrayDialer] ℹ️  Socket protection will still work, only explicit source binding is skipped")
	}

	// Detect physical interface for SO_BINDTODEVICE
	physicalIface := detectPhysicalInterface()
	if physicalIface != "" {
		logInfo("[XrayDialer] ✅ Physical interface detected: %s", physicalIface)
	} else {
		logWarn("[XrayDialer] ⚠️ No physical interface detected for SO_BINDTODEVICE")
	}

	// Use net.Dialer with Control callback for socket protection and interface binding
	// This is the modern, platform-independent approach
	dialer := net.Dialer{
		Timeout:   30 * time.Second,
		KeepAlive: 30 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			var protectErr error
			var bindErr error
			var deviceBindErr error
			
			err := c.Control(func(fd uintptr) {
				fdInt := int(fd)
				
				// ============================================================
				// STEP 1: PROTECT SOCKET (CRITICAL - MUST SUCCEED)
				// ============================================================
				// On Android, socket must be protected immediately after creation
				// to prevent it from being routed through VPN (routing loop)
				logInfo("[XrayDialer] [STEP 1/3] Protecting socket fd: %d...", fdInt)
				if !ProtectSocket(fdInt) {
					protectErr = fmt.Errorf("FATAL: failed to protect socket %d - blocking connection to prevent routing loop", fdInt)
					logError("[XrayDialer] ❌ FATAL: Protection FAILED (socket %d)", fdInt)
					logError("[XrayDialer] ❌ Connection will be BLOCKED to prevent routing loop")
					return // Don't continue if protection fails - this is FATAL
				}
				logInfo("[XrayDialer] ✅ [STEP 1/3] Socket %d protected successfully", fdInt)
				
				// ============================================================
				// STEP 2: BIND TO PHYSICAL INTERFACE (SO_BINDTODEVICE)
				// ============================================================
				// This explicitly binds the socket to the physical network interface
				// (e.g., wlan0, rmnet_data0) to ensure traffic bypasses VPN routing
				// This is a secondary protection layer in addition to VpnService.protect()
				logInfo("[XrayDialer] [STEP 2/3] Binding socket to physical interface...")
				if physicalIface != "" {
					deviceBindErr = bindSocketToDevice(fdInt, physicalIface)
					if deviceBindErr != nil {
						// SO_BINDTODEVICE may fail due to permissions (requires CAP_NET_RAW)
						// This is non-fatal as VpnService.protect() is the primary protection
						logWarn("[XrayDialer] ⚠️ SO_BINDTODEVICE failed: %v (non-fatal)", deviceBindErr)
						logInfo("[XrayDialer] ℹ️  This is normal on Android - VpnService.protect() is primary protection")
					} else {
						logInfo("[XrayDialer] ✅ [STEP 2/3] Socket bound to interface: %s", physicalIface)
					}
				} else {
					logInfo("[XrayDialer] ℹ️  [STEP 2/3] Skipping SO_BINDTODEVICE (no interface detected)")
				}
				
				// ============================================================
				// STEP 3: BIND TO PHYSICAL IP ADDRESS (OPTIONAL)
				// ============================================================
				// This binds the socket to the physical interface's IP address
				// Provides additional assurance that traffic uses physical network
				logInfo("[XrayDialer] [STEP 3/3] Binding socket to physical IP...")
				if physicalIP != nil && (network == "tcp" || network == "tcp4" || network == "tcp6") {
					var bindAddr syscall.SockaddrInet4
					copy(bindAddr.Addr[:], physicalIP.To4())
					bindAddr.Port = 0 // Let OS choose port

					if err := syscall.Bind(fdInt, &bindAddr); err != nil {
						bindErr = fmt.Errorf("failed to bind to physical IP %s: %w", physicalIP.String(), err)
						logWarn("[XrayDialer] ⚠️ IP binding failed: %v (non-fatal)", bindErr)
					} else {
						logInfo("[XrayDialer] ✅ [STEP 3/3] Socket bound to physical IP: %s", physicalIP.String())
					}
				} else if physicalIP == nil {
					logInfo("[XrayDialer] ℹ️  [STEP 3/3] Skipping IP binding (no physical IP available)")
				}
			})
			
			if err != nil {
				return fmt.Errorf("control callback error: %w", err)
			}
			
			// Protection error is FATAL - return it to block the connection
			if protectErr != nil {
				return protectErr
			}
			
			// Device bind and IP bind errors are non-fatal - just log them
			// VpnService.protect() is the primary protection mechanism
			if deviceBindErr != nil {
				logDebug("[XrayDialer] Note: SO_BINDTODEVICE failed but continuing (protection succeeded)")
			}
			if bindErr != nil {
				logDebug("[XrayDialer] Note: IP bind failed but continuing (protection succeeded)")
			}
			
			return nil
		},
	}

	// Dial with context support
	startTime := time.Now()
	conn, err := dialer.DialContext(ctx, network, address)
	dialDuration := time.Since(startTime)
	
	if err != nil {
		logError("[XrayDialer] ❌ Dial failed after %v: %v", dialDuration, err)
		logError("[XrayDialer]    Network: %s, Address: %s", network, address)
		logError("[XrayDialer]    Destination: %v (Network: %v, Address: %v, Port: %v)", dest, dest.Network, dest.Address, dest.Port)
		
		// Enhanced error analysis
		errStr := err.Error()
		errLower := strings.ToLower(errStr)
		if strings.Contains(errLower, "timeout") || strings.Contains(errLower, "deadline") {
			logError("[XrayDialer]    Error Type: Connection Timeout")
			logError("[XrayDialer]    Possible causes:")
			logError("[XrayDialer]      1. Xray server is unreachable or not responding")
			logError("[XrayDialer]      2. TLS/REALITY handshake is failing silently")
			logError("[XrayDialer]      3. Network/firewall blocking Xray traffic")
			logError("[XrayDialer]      4. Protected Dialer is not binding to correct network interface")
		} else if strings.Contains(errLower, "refused") {
			logError("[XrayDialer]    Error Type: Connection Refused")
			logError("[XrayDialer]    Possible causes:")
			logError("[XrayDialer]      1. Xray server is not running")
			logError("[XrayDialer]      2. Wrong server port in configuration")
			logError("[XrayDialer]      3. Firewall blocking connections")
		} else if strings.Contains(errLower, "unreachable") || strings.Contains(errLower, "no route") {
			logError("[XrayDialer]    Error Type: Network Unreachable")
			logError("[XrayDialer]    Possible causes:")
			logError("[XrayDialer]      1. No network connectivity")
			logError("[XrayDialer]      2. Wrong server address")
			logError("[XrayDialer]      3. DNS resolution failure")
		}
		
		return nil, fmt.Errorf("dial: %w", err)
	}

	logInfo("[XrayDialer] ✅ Connected to %s through protected socket (duration: %v)", address, dialDuration)
	
	// Enhanced connection state logging
	if conn != nil {
		localAddr := conn.LocalAddr()
		remoteAddr := conn.RemoteAddr()
		
		logInfo("[XrayDialer] Connection Details:")
		logInfo("[XrayDialer]    Local address: %v", localAddr)
		logInfo("[XrayDialer]    Remote address: %v", remoteAddr)
		logInfo("[XrayDialer]    Network: %s", network)
		logInfo("[XrayDialer]    Connection type: %T", conn)
		
		// Validate addresses
		if localAddr != nil {
			localStr := localAddr.String()
			if localStr == "0.0.0.0:0" || localStr == "[::]:0" {
				logWarn("[XrayDialer] ⚠️ Local address is invalid: %v - This may indicate connection issue", localAddr)
			} else {
				logInfo("[XrayDialer]    ✅ Local address is valid")
			}
		} else {
			logWarn("[XrayDialer] ⚠️ Local address is nil!")
		}
		
		if remoteAddr != nil {
			remoteStr := remoteAddr.String()
			if remoteStr == "0.0.0.0:0" || remoteStr == "[::]:0" {
				logWarn("[XrayDialer] ⚠️ Remote address is invalid: %v - This may indicate connection issue", remoteAddr)
			} else {
				logInfo("[XrayDialer]    ✅ Remote address is valid")
			}
		} else {
			logWarn("[XrayDialer] ⚠️ Remote address is nil!")
		}
	} else {
		logError("[XrayDialer] ❌ Connection is nil after successful dial!")
		return nil, fmt.Errorf("connection is nil")
	}
	
	return conn, nil
}

// DestIpAddress implements internet.SystemDialer interface
func (d *ProtectedXrayDialer) DestIpAddress() net.IP {
	// Return nil to use default behavior
	return nil
}

// initXrayProtectedDialer registers the protected dialer with Xray-core
// This MUST be called BEFORE core.New() to ensure all Xray sockets are protected
func initXrayProtectedDialer() error {
	logInfo("[XrayDialer] ========================================")
	logInfo("[XrayDialer] Registering protected dialer with Xray-core...")
	logInfo("[XrayDialer] ========================================")

	// Check protector is available
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()

	if protector == nil {
		logError("[XrayDialer] ❌ Socket protector is NOT SET!")
		logError("[XrayDialer] ❌ Cannot register protected dialer - call SetSocketProtector first!")
		return fmt.Errorf("socket protector not set")
	}

	// Create protected dialer instance
	protectedDialer := &ProtectedXrayDialer{}

	// Register as alternative system dialer using UseAlternativeSystemDialer
	// This replaces the default system dialer for ALL outbound connections
	// This is the recommended way to inject socket protection in Xray-core
	internet.UseAlternativeSystemDialer(protectedDialer)

	logInfo("[XrayDialer] ✅ Protected dialer registered as alternative system dialer")
	logInfo("[XrayDialer] ✅ ALL Xray outbound sockets will now be protected!")
	logInfo("[XrayDialer] ========================================")

	return nil
}

// resolveDomainProtected resolves a domain name using Google DNS (8.8.8.8)
// This is CRITICAL for avoiding DNS deadlock:
// - VPN is not yet established
// - System DNS may try to use VPN (which isn't up)
// - We use Google DNS IPs to resolve the VPN server hostname
// - DNS queries are sent through protected sockets on physical interface
func resolveDomainProtected(domain string) (net.IP, error) {
	logDebug("[GoogleDNS] Resolving domain: %s", domain)
	
	// Detect physical interface for binding DNS queries
	physicalIface := detectPhysicalInterface()
	if physicalIface != "" {
		logInfo("[GoogleDNS] Physical interface for DNS: %s", physicalIface)
	}
	
	// Try Google DNS servers (primary and secondary)
	var lastErr error
	for _, dnsServer := range bootstrapDNSServers {
		logInfo("[GoogleDNS] Trying DNS server: %s", dnsServer)
		
		ip, err := resolveDomainUDPProtected(domain, dnsServer, physicalIface)
		if err == nil {
			logInfo("[GoogleDNS] ✅ Resolution successful via %s: %s → %s", dnsServer, domain, ip.String())
			return ip, nil
		}
		
		logWarn("[GoogleDNS] ⚠️ DNS server %s failed: %v", dnsServer, err)
		lastErr = err
	}
	
	logError("[GoogleDNS] ❌ All DNS servers failed!")
	logError("[GoogleDNS] ❌ This may indicate:")
	logError("[GoogleDNS]    1. No network connectivity")
	logError("[GoogleDNS]    2. All DNS servers blocked by firewall")
	logError("[GoogleDNS]    3. Socket protection not working")
	
	return nil, fmt.Errorf("Google DNS resolution failed for %s: %w", domain, lastErr)
}

// resolveDomainUDPProtected resolves domain using UDP DNS query with protected socket
// The socket is protected via VpnService.protect() AND bound to physical interface
func resolveDomainUDPProtected(domain, dnsServer, physicalIface string) (net.IP, error) {
	// Parse DNS server address
	addr, err := net.ResolveUDPAddr("udp", dnsServer)
	if err != nil {
		return nil, fmt.Errorf("invalid DNS server address: %w", err)
	}
	
	// Create UDP socket
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return nil, fmt.Errorf("failed to create UDP socket: %w", err)
	}
	defer syscall.Close(fd)
	
	fdInt := int(fd)
	
	// ============================================================
	// STEP 1: PROTECT SOCKET (CRITICAL)
	// ============================================================
	if !ProtectSocket(fdInt) {
		return nil, fmt.Errorf("FATAL: failed to protect DNS socket - cannot proceed")
	}
	logDebug("[BootstrapDNS] ✅ DNS socket protected (fd=%d)", fdInt)
	
	// ============================================================
	// STEP 2: BIND TO PHYSICAL INTERFACE (SO_BINDTODEVICE)
	// ============================================================
	if physicalIface != "" {
		if err := bindSocketToDevice(fdInt, physicalIface); err != nil {
			logDebug("[BootstrapDNS] ⚠️ SO_BINDTODEVICE failed for DNS socket: %v (non-fatal)", err)
		} else {
			logDebug("[BootstrapDNS] ✅ DNS socket bound to interface: %s", physicalIface)
		}
	}
	
	// ============================================================
	// STEP 3: BIND TO PHYSICAL IP (OPTIONAL)
	// ============================================================
	physicalIP, _ := GetLocalPhysicalIP()
	if physicalIP != nil {
		var bindAddr syscall.SockaddrInet4
		copy(bindAddr.Addr[:], physicalIP.To4())
		bindAddr.Port = 0
		if err := syscall.Bind(fd, &bindAddr); err != nil {
			logDebug("[BootstrapDNS] ⚠️ IP binding failed: %v (non-fatal)", err)
		} else {
			logDebug("[BootstrapDNS] ✅ DNS socket bound to IP: %s", physicalIP.String())
		}
	}
	
	// ============================================================
	// STEP 4: CONNECT AND SEND DNS QUERY
	// ============================================================
	sa := &syscall.SockaddrInet4{
		Port: addr.Port,
	}
	copy(sa.Addr[:], addr.IP.To4())
	
	err = syscall.Connect(fd, sa)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to DNS server: %w", err)
	}
	logDebug("[BootstrapDNS] ✅ Connected to DNS server: %s", dnsServer)
	
	// Build and send DNS query
	query := buildDNSQuery(domain)
	_, err = syscall.Write(fd, query)
	if err != nil {
		return nil, fmt.Errorf("failed to send DNS query: %w", err)
	}
	logDebug("[BootstrapDNS] ✅ DNS query sent for: %s", domain)
	
	// ============================================================
	// STEP 5: RECEIVE DNS RESPONSE WITH TIMEOUT
	// ============================================================
	buffer := make([]byte, 512)
	recvDone := make(chan struct {
		n   int
		err error
	}, 1)
	
	go func() {
		n, err := syscall.Read(fd, buffer)
		recvDone <- struct {
			n   int
			err error
		}{n, err}
	}()
	
	// Wait for response or timeout (3 seconds per server)
	select {
	case result := <-recvDone:
		if result.err != nil {
			return nil, fmt.Errorf("failed to receive DNS response: %w", result.err)
		}
		buffer = buffer[:result.n]
		logDebug("[BootstrapDNS] ✅ DNS response received (%d bytes)", result.n)
	case <-time.After(3 * time.Second):
		return nil, fmt.Errorf("DNS query timeout after 3 seconds")
	}
	
	// Parse DNS response
	ip, err := parseDNSResponse(buffer, domain)
	if err != nil {
		return nil, fmt.Errorf("failed to parse DNS response: %w", err)
	}
	
	return ip, nil
}

// buildDNSQuery builds a simple DNS A record query
func buildDNSQuery(domain string) []byte {
	// Simple DNS query builder (minimal implementation)
	// DNS header: ID (2 bytes) + Flags (2 bytes) + Questions (2 bytes) + Answers (2 bytes) + ...
	query := make([]byte, 12) // DNS header
	
	// Transaction ID (random, but we'll use 0x1234 for simplicity)
	query[0] = 0x12
	query[1] = 0x34
	
	// Flags: Standard query, recursion desired
	query[2] = 0x01
	query[3] = 0x00
	
	// Questions: 1
	query[4] = 0x00
	query[5] = 0x01
	
	// Answers, Authority, Additional: 0
	// (already zero)
	
	// Question section: domain name + type + class
	parts := strings.Split(domain, ".")
	for _, part := range parts {
		query = append(query, byte(len(part)))
		query = append(query, []byte(part)...)
	}
	query = append(query, 0x00) // End of domain name
	
	// Type: A (0x0001)
	query = append(query, 0x00, 0x01)
	
	// Class: IN (0x0001)
	query = append(query, 0x00, 0x01)
	
	return query
}

// parseDNSResponse parses DNS response and extracts IP address
func parseDNSResponse(response []byte, domain string) (net.IP, error) {
	if len(response) < 12 {
		return nil, fmt.Errorf("DNS response too short")
	}
	
	// Check response code (bits 0-3 of flags byte 3)
	rcode := response[3] & 0x0F
	if rcode != 0 {
		return nil, fmt.Errorf("DNS error code: %d", rcode)
	}
	
	// Check if we have answers
	answerCount := int(response[6])<<8 | int(response[7])
	if answerCount == 0 {
		return nil, fmt.Errorf("no answers in DNS response")
	}
	
	// Skip question section
	offset := 12
	// Skip domain name in question
	for offset < len(response) && response[offset] != 0 {
		length := int(response[offset])
		offset += length + 1
	}
	offset += 5 // Skip null terminator + type + class
	
	// Parse answer section
	for i := 0; i < answerCount && offset < len(response); i++ {
		// Skip name (pointer or full name)
		if response[offset]&0xC0 == 0xC0 {
			// Compressed name (pointer)
			offset += 2
		} else {
			// Full name
			for offset < len(response) && response[offset] != 0 {
				length := int(response[offset])
				offset += length + 1
			}
			offset++
		}
		
		// Type (2 bytes)
		if offset+2 > len(response) {
			break
		}
		typeCode := int(response[offset])<<8 | int(response[offset+1])
		offset += 2
		
		// Class (2 bytes)
		offset += 2
		
		// TTL (4 bytes)
		offset += 4
		
		// Data length (2 bytes)
		if offset+2 > len(response) {
			break
		}
		dataLength := int(response[offset])<<8 | int(response[offset+1])
		offset += 2
		
		// If this is an A record (type 1), extract IP
		if typeCode == 1 && dataLength == 4 {
			if offset+4 > len(response) {
				break
			}
			ip := net.IP(response[offset : offset+4])
			return ip, nil
		}
		
		offset += dataLength
	}
	
	return nil, fmt.Errorf("no A record found in DNS response")
}


// ============================================================
// PHYSICAL INTERFACE BINDING HELPERS
// ============================================================

// bindSocketToDevice binds a socket to a specific network interface using SO_BINDTODEVICE
// This is a Linux/Android-specific syscall that ensures traffic goes through the specified interface
// Note: This requires CAP_NET_RAW capability, which VpnService.protect() provides indirectly
func bindSocketToDevice(fd int, ifaceName string) error {
	if ifaceName == "" {
		return fmt.Errorf("interface name is empty")
	}
	
	// SO_BINDTODEVICE expects a null-terminated string
	// We use setsockoptString which handles this correctly
	err := setsockoptString(fd, syscall.SOL_SOCKET, SO_BINDTODEVICE, ifaceName)
	if err != nil {
		return fmt.Errorf("SO_BINDTODEVICE(%s) failed: %w", ifaceName, err)
	}
	
	return nil
}

// setsockoptString sets a string socket option (like SO_BINDTODEVICE)
// This is equivalent to setsockopt(fd, level, opt, ifname, strlen(ifname)+1)
func setsockoptString(fd, level, opt int, s string) error {
	// Convert string to null-terminated byte slice
	b := make([]byte, len(s)+1)
	copy(b, s)
	b[len(s)] = 0 // Null terminator
	
	// Use raw syscall for setsockopt
	_, _, errno := syscall.Syscall6(
		syscall.SYS_SETSOCKOPT,
		uintptr(fd),
		uintptr(level),
		uintptr(opt),
		uintptr(unsafe.Pointer(&b[0])),
		uintptr(len(b)),
		0,
	)
	
	if errno != 0 {
		return errno
	}
	return nil
}

// detectPhysicalInterface finds the first available physical network interface
// Returns empty string if no physical interface is found
func detectPhysicalInterface() string {
	for _, ifaceName := range physicalInterfaceNames {
		iface, err := net.InterfaceByName(ifaceName)
		if err != nil {
			continue // Interface doesn't exist
		}
		
		// Check if interface is up
		if iface.Flags&net.FlagUp == 0 {
			continue
		}
		
		// Check if interface has an IP address
		addrs, err := iface.Addrs()
		if err != nil || len(addrs) == 0 {
			continue
		}
		
		// Found a valid physical interface
		logDebug("[XrayDialer] Detected physical interface: %s (flags: %v)", ifaceName, iface.Flags)
		return ifaceName
	}
	
	return ""
}

// ClearBootstrapDNSCache is a no-op (DNS cache removed, using Google DNS directly)
func ClearBootstrapDNSCache() {
	logInfo("[GoogleDNS] No cache to clear (using Google DNS directly)")
}
