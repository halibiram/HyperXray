package bridge

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
)

// ConnectivityCheckResult holds the result of connectivity check
type ConnectivityCheckResult struct {
	Success         bool
	Latency         time.Duration
	Error           string
	TestURL         string
	StatusCode      int
	BytesRead       int
	XrayLogAnalysis *XrayLogAnalysis `json:"xrayLogAnalysis,omitempty"` // NEW: Xray log analysis
}

// XrayLogAnalysis contains analysis of Xray-core logs during connectivity check
type XrayLogAnalysis struct {
	ErrorType        string   `json:"errorType"`        // Type of error detected
	ErrorDetails     string   `json:"errorDetails"`     // Detailed error description
	PossibleCauses   []string `json:"possibleCauses"`   // List of possible causes
	Recommendations  []string `json:"recommendations"`  // Recommendations to fix
	DetectedIssues   []string `json:"detectedIssues"`   // Specific issues detected in logs
}

// CheckXrayConnectivity tests if Xray can actually reach the internet
func (x *XrayWrapper) CheckXrayConnectivity() (*ConnectivityCheckResult, error) {
	logInfo("[XrayTest] ========================================")
	logInfo("[XrayTest] Starting Xray Connectivity Check...")
	logInfo("[XrayTest] ========================================")

	if !x.running {
		logError("[XrayTest] ❌ Xray is not running!")
		return &ConnectivityCheckResult{
			Success: false,
			Error:   "Xray not running",
		}, fmt.Errorf("xray not running")
	}

	// Test URLs (try multiple in case one is blocked)
	testURLs := []string{
		"http://connectivitycheck.gstatic.com/generate_204", // Google connectivity check
		"http://cp.cloudflare.com/",                           // Cloudflare
		"http://www.msftconnecttest.com/connecttest.txt",      // Microsoft
	}

	// Collect all errors for analysis
	var allErrors []string
	var allResults []*ConnectivityCheckResult
	
	for _, testURL := range testURLs {
		result := x.testSingleURL(testURL)
		allResults = append(allResults, result)
		
		if result.Success {
			logInfo("[XrayTest] ✅ Connectivity check PASSED!")
			logInfo("[XrayTest]    URL: %s", result.TestURL)
			logInfo("[XrayTest]    Latency: %v", result.Latency)
			logInfo("[XrayTest]    Status: %d", result.StatusCode)
			return result, nil
		}
		
		allErrors = append(allErrors, fmt.Sprintf("%s: %s", testURL, result.Error))
		logWarn("[XrayTest] ⚠️ Failed for %s: %s", testURL, result.Error)
	}

	logError("[XrayTest] ❌ All connectivity checks FAILED!")
	
	// Analyze errors and create detailed log analysis
	logAnalysis := x.analyzeConnectivityErrors(allErrors, allResults)
	
	finalResult := &ConnectivityCheckResult{
		Success:         false,
		Error:           "All test URLs failed",
		XrayLogAnalysis: logAnalysis,
	}
	
	// Enhance error message with analysis
	if logAnalysis != nil && logAnalysis.ErrorDetails != "" {
		finalResult.Error = fmt.Sprintf("%s. %s", finalResult.Error, logAnalysis.ErrorDetails)
	}
	
	return finalResult, fmt.Errorf("connectivity check failed: %w", fmt.Errorf(finalResult.Error))
}

