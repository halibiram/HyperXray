package masque

import (
	"testing"

	"github.com/leanovate/gopter"
	"github.com/leanovate/gopter/gen"
	"github.com/leanovate/gopter/prop"
)

// **Feature: masque-over-xray, Property 1: Capsule Round-Trip Correctness (partial - varint)**
// **Validates: Requirements 7.1, 7.2**
func TestProperty_VarintRoundTrip(t *testing.T) {
	parameters := gopter.DefaultTestParameters()
	parameters.MinSuccessfulTests = 100
	parameters.Rng.Seed(42) // Reproducible

	properties := gopter.NewProperties(parameters)

	// Property: For any valid uint64 value <= MaxVarint, encode then decode produces the original value
	properties.Property("varint round-trip preserves value", prop.ForAll(
		func(v uint64) bool {
			// Clamp to MaxVarint
			if v > MaxVarint {
				v = v % (MaxVarint + 1)
			}

			encoded, err := EncodeVarint(v)
			if err != nil {
				return false
			}

			decoded, consumed, err := DecodeVarint(encoded)
			if err != nil {
				return false
			}

			return decoded == v && consumed == len(encoded)
		},
		gen.UInt64(),
	))

	// Property: Encoded length matches VarintLen prediction
	properties.Property("varint length prediction is accurate", prop.ForAll(
		func(v uint64) bool {
			if v > MaxVarint {
				v = v % (MaxVarint + 1)
			}

			predictedLen := VarintLen(v)
			encoded, err := EncodeVarint(v)
			if err != nil {
				return false
			}

			return len(encoded) == predictedLen
		},
		gen.UInt64(),
	))

	// Property: EncodeVarintTo produces same result as EncodeVarint
	properties.Property("EncodeVarintTo matches EncodeVarint", prop.ForAll(
		func(v uint64) bool {
			if v > MaxVarint {
				v = v % (MaxVarint + 1)
			}

			encoded1, err1 := EncodeVarint(v)
			if err1 != nil {
				return false
			}

			buf := make([]byte, 8)
			n, err2 := EncodeVarintTo(buf, v)
			if err2 != nil {
				return false
			}

			if n != len(encoded1) {
				return false
			}

			for i := 0; i < n; i++ {
				if buf[i] != encoded1[i] {
					return false
				}
			}

			return true
		},
		gen.UInt64(),
	))

	properties.TestingRun(t)
}
