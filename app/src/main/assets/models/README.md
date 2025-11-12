# HyperXray Policy Model

This directory contains the ONNX model for DeepPolicyModel inference.

## Model Files

- `hyperxray_policy.onnx`: FP32 trained model (~400-600 KB)
- `hyperxray_policy_fp16.onnx`: FP16 quantized model (~300 KB, optional)
- `hyperxray_policy_int8.onnx`: INT8 quantized model (~200 KB, optional)

## Auto-Training

The model is automatically **trained** during build using the script at `tools/train_hyperxray_policy.py`.

The training script:

1. Generates 50,000 synthetic telemetry samples
2. Trains a neural network for 12 epochs
3. Exports the trained model to ONNX format
4. Optionally generates FP16 and INT8 quantized variants

To manually train the model:

```bash
# From project root
pip install torch torchvision onnx onnxruntime onnxoptimizer tqdm numpy
pip install onnxconverter-common  # optional, for FP16 quantization
python tools/train_hyperxray_policy.py
```

## Model Architecture

The trained model uses a feedforward neural network:

- **Input layer**: 16 features (RTT, loss, throughput, handshake time, jitter, ASN, time of day, signal strength, etc.)
- **Hidden layer 1**: 64 units with ReLU
- **Hidden layer 2**: 64 units with ReLU
- **Output layer**: 5 units (5 action logits: uTLS/IP/SNI/buf combinations)

## Model Requirements

The model:

- Accepts input shape: `[batch_size, 16]` (16D context features)
- Returns output shape: `[batch_size, 5]` (5 action logits)
- Uses float32 tensors (FP32 model)
- Compatible with ONNX Runtime Mobile 1.20.0+
- Exported with ONNX opset version 18

## Training Details

The model is trained on synthetic network telemetry data:

- **Training samples**: 50,000
- **Epochs**: 12
- **Batch size**: 128
- **Optimizer**: Adam (learning rate: 1e-3)
- **Loss function**: CrossEntropyLoss

The synthetic data includes realistic network metrics with correlations between features and actions (e.g., high RTT correlates with certain action preferences).

## Fallback Generation

If training fails, the build system falls back to `tools/gen_hyperxray_policy.py` which generates a minimal untrained model (8 input features, initialized weights only).
