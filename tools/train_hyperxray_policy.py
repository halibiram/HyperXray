#!/usr/bin/env python3
"""
HyperXray-AI Optimizer — Policy Model Trainer

Trains a neural policy model using synthetic telemetry data and exports it to ONNX format.
This script generates a real trained model with actual weights (not just initialized).
"""
import sys
import warnings
from pathlib import Path

import torch
import torch.nn as nn
import torch.optim as optim
import numpy as np
from tqdm import tqdm

# ONNX imports (may fail if not installed)
try:
    import onnx
    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

# Suppress verbose warnings
warnings.filterwarnings("ignore", category=UserWarning)
warnings.filterwarnings("ignore", message=".*dynamic_axes.*")
warnings.filterwarnings("ignore", message=".*dynamic_shapes.*")

# ---- Synthetic Training Data (Simulated network metrics) ----

def generate_data(n=10000):
    """
    Generate synthetic training data simulating network telemetry.
    
    Inputs (16 features):
    - rtt, loss, throughput, hs_time, jitter, asn, tod, signal_strength, etc.
    
    Outputs:
    - 5 actions (uTLS/IP/SNI/buf combo)
    """
    # Generate realistic network metrics
    X = np.random.rand(n, 16).astype(np.float32)
    
    # Normalize features to realistic ranges
    # RTT: 0-500ms, Loss: 0-10%, Throughput: 0-100Mbps, etc.
    X[:, 0] = X[:, 0] * 500.0  # RTT
    X[:, 1] = X[:, 1] * 10.0   # Loss %
    X[:, 2] = X[:, 2] * 100.0   # Throughput
    X[:, 3] = X[:, 3] * 2000.0  # Handshake time
    X[:, 4] = X[:, 4] * 50.0    # Jitter
    # ... other features remain normalized
    
    # Generate action labels (0-4) with some correlation to features
    # Higher RTT/loss -> prefer different actions
    y = np.random.randint(0, 5, size=(n,))
    
    # Add some correlation: high RTT -> prefer action 0 or 1
    high_rtt_mask = X[:, 0] > 300
    y[high_rtt_mask] = np.random.choice([0, 1], size=np.sum(high_rtt_mask))
    
    return torch.tensor(X), torch.tensor(y, dtype=torch.long)


# ---- Model Definition ----

class HyperXrayPolicy(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(16, 64),
            nn.ReLU(),
            nn.Linear(64, 64),
            nn.ReLU(),
            nn.Linear(64, 5)
        )
    
    def forward(self, x):
        return self.net(x)


# ---- Training Function ----

def train_model(model, epochs=10, batch_size=64):
    """Train the policy model on synthetic telemetry data."""
    print(f"[AI] Generating {50000} training samples...")
    X, y = generate_data(50000)
    
    dataset = torch.utils.data.TensorDataset(X, y)
    loader = torch.utils.data.DataLoader(
        dataset, 
        batch_size=batch_size, 
        shuffle=True
    )
    
    opt = optim.Adam(model.parameters(), lr=1e-3)
    loss_fn = nn.CrossEntropyLoss()
    model.train()
    
    for epoch in range(epochs):
        pbar = tqdm(loader, desc=f"Epoch {epoch+1}/{epochs}")
        epoch_loss = 0.0
        batch_count = 0
        
        for xb, yb in pbar:
            opt.zero_grad()
            logits = model(xb)
            loss = loss_fn(logits, yb)
            loss.backward()
            opt.step()
            
            epoch_loss += loss.item()
            batch_count += 1
            pbar.set_postfix(loss=f"{loss.item():.4f}")
        
        avg_loss = epoch_loss / batch_count
        print(f"[AI] Epoch {epoch+1}/{epochs} completed - Average loss: {avg_loss:.4f}")
    
    return model


# ---- Quantization Functions ----

def quantize_model_fp16(onnx_path):
    """Convert FP32 model to FP16 for smaller size."""
    try:
        import onnx
        try:
            from onnxconverter_common import float16
            converter_available = True
        except ImportError:
            # Try alternative: onnxruntime quantization
            try:
                from onnxruntime.quantization import quantize_static, QuantType
                converter_available = False
            except ImportError:
                raise ImportError("No FP16 converter available")
        
        if converter_available:
            model_fp32 = onnx.load(str(onnx_path))
            model_fp16 = float16.convert_float_to_float16(model_fp32)
            
            fp16_path = onnx_path.parent / "hyperxray_policy_fp16.onnx"
            onnx.save(model_fp16, str(fp16_path))
        else:
            # Use onnxruntime quantization (though it's mainly for INT8)
            # For FP16, we'll skip if onnxconverter-common is not available
            print("[AI] FP16 conversion requires onnxconverter-common")
            print("[AI] Install with: pip install onnxconverter-common")
            return None
        
        size_kb = fp16_path.stat().st_size / 1024
        print(f"[AI] FP16 model exported: {fp16_path.name} ({size_kb:.2f} KB)")
        return fp16_path
    except ImportError:
        print("[AI] FP16 converter not available, skipping FP16 conversion")
        print("[AI] Install with: pip install onnxconverter-common")
        return None
    except Exception as e:
        print(f"[AI] FP16 conversion failed: {e}")
        return None


