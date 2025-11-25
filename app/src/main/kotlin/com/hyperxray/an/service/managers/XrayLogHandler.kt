package com.hyperxray.an.service.managers

import android.content.Context
import android.content.Intent
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.service.utils.TProxyUtils
import com.hyperxray.an.service.utils.TProxyUtils.UdpErrorRecord
import com.hyperxray.an.service.utils.TProxyUtils.UdpErrorPattern
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException

/**
 * Handles Xray process log reading, parsing, and broadcasting.
 * Manages stdout/stderr stream reading, log file writing, and UI broadcasting.
 * 
 * All business logic (SNI processing, UDP error handling, etc.) is handled via callbacks
 * to keep the handler focused on I/O and parsing.
 */
class XrayLogHandler(
    private val serviceScope: CoroutineScope,
    private val context: Context
) {
    companion object {
        private const val TAG = "XrayLogHandler"
        private const val BROADCAST_DELAY_MS: Long = 1000 // 1 second delay for larger batches
        private const val BROADCAST_BUFFER_SIZE_THRESHOLD: Int = 100 // Larger batches (100 entries)
        private const val READ_TIMEOUT_MS = 10000L // 10 seconds without any read
        private const val HEALTH_CHECK_INITIAL_DELAY_MS = 5000L // Wait 5 seconds before first health check
        private const val HEALTH_CHECK_INTERVAL_MS = 2000L // Check every 2 seconds
    }

    /**
     * Listener interface for log processing events.
     * All business logic is handled via these callbacks.
     */
    interface LogProcessingListener {
        /**
         * Called when Xray startup is detected in logs.
         */
        fun onXrayStarted()

        /**
         * Called when a UDP error is detected.
         * @param errorRecord The detected error record
         * @param pattern The error pattern analysis
         * @param updateLastErrorTime Callback to update the last error time
         * @param updateErrorCount Callback to update the error count
         */
        fun onUdpErrorDetected(
            errorRecord: UdpErrorRecord,
            pattern: UdpErrorPattern,
            updateLastErrorTime: (Long) -> Unit,
            updateErrorCount: (Int) -> Unit
        )

        /**
         * Called when a connection reset error is detected.
         * @param newCount Updated connection reset error count
         * @param newTime Updated last connection reset time
         * @param updateCount Callback to update the count
         * @param updateTime Callback to update the time
         */
        fun onConnectionResetErrorDetected(
            newCount: Int,
            newTime: Long,
            updateCount: (Int) -> Unit,
            updateTime: (Long) -> Unit
        )

        /**
         * Called when SNI is detected in a log line.
         * @param sni The detected SNI
         * @param logEntry The full log entry
         */
        fun onSniDetected(sni: String, logEntry: String)
    }

    // Lock-free queue for log broadcasts
    private val logBroadcastChannel = Channel<String>(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    /**
     * Get the log broadcast channel for sending log lines to UI.
     */
    fun getLogBroadcastChannel(): Channel<String> = logBroadcastChannel
    private val broadcastBuffer = mutableListOf<String>()
    private val reusableBroadcastList = ArrayList<String>(100)

    private var readJob: Job? = null
    private var stderrJob: Job? = null
    private var healthCheckJob: Job? = null
    private var broadcastJob: Job? = null

    // Listener for log processing events
    var listener: LogProcessingListener? = null

    // State tracking for UDP errors (managed by listener callbacks)
    var lastUdpErrorTime: Long = 0L
        private set
    var udpErrorCount: Int = 0
        private set

    // State tracking for connection reset errors (managed by listener callbacks)
    var connectionResetErrorCount: Int = 0
        private set
    var lastConnectionResetTime: Long = 0L
        private set

    // Callbacks for state management
    var isStopping: () -> Boolean = { false }
    var serviceStartTime: Long = 0L
    var udpErrorHistory: MutableList<UdpErrorRecord> = mutableListOf()
    var maxErrorHistorySize: Int = 100
    var connectionResetThreshold: Int = 5
    var connectionResetWindowMs: Long = 60000L

    var logFileManager: LogFileManager? = null

    init {
        // Start background broadcaster coroutine
        startBroadcastCoroutine()
    }

    /**
     * Start reading logs from Process streams.
     * 
     * @param process Xray process to observe
     * @param logFileManager Log file manager for writing logs
     */
    fun startReading(process: Process, logFileManager: LogFileManager) {
        if (readJob?.isActive == true) {
            Log.w(TAG, "Already reading from a process, stopping previous reading")
            stopObserving()
        }

        this.logFileManager = logFileManager
        Log.d(TAG, "Starting log reading for Xray process")
        serviceScope.launch {
            readProcessStreamWithTimeout(process, logFileManager)
        }
    }

    /**
     * Start reading logs from InputStream (alternative to Process).
     * 
     * @param inputStream InputStream to read from
     * @param logFileManager Log file manager for writing logs
     */
    fun startReading(inputStream: InputStream, logFileManager: LogFileManager) {
        if (readJob?.isActive == true) {
            Log.w(TAG, "Already reading from a stream, stopping previous reading")
            stopObserving()
        }

        this.logFileManager = logFileManager
        Log.d(TAG, "Starting log reading from InputStream")
        readInputStreamWithTimeout(inputStream, logFileManager)
    }

    /**
     * Initialize handler for callback-based log processing.
     * Use this when logs come via LogLineCallback instead of Process streams.
     * 
     * @param logFileManager Log file manager for writing logs
     */
    fun initialize(logFileManager: LogFileManager) {
        this.logFileManager = logFileManager
        Log.d(TAG, "XrayLogHandler initialized for callback-based log processing")
    }

    /**
     * Process a log line (for callback-based log processing).
     * Parses the line for SNI, UDP errors, connection resets, and Xray startup.
     * 
     * @param line Log line to process
     */
    fun processLogLine(line: String) {
        logFileManager?.appendLog(line)
        logBroadcastChannel.trySend(line)

        // Check if Xray has started
        if (line.contains("started", ignoreCase = true) &&
            line.contains("Xray", ignoreCase = true)) {
            Log.d(TAG, "Detected Xray startup in logs")
            AiLogHelper.i(TAG, "✅ XRAY LOG: Detected Xray startup in logs")
            listener?.onXrayStarted()
        }

        // Detect UDP closed pipe errors
        val oldLastUdpErrorTime = lastUdpErrorTime
        val newLastUdpErrorTime = TProxyUtils.detectUdpClosedPipeErrors(
            logEntry = line,
            isStopping = isStopping(),
            serviceStartTime = serviceStartTime,
            lastUdpErrorTime = lastUdpErrorTime,
            udpErrorCount = udpErrorCount,
            udpErrorHistory = udpErrorHistory,
            maxErrorHistorySize = maxErrorHistorySize,
            onErrorDetected = { errorRecord, pattern ->
                listener?.onUdpErrorDetected(
                    errorRecord = errorRecord,
                    pattern = pattern,
                    updateLastErrorTime = { time -> lastUdpErrorTime = time },
                    updateErrorCount = { count -> udpErrorCount = count }
                )
            }
        )
        if (newLastUdpErrorTime != oldLastUdpErrorTime) {
            lastUdpErrorTime = newLastUdpErrorTime
            val timeSinceLastError = if (oldLastUdpErrorTime > 0) newLastUdpErrorTime - oldLastUdpErrorTime else Long.MAX_VALUE
            if (timeSinceLastError < 12000) {
                udpErrorCount++
            } else {
                udpErrorCount = 1
            }
        }

        // Detect connection reset errors
        val (newCount, newTime) = TProxyUtils.detectConnectionResetErrors(
            context = context,
            logEntry = line,
            connectionResetErrorCount = connectionResetErrorCount,
            lastConnectionResetTime = lastConnectionResetTime,
            connectionResetThreshold = connectionResetThreshold,
            connectionResetWindowMs = connectionResetWindowMs,
            serviceScope = serviceScope,
            onThresholdExceeded = { count, time ->
                listener?.onConnectionResetErrorDetected(
                    newCount = count,
                    newTime = time,
                    updateCount = { connectionResetErrorCount = it },
                    updateTime = { lastConnectionResetTime = it }
                )
            }
        )
        connectionResetErrorCount = newCount
        lastConnectionResetTime = newTime

        // Process SNI for TLS optimization
        val sni = try {
            com.hyperxray.an.ui.screens.log.extractSNI(line)
        } catch (e: Exception) {
            null
        }
        if (sni != null && sni.isNotEmpty()) {
            listener?.onSniDetected(sni, line)
        }
    }

    /**
     * Stop observing logs.
     */
    fun stopObserving() {
        Log.d(TAG, "Stopping log observation")
        AiLogHelper.i(TAG, "🛑 XRAY LOG HANDLER: Stopping log observation")
        readJob?.cancel()
        stderrJob?.cancel()
        healthCheckJob?.cancel()
        readJob = null
        stderrJob = null
        healthCheckJob = null
        AiLogHelper.i(TAG, "✅ XRAY LOG HANDLER: Log observation stopped")
    }

    /**
     * Reads process output stream with timeout protection and health checks.
     * Prevents thread hangs when process dies but stream remains open.
     * Uses coroutines for proper cancellation and resource management.
     * Also reads stderr stream in parallel to capture error messages.
     */
    private suspend fun readProcessStreamWithTimeout(process: Process, logFileManager: LogFileManager) {
        val readStartTime = System.currentTimeMillis()
        val processId = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val pidMethod = process.javaClass.getMethod("pid")
                pidMethod.invoke(process) as? Long ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
        AiLogHelper.i(TAG, "📖 XRAY LOG HANDLER: Starting process stream reading (PID: $processId)")
        
        try {
            // Start reading stderr in parallel to capture error messages immediately
            AiLogHelper.d(TAG, "🔧 XRAY LOG HANDLER: Starting stderr reader...")
            stderrJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                        while (isActive && process.isAlive && !isStopping()) {
                            try {
                                if (!errorReader.ready()) {
                                    delay(100)
                                    ensureActive()
                                    continue
                                }
                                val line = errorReader.readLine()
                                if (line == null) {
                                    delay(100)
                                    continue
                                }
                                // Log stderr output prominently
                                Log.e(TAG, "Xray stderr: $line")
                                // Also broadcast as log entry
                                logFileManager.appendLog("STDERR: $line")
                                logBroadcastChannel.trySend("STDERR: $line")
                            } catch (e: IOException) {
                                if (process.isAlive && !isStopping()) {
                                    delay(100)
                                    continue
                                } else {
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Error reading stderr: ${e.message}")
                    }
                }
            }

            // Health check coroutine monitors process and cancels read job if needed
            AiLogHelper.d(TAG, "🔧 XRAY LOG HANDLER: Starting health check coroutine...")
            healthCheckJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    delay(HEALTH_CHECK_INITIAL_DELAY_MS)
                    while (isActive) {
                        delay(HEALTH_CHECK_INTERVAL_MS)
                        ensureActive()

                        // Check if we're stopping
                        if (isStopping()) {
                            Log.d(TAG, "Health check detected stop request, cancelling read job.")
                            AiLogHelper.i(TAG, "🛑 XRAY LOG HANDLER: Health check detected stop request, cancelling read job")
                            readJob?.cancel()
                            stderrJob?.cancel()
                            break
                        }

                        // Check if process is still alive
                        if (!process.isAlive) {
                            val exitValue = try {
                                process.exitValue()
                            } catch (e: IllegalThreadStateException) {
                                -1
                            }
                            Log.d(TAG, "Health check detected process death (exit code: $exitValue), cancelling read job.")
                            AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Health check detected process death (exit code: $exitValue), cancelling read job")
                            readJob?.cancel()
                            stderrJob?.cancel()

                            // Try to read remaining stderr output after process death
                            try {
                                delay(500) // Give stderr reader time to capture final output
                                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                                var line = errorReader.readLine()
                                var lineCount = 0
                                while (line != null && lineCount < 20 && isActive) {
                                    Log.e(TAG, "Xray stderr (final): $line")
                                    line = errorReader.readLine()
                                    lineCount++
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not read final stderr: ${e.message}")
                            }

                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in health check coroutine: ${e.message}", e)
                    }
                }
            }

            // Stream reading coroutine - can be cancelled by health check
            AiLogHelper.d(TAG, "🔧 XRAY LOG HANDLER: Starting stdout reader coroutine...")
            readJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var lastReadTime = System.currentTimeMillis()
                        var lineCount = 0L

                        while (isActive && !isStopping()) {
                            try {
                                ensureActive()

                                // Check if we've been reading for too long without data
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastReadTime > READ_TIMEOUT_MS) {
                                    // Check if process is still alive
                                    if (!process.isAlive) {
                                        val exitValue = try {
                                            process.exitValue()
                                        } catch (e: IllegalThreadStateException) {
                                            -1
                                        }
                                        Log.d(TAG, "Read timeout detected, process is dead (exit code: $exitValue)")
                                        AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Read timeout detected, process is dead (exit code: $exitValue, lines read: $lineCount)")
                                        break
                                    }
                                    Log.w(TAG, "Read timeout: no data for ${READ_TIMEOUT_MS}ms, but process is alive. Continuing...")
                                    AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Read timeout: no data for ${READ_TIMEOUT_MS}ms, but process is alive. Continuing... (lines read: $lineCount)")
                                    lastReadTime = currentTime
                                }

                                // Check if reader is ready before attempting to read
                                if (!reader.ready()) {
                                    delay(100)
                                    ensureActive()
                                    continue
                                }

                                // Check available bytes to detect if stream is closed
                                try {
                                    process.inputStream.available()
                                } catch (e: IOException) {
                                    Log.d(TAG, "Stream unavailable (likely closed): ${e.message}")
                                    break
                                }

                                // Read line
                                val line = reader.readLine()

                                if (line == null) {
                                    // EOF - stream is closed
                                    Log.d(TAG, "Stream reached EOF (null read)")
                                    break
                                }

                                // Update last read time on successful read
                                lastReadTime = System.currentTimeMillis()

                                // Process the log line (includes parsing and callbacks)
                                processLogLine(line)

                                // Small delay to allow cancellation to be checked
                                delay(10)

                            } catch (e: InterruptedIOException) {
                                Log.d(TAG, "Stream read interrupted: ${e.message}")
                                AiLogHelper.d(TAG, "📖 XRAY LOG HANDLER: Stream read interrupted: ${e.message} (lines read: $lineCount)")
                                break
                            } catch (e: IOException) {
                                // Check if process is still alive
                                if (!process.isAlive) {
                                    val exitValue = try {
                                        process.exitValue()
                                    } catch (ex: IllegalThreadStateException) {
                                        -1
                                    }
                                    Log.d(TAG, "IOException during read, process is dead (exit code: $exitValue): ${e.message}")
                                    AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: IOException during read, process is dead (exit code: $exitValue, lines read: $lineCount): ${e.message}")
                                } else {
                                    Log.e(TAG, "IOException during stream read (process alive): ${e.message}", e)
                                    AiLogHelper.e(TAG, "❌ XRAY LOG HANDLER: IOException during stream read (process alive, lines read: $lineCount): ${e.message}", e)
                                }
                                break
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                Log.d(TAG, "Read coroutine cancelled")
                                AiLogHelper.d(TAG, "📖 XRAY LOG HANDLER: Read coroutine cancelled (lines read: $lineCount)")
                                throw e
                            } catch (e: Exception) {
                                if (isActive) {
                                    Log.e(TAG, "Unexpected error during stream read: ${e.message}", e)
                                    AiLogHelper.e(TAG, "❌ XRAY LOG HANDLER: Unexpected error during stream read (lines read: $lineCount): ${e.message}", e)
                                }
                                break
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Read coroutine cancelled during stream reading")
                    AiLogHelper.d(TAG, "📖 XRAY LOG HANDLER: Read coroutine cancelled during stream reading")
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in read coroutine: ${e.message}", e)
                        AiLogHelper.e(TAG, "❌ XRAY LOG HANDLER: Error in read coroutine: ${e.message}", e)
                    }
                } finally {
                    // Cleanup: close streams
                    AiLogHelper.d(TAG, "🧹 XRAY LOG HANDLER: Cleaning up streams...")
                    try {
                        process.inputStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing process input stream", e)
                        AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Error closing process input stream: ${e.message}")
                    }
                    try {
                        process.errorStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing process error stream", e)
                        AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Error closing process error stream: ${e.message}")
                    }
                    try {
                        process.outputStream?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing process output stream", e)
                        AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Error closing process output stream: ${e.message}")
                    }
                    val readDuration = System.currentTimeMillis() - readStartTime
                    AiLogHelper.i(TAG, "✅ XRAY LOG HANDLER: Stream reading completed (duration: ${readDuration}ms)")
                }
            }

            // Wait for read job to complete (it will finish when stream ends or is cancelled)
            readJob?.join()

            // Read job finished, cancel health check and stderr jobs since they're no longer needed
            healthCheckJob?.cancel()
            stderrJob?.cancel()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting log observation: ${e.message}", e)
            AiLogHelper.e(TAG, "❌ XRAY LOG HANDLER: Error starting log observation: ${e.message}", e)
        } finally {
            // Cancel all jobs and wait for them to finish
            try {
                AiLogHelper.d(TAG, "🧹 XRAY LOG HANDLER: Cancelling all jobs...")
                healthCheckJob?.cancel()
                readJob?.cancel()
                stderrJob?.cancel()
                // Wait for jobs to complete cancellation (with timeout)
                kotlinx.coroutines.withTimeoutOrNull(1000) {
                    healthCheckJob?.join()
                    readJob?.join()
                    stderrJob?.join()
                }
                AiLogHelper.d(TAG, "✅ XRAY LOG HANDLER: All jobs cancelled")
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling stream reading coroutines: ${e.message}", e)
                AiLogHelper.w(TAG, "⚠️ XRAY LOG HANDLER: Error cancelling stream reading coroutines: ${e.message}")
            }

            // Wait before closing streams to allow process to finish writing
            // This prevents "closed pipe" errors when Xray is handling UDP traffic
            delay(200) // Initial delay to let process finish current operations

            // Only close streams if process is dead, or add delay if still alive
            val processAlive = process.isAlive
            if (processAlive) {
                // Process still alive - wait more before closing streams to avoid pipe errors
                delay(300) // Increased to 300ms for better UDP cleanup
            }

            // Now close streams - process should have finished writing by now
            try {
                process.inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing process input stream", e)
            }
            try {
                process.errorStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing process error stream", e)
            }
        }
    }

    /**
     * Reads InputStream with timeout protection.
     * Simplified version for when we only have an InputStream (not a Process).
     */
    private fun readInputStreamWithTimeout(inputStream: InputStream, logFileManager: LogFileManager) {
        try {
            readJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var lastReadTime = System.currentTimeMillis()

                        while (isActive && !isStopping()) {
                            try {
                                ensureActive()

                                // Check if we've been reading for too long without data
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastReadTime > READ_TIMEOUT_MS) {
                                    Log.w(TAG, "Read timeout: no data for ${READ_TIMEOUT_MS}ms")
                                    lastReadTime = currentTime
                                }

                                // Check if reader is ready before attempting to read
                                if (!reader.ready()) {
                                    delay(100)
                                    ensureActive()
                                    continue
                                }

                                // Check available bytes to detect if stream is closed
                                try {
                                    inputStream.available()
                                } catch (e: IOException) {
                                    Log.d(TAG, "Stream unavailable (likely closed): ${e.message}")
                                    break
                                }

                                // Read line
                                val line = reader.readLine()

                                if (line == null) {
                                    // EOF - stream is closed
                                    Log.d(TAG, "Stream reached EOF (null read)")
                                    break
                                }

                                // Update last read time on successful read
                                lastReadTime = System.currentTimeMillis()

                                // Process the log line (includes parsing and callbacks)
                                processLogLine(line)

                                // Small delay to allow cancellation to be checked
                                delay(10)

                            } catch (e: InterruptedIOException) {
                                Log.d(TAG, "Stream read interrupted: ${e.message}")
                                break
                            } catch (e: IOException) {
                                Log.e(TAG, "IOException during stream read: ${e.message}", e)
                                break
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                Log.d(TAG, "Read coroutine cancelled")
                                throw e
                            } catch (e: Exception) {
                                if (isActive) {
                                    Log.e(TAG, "Unexpected error during stream read: ${e.message}", e)
                                }
                                break
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Read coroutine cancelled during stream reading")
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in read coroutine: ${e.message}", e)
                    }
                } finally {
                    // Cleanup: close stream
                    try {
                        inputStream.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing input stream", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream reading: ${e.message}", e)
        }
    }

    /**
     * Start background coroutine for broadcasting logs.
     */
    private fun startBroadcastCoroutine() {
        broadcastJob = serviceScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    try {
                        val logEntry = logBroadcastChannel.receive()
                        broadcastBuffer.add(logEntry)

                        if (broadcastBuffer.size >= BROADCAST_BUFFER_SIZE_THRESHOLD) {
                            broadcastLogsBatch()
                        } else {
                            select<Unit> {
                                logBroadcastChannel.onReceive { newLog ->
                                    broadcastBuffer.add(newLog)
                                    if (broadcastBuffer.size >= BROADCAST_BUFFER_SIZE_THRESHOLD) {
                                        broadcastLogsBatch()
                                    }
                                }
                                onTimeout(BROADCAST_DELAY_MS) {
                                    if (broadcastBuffer.isNotEmpty()) {
                                        broadcastLogsBatch()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in log broadcast coroutine", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error in log broadcast coroutine", e)
                }
            }
        }
    }

    /**
     * Broadcast accumulated logs to UI.
     */
    private fun broadcastLogsBatch() {
        if (broadcastBuffer.isEmpty()) return

        val logUpdateIntent = Intent(TProxyService.ACTION_LOG_UPDATE)
        logUpdateIntent.setPackage(context.packageName)

        // Optimize: reuse list and only resize if needed
        reusableBroadcastList.clear()
        if (broadcastBuffer.size > reusableBroadcastList.size) {
            reusableBroadcastList.ensureCapacity(broadcastBuffer.size)
        }
        reusableBroadcastList.addAll(broadcastBuffer)
        logUpdateIntent.putStringArrayListExtra(TProxyService.EXTRA_LOG_DATA, reusableBroadcastList)
        context.sendBroadcast(logUpdateIntent)
        broadcastBuffer.clear()
        Log.d(TAG, "Broadcasted a batch of logs.")
    }
}

