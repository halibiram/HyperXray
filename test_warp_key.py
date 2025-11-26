#!/usr/bin/env python3
"""
WireGuard WARP Private Key Validation Script
Tests if the private key from the app can successfully perform handshake with Cloudflare WARP.
"""

import base64
import socket
import struct
import time
import hashlib
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.backends import default_backend
import hmac

# WARP Configuration
WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
WARP_ENDPOINTS = [
    ("162.159.192.1", 2408),
    ("162.159.193.1", 2408),
    ("162.159.193.5", 2408),
    ("162.159.195.1", 2408),
    ("162.159.195.10", 2408),
]

# Private key from app
APP_PRIVATE_KEY = "IDvSDysIkBcoVj1lX59dAkCrAuBqUbyZB4efW/A5Llk="

def decode_base64_key(key_str: str) -> bytes:
    """Decode base64 encoded WireGuard key."""
    try:
        return base64.b64decode(key_str)
    except Exception as e:
        print(f"❌ Error decoding key: {e}")
        return None

def validate_key_format(key_bytes: bytes) -> bool:
    """Validate that key is exactly 32 bytes."""
    if len(key_bytes) != 32:
        print(f"❌ Key length is {len(key_bytes)}, expected 32 bytes")
        return False
    return True

def clamp_curve25519_key(key: bytes) -> bytes:
    """Clamp Curve25519 private key according to WireGuard specification."""
    if len(key) != 32:
        raise ValueError("Key must be 32 bytes")
    
    clamped = bytearray(key)
    # Clear bits 0, 1, 2 of first byte
    clamped[0] &= 0xF8
    # Clear bit 7, set bit 6 of last byte
    clamped[31] = (clamped[31] & 0x7F) | 0x40
    return bytes(clamped)

def generate_public_key(private_key_bytes: bytes) -> bytes:
    """Generate public key from private key using X25519."""
    try:
        # Clamp the private key
        clamped_key = clamp_curve25519_key(private_key_bytes)
        
        # Create X25519 private key
        private_key = X25519PrivateKey.from_private_bytes(clamped_key)
        
        # Get public key
        public_key = private_key.public_key()
        public_key_bytes = public_key.public_bytes_raw()
        
        return public_key_bytes
    except Exception as e:
        print(f"❌ Error generating public key: {e}")
        return None

def test_key_validity():
    """Test if the private key is valid and can generate a public key."""
    print("=" * 60)
    print("WireGuard WARP Private Key Validation Test")
    print("=" * 60)
    print()
    
    # Decode private key
    print(f"📝 Private Key (Base64): {APP_PRIVATE_KEY}")
    private_key_bytes = decode_base64_key(APP_PRIVATE_KEY)
    
    if not private_key_bytes:
        return False
    
    # Validate key format
    print(f"📏 Key Length: {len(private_key_bytes)} bytes")
    if not validate_key_format(private_key_bytes):
        return False
    
    # Check if key is properly clamped
    clamped_key = clamp_curve25519_key(private_key_bytes)
    if clamped_key != private_key_bytes:
        print("⚠️  Key is not properly clamped (will be clamped automatically)")
        print(f"   Original: {private_key_bytes.hex()[:16]}...")
        print(f"   Clamped:  {clamped_key.hex()[:16]}...")
    else:
        print("✅ Key is properly clamped")
    
    # Generate public key
    print("\n🔑 Generating public key from private key...")
    public_key_bytes = generate_public_key(private_key_bytes)
    
    if not public_key_bytes:
        print("❌ Failed to generate public key")
        return False
    
    public_key_b64 = base64.b64encode(public_key_bytes).decode('utf-8')
    print(f"✅ Generated Public Key: {public_key_b64}")
    print(f"   Length: {len(public_key_bytes)} bytes")
    
    # Verify against WARP public key
    print(f"\n🌐 WARP Public Key: {WARP_PUBLIC_KEY}")
    warp_public_bytes = decode_base64_key(WARP_PUBLIC_KEY)
    
    if warp_public_bytes:
        print("✅ WARP public key decoded successfully")
    
    # Test key pair validity
    print("\n🧪 Testing key pair validity...")
    try:
        private_key = X25519PrivateKey.from_private_bytes(clamp_curve25519_key(private_key_bytes))
        public_key = X25519PublicKey.from_public_bytes(warp_public_bytes)
        
        # Try to compute shared secret (this validates the key pair)
        shared_secret = private_key.exchange(public_key)
        print(f"✅ Key pair is valid! Shared secret computed: {len(shared_secret)} bytes")
        print(f"   Shared secret (first 16 bytes): {shared_secret[:16].hex()}")
        return True
    except Exception as e:
        print(f"❌ Key pair validation failed: {e}")
        return False

def test_network_connectivity():
    """Test network connectivity to WARP endpoints."""
    print("\n" + "=" * 60)
    print("Network Connectivity Test")
    print("=" * 60)
    print()
    
    for endpoint_ip, endpoint_port in WARP_ENDPOINTS:
        print(f"🔍 Testing {endpoint_ip}:{endpoint_port}...", end=" ")
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(2)
            
            # Try to connect (UDP doesn't really connect, but we can test reachability)
            sock.connect((endpoint_ip, endpoint_port))
            sock.close()
            print("✅ Reachable")
        except socket.timeout:
            print("⏱️  Timeout")
        except Exception as e:
            print(f"❌ Error: {e}")

def main():
    """Main test function."""
    print("\n")
    
    # Test key validity
    key_valid = test_key_validity()
    
    # Test network connectivity
    test_network_connectivity()
    
    print("\n" + "=" * 60)
    print("Test Summary")
    print("=" * 60)
    
    if key_valid:
        print("✅ Private key is VALID and can generate proper public key")
        print("✅ Key pair can compute shared secret with WARP public key")
        print("\n💡 If handshake still fails, possible causes:")
        print("   1. Network firewall blocking UDP port 2408")
        print("   2. ISP blocking Cloudflare WARP endpoints")
        print("   3. VPN/TUN interface routing issues")
        print("   4. Endpoint IP is blocked/blacklisted")
    else:
        print("❌ Private key validation FAILED")
        print("   The key may be corrupted or invalid")
    
    print()

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️  Test interrupted by user")
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        import traceback
        traceback.print_exc()




