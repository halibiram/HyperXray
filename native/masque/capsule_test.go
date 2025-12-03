package masque

import (
	"bytes"
	"testing"
)

func TestCapsuleEncoder_Encode(t *testing.T) {
	encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)

	tests := []struct {
		name    string
		packet  []byte
		wantErr error
	}{
		{
			name:    "small packet",
			packet:  []byte{0x45, 0x00, 0x00, 0x14}, // Minimal IP header start
			wantErr: nil,
		},
		{
			name:    "single byte",
			packet:  []byte{0xFF},
			wantErr: nil,
		},
		{
			name:    "empty packet",
			packet:  []byte{},
			wantErr: ErrCapsulePayloadEmpty,
		},
		{
			name:    "MTU sized packet",
			packet:  make([]byte, DefaultMTU),
			wantErr: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := encoder.Encode(tt.packet)
			if err != tt.wantErr {
				t.Errorf("Encode() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if tt.wantErr != nil {
				return
			}

			// Verify structure: type (1 byte for 0x00) + length (varint) + payload
			if len(result) < 2+len(tt.packet) {
				t.Errorf("Encode() result too short: got %d bytes", len(result))
			}

			// First byte should be 0x00 (IP_DATAGRAM type)
			if result[0] != 0x00 {
				t.Errorf("Encode() type = %x, want 0x00", result[0])
			}
		})
	}
}

func TestCapsuleEncoder_EncodeTo(t *testing.T) {
	encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)
	packet := []byte{0x45, 0x00, 0x00, 0x14, 0x00, 0x01}

	// Buffer large enough
	buf := make([]byte, 100)
	n, err := encoder.EncodeTo(buf, packet)
	if err != nil {
		t.Fatalf("EncodeTo() error = %v", err)
	}

	// Should be: type(1) + length(1) + payload(6) = 8 bytes
	expectedLen := 1 + 1 + len(packet)
	if n != expectedLen {
		t.Errorf("EncodeTo() = %d, want %d", n, expectedLen)
	}

	// Buffer too small
	smallBuf := make([]byte, 2)
	_, err = encoder.EncodeTo(smallBuf, packet)
	if err == nil {
		t.Error("EncodeTo() with small buffer should error")
	}
}

func TestCapsuleDecoder_Decode(t *testing.T) {
	decoder := NewCapsuleDecoder(MasqueModeConnectIP)

	tests := []struct {
		name        string
		data        []byte
		wantPayload []byte
		wantErr     error
	}{
		{
			name:        "valid capsule",
			data:        []byte{0x00, 0x04, 0x45, 0x00, 0x00, 0x14}, // type=0, len=4, payload
			wantPayload: []byte{0x45, 0x00, 0x00, 0x14},
			wantErr:     nil,
		},
		{
			name:        "single byte payload",
			data:        []byte{0x00, 0x01, 0xFF},
			wantPayload: []byte{0xFF},
			wantErr:     nil,
		},
		{
			name:        "empty input",
			data:        []byte{},
			wantPayload: nil,
			wantErr:     ErrCapsuleTruncated,
		},
		{
			name:        "truncated - no length",
			data:        []byte{0x00},
			wantPayload: nil,
			wantErr:     ErrCapsuleTruncated,
		},
		{
			name:        "truncated - incomplete payload",
			data:        []byte{0x00, 0x04, 0x45, 0x00}, // claims 4 bytes, only 2
			wantPayload: nil,
			wantErr:     ErrCapsuleTruncated,
		},
		{
			name:        "unknown capsule type - skipped",
			data:        []byte{0x05, 0x02, 0xAA, 0xBB}, // type=5 (unknown), len=2
			wantPayload: nil,                            // Unknown types return nil payload
			wantErr:     nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			payload, _, err := decoder.Decode(tt.data)
			if err != tt.wantErr {
				t.Errorf("Decode() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if !bytes.Equal(payload, tt.wantPayload) {
				t.Errorf("Decode() payload = %x, want %x", payload, tt.wantPayload)
			}
		})
	}
}

func TestCapsuleDecoder_DecodeTo(t *testing.T) {
	decoder := NewCapsuleDecoder(MasqueModeConnectIP)
	data := []byte{0x00, 0x04, 0x45, 0x00, 0x00, 0x14}

	// Buffer large enough
	dst := make([]byte, 10)
	payloadLen, consumed, err := decoder.DecodeTo(dst, data)
	if err != nil {
		t.Fatalf("DecodeTo() error = %v", err)
	}
	if payloadLen != 4 {
		t.Errorf("DecodeTo() payloadLen = %d, want 4", payloadLen)
	}
	if consumed != 6 {
		t.Errorf("DecodeTo() consumed = %d, want 6", consumed)
	}
	if !bytes.Equal(dst[:payloadLen], []byte{0x45, 0x00, 0x00, 0x14}) {
		t.Errorf("DecodeTo() payload = %x", dst[:payloadLen])
	}

	// Buffer too small
	smallDst := make([]byte, 2)
	_, _, err = decoder.DecodeTo(smallDst, data)
	if err == nil {
		t.Error("DecodeTo() with small buffer should error")
	}
}