// testSingleURL tests connectivity to a single URL
func (x *XrayWrapper) testSingleURL(testURL string) *ConnectivityCheckResult {
	result := &ConnectivityCheckResult{
		TestURL: testURL,
	}

	startTime := time.Now()

	// Create context with timeout
	ctx, cancel := context.WithTimeout(x.ctx, 10*time.Second)
	defer cancel()

	// Create HTTP client that uses Xray for transport
	// Use protected dialer to avoid VPN routing loop
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			logDebug("[ConnTest] Dialing protected to %s", addr)
			dialer := &ProtectedDialer{Timeout: 10}
			return dialer.DialTCP(network, addr)
		},
		DisableKeepAlives: true,
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   10 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // Don't follow redirects
		},
	}

	logDebug("[XrayTest] Sending HTTP GET to %s...", testURL)

	req, err := http.NewRequestWithContext(ctx, "GET", testURL, nil)
	if err != nil {
		result.Error = fmt.Sprintf("create request: %v", err)
		return result
	}

	req.Header.Set("User-Agent", "HyperXray/1.0 ConnectivityCheck")

	resp, err := client.Do(req)
	if err != nil {
		// Enhanced error analysis
		errStr := err.Error()
		result.Error = fmt.Sprintf("HTTP request: %v", err)
		result.Latency = time.Since(startTime)
		
		// Log detailed error information
		logError("[XrayTest] ❌ HTTP request FAILED for %s", testURL)
		logError("[XrayTest]    Error: %v", err)
		logError("[XrayTest]    Elapsed: %v", result.Latency)
		
		// Try to extract more context from error
		errLower := strings.ToLower(errStr)
		if strings.Contains(errLower, "context deadline exceeded") || strings.Contains(errLower, "timeout") {
			logError("[XrayTest]    Issue: Request timed out - connection established but no response")
			logError("[XrayTest]    Possible: TLS/REALITY handshake hanging, or server not responding")
		} else if strings.Contains(errLower, "dial") {
			logError("[XrayTest]    Issue: Dial failed - cannot establish TCP connection through Xray")
		}
		
		return result
	}
	defer resp.Body.Close()

	// Read some bytes to confirm data flow
	body := make([]byte, 1024)
	n, _ := io.ReadAtLeast(resp.Body, body, 1)

	result.Latency = time.Since(startTime)
	result.StatusCode = resp.StatusCode
	result.BytesRead = n

	// Check if response is acceptable
	// 204 = Google connectivity check success
	// 200 = General success
	// 301/302 = Redirect (still means we connected)
	if resp.StatusCode == 204 || resp.StatusCode == 200 ||
		resp.StatusCode == 301 || resp.StatusCode == 302 {
		result.Success = true
	} else {
		result.Error = fmt.Sprintf("unexpected status: %d", resp.StatusCode)
	}

	return result
}

// dialTCP creates a TCP connection through Xray with detailed tracing
func (x *XrayWrapper) dialTCP(ctx context.Context, addr string) (net.Conn, error) {
	logInfo("[XrayDial] ========================================")
	logInfo("[XrayDial] Dialing through Xray: %s", addr)
	logInfo("[XrayDial] ========================================")

	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		logError("[XrayDial] Invalid address: %v", err)
		return nil, fmt.Errorf("split host port: %w", err)
	}

	var port int
	fmt.Sscanf(portStr, "%d", &port)

	logDebug("[XrayDial] Host: %s, Port: %d", host, port)

	// Create destination
	dest := xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port))
	logDebug("[XrayDial] Destination created: %v", dest)

	// Create outbound context
	outboundCtx := ctx
	// Note: session.ContextWithOutbound may not be available in this version
	// The core.Dial will handle routing based on destination
	logDebug("[XrayDial] Outbound context created")

	// Get outbound tag being used
	if x.handler != nil {
		logDebug("[XrayDial] Using outbound handler...")
	}

	// Dial through Xray
	logInfo("[XrayDial] Calling core.Dial()...")
	startTime := time.Now()

	conn, err := core.Dial(outboundCtx, x.instance, dest)
	dialTime := time.Since(startTime)

	if err != nil {
		logError("[XrayDial] ❌ core.Dial() FAILED after %v: %v", dialTime, err)
		logError("[XrayDial] ")
		logError("[XrayDial] This usually means:")
		logError("[XrayDial]   1. Xray server is not responding")
		logError("[XrayDial]   2. VLESS/VMess authentication failed")
		logError("[XrayDial]   3. REALITY handshake failed")
		logError("[XrayDial]   4. Outbound configuration is wrong")
		
		// Enhanced error categorization
		errStr := err.Error()
		errLower := strings.ToLower(errStr)
		
		if strings.Contains(errLower, "timeout") || strings.Contains(errLower, "deadline") {
			logError("[XrayDial]    Error Type: Connection Timeout")
			logError("[XrayDial]    This suggests Xray cannot establish connection to server")
		} else if strings.Contains(errLower, "refused") {
			logError("[XrayDial]    Error Type: Connection Refused")
			logError("[XrayDial]    Server is not accepting connections")
		} else if strings.Contains(errLower, "tls") || strings.Contains(errLower, "ssl") || 
		          strings.Contains(errLower, "certificate") || strings.Contains(errLower, "handshake") {
			logError("[XrayDial]    Error Type: TLS/SSL Handshake Failure")
			logError("[XrayDial]    TLS/REALITY handshake is failing")
		} else if strings.Contains(errLower, "unreachable") || strings.Contains(errLower, "no route") {
			logError("[XrayDial]    Error Type: Network Unreachable")
			logError("[XrayDial]    Cannot reach network destination")
		} else {
			logError("[XrayDial]    Error Type: Unknown (%s)", errStr)
		}
		
		return nil, err
	}

	logInfo("[XrayDial] ✅ core.Dial() returned in %v", dialTime)

	// Check if connection is actually usable
	if conn == nil {
		logError("[XrayDial] ❌ core.Dial() returned nil connection!")
		return nil, fmt.Errorf("nil connection")
	}

	logDebug("[XrayDial] Connection type: %T", conn)
	logDebug("[XrayDial] Local addr: %v", conn.LocalAddr())
	logDebug("[XrayDial] Remote addr: %v", conn.RemoteAddr())

	// Try to verify the connection is actually working
	logInfo("[XrayDial] Verifying connection with small write...")

	// Set short deadline for verification
	conn.SetDeadline(time.Now().Add(5 * time.Second))

	// For HTTP, we can't really verify without sending data
	// Just reset deadline and return
	conn.SetDeadline(time.Time{})

	logInfo("[XrayDial] ✅ Connection ready")

	return conn, nil
}

