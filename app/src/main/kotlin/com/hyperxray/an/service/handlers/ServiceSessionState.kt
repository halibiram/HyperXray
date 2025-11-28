package com.hyperxray.an.service.handlers

import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.prefs.Preferences
import android.content.Context
import com.hyperxray.an.service.managers.ServiceNotificationManager
import com.hyperxray.an.service.managers.TunInterfaceManager
import com.hyperxray.an.service.managers.XrayCoreManager
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds service state references needed by intent and broadcast handlers.
 * Provides a clean interface for handlers to access service components without
 * directly coupling to TProxyService implementation details.
 */
data class ServiceSessionState(
    val service: Context,
    val notificationManager: ServiceNotificationManager,
    val logFileManager: LogFileManager,
    val tunInterfaceManager: TunInterfaceManager,
    val xrayCoreManager: XrayCoreManager,
    val prefs: Preferences,
    val serviceScope: CoroutineScope,
    val telegramNotificationManager: TelegramNotificationManager?,
    val reloadingRequested: AtomicBoolean,
    val isStarting: AtomicBoolean,
    val isStopping: AtomicBoolean,
    val getXrayProcess: () -> Process? // Function to access xrayProcess (may be null)
)















