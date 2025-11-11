package com.hyperxray.an.telemetry

import kotlin.math.sqrt

/**
 * Scaler: Normalizes context features for neural network inference.
 * 
 * Provides standardization (mean=0, std=1) and min-max normalization
 * for context vectors used in DeepPolicyModel.
 */
class Scaler(
    /**
     * Mean values for each feature dimension (for standardization)
     */
    private val mean: DoubleArray? = null,
    
    /**
     * Standard deviation values for each feature dimension
     */
    private val std: DoubleArray? = null,
    
    /**
     * Minimum values for each feature dimension (for min-max scaling)
     */
    private val min: DoubleArray? = null,
    
    /**
     * Maximum values for each feature dimension (for min-max scaling)
     */
    private val max: DoubleArray? = null
) {
    /**
     * Normalize context using standardization (z-score normalization).
     * If mean/std not provided, computes from input data.
     * 
     * @param context Input context vector
     * @return Normalized context vector (mean=0, std=1)
     */
    fun standardize(context: DoubleArray): DoubleArray {
        val computedMean = mean ?: computeMean(context)
        val computedStd = std ?: computeStd(context, computedMean)
        
        return context.mapIndexed { index, value ->
            val m = computedMean.getOrNull(index) ?: computedMean[0]
            val s = computedStd.getOrNull(index) ?: computedStd[0]
            if (s != 0.0) (value - m) / s else 0.0
        }.toDoubleArray()
    }
    
    /**
     * Normalize context using min-max scaling to [0, 1] range.
     * If min/max not provided, computes from input data.
     * 
     * @param context Input context vector
     * @return Normalized context vector in [0, 1] range
     */
    fun minMaxScale(context: DoubleArray): DoubleArray {
        val computedMin = min ?: context.minOrNull()?.let { doubleArrayOf(it) } ?: doubleArrayOf(0.0)
        val computedMax = max ?: context.maxOrNull()?.let { doubleArrayOf(it) } ?: doubleArrayOf(1.0)
        
        return context.mapIndexed { index, value ->
            val minVal = computedMin.getOrNull(index) ?: computedMin[0]
            val maxVal = computedMax.getOrNull(index) ?: computedMax[0]
            if (maxVal != minVal) {
                ((value - minVal) / (maxVal - minVal)).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        }.toDoubleArray()
    }
    
    /**
     * Normalize context for ONNX inference.
     * Uses standardization by default, falls back to min-max if needed.
     * 
     * @param context Input context vector
     * @return Normalized context as FloatArray for ONNX
     */
    fun normalize(context: DoubleArray): FloatArray {
        val normalized = if (mean != null || std != null) {
            standardize(context)
        } else {
            minMaxScale(context)
        }
        return normalized.map { it.toFloat() }.toFloatArray()
    }
    
    /**
     * Compute mean of context vector
     */
    private fun computeMean(context: DoubleArray): DoubleArray {
        val meanValue = context.average()
        return DoubleArray(context.size) { meanValue }
    }
    
    /**
     * Compute standard deviation of context vector
     */
    private fun computeStd(context: DoubleArray, mean: DoubleArray): DoubleArray {
        val meanValue = mean[0]
        val variance = context.map { (it - meanValue) * (it - meanValue) }.average()
        val stdValue = sqrt(variance)
        return DoubleArray(context.size) { if (stdValue > 0.0) stdValue else 1.0 }
    }
    
    companion object {
        /**
         * Create a Scaler with default normalization (min-max to [0, 1])
         */
        fun default(): Scaler {
            return Scaler()
        }
        
        /**
         * Create a Scaler with pre-computed statistics
         */
        fun withStats(mean: DoubleArray, std: DoubleArray): Scaler {
            return Scaler(mean = mean, std = std)
        }
    }
}

