package com.hyperxray.an.service.xray

import android.content.Context
import android.content.Intent
import android.os.Handler
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.managers.XrayLogHandler
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Context interface for XrayRunner implementations.
 * Provides access to service dependencies and callbacks.
 */
interface XrayRunnerContext {
    val context: Context
    val serviceScope: CoroutineScope
    val handler: Handler
    val prefs: Preferences
    val logFileManager: LogFileManager
    val xrayLogHandler: XrayLogHandler
    val telegramNotificationManager: TelegramNotificationManager?
    
    // Process management
    var xrayProcess: Process?
    var multiXrayCoreManager: MultiXrayCoreManager?
    
    // Session state (accessed via callbacks)
    fun isStopping(): Boolean
    fun isStarting(): Boolean
    fun setStarting(value: Boolean)
    fun setStopping(value: Boolean)
    fun isReloadingRequested(): Boolean
    fun setReloadingRequested(value: Boolean)
    fun isSocks5ReadinessChecked(): Boolean
    fun setSocks5ReadinessChecked(value: Boolean)
    
    // Additional dependencies for multi-instance mode
    fun getSystemDnsCacheServer(): com.hyperxray.an.core.network.dns.SystemDnsCacheServer?
    fun isDnsCacheInitialized(): Boolean
    fun setDnsCacheInitialized(value: Boolean)
    fun getLogBroadcastChannel(): kotlinx.coroutines.channels.SendChannel<String>?
    suspend fun checkSocks5Readiness(prefs: Preferences)
    
    // Callbacks
    fun stopXray(reason: String?)
    suspend fun runXrayProcess()
    fun sendBroadcast(intent: Intent)
    fun getApplicationPackageName(): String
    fun getNativeLibraryDir(): String?
    
    // Constants
    fun getActionError(): String
    fun getActionInstanceStatusUpdate(): String
    fun getActionSocks5Ready(): String
    fun getExtraErrorMessage(): String
}

