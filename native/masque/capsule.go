package masque

import (
	"errors"
	"io"
)

// Capsule types defined in RFC 9484 (Proxying IP in HTTP)
const (
	// CapsuleTypeIPDatagram carries an IP packet payload
	CapsuleTypeIPDatagram uint64 = 0x00

	// CapsuleTypeAddressAssign assigns an IP address to the client
	CapsuleTypeAddressAssign uint64 = 0x01

	// CapsuleTypeAddressRequest requests an IP address from the server
	CapsuleTypeAddressRequest uint64 = 0x02

	// CapsuleTypeRouteAdvertisement advertises routes to the client
	CapsuleTypeRouteAdvertisement uint64 = 0x03
)

// MasqueMode defines the encapsulation mode
type MasqueMode int

const (
	// MasqueModeConnectIP encapsulates raw IP packets (Layer 3) - RFC 9484
	MasqueModeConnectIP MasqueMode = iota
	// MasqueModeConnectUDP encapsulates UDP datagrams (Layer 4) - RFC 9298
	MasqueModeConnectUDP
)

// Capsule errors
var (
	ErrCapsuleTruncated    = errors.New("capsule: truncated data")
	ErrCapsuleInvalidType  = errors.New("capsule: invalid or unsupported type")
	ErrCapsulePayloadEmpty = errors.New("capsule: empty payload")
	ErrCapsuleTooLarge     = errors.New("capsule: payload exceeds maximum size")
)

// DefaultMTU is the default Maximum Transmission Unit for IP packets
const DefaultMTU = 1500

// MaxCapsulePayload is the maximum payload size we'll accept
const MaxCapsulePayload = 65535

// Capsule represents an HTTP/3 capsule as defined in RFC 9484
//
// Capsule Format:
//
//	+------------+------------+------------+
//	| Type (vi)  | Length (vi)| Payload    |
//	+------------+------------+------------+
//	     1-8B        1-8B       Variable
type Capsule struct {
	Type    uint64
	Payload []byte
}

// CapsuleEncoder handles IP packet to Capsule conversion
type CapsuleEncoder struct {
	mode MasqueMode
	mtu  int
}

// NewCapsuleEncoder creates a new CapsuleEncoder with the specified mode and MTU
func NewCapsuleEncoder(mode MasqueMode, mtu int) *CapsuleEncoder {
	if mtu <= 0 {
		mtu = DefaultMTU
	}
	return &CapsuleEncoder{
		mode: mode,
		mtu:  mtu,
	}
}

// Encode encodes an IP packet (or UDP datagram in CONNECT-UDP mode) into a capsule.
// Returns the encoded capsule bytes.
func (e *CapsuleEncoder) Encode(packet []byte) ([]byte, error) {
	if len(packet) == 0 {
		return nil, ErrCapsulePayloadEmpty
	}

	if len(packet) > MaxCapsulePayload {
		return nil, ErrCapsuleTooLarge
	}

	// For both CONNECT-IP and CONNECT-UDP, we use IP_DATAGRAM capsule type
	// The difference is in what the payload contains:
	// - CONNECT-IP: full IP packet
	// - CONNECT-UDP: UDP payload (context ID + data)
	capsuleType := CapsuleTypeIPDatagram

	// Calculate total size needed
	typeLen := VarintLen(capsuleType)
	payloadLen := uint64(len(packet))
	lengthLen := VarintLen(payloadLen)

	totalLen := typeLen + lengthLen + len(packet)
	buf := make([]byte, totalLen)

	// Encode type
	offset := 0
	n, err := EncodeVarintTo(buf[offset:], capsuleType)
	if err != nil {
		return nil, err
	}
	offset += n

	// Encode length
	n, err = EncodeVarintTo(buf[offset:], payloadLen)
	if err != nil {
		return nil, err
	}
	offset += n

	// Copy payload
	copy(buf[offset:], packet)

	return buf, nil
}

// EncodeTo encodes an IP packet into the provided buffer.
// Returns the number of bytes written.
func (e *CapsuleEncoder) EncodeTo(buf []byte, packet []byte) (int, error) {
	if len(packet) == 0 {
		return 0, ErrCapsulePayloadEmpty
	}

	if len(packet) > MaxCapsulePayload {
		return 0, ErrCapsuleTooLarge
	}

	capsuleType := CapsuleTypeIPDatagram
	payloadLen := uint64(len(packet))

	// Calculate required size
	typeLen := VarintLen(capsuleType)
	lengthLen := VarintLen(payloadLen)
	totalLen := typeLen + lengthLen + len(packet)

	if len(buf) < totalLen {
		return 0, io.ErrShortBuffer
	}

	// Encode type
	offset := 0
	n, err := EncodeVarintTo(buf[offset:], capsuleType)
	if err != nil {
		return 0, err
	}
	offset += n

	// Encode length
	n, err = EncodeVarintTo(buf[offset:], payloadLen)
	if err != nil {
		return 0, err
	}
	offset += n

	// Copy payload
	copy(buf[offset:], packet)

	return totalLen, nil
}

