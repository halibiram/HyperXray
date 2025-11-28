package com.hyperxray.an.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.vpn.HyperVpnService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "com.hyperxray.an.TEST_BOOT") {
            Log.d(TAG, "Boot completed or test received: ${intent.action}")
            val prefs = Preferences(context)
            // Force true for testing if needed, or rely on pref
            if (prefs.autoStart || intent.action == "com.hyperxray.an.TEST_BOOT") {
                Log.d(TAG, "Auto start enabled, starting HyperVpnService")
                startVpnService(context)
            } else {
                Log.d(TAG, "Auto start disabled")
            }
        }
    }

    private fun startVpnService(context: Context) {
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
