package com.hyperxray.an.vpn

import android.content.Context
import android.content.Intent
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.vpn.lifecycle.HyperVpnServiceAdapter

/**
 * 🚀 Helper class for VPN lifecycle management (2030 Architecture)
 * 
 * Provides convenient methods for UI components to interact with
 * the next-generation VPN lifecycle system.
 * 
 * Features:
 * - Declarative state machine integration
 * - Automatic permission handling
 * - Backward compatible API
 */
object HyperVpnHelper {
    private const val TAG = "HyperVpnHelper"
    
    // Feature flag for new lifecycle system
    // TODO: Set to true when lifecycle system is fully integrated
    private const val USE_NEW_LIFECYCLE = false
    
    /**
     * Callback interface for VPN conflict resolution
     */
    interface ConflictResolutionCallback {
        fun onNoConflict()
        fun onConflictResolved(appName: String)
        fun onNeedsUserIntervention(app: ConflictingVpnDetector.ConflictingVpnApp)
        fun onUnknownConflict()
        fun onTunActiveUnknownApp(tunInfo: ConflictingVpnDetector.TunInterfaceInfo)
    }
    
    /**
     * Check and handle conflicting VPN apps before starting.
     * Also checks if TUN interface is active.
     * 
     * @param context Android context
     * @param autoForceStop If true, automatically attempt to force stop conflicting apps
     * @param callback Callback for conflict resolution results
     * @return true if VPN can be started (no conflict or resolved), false if user intervention needed
     */
    fun checkAndHandleVpnConflicts(
        context: Context,
        autoForceStop: Boolean = true,
        callback: ConflictResolutionCallback? = null
    ): Boolean {
        AiLogHelper.d(TAG, "🔍 Checking for conflicting VPN apps and TUN interfaces...")
        
        val result = ConflictingVpnDetector.checkAndHandleConflicts(context)
        
        return when (result) {
            is ConflictingVpnDetector.ConflictCheckResult.NoConflict -> {
                callback?.onNoConflict()
                true
            }
            is ConflictingVpnDetector.ConflictCheckResult.ConflictResolved -> {
                AiLogHelper.i(TAG, "✅ Conflicting VPN '${result.app.appName}' stopped automatically")
                callback?.onConflictResolved(result.app.appName)
                true
            }
            is ConflictingVpnDetector.ConflictCheckResult.NeedsUserIntervention -> {
                AiLogHelper.w(TAG, "⚠️ User needs to manually stop: ${result.app.appName}")
                callback?.onNeedsUserIntervention(result.app)
                false
            }
            is ConflictingVpnDetector.ConflictCheckResult.UnknownConflict -> {
                AiLogHelper.w(TAG, "⚠️ Unknown VPN conflict detected")
                callback?.onUnknownConflict()
                false
            }
            is ConflictingVpnDetector.ConflictCheckResult.TunActiveUnknownApp -> {
                AiLogHelper.w(TAG, "⚠️ TUN interface active (${result.tunInfo.name}) but app unknown")
                callback?.onTunActiveUnknownApp(result.tunInfo)
                false
            }
        }
    }
    
    /**
     * Check if TUN interface is currently active.
     * 
     * @return TunInterfaceInfo if active, null otherwise
     */
    fun getTunInterfaceInfo(): ConflictingVpnDetector.TunInterfaceInfo? {
        return ConflictingVpnDetector.getTunInterfaceInfo()
    }
    
    /**
     * Check if TUN interface is active.
     * 
     * @return true if TUN is active
     */
    fun isTunActive(): Boolean {
        return ConflictingVpnDetector.isTunInterfaceActive()
    }
    
    /**
     * Open settings for a conflicting VPN app so user can force stop it.
     */
    fun openConflictingAppSettings(context: Context, packageName: String): Boolean {
        return ConflictingVpnDetector.openAppSettings(context, packageName)
    }
    
