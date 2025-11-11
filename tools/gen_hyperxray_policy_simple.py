#!/usr/bin/env python3
"""
HyperXray-AI Optimizer — Simple Model Generator (ONNX Builder)

Generates hyperxray_policy.onnx using pure ONNX (no PyTorch required).
This is a fallback when PyTorch is not available.
"""
import sys
from pathlib import Path

try:
    import onnx
    from onnx import helper, TensorProto
    import numpy as np
except ImportError:
    print("[AI] ✗ Error: onnx package not installed")
    print("[AI] Install with: pip install onnx numpy")
    sys.exit(1)


def main():
    # Get project root
    script_dir = Path(__file__).parent.absolute()
    project_root = script_dir.parent
    model_path = project_root / "app" / "src" / "main" / \
        "assets" / "models" / "hyperxray_policy.onnx"

    model_path.parent.mkdir(parents=True, exist_ok=True)

    # Check if model already exists
    if model_path.exists():
        print(f"[AI] Model already exists at: {model_path}")
        print(f"[AI] Skipping generation. Delete the file to regenerate.")
        return 0

    print(f"[AI] Generating ONNX model → {model_path}")

    try:
        # Create input tensor: [batch=1, features=8]
        input_tensor = helper.make_tensor_value_info(
            'input',
            TensorProto.FLOAT,
            [1, 8]  # [batch_size, context_dim]
        )

        # Create output tensor: [batch=1, output=5]
        output_tensor = helper.make_tensor_value_info(
            'output',
            TensorProto.FLOAT,
            [1, 5]  # [batch_size, output_dim]
        )

        # Create a simple neural network-like structure
        # We'll use MatMul + Add + Relu layers to approximate a feedforward network

        # Layer 1: 8 -> 64
        # Create weight and bias for first layer
        w1_shape = [8, 64]
        b1_shape = [64]
        w1_data = np.random.randn(*w1_shape).astype(np.float32) * 0.1
        b1_data = np.random.randn(*b1_shape).astype(np.float32) * 0.1

        w1 = helper.make_tensor(
            'weight1', TensorProto.FLOAT, w1_shape, w1_data.flatten().tolist())
        b1 = helper.make_tensor(
            'bias1', TensorProto.FLOAT, b1_shape, b1_data.flatten().tolist())

        # MatMul + Add + Relu for layer 1
        matmul1 = helper.make_node(
            'MatMul', ['input', 'weight1'], ['matmul1_out'])
        add1 = helper.make_node('Add', ['matmul1_out', 'bias1'], ['add1_out'])
        relu1 = helper.make_node('Relu', ['add1_out'], ['relu1_out'])

        # Layer 2: 64 -> 64
        w2_shape = [64, 64]
        b2_shape = [64]
        w2_data = np.random.randn(*w2_shape).astype(np.float32) * 0.1
        b2_data = np.random.randn(*b2_shape).astype(np.float32) * 0.1

        w2 = helper.make_tensor(
            'weight2', TensorProto.FLOAT, w2_shape, w2_data.flatten().tolist())
        b2 = helper.make_tensor(
            'bias2', TensorProto.FLOAT, b2_shape, b2_data.flatten().tolist())

        matmul2 = helper.make_node(
            'MatMul', ['relu1_out', 'weight2'], ['matmul2_out'])
        add2 = helper.make_node('Add', ['matmul2_out', 'bias2'], ['add2_out'])
        relu2 = helper.make_node('Relu', ['add2_out'], ['relu2_out'])

        # Layer 3: 64 -> 5
        w3_shape = [64, 5]
        b3_shape = [5]
        w3_data = np.random.randn(*w3_shape).astype(np.float32) * 0.1
        b3_data = np.random.randn(*b3_shape).astype(np.float32) * 0.1

        w3 = helper.make_tensor(
            'weight3', TensorProto.FLOAT, w3_shape, w3_data.flatten().tolist())
        b3 = helper.make_tensor(
            'bias3', TensorProto.FLOAT, b3_shape, b3_data.flatten().tolist())

        matmul3 = helper.make_node(
            'MatMul', ['relu2_out', 'weight3'], ['matmul3_out'])
        add3 = helper.make_node('Add', ['matmul3_out', 'bias3'], ['add3_out'])

        # Softmax for output probabilities
        softmax = helper.make_node('Softmax', ['add3_out'], ['output'], axis=1)

        # Create graph
        graph = helper.make_graph(
            [matmul1, add1, relu1, matmul2, add2, relu2, matmul3, add3, softmax],
            'hyperxray_policy',
            [input_tensor],
            [output_tensor],
            initializer=[w1, b1, w2, b2, w3, b3]
        )

        # Create model
        model = helper.make_model(graph, producer_name='hyperxray')
        model.opset_import[0].version = 18

        # Save model
        onnx.save(model, str(model_path))

        # Verify model was created
        if model_path.exists() and model_path.stat().st_size > 0:
            size_kb = model_path.stat().st_size / 1024
            print(f"[AI] ✓ Model created successfully ({size_kb:.2f} KB)")
            print(f"[AI]   Input shape: [1, 8]")
            print(f"[AI]   Output shape: [1, 5]")
            return 0
        else:
            print("[AI] ✗ Error: Model file was not created properly")
            return 1

    except Exception as e:
        print(f"[AI] ✗ Error generating model: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
