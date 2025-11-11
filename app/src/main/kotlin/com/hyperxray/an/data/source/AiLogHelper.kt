package com.hyperxray.an.data.source

import android.content.Context
import android.content.Intent
import android.util.Log
import com.hyperxray.an.service.TProxyService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper class to write AI logs directly to LogFileManager and broadcast them.
 * This is more reliable than reading from logcat.
 */
object AiLogHelper {
    private var logFileManager: LogFileManager? = null
    private var context: Context? = null
    
    fun initialize(context: Context, logFileManager: LogFileManager) {
        this.context = context.applicationContext
        this.logFileManager = logFileManager
    }
    
    /**
     * Write AI log to LogFileManager and broadcast it.
     */
    fun log(level: String, tag: String, message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
            val logLine = "$timestamp [$tag] $message"
            
            // Write to log file
            logFileManager?.appendLog(logLine)
            
            // Broadcast to LogViewModel
            context?.let { ctx ->
                val intent = Intent(TProxyService.ACTION_LOG_UPDATE).apply {
                    putStringArrayListExtra(
                        TProxyService.EXTRA_LOG_DATA,
                        arrayListOf(logLine)
                    )
                }
                ctx.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e("AiLogHelper", "Error writing AI log", e)
        }
    }
    
    fun d(tag: String, message: String) {
        log("DEBUG", tag, message)
        Log.d(tag, message)
    }
    
    fun i(tag: String, message: String) {
        log("INFO", tag, message)
        Log.i(tag, message)
    }
    
    fun w(tag: String, message: String) {
        log("WARN", tag, message)
        Log.w(tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        log("ERROR", tag, fullMessage)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}

