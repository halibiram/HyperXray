# TProxy AI Integration

## Overview

AI-powered TProxy optimizer has been integrated into HyperXray to dynamically optimize TProxy configuration parameters based on real-time network performance metrics. The system uses machine learning to adjust MTU, buffer sizes, timeouts, and other TProxy settings to maximize performance.

## Architecture

### Components

1. **TProxyAiOptimizer** (`app/src/main/kotlin/com/hyperxray/an/telemetry/TProxyAiOptimizer.kt`)
   - Main optimizer class that uses AI to optimize TProxy configuration
   - Collects metrics, runs AI inference, and applies optimized settings
   - Runs continuous optimization loop (default: every 30 seconds)

2. **TProxyMetricsCollector** (`app/src/main/kotlin/com/hyperxray/an/telemetry/TProxyMetricsCollector.kt`)
   - Collects real-time performance metrics from TProxy and Xray-core
   - Measures throughput, RTT, packet loss, handshake time
   - Maintains metrics history for trend analysis

3. **DeepPolicyModel** (Enhanced)
   - Added `inferRaw()` method to get raw model output for TProxy optimization
   - Supports both server selection and TProxy parameter optimization

4. **TProxyService** (Enhanced)
   - Integrated with TProxyAiOptimizer
   - Automatically starts optimization when VPN service starts
   - Updates TProxy configuration file when AI recommends changes

## Optimized Parameters

The AI optimizer adjusts the following TProxy parameters:

- **MTU** (`tunnelMtuCustom`): Maximum Transmission Unit (1380-1500)
- **Task Stack Size** (`taskStackSizeCustom`): Thread stack size (16KB-256KB)
- **TCP Buffer Size** (`tcpBufferSize`): TCP buffer size (8KB-65KB)
- **File Descriptor Limit** (`limitNofile`): Maximum open files (1024-1M)
- **Connect Timeout** (`connectTimeout`): Connection timeout (1s-30s)
- **Read/Write Timeout** (`readWriteTimeout`): I/O timeout (5s-300s)
- **SOCKS5 Pipeline** (`socks5Pipeline`): Enable/disable pipeline mode
- **Tunnel Multi-Queue** (`tunnelMultiQueue`): Enable/disable multi-queue

## AI Model

### Model Architecture

- **Input**: 16 features (throughput, RTT, loss, handshake time, jitter, uplink, downlink, goroutines, memory, current config, network conditions)
- **Output**: 5 actions (MTU adjustment, buffer adjustment, timeout adjustment, pipeline enable, multi-queue enable)
- **Architecture**: 16 → 64 → 64 → 32 → 5 (with dropout and ReLU activations)
- **Output Activation**: Sigmoid (probabilities for each action)

### Training

The model is trained on synthetic telemetry data using `tools/gen_hyperxray_policy.py`:

```bash
python3 tools/gen_hyperxray_policy.py
```

The script generates:
- `hyperxray_policy.onnx` - FP32 model
- `hyperxray_policy_fp16.onnx` - FP16 quantized model (smaller size)
- `hyperxray_policy_int8.onnx` - INT8 quantized model (smallest size)

### Model Location

Models are stored in `app/src/main/assets/models/` and loaded automatically by the app.

## Optimization Algorithm

1. **Metrics Collection**: Collects real-time metrics from TProxy and Xray-core
2. **Feature Extraction**: Normalizes metrics to [0, 1] range for AI model input
3. **AI Inference**: Runs ONNX model to predict optimal configuration adjustments
4. **Configuration Generation**: Creates optimized configuration based on AI recommendations
5. **Validation**: Validates configuration against safety bounds
6. **Improvement Estimation**: Calculates expected performance improvement
7. **Application**: Applies configuration if improvement > 2% and config changed
8. **Reload**: Updates TProxy configuration file (takes effect on next connection)

## Performance Targets

The optimizer aims to:
- **Maximize Throughput**: Increase MTU and buffer sizes for high-throughput scenarios
- **Minimize Latency**: Reduce timeouts and enable optimizations for low-latency scenarios
- **Minimize Packet Loss**: Adjust MTU and buffer sizes based on loss patterns
- **Optimize Resource Usage**: Balance performance with resource consumption

## Safety Constraints

Configuration changes are validated against safe bounds:
- MTU: 1380-1500 bytes
- Task Stack: 16KB-256KB
- TCP Buffer: 8KB-65KB
- File Descriptors: 1024-1M
- Timeouts: Within reasonable ranges

## Usage

### Automatic Optimization

The optimizer starts automatically when the VPN service starts. No user intervention required.

### Manual Control

To disable AI optimization, you can modify the optimization interval or disable it in the code:

```kotlin
// In TProxyService.kt
tproxyAiOptimizer!!.startOptimization(
    coreStatsState = coreStatsState,
    optimizationIntervalMs = Long.MAX_VALUE // Disable by setting very long interval
)
```

### Monitoring

Check logs for optimization activity:

```bash
adb logcat | grep TProxyAiOptimizer
```

Example log output:
```
I/TProxyAiOptimizer: Applied optimized TProxy configuration: MTU=8500, Buffer=65536, Pipeline=true, MultiQueue=true, NeedsReload=true
I/TProxyAiOptimizer: Expected improvement: 25.5%
```

## Configuration Reload

When AI optimizer applies new configuration:
1. Configuration is saved to Preferences
2. TProxy configuration file is updated
3. Changes take effect on next connection or service restart

**Note**: Some configuration changes may require TProxy service restart to take effect immediately. The current implementation updates the config file, and changes will be applied on the next connection.

## Future Enhancements

1. **Hot Reload**: Implement hot reload for TProxy configuration without service restart
2. **A/B Testing**: Compare optimized vs. baseline configurations
3. **Persistent Learning**: Store optimization results for future learning
4. **User Feedback**: Allow users to provide feedback on optimization results
5. **Custom Profiles**: Support user-defined optimization profiles
6. **Real-time Monitoring**: Add UI dashboard for optimization metrics

## Dependencies

- `ai.onnxruntime` - ONNX Runtime for model inference
- `com.hyperxray.an.common.CoreStatsClient` - Xray-core stats API client
- `com.hyperxray.an.telemetry.DeepPolicyModel` - ONNX model wrapper
- `com.hyperxray.an.prefs.Preferences` - Configuration storage

## Testing

To test the AI optimizer:

1. Start VPN service
2. Monitor logs for optimization activity
3. Check TProxy configuration file for changes
4. Monitor network performance metrics

## Troubleshooting

### Model Not Loading

If the AI model fails to load:
- Check that `hyperxray_policy.onnx` exists in `app/src/main/assets/models/`
- Check logs for model loading errors
- The optimizer will fall back to heuristic optimization

### No Optimization Applied

If no optimization is applied:
- Check that metrics are being collected (check `TProxyMetricsCollector` logs)
- Check that expected improvement > 2%
- Check that configuration actually changed

### Performance Degradation

If performance degrades after optimization:
- Check optimization logs for applied changes
- Manually reset TProxy configuration if needed
- Report issue with optimization logs

## References

- [TProxy Service Implementation](app/src/main/kotlin/com/hyperxray/an/service/TProxyService.kt)
- [AI Optimizer Implementation](app/src/main/kotlin/com/hyperxray/an/telemetry/TProxyAiOptimizer.kt)
- [Metrics Collector Implementation](app/src/main/kotlin/com/hyperxray/an/telemetry/TProxyMetricsCollector.kt)
- [Model Training Script](tools/gen_hyperxray_policy.py)

