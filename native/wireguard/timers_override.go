package wireguard

// WireGuard Timer Configuration
// These values override the default WireGuard-go timer constants
// to reduce handshake frequency from 2 minutes to 2 hours.
//
// Default WireGuard-go values (device/timers.go):
// - RekeyAfterTime = 120 seconds (2 minutes)
// - RekeyAttemptTime = 90 seconds
// - RekeyTimeout = 5 seconds
// - RejectAfterTime = 180 seconds
//
// Our optimized values:
// - RekeyAfterTime = 7200 seconds (2 hours)
// - Other values remain the same for security

import (
	"time"
)

// Timer constants for WireGuard handshake
// These are used to configure peer timers
const (
	// RekeyAfterTime is the time after which a new handshake is initiated
	// Default: 120 seconds (2 minutes)
	// Optimized: 7200 seconds (2 hours) - reduces handshake frequency
	RekeyAfterTime = 2 * time.Hour

	// RekeyAttemptTime is the time to wait before retrying a failed handshake
	// Default: 90 seconds
	RekeyAttemptTime = 90 * time.Second

	// RekeyTimeout is the timeout for a single handshake attempt
	// Default: 5 seconds
	RekeyTimeout = 5 * time.Second

	// RejectAfterTime is the time after which old keys are rejected
	// Default: 180 seconds (3 minutes)
	// Optimized: 3 hours (must be > RekeyAfterTime for security)
	RejectAfterTime = 3 * time.Hour

	// KeepaliveTimeout is the time between keepalive packets
	// This is set via IPC (persistent_keepalive_interval)
	// Default: 25 seconds (set in WarpManager.kt)
	KeepaliveTimeout = 25 * time.Second
)

// Note: These constants cannot directly override WireGuard-go's internal values
// because they are defined as package-level constants in device/timers.go.
//
// To actually change the rekey interval, we need to either:
// 1. Fork WireGuard-go and modify device/timers.go
// 2. Use a replace directive in go.mod to use our forked version
// 3. Patch the binary at build time
//
// For now, this file documents the desired values and can be used
// as a reference for creating a WireGuard-go fork.
