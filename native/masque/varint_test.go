package masque

import (
	"bytes"
	"io"
	"testing"
)

func TestEncodeVarint_1Byte(t *testing.T) {
	tests := []struct {
		value    uint64
		expected []byte
	}{
		{0, []byte{0x00}},
		{1, []byte{0x01}},
		{37, []byte{0x25}},
		{63, []byte{0x3F}},
	}

	for _, tt := range tests {
		result, err := EncodeVarint(tt.value)
		if err != nil {
			t.Errorf("EncodeVarint(%d) error: %v", tt.value, err)
			continue
		}
		if !bytes.Equal(result, tt.expected) {
			t.Errorf("EncodeVarint(%d) = %x, want %x", tt.value, result, tt.expected)
		}
	}
}

func TestEncodeVarint_2Bytes(t *testing.T) {
	tests := []struct {
		value    uint64
		expected []byte
	}{
		{64, []byte{0x40, 0x40}},
		{494, []byte{0x41, 0xEE}},
		{16383, []byte{0x7F, 0xFF}},
	}

	for _, tt := range tests {
		result, err := EncodeVarint(tt.value)
		if err != nil {
			t.Errorf("EncodeVarint(%d) error: %v", tt.value, err)
			continue
		}
		if !bytes.Equal(result, tt.expected) {
			t.Errorf("EncodeVarint(%d) = %x, want %x", tt.value, result, tt.expected)
		}
	}
}

func TestEncodeVarint_4Bytes(t *testing.T) {
	tests := []struct {
		value    uint64
		expected []byte
	}{
		{16384, []byte{0x80, 0x00, 0x40, 0x00}},
		{494878333, []byte{0x9D, 0x7F, 0x3E, 0x7D}}, // Example from RFC 9000
		{1073741823, []byte{0xBF, 0xFF, 0xFF, 0xFF}},
	}

	for _, tt := range tests {
		result, err := EncodeVarint(tt.value)
		if err != nil {
			t.Errorf("EncodeVarint(%d) error: %v", tt.value, err)
			continue
		}
		if !bytes.Equal(result, tt.expected) {
			t.Errorf("EncodeVarint(%d) = %x, want %x", tt.value, result, tt.expected)
		}
	}
}

func TestEncodeVarint_8Bytes(t *testing.T) {
	tests := []struct {
		value    uint64
		expected []byte
	}{
		{1073741824, []byte{0xC0, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00}},
		{MaxVarint, []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}},
	}

	for _, tt := range tests {
		result, err := EncodeVarint(tt.value)
		if err != nil {
			t.Errorf("EncodeVarint(%d) error: %v", tt.value, err)
			continue
		}
		if !bytes.Equal(result, tt.expected) {
			t.Errorf("EncodeVarint(%d) = %x, want %x", tt.value, result, tt.expected)
		}
	}
}

func TestEncodeVarint_Overflow(t *testing.T) {
	_, err := EncodeVarint(MaxVarint + 1)
	if err != ErrVarintOverflow {
		t.Errorf("EncodeVarint(MaxVarint+1) error = %v, want ErrVarintOverflow", err)
	}
}

func TestDecodeVarint_1Byte(t *testing.T) {
	tests := []struct {
		data     []byte
		expected uint64
		consumed int
	}{
		{[]byte{0x00}, 0, 1},
		{[]byte{0x01}, 1, 1},
		{[]byte{0x25}, 37, 1},
		{[]byte{0x3F}, 63, 1},
	}

	for _, tt := range tests {
		value, consumed, err := DecodeVarint(tt.data)
		if err != nil {
			t.Errorf("DecodeVarint(%x) error: %v", tt.data, err)
			continue
		}
		if value != tt.expected || consumed != tt.consumed {
			t.Errorf("DecodeVarint(%x) = (%d, %d), want (%d, %d)", tt.data, value, consumed, tt.expected, tt.consumed)
		}
	}
}

func TestDecodeVarint_2Bytes(t *testing.T) {
	tests := []struct {
		data     []byte
		expected uint64
		consumed int
	}{
		{[]byte{0x40, 0x40}, 64, 2},
		{[]byte{0x41, 0xEE}, 494, 2},
		{[]byte{0x7F, 0xFF}, 16383, 2},
	}

	for _, tt := range tests {
		value, consumed, err := DecodeVarint(tt.data)
		if err != nil {
			t.Errorf("DecodeVarint(%x) error: %v", tt.data, err)
			continue
		}
		if value != tt.expected || consumed != tt.consumed {
			t.Errorf("DecodeVarint(%x) = (%d, %d), want (%d, %d)", tt.data, value, consumed, tt.expected, tt.consumed)
		}
	}
}

func TestDecodeVarint_4Bytes(t *testing.T) {
	tests := []struct {
		data     []byte
		expected uint64
		consumed int
	}{
		{[]byte{0x80, 0x00, 0x40, 0x00}, 16384, 4},
		{[]byte{0x9D, 0x7F, 0x3E, 0x7D}, 494878333, 4}, // Example from RFC 9000
		{[]byte{0xBF, 0xFF, 0xFF, 0xFF}, 1073741823, 4},
	}

	for _, tt := range tests {
		value, consumed, err := DecodeVarint(tt.data)
		if err != nil {
			t.Errorf("DecodeVarint(%x) error: %v", tt.data, err)
			continue
		}
		if value != tt.expected || consumed != tt.consumed {
			t.Errorf("DecodeVarint(%x) = (%d, %d), want (%d, %d)", tt.data, value, consumed, tt.expected, tt.consumed)
		}
	}
}

