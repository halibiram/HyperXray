package wireguard

// WireGuard Timer Configuration
// Using default WireGuard-go timer values
//
// Default WireGuard-go values (device/timers.go):
// - RekeyAfterTime = 120 seconds (2 minutes)
// - RekeyAttemptTime = 90 seconds
// - RekeyTimeout = 5 seconds
// - RejectAfterTime = 180 seconds
// - KeepaliveTimeout = 10 seconds

import (
	"time"
)

// Timer constants for WireGuard handshake
// Using default WireGuard values
const (
	// RekeyAfterTime is the time after which a new handshake is initiated
	// Default: 120 seconds (2 minutes)
	RekeyAfterTime = 120 * time.Second

	// RekeyAttemptTime is the time to wait before retrying a failed handshake
	// Default: 90 seconds
	RekeyAttemptTime = 90 * time.Second

	// RekeyTimeout is the timeout for a single handshake attempt
	// Default: 5 seconds
	RekeyTimeout = 5 * time.Second

	// RejectAfterTime is the time after which old keys are rejected
	// Default: 180 seconds (3 minutes)
	RejectAfterTime = 180 * time.Second

	// KeepaliveTimeout is the time between keepalive packets
	// Default: 10 seconds
	KeepaliveTimeout = 10 * time.Second
)
