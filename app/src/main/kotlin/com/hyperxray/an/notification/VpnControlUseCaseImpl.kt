package com.hyperxray.an.notification

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.hyperxray.an.feature.telegram.domain.usecase.VpnControlUseCase
import com.hyperxray.an.vpn.HyperVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Implementation of VpnControlUseCase
 */
class VpnControlUseCaseImpl(
    private val context: Context
) : VpnControlUseCase {
    override suspend fun connect(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check if VPN permission is granted
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                return@withContext Result.success(
                    "❌ VPN permission not granted. Please grant VPN permission in app settings first."
                )
            }
            
            // Check if already connected
            if (isVpnServiceRunning(context, HyperVpnService::class.java)) {
                return@withContext Result.success(
                    "⚠️ VPN is already connected!"
                )
            }
            
            // Start VPN service
            val startIntent = Intent(context, HyperVpnService::class.java).apply {
                action = HyperVpnService.ACTION_START
            }
            context.startForegroundService(startIntent)
            
            // Wait a bit and check if service started
            kotlinx.coroutines.delay(2000)
            
            if (isVpnServiceRunning(context, HyperVpnService::class.java)) {
                Result.success("✅ VPN connection started successfully!")
            } else {
                Result.success("⚠️ VPN start command sent. Connection may take a few moments...")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun disconnect(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check if VPN is running
            if (!isVpnServiceRunning(context, HyperVpnService::class.java)) {
                return@withContext Result.success(
                    "⚠️ VPN is already disconnected!"
                )
            }
            
            // Stop VPN service
            val stopIntent = Intent(context, HyperVpnService::class.java).apply {
                action = HyperVpnService.ACTION_DISCONNECT
            }
            context.startForegroundService(stopIntent)
            
            // Wait a bit and check if service stopped
            kotlinx.coroutines.delay(2000)
            
            if (!isVpnServiceRunning(context, HyperVpnService::class.java)) {
                Result.success("✅ VPN connection stopped successfully!")
            } else {
                Result.success("⚠️ VPN stop command sent. Disconnection may take a few moments...")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getConnectionStatus(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Result.success(isVpnServiceRunning(context, HyperVpnService::class.java))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun restart(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check if VPN is running
            if (!isVpnServiceRunning(context, HyperVpnService::class.java)) {
                return@withContext Result.success(
                    "⚠️ VPN is not running. Use /connect to start VPN first."
                )
            }
            
            // Reload config (this will restart Xray process)
            val reloadIntent = Intent(context, HyperVpnService::class.java).apply {
                action = HyperVpnService.ACTION_RELOAD_CONFIG
            }
            context.startForegroundService(reloadIntent)
            
            // Wait a bit and check if service is still running
            kotlinx.coroutines.delay(3000)
            
            if (isVpnServiceRunning(context, HyperVpnService::class.java)) {
                Result.success("✅ VPN restarted successfully! Native Go tunnel reloaded.")
            } else {
                Result.success("⚠️ Restart command sent. VPN may take a few moments to restart...")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    @Suppress("DEPRECATION")
    private fun isVpnServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
            serviceClass.name == service.service.className
        }
    }
}

