package bridge

import (
	"context"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

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
		logWarn("[XrayDialer] ⚠️ Physical IP is nil (cache miss), will proceed without binding")
	}

	// Use net.Dialer with Control callback for socket protection and source binding
	// This is the modern, platform-independent approach
	dialer := net.Dialer{
		Timeout:   30 * time.Second,
		KeepAlive: 30 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			var protectErr error
			var bindErr error
			err := c.Control(func(fd uintptr) {
				fdInt := int(fd)
				
				// CRITICAL: Protect socket FIRST, before any other operations
				// On Android, socket must be protected immediately after creation
				// to prevent it from being routed through VPN
				logInfo("[XrayDialer] Protecting socket fd: %d (BEFORE bind)...", fdInt)
				if !ProtectSocket(fdInt) {
					protectErr = fmt.Errorf("failed to protect socket %d", fdInt)
					logError("[XrayDialer] ❌ Protection result: FAILED (socket %d)", fdInt)
					return // Don't continue if protection fails
				} else {
					logInfo("[XrayDialer] ✅ Protection result: SUCCESS (socket %d protected)", fdInt)
				}
				
				// Then, bind to physical IP if available
				// This ensures packets use physical interface, not VPN
				// Note: Binding may fail on Android due to permissions, but protection is more important
				if physicalIP != nil && network == "tcp" {
					var bindAddr syscall.SockaddrInet4
					copy(bindAddr.Addr[:], physicalIP.To4())
					bindAddr.Port = 0 // Let OS choose port
					
					// Convert fd to appropriate type for syscall.Bind
					// On Android, syscall.Socket returns int, so we can use it directly
					if err := syscall.Bind(int(fd), &bindAddr); err != nil {
						bindErr = fmt.Errorf("failed to bind to physical IP %s: %w", physicalIP.String(), err)
						logWarn("[XrayDialer] ⚠️ Bind failed (non-fatal): %v", bindErr)
						// Bind failure is not fatal - socket protection is more important
					} else {
						logInfo("[XrayDialer] ✅ Socket bound to physical IP: %s", physicalIP.String())
					}
				}
			})
			if err != nil {
				return err
			}
			// Protection error is fatal - return it
			if protectErr != nil {
				return protectErr
			}
			// Bind error is not fatal - just log it
			if bindErr != nil {
				logWarn("[XrayDialer] ⚠️ Bind failed but continuing (protection succeeded): %v", bindErr)
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

// resolveDomainProtected resolves a domain name using protected DNS resolver
// This ensures DNS queries go through physical network, not VPN
func resolveDomainProtected(domain string) (net.IP, error) {
	// Try multiple DNS servers in order
	dnsServers := []string{
		"8.8.8.8:53",      // Google DNS
		"1.1.1.1:53",      // Cloudflare DNS
		"8.8.4.4:53",      // Google DNS secondary
		"1.0.0.1:53",      // Cloudflare DNS secondary
	}
	
	var lastErr error
	for _, dnsServer := range dnsServers {
		logInfo("[XrayDialer] Trying DNS server: %s for domain: %s", dnsServer, domain)
		
		ip, err := resolveDomainUDPProtected(domain, dnsServer)
		if err == nil {
			logInfo("[XrayDialer] ✅ DNS resolution successful via %s: %s → %s", dnsServer, domain, ip.String())
			return ip, nil
		}
		
		logWarn("[XrayDialer] ⚠️ DNS resolution failed via %s: %v", dnsServer, err)
		lastErr = err
	}
	
	return nil, fmt.Errorf("all DNS servers failed, last error: %w", lastErr)
}

// resolveDomainUDPProtected resolves domain using UDP DNS query with protected socket
func resolveDomainUDPProtected(domain, dnsServer string) (net.IP, error) {
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
	
	// Protect the socket BEFORE any network operations
	// Convert fd to int for ProtectSocket
	fdInt := int(fd)
	if !ProtectSocket(fdInt) {
		return nil, fmt.Errorf("failed to protect DNS query socket")
	}
	logInfo("[XrayDialer] ✅ DNS query socket protected")
	
	// Build simple DNS query (A record)
	query := buildDNSQuery(domain)
	
	// Send DNS query
	sa := &syscall.SockaddrInet4{
		Port: addr.Port,
	}
	copy(sa.Addr[:], addr.IP.To4())
	
	err = syscall.Sendto(fd, query, 0, sa)
	if err != nil {
		return nil, fmt.Errorf("failed to send DNS query: %w", err)
	}
	
	// Receive DNS response with timeout using goroutine and channel
	buffer := make([]byte, 512)
	recvDone := make(chan struct {
		n   int
		err error
	}, 1)
	
	go func() {
		n, _, err := syscall.Recvfrom(fd, buffer, 0)
		recvDone <- struct {
			n   int
			err error
		}{n, err}
	}()
	
	// Wait for response or timeout (5 seconds)
	select {
	case result := <-recvDone:
		if result.err != nil {
			return nil, fmt.Errorf("failed to receive DNS response: %w", result.err)
		}
		buffer = buffer[:result.n]
	case <-time.After(5 * time.Second):
		return nil, fmt.Errorf("DNS query timeout after 5 seconds")
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

