# hev-socks5-tunnel AI Integration

## Overview

hev-socks5-tunnel native library has been integrated with the AI-powered TProxy optimizer to provide real-time performance metrics and enable dynamic configuration optimization.

## Native Statistics Integration

### TProxyGetStats() Function

The native `TProxyGetStats()` function provides real-time statistics from hev-socks5-tunnel:

- **txPackets**: Transmitted packets count
- **txBytes**: Transmitted bytes count
- **rxPackets**: Received packets count
- **rxBytes**: Received bytes count

These statistics are collected at the native TProxy layer, providing accurate measurements of actual tunnel performance.

## Integration Architecture

### 1. Native Stats Collection

```kotlin
// Native function exposed via JNI
TProxyService.TProxyGetStats(): LongArray?
// Returns: [txPackets, txBytes, rxPackets, rxBytes]
```

### 2. Metrics Collector Enhancement

`TProxyMetricsCollector` now uses native stats as the primary data source:

- **Throughput Calculation**: Uses native tx/rx bytes for accurate throughput measurement
- **Packet Loss Estimation**: Compares tx/rx packet counts to estimate loss
- **Fallback**: Falls back to Xray-core stats if native stats unavailable

### 3. AI Optimizer Integration

The AI optimizer uses native stats for:
- More accurate performance metrics
- Real-time TProxy layer performance monitoring
- Better optimization decisions based on actual tunnel performance

## Benefits

### 1. Accurate Metrics

Native stats provide direct measurement of TProxy layer performance, not just Xray-core stats:
- Measures actual tunnel throughput
- Detects packet loss at TProxy layer
- Provides real-time performance data

### 2. Better Optimization

AI optimizer can make better decisions with accurate metrics:
- Optimizes based on actual TProxy performance
- Adjusts parameters based on real tunnel behavior
- Detects performance issues at TProxy layer

### 3. Real-time Monitoring

Native stats enable real-time monitoring:
- Continuous performance tracking
- Immediate detection of performance changes
- Better responsiveness to network conditions

## Configuration Hot Reload

When AI optimizer applies new configuration:

1. **Configuration Update**: Preferences are updated with optimized values
2. **Config File Update**: TProxy configuration file is regenerated
3. **Service Restart**: TProxy service is restarted to apply new configuration
4. **Stats Reset**: Native stats are reset after restart

### Hot Reload Process

```kotlin
// 1. Update configuration file
FileOutputStream(tproxyFile, false).use { fos ->
    val tproxyConf = getTproxyConf(prefs)
    fos.write(tproxyConf.toByteArray())
}

// 2. Restart TProxy service
TProxyStopService()
Thread.sleep(100) // Brief delay for clean shutdown
TProxyStartService(tproxyFile.absolutePath, fd)
```

## Native Stats Usage

### Throughput Calculation

```kotlin
val throughput = calculateThroughputFromNative(nativeStats)
// Uses tx/rx bytes delta over time
// More accurate than Xray-core stats
```

### Packet Loss Estimation

```kotlin
val loss = estimatePacketLossFromNative(nativeStats, coreStatsState)
// Compares tx/rx packet counts
// Estimates loss based on packet ratio
```

## Performance Improvements

### 1. Accurate Metrics → Better Decisions

- Native stats provide accurate TProxy layer metrics
- AI optimizer makes better optimization decisions
- Results in improved overall performance

### 2. Real-time Adaptation

- Continuous monitoring of TProxy performance
- Immediate response to performance changes
- Dynamic adjustment of configuration parameters

### 3. Reduced Overhead

- Native stats collection is efficient
- Minimal performance impact
- Real-time metrics without significant overhead

## Implementation Details

### Native Function Mapping

```c
// JNI function in hev-jni.c
static jlongArray native_get_stats (JNIEnv *env, jobject thiz)
{
    size_t tx_packets, rx_packets, tx_bytes, rx_bytes;
    hev_socks5_tunnel_stats (&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    // Returns [tx_packets, tx_bytes, rx_packets, rx_bytes]
}
```

### Kotlin Integration

```kotlin
// Public function in TProxyService
fun TProxyGetStats(): LongArray? {
    return try {
        nativeGetStats()
    } catch (e: Exception) {
        Log.e(TAG, "Error getting TProxy stats", e)
        null
    }
}
```

### Metrics Collector

```kotlin
// Collect native stats
val nativeStats = collectNativeTProxyStats()

// Calculate throughput from native stats
val throughput = calculateThroughputFromNative(nativeStats)

// Estimate packet loss from native stats
val loss = estimatePacketLossFromNative(nativeStats, coreStatsState)
```

## Usage

### Automatic Integration

Native stats are automatically collected and used by the AI optimizer. No manual configuration required.

### Monitoring

Check logs for native stats usage:

```bash
adb logcat | grep TProxyMetricsCollector
```

Example log output:
```
D/TProxyMetricsCollector: Collected metrics: throughput=50.2MB/s, rtt=45ms, loss=0.1%, nativeStats=true
```

## Troubleshooting

### Native Stats Not Available

If native stats are not available:
- Check that TProxy service is running
- Verify hev-socks5-tunnel library is loaded
- Check logs for JNI errors
- Fallback to Xray-core stats automatically

### Stats Reset After Reload

Native stats are reset after TProxy service restart:
- This is expected behavior
- Stats will accumulate again after restart
- AI optimizer will continue optimizing with new stats

## Future Enhancements

1. **Extended Stats**: Add more detailed native stats (latency, jitter, etc.)
2. **Stats Persistence**: Persist stats across service restarts
3. **Advanced Loss Detection**: Implement more sophisticated packet loss detection
4. **Performance Profiling**: Add performance profiling capabilities
5. **Stats Dashboard**: Add UI dashboard for native stats visualization

## References

- [hev-socks5-tunnel JNI Implementation](app/src/main/jni/hev-socks5-tunnel/src/hev-jni.c)
- [TProxyMetricsCollector](app/src/main/kotlin/com/hyperxray/an/telemetry/TProxyMetricsCollector.kt)
- [TProxyService](app/src/main/kotlin/com/hyperxray/an/service/TProxyService.kt)
- [TProxy AI Integration](TPROXY_AI_INTEGRATION.md)

