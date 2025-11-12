package com.hyperxray.an.optimizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LearnerState: Manages temperature and bias parameters for on-device learning.
 * 
 * Parameters:
 * - temperature T: Controls prediction confidence (higher = more uniform)
 * - svcBias[8]: Service type biases (one per service class)
 * - routeBias[3]: Routing decision biases (proxy, direct, optimized)
 * 
 * Stored persistently in SharedPreferences with atomic updates.
 */
class LearnerState(context: Context) {
    private val TAG = "LearnerState"
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "tls_sni_learner_state", Context.MODE_PRIVATE
    )
    
    // Default values
    private val DEFAULT_TEMPERATURE = 1.0f
    private val DEFAULT_BIAS = 0.0f
    // Give route decision 2 (optimized) a very large positive initial bias to encourage exploration
    // ONNX model gives route 2 very low scores (-1.8 to -2.0), so we need a very strong bias
    // With 10x scaling, 0.6 bias = 6.0 logit boost, which should make route 2 competitive or preferred
    private val DEFAULT_ROUTE_BIAS_OPTIMIZED = 0.6f
    
    // Keys
    private val KEY_TEMPERATURE = "temperature"
    private val KEY_SVC_BIAS_PREFIX = "svc_bias_"
    private val KEY_ROUTE_BIAS_PREFIX = "route_bias_"
    private val KEY_SUCCESS_COUNT = "successCount"
    private val KEY_FAIL_COUNT = "failCount"
    
    /**
     * Get current temperature.
     */
    fun getTemperature(): Float {
        return prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
    }
    
    /**
     * Get service type biases (8 elements).
     */
    fun getSvcBiases(): FloatArray {
        return FloatArray(8) { i ->
            prefs.getFloat("$KEY_SVC_BIAS_PREFIX$i", DEFAULT_BIAS)
        }
    }
    
    /**
     * Get routing decision biases (3 elements).
     * Route decision 2 (optimized) gets a larger positive initial bias to encourage exploration.
     * Migration: If route_bias_2 is < 0.2 (too low), automatically boost it to encourage exploration.
     */
    fun getRouteBiases(): FloatArray {
        val biases = FloatArray(3) { i ->
            val defaultValue = if (i == 2) DEFAULT_ROUTE_BIAS_OPTIMIZED else DEFAULT_BIAS
            val currentBias = prefs.getFloat("$KEY_ROUTE_BIAS_PREFIX$i", defaultValue)
            currentBias
        }
        
        // Migration: If route_bias_2 is < 0.55 (too low), boost it to encourage exploration
        // This fixes the chicken-egg problem where route 2 never gets selected because bias is too low
        // ONNX model gives route 2 very low scores (-1.8 to -2.0), so we need a very strong initial bias
        // With 10x scaling, 0.6 bias = 6.0 logit boost, which should make route 2 competitive or preferred
        if (biases[2] < 0.55f) {
            Log.i(TAG, "Route bias 2 is ${biases[2]}, migrating to ${DEFAULT_ROUTE_BIAS_OPTIMIZED} to encourage exploration")
            updateRouteBias(2, DEFAULT_ROUTE_BIAS_OPTIMIZED)
            biases[2] = DEFAULT_ROUTE_BIAS_OPTIMIZED
        }
        
        return biases
    }
    
    /**
     * Update temperature atomically.
     */
    fun updateTemperature(newTemp: Float) {
        prefs.edit().putFloat(KEY_TEMPERATURE, newTemp).apply()
        Log.d(TAG, "Updated temperature: $newTemp")
    }
    
    /**
     * Update service type bias atomically.
     */
    fun updateSvcBias(index: Int, bias: Float) {
        if (index in 0..7) {
            prefs.edit().putFloat("$KEY_SVC_BIAS_PREFIX$index", bias).apply()
            Log.d(TAG, "Updated svcBias[$index]: $bias")
        }
    }
    
    /**
     * Update routing decision bias atomically.
     */
    fun updateRouteBias(index: Int, bias: Float) {
        if (index in 0..2) {
            prefs.edit().putFloat("$KEY_ROUTE_BIAS_PREFIX$index", bias).apply()
            Log.d(TAG, "Updated routeBias[$index]: $bias")
        }
    }
    
    /**
     * Update all service biases atomically.
     */
    fun updateAllSvcBiases(biases: FloatArray) {
        if (biases.size != 8) {
            Log.e(TAG, "Invalid svcBiases size: ${biases.size}, expected 8")
            return
        }
        
        val editor = prefs.edit()
        biases.forEachIndexed { index, bias ->
            editor.putFloat("$KEY_SVC_BIAS_PREFIX$index", bias)
        }
        editor.apply()
        Log.d(TAG, "Updated all svcBiases")
    }
    
    /**
     * Update all route biases atomically.
     */
    fun updateAllRouteBiases(biases: FloatArray) {
        if (biases.size != 3) {
            Log.e(TAG, "Invalid routeBiases size: ${biases.size}, expected 3")
            return
        }
        
        val editor = prefs.edit()
        biases.forEachIndexed { index, bias ->
            editor.putFloat("$KEY_ROUTE_BIAS_PREFIX$index", bias)
        }
        editor.apply()
        Log.d(TAG, "Updated all routeBiases")
    }
    
    
    /**
     * Get success count.
     */
    fun getSuccessCount(): Int {
        return prefs.getInt(KEY_SUCCESS_COUNT, 0)
    }
    
    /**
     * Get fail count.
     */
    fun getFailCount(): Int {
        return prefs.getInt(KEY_FAIL_COUNT, 0)
    }
    
    /**
     * Increment success count atomically.
     */
    fun incrementSuccess() {
        val current = getSuccessCount()
        prefs.edit().putInt(KEY_SUCCESS_COUNT, current + 1).apply()
    }
    
    /**
     * Increment fail count atomically.
     */
    fun incrementFail() {
        val current = getFailCount()
        prefs.edit().putInt(KEY_FAIL_COUNT, current + 1).apply()
    }
    
    /**
     * Get success rate (0.0 to 1.0).
     */
    fun getSuccessRate(): Float {
        val success = getSuccessCount()
        val fail = getFailCount()
        val total = success + fail
        return if (total > 0) success.toFloat() / total else 0.5f
    }
    
    /**
     * Save state (explicit save for clarity).
     */
    fun save() {
        // All updates are already atomic via apply()
        // This is a no-op but provides explicit API
        Log.d(TAG, "State saved (all updates are atomic)")
    }
    
    /**
     * Get current state as a summary string.
     */
    fun getStateSummary(): String {
        val temp = getTemperature()
        val svcBiases = getSvcBiases()
        val routeBiases = getRouteBiases()
        val successRate = getSuccessRate()
        return "T=$temp, svcBias=${svcBiases.joinToString(",")}, routeBias=${routeBiases.joinToString(",")}, successRate=$successRate"
    }
    
    /**
     * Reset all parameters to defaults.
     * Route decision 2 (optimized) gets a small positive initial bias.
     */
    fun reset() {
        val editor = prefs.edit()
        editor.putFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        for (i in 0..7) {
            editor.putFloat("$KEY_SVC_BIAS_PREFIX$i", DEFAULT_BIAS)
        }
        for (i in 0..2) {
            val defaultValue = if (i == 2) DEFAULT_ROUTE_BIAS_OPTIMIZED else DEFAULT_BIAS
            editor.putFloat("$KEY_ROUTE_BIAS_PREFIX$i", defaultValue)
        }
        editor.putInt(KEY_SUCCESS_COUNT, 0)
        editor.putInt(KEY_FAIL_COUNT, 0)
        editor.apply()
        Log.i(TAG, "Reset all learner parameters to defaults (route_bias_2=${DEFAULT_ROUTE_BIAS_OPTIMIZED})")
    }
}

