package bridge

import (
	"context"
	"fmt"
	"strings"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/xtls/xray-core/app/stats/command"
)

// XrayGrpcClient wraps gRPC connection to Xray-core StatsService
type XrayGrpcClient struct {
	conn   *grpc.ClientConn
	client command.StatsServiceClient
	port   int
}

// NewXrayGrpcClient creates a new gRPC client connection to Xray-core
func NewXrayGrpcClient(apiPort int) (*XrayGrpcClient, error) {
	logInfo("[XrayGrpc] Creating gRPC client for port %d...", apiPort)

	// Connect to localhost (Xray-core runs in same process)
	address := fmt.Sprintf("127.0.0.1:%d", apiPort)

	// Create gRPC connection with plaintext (no TLS for localhost)
	// IMPORTANT: We do NOT use grpc.WithBlock() to avoid blocking Xray-core startup
	// The connection will be established asynchronously in the background
	conn, err := grpc.NewClient(
		address,
		grpc.WithTransportCredentials(insecure.NewCredentials()), // Plaintext for localhost
	)
	if err != nil {
		logError("[XrayGrpc] Failed to create gRPC client: %v", err)
		return nil, fmt.Errorf("grpc new client: %w", err)
	}

	// Create StatsService client
	client := command.NewStatsServiceClient(conn)

	logInfo("[XrayGrpc] ✅ gRPC client created for %s (connection will be established asynchronously)", address)

	return &XrayGrpcClient{
		conn:   conn,
		client: client,
		port:   apiPort,
	}, nil
}

// waitForConnection waits for gRPC connection to be ready
func (c *XrayGrpcClient) waitForConnection(maxWait time.Duration) error {
	if c.conn == nil {
		return fmt.Errorf("connection is nil")
	}

	state := c.conn.GetState()
	if state == connectivity.Ready {
		return nil // Already ready
	}

	// Wait for connection to be ready
	ctx, cancel := context.WithTimeout(context.Background(), maxWait)
	defer cancel()

	// Log initial state
	logDebug("[XrayGrpc] Waiting for connection (initial state: %v, maxWait: %v)", state, maxWait)
	
	startTime := time.Now()
	for {
		if !c.conn.WaitForStateChange(ctx, state) {
			// Context expired
			currentState := c.conn.GetState()
			elapsed := time.Since(startTime)
			logWarn("[XrayGrpc] Connection wait timeout: state=%v (waited %v, maxWait: %v)", currentState, elapsed, maxWait)
			return fmt.Errorf("connection not ready: state=%v (waited %v)", currentState, elapsed)
		}

		state = c.conn.GetState()
		elapsed := time.Since(startTime)
		logDebug("[XrayGrpc] Connection state changed: %v (elapsed: %v)", state, elapsed)
		
		if state == connectivity.Ready {
			logInfo("[XrayGrpc] ✅ Connection is now ready (elapsed: %v)", elapsed)
			return nil
		}

		if state == connectivity.Shutdown {
			return fmt.Errorf("connection shutdown: state=%v", state)
		}
		
		if state == connectivity.TransientFailure {
			// TransientFailure is recoverable - continue waiting
			logDebug("[XrayGrpc] Connection in transient failure (recoverable), continuing to wait...")
			// Don't return error immediately, allow retry
		}

		// Continue waiting for IDLE or CONNECTING states
		// IDLE state means connection is not yet established but can become ready
		// CONNECTING state means connection is in progress
	}
}