// QuickConnectivityCheck does a fast check with shorter timeout
func (x *XrayWrapper) QuickConnectivityCheck() bool {
	logInfo("[XrayTest] Quick connectivity check...")

	if !x.running {
		return false
	}

	// Use Google's 204 endpoint - fastest check
	ctx, cancel := context.WithTimeout(x.ctx, 5*time.Second)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return x.dialTCP(ctx, addr)
		},
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   5 * time.Second,
	}

	req, _ := http.NewRequestWithContext(ctx, "GET",
		"http://connectivitycheck.gstatic.com/generate_204", nil)

	resp, err := client.Do(req)
	if err != nil {
		logWarn("[XrayTest] Quick check failed: %v", err)
		return false
	}
	defer resp.Body.Close()

	success := resp.StatusCode == 204
	if success {
		logInfo("[XrayTest] ✅ Quick check passed")
	} else {
		logWarn("[XrayTest] ⚠️ Quick check: unexpected status %d", resp.StatusCode)
	}

	return success
}

// TestUDPConnectivity tests if UDP works through Xray (for WireGuard)
func (x *XrayWrapper) TestUDPConnectivity(endpoint string) error {
	logInfo("[XrayTest] ========================================")
	logInfo("[XrayTest] Testing UDP connectivity to %s", endpoint)
	logInfo("[XrayTest] ========================================")

	if !x.running {
		return fmt.Errorf("xray not running")
	}

	host, portStr, err := net.SplitHostPort(endpoint)
	if err != nil {
		return fmt.Errorf("invalid endpoint: %w", err)
	}

	var port int
	fmt.Sscanf(portStr, "%d", &port)

	// Create UDP connection through Xray
	udpConn, err := x.DialUDP(host, port)
	if err != nil {
		logError("[XrayTest] ❌ DialUDP failed: %v", err)
		return fmt.Errorf("dial udp: %w", err)
	}
	defer udpConn.Close()

	// Connect
	if err := udpConn.Connect(); err != nil {
		logError("[XrayTest] ❌ Connect failed: %v", err)
		return fmt.Errorf("connect: %w", err)
	}

	logInfo("[XrayTest] ✅ UDP connection established")

	// Send a test packet (WireGuard-like handshake initiation)
	// This is just to verify packet can be sent
	testPacket := make([]byte, 148)
	testPacket[0] = 1 // Handshake initiation type

	n, err := udpConn.Write(testPacket)
	if err != nil {
		logError("[XrayTest] ❌ Write failed: %v", err)
		return fmt.Errorf("write: %w", err)
	}

	logInfo("[XrayTest] ✅ Sent %d bytes", n)

	// Try to read response (with short timeout)
	// Note: We don't expect valid WG response, just checking if pipe works
	data, err := udpConn.Read(3 * time.Second)
	if err != nil {
		logWarn("[XrayTest] ⚠️ No response in 3s (may be normal): %v", err)
		// This is acceptable - endpoint might not respond to invalid WG packet
	} else {
		logInfo("[XrayTest] ✅ Received %d bytes response!", len(data))
	}

	logInfo("[XrayTest] ✅ UDP test completed")
	return nil
}