func TestCapsule_RoundTrip(t *testing.T) {
	encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)
	decoder := NewCapsuleDecoder(MasqueModeConnectIP)

	testPackets := [][]byte{
		{0x45, 0x00, 0x00, 0x14}, // Minimal
		make([]byte, 100),        // Medium
		make([]byte, 1500),       // MTU
	}

	// Fill test packets with data
	for i := range testPackets {
		for j := range testPackets[i] {
			testPackets[i][j] = byte(j % 256)
		}
	}

	for i, original := range testPackets {
		encoded, err := encoder.Encode(original)
		if err != nil {
			t.Errorf("packet %d: Encode() error = %v", i, err)
			continue
		}

		decoded, consumed, err := decoder.Decode(encoded)
		if err != nil {
			t.Errorf("packet %d: Decode() error = %v", i, err)
			continue
		}

		if consumed != len(encoded) {
			t.Errorf("packet %d: consumed %d, want %d", i, consumed, len(encoded))
		}

		if !bytes.Equal(decoded, original) {
			t.Errorf("packet %d: round-trip failed, got %d bytes, want %d bytes", i, len(decoded), len(original))
		}
	}
}

func TestReadWriteCapsule(t *testing.T) {
	original := &Capsule{
		Type:    CapsuleTypeIPDatagram,
		Payload: []byte{0x45, 0x00, 0x00, 0x14, 0x00, 0x01, 0x00, 0x00},
	}

	// Write to buffer
	var buf bytes.Buffer
	err := WriteCapsule(&buf, original)
	if err != nil {
		t.Fatalf("WriteCapsule() error = %v", err)
	}

	// Read back
	result, err := ReadCapsule(&buf)
	if err != nil {
		t.Fatalf("ReadCapsule() error = %v", err)
	}

	if result.Type != original.Type {
		t.Errorf("Type = %d, want %d", result.Type, original.Type)
	}
	if !bytes.Equal(result.Payload, original.Payload) {
		t.Errorf("Payload = %x, want %x", result.Payload, original.Payload)
	}
}

func TestReadWriteCapsule_EmptyPayload(t *testing.T) {
	original := &Capsule{
		Type:    CapsuleTypeAddressRequest,
		Payload: []byte{},
	}

	var buf bytes.Buffer
	err := WriteCapsule(&buf, original)
	if err != nil {
		t.Fatalf("WriteCapsule() error = %v", err)
	}

	result, err := ReadCapsule(&buf)
	if err != nil {
		t.Fatalf("ReadCapsule() error = %v", err)
	}

	if result.Type != original.Type {
		t.Errorf("Type = %d, want %d", result.Type, original.Type)
	}
	if len(result.Payload) != 0 {
		t.Errorf("Payload should be empty, got %x", result.Payload)
	}
}

func TestCapsuleEncoder_LargePayload(t *testing.T) {
	encoder := NewCapsuleEncoder(MasqueModeConnectIP, DefaultMTU)

	// Test with payload that requires 2-byte length encoding
	largePacket := make([]byte, 1000)
	for i := range largePacket {
		largePacket[i] = byte(i % 256)
	}

	encoded, err := encoder.Encode(largePacket)
	if err != nil {
		t.Fatalf("Encode() error = %v", err)
	}

	// Verify: type(1) + length(2, since 1000 > 63) + payload(1000)
	expectedMinLen := 1 + 2 + 1000
	if len(encoded) != expectedMinLen {
		t.Errorf("Encode() len = %d, want %d", len(encoded), expectedMinLen)
	}

	// Decode and verify
	decoder := NewCapsuleDecoder(MasqueModeConnectIP)
	decoded, _, err := decoder.Decode(encoded)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if !bytes.Equal(decoded, largePacket) {
		t.Error("Round-trip failed for large packet")
	}
}

func TestNewCapsuleEncoder_DefaultMTU(t *testing.T) {
	encoder := NewCapsuleEncoder(MasqueModeConnectIP, 0)
	if encoder.mtu != DefaultMTU {
		t.Errorf("mtu = %d, want %d", encoder.mtu, DefaultMTU)
	}

	encoder = NewCapsuleEncoder(MasqueModeConnectIP, -1)
	if encoder.mtu != DefaultMTU {
		t.Errorf("mtu = %d, want %d", encoder.mtu, DefaultMTU)
	}
}