// GetSystemStats queries system statistics from Xray-core
func (c *XrayGrpcClient) GetSystemStats() (*command.SysStatsResponse, error) {
	// Wait for connection to be ready (max 5 seconds - increased for slower devices)
	if err := c.waitForConnection(5 * time.Second); err != nil {
		logError("[XrayGrpc] Connection not ready: %v", err)
		return nil, fmt.Errorf("connection not ready: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	req := &command.SysStatsRequest{}
	resp, err := c.client.GetSysStats(ctx, req)
	if err != nil {
		logError("[XrayGrpc] GetSysStats failed: %v", err)
		return nil, fmt.Errorf("get sys stats: %w", err)
	}

	logDebug("[XrayGrpc] System stats: uptime=%ds, goroutines=%d", resp.Uptime, resp.NumGoroutine)
	return resp, nil
}

// QueryTrafficStats queries traffic statistics from Xray-core
// Returns uplink and downlink bytes, matching Kotlin CoreStatsClient pattern matching logic
func (c *XrayGrpcClient) QueryTrafficStats() (uplink, downlink int64, err error) {
	// Wait for connection to be ready (max 5 seconds - increased for slower devices)
	if err := c.waitForConnection(5 * time.Second); err != nil {
		logError("[XrayGrpc] Connection not ready for traffic stats: %v", err)
		return 0, 0, fmt.Errorf("connection not ready: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	// Query all stats with empty pattern
	req := &command.QueryStatsRequest{
		Pattern: "",
	}

	resp, err := c.client.QueryStats(ctx, req)
	if err != nil {
		logError("[XrayGrpc] QueryStats failed: %v", err)
		return 0, 0, fmt.Errorf("query stats: %w", err)
	}

	// Parse traffic stats from response using same pattern matching as Kotlin CoreStatsClient
	for _, stat := range resp.Stat {
		name := stat.Name
		value := stat.Value

		nameLower := strings.ToLower(name)
		isUplink := strings.Contains(nameLower, "uplink")
		isDownlink := strings.Contains(nameLower, "downlink")
		hasTraffic := strings.Contains(nameLower, "traffic")
		isOutbound := strings.Contains(nameLower, "outbound")
		isInbound := strings.Contains(nameLower, "inbound")

		// Pattern matching logic (same as Kotlin CoreStatsClient):
		// 1. First priority: stat names containing both "uplink"/"downlink" AND "traffic"
		// 2. Second priority: stat names containing "uplink"/"downlink" (even without "traffic")
		// 3. Also check for "outbound" (usually uplink) and "inbound" (usually downlink) patterns
		// Note: Check downlink patterns BEFORE uplink patterns to avoid conflicts

		matched := false
		switch {
		// Downlink patterns (checked first to prioritize)
		case isDownlink && hasTraffic:
			downlink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found downlink+traffic stat: %s = %d, total now: %d", name, value, downlink)
		case isDownlink && !isUplink:
			// Only downlink, no uplink - likely downlink traffic
			downlink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found downlink-only stat: %s = %d, total now: %d", name, value, downlink)
		case isInbound && hasTraffic:
			// Inbound usually means downlink
			downlink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found inbound+traffic stat (downlink): %s = %d, total now: %d", name, value, downlink)
		case isInbound && !isOutbound && !isUplink:
			// Inbound without outbound/uplink - likely downlink (even without "traffic" keyword)
			downlink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found inbound-only stat (downlink): %s = %d, total now: %d", name, value, downlink)
		// Uplink patterns
		case isUplink && hasTraffic:
			uplink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found uplink+traffic stat: %s = %d, total now: %d", name, value, uplink)
		case isUplink && !isDownlink:
			// Only uplink, no downlink - likely uplink traffic
			uplink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found uplink-only stat: %s = %d, total now: %d", name, value, uplink)
		case isOutbound && hasTraffic:
			// Outbound usually means uplink
			uplink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found outbound+traffic stat (uplink): %s = %d, total now: %d", name, value, uplink)
		case isOutbound && !isInbound && !isDownlink:
			// Outbound without inbound/downlink - likely uplink (even without "traffic" keyword)
			uplink += value
			matched = true
			logDebug("[XrayGrpc] ✓ Found outbound-only stat (uplink): %s = %d, total now: %d", name, value, uplink)
		}

		// Log unmatched stats for debugging (only if they seem traffic-related)
		if !matched && (isUplink || isDownlink || isOutbound || isInbound || hasTraffic) {
			logDebug("[XrayGrpc] ⚠ Unmatched traffic-related stat: %s = %d (uplink=%v, downlink=%v, traffic=%v, outbound=%v, inbound=%v)", name, value, isUplink, isDownlink, hasTraffic, isOutbound, isInbound)
		}
	}

	logDebug("[XrayGrpc] Traffic stats: uplink=%d, downlink=%d", uplink, downlink)
	return uplink, downlink, nil
}

// Close closes the gRPC connection
func (c *XrayGrpcClient) Close() error {
	if c.conn != nil {
		logInfo("[XrayGrpc] Closing gRPC connection...")
		err := c.conn.Close()
		if err != nil {
			logError("[XrayGrpc] Error closing connection: %v", err)
			return err
		}
		logInfo("[XrayGrpc] ✅ gRPC connection closed")
	}
	return nil
}
