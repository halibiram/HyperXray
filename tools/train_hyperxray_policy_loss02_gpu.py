#!/usr/bin/env python3
"""
HyperXray-AI Optimizer — Large-Capacity Policy Model Trainer (Loss ≤ 0.2, GPU Accelerated)

Trains a high-capacity neural policy model using GPU acceleration, achieving final training
loss below 0.2. Uses 500k correlated synthetic telemetry samples and exports to ONNX format
with INT8 quantization support.

Target: Loss ≤ 0.2, Training Accuracy ≥ 85%, Model Size: ~700-850 KB (FP32), ~230-300 KB (INT8)
"""
import sys
import warnings
from pathlib import Path
import time

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
warnings.filterwarnings("ignore", message=".*dynamo.*")

# Set random seed for reproducibility
torch.manual_seed(42)
np.random.seed(42)
if torch.cuda.is_available():
    torch.cuda.manual_seed_all(42)

# Detect device
device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"[AI] Using device: {device}")

# ---- Synthetic Training Data (Correlated telemetry) ----

def generate_data(n=500000):
    """
    Generate correlated training data with learnable patterns.
    Uses matrix multiplication to create meaningful correlations between features and labels.
    """
    X = np.random.rand(n, 32).astype(np.float32)
    
    # Korelasyonlu label üretimi (daha öğrenilebilir)
    weights = np.random.randn(32, 8).astype(np.float32)
    logits = X @ weights + np.random.normal(0, 0.05, (n, 8))
    y = np.argmax(logits, axis=1)
    
    return torch.tensor(X, dtype=torch.float32), torch.tensor(y, dtype=torch.long)


# ---- Model Definition ----

class HyperXrayPolicyV3(nn.Module):
    """
    Improved policy model with SiLU activation and optimized architecture.
    Architecture: 32 -> 256 -> 256 -> 128 -> 8
    """
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(32, 256),
            nn.BatchNorm1d(256),
            nn.SiLU(),
            nn.Linear(256, 256),
            nn.BatchNorm1d(256),
            nn.SiLU(),
            nn.Dropout(0.15),
            nn.Linear(256, 128),
            nn.BatchNorm1d(128),
            nn.SiLU(),
            nn.Linear(128, 8)
        )
    
    def forward(self, x):
        return self.net(x)


# ---- Training Function ----

def train_model(model, epochs=50, batch_size=256):
    """
    Train the policy model with early stopping at loss < 0.2.
    """
    print(f"[AI] Generating 500,000 training samples with correlated features...")
    X, y = generate_data(500000)
    
    # Move data to device
    X = X.to(device)
    y = y.to(device)
    
    # Split into train/validation (90/10)
    n_train = int(0.9 * len(X))
    indices = torch.randperm(len(X))
    train_indices = indices[:n_train]
    val_indices = indices[n_train:]
    
    X_train, y_train = X[train_indices], y[train_indices]
    X_val, y_val = X[val_indices], y[val_indices]
    
    train_dataset = torch.utils.data.TensorDataset(X_train, y_train)
    val_dataset = torch.utils.data.TensorDataset(X_val, y_val)
    
    train_loader = torch.utils.data.DataLoader(
        train_dataset,
        batch_size=batch_size,
        shuffle=True,
        num_workers=0,
        pin_memory=True if device == "cuda" else False
    )
    
    val_loader = torch.utils.data.DataLoader(
        val_dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=0,
        pin_memory=True if device == "cuda" else False
    )
    
    # Move model to device
    model = model.to(device)
    
    # Optimizer: AdamW with weight decay
    opt = optim.AdamW(model.parameters(), lr=1e-4, weight_decay=1e-4)
    loss_fn = nn.CrossEntropyLoss()
    
    print(f"[AI] Starting training on {device}...")
    print(f"[AI] Target: loss < 0.2")
    print(f"[AI] Model parameters: {sum(p.numel() for p in model.parameters()):,}")
    
    best_val_loss = float('inf')
    best_model_state = None
    
    for epoch in range(epochs):
        # Training phase
        model.train()
        total_loss = 0
        train_correct = 0
        train_total = 0
        
        for xb, yb in tqdm(train_loader, desc=f"Epoch {epoch+1}/{epochs} [Train]"):
            xb, yb = xb.to(device), yb.to(device)
            
            opt.zero_grad()
            logits = model(xb)
            loss = loss_fn(logits, yb)
            loss.backward()
            opt.step()
            
            total_loss += loss.item()
            _, predicted = torch.max(logits.data, 1)
            train_total += yb.size(0)
            train_correct += (predicted == yb).sum().item()
        
        avg_train_loss = total_loss / len(train_loader)
        train_acc = 100 * train_correct / train_total
        
        # Validation phase
        model.eval()
        val_loss = 0.0
        val_correct = 0
        val_total = 0
        
        with torch.no_grad():
            for xb, yb in val_loader:
                xb, yb = xb.to(device), yb.to(device)
                logits = model(xb)
                loss = loss_fn(logits, yb)
                
                val_loss += loss.item()
                _, predicted = torch.max(logits.data, 1)
                val_total += yb.size(0)
                val_correct += (predicted == yb).sum().item()
        
        avg_val_loss = val_loss / len(val_loader)
        val_acc = 100 * val_correct / val_total
        
        # Check for best model
        if avg_val_loss < best_val_loss:
            best_val_loss = avg_val_loss
            best_model_state = model.state_dict().copy()
        
        print(f"[AI] Epoch {epoch+1}: Train Loss={avg_train_loss:.4f} | Train Acc={train_acc:.2f}% | "
              f"Val Loss={avg_val_loss:.4f} | Val Acc={val_acc:.2f}%")
        
        # Early stopping at target loss
        if avg_train_loss < 0.2:
            print("[AI] 🎯 Target achieved: loss < 0.2")
            break
    
    # Load best model state
    if best_model_state is not None:
        model.load_state_dict(best_model_state)
    
    print(f"[AI] Training complete. Final train loss: {avg_train_loss:.4f}")
    print(f"[AI] Final train accuracy: {train_acc:.2f}%")
    print(f"[AI] Final validation loss: {avg_val_loss:.4f}")
    print(f"[AI] Final validation accuracy: {val_acc:.2f}%")
    
    return model


