package com.hyperxray.an.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin wrapper for native Go multi-instance Xray manager.
 * Provides a clean API for managing multiple Xray instances through native Go code.
 */
class NativeMultiInstanceManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NativeMultiInstanceMgr"
        
        @Volatile
        private var instance: NativeMultiInstanceManager? = null
        
        /**
         * Get singleton instance.
         */
        fun getInstance(context: Context): NativeMultiInstanceManager {
            return instance ?: synchronized(this) {
                instance ?: NativeMultiInstanceManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        /**
         * Reset singleton (for testing).
         */
        @Synchronized
        fun resetInstance() {
            instance?.cleanup()
            instance = null
        }
    }
    
    /**
     * Instance status enum.
     */
    enum class InstanceStatus {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }
    
    /**
     * Data class representing instance info.
     */
    data class InstanceInfo(
        val index: Int,
        val status: InstanceStatus,
        val apiPort: Int,
        val startTime: Long = 0,
        val errorMsg: String? = null,
        val txBytes: Long = 0,
        val rxBytes: Long = 0,
        val connections: Int = 0
    )
    
    private val _instancesStatus = MutableStateFlow<Map<Int, InstanceInfo>>(emptyMap())
    val instancesStatus: StateFlow<Map<Int, InstanceInfo>> = _instancesStatus.asStateFlow()
    
    private var initialized = false
    private var vpnService: HyperVpnService? = null
    
    /**
     * Initialize the native multi-instance manager.
     * Must be called before any other operations.
     * 
     * @param service The HyperVpnService instance (required for JNI calls)
     * @param maxInstances Maximum number of instances (1-8)
     * @return true if initialization was successful
     */
    fun initialize(service: HyperVpnService, maxInstances: Int = 4): Boolean {
        if (initialized) {
            Log.d(TAG, "Already initialized")
            return true
        }
        
        vpnService = service
        
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val filesDir = context.filesDir.absolutePath
        
        Log.i(TAG, "Initializing native multi-instance manager: nativeLibDir=$nativeLibDir, filesDir=$filesDir, maxInstances=$maxInstances")
        
        try {
            val result = service.initMultiInstanceManagerNative(nativeLibDir, filesDir, maxInstances)
            if (result == 0) {
                initialized = true
                Log.i(TAG, "Native multi-instance manager initialized successfully")
                return true
            } else {
                Log.e(TAG, "Failed to initialize native multi-instance manager: error code $result")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Start multiple Xray instances.
     * 
     * @param count Number of instances to start (1-8)
     * @param configJSON Xray configuration JSON
     * @param excludedPorts Ports to exclude when assigning API ports
     * @return Map of instance index to API port, or null on error
     */
    fun startInstances(
        count: Int,
        configJSON: String,
        excludedPorts: Set<Int> = emptySet()
    ): Map<Int, Int>? {
        val service = vpnService
        if (service == null || !initialized) {
            Log.e(TAG, "Manager not initialized")
            return null
        }
        
        Log.i(TAG, "Starting $count instances...")
        
        val excludedPortsJSON = JSONArray(excludedPorts.toList()).toString()
        
        return try {
            val resultJSON = service.startMultiInstancesNative(count, configJSON, excludedPortsJSON)
            val result = JSONObject(resultJSON)
            
            if (result.optBoolean("success", false)) {
                val instancesObj = result.getJSONObject("instances")
                val instanceMap = mutableMapOf<Int, Int>()
                
                instancesObj.keys().forEach { key ->
                    instanceMap[key.toInt()] = instancesObj.getInt(key)
                }
                
                // Update status
                updateStatusFromNative()
                
                Log.i(TAG, "Started ${instanceMap.size} instances: $instanceMap")
                instanceMap
            } else {
                val error = result.optString("error", "Unknown error")
                Log.e(TAG, "Failed to start instances: $error")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting instances: ${e.message}", e)
            null
        }
    }
    
    /**
     * Stop a specific instance.
     */
    fun stopInstance(index: Int): Boolean {
        val service = vpnService
        if (service == null || !initialized) {
            Log.e(TAG, "Manager not initialized")
            return false
        }
        
        return try {
            val result = service.stopMultiInstanceNative(index)
            updateStatusFromNative()
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping instance $index: ${e.message}", e)
            false
        }
    }
    
    /**
     * Stop all instances.
     */
    fun stopAllInstances(): Boolean {
        val service = vpnService
        if (service == null || !initialized) {
            Log.e(TAG, "Manager not initialized")
            return false
        }
        
        return try {
            val result = service.stopAllMultiInstancesNative()
            _instancesStatus.value = emptyMap()
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping all instances: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get status of a specific instance.
     */
    fun getInstanceStatus(index: Int): InstanceInfo? {
        val service = vpnService
        if (service == null || !initialized) {
            return null
        }
        
        return try {
            val statusJSON = service.getMultiInstanceStatusNative(index)
            parseInstanceInfo(JSONObject(statusJSON))
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting instance status: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get status of all instances and update StateFlow.
     */
    fun updateStatusFromNative() {
        val service = vpnService
        if (service == null || !initialized) {
            return
        }
        
        try {
            val statusJSON = service.getAllMultiInstancesStatusNative()
            val statusObj = JSONObject(statusJSON)
            
            val statusMap = mutableMapOf<Int, InstanceInfo>()
            statusObj.keys().forEach { key ->
                val infoObj = statusObj.getJSONObject(key)
                parseInstanceInfo(infoObj)?.let { info ->
                    statusMap[key.toInt()] = info
                }
            }
            
            _instancesStatus.value = statusMap
        } catch (e: Exception) {
            Log.e(TAG, "Exception updating status: ${e.message}", e)
        }
    }
    
    /**
     * Get count of running instances.
     */
    fun getInstanceCount(): Int {
        val service = vpnService
        if (service == null || !initialized) {
            return 0
        }
        
        return try {
            service.getMultiInstanceCountNative()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Check if any instance is running.
     */
    fun isRunning(): Boolean {
        val service = vpnService
        if (service == null || !initialized) {
            return false
        }
        
        return try {
            service.isMultiInstanceRunningNative()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stopAllInstances()
        initialized = false
        vpnService = null
        _instancesStatus.value = emptyMap()
    }
    
    /**
     * Parse InstanceInfo from JSON.
     */
    private fun parseInstanceInfo(json: JSONObject): InstanceInfo? {
        return try {
            InstanceInfo(
                index = json.optInt("index", -1),
                status = parseStatus(json.optString("status", "stopped")),
                apiPort = json.optInt("apiPort", 0),
                startTime = json.optLong("startTime", 0),
                errorMsg = json.optString("errorMsg", null).takeIf { it.isNotEmpty() },
                txBytes = json.optLong("txBytes", 0),
                rxBytes = json.optLong("rxBytes", 0),
                connections = json.optInt("connections", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse status string to enum.
     */
    private fun parseStatus(status: String): InstanceStatus {
        return when (status.lowercase()) {
            "stopped" -> InstanceStatus.STOPPED
            "starting" -> InstanceStatus.STARTING
            "running" -> InstanceStatus.RUNNING
            "stopping" -> InstanceStatus.STOPPING
            "error" -> InstanceStatus.ERROR
            else -> InstanceStatus.STOPPED
        }
    }
}

/**
 * Extension functions to expose native multi-instance methods from HyperVpnService.
 * These are internal and should only be used by NativeMultiInstanceManager.
 */
internal fun HyperVpnService.initMultiInstanceManagerNative(
    nativeLibDir: String,
    filesDir: String,
    maxInstances: Int
): Int {
    // Use reflection to call private native method
    val method = this::class.java.getDeclaredMethod(
        "initMultiInstanceManager",
        String::class.java,
        String::class.java,
        Int::class.java
    )
    method.isAccessible = true
    return method.invoke(this, nativeLibDir, filesDir, maxInstances) as Int
}

internal fun HyperVpnService.startMultiInstancesNative(
    count: Int,
    configJSON: String,
    excludedPortsJSON: String
): String {
    val method = this::class.java.getDeclaredMethod(
        "startMultiInstances",
        Int::class.java,
        String::class.java,
        String::class.java
    )
    method.isAccessible = true
    return method.invoke(this, count, configJSON, excludedPortsJSON) as String
}

internal fun HyperVpnService.stopMultiInstanceNative(index: Int): Int {
    val method = this::class.java.getDeclaredMethod(
        "stopMultiInstance",
        Int::class.java
    )
    method.isAccessible = true
    return method.invoke(this, index) as Int
}

internal fun HyperVpnService.stopAllMultiInstancesNative(): Int {
    val method = this::class.java.getDeclaredMethod("stopAllMultiInstances")
    method.isAccessible = true
    return method.invoke(this) as Int
}

internal fun HyperVpnService.getMultiInstanceStatusNative(index: Int): String {
    val method = this::class.java.getDeclaredMethod(
        "getMultiInstanceStatus",
        Int::class.java
    )
    method.isAccessible = true
    return method.invoke(this, index) as String
}

internal fun HyperVpnService.getAllMultiInstancesStatusNative(): String {
    val method = this::class.java.getDeclaredMethod("getAllMultiInstancesStatus")
    method.isAccessible = true
    return method.invoke(this) as String
}

internal fun HyperVpnService.getMultiInstanceCountNative(): Int {
    val method = this::class.java.getDeclaredMethod("getMultiInstanceCount")
    method.isAccessible = true
    return method.invoke(this) as Int
}

internal fun HyperVpnService.isMultiInstanceRunningNative(): Boolean {
    val method = this::class.java.getDeclaredMethod("isMultiInstanceRunning")
    method.isAccessible = true
    return method.invoke(this) as Boolean
}





