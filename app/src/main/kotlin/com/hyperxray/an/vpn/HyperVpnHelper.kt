package com.hyperxray.an.vpn

import android.content.Context
import android.content.Intent
import com.hyperxray.an.common.AiLogHelper

/**
 * Helper class for starting and stopping HyperVpnService.
 * Provides convenient methods for UI components.
 */
object HyperVpnHelper {
    private const val TAG = "HyperVpnHelper"
    
    /**
     * Start HyperVpnService with WARP configuration.
     * Service will automatically register WARP and extract Xray config from selected profile.
     * 
     * This is a convenience method that starts the service without explicit configs.
     * The service will:
     * 1. Register with Cloudflare WARP API to get WireGuard config
     * 2. Extract Xray config from the currently selected profile
     */
    fun startVpnWithWarp(context: Context) {
        try {
            // Check VPN permission first
            val prepareIntent = android.net.VpnService.prepare(context)
            if (prepareIntent != null) {
                // VPN permission not granted, user needs to approve
                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(prepareIntent)
                AiLogHelper.w(TAG, "VPN permission not granted, showing permission dialog")
                return
            }
            
            val intent = Intent(context, HyperVpnService::class.java).apply {
                action = HyperVpnService.ACTION_START
            }
            context.startForegroundService(intent)
            AiLogHelper.d(TAG, "✅ Started HyperVpnService with WARP")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "❌ Failed to start HyperVpnService: ${e.message}", e)
        }
    }
    
    /**
     * Start HyperVpnService with custom WireGuard and Xray configurations.
     * 
     * @param context Android context
     * @param wgConfigJson WireGuard configuration as JSON string
     * @param xrayConfigJson Xray configuration as JSON string
     */
    fun startVpnWithConfig(
        context: Context,
        wgConfigJson: String,
        xrayConfigJson: String
    ) {
        try {
            val intent = Intent(context, HyperVpnService::class.java).apply {
                action = HyperVpnService.ACTION_START
                putExtra(HyperVpnService.EXTRA_WG_CONFIG, wgConfigJson)
                putExtra(HyperVpnService.EXTRA_XRAY_CONFIG, xrayConfigJson)
            }
            context.startForegroundService(intent)
            AiLogHelper.d(TAG, "Started HyperVpnService with custom config")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to start HyperVpnService: ${e.message}", e)
        }
    }
    
    /**
     * Stop HyperVpnService.
     */
    fun stopVpn(context: Context) {
        try {
            val intent = Intent(context, HyperVpnService::class.java).apply {
                action = HyperVpnService.ACTION_STOP
            }
            context.startService(intent)
            AiLogHelper.d(TAG, "Stopped HyperVpnService")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to stop HyperVpnService: ${e.message}", e)
        }
    }
}