func TestDecodeVarint_8Bytes(t *testing.T) {
	tests := []struct {
		data     []byte
		expected uint64
		consumed int
	}{
		{[]byte{0xC0, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00}, 1073741824, 8},
		{[]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, MaxVarint, 8},
	}

	for _, tt := range tests {
		value, consumed, err := DecodeVarint(tt.data)
		if err != nil {
			t.Errorf("DecodeVarint(%x) error: %v", tt.data, err)
			continue
		}
		if value != tt.expected || consumed != tt.consumed {
			t.Errorf("DecodeVarint(%x) = (%d, %d), want (%d, %d)", tt.data, value, consumed, tt.expected, tt.consumed)
		}
	}
}

func TestDecodeVarint_Truncated(t *testing.T) {
	tests := [][]byte{
		{},                                   // Empty
		{0x40},                               // 2-byte prefix, only 1 byte
		{0x80, 0x00},                         // 4-byte prefix, only 2 bytes
		{0x80, 0x00, 0x00},                   // 4-byte prefix, only 3 bytes
		{0xC0, 0x00, 0x00, 0x00},             // 8-byte prefix, only 4 bytes
		{0xC0, 0x00, 0x00, 0x00, 0x00, 0x00}, // 8-byte prefix, only 6 bytes
	}

	for _, data := range tests {
		_, _, err := DecodeVarint(data)
		if err != ErrVarintTruncated {
			t.Errorf("DecodeVarint(%x) error = %v, want ErrVarintTruncated", data, err)
		}
	}
}

func TestVarintLen(t *testing.T) {
	tests := []struct {
		value    uint64
		expected int
	}{
		{0, 1},
		{63, 1},
		{64, 2},
		{16383, 2},
		{16384, 4},
		{1073741823, 4},
		{1073741824, 8},
		{MaxVarint, 8},
		{MaxVarint + 1, 0}, // Overflow
	}

	for _, tt := range tests {
		result := VarintLen(tt.value)
		if result != tt.expected {
			t.Errorf("VarintLen(%d) = %d, want %d", tt.value, result, tt.expected)
		}
	}
}

func TestEncodeVarintTo(t *testing.T) {
	buf := make([]byte, 8)

	tests := []struct {
		value    uint64
		expected int
	}{
		{0, 1},
		{63, 1},
		{64, 2},
		{16383, 2},
		{16384, 4},
		{1073741823, 4},
		{1073741824, 8},
		{MaxVarint, 8},
	}

	for _, tt := range tests {
		n, err := EncodeVarintTo(buf, tt.value)
		if err != nil {
			t.Errorf("EncodeVarintTo(buf, %d) error: %v", tt.value, err)
			continue
		}
		if n != tt.expected {
			t.Errorf("EncodeVarintTo(buf, %d) = %d, want %d", tt.value, n, tt.expected)
		}

		// Verify round-trip
		decoded, consumed, err := DecodeVarint(buf[:n])
		if err != nil {
			t.Errorf("DecodeVarint after EncodeVarintTo(%d) error: %v", tt.value, err)
			continue
		}
		if decoded != tt.value || consumed != n {
			t.Errorf("Round-trip failed for %d: got (%d, %d)", tt.value, decoded, consumed)
		}
	}
}

func TestEncodeVarintTo_ShortBuffer(t *testing.T) {
	tests := []struct {
		value   uint64
		bufSize int
	}{
		{64, 1},         // Needs 2 bytes
		{16384, 3},      // Needs 4 bytes
		{1073741824, 7}, // Needs 8 bytes
	}

	for _, tt := range tests {
		buf := make([]byte, tt.bufSize)
		_, err := EncodeVarintTo(buf, tt.value)
		if err != io.ErrShortBuffer {
			t.Errorf("EncodeVarintTo(buf[:%d], %d) error = %v, want io.ErrShortBuffer", tt.bufSize, tt.value, err)
		}
	}
}

func TestReadVarint(t *testing.T) {
	tests := []struct {
		data     []byte
		expected uint64
	}{
		{[]byte{0x00}, 0},
		{[]byte{0x25}, 37},
		{[]byte{0x40, 0x40}, 64},
		{[]byte{0x80, 0x00, 0x40, 0x00}, 16384},
		{[]byte{0xC0, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00}, 1073741824},
	}

	for _, tt := range tests {
		r := bytes.NewReader(tt.data)
		value, err := ReadVarint(r)
		if err != nil {
			t.Errorf("ReadVarint(%x) error: %v", tt.data, err)
			continue
		}
		if value != tt.expected {
			t.Errorf("ReadVarint(%x) = %d, want %d", tt.data, value, tt.expected)
		}
	}
}

func TestReadVarint_EOF(t *testing.T) {
	r := bytes.NewReader([]byte{})
	_, err := ReadVarint(r)
	if err != io.EOF {
		t.Errorf("ReadVarint(empty) error = %v, want io.EOF", err)
	}
}

func TestReadVarint_UnexpectedEOF(t *testing.T) {
	tests := [][]byte{
		{0x40},                   // 2-byte prefix, only 1 byte
		{0x80, 0x00},             // 4-byte prefix, only 2 bytes
		{0xC0, 0x00, 0x00, 0x00}, // 8-byte prefix, only 4 bytes
	}

	for _, data := range tests {
		r := bytes.NewReader(data)
		_, err := ReadVarint(r)
		// io.ReadFull returns io.EOF when reading 0 bytes, io.ErrUnexpectedEOF otherwise
		if err != io.ErrUnexpectedEOF && err != io.EOF {
			t.Errorf("ReadVarint(%x) error = %v, want io.ErrUnexpectedEOF or io.EOF", data, err)
		}
	}
}
