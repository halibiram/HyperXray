package com.hyperxray.an.service

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.hyperxray.an.vpn.HyperVpnService

/**
 * Quick Settings tile service for quick VPN connection toggle.
 * Listens to HyperVpnService broadcasts to update tile state.
 */
class QuickTileService : TileService() {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HyperVpnService.ACTION_START -> updateTileState(true)
                HyperVpnService.ACTION_STOP -> updateTileState(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "QuickTileService created.")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "QuickTileService started listening.")

        IntentFilter().apply {
            addAction(HyperVpnService.ACTION_START)
            addAction(HyperVpnService.ACTION_STOP)
        }.also { filter ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                    broadcastReceiver,
                    filter
                )
            }
        }

        updateTileState(isVpnServiceRunning(this, HyperVpnService::class.java))
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "QuickTileService stopped listening.")
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "QuickTileService clicked.")

        qsTile.run {
            if (state == Tile.STATE_INACTIVE) {
                if (VpnService.prepare(this@QuickTileService) != null) {
                    Log.e(TAG, "QuickTileService VPN not ready.")
                    return
                }
                startVpnService(HyperVpnService.ACTION_START)
            } else {
                startVpnService(HyperVpnService.ACTION_DISCONNECT)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "QuickTileService destroyed.")
    }

    private fun updateTileState(isActive: Boolean) {
        qsTile.apply {
            state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    private fun isVpnServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
            serviceClass.name == service.service.className
        }
    }

    private fun startVpnService(action: String) {
        Intent(this, HyperVpnService::class.java).apply {
            this.action = action
        }.also { intent ->
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "QuickTileService"
    }
}