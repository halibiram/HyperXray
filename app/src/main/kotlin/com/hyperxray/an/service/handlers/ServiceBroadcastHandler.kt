package com.hyperxray.an.service.handlers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages broadcast receiver registration and unregistration for TProxyService.
 * Currently no receivers are registered, but this provides structure for future receivers.
 */
class ServiceBroadcastHandler(private val context: Context) {
    
    companion object {
        private const val TAG = "ServiceBroadcastHandler"
    }
    
    /**
     * List of registered receivers for proper cleanup.
     * Uses CopyOnWriteArrayList for thread-safe access.
     */
    private val registeredReceivers = CopyOnWriteArrayList<Pair<BroadcastReceiver, IntentFilter>>()
    
    /**
     * Registers all broadcast receivers.
     * Currently empty as no receivers exist, but structure is ready for future additions.
     */
    fun registerReceivers() {
        // No receivers to register currently
        // Future receivers can be added here, e.g.:
        // val networkReceiver = NetworkStateReceiver()
        // registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        Log.d(TAG, "registerReceivers called (no receivers to register)")
    }
    
    /**
     * Unregisters all broadcast receivers.
     * Ensures proper cleanup of all registered receivers.
     */
    fun unregisterReceivers() {
        registeredReceivers.forEach { (receiver, _) ->
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Unregistered receiver: ${receiver.javaClass.simpleName}")
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
                Log.d(TAG, "Receiver ${receiver.javaClass.simpleName} was not registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${receiver.javaClass.simpleName}", e)
            }
        }
        registeredReceivers.clear()
        Log.d(TAG, "All receivers unregistered")
    }
    
    /**
     * Helper method to register a broadcast receiver.
     * 
     * @param receiver The receiver to register
     * @param filter The intent filter for the receiver
     */
    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            registeredReceivers.add(receiver to filter)
            Log.d(TAG, "Registered receiver: ${receiver.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver: ${receiver.javaClass.simpleName}", e)
        }
    }
    
    /**
     * Helper method to unregister a specific broadcast receiver.
     * 
     * @param receiver The receiver to unregister
     */
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
            registeredReceivers.removeAll { it.first == receiver }
            Log.d(TAG, "Unregistered receiver: ${receiver.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
            Log.d(TAG, "Receiver ${receiver.javaClass.simpleName} was not registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${receiver.javaClass.simpleName}", e)
        }
    }
}













