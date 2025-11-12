#!/usr/bin/env python3
"""
Generate a minimal placeholder ONNX model for HyperXray DeepPolicyModel.

This creates a simple identity model that:
- Takes input: [1, 8] float32 (8D context vector)
- Returns output: [1, 1] float32 (single score/probability)

Install dependencies:
    pip install onnx numpy

Run:
    python generate_placeholder_model.py
"""

try:
    import onnx
    from onnx import helper, TensorProto
    import numpy as np
    
    # Create input tensor info: [batch=1, features=8]
    input_tensor = helper.make_tensor_value_info(
        'input',
        TensorProto.FLOAT,
        [1, 8]  # [batch_size, context_dim]
    )
    
    # Create output tensor info: [batch=1, output=1]
    output_tensor = helper.make_tensor_value_info(
        'output',
        TensorProto.FLOAT,
        [1, 1]  # [batch_size, output_dim]
    )
    
    # Create a simple identity-like node (sum of first 4 features as placeholder)
    # In a real model, this would be a neural network
    # For now, we use a simple ReduceSum as placeholder
    reduce_node = helper.make_node(
        'ReduceSum',
        inputs=['input'],
        outputs=['output'],
        axes=[1],  # Sum over feature dimension
        keepdims=1
    )
    
    # Create graph
    graph = helper.make_graph(
        [reduce_node],
        'hyperxray_policy_placeholder',
        [input_tensor],
        [output_tensor]
    )
    
    # Create model
    model = helper.make_model(graph, producer_name='hyperxray')
    
    # Set model version
    model.opset_import[0].version = 13
    
    # Save model
    output_path = 'hyperxray_policy.onnx'
    onnx.save(model, output_path)
    
    print(f"✓ Placeholder ONNX model created: {output_path}")
    print(f"  Input shape: [1, 8]")
    print(f"  Output shape: [1, 1]")
    print(f"  Model size: {len(onnx._serialize(model))} bytes")
    
except ImportError:
    print("Error: onnx package not installed")
    print("Install with: pip install onnx numpy")
    exit(1)
except Exception as e:
    print(f"Error creating model: {e}")
    exit(1)