// CapsuleDecoder handles Capsule to IP packet conversion
type CapsuleDecoder struct {
	mode MasqueMode
}

// NewCapsuleDecoder creates a new CapsuleDecoder with the specified mode
func NewCapsuleDecoder(mode MasqueMode) *CapsuleDecoder {
	return &CapsuleDecoder{
		mode: mode,
	}
}

// Decode decodes a capsule from the given bytes.
// Returns the payload (IP packet or UDP datagram) and number of bytes consumed.
func (d *CapsuleDecoder) Decode(data []byte) ([]byte, int, error) {
	if len(data) == 0 {
		return nil, 0, ErrCapsuleTruncated
	}

	offset := 0

	// Decode type
	capsuleType, n, err := DecodeVarint(data[offset:])
	if err != nil {
		return nil, 0, ErrCapsuleTruncated
	}
	offset += n

	// Decode length
	if offset >= len(data) {
		return nil, 0, ErrCapsuleTruncated
	}
	payloadLen, n, err := DecodeVarint(data[offset:])
	if err != nil {
		return nil, 0, ErrCapsuleTruncated
	}
	offset += n

	// Validate payload length
	if payloadLen > MaxCapsulePayload {
		return nil, 0, ErrCapsuleTooLarge
	}

	// Check we have enough data for payload
	if uint64(len(data)-offset) < payloadLen {
		return nil, 0, ErrCapsuleTruncated
	}

	// For IP_DATAGRAM capsules, return the payload directly
	// For other types, we could handle them differently in the future
	if capsuleType != CapsuleTypeIPDatagram {
		// Skip unknown capsule types (as per RFC)
		// Return nil payload but consume the bytes
		return nil, offset + int(payloadLen), nil
	}

	// Extract payload
	payload := make([]byte, payloadLen)
	copy(payload, data[offset:offset+int(payloadLen)])

	return payload, offset + int(payloadLen), nil
}

// DecodeTo decodes a capsule into the provided buffer.
// Returns the payload length and total bytes consumed from input.
func (d *CapsuleDecoder) DecodeTo(dst []byte, data []byte) (payloadLen int, consumed int, err error) {
	if len(data) == 0 {
		return 0, 0, ErrCapsuleTruncated
	}

	offset := 0

	// Decode type
	capsuleType, n, err := DecodeVarint(data[offset:])
	if err != nil {
		return 0, 0, ErrCapsuleTruncated
	}
	offset += n

	// Decode length
	if offset >= len(data) {
		return 0, 0, ErrCapsuleTruncated
	}
	pLen, n, err := DecodeVarint(data[offset:])
	if err != nil {
		return 0, 0, ErrCapsuleTruncated
	}
	offset += n

	// Validate payload length
	if pLen > MaxCapsulePayload {
		return 0, 0, ErrCapsuleTooLarge
	}

	// Check we have enough data for payload
	if uint64(len(data)-offset) < pLen {
		return 0, 0, ErrCapsuleTruncated
	}

	totalConsumed := offset + int(pLen)

	// Skip unknown capsule types
	if capsuleType != CapsuleTypeIPDatagram {
		return 0, totalConsumed, nil
	}

	// Check destination buffer size
	if len(dst) < int(pLen) {
		return 0, 0, io.ErrShortBuffer
	}

	// Copy payload to destination
	copy(dst, data[offset:offset+int(pLen)])

	return int(pLen), totalConsumed, nil
}

// ReadCapsule reads a single capsule from an io.Reader.
// Returns the decoded Capsule struct.
func ReadCapsule(r io.Reader) (*Capsule, error) {
	// Read type
	capsuleType, err := ReadVarint(r)
	if err != nil {
		return nil, err
	}

	// Read length
	payloadLen, err := ReadVarint(r)
	if err != nil {
		return nil, err
	}

	// Validate payload length
	if payloadLen > MaxCapsulePayload {
		return nil, ErrCapsuleTooLarge
	}

	// Read payload
	payload := make([]byte, payloadLen)
	if payloadLen > 0 {
		if _, err := io.ReadFull(r, payload); err != nil {
			return nil, err
		}
	}

	return &Capsule{
		Type:    capsuleType,
		Payload: payload,
	}, nil
}

// WriteCapsule writes a capsule to an io.Writer.
func WriteCapsule(w io.Writer, c *Capsule) error {
	// Encode type
	typeBytes, err := EncodeVarint(c.Type)
	if err != nil {
		return err
	}
	if _, err := w.Write(typeBytes); err != nil {
		return err
	}

	// Encode length
	lengthBytes, err := EncodeVarint(uint64(len(c.Payload)))
	if err != nil {
		return err
	}
	if _, err := w.Write(lengthBytes); err != nil {
		return err
	}

	// Write payload
	if len(c.Payload) > 0 {
		if _, err := w.Write(c.Payload); err != nil {
			return err
		}
	}

	return nil
}
