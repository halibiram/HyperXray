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
		// Domain name - resolve it first
		domain := dest.Address.Domain()
		logInfo("[XrayDialer] Resolving domain: %s", domain)
		
		// Use system DNS resolver
		ips, err := net.LookupIP(domain)
		if err != nil || len(ips) == 0 {
			logError("[XrayDialer] ❌ DNS resolution failed for %s: %v", domain, err)
			return nil, fmt.Errorf("dns resolution failed: %w", err)
		}
		
		// Prefer IPv4
		var selectedIP net.IP
		for _, ip := range ips {
			if ip.To4() != nil {
				selectedIP = ip
				break
			}
		}
		if selectedIP == nil {
			selectedIP = ips[0]
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
	} else {
		logInfo("[XrayDialer] ✅ Physical IP found: %s", physicalIP.String())
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
					
					if err := syscall.Bind(fdInt, &bindAddr); err != nil {
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