    /**
     * Start VPN with WARP configuration.
     * Uses the new declarative lifecycle system for improved reliability.
     * Automatically checks and handles conflicting VPN apps.
     * 
     * @param context Android context
     * @param autoHandleConflicts If true, automatically handle conflicting VPN apps
     * @param conflictCallback Optional callback for conflict resolution events
     */
    @JvmOverloads
    fun startVpnWithWarp(
        context: Context,
        autoHandleConflicts: Boolean = true,
        conflictCallback: ConflictResolutionCallback? = null
    ) {
        try {
            AiLogHelper.i(TAG, "🚀 Starting VPN with next-gen lifecycle...")
            
            // Step 1: Check and handle conflicting VPN apps
            if (autoHandleConflicts) {
                val canProceed = checkAndHandleVpnConflicts(context, true, conflictCallback)
                if (!canProceed) {
                    AiLogHelper.w(TAG, "⚠️ Cannot start VPN - conflicting app needs manual intervention")
                    return
                }
            }

            // Step 2: Check VPN permission
            val prepareIntent = android.net.VpnService.prepare(context)
            AiLogHelper.d(TAG, "🔍 VpnService.prepare() returned: ${if (prepareIntent != null) "Intent (permission needed)" else "null (permission granted)"}")

            if (prepareIntent != null) {
                // VPN permission not granted, user needs to approve
                AiLogHelper.w(TAG, "⚠️ VPN permission not granted, showing permission dialog")

                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

                try {
                    context.startActivity(prepareIntent)
                    AiLogHelper.i(TAG, "✅ Permission dialog intent started successfully")
                } catch (e: Exception) {
                    AiLogHelper.e(TAG, "❌ Failed to start permission dialog: ${e.message}", e)
                    try {
                        val appContext = context.applicationContext ?: context
                        appContext.startActivity(prepareIntent)
                        AiLogHelper.i(TAG, "✅ Permission dialog started with application context")
                    } catch (e2: Exception) {
                        AiLogHelper.e(TAG, "❌ Failed to start permission dialog with app context: ${e2.message}", e2)
                    }
                }
                return
            }

            AiLogHelper.i(TAG, "✅ VPN permission already granted, starting service...")

            if (USE_NEW_LIFECYCLE) {
                // Use new lifecycle adapter
                val intent = Intent(context, HyperVpnServiceAdapter::class.java).apply {
                    action = HyperVpnServiceAdapter.ACTION_START
                }
                context.startForegroundService(intent)
                AiLogHelper.d(TAG, "✅ Started HyperVpnServiceAdapter (next-gen lifecycle)")
            } else {
                // Fallback to legacy service
                val intent = Intent(context, HyperVpnService::class.java).apply {
                    action = HyperVpnService.ACTION_START
                }
                context.startForegroundService(intent)
                AiLogHelper.d(TAG, "✅ Started HyperVpnService (legacy)")
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "❌ Failed to start VPN: ${e.message}", e)
        }
    }
    
    /**
     * Start VPN with custom WireGuard and Xray configurations.
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
            if (USE_NEW_LIFECYCLE) {
                val intent = Intent(context, HyperVpnServiceAdapter::class.java).apply {
                    action = HyperVpnServiceAdapter.ACTION_START
                    putExtra(HyperVpnService.EXTRA_WG_CONFIG, wgConfigJson)
                    putExtra(HyperVpnService.EXTRA_XRAY_CONFIG, xrayConfigJson)
                }
                context.startForegroundService(intent)
                AiLogHelper.d(TAG, "Started HyperVpnServiceAdapter with custom config")
            } else {
                val intent = Intent(context, HyperVpnService::class.java).apply {
                    action = HyperVpnService.ACTION_START
                    putExtra(HyperVpnService.EXTRA_WG_CONFIG, wgConfigJson)
                    putExtra(HyperVpnService.EXTRA_XRAY_CONFIG, xrayConfigJson)
                }
                context.startForegroundService(intent)
                AiLogHelper.d(TAG, "Started HyperVpnService with custom config")
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to start VPN: ${e.message}", e)
        }
    }
    
    /**
     * Stop VPN with graceful disconnection.
     * Uses the new lifecycle system for reliable shutdown.
     */
    fun stopVpn(context: Context) {
        try {
            if (USE_NEW_LIFECYCLE) {
                val intent = Intent(context, HyperVpnServiceAdapter::class.java).apply {
                    action = HyperVpnServiceAdapter.ACTION_STOP
                }
                context.startService(intent)
                AiLogHelper.d(TAG, "Initiated graceful VPN disconnection (next-gen)")
            } else {
                val intent = Intent(context, HyperVpnService::class.java).apply {
                    action = HyperVpnService.ACTION_STOP
                }
                context.startService(intent)
                AiLogHelper.d(TAG, "Initiated VPN disconnection (legacy)")
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to initiate VPN disconnection: ${e.message}", e)
        }
    }
    
    /**
     * Check if new lifecycle system is enabled
     */
    fun isNewLifecycleEnabled(): Boolean = USE_NEW_LIFECYCLE
}



