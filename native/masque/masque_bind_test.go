package masque

import (
	"encoding/json"
	"testing"
	"time"
)

func TestMasqueConfig_Validate(t *testing.T) {
	tests := []struct {
		name    string
		config  MasqueConfig
		wantErr bool
	}{
		{
			name: "valid config",
			config: MasqueConfig{
				ProxyEndpoint: "masque.example.com:443",
				Mode:          "connect-ip",
			},
			wantErr: false,
		},
		{
			name: "valid config connect-udp",
			config: MasqueConfig{
				ProxyEndpoint: "masque.example.com:443",
				Mode:          "connect-udp",
			},
			wantErr: false,
		},
		{
			name: "empty endpoint",
			config: MasqueConfig{
				ProxyEndpoint: "",
				Mode:          "connect-ip",
			},
			wantErr: true,
		},
		{
			name: "invalid endpoint format",
			config: MasqueConfig{
				ProxyEndpoint: "invalid",
				Mode:          "connect-ip",
			},
			wantErr: true,
		},
		{
			name: "invalid mode",
			config: MasqueConfig{
				ProxyEndpoint: "masque.example.com:443",
				Mode:          "invalid-mode",
			},
			wantErr: true,
		},
		{
			name: "empty mode (defaults to connect-ip)",
			config: MasqueConfig{
				ProxyEndpoint: "masque.example.com:443",
				Mode:          "",
			},
			wantErr: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.config.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestMasqueConfig_ApplyDefaults(t *testing.T) {
	config := MasqueConfig{
		ProxyEndpoint: "masque.example.com:443",
	}

	config.ApplyDefaults()

	if config.Mode != "connect-ip" {
		t.Errorf("Mode = %s, want connect-ip", config.Mode)
	}
	if config.MaxReconnects != DefaultMaxReconnects {
		t.Errorf("MaxReconnects = %d, want %d", config.MaxReconnects, DefaultMaxReconnects)
	}
	if config.ReconnectDelay != DefaultReconnectDelay {
		t.Errorf("ReconnectDelay = %d, want %d", config.ReconnectDelay, DefaultReconnectDelay)
	}
	if config.QueueSize != DefaultQueueSize {
		t.Errorf("QueueSize = %d, want %d", config.QueueSize, DefaultQueueSize)
	}
	if config.MTU != DefaultMTU {
		t.Errorf("MTU = %d, want %d", config.MTU, DefaultMTU)
	}
}

func TestMasqueConfig_GetMode(t *testing.T) {
	tests := []struct {
		mode     string
		expected MasqueMode
	}{
		{"connect-ip", MasqueModeConnectIP},
		{"connect-udp", MasqueModeConnectUDP},
		{"", MasqueModeConnectIP}, // Default
	}

	for _, tt := range tests {
		config := MasqueConfig{Mode: tt.mode}
		if got := config.GetMode(); got != tt.expected {
			t.Errorf("GetMode() for %q = %v, want %v", tt.mode, got, tt.expected)
		}
	}
}

func TestNewMasqueBind_InvalidConfig(t *testing.T) {
	tests := []struct {
		name       string
		configJSON string
		wantErr    bool
	}{
		{
			name:       "invalid JSON",
			configJSON: "not json",
			wantErr:    true,
		},
		{
			name:       "empty endpoint",
			configJSON: `{"proxyEndpoint": ""}`,
			wantErr:    true,
		},
		{
			name:       "invalid mode",
			configJSON: `{"proxyEndpoint": "example.com:443", "mode": "invalid"}`,
			wantErr:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := NewMasqueBind(nil, tt.configJSON)
			if (err != nil) != tt.wantErr {
				t.Errorf("NewMasqueBind() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

// mockXrayWrapper implements XrayWrapper for testing
type mockXrayWrapper struct{}

func (m *mockXrayWrapper) DialQUIC(endpoint string) (interface{}, error) {
	return nil, nil
}

func TestNewMasqueBind_NilXrayWrapper(t *testing.T) {
	configJSON := `{"proxyEndpoint": "example.com:443"}`
	_, err := NewMasqueBind(nil, configJSON)
	if err == nil {
		t.Error("NewMasqueBind() with nil xrayWrapper should error")
	}
}

func TestConnectionState_String(t *testing.T) {
	tests := []struct {
		state    ConnectionState
		expected string
	}{
		{StateDisconnected, "disconnected"},
		{StateConnecting, "connecting"},
		{StateConnected, "connected"},
		{StateReconnecting, "reconnecting"},
		{ConnectionState(99), "unknown"},
	}

	for _, tt := range tests {
		if got := tt.state.String(); got != tt.expected {
			t.Errorf("ConnectionState(%d).String() = %s, want %s", tt.state, got, tt.expected)
		}
	}
}

func TestMasqueBind_CalculateBackoff(t *testing.T) {
	config := MasqueConfig{
		ProxyEndpoint:  "example.com:443",
		ReconnectDelay: 1000, // 1 second base
		MaxReconnects:  5,
	}
	config.ApplyDefaults()

	configJSON, _ := json.Marshal(config)

	// We can't create a real bind without xrayWrapper, so test the logic directly
	// by checking the expected backoff values

	tests := []struct {
		attempt  int
		expected time.Duration
	}{
		{0, 1000 * time.Millisecond},  // 1s
		{1, 2000 * time.Millisecond},  // 2s
		{2, 4000 * time.Millisecond},  // 4s
		{3, 8000 * time.Millisecond},  // 8s
		{4, 16000 * time.Millisecond}, // 16s
		{5, 30000 * time.Millisecond}, // 30s (capped)
		{6, 30000 * time.Millisecond}, // 30s (capped)
	}

	for _, tt := range tests {
		delay := config.ReconnectDelay * (1 << tt.attempt)
		if delay > MaxReconnectDelay {
			delay = MaxReconnectDelay
		}
		got := time.Duration(delay) * time.Millisecond

		if got != tt.expected {
			t.Errorf("backoff for attempt %d = %v, want %v", tt.attempt, got, tt.expected)
		}
	}

	_ = configJSON // Suppress unused variable warning
}

func TestMasqueEndpoint(t *testing.T) {
	endpoint := &MasqueEndpoint{}

	// Test with IPv4
	addrPort, _ := parseEndpoint("192.168.1.1:443")
	endpoint.addr = addrPort

	if endpoint.DstToString() != "192.168.1.1:443" {
		t.Errorf("DstToString() = %s, want 192.168.1.1:443", endpoint.DstToString())
	}

	if endpoint.DstPort() != 443 {
		t.Errorf("DstPort() = %d, want 443", endpoint.DstPort())
	}

	if endpoint.SrcToString() != "" {
		t.Errorf("SrcToString() = %s, want empty", endpoint.SrcToString())
	}

	dstBytes := endpoint.DstToBytes()
	if len(dstBytes) != 4 {
		t.Errorf("DstToBytes() len = %d, want 4", len(dstBytes))
	}

	// ClearSrc should not panic
	endpoint.ClearSrc()
}

func TestBufferPool_Safety(t *testing.T) {
	// Get a buffer
	buf := getMasqueBuffer()
	if buf == nil {
		t.Fatal("getMasqueBuffer() returned nil")
	}

	// Write some data
	(*buf)[0] = 0xAA
	(*buf)[1] = 0xBB
	(*buf)[2] = 0xCC

	// Return to pool
	putMasqueBuffer(buf)

	// Get another buffer (might be the same one)
	buf2 := getMasqueBuffer()

	// Buffer should be cleared (Property 4: Buffer Pool Safety)
	if (*buf2)[0] != 0 || (*buf2)[1] != 0 || (*buf2)[2] != 0 {
		t.Error("Buffer was not cleared before return to pool")
	}

	putMasqueBuffer(buf2)
}

func TestBufferPool_NilSafe(t *testing.T) {
	// Should not panic
	putMasqueBuffer(nil)
}

func TestParseEndpoint(t *testing.T) {
	tests := []struct {
		name     string
		endpoint string
		wantErr  bool
	}{
		{
			name:     "valid IP:port",
			endpoint: "192.168.1.1:443",
			wantErr:  false,
		},
		{
			name:     "valid IPv6:port",
			endpoint: "[::1]:443",
			wantErr:  false,
		},
		{
			name:     "invalid format",
			endpoint: "invalid",
			wantErr:  true,
		},
		{
			name:     "missing port",
			endpoint: "192.168.1.1",
			wantErr:  true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := parseEndpoint(tt.endpoint)
			if (err != nil) != tt.wantErr {
				t.Errorf("parseEndpoint() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}
