package bridge

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
)

// ConnectivityCheckResult holds the result of connectivity check
type ConnectivityCheckResult struct {
	Success    bool
	Latency    time.Duration
	Error      string
	TestURL    string
	StatusCode int
	BytesRead  int
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

	for _, testURL := range testURLs {
		result := x.testSingleURL(testURL)
		if result.Success {
			logInfo("[XrayTest] ✅ Connectivity check PASSED!")
			logInfo("[XrayTest]    URL: %s", result.TestURL)
			logInfo("[XrayTest]    Latency: %v", result.Latency)
			logInfo("[XrayTest]    Status: %d", result.StatusCode)
			return result, nil
		}
		logWarn("[XrayTest] ⚠️ Failed for %s: %s", testURL, result.Error)
	}

	logError("[XrayTest] ❌ All connectivity checks FAILED!")
	return &ConnectivityCheckResult{
		Success: false,
		Error:   "All test URLs failed",
	}, fmt.Errorf("connectivity check failed")
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
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return x.dialTCP(ctx, addr)
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
		result.Error = fmt.Sprintf("HTTP request: %v", err)
		result.Latency = time.Since(startTime)
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

// dialTCP creates a TCP connection through Xray
func (x *XrayWrapper) dialTCP(ctx context.Context, addr string) (net.Conn, error) {
	logDebug("[XrayTest] Dialing TCP through Xray: %s", addr)

	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, fmt.Errorf("split host port: %w", err)
	}

	var port int
	fmt.Sscanf(portStr, "%d", &port)

	// Create destination
	dest := xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port))

	// Dial through Xray
	// Note: We use the context directly as session.ContextWithOutbound may not be available
	conn, err := core.Dial(ctx, x.instance, dest)
	if err != nil {
		logError("[XrayTest] core.Dial failed: %v", err)
		return nil, err
	}

	logDebug("[XrayTest] ✅ TCP connection established through Xray")
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




