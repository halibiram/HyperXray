#!/usr/bin/env python3
"""
Update manifest.json with SHA256 hash of the model file.

This script calculates the SHA256 hash of hyperxray_policy.onnx
and updates the manifest.json file with the hash.

Usage:
    python update_manifest_hash.py
"""

import hashlib
import json
import os

def calculate_sha256(file_path):
    """Calculate SHA256 hash of a file."""
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def update_manifest():
    """Update manifest.json with model hash."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(script_dir, "hyperxray_policy.onnx")
    manifest_path = os.path.join(script_dir, "manifest.json")
    
    if not os.path.exists(model_path):
        print(f"Error: Model file not found: {model_path}")
        return False
    
    if not os.path.exists(manifest_path):
        print(f"Error: Manifest file not found: {manifest_path}")
        return False
    
    # Calculate hash
    print(f"Calculating SHA256 hash of {model_path}...")
    model_hash = calculate_sha256(model_path)
    print(f"Hash: {model_hash}")
    
    # Load manifest
    with open(manifest_path, "r") as f:
        manifest = json.load(f)
    
    # Update hash
    manifest["model"]["sha256"] = model_hash
    
    # Save manifest
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    
    print(f"✓ Updated manifest.json with hash: {model_hash[:16]}...")
    return True

if __name__ == "__main__":
    try:
        update_manifest()
    except Exception as e:
        print(f"Error: {e}")
        exit(1)



