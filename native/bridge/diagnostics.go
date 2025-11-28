package bridge

import (
	"crypto/tls"
	"fmt"
	"net"
	"strings"
	"time"
)

// DiagnosticResult holds diagnostic results
type DiagnosticResult struct {
	ServerReachable bool
	ServerLatency   time.Duration
	TLSHandshake    bool
	TLSVersion      string
	TLSCipherSuite  string
	Error           string
}

// DiagnoseXrayServer tests direct connectivity to Xray server using PROTECTED sockets
func DiagnoseXrayServer(address string, port int) *DiagnosticResult {
	result := &DiagnosticResult{}
	target := fmt.Sprintf("%s:%d", address, port)

	logInfo("[Diag] ========================================")
	logInfo("[Diag] Diagnosing Xray Server: %s (using protected socket)", target)
	logInfo("[Diag] ========================================")

	// Test 1: TCP Connection (PROTECTED)
	logInfo("[Diag] Test 1: TCP Connection (protected)...")
	startTime := time.Now()

	// Check if socket protector is set before attempting connection
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()
	
	if protector == nil {
		result.Error = "Socket protector not set - call initSocketProtector() first"
		logError("[Diag] ❌ Socket protector is NOT SET!")
		logError("[Diag] ❌ Cannot connect to server without socket protector")
		logError("[Diag] ")
		logError("[Diag] SOLUTION: Call initSocketProtector() before StartHyperTunnel()")
		return result
	}
	logInfo("[Diag] ✅ Socket protector is available")

	// Use protected dialer!
	logInfo("[Diag] Attempting protected TCP connection to %s...", target)
	dialer := &ProtectedDialer{Timeout: 10}
	conn, err := dialer.DialTCP("tcp", target)
	if err != nil {
		result.Error = fmt.Sprintf("Protected TCP failed: %v", err)
		logError("[Diag] ❌ Protected TCP connection FAILED: %v", err)
		logError("[Diag] ")
		logError("[Diag] Error details:")
		errStr := err.Error()
		if strings.Contains(errStr, "socket protector not set") {
			logError("[Diag]   - Socket protector was not initialized")
			logError("[Diag]   - SOLUTION: Call initSocketProtector() before StartHyperTunnel()")
		} else if strings.Contains(errStr, "resolve address") || strings.Contains(errStr, "DNS") {
			logError("[Diag]   - DNS resolution failed")
			logError("[Diag]   - Check if server address is correct: %s", address)
			logError("[Diag]   - Check network connectivity")
		} else if strings.Contains(errStr, "connection timeout") {
			logError("[Diag]   - Connection timed out after 10 seconds")
			logError("[Diag]   - Server might be down or unreachable")
			logError("[Diag]   - Check firewall rules")
		} else if strings.Contains(errStr, "connection refused") {
			logError("[Diag]   - Connection refused by server")
			logError("[Diag]   - Server might be down or port is wrong")
			logError("[Diag]   - Check server address and port: %s:%d", address, port)
		} else {
			logError("[Diag]   - Unknown error: %s", errStr)
		}
		logError("[Diag] ")
		logError("[Diag] Possible causes:")
		logError("[Diag]   - Server is down")
		logError("[Diag]   - Wrong address/port")
		logError("[Diag]   - Firewall blocking")
		logError("[Diag]   - DNS resolution failed")
		logError("[Diag]   - Socket protector not working")
		return result
	}

	result.ServerReachable = true
	result.ServerLatency = time.Since(startTime)
	logInfo("[Diag] ✅ TCP connection successful! Latency: %v", result.ServerLatency)

	// Test 2: TLS Handshake
	logInfo("[Diag] Test 2: TLS Handshake...")

	tlsConfig := &tls.Config{
		ServerName:         address,
		InsecureSkipVerify: true, // For testing only
	}

	tlsConn := tls.Client(conn, tlsConfig)
	tlsConn.SetDeadline(time.Now().Add(10 * time.Second))

	err = tlsConn.Handshake()
	if err != nil {
		result.Error = fmt.Sprintf("TLS handshake failed: %v", err)
		logError("[Diag] ❌ TLS handshake FAILED: %v", err)
		logError("[Diag] ")
		logError("[Diag] Server is reachable but TLS failed!")
		logError("[Diag] This could affect REALITY protocol.")
		conn.Close()
		return result
	}

	result.TLSHandshake = true
	state := tlsConn.ConnectionState()
	result.TLSVersion = tlsVersionString(state.Version)
	result.TLSCipherSuite = tls.CipherSuiteName(state.CipherSuite)

	logInfo("[Diag] ✅ TLS handshake successful!")
	logInfo("[Diag]    Version: %s", result.TLSVersion)
	logInfo("[Diag]    Cipher: %s", result.TLSCipherSuite)

	tlsConn.Close()

	logInfo("[Diag] ")
	logInfo("[Diag] ========================================")
	logInfo("[Diag] ✅ Server diagnostics PASSED!")
	logInfo("[Diag] ========================================")

	return result
}

func tlsVersionString(version uint16) string {
	switch version {
	case tls.VersionTLS10:
		return "TLS 1.0"
	case tls.VersionTLS11:
		return "TLS 1.1"
	case tls.VersionTLS12:
		return "TLS 1.2"
	case tls.VersionTLS13:
		return "TLS 1.3"
	default:
		return fmt.Sprintf("Unknown (0x%x)", version)
	}
}

// DiagnoseNetwork performs basic network diagnostics using PROTECTED sockets
func DiagnoseNetwork() {
	logInfo("[Diag] ========================================")
	logInfo("[Diag] Network Diagnostics (using protected sockets)")
	logInfo("[Diag] ========================================")

	// Test DNS resolution
	logInfo("[Diag] Test: DNS Resolution...")

	testHosts := []string{
		"stol.halibiram.online",
		"google.com",
		"cloudflare.com",
	}

	for _, host := range testHosts {
		ips, err := net.LookupIP(host)
		if err != nil {
			logWarn("[Diag] ⚠️ DNS lookup failed for %s: %v", host, err)
		} else {
			logInfo("[Diag] ✅ %s → %v", host, ips)
		}
	}

	// Test basic TCP to known servers (PROTECTED)
	logInfo("[Diag] ")
	logInfo("[Diag] Test: TCP connectivity to common servers (protected)...")

	dialer := &ProtectedDialer{Timeout: 5}
	
	testServers := []string{
		"google.com:443",
		"cloudflare.com:443",
		"1.1.1.1:443",
	}

	for _, server := range testServers {
		conn, err := dialer.DialTCP("tcp", server)
		if err != nil {
			logWarn("[Diag] ⚠️ %s failed: %v", server, err)
		} else {
			logInfo("[Diag] ✅ %s successful", server)
			conn.Close()
		}
	}
}

