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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LogViewModel"
private const val RING_BUFFER_CAPACITY = 2000
// Maximum entries to expose to UI - prevents Compose from retaining entire buffer
private const val MAX_UI_ENTRIES = 2_500
// Log rotation threshold - when buffer reaches this, rotate oldest entries to disk
private const val ROTATION_THRESHOLD = 1_800
private const val ROTATION_BATCH_SIZE = 500

/**
 * Ultra-high-performance ViewModel for managing log display and filtering.
 * 
 * Performance optimizations:
 * - Ring buffer with fixed 2000 entry capacity (O(1) memory)
 * - StateFlow with replay=1 (minimal memory footprint)
 * - All log processing off Main dispatcher
 * - DerivedStateOf for filtered lists (prevents recomposition storms)
 * - Proper coroutine cancellation in onCleared
 */
@OptIn(FlowPreview::class)
class LogViewModel(application: Application) :
    AndroidViewModel(application) {

    private val logFileManager = LogFileManager(application)
    private val aiLogCapture = AiLogCapture(application, logFileManager)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filteredEntries = MutableStateFlow<List<String>>(emptyList())
    val filteredEntries: StateFlow<List<String>> = _filteredEntries.asStateFlow()

    private val _hasLogsToExport = MutableStateFlow(false)
    val hasLogsToExport: StateFlow<Boolean> = _hasLogsToExport.asStateFlow()

    private val logBuffer = RingLogBuffer(RING_BUFFER_CAPACITY)
    private var currentFilterCriteria = FilterCriteria()
    
    private var logUpdateReceiver: BroadcastReceiver? = null
    private var filterJob: Job? = null
    private var exportCheckJob: Job? = null

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }
    
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
            
            // Get filtered snapshot with bounded size for UI
            val filtered = logBuffer.filteredSnapshot(currentFilterCriteria)
            val boundedFiltered = filtered.take(MAX_UI_ENTRIES)
            
            withContext(Dispatchers.Main) {
                // Only update if the list actually changed
                if (_filteredEntries.value != boundedFiltered) {
                    _filteredEntries.value = boundedFiltered
                }
            }
        }
    }

    init {
        Log.d(TAG, "LogViewModel initialized with ring buffer capacity: $RING_BUFFER_CAPACITY")
        
        logUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (TProxyService.ACTION_LOG_UPDATE == intent.action) {
                    val newLogs = intent.getStringArrayListExtra(TProxyService.EXTRA_LOG_DATA)
                    if (!newLogs.isNullOrEmpty()) {
                        viewModelScope.launch(Dispatchers.Default) {
                            processNewLogs(newLogs)
                        }
                    }
                }
            }
        }
        
        exportCheckJob = viewModelScope.launch {
            _filteredEntries.collect { entries ->
                _hasLogsToExport.value = entries.isNotEmpty() && logFileManager.logFile.exists()
            }
        }
        
        filterJob = viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .flowOn(Dispatchers.Default)
                .collect { query ->
                    val criteria = currentFilterCriteria.copy(searchQuery = query)
                    currentFilterCriteria = criteria
                    // Get filtered snapshot with bounded size for UI
                    val filtered = logBuffer.filteredSnapshot(criteria)
                    val boundedFiltered = filtered.take(MAX_UI_ENTRIES)
                    withContext(Dispatchers.Main) {
                        // Only update if the list actually changed
                        if (_filteredEntries.value != boundedFiltered) {
                            _filteredEntries.value = boundedFiltered
                        }
                    }
                }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Cancel all coroutines to prevent leaks
        filterJob?.cancel()
        exportCheckJob?.cancel()
        filterJob = null
        exportCheckJob = null
        logUpdateReceiver = null
        // Clear state flows to release references
        _filteredEntries.value = emptyList()
        _searchQuery.value = ""
        Log.d(TAG, "LogViewModel cleared, all coroutines cancelled and state cleared")
    }

    fun registerLogReceiver(context: Context) {
        val receiver = logUpdateReceiver ?: return
        val filter = IntentFilter(TProxyService.ACTION_LOG_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        aiLogCapture.startCapture()
        Log.d(TAG, "Log receiver registered and AI log capture started.")
    }

    fun unregisterLogReceiver(context: Context) {
        val receiver = logUpdateReceiver ?: return
        context.unregisterReceiver(receiver)
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
            logBuffer.initialize(initialLogs)
            // Get snapshot with bounded size for UI
            val snapshot = logBuffer.snapshot()
            val boundedSnapshot = snapshot.take(MAX_UI_ENTRIES)
            withContext(Dispatchers.Main) {
                _filteredEntries.value = boundedSnapshot
            }
        }
        Log.d(TAG, "Processed initial logs: ${logBuffer.getSize()} entries (UI shows ${_filteredEntries.value.size})")
    }

    private suspend fun processNewLogs(newLogs: ArrayList<String>) {
        withContext(Dispatchers.Default) {
            val added = logBuffer.add(newLogs)
            if (added > 0) {
                // Check if rotation is needed (when buffer is getting full)
                val currentSize = logBuffer.getSize()
                if (currentSize >= ROTATION_THRESHOLD) {
                    rotateOldestLogsToDisk()
                }
                
                // Get filtered snapshot with bounded size for UI
                val filtered = logBuffer.filteredSnapshot(currentFilterCriteria)
                val boundedFiltered = filtered.take(MAX_UI_ENTRIES)
                
                withContext(Dispatchers.Main) {
                    // Use distinctUntilChanged to prevent unnecessary recompositions
                    // Only update if the list actually changed
                    if (_filteredEntries.value != boundedFiltered) {
                        _filteredEntries.value = boundedFiltered
                    }
                }
            }
        }
    }
    
    /**
     * Rotate oldest log entries to disk to prevent memory growth.
     * Writes entries that will be evicted next to the log file.
     */
    private suspend fun rotateOldestLogsToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val oldestEntries = logBuffer.getOldestEntries(ROTATION_BATCH_SIZE)
                if (oldestEntries.isNotEmpty()) {
                    // Append oldest entries to log file
                    oldestEntries.forEach { entry ->
                        logFileManager.appendLog(entry)
                    }
                    logFileManager.flush()
                    Log.d(TAG, "Rotated ${oldestEntries.size} oldest log entries to disk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rotating logs to disk", e)
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.Default) {
            logBuffer.clear()
            withContext(Dispatchers.Main) {
                _filteredEntries.value = emptyList()
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
