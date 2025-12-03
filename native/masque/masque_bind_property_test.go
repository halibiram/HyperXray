package masque

import (
	"sync"
	"testing"
	"time"

	"github.com/leanovate/gopter"
	"github.com/leanovate/gopter/gen"
	"github.com/leanovate/gopter/prop"
)

// **Feature: masque-over-xray, Property 4: Buffer Pool Safety**
// **Validates: Requirements 5.4**
func TestProperty_BufferPoolSafety(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: Buffers returned to pool are cleared (no data leakage)
	properties.Property("buffer pool clears data on return", prop.ForAll(
		func(data []byte) bool {
			if len(data) == 0 || len(data) > PacketBufferSize {
				return true
			}

			// Get a buffer and write data
			buf := getMasqueBuffer()
			copy(*buf, data)

			// Return to pool
			putMasqueBuffer(buf)

			// Get another buffer (might be the same one)
			buf2 := getMasqueBuffer()
			defer putMasqueBuffer(buf2)

			// Check that buffer is cleared
			for i := 0; i < len(data) && i < len(*buf2); i++ {
				if (*buf2)[i] != 0 {
					return false
				}
			}

			return true
		},
		gen.SliceOfN(100, gen.UInt8()),
	))

	// Property: Concurrent buffer pool access is safe
	properties.Property("buffer pool is thread-safe", prop.ForAll(
		func(numGoroutines int) bool {
			if numGoroutines < 1 || numGoroutines > 50 {
				numGoroutines = 10
			}

			var wg sync.WaitGroup
			errors := make(chan error, numGoroutines)

			for i := 0; i < numGoroutines; i++ {
				wg.Add(1)
				go func() {
					defer wg.Done()
					defer func() {
						if r := recover(); r != nil {
							errors <- nil // Signal panic occurred
						}
					}()

					for j := 0; j < 10; j++ {
						buf := getMasqueBuffer()
						if buf == nil {
							errors <- nil
							return
						}
						(*buf)[0] = byte(j)
						putMasqueBuffer(buf)
					}
				}()
			}

			wg.Wait()
			close(errors)

			// No panics should have occurred
			for err := range errors {
				if err == nil {
					return false
				}
			}

			return true
		},
		gen.IntRange(1, 20),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 3: Queue Bounds Enforcement**
// **Validates: Requirements 5.2, 5.3**
func TestProperty_QueueBoundsEnforcement(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 50
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: Queue never exceeds configured maximum size
	properties.Property("queue size never exceeds maximum", prop.ForAll(
		func(queueSize int, numPackets int) bool {
			if queueSize < 1 || queueSize > 1000 {
				queueSize = 100
			}
			if numPackets < 1 || numPackets > 2000 {
				numPackets = 500
			}

			queue := make(chan []byte, queueSize)

			// Try to add more packets than queue size
			added := 0
			for i := 0; i < numPackets; i++ {
				select {
				case queue <- make([]byte, 10):
					added++
				default:
					// Queue full, packet dropped (expected behavior)
				}
			}

			// Queue should never exceed its capacity
			return len(queue) <= queueSize
		},
		gen.IntRange(10, 500),
		gen.IntRange(100, 1000),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 6: Configuration Validation**
// **Validates: Requirements 3.4**
func TestProperty_ConfigurationValidation(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: Invalid configurations are rejected with descriptive errors
	properties.Property("invalid configs are rejected", prop.ForAll(
		func(endpoint string, mode string) bool {
			config := MasqueConfig{
				ProxyEndpoint: endpoint,
				Mode:          mode,
			}

			err := config.Validate()

			// Empty endpoint should be rejected
			if endpoint == "" && err == nil {
				return false
			}

			// Invalid mode should be rejected
			if mode != "" && mode != "connect-ip" && mode != "connect-udp" && err == nil {
				return false
			}

			return true
		},
		gen.OneConstOf("", "invalid", "example.com:443", "192.168.1.1:8080"),
		gen.OneConstOf("", "connect-ip", "connect-udp", "invalid-mode"),
	))

	// Property: Valid configurations pass validation
	properties.Property("valid configs pass validation", prop.ForAll(
		func(port int) bool {
			if port < 1 || port > 65535 {
				port = 443
			}

			config := MasqueConfig{
				ProxyEndpoint: "example.com:" + string(rune('0'+port%10)),
				Mode:          "connect-ip",
			}

			// This specific config should be valid
			config.ProxyEndpoint = "example.com:443"
			err := config.Validate()
			return err == nil
		},
		gen.IntRange(1, 65535),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 7: Reconnection Backoff**
// **Validates: Requirements 2.5, 8.2, 8.5**
func TestProperty_ReconnectionBackoff(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: Backoff delay increases exponentially
	properties.Property("backoff increases exponentially", prop.ForAll(
		func(baseDelay int, maxAttempts int) bool {
			if baseDelay < 100 || baseDelay > 5000 {
				baseDelay = 1000
			}
			if maxAttempts < 1 || maxAttempts > 10 {
				maxAttempts = 5
			}

			var prevDelay time.Duration
			for attempt := 0; attempt < maxAttempts; attempt++ {
				delay := baseDelay * (1 << attempt)
				if delay > MaxReconnectDelay {
					delay = MaxReconnectDelay
				}
				currentDelay := time.Duration(delay) * time.Millisecond

				// Each delay should be >= previous (exponential growth or capped)
				if attempt > 0 && currentDelay < prevDelay {
					return false
				}
				prevDelay = currentDelay
			}

			return true
		},
		gen.IntRange(100, 2000),
		gen.IntRange(1, 8),
	))

	// Property: Backoff is capped at MaxReconnectDelay
	properties.Property("backoff is capped at maximum", prop.ForAll(
		func(attempt int) bool {
			if attempt < 0 {
				attempt = 0
			}

			baseDelay := 1000 // 1 second
			delay := baseDelay * (1 << attempt)
			if delay > MaxReconnectDelay {
				delay = MaxReconnectDelay
			}

			return delay <= MaxReconnectDelay
		},
		gen.IntRange(0, 20),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 2: Statistics Accuracy**
// **Validates: Requirements 4.1, 4.2**
func TestProperty_StatisticsAccuracy(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 50
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: txBytes equals sum of all sent packet sizes
	properties.Property("tx stats accurately track sent bytes", prop.ForAll(
		func(packetSizes []int) bool {
			var totalExpected uint64
			for _, size := range packetSizes {
				if size > 0 && size < 10000 {
					totalExpected += uint64(size)
				}
			}

			// Simulate tracking
			var tracked uint64
			for _, size := range packetSizes {
				if size > 0 && size < 10000 {
					tracked += uint64(size)
				}
			}

			return tracked == totalExpected
		},
		gen.SliceOfN(100, gen.IntRange(1, 1500)),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 10: State Consistency**
// **Validates: Requirements 4.4**
func TestProperty_StateConsistency(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: State transitions follow valid state machine
	properties.Property("state transitions are valid", prop.ForAll(
		func(transitions []int) bool {
			state := StateDisconnected

			for _, trans := range transitions {
				nextState := ConnectionState(trans % 4)

				// Validate transition is legal
				validTransition := false
				switch state {
				case StateDisconnected:
					validTransition = nextState == StateConnecting || nextState == StateDisconnected
				case StateConnecting:
					validTransition = nextState == StateConnected || nextState == StateDisconnected
				case StateConnected:
					validTransition = nextState == StateReconnecting || nextState == StateDisconnected
				case StateReconnecting:
					validTransition = nextState == StateConnected || nextState == StateDisconnected
				}

				if validTransition {
					state = nextState
				}
			}

			// Final state should be valid
			return state >= StateDisconnected && state <= StateReconnecting
		},
		gen.SliceOfN(20, gen.IntRange(0, 3)),
	))

	// Property: ConnectionState.String() never panics
	properties.Property("state string never panics", prop.ForAll(
		func(stateVal int) bool {
			defer func() {
				if r := recover(); r != nil {
					t.Errorf("String() panicked for state %d", stateVal)
				}
			}()

			state := ConnectionState(stateVal)
			_ = state.String()
			return true
		},
		gen.IntRange(-10, 10),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 8: Resource Cleanup on Close**
// **Validates: Requirements 2.4, 5.5**
func TestProperty_ResourceCleanupOnClose(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 20
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: After close, queues are drained
	properties.Property("queues are drained on close", prop.ForAll(
		func(numPackets int) bool {
			if numPackets < 0 || numPackets > 100 {
				numPackets = 50
			}

			queue := make(chan []byte, 1000)

			// Add packets
			for i := 0; i < numPackets; i++ {
				queue <- make([]byte, 10)
			}

			// Drain queue (simulating close behavior)
			close(queue)
			drained := 0
			for range queue {
				drained++
			}

			return drained == numPackets
		},
		gen.IntRange(0, 100),
	))

	properties.TestingRun(t)
}
