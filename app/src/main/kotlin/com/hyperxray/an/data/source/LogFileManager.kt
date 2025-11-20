package com.hyperxray.an.data.source

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages log file operations with automatic size-based truncation.
 * Ensures log file doesn't exceed MAX_LOG_SIZE_BYTES by truncating oldest entries.
 * Uses lock-free queue (coroutine channel) for async writes to avoid blocking.
 */
class LogFileManager(context: Context) {
    val logFile: File
    private var bufferedWriter: BufferedWriter? = null
    private var writeCount = 0
    private val flushThreshold = 50 // Flush after 50 writes (larger batches = better performance)
    
    // Lock-free queue for async log writes
    private val logChannel = Channel<String>(Channel.UNLIMITED)
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isInitialized = AtomicBoolean(false)
    private val mutex = kotlinx.coroutines.sync.Mutex()

    init {
        val filesDir = context.filesDir
        this.logFile = File(filesDir, LOG_FILE_NAME)
        Log.d(TAG, "Log file path: " + logFile.absolutePath)
        
        // Start background writer coroutine
        startBackgroundWriter()
    }
    
    private fun startBackgroundWriter() {
        if (isInitialized.compareAndSet(false, true)) {
            writeScope.launch {
                try {
                    while (isActive) {
                        val logEntry = logChannel.receive()
                        writeLogEntry(logEntry)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in background log writer", e)
                    }
                }
            }
        }
    }

    fun appendLog(logEntry: String?) {
        if (logEntry == null) return
        
        // Non-blocking: send to channel (lock-free)
        try {
            logChannel.trySend(logEntry)
        } catch (e: Exception) {
            // Channel closed or full - fallback to direct write (shouldn't happen with UNLIMITED)
            Log.w(TAG, "Failed to send log to channel, writing directly: ${e.message}")
            writeScope.launch {
                writeLogEntry(logEntry)
            }
        }
    }
    
    private suspend fun writeLogEntry(logEntry: String) {
        mutex.withLock {
            try {
                // Use buffered writer for better performance
                if (bufferedWriter == null) {
                    bufferedWriter = BufferedWriter(FileWriter(logFile, true), 8192) // 8KB buffer
                }
                
                bufferedWriter?.let { writer ->
                    writer.write(logEntry)
                    writer.newLine()
                    writeCount++
                    
                    // Flush periodically instead of every write
                    if (writeCount >= flushThreshold) {
                        writer.flush()
                        writeCount = 0
                        // Check truncation only after flush to reduce I/O
                        checkAndTruncateLogFile()
                    }
                } ?: run {
                    // Writer is null (possibly closed after truncation), recreate it
                    bufferedWriter = BufferedWriter(FileWriter(logFile, true), 8192)
                    bufferedWriter?.write(logEntry)
                    bufferedWriter?.newLine()
                    writeCount = 1
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing log entry to file", e)
                // Close and reset writer on error
                closeWriter()
            }
        }
    }
    
    private suspend fun closeWriter() {
        try {
            bufferedWriter?.flush()
            bufferedWriter?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing buffered writer", e)
        } finally {
            bufferedWriter = null
            writeCount = 0
        }
    }
    
    suspend fun flush() {
        mutex.withLock {
            try {
                bufferedWriter?.flush()
                checkAndTruncateLogFile()
                // If truncation occurred and writer was closed, it will be reopened on next appendLog
                // No need to reopen here as it's lazy-initialized
            } catch (e: IOException) {
                Log.e(TAG, "Error flushing log file", e)
                // Close writer on error to ensure clean state
                closeWriter()
            }
        }
    }
    
    fun flushSync() {
        runBlocking {
            flush()
        }
    }

    fun readLogs(): String? {
        val logContent = StringBuilder()
        if (!logFile.exists()) {
            Log.d(TAG, "Log file does not exist.")
            return ""
        }
        try {
            FileReader(logFile).use { fileReader ->
                BufferedReader(fileReader).use { bufferedReader ->
                    var line: String?
                    while (bufferedReader.readLine().also { line = it } != null) {
                        logContent.append(line).append("\n")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading log file", e)
            return null
        }
        return logContent.toString()
    }

    suspend fun clearLogs() {
        mutex.withLock {
            // Close writer before clearing
            closeWriter()
            
            if (logFile.exists()) {
                try {
                    FileWriter(logFile, false).use { fileWriter ->
                        fileWriter.write("")
                        Log.d(TAG, "Log file content cleared successfully.")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to clear log file content.", e)
                }
            } else {
                Log.d(TAG, "Log file does not exist, no content to clear.")
            }
        }
    }
    
    fun clearLogsSync() {
        runBlocking {
            clearLogs()
        }
    }

    private suspend fun checkAndTruncateLogFile() {
        if (!logFile.exists()) {
            Log.d(TAG, "Log file does not exist for truncation check.")
            return
        }
        val currentSize = logFile.length()
        if (currentSize <= MAX_LOG_SIZE_BYTES) {
            return
        }
        Log.d(
            TAG,
            "Log file size ($currentSize bytes) exceeds limit ($MAX_LOG_SIZE_BYTES bytes). Truncating oldest $TRUNCATE_SIZE_BYTES bytes."
        )
        try {
            val startByteToKeep = currentSize - TRUNCATE_SIZE_BYTES
            RandomAccessFile(logFile, "rw").use { raf ->
                raf.seek(startByteToKeep)
                val firstLineToKeepStartPos: Long
                val firstPartialOrFullLine = raf.readLine()
                if (firstPartialOrFullLine != null) {
                    firstLineToKeepStartPos = raf.filePointer
                } else {
                    Log.w(
                        TAG,
                        "Could not read line from calculated start position for truncation. Clearing file as a fallback."
                    )
                    clearLogs()
                    return
                }
                raf.channel.use { sourceChannel ->
                    val tempLogFile = File(logFile.parentFile, "$LOG_FILE_NAME.tmp")
                    FileOutputStream(tempLogFile).use { fos ->
                        fos.channel.use { destChannel ->
                            val bytesToTransfer = sourceChannel.size() - firstLineToKeepStartPos
                            sourceChannel.transferTo(
                                firstLineToKeepStartPos,
                                bytesToTransfer,
                                destChannel
                            )
                        }
                    }
                    if (logFile.delete()) {
                        if (tempLogFile.renameTo(logFile)) {
                            Log.d(
                                TAG,
                                "Log file truncated successfully. New size: " + logFile.length() + " bytes."
                            )
                            // Close writer so it will reopen with the new file on next appendLog call
                            closeWriter()
                        } else {
                            Log.e(TAG, "Failed to rename temp log file to original file.")
                            tempLogFile.delete()
                        }
                    } else {
                        Log.e(TAG, "Failed to delete original log file during truncation.")
                        tempLogFile.delete()
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error during log file truncation", e)
            clearLogs()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during log file truncation", e)
            clearLogs()
        }
    }

    fun close() {
        logChannel.close()
        writeScope.cancel()
        runBlocking {
            mutex.withLock {
                closeWriter()
            }
        }
    }
    
    companion object {
        private const val TAG = "LogFileManager"
        private const val LOG_FILE_NAME = "app_log.txt"
        private const val MAX_LOG_SIZE_BYTES = (10 * 1024 * 1024).toLong()
        private const val TRUNCATE_SIZE_BYTES = (5 * 1024 * 1024).toLong()
    }
}