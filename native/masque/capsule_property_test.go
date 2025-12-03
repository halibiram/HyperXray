package masque

import (
	"bytes"
	"testing"

	"github.com/leanovate/gopter"
	"github.com/leanovate/gopter/gen"
	"github.com/leanovate/gopter/prop"
)

// **Feature: masque-over-xray, Property 1: Capsule Round-Trip Correctness**
// **Validates: Requirements 7.1, 7.2, 7.5**
func TestProperty_CapsuleRoundTrip(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)
	decoder := NewCapsuleDecoder(MasqueModeConnectIP)

	// Property: For any valid IP packet, encoding then decoding produces the original packet
	properties.Property("capsule round-trip preserves packet data", prop.ForAll(
		func(packet []byte) bool {
			if len(packet) == 0 || len(packet) > MaxCapsulePayload {
				return true // Skip invalid inputs
			}

			encoded, err := encoder.Encode(packet)
			if err != nil {
				return false
			}

			decoded, consumed, err := decoder.Decode(encoded)
			if err != nil {
				return false
			}

			return bytes.Equal(decoded, packet) && consumed == len(encoded)
		},
		gen.SliceOfN(1500, gen.UInt8()), // Generate packets up to MTU size
	))

	// Property: EncodeTo and Encode produce identical results
	properties.Property("EncodeTo matches Encode", prop.ForAll(
		func(packet []byte) bool {
			if len(packet) == 0 || len(packet) > MaxCapsulePayload {
				return true
			}

			encoded1, err1 := encoder.Encode(packet)
			if err1 != nil {
				return false
			}

			buf := make([]byte, len(encoded1)+100)
			n, err2 := encoder.EncodeTo(buf, packet)
			if err2 != nil {
				return false
			}

			return n == len(encoded1) && bytes.Equal(buf[:n], encoded1)
		},
		gen.SliceOfN(500, gen.UInt8()),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 5: Malformed Capsule Resilience**
// **Validates: Requirements 7.3**
func TestProperty_MalformedCapsuleResilience(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	decoder := NewCapsuleDecoder(MasqueModeConnectIP)

	// Property: Decoder never panics on any input, always returns error or valid result
	properties.Property("decoder handles any input without panic", prop.ForAll(
		func(data []byte) bool {
			// This should never panic
			defer func() {
				if r := recover(); r != nil {
					t.Errorf("Decoder panicked on input: %v", data)
				}
			}()

			_, _, err := decoder.Decode(data)
			// Either returns error or valid result - both are acceptable
			// The key is that it doesn't panic
			_ = err
			return true
		},
		gen.SliceOf(gen.UInt8()),
	))

	// Property: Truncated capsules return ErrCapsuleTruncated
	properties.Property("truncated capsules return appropriate error", prop.ForAll(
		func(packet []byte) bool {
			if len(packet) == 0 || len(packet) > 1000 {
				return true
			}

			encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)
			encoded, err := encoder.Encode(packet)
			if err != nil {
				return true
			}

			// Truncate the encoded data
			if len(encoded) > 2 {
				truncated := encoded[:len(encoded)-1]
				_, _, err := decoder.Decode(truncated)
				// Should return an error for truncated data
				return err != nil
			}
			return true
		},
		gen.SliceOfN(100, gen.UInt8()),
	))

	properties.TestingRun(t)
}

// **Feature: masque-over-xray, Property 9: Mode-Specific Encapsulation**
// **Validates: Requirements 6.1, 6.2**
func TestProperty_ModeSpecificEncapsulation(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42)

	properties := gopter.NewProperties(parameters)

	// Property: CONNECT-IP mode preserves complete IP packet
	properties.Property("CONNECT-IP preserves complete packet", prop.ForAll(
		func(packet []byte) bool {
			if len(packet) == 0 || len(packet) > MaxCapsulePayload {
				return true
			}

			encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)
			decoder := NewCapsuleDecoder(MasqueModeConnectIP)

			encoded, err := encoder.Encode(packet)
			if err != nil {
				return false
			}

			decoded, _, err := decoder.Decode(encoded)
			if err != nil {
				return false
			}

			// Complete packet should be preserved
			return bytes.Equal(decoded, packet)
		},
		gen.SliceOfN(1000, gen.UInt8()),
	))

	// Property: CONNECT-UDP mode preserves UDP datagram
	properties.Property("CONNECT-UDP preserves datagram", prop.ForAll(
		func(packet []byte) bool {
			if len(packet) == 0 || len(packet) > MaxCapsulePayload {
				return true
			}

			encoder := NewCapsuleEncoder(MasqueModeConnectUDP, DefaultMTU)
			decoder := NewCapsuleDecoder(MasqueModeConnectUDP)

			encoded, err := encoder.Encode(packet)
			if err != nil {
				return false
			}

			decoded, _, err := decoder.Decode(encoded)
			if err != nil {
				return false
			}

			return bytes.Equal(decoded, packet)
		},
		gen.SliceOfN(1000, gen.UInt8()),
	))

	properties.TestingRun(t)
}
