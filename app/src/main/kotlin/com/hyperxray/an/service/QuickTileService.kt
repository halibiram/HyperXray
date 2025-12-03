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
import com.hyperxray.an.vpn.HyperVpnHelper
import com.hyperxray.an.vpn.HyperVpnService
import com.hyperxray.an.vpn.lifecycle.HyperVpnServiceAdapter

/**
 * 🚀 Quick Settings tile service for VPN toggle (2030 Architecture)
 * 
 * Supports both legacy HyperVpnService and new HyperVpnServiceAdapter.
 * Uses HyperVpnHelper for automatic service selection.
 */
class QuickTileService : TileService() {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateStr = intent.getStringExtra("state")
            when {
                stateStr == "connected" -> updateTileState(true)
                stateStr == "disconnected" -> updateTileState(false)
                intent.action == HyperVpnService.ACTION_START -> updateTileState(true)
                intent.action == HyperVpnService.ACTION_STOP -> updateTileState(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "QuickTileService created (next-gen lifecycle support)")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "QuickTileService started listening.")

        IntentFilter().apply {
            // Legacy actions
            addAction(HyperVpnService.ACTION_START)
            addAction(HyperVpnService.ACTION_STOP)
            // New lifecycle state broadcasts
            addAction("com.hyperxray.an.HYPER_VPN_STATE_CHANGED")
        }.also { filter ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") 
                registerReceiver(broadcastReceiver, filter)
            }
        }

        // Check both services
        val isRunning = isVpnServiceRunning(this, HyperVpnService::class.java) ||
                        isVpnServiceRunning(this, HyperVpnServiceAdapter::class.java)
        updateTileState(isRunning)
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
                // Use HyperVpnHelper for automatic service selection
                HyperVpnHelper.startVpnWithWarp(this@QuickTileService)
            } else {
                HyperVpnHelper.stopVpn(this@QuickTileService)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "QuickTileService destroyed.")
    }

    private fun updateTileState(isActive: Boolean) {
        qsTile?.apply {
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

    companion object {
        private const val TAG = "QuickTileService"
    }
}