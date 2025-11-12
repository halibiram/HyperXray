package com.hyperxray.an.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel for AI Insights Dashboard.
 * Loads learner state from SharedPreferences and files.
 */
class AiInsightsViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "AiInsightsViewModel"
    private val gson = Gson()
    
    // Maximum entries to load (1GB limit allows ~6-7 million entries, we load last 1M for optimal AI learning)
    private val MAX_ENTRIES_TO_LOAD = 1_000_000
    
    private val _uiState = MutableStateFlow(AiInsightsUiState())
    val uiState: StateFlow<AiInsightsUiState> = _uiState.asStateFlow()
    
    private val context: Context = application.applicationContext
    private val filesDir: File = context.filesDir
    
    init {
        loadData()
    }
    
    fun refresh() {
        // First trigger RealityWorker to create/update policy files
        triggerRealityWorker()
        // Then reload data after a short delay to allow RealityWorker to finish
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000) // Wait 1 second for RealityWorker to complete
            loadData()
        }
    }
    
    private fun triggerRealityWorker() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Use WorkManager to trigger RealityWorker immediately
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.hyperxray.an.optimizer.RealityWorker>()
                        .build()
                    
                    androidx.work.WorkManager.getInstance(context)
                        .enqueue(workRequest)
                    
                    Log.d(TAG, "Triggered RealityWorker to create policy files")
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering RealityWorker: ${e.message}", e)
                    // If WorkManager fails, still try to load data
                    loadData()
                }
            }
        }
    }
    
    fun resetLearner() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Reset SharedPreferences "ondev_learner"
                    val prefs = context.getSharedPreferences("ondev_learner", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()
                    
                    // Also reset tls_sni_learner_state if it exists
                    val learnerStatePrefs = context.getSharedPreferences("tls_sni_learner_state", Context.MODE_PRIVATE)
                    learnerStatePrefs.edit().clear().apply()
                    
                    Log.d(TAG, "Learner state reset")
                    loadData()
                } catch (e: Exception) {
                    Log.e(TAG, "Error resetting learner", e)
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to reset learner: ${e.message}"
                    )
                }
            }
        }
    }
    
    fun exportReport() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val state = _uiState.value
                    val report = mapOf(
                        "timestamp" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                        "temperature" to state.temperature,
                        "svcBias" to state.svcBias.toList(),
                        "routeBias" to state.routeBias.toList(),
                        "successRate" to state.successRate,
                        "totalFeedback" to state.totalFeedback,
                        "lastUpdated" to state.lastUpdated,
                        "feedbackEntries" to state.recentFeedback.map { it.toMap() },
                        "currentPolicy" to state.currentPolicy.map { it.toMap() },
                        "policyChanges" to state.policyChanges.map { it.toMap() }
                    )
                    
                    val jsonReport = gson.toJson(report)
                    val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
                    downloadsDir.mkdirs()
                    
                    val reportFile = File(downloadsDir, "ai_insights_report_${System.currentTimeMillis()}.json")
                    reportFile.writeText(jsonReport)
                    
                    _uiState.value = _uiState.value.copy(
                        exportMessage = "Report exported to: ${reportFile.absolutePath}"
                    )
                    
                    Log.d(TAG, "Report exported to ${reportFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error exporting report", e)
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to export report: ${e.message}"
                    )
                }
            }
        }
    }
    
    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            withContext(Dispatchers.IO) {
                try {
                    // Load from SharedPreferences "ondev_learner"
                    val prefs = context.getSharedPreferences("ondev_learner", Context.MODE_PRIVATE)
                    
                    val temperature = prefs.getFloat("T", 1.0f)
                    val svcBias = FloatArray(8) { i ->
                        prefs.getFloat("sb$i", 0.0f)
                    }
                    val routeBias = FloatArray(3) { i ->
                        prefs.getFloat("rb$i", 0.0f)
                    }
                    
                    // Also try to load from tls_sni_learner_state if ondev_learner is empty
                    if (temperature == 1.0f && svcBias.all { it == 0.0f } && routeBias.all { it == 0.0f }) {
                        val learnerStatePrefs = context.getSharedPreferences("tls_sni_learner_state", Context.MODE_PRIVATE)
                        val tempFromLearner = learnerStatePrefs.getFloat("temperature", 1.0f)
                        val hasData = learnerStatePrefs.contains("temperature") || 
                                     learnerStatePrefs.contains("svc_bias_0") ||
                                     learnerStatePrefs.contains("successCount")
                        if (tempFromLearner != 1.0f || hasData) {
                            val temp = learnerStatePrefs.getFloat("temperature", 1.0f)
                            val svc = FloatArray(8) { i ->
                                learnerStatePrefs.getFloat("svc_bias_$i", 0.0f)
                            }
                            val route = FloatArray(3) { i ->
                                learnerStatePrefs.getFloat("route_bias_$i", 0.0f)
                            }
                            val successCount = learnerStatePrefs.getInt("successCount", 0)
                            val failCount = learnerStatePrefs.getInt("failCount", 0)
                            
                            _uiState.value = _uiState.value.copy(
                                temperature = temp,
                                svcBias = svc,
                                routeBias = route,
                                totalFeedback = successCount + failCount,
                                successRate = if (successCount + failCount > 0) {
                                    successCount.toFloat() / (successCount + failCount)
                                } else 0.5f
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                temperature = temperature,
                                svcBias = svcBias,
                                routeBias = routeBias
                            )
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            temperature = temperature,
                            svcBias = svcBias,
                            routeBias = routeBias
                        )
                    }
                    
                    // Load learner_log.jsonl
                    val feedbackEntries = loadFeedbackLog()
                    
                    // Load xray_reality_policy files
                    val (currentPolicy, policyChanges) = loadRealityPolicy()
                    
                    // Calculate success rate from feedback if available
                    val feedbackSuccessRate = if (feedbackEntries.isNotEmpty()) {
                        val successCount = feedbackEntries.count { it.success }
                        successCount.toFloat() / feedbackEntries.size
                    } else {
                        _uiState.value.successRate
                    }
                    
                    val lastUpdated = if (feedbackEntries.isNotEmpty()) {
                        feedbackEntries.maxOfOrNull { it.timestamp } ?: ""
                    } else {
                        ""
                    }
                    
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        recentFeedback = feedbackEntries,
                        currentPolicy = currentPolicy,
                        policyChanges = policyChanges,
                        successRate = feedbackSuccessRate,
                        totalFeedback = feedbackEntries.size,
                        lastUpdated = lastUpdated
                    )
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading AI insights data", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Failed to load data: ${e.message}"
                    )
                }
            }
        }
    }
    
    private fun loadFeedbackLog(): List<FeedbackEntry> {
        val logFile = File(filesDir, "learner_log.jsonl")
        if (!logFile.exists()) {
            Log.d(TAG, "learner_log.jsonl not found")
            return emptyList()
        }
        
        return try {
            val fileSize = logFile.length()
            Log.d(TAG, "Loading feedback log: size=${fileSize} bytes (${fileSize / 1024 / 1024}MB)")
            
            // For large files, read efficiently without loading everything into memory
            val lines = if (fileSize > 100 * 1024 * 1024) { // If file > 100MB, use buffered reading
                readLastLinesBuffered(logFile, MAX_ENTRIES_TO_LOAD)
            } else {
                logFile.readLines().takeLast(MAX_ENTRIES_TO_LOAD)
            }
            
            Log.d(TAG, "Loaded ${lines.size} entries from feedback log")
            
            lines.mapNotNull { line ->
                    try {
                        val json = gson.fromJson(line, Map::class.java)
                        // Parse timestamp - can be Long (millis) or String
                        val timestampValue = json["timestamp"]
                        val timestampStr = when (timestampValue) {
                            is Number -> {
                                // Convert Long timestamp to string
                                val date = Date(timestampValue.toLong())
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
                            }
                            is String -> timestampValue
                            else -> ""
                        }
                        
                        FeedbackEntry(
                            sni = json["sni"] as? String ?: "",
                            latency = ((json["latencyMs"] as? Number)?.toLong() 
                                ?: (json["latency"] as? Number)?.toLong() 
                                ?: 0L),
                            throughput = ((json["throughputKbps"] as? Number)?.toFloat()
                                ?: (json["throughput"] as? Number)?.toFloat()
                                ?: 0f),
                            success = (json["success"] as? Boolean) ?: false,
                            timestamp = timestampStr,
                            svcClass = (json["svcClass"] as? Number)?.toInt() ?: 7,
                            routeDecision = (json["routeDecision"] as? Number)?.toInt() ?: 0,
                            alpn = (json["alpn"] as? String) ?: "h2",
                            rtt = (json["rtt"] as? Number)?.toDouble(),
                            jitter = (json["jitter"] as? Number)?.toDouble(),
                            networkType = (json["networkType"] as? String)?.takeIf { it.isNotEmpty() },
                            hourOfDay = (json["hourOfDay"] as? Number)?.toInt(),
                            dayOfWeek = (json["dayOfWeek"] as? Number)?.toInt()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse feedback line: $line", e)
                        null
                    }
                }
                .reversed() // Most recent first
        } catch (e: Exception) {
            Log.e(TAG, "Error reading learner_log.jsonl", e)
            emptyList()
        }
    }
    
    private fun loadRealityPolicy(): Pair<List<PolicyEntry>, List<PolicyChange>> {
        // Find all xray_reality_policy_v*.json files
        val policyFilesList = try {
            filesDir.listFiles { _, name ->
                name.startsWith("xray_reality_policy_v") && name.endsWith(".json")
            }?.sortedBy { it.name }?.toList() ?: emptyList<File>()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing policy files: ${e.message}", e)
            emptyList<File>()
        }
        
        if (policyFilesList.isEmpty()) {
            Log.d(TAG, "No xray_reality_policy files found in ${filesDir.absolutePath}")
            // List all files for debugging
            try {
                val allFiles = filesDir.listFiles()?.map { it.name }?.take(20) ?: emptyList()
                Log.d(TAG, "Files in directory: ${allFiles.joinToString(", ")}")
            } catch (e: Exception) {
                Log.e(TAG, "Error listing directory: ${e.message}", e)
            }
            return Pair(emptyList(), emptyList())
        }
        
        Log.d(TAG, "Found ${policyFilesList.size} policy files: ${policyFilesList.map { it.name }.joinToString(", ")}")
        
        val latestFile = policyFilesList.last()
        val previousFile = if (policyFilesList.size > 1) policyFilesList[policyFilesList.size - 2] else null
        
        Log.d(TAG, "Loading latest policy file: ${latestFile.name}")
        
        val currentPolicy = try {
            val json = gson.fromJson(FileReader(latestFile.absolutePath), Map::class.java)
            val policyArray = json["policy"] as? List<Map<*, *>> ?: emptyList()
            val parsedEntries = policyArray.mapNotNull { entry ->
                try {
                    PolicyEntry(
                        sni = entry["sni"] as? String ?: "",
                        svcClass = (entry["svc_class"] as? Number)?.toInt() ?: 0,
                        routeDecision = (entry["route_decision"] as? Number)?.toInt() ?: 0,
                        alpn = entry["alpn"] as? String ?: "",
                        timestamp = entry["timestamp"] as? String ?: ""
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse policy entry", e)
                    null
                }
            }
            
            // Log route decision distribution for debugging
            val route2Count = parsedEntries.count { it.routeDecision == 2 }
            val route1Count = parsedEntries.count { it.routeDecision == 1 }
            val route0Count = parsedEntries.count { it.routeDecision == 0 }
            Log.d(TAG, "Policy entries: route_2=$route2Count, route_1=$route1Count, route_0=$route0Count, total=${parsedEntries.size}")
            
            // Return entries in original order (no sorting)
            parsedEntries
        } catch (e: Exception) {
            Log.e(TAG, "Error reading latest policy file", e)
            emptyList()
        }
        
        val changes = if (previousFile != null) {
            try {
                val prevJson = gson.fromJson(FileReader(previousFile.absolutePath), Map::class.java)
                val prevPolicyArray = prevJson["policy"] as? List<Map<*, *>> ?: emptyList()
                val prevPolicyMap = prevPolicyArray.associateBy { it["sni"] as? String ?: "" }
                
                currentPolicy.mapNotNull { current ->
                    val prev = prevPolicyMap[current.sni]
                    if (prev != null) {
                        val prevRoute = (prev["route_decision"] as? Number)?.toInt() ?: 0
                        val prevAlpn = prev["alpn"] as? String ?: ""
                        
                        if (current.routeDecision != prevRoute || current.alpn != prevAlpn) {
                            PolicyChange(
                                sni = current.sni,
                                oldRoute = prevRoute,
                                newRoute = current.routeDecision,
                                oldAlpn = prevAlpn,
                                newAlpn = current.alpn,
                                timestamp = current.timestamp
                            )
                        } else null
                    } else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error comparing policy files", e)
                emptyList()
            }
        } else {
            emptyList()
        }
        
        return Pair(currentPolicy, changes)
    }
    
    /**
     * Read last N lines from a file efficiently for large files.
     * Uses sliding window (ArrayDeque) to avoid loading entire file into memory.
     * Optimized for files up to 1GB (1M+ entries).
     */
    private fun readLastLinesBuffered(file: File, maxLines: Int): List<String> {
        return try {
            val fileSize = file.length()
            // Use sliding window for efficient reading of large files
            // ArrayDeque provides O(1) add/remove operations
            file.bufferedReader().use { reader ->
                val slidingWindow = ArrayDeque<String>(maxLines)
                reader.forEachLine { line ->
                    slidingWindow.addLast(line)
                    if (slidingWindow.size > maxLines) {
                        slidingWindow.removeFirst()
                    }
                }
                slidingWindow.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file buffered: ${e.message}", e)
            emptyList()
        }
    }
}

/**
 * UI State for AI Insights Dashboard.
 */
data class AiInsightsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val exportMessage: String? = null,
    val temperature: Float = 1.0f,
    val svcBias: FloatArray = FloatArray(8),
    val routeBias: FloatArray = FloatArray(3),
    val successRate: Float = 0.5f,
    val totalFeedback: Int = 0,
    val lastUpdated: String = "",
    val recentFeedback: List<FeedbackEntry> = emptyList(),
    val currentPolicy: List<PolicyEntry> = emptyList(),
    val policyChanges: List<PolicyChange> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as AiInsightsUiState
        
        if (isLoading != other.isLoading) return false
        if (error != other.error) return false
        if (exportMessage != other.exportMessage) return false
        if (temperature != other.temperature) return false
        if (!svcBias.contentEquals(other.svcBias)) return false
        if (!routeBias.contentEquals(other.routeBias)) return false
        if (successRate != other.successRate) return false
        if (totalFeedback != other.totalFeedback) return false
        if (lastUpdated != other.lastUpdated) return false
        if (recentFeedback != other.recentFeedback) return false
        if (currentPolicy != other.currentPolicy) return false
        if (policyChanges != other.policyChanges) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = isLoading.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (exportMessage?.hashCode() ?: 0)
        result = 31 * result + temperature.hashCode()
        result = 31 * result + svcBias.contentHashCode()
        result = 31 * result + routeBias.contentHashCode()
        result = 31 * result + successRate.hashCode()
        result = 31 * result + totalFeedback
        result = 31 * result + lastUpdated.hashCode()
        result = 31 * result + recentFeedback.hashCode()
        result = 31 * result + currentPolicy.hashCode()
        result = 31 * result + policyChanges.hashCode()
        return result
    }
}

/**
 * Feedback entry from learner_log.jsonl.
 */
data class FeedbackEntry(
    val sni: String,
    val latency: Long,
    val throughput: Float,
    val success: Boolean,
    val timestamp: String,
    val svcClass: Int = 7,
    val routeDecision: Int = 0,
    val alpn: String = "h2",
    val rtt: Double? = null,
    val jitter: Double? = null,
    val networkType: String? = null,
    val hourOfDay: Int? = null,
    val dayOfWeek: Int? = null
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sni" to sni,
        "latency" to latency,
        "throughput" to throughput,
        "success" to success,
        "timestamp" to timestamp,
        "svcClass" to svcClass,
        "routeDecision" to routeDecision,
        "alpn" to alpn
    ).plus(
        listOfNotNull(
            rtt?.let { "rtt" to it },
            jitter?.let { "jitter" to it },
            networkType?.let { "networkType" to it },
            hourOfDay?.let { "hourOfDay" to it },
            dayOfWeek?.let { "dayOfWeek" to it }
        )
    )
}

/**
 * Policy entry from xray_reality_policy_v*.json.
 */
data class PolicyEntry(
    val sni: String,
    val svcClass: Int,
    val routeDecision: Int,
    val alpn: String,
    val timestamp: String
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sni" to sni,
        "svc_class" to svcClass,
        "route_decision" to routeDecision,
        "alpn" to alpn,
        "timestamp" to timestamp
    )
}

/**
 * Policy change detected between versions.
 */
data class PolicyChange(
    val sni: String,
    val oldRoute: Int,
    val newRoute: Int,
    val oldAlpn: String,
    val newAlpn: String,
    val timestamp: String
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sni" to sni,
        "old_route" to oldRoute,
        "new_route" to newRoute,
        "old_alpn" to oldAlpn,
        "new_alpn" to newAlpn,
        "timestamp" to timestamp
    )
}

