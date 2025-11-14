package com.hyperxray.an.feature.policyai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Benchmark utility for measuring ONNX Runtime Mobile inference performance.
 * 
 * Compares inference time before and after ONNX Runtime Mobile integration.
 * 
 * Usage:
 * ```kotlin
 * val benchmark = RoutingBenchmark(context)
 * val results = benchmark.measureInferenceTime(features, iterations = 100)
 * Log.d(TAG, "Average inference time: ${results.averageMs}ms")
 * ```
 */
object RoutingBenchmark {
    private const val TAG = "RoutingBenchmark"
    
    /**
     * Benchmark results for inference time measurement.
     */
    data class BenchmarkResults(
        val iterations: Int,
        val totalTimeMs: Double,
        val averageMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val medianMs: Double,
        val p95Ms: Double,
        val p99Ms: Double
    )
    
    /**
     * Measure inference time for ONNX Runtime Mobile routing engine.
     * 
     * @param context Android context
     * @param features Test feature vector (32 elements)
     * @param iterations Number of iterations to run (default: 100)
     * @param warmupIterations Warmup iterations before measurement (default: 10)
     * @return BenchmarkResults with timing statistics
     */
    suspend fun measureInferenceTime(
        context: Context,
        features: FloatArray = generateTestFeatures(),
        iterations: Int = 100,
        warmupIterations: Int = 10
    ): BenchmarkResults = withContext(Dispatchers.Default) {
        // Ensure engine is initialized
        if (!OnnxRuntimeRoutingEngine.isReady()) {
            OnnxRuntimeRoutingEngine.initialize(context)
        }
        
        if (!OnnxRuntimeRoutingEngine.isReady()) {
            Log.w(TAG, "Engine not ready, returning zero results")
            return@withContext BenchmarkResults(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        
        // Warmup: run inference a few times to warm up JIT and native code
        Log.d(TAG, "Warming up with $warmupIterations iterations...")
        repeat(warmupIterations) {
            OnnxRuntimeRoutingEngine.evaluateRouting(features)
        }
        
        // Actual benchmark: measure inference time
        Log.d(TAG, "Benchmarking with $iterations iterations...")
        val timings = mutableListOf<Double>()
        
        repeat(iterations) {
            val startTime = System.nanoTime()
            OnnxRuntimeRoutingEngine.evaluateRouting(features)
            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            timings.add(durationMs)
        }
        
        // Calculate statistics
        timings.sort()
        val totalTime = timings.sum()
        val average = totalTime / iterations
        val min = timings.minOrNull() ?: 0.0
        val max = timings.maxOrNull() ?: 0.0
        val median = timings[iterations / 2]
        val p95Index = (iterations * 0.95).toInt().coerceAtMost(iterations - 1)
        val p99Index = (iterations * 0.99).toInt().coerceAtMost(iterations - 1)
        val p95 = timings[p95Index]
        val p99 = timings[p99Index]
        
        val results = BenchmarkResults(
            iterations = iterations,
            totalTimeMs = totalTime,
            averageMs = average,
            minMs = min,
            maxMs = max,
            medianMs = median,
            p95Ms = p95,
            p99Ms = p99
        )
        
        Log.i(TAG, "Benchmark results: avg=${average.format(2)}ms, " +
                "min=${min.format(2)}ms, max=${max.format(2)}ms, " +
                "median=${median.format(2)}ms, p95=${p95.format(2)}ms, p99=${p99.format(2)}ms")
        
        return@withContext results
    }
    
    /**
     * Generate test feature vector for benchmarking.
     * Uses realistic domain-based features.
     */
    private fun generateTestFeatures(): FloatArray {
        // Generate features similar to SniFeatureEncoder for realistic testing
        val features = FloatArray(32) { i ->
            when (i) {
                0 -> 0.15f // Domain length (normalized)
                1 -> 0.2f  // Dots count
                2 -> 0.1f  // Digit count
                3 -> 0.6f  // Unique chars
                4 -> 0.45f // Hash normalization
                5 -> 0.3f  // ALPN id (h2)
                6 -> 0.65f // Entropy
                7 -> 0.3f  // Latency normalization
                8 -> 0.7f  // Throughput normalization
                9 -> 0.5f  // Cipher diversity
                10 -> 0.4f // Extension count
                11 -> 0.3f // ClientHello length
                12 -> 0.5f // Hour of day
                13 -> 0.5f // Day of week
                else -> {
                    // Pseudo-random padding (deterministic)
                    val hash = 42
                    val pseudoRandom = ((hash + i * 31) % 200 - 100) / 100f
                    pseudoRandom.coerceIn(-0.5f, 0.5f)
                }
            }
        }
        return features
    }
    
    /**
     * Format double to string with specified decimal places.
     */
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
    
    /**
     * Compare old vs new implementation (for PR documentation).
     * 
     * This method should be called with both old and new implementations
     * to generate before/after comparison data.
     */
    suspend fun compareImplementations(
        context: Context,
        oldImpl: suspend (FloatArray) -> RoutingDecision,
        newImpl: suspend (FloatArray) -> RoutingDecision,
        features: FloatArray = generateTestFeatures(),
        iterations: Int = 100
    ): Pair<BenchmarkResults, BenchmarkResults> {
        Log.i(TAG, "Comparing old vs new implementation...")
        
        // Benchmark old implementation
        Log.d(TAG, "Benchmarking old implementation...")
        val oldTimings = mutableListOf<Double>()
        repeat(iterations) {
            val startTime = System.nanoTime()
            oldImpl(features)
            val endTime = System.nanoTime()
            oldTimings.add((endTime - startTime) / 1_000_000.0)
        }
        val oldResults = calculateResults(oldTimings, iterations)
        
        // Benchmark new implementation
        Log.d(TAG, "Benchmarking new implementation...")
        val newTimings = mutableListOf<Double>()
        repeat(iterations) {
            val startTime = System.nanoTime()
            newImpl(features)
            val endTime = System.nanoTime()
            newTimings.add((endTime - startTime) / 1_000_000.0)
        }
        val newResults = calculateResults(newTimings, iterations)
        
        // Log comparison
        val improvement = ((oldResults.averageMs - newResults.averageMs) / oldResults.averageMs * 100)
        Log.i(TAG, "Performance comparison:")
        Log.i(TAG, "  Old average: ${oldResults.averageMs.format(2)}ms")
        Log.i(TAG, "  New average: ${newResults.averageMs.format(2)}ms")
        Log.i(TAG, "  Improvement: ${improvement.format(1)}%")
        
        return Pair(oldResults, newResults)
    }
    
    private fun calculateResults(timings: List<Double>, iterations: Int): BenchmarkResults {
        timings.sorted()
        val totalTime = timings.sum()
        val average = totalTime / iterations
        val min = timings.minOrNull() ?: 0.0
        val max = timings.maxOrNull() ?: 0.0
        val sorted = timings.sorted()
        val median = sorted[iterations / 2]
        val p95Index = (iterations * 0.95).toInt().coerceAtMost(iterations - 1)
        val p99Index = (iterations * 0.99).toInt().coerceAtMost(iterations - 1)
        val p95 = sorted[p95Index]
        val p99 = sorted[p99Index]
        
        return BenchmarkResults(
            iterations = iterations,
            totalTimeMs = totalTime,
            averageMs = average,
            minMs = min,
            maxMs = max,
            medianMs = median,
            p95Ms = p95,
            p99Ms = p99
        )
    }
}