def quantize_model_int8(onnx_path):
    """Quantize model to INT8 for smallest size."""
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        import onnx
        
        # Verify model is valid before quantization
        try:
            onnx.checker.check_model(str(onnx_path))
        except Exception as e:
            print(f"[AI] Model validation failed before INT8 quantization: {e}")
            return None
        
        int8_path = onnx_path.parent / "hyperxray_policy_int8.onnx"
        
        # Use dynamic quantization (weights only, activations stay FP32)
        quantize_dynamic(
            str(onnx_path),
            str(int8_path),
            weight_type=QuantType.QInt8
        )
        
        size_kb = int8_path.stat().st_size / 1024
        print(f"[AI] INT8 model exported: {int8_path.name} ({size_kb:.2f} KB)")
        return int8_path
    except ImportError:
        print("[AI] onnxruntime not available, skipping INT8 quantization")
        return None
    except Exception as e:
        print(f"[AI] INT8 quantization failed: {e}")
        import traceback
        traceback.print_exc()
        return None


# ---- Main Execution ----

def main():
    """Main training and export pipeline."""
    # Get project root (assuming script is in tools/ directory)
    script_dir = Path(__file__).parent.absolute()
    project_root = script_dir.parent
    out_dir = project_root / "app" / "src" / "main" / "assets" / "models"
    out_dir.mkdir(parents=True, exist_ok=True)
    
    onnx_path = out_dir / "hyperxray_policy.onnx"
    
    print("[AI] Training policy model on synthetic telemetry...")
    print("[AI] Model architecture: 16 -> 64 -> 64 -> 5")
    
    # Create and train model
    model = HyperXrayPolicy()
    trained_model = train_model(model, epochs=12, batch_size=128)
    trained_model.eval()
    
    print(f"[AI] Exporting model to ONNX: {onnx_path}")
    
    # Export to ONNX
    dummy = torch.randn(1, 16)
    
    try:
        with warnings.catch_warnings():
            warnings.filterwarnings("ignore")
            torch.onnx.export(
                trained_model,
                dummy,
                str(onnx_path),
                export_params=True,
                opset_version=18,
                input_names=["input"],
                output_names=["output"],
                dynamic_axes={
                    'input': {0: 'batch_size'},
                    'output': {0: 'batch_size'}
                },
                do_constant_folding=True
            )
        print(f"[AI] Model exported to: {onnx_path}")
    except Exception as e:
        print(f"[AI] Export failed: {e}")
        import traceback
        traceback.print_exc()
        return 1
    
    # ---- Optimize ----
    if ONNX_AVAILABLE:
        try:
            import onnxoptimizer
            model_onnx = onnx.load(str(onnx_path))
            passes = [
                "eliminate_identity",
                "eliminate_unused_initializer",
                "fuse_consecutive_transposes",
                "fuse_add_bias_into_conv"
            ]
            optimized = onnxoptimizer.optimize(model_onnx, passes)
            onnx.save(optimized, str(onnx_path))
            print("[AI] Model optimized successfully.")
        except ImportError:
            print("[AI] onnxoptimizer not available, skipping optimization")
        except Exception as e:
            print(f"[AI] Optimization skipped: {e}")
    else:
        print("[AI] onnx not available, skipping optimization")
    
    # Check file size
    if onnx_path.exists():
        size_kb = onnx_path.stat().st_size / 1024
        print(f"[AI] Done. File size: {size_kb:.2f} KB")
    else:
        print("[AI] Error: Model file was not created")
        return 1
    
    # ---- Optional Quantization ----
    print("\n[AI] Generating quantized variants...")
    quantize_model_fp16(onnx_path)
    quantize_model_int8(onnx_path)
    
    print("\n[AI] Training complete! Models available:")
    for model_file in out_dir.glob("hyperxray_policy*.onnx"):
        size_kb = model_file.stat().st_size / 1024
        print(f"  - {model_file.name}: {size_kb:.2f} KB")
    
    return 0


if __name__ == "__main__":
    sys.exit(main())

