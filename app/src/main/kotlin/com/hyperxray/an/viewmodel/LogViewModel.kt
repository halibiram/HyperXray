package com.hyperxray.an.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.data.source.AiLogCapture
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.ui.screens.log.ConnectionType
import com.hyperxray.an.ui.screens.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

private const val TAG = "LogViewModel"

/**
 * ViewModel for managing log display and filtering.
 * Handles log updates from TProxyService broadcasts and provides search functionality.
 */
@OptIn(FlowPreview::class)
class LogViewModel(application: Application) :
    AndroidViewModel(application) {

    private val logFileManager = LogFileManager(application)
    private val aiLogCapture = AiLogCapture(application, logFileManager)

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filteredEntries = MutableStateFlow<List<String>>(emptyList())
    val filteredEntries: StateFlow<List<String>> = _filteredEntries.asStateFlow()
    
    // Current filter criteria for index-based filtering
    private var currentFilterCriteria = FilterCriteria()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }
    
    /**
     * Update filter criteria for index-based filtering.
     * Called from UI when filters change.
     */
    fun updateFilters(
        level: LogLevel?,
        type: ConnectionType?,
        sniffingOnly: Boolean,
        aiOnly: Boolean
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            currentFilterCriteria = FilterCriteria(
                level = level?.ordinal,
                type = type?.ordinal,
                sniffingOnly = sniffingOnly,
                aiOnly = aiOnly,
                searchQuery = _searchQuery.value
            )
            
            // Trigger filtered entries update
            val filtered = logBuffer.getFiltered(currentFilterCriteria)
            withContext(Dispatchers.Main) {
                _filteredEntries.value = filtered
            }
        }
    }

    private val _hasLogsToExport = MutableStateFlow(false)
    val hasLogsToExport: StateFlow<Boolean> = _hasLogsToExport.asStateFlow()

    // Mega-scale buffer optimized for 100K+ log entries
    // Uses windowed storage (5K active window) + index-based filtering
    private val MAX_LOG_ENTRIES = 100000 // Support 100K entries
    private val WINDOW_SIZE = 5000 // Keep 5K in active memory window
    
    // Mega-scale buffer with windowed storage and index-based filtering
    // - Windowed/paginated storage (only active window in memory)
    // - Index-based filtering (O(1) filter lookups)
    // - Lazy metadata computation
    // - Memory-efficient string pooling
    private val logBuffer = MegaLogBuffer(MAX_LOG_ENTRIES, WINDOW_SIZE)

    private var logUpdateReceiver: BroadcastReceiver

    init {
        Log.d(TAG, "LogViewModel initialized.")
        logUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (TProxyService.ACTION_LOG_UPDATE == intent.action) {
                    val newLogs = intent.getStringArrayListExtra(TProxyService.EXTRA_LOG_DATA)
                    if (!newLogs.isNullOrEmpty()) {
                        Log.d(TAG, "Received log update broadcast with ${newLogs.size} entries.")
                        viewModelScope.launch {
                            processNewLogs(newLogs)
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Received log update broadcast, but log data list is null or empty."
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            logEntries.collect { entries ->
                _hasLogsToExport.value = entries.isNotEmpty() && logFileManager.logFile.exists()
            }
        }
        // Index-based filtering with debounce for ultra-fast performance
        viewModelScope.launch {
            combine(
                logEntries,
                searchQuery.debounce(500) // Longer debounce for 100K entries
            ) { logs, query ->
                // Update filter criteria
                currentFilterCriteria = currentFilterCriteria.copy(searchQuery = query)
                
                // Use index-based filtering from MegaLogBuffer (ultra-fast)
                withContext(Dispatchers.Default) {
                    logBuffer.getFiltered(currentFilterCriteria)
                }
            }
                .flowOn(Dispatchers.Default)
                .collect { _filteredEntries.value = it }
        }
    }

    fun registerLogReceiver(context: Context) {
        val filter = IntentFilter(TProxyService.ACTION_LOG_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(logUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(logUpdateReceiver, filter)
        }
        // Start capturing AI logs
        aiLogCapture.startCapture()
        Log.d(TAG, "Log receiver registered and AI log capture started.")
    }

    fun unregisterLogReceiver(context: Context) {
        context.unregisterReceiver(logUpdateReceiver)
        // Stop capturing AI logs
        aiLogCapture.stopCapture()
        Log.d(TAG, "Log receiver unregistered and AI log capture stopped.")
    }

    fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading logs.")
            val savedLogData = logFileManager.readLogs()
            val initialLogs = if (!savedLogData.isNullOrEmpty()) {
                savedLogData.split("\n").filter { it.trim().isNotEmpty() }
            } else {
                emptyList()
            }
            processInitialLogs(initialLogs)
        }
    }

    private suspend fun processInitialLogs(initialLogs: List<String>) {
        withContext(Dispatchers.Default) {
            // FastLogBuffer handles locking internally
            logBuffer.initialize(initialLogs)
            // Single StateFlow update after initialization
            withContext(Dispatchers.Main) {
                _logEntries.value = logBuffer.toList()
            }
        }
        Log.d(TAG, "Processed initial logs: ${logBuffer.getSize()} unique entries.")
    }

    private suspend fun processNewLogs(newLogs: ArrayList<String>) {
        // Process on background thread using ultra-fast incremental update
        // FastLogBuffer uses ReadWriteLock internally, so we don't need external mutex
        val uniqueNewLogs = withContext(Dispatchers.Default) {
            // FastLogBuffer handles locking internally for better concurrency
            logBuffer.addIncremental(newLogs)
        }
        
        if (uniqueNewLogs.isNotEmpty()) {
            // Get cached list from buffer (cached for performance)
            // Only update StateFlow if there are actually new entries
            withContext(Dispatchers.Default) {
                // Get fresh list (uses cache if available)
                val updatedList = logBuffer.toList()
                
                // Update StateFlow on Main thread (single update per batch)
                withContext(Dispatchers.Main) {
                    _logEntries.value = updatedList
                }
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                // FastLogBuffer handles locking internally
                logBuffer.clear()
                withContext(Dispatchers.Main) {
                    _logEntries.value = emptyList()
                }
            }
            Log.d(TAG, "Logs cleared.")
        }
    }

    fun getLogFile(): File {
        return logFileManager.logFile
    }
}

/**
 * Factory for creating LogViewModel instances.
 */
class LogViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LogViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