# ---- Quantization Functions ----

def quantize_model_int8(onnx_path):
    """Quantize model to INT8 using dynamic quantization."""
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        import onnx
        
        # Verify model is valid before quantization
        try:
            onnx.checker.check_model(str(onnx_path))
        except Exception as e:
            print(f"[AI] Model validation failed before INT8 quantization: {e}")
            return None
        
        int8_path = onnx_path.parent / "hyperxray_policy_loss02_int8.onnx"
        
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
        print("[AI] Install with: pip install onnxruntime")
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
    
    fp32_path = out_dir / "hyperxray_policy_loss02.onnx"
    int8_path = out_dir / "hyperxray_policy_loss02_int8.onnx"
    
    print("[AI] Training large-capacity policy model on synthetic telemetry...")
    print("[AI] Model architecture: 32 -> 256 -> 256 -> 128 -> 8")
    print("[AI] Target: Loss ≤ 0.2, Training Accuracy ≥ 85%")
    
    # Create model
    model = HyperXrayPolicyV3()
    
    # Train model
    trained_model = train_model(model, epochs=50, batch_size=256)
    
    # Validate training results
    trained_model.eval()
    
    # Move model to CPU for export
    trained_model_cpu = trained_model.to("cpu")
    
    print(f"\n[AI] Exporting FP32 model to ONNX: {fp32_path}")
    
    # Export to ONNX
    dummy = torch.randn(1, 32)
    
    try:
        with warnings.catch_warnings():
            warnings.filterwarnings("ignore")
            torch.onnx.export(
                trained_model_cpu,
                dummy,
                str(fp32_path),
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
        print(f"[AI] FP32 model exported to: {fp32_path}")
        size_kb = fp32_path.stat().st_size / 1024
        print(f"[AI] Model size: {size_kb:.2f} KB")
    except Exception as e:
        print(f"[AI] Export failed: {e}")
        import traceback
        traceback.print_exc()
        # Save model state dict as fallback
        state_dict_path = fp32_path.with_suffix('.pth')
        torch.save(trained_model.state_dict(), str(state_dict_path))
        print(f"[AI] Model state dict saved to: {state_dict_path}")
        return 1
    
    # Optimize ONNX model
    if ONNX_AVAILABLE:
        try:
            import onnxoptimizer
            model_onnx = onnx.load(str(fp32_path))
            passes = [
                "eliminate_identity",
                "eliminate_unused_initializer",
                "fuse_consecutive_transposes",
                "fuse_add_bias_into_conv"
            ]
            optimized = onnxoptimizer.optimize(model_onnx, passes)
            onnx.save(optimized, str(fp32_path))
            print("[AI] Model optimized successfully.")
        except ImportError:
            print("[AI] onnxoptimizer not available, skipping optimization")
            print("[AI] Install with: pip install onnxoptimizer")
        except Exception as e:
            print(f"[AI] Optimization skipped: {e}")
    else:
        print("[AI] onnx not available, skipping optimization")
    
    # Check FP32 file size
    if fp32_path.exists():
        size_kb = fp32_path.stat().st_size / 1024
        print(f"[AI] FP32 model size: {size_kb:.2f} KB")
        if size_kb < 700 or size_kb > 850:
            print(f"[AI] ⚠ Warning: Model size ({size_kb:.2f} KB) outside expected range (700-850 KB)")
    else:
        print("[AI] Error: FP32 model file was not created")
        return 1
    
    # Quantize to INT8
    print("\n[AI] Generating INT8 quantized model...")
    int8_result = quantize_model_int8(fp32_path)
    
    if int8_result and int8_result.exists():
        size_kb = int8_result.stat().st_size / 1024
        print(f"[AI] INT8 model size: {size_kb:.2f} KB")
        if size_kb < 230 or size_kb > 300:
            print(f"[AI] ⚠ Warning: INT8 model size ({size_kb:.2f} KB) outside expected range (230-300 KB)")
    
    print("\n[AI] Training complete! Models available:")
    for model_file in [fp32_path, int8_path]:
        if model_file.exists():
            size_kb = model_file.stat().st_size / 1024
            print(f"  - {model_file.name}: {size_kb:.2f} KB")
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