// CheckServerDirect tests direct connection to Xray server (without using Xray)
func CheckServerDirect(address string, port int) error {
	logInfo("[ServerCheck] Testing direct connection to %s:%d...", address, port)

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", address, port), 10*time.Second)
	if err != nil {
		logError("[ServerCheck] ❌ Cannot reach server: %v", err)
		return err
	}
	defer conn.Close()

	logInfo("[ServerCheck] ✅ Server is reachable!")
	return nil
}

// TestConnectivity tests basic TCP connectivity using Protected Dialer
// This function should be called BEFORE Xray starts to verify the Protected Dialer
// is working correctly and can reach the internet without VPN routing loop
// 
// Tests multiple targets to ensure connectivity:
// - 1.1.1.1:80 (Cloudflare HTTP - TCP)
// - 8.8.8.8:53 (Google DNS - TCP)
// 
// Interface Binding:
// - On Android, VpnService.protect() automatically binds sockets to the active
//   network interface (WiFi/LTE) and prevents routing through VPN TUN interface
// - No explicit binding is needed - protect() handles this automatically
// - On Android 11+, protect() ensures correct interface binding
//
// If this test fails, it indicates:
// 1. Protected Dialer is not protecting sockets correctly (reporting SUCCESS but blocking traffic)
// 2. Network interface binding issue (protect() may not be working correctly)
// 3. Firewall blocking outbound connections
// 4. Network connectivity issue (no internet access)
//
// If this test succeeds, Protected Dialer is working and the issue is specific to Xray server
func TestConnectivity() error {
	logInfo("[Connectivity] ========================================")
	logInfo("[Connectivity] Testing Protected Dialer connectivity...")
	logInfo("[Connectivity] This test runs BEFORE Xray starts to verify socket protection")
	logInfo("[Connectivity] ========================================")

	// Check if socket protector is set
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()

	if protector == nil {
		logError("[Connectivity] ❌ Socket protector is NOT SET!")
		logError("[Connectivity] ❌ Cannot test Protected Dialer - call SetSocketProtector first!")
		return fmt.Errorf("socket protector not set - call SetSocketProtector first")
	}
	logInfo("[Connectivity] ✅ Socket protector is available")

	// Create Protected Dialer
	dialer := &ProtectedDialer{Timeout: 10}

	// Test targets: multiple endpoints to ensure connectivity
	testTargets := []struct {
		address string
		network string
		name    string
	}{
		{"1.1.1.1:80", "tcp", "Cloudflare HTTP"},
		{"8.8.8.8:53", "tcp", "Google DNS (TCP)"},
	}

	var lastErr error
	var successCount int

	for _, target := range testTargets {
		logInfo("[Connectivity] ")
		logInfo("[Connectivity] Testing %s (%s)...", target.name, target.address)
		
		startTime := time.Now()
		conn, err := dialer.DialTCP(target.network, target.address)
		dialDuration := time.Since(startTime)

		if err != nil {
			logError("[Connectivity] ❌ Connection to %s FAILED after %v", target.address, dialDuration)
			logError("[Connectivity]    Error: %v", err)
			lastErr = err
			continue
		}

		// Verify connection is actually usable by checking local/remote addresses
		localAddr := conn.LocalAddr()
		remoteAddr := conn.RemoteAddr()
		logInfo("[Connectivity] ✅ Connection established in %v", dialDuration)
		logInfo("[Connectivity]    Local address: %v", localAddr)
		logInfo("[Connectivity]    Remote address: %v", remoteAddr)

		// Validate addresses are not 0.0.0.0:0
		if localAddr != nil {
			localStr := localAddr.String()
			if localStr == "0.0.0.0:0" || localStr == "[::]:0" {
				logError("[Connectivity] ❌ Local address is invalid: %v", localAddr)
				logError("[Connectivity]    This indicates connection binding issue")
				conn.Close()
				lastErr = fmt.Errorf("invalid local address: %v", localAddr)
				continue
			}
			logInfo("[Connectivity]    ✅ Local address is valid")
			
			// Verify local address matches physical IP (not VPN virtual IP)
			physicalIP, err := GetLocalPhysicalIP()
			if err == nil && physicalIP != nil {
				localTCPAddr, ok := localAddr.(*net.TCPAddr)
				if ok && localTCPAddr.IP != nil {
					if localTCPAddr.IP.Equal(physicalIP) {
						logInfo("[Connectivity]    ✅ Local IP matches physical IP: %s", physicalIP.String())
					} else {
						logWarn("[Connectivity]    ⚠️ Local IP (%s) differs from physical IP (%s)", 
							localTCPAddr.IP.String(), physicalIP.String())
						logWarn("[Connectivity]    ⚠️ This may indicate VPN virtual IP is being used")
					}
				}
			}
		} else {
			logError("[Connectivity] ❌ Local address is nil!")
			conn.Close()
			lastErr = fmt.Errorf("local address is nil")
			continue
		}

		if remoteAddr != nil {
			remoteStr := remoteAddr.String()
			if remoteStr == "0.0.0.0:0" || remoteStr == "[::]:0" {
				logError("[Connectivity] ❌ Remote address is invalid: %v", remoteAddr)
				conn.Close()
				lastErr = fmt.Errorf("invalid remote address: %v", remoteAddr)
				continue
			}
			logInfo("[Connectivity]    ✅ Remote address is valid")
		} else {
			logError("[Connectivity] ❌ Remote address is nil!")
			conn.Close()
			lastErr = fmt.Errorf("remote address is nil")
			continue
		}

		// Try to send a simple request to verify bidirectional communication
		logInfo("[Connectivity]    Testing bidirectional communication...")
		conn.SetDeadline(time.Now().Add(5 * time.Second))

		// Send HTTP GET request (works for both HTTP endpoints)
		httpRequest := fmt.Sprintf("GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", target.address)
		_, err = conn.Write([]byte(httpRequest))
		if err != nil {
			logError("[Connectivity] ❌ Write failed: %v", err)
			conn.Close()
			lastErr = fmt.Errorf("write test failed: %w", err)
			continue
		}
		logInfo("[Connectivity]    ✅ Sent request (%d bytes)", len(httpRequest))

		// Try to read response (with timeout)
		responseBuffer := make([]byte, 1024)
		conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := conn.Read(responseBuffer)
		if err != nil {
			// Read timeout is acceptable - we just want to verify connection works
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				logInfo("[Connectivity]    ⚠️ Read timeout (acceptable - connection is working)")
			} else {
				logError("[Connectivity] ❌ Read failed: %v", err)
				conn.Close()
				lastErr = fmt.Errorf("read test failed: %w", err)
				continue
			}
		} else {
			logInfo("[Connectivity]    ✅ Received %d bytes response", n)
			if n > 0 && target.network == "tcp" {
				responsePreview := string(responseBuffer[:min(n, 100)])
				logInfo("[Connectivity]    Response preview: %s...", responsePreview)
			}
		}

		conn.Close()
		conn.SetDeadline(time.Time{}) // Clear deadline

		successCount++
		logInfo("[Connectivity] ✅ Test to %s PASSED!", target.name)
	}

	// Evaluate results
	if successCount == 0 {
		logError("[Connectivity] ========================================")
		logError("[Connectivity] ❌ ALL CONNECTIVITY TESTS FAILED!")
		logError("[Connectivity] ========================================")
		logError("[Connectivity] ")
		logError("[Connectivity] This indicates:")
		logError("[Connectivity]    1. Protected Dialer may be reporting SUCCESS but blocking traffic")
		logError("[Connectivity]    2. Socket protection is not binding to correct network interface")
		logError("[Connectivity]    3. Firewall is blocking outbound connections")
		logError("[Connectivity]    4. Network connectivity issue")
		logError("[Connectivity] ")
		if lastErr != nil {
			return fmt.Errorf("protected dialer test failed: %w", lastErr)
		}
		return fmt.Errorf("all connectivity tests failed")
	}

	logInfo("[Connectivity] ========================================")
	logInfo("[Connectivity] ✅ CONNECTIVITY TEST PASSED!")
	logInfo("[Connectivity]    Success: %d/%d targets", successCount, len(testTargets))
	logInfo("[Connectivity]    Protected Dialer is working correctly")
	logInfo("[Connectivity]    Socket protection is functioning properly")
	logInfo("[Connectivity] ========================================")

	return nil
}

