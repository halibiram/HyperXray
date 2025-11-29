package com.hyperxray.an.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.hyperxray.an.vpn.HyperVpnService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.Volatile

/**
 * Sealed class representing service events from HyperVpnService.
 */
sealed class ServiceEvent {
    /**
     * Service has started successfully.
     */
    data object Started : ServiceEvent()

    /**
     * Service has stopped.
     */
    data object Stopped : ServiceEvent()

    /**
     * Service encountered an error.
     * @param errorMessage The error message from the service
     */
    data class Error(val errorMessage: String) : ServiceEvent()
    
    /**
     * Log update received from service.
     * @param logData The log data from the service
     */
    data class LogUpdate(val logData: String) : ServiceEvent()
}

/**
 * Observes HyperVpnService events via BroadcastReceivers and exposes them as a Flow.
 * 
 * This class handles all BroadcastReceiver registration/unregistration logic and converts
 * broadcast intents into typed ServiceEvent objects.
 * 
 * Usage:
 * ```
 * val observer = ServiceEventObserver()
 * observer.startObserving(context)
 * observer.events.collect { event ->
 *     when (event) {
 *         is ServiceEvent.Started -> handleStart()
 *         is ServiceEvent.Stopped -> handleStop()
 *         // ...
 *     }
 * }
 * observer.stopObserving(context)
 * ```
 * 
 * The observer must be started before events will be emitted, and should be stopped
 * when no longer needed to prevent memory leaks.
 * 
 * Note: SOCKS5 and multi-instance Xray events have been removed as they are not
 * applicable to the native Go-based HyperVpnService architecture.
 */
class ServiceEventObserver {
    private val TAG = "ServiceEventObserver"

    @Volatile
    private var receiversRegistered = false

    // Lock object for synchronous operations (used in start and stop)
    private val receiversLock = Any()

    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service started event received")
            _eventChannel.trySend(ServiceEvent.Started)
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service stopped event received")
            _eventChannel.trySend(ServiceEvent.Stopped)
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val errorMessage = intent.getStringExtra(HyperVpnService.EXTRA_ERROR_MESSAGE)
                ?: "An error occurred while starting the VPN service."
            Log.e(TAG, "Service error event received: $errorMessage")
            _eventChannel.trySend(ServiceEvent.Error(errorMessage))
        }
    }
    
    private val logUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Handle Parcel$LazyValue issue on Android by using getExtras() and safe casting
            val logData = try {
                val extras = intent.extras
                if (extras != null) {
                    val value = extras.get(HyperVpnService.EXTRA_LOG_DATA)
                    when (value) {
                        is String -> value
                        else -> value?.toString() ?: ""
                    }
                } else {
                    ""
                }
            } catch (e: ClassCastException) {
                Log.w(TAG, "Failed to get log data from intent: ${e.message}")
                ""
            } catch (e: Exception) {
                Log.w(TAG, "Error reading log data: ${e.message}")
                ""
            }
            Log.d(TAG, "Log update event received")
            _eventChannel.trySend(ServiceEvent.LogUpdate(logData))
        }
    }

    // Internal channel for events (used by receivers)
    private val _eventChannel = Channel<ServiceEvent>(
        capacity = Channel.UNLIMITED
    )

    // Internal scope for the observer
    private val observerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Flow of service events. Events are emitted as they are received from the service.
     * 
     * This is a SharedFlow that will keep emitting events to all collectors.
     * Make sure to call [startObserving] before collecting from this flow.
     */
    val events: SharedFlow<ServiceEvent> = _eventChannel.receiveAsFlow()
        .shareIn(
            scope = observerScope,
            started = SharingStarted.Lazily,
            replay = 0
        )

    /**
     * Starts observing service events by registering all BroadcastReceivers.
     * This method is idempotent - it will not register receivers multiple times.
     * 
     * @param context The context to use for registering receivers
     * 
     * Note: This method is synchronous and should be called before collecting from [events].
     */
    fun startObserving(context: Context) {
        // Check if receivers are already registered to prevent double registration
        if (receiversRegistered) {
            Log.d(TAG, "Receivers already registered, skipping.")
            return
        }

        // Use synchronized block for thread safety
        synchronized(receiversLock) {
            // Double-check pattern: check again after acquiring lock
            if (receiversRegistered) {
                Log.d(TAG, "Receivers already registered (double-check), skipping.")
                return@synchronized
            }

            try {
                val startSuccessFilter = IntentFilter(HyperVpnService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        startReceiver,
                        startSuccessFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(startReceiver, startSuccessFilter)
                }

                val stopSuccessFilter = IntentFilter(HyperVpnService.ACTION_STOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        stopReceiver,
                        stopSuccessFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(stopReceiver, stopSuccessFilter)
                }

                val errorFilter = IntentFilter(HyperVpnService.ACTION_ERROR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        errorReceiver,
                        errorFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(errorReceiver, errorFilter)
                }
                
                val logUpdateFilter = IntentFilter(HyperVpnService.ACTION_LOG_UPDATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        logUpdateReceiver,
                        logUpdateFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(logUpdateReceiver, logUpdateFilter)
                }

                receiversRegistered = true
                Log.d(TAG, "HyperVpnService receivers registered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering receivers: ${e.message}", e)
                // Don't set receiversRegistered = true on error
            }
        }
    }

    /**
     * Stops observing service events by unregistering all BroadcastReceivers.
     * This method is idempotent - it will not throw if receivers are not registered.
     * 
     * @param context The context to use for unregistering receivers
     * 
     * Note: This method is synchronous and should be called when the observer is no longer needed.
     */
    fun stopObserving(context: Context) {
        // Check if receivers are already unregistered to prevent double unregistration
        if (!receiversRegistered) {
            Log.d(TAG, "Receivers already unregistered, skipping.")
            return
        }

        // Use synchronized block for thread safety
        synchronized(receiversLock) {
            // Double-check pattern: check again after acquiring lock
            if (!receiversRegistered) {
                Log.d(TAG, "Receivers already unregistered (double-check), skipping.")
                return@synchronized
            }

            try {
                // Unregister each receiver, handling IllegalArgumentException if not registered
                try {
                    context.unregisterReceiver(startReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "startReceiver not registered: ${e.message}")
                }

                try {
                    context.unregisterReceiver(stopReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "stopReceiver not registered: ${e.message}")
                }

                try {
                    context.unregisterReceiver(errorReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "errorReceiver not registered: ${e.message}")
                }
                
                try {
                    context.unregisterReceiver(logUpdateReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "logUpdateReceiver not registered: ${e.message}")
                }

                receiversRegistered = false
                Log.d(TAG, "HyperVpnService receivers unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receivers: ${e.message}", e)
                // Set receiversRegistered = false even on error to allow retry
                receiversRegistered = false
            }
        }
    }
}
