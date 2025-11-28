package com.hyperxray.an.notification

import android.app.ActivityManager
import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetVpnStatusUseCase
import com.hyperxray.an.vpn.HyperVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetVpnStatusUseCase
 */
class GetVpnStatusUseCaseImpl(
    private val context: Context
) : GetVpnStatusUseCase {
    override suspend fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val isRunning = isVpnServiceRunning(context, HyperVpnService::class.java)
            val status = if (isRunning) {
                buildString {
                    appendLine("<b>📡 VPN CONNECTION STATUS</b>")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("Status: <b>✅ CONNECTED</b>")
                    appendLine("")
                    appendLine("VPN is currently active")
                    appendLine("and running properly.")
                }
            } else {
                buildString {
                    appendLine("<b>📡 VPN CONNECTION STATUS</b>")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("Status: <b>❌ DISCONNECTED</b>")
                    appendLine("")
                    appendLine("VPN is currently not active.")
                }
            }
            Result.success(status)
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