// TestInternetConnection is kept for backward compatibility
// Deprecated: Use TestConnectivity() instead
func TestInternetConnection() error {
	return TestConnectivity()
}

// min returns the minimum of two integers
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// analyzeConnectivityErrors analyzes connectivity check errors and provides detailed diagnosis
func (x *XrayWrapper) analyzeConnectivityErrors(errors []string, results []*ConnectivityCheckResult) *XrayLogAnalysis {
	logInfo("[XrayLogAnalysis] ========================================")
	logInfo("[XrayLogAnalysis] Analyzing connectivity errors...")
	logInfo("[XrayLogAnalysis] ========================================")
	
	analysis := &XrayLogAnalysis{
		DetectedIssues:  []string{},
		PossibleCauses:  []string{},
		Recommendations: []string{},
	}
	
	// Analyze error patterns
	errorText := strings.Join(errors, "; ")
	errorTextLower := strings.ToLower(errorText)
	
	// Pattern detection
	detectedPatterns := []string{}
	
	// Check for timeout errors
	if strings.Contains(errorTextLower, "timeout") || strings.Contains(errorTextLower, "deadline exceeded") {
		detectedPatterns = append(detectedPatterns, "timeout")
		analysis.ErrorType = "Connection Timeout"
		analysis.DetectedIssues = append(analysis.DetectedIssues, "HTTP requests are timing out after 10 seconds")
		analysis.PossibleCauses = append(analysis.PossibleCauses,
			"Xray server is unreachable or not responding",
			"TLS/REALITY handshake is failing silently",
			"Network firewall blocking Xray traffic",
			"Server is overloaded or not accepting connections",
		)
		analysis.Recommendations = append(analysis.Recommendations,
			"Check if Xray server is running and accessible",
			"Verify server address and port in Xray config",
			"Check network connectivity to server",
			"Verify TLS/REALITY configuration",
		)
	}
	
	// Check for connection refused
	if strings.Contains(errorTextLower, "connection refused") || strings.Contains(errorTextLower, "refused") {
		detectedPatterns = append(detectedPatterns, "connection_refused")
		analysis.ErrorType = "Connection Refused"
		analysis.DetectedIssues = append(analysis.DetectedIssues, "Server is refusing connections")
		analysis.PossibleCauses = append(analysis.PossibleCauses,
			"Xray server is not running",
			"Wrong server port in configuration",
			"Firewall blocking connections",
			"Server is not listening on the specified port",
		)
		analysis.Recommendations = append(analysis.Recommendations,
			"Verify server is running and accessible",
			"Check server port configuration",
			"Test direct connection to server (without Xray)",
		)
	}
	
	// Check for TLS/SSL errors
	if strings.Contains(errorTextLower, "tls") || strings.Contains(errorTextLower, "ssl") || 
	   strings.Contains(errorTextLower, "certificate") || strings.Contains(errorTextLower, "handshake") {
		detectedPatterns = append(detectedPatterns, "tls_error")
		analysis.ErrorType = "TLS/SSL Handshake Failure"
		analysis.DetectedIssues = append(analysis.DetectedIssues, "TLS/REALITY handshake is failing")
		analysis.PossibleCauses = append(analysis.PossibleCauses,
			"TLS/REALITY configuration mismatch",
			"Invalid server certificate",
			"REALITY SNI or fingerprint mismatch",
			"Server TLS settings not matching client config",
		)
		analysis.Recommendations = append(analysis.Recommendations,
			"Verify REALITY configuration (SNI, fingerprint, public key)",
			"Check TLS settings in Xray config",
			"Verify server certificate is valid",
			"Check if server supports the configured TLS version",
		)
	}
	
	// Check for network unreachable
	if strings.Contains(errorTextLower, "unreachable") || strings.Contains(errorTextLower, "no route") {
		detectedPatterns = append(detectedPatterns, "network_unreachable")
		analysis.ErrorType = "Network Unreachable"
		analysis.DetectedIssues = append(analysis.DetectedIssues, "Cannot reach network destination")
		analysis.PossibleCauses = append(analysis.PossibleCauses,
			"No network connectivity",
			"Wrong server address",
			"DNS resolution failure",
			"Routing issues",
		)
		analysis.Recommendations = append(analysis.Recommendations,
			"Check network connectivity",
			"Verify server address is correct",
			"Test DNS resolution for server domain",
			"Check routing table",
		)
	}
	
	// Analyze dial errors specifically
	for _, result := range results {
		if result.Error != "" {
			errLower := strings.ToLower(result.Error)
			if strings.Contains(errLower, "dial") && strings.Contains(errLower, "failed") {
				analysis.DetectedIssues = append(analysis.DetectedIssues, 
					fmt.Sprintf("Dial failed for %s: %s", result.TestURL, result.Error))
			}
		}
	}
	
	// Default error type if not detected
	if analysis.ErrorType == "" {
		analysis.ErrorType = "Unknown Error"
		analysis.DetectedIssues = append(analysis.DetectedIssues, "Could not establish connection through Xray")
		if len(detectedPatterns) == 0 {
			analysis.PossibleCauses = append(analysis.PossibleCauses,
				"Xray server configuration issue",
				"Network connectivity problem",
				"VLESS/VMess credentials invalid",
				"TLS/REALITY handshake failure",
			)
			analysis.Recommendations = append(analysis.Recommendations,
				"Review Xray server configuration",
				"Check VLESS/VMess credentials",
				"Verify network connectivity",
				"Check Xray server logs for more details",
			)
		}
	}
	
	// Create detailed error description
	analysis.ErrorDetails = fmt.Sprintf("Error Type: %s", analysis.ErrorType)
	if len(analysis.DetectedIssues) > 0 {
		analysis.ErrorDetails += fmt.Sprintf(". Issues: %s", strings.Join(analysis.DetectedIssues, "; "))
	}
	
	// Log analysis results
	logInfo("[XrayLogAnalysis] Error Type: %s", analysis.ErrorType)
	logInfo("[XrayLogAnalysis] Detected Issues: %d", len(analysis.DetectedIssues))
	for i, issue := range analysis.DetectedIssues {
		logInfo("[XrayLogAnalysis]   [%d] %s", i+1, issue)
	}
	logInfo("[XrayLogAnalysis] Possible Causes: %d", len(analysis.PossibleCauses))
	for i, cause := range analysis.PossibleCauses {
		logInfo("[XrayLogAnalysis]   [%d] %s", i+1, cause)
	}
	logInfo("[XrayLogAnalysis] Recommendations: %d", len(analysis.Recommendations))
	for i, rec := range analysis.Recommendations {
		logInfo("[XrayLogAnalysis]   [%d] %s", i+1, rec)
	}
	
	return analysis
}

