package com.hyperxray.an.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.vpn.HyperVpnHelper

/**
 * 🚀 Boot Receiver (2030 Architecture)
 * 
 * Handles auto-start on device boot using the new lifecycle system.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "com.hyperxray.an.TEST_BOOT") {
            Log.d(TAG, "Boot completed or test received: ${intent.action}")
            val prefs = Preferences(context)
            
            if (prefs.autoStart || intent.action == "com.hyperxray.an.TEST_BOOT") {
                Log.d(TAG, "Auto start enabled, starting VPN with next-gen lifecycle")
                startVpnService(context)
            } else {
                Log.d(TAG, "Auto start disabled")
            }
        }
    }

    private fun startVpnService(context: Context) {
        // Check if VPN permission is already granted
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            Log.w(TAG, "VPN permission not granted, cannot auto-start from boot")
            return
        }
        
        // Use HyperVpnHelper for automatic service selection (new lifecycle)
        HyperVpnHelper.startVpnWithWarp(context)
        Log.d(TAG, "VPN auto-start initiated via HyperVpnHelper")
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
