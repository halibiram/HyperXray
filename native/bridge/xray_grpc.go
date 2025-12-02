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

	logInfo("[XrayGrpc] ✅ gRPC client created for %s", address)
	
	// CRITICAL FIX: Trigger initial connection attempt
	// gRPC uses lazy connection - without this, connection stays in IDLE state
	// until first RPC call or explicit Connect() call
	logInfo("[XrayGrpc] Triggering initial connection attempt...")
	conn.Connect()
	
	// Check initial state after Connect()
	initialState := conn.GetState()
	logInfo("[XrayGrpc] Initial connection state after Connect(): %v", initialState)

	return &XrayGrpcClient{
		conn:   conn,
		client: client,
		port:   apiPort,
	}, nil
}

// checkConnection checks if gRPC connection is ready WITHOUT blocking
// CRITICAL FIX: This is NON-BLOCKING to prevent traffic lockup
// If connection is not ready, it triggers Connect() and returns error immediately
// The caller should handle the error gracefully (e.g., return cached/default values)
func (c *XrayGrpcClient) checkConnection() error {
	if c.conn == nil {
		return fmt.Errorf("connection is nil")
	}

	state := c.conn.GetState()
	
	// If already ready, return immediately
	if state == connectivity.Ready {
		return nil
	}
	
	// If IDLE, trigger connection attempt (non-blocking)
	if state == connectivity.Idle {
		logDebug("[XrayGrpc] Connection is IDLE, triggering Connect() (non-blocking)...")
		c.conn.Connect()
		// Check state again after Connect()
		state = c.conn.GetState()
		if state == connectivity.Ready {
			return nil
		}
	}
	
	// If CONNECTING, just return error - don't wait
	// The connection will be ready on next call
	if state == connectivity.Connecting {
		logDebug("[XrayGrpc] Connection is CONNECTING, will be ready soon...")
		return fmt.Errorf("connection not ready yet: state=%v", state)
	}
	
	// If TransientFailure, trigger reconnection (non-blocking)
	if state == connectivity.TransientFailure {
		logDebug("[XrayGrpc] Connection in TransientFailure, triggering reconnect...")
		c.conn.Connect()
		return fmt.Errorf("connection in transient failure: state=%v", state)
	}
	
	// If Shutdown, connection is dead
	if state == connectivity.Shutdown {
		return fmt.Errorf("connection shutdown: state=%v", state)
	}
	
	// Unknown state
	return fmt.Errorf("connection not ready: state=%v", state)
}

// waitForConnection waits for gRPC connection to be ready with polling
// Uses a simple polling approach with Connect() calls to trigger connection attempts
// NOTE: This is only used for background verification, not for stats queries
func (c *XrayGrpcClient) waitForConnection(maxWait time.Duration) error {
	if c.conn == nil {
		return fmt.Errorf("connection is nil")
	}

	state := c.conn.GetState()
	if state == connectivity.Ready {
		return nil // Already ready
	}

	startTime := time.Now()
	pollInterval := 10 * time.Millisecond
	
	logDebug("[XrayGrpc] Waiting for connection (initial state: %v, maxWait: %v)", state, maxWait)
	
	// Trigger initial connection attempt
	c.conn.Connect()
	
	for time.Since(startTime) < maxWait {
		state = c.conn.GetState()
		
		if state == connectivity.Ready {
			logDebug("[XrayGrpc] ✅ Connection ready (elapsed: %v)", time.Since(startTime))
			return nil
		}
		
		if state == connectivity.Shutdown {
			return fmt.Errorf("connection shutdown")
		}
		
		// If IDLE or TransientFailure, trigger reconnection
		if state == connectivity.Idle || state == connectivity.TransientFailure {
			c.conn.Connect()
		}
		
		time.Sleep(pollInterval)
	}
	
	finalState := c.conn.GetState()
	logWarn("[XrayGrpc] Connection wait timeout: state=%v (waited %v)", finalState, time.Since(startTime))
	return fmt.Errorf("connection not ready: state=%v (waited %v)", finalState, time.Since(startTime))
}

// GetSystemStats queries system statistics from Xray-core
// NON-BLOCKING: Returns error immediately if connection not ready
func (c *XrayGrpcClient) GetSystemStats() (*command.SysStatsResponse, error) {
	// NON-BLOCKING check - don't wait for connection
	if err := c.checkConnection(); err != nil {
		// Don't log as error - this is expected during connection establishment
		logDebug("[XrayGrpc] Connection not ready for system stats: %v", err)
		return nil, fmt.Errorf("connection not ready: %w", err)
	}

	// Use short timeout (500ms) to prevent blocking
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()

	req := &command.SysStatsRequest{}
	resp, err := c.client.GetSysStats(ctx, req)
	if err != nil {
		logDebug("[XrayGrpc] GetSysStats failed: %v", err)
		return nil, fmt.Errorf("get sys stats: %w", err)
	}

	logDebug("[XrayGrpc] System stats: uptime=%ds, goroutines=%d", resp.Uptime, resp.NumGoroutine)
	return resp, nil
}

// QueryTrafficStats queries traffic statistics from Xray-core
// Returns uplink and downlink bytes, matching Kotlin CoreStatsClient pattern matching logic
// NON-BLOCKING: Returns error immediately if connection not ready
func (c *XrayGrpcClient) QueryTrafficStats() (uplink, downlink int64, err error) {
	// NON-BLOCKING check - don't wait for connection
	if err := c.checkConnection(); err != nil {
		// Don't log as error - this is expected during connection establishment
		logDebug("[XrayGrpc] Connection not ready for traffic stats: %v", err)
		return 0, 0, fmt.Errorf("connection not ready: %w", err)
	}

	// Use short timeout (500ms) to prevent blocking
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
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
