package com.hyperxray.an.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
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
 * Sealed class representing service events from TProxyService.
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
     * SOCKS5 proxy readiness status update.
     * @param isReady Whether SOCKS5 is ready
     */
    data class Socks5Ready(val isReady: Boolean) : ServiceEvent()

    /**
     * Xray instance status update.
     * @param instanceCount Total number of instances
     * @param hasRunning Whether any instance is running
     * @param instancesStatus Map of instance index to status
     */
    data class InstanceStatusUpdate(
        val instanceCount: Int,
        val hasRunning: Boolean,
        val instancesStatus: Map<Int, XrayRuntimeStatus>
    ) : ServiceEvent()
}

/**
 * Observes TProxyService events via BroadcastReceivers and exposes them as a Flow.
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
            val errorMessage = intent.getStringExtra(TProxyService.EXTRA_ERROR_MESSAGE)
                ?: "An error occurred while starting the VPN service."
            Log.e(TAG, "Service error event received: $errorMessage")
            _eventChannel.trySend(ServiceEvent.Error(errorMessage))
        }
    }

    private val socks5ReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isReady = intent.getBooleanExtra("is_ready", false)
            Log.d(TAG, "SOCKS5 readiness event received: $isReady")
            _eventChannel.trySend(ServiceEvent.Socks5Ready(isReady))
        }
    }

    private val instanceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val instanceCount = intent.getIntExtra("instance_count", 0)
            val hasRunning = intent.getBooleanExtra("has_running", false)
            Log.d(TAG, "Instance status event received: count=$instanceCount, hasRunning=$hasRunning")

            // Build status map from intent extras with proper status type parsing
            val statusMap = mutableMapOf<Int, XrayRuntimeStatus>()

            // Parse all instance statuses from intent extras
            // Try to get status by index first (new format with status_type)
            // If not found, try legacy format (just PID/port)
            for (i in 0 until instanceCount) {
                val statusType = intent.getStringExtra("instance_${i}_status_type")

                val status = when (statusType) {
                    "Running" -> {
                        val pid = intent.getLongExtra("instance_${i}_pid", -1L)
                        val port = intent.getIntExtra("instance_${i}_port", -1)
                        if (pid > 0 && port > 0) {
                            XrayRuntimeStatus.Running(pid, port)
                        } else {
                            // Invalid Running status, fallback to Stopped
                            Log.w(TAG, "Instance $i: Running status but invalid PID/port (pid=$pid, port=$port), using Stopped")
                            XrayRuntimeStatus.Stopped
                        }
                    }
                    "Starting" -> {
                        XrayRuntimeStatus.Starting
                    }
                    "Stopping" -> {
                        XrayRuntimeStatus.Stopping
                    }
                    "Error" -> {
                        val errorMessage = intent.getStringExtra("instance_${i}_error_message") ?: "Unknown error"
                        XrayRuntimeStatus.Error(errorMessage)
                    }
                    "ProcessExited" -> {
                        val exitCode = intent.getIntExtra("instance_${i}_exit_code", -1)
                        val exitMessage = intent.getStringExtra("instance_${i}_exit_message")
                        XrayRuntimeStatus.ProcessExited(exitCode, exitMessage)
                    }
                    "Stopped" -> {
                        XrayRuntimeStatus.Stopped
                    }
                    null, "" -> {
                        // Legacy format: check PID and port
                        val pid = intent.getLongExtra("instance_${i}_pid", -1L)
                        val port = intent.getIntExtra("instance_${i}_port", -1)
                        if (pid > 0 && port > 0) {
                            Log.d(TAG, "Instance $i: Legacy format detected, using Running status")
                            XrayRuntimeStatus.Running(pid, port)
                        } else {
                            XrayRuntimeStatus.Stopped
                        }
                    }
                    else -> {
                        Log.w(TAG, "Instance $i: Unknown status type '$statusType', using Stopped")
                        XrayRuntimeStatus.Stopped
                    }
                }

                statusMap[i] = status
                Log.d(TAG, "Instance $i status parsed: $status")
            }

            _eventChannel.trySend(
                ServiceEvent.InstanceStatusUpdate(
                    instanceCount = instanceCount,
                    hasRunning = hasRunning,
                    instancesStatus = statusMap
                )
            )
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
                val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
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

                val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
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

                val errorFilter = IntentFilter(TProxyService.ACTION_ERROR)
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

                val socks5ReadyFilter = IntentFilter(TProxyService.ACTION_SOCKS5_READY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        socks5ReadyReceiver,
                        socks5ReadyFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(socks5ReadyReceiver, socks5ReadyFilter)
                }

                val instanceStatusFilter = IntentFilter(TProxyService.ACTION_INSTANCE_STATUS_UPDATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        instanceStatusReceiver,
                        instanceStatusFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(instanceStatusReceiver, instanceStatusFilter)
                }

                receiversRegistered = true
                Log.d(TAG, "TProxyService receivers registered.")
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
                    context.unregisterReceiver(socks5ReadyReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "socks5ReadyReceiver not registered: ${e.message}")
                }

                try {
                    context.unregisterReceiver(instanceStatusReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "instanceStatusReceiver not registered: ${e.message}")
                }

                receiversRegistered = false
                Log.d(TAG, "TProxyService receivers unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receivers: ${e.message}", e)
                // Set receiversRegistered = false even on error to allow retry
                receiversRegistered = false
            }
        }
    }
}

