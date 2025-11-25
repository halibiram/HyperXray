package com.hyperxray.an.common

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.service.TProxyService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper class to capture and broadcast AI logs directly.
 * This is more reliable than reading from logcat.
 */
object AiLogHelper {
    private var logFileManager: LogFileManager? = null
    private var context: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
    
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val ctx = context ?: run {
                    Log.e("AiLogHelper", "Context is null, cannot broadcast logs")
                    return@Runnable
                }
                val logUpdateIntent = Intent(TProxyService.ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(ctx.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    TProxyService.EXTRA_LOG_DATA, ArrayList(logBroadcastBuffer)
                )
                ctx.sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
            }
        }
    }
    
    /**
     * Initialize the AI log helper with context and log file manager.
     */
    fun initialize(ctx: Context, logManager: LogFileManager) {
        context = ctx.applicationContext
        logFileManager = logManager
    }
    
    /**
     * Log an AI-related message and broadcast it.
     */
    fun log(tag: String, level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp [$tag] $message"
        
        logFileManager?.appendLog(logLine)
        
        // Broadcast immediately
        synchronized(logBroadcastBuffer) {
            logBroadcastBuffer.add(logLine)
            handler.removeCallbacks(broadcastLogsRunnable)
            handler.post(broadcastLogsRunnable)
        }
        
        // Also write to Android Log for debugging
        when (level.uppercase()) {
            "ERROR", "E" -> Log.e(tag, message)
            "WARN", "W" -> Log.w(tag, message)
            "INFO", "I" -> Log.i(tag, message)
            "DEBUG", "D" -> Log.d(tag, message)
            else -> Log.d(tag, message)
        }
    }
    
    fun d(tag: String, message: String) = log(tag, "DEBUG", message)
    fun i(tag: String, message: String) = log(tag, "INFO", message)
    fun w(tag: String, message: String) = log(tag, "WARN", message)
    fun e(tag: String, message: String) = log(tag, "ERROR", message)
    
    // Overloads with exception support
    fun e(tag: String, message: String, throwable: Throwable) {
        val messageWithException = "$message\nException: ${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString()}"
        log(tag, "ERROR", messageWithException)
        Log.e(tag, message, throwable)
    }
    
    fun w(tag: String, message: String, throwable: Throwable) {
        val messageWithException = "$message\nException: ${throwable.javaClass.simpleName}: ${throwable.message}"
        log(tag, "WARN", messageWithException)
        Log.w(tag, message, throwable)
    }
}




