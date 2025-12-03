// Package masque implements MASQUE protocol (RFC 9298, RFC 9484) over Xray.
package masque

import (
	"encoding/binary"
	"errors"
	"io"
)

// QUIC Variable-Length Integer Encoding (RFC 9000, Section 16)
//
// Format:
//   2MSB | Length | Usable Bits | Range
//   00   | 1      | 6           | 0-63
//   01   | 2      | 14          | 0-16383
//   10   | 4      | 30          | 0-1073741823
//   11   | 8      | 62          | 0-4611686018427387903

var (
	// ErrVarintOverflow indicates the value is too large for QUIC varint encoding
	ErrVarintOverflow = errors.New("varint: value exceeds maximum encodable value (2^62-1)")
	// ErrVarintTruncated indicates insufficient bytes for decoding
	ErrVarintTruncated = errors.New("varint: truncated input")
)

const (
	// Maximum value that can be encoded as a QUIC varint
	MaxVarint = (1 << 62) - 1

	// Length prefix masks (2 MSB)
	varintLen1Mask = 0x00 // 00xxxxxx - 1 byte
	varintLen2Mask = 0x40 // 01xxxxxx - 2 bytes
	varintLen4Mask = 0x80 // 10xxxxxx - 4 bytes
	varintLen8Mask = 0xC0 // 11xxxxxx - 8 bytes

	// Mask to extract length prefix
	varintLenMask = 0xC0
)

// EncodeVarint encodes a uint64 value using QUIC variable-length integer encoding.
// Returns the encoded bytes and any error.
func EncodeVarint(v uint64) ([]byte, error) {
	if v > MaxVarint {
		return nil, ErrVarintOverflow
	}

	switch {
	case v <= 63:
		// 1 byte: 00xxxxxx
		return []byte{byte(v)}, nil

	case v <= 16383:
		// 2 bytes: 01xxxxxx xxxxxxxx
		buf := make([]byte, 2)
		binary.BigEndian.PutUint16(buf, uint16(v)|0x4000)
		return buf, nil

	case v <= 1073741823:
		// 4 bytes: 10xxxxxx xxxxxxxx xxxxxxxx xxxxxxxx
		buf := make([]byte, 4)
		binary.BigEndian.PutUint32(buf, uint32(v)|0x80000000)
		return buf, nil

	default:
		// 8 bytes: 11xxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx
		buf := make([]byte, 8)
		binary.BigEndian.PutUint64(buf, v|0xC000000000000000)
		return buf, nil
	}
}

// EncodeVarintTo encodes a uint64 value into the provided buffer.
// Returns the number of bytes written and any error.
// The buffer must be at least 8 bytes to handle the worst case.
func EncodeVarintTo(buf []byte, v uint64) (int, error) {
	if v > MaxVarint {
		return 0, ErrVarintOverflow
	}

	switch {
	case v <= 63:
		if len(buf) < 1 {
			return 0, io.ErrShortBuffer
		}
		buf[0] = byte(v)
		return 1, nil

	case v <= 16383:
		if len(buf) < 2 {
			return 0, io.ErrShortBuffer
		}
		binary.BigEndian.PutUint16(buf, uint16(v)|0x4000)
		return 2, nil

	case v <= 1073741823:
		if len(buf) < 4 {
			return 0, io.ErrShortBuffer
		}
		binary.BigEndian.PutUint32(buf, uint32(v)|0x80000000)
		return 4, nil

	default:
		if len(buf) < 8 {
			return 0, io.ErrShortBuffer
		}
		binary.BigEndian.PutUint64(buf, v|0xC000000000000000)
		return 8, nil
	}
}

// DecodeVarint decodes a QUIC variable-length integer from the given bytes.
// Returns the decoded value, number of bytes consumed, and any error.
func DecodeVarint(data []byte) (uint64, int, error) {
	if len(data) == 0 {
		return 0, 0, ErrVarintTruncated
	}

	// Determine length from 2 MSB
	prefix := data[0] & varintLenMask

	switch prefix {
	case varintLen1Mask:
		// 1 byte
		return uint64(data[0] & 0x3F), 1, nil

	case varintLen2Mask:
		// 2 bytes
		if len(data) < 2 {
			return 0, 0, ErrVarintTruncated
		}
		v := binary.BigEndian.Uint16(data[:2]) & 0x3FFF
		return uint64(v), 2, nil

	case varintLen4Mask:
		// 4 bytes
		if len(data) < 4 {
			return 0, 0, ErrVarintTruncated
		}
		v := binary.BigEndian.Uint32(data[:4]) & 0x3FFFFFFF
		return uint64(v), 4, nil

	default: // varintLen8Mask
		// 8 bytes
		if len(data) < 8 {
			return 0, 0, ErrVarintTruncated
		}
		v := binary.BigEndian.Uint64(data[:8]) & 0x3FFFFFFFFFFFFFFF
		return uint64(v), 8, nil
	}
}

// VarintLen returns the number of bytes needed to encode the given value.
// Returns 0 if the value exceeds MaxVarint.
func VarintLen(v uint64) int {
	switch {
	case v <= 63:
		return 1
	case v <= 16383:
		return 2
	case v <= 1073741823:
		return 4
	case v <= MaxVarint:
		return 8
	default:
		return 0
	}
}

// ReadVarint reads a QUIC variable-length integer from an io.Reader.
// Returns the decoded value and any error.
func ReadVarint(r io.Reader) (uint64, error) {
	// Read first byte to determine length
	var firstByte [1]byte
	if _, err := io.ReadFull(r, firstByte[:]); err != nil {
		return 0, err
	}

	prefix := firstByte[0] & varintLenMask

	switch prefix {
	case varintLen1Mask:
		return uint64(firstByte[0] & 0x3F), nil

	case varintLen2Mask:
		var rest [1]byte
		if _, err := io.ReadFull(r, rest[:]); err != nil {
			return 0, err
		}
		v := uint16(firstByte[0]&0x3F)<<8 | uint16(rest[0])
		return uint64(v), nil

	case varintLen4Mask:
		var rest [3]byte
		if _, err := io.ReadFull(r, rest[:]); err != nil {
			return 0, err
		}
		v := uint32(firstByte[0]&0x3F)<<24 | uint32(rest[0])<<16 | uint32(rest[1])<<8 | uint32(rest[2])
		return uint64(v), nil

	default: // varintLen8Mask
		var rest [7]byte
		if _, err := io.ReadFull(r, rest[:]); err != nil {
			return 0, err
		}
		v := uint64(firstByte[0]&0x3F)<<56 |
			uint64(rest[0])<<48 |
			uint64(rest[1])<<40 |
			uint64(rest[2])<<32 |
			uint64(rest[3])<<24 |
			uint64(rest[4])<<16 |
			uint64(rest[5])<<8 |
			uint64(rest[6])
		return uint64(v), nil
	}
}
