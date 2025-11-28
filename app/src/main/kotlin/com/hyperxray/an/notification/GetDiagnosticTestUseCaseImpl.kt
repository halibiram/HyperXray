package com.hyperxray.an.notification

import android.app.ActivityManager
import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetDiagnosticTestUseCase
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.vpn.HyperVpnService
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetDiagnosticTestUseCase
 */
class GetDiagnosticTestUseCaseImpl(
    private val context: Context
) : GetDiagnosticTestUseCase {
    override suspend fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val diagnosticResults = mutableListOf<DiagnosticResult>()
            
            // Test 1: VPN Service Status
            val vpnRunning = isVpnServiceRunning(context, HyperVpnService::class.java)
            diagnosticResults.add(
                DiagnosticResult(
                    name = "VPN Service",
                    status = if (vpnRunning) "✅ Running" else "❌ Not Running",
                    details = if (vpnRunning) "Service is active" else "Service is not active"
                )
            )
            
            // Test 2: Xray-core Connection
            val prefs = Preferences(context)
            val client = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
            var xrayConnected = false
            var xrayDetails = "Cannot connect to Xray-core"
            
            if (client != null) {
                try {
                    val stats = client.getSystemStats()
                    if (stats != null) {
                        xrayConnected = true
                        xrayDetails = "Connected (${stats.numGoroutine} goroutines, uptime: ${stats.uptime}s)"
                    }
                    client.close()
                } catch (e: Exception) {
                    client.close()
                    xrayDetails = "Error: ${e.message}"
                }
            }
            
            diagnosticResults.add(
                DiagnosticResult(
                    name = "Xray-core Connection",
                    status = if (xrayConnected) "✅ Connected" else "❌ Not Connected",
                    details = xrayDetails
                )
            )
            
            // Test 3: DNS Cache Server
            val dnsCacheStatus = try {
                val stats = com.hyperxray.an.core.network.dns.DnsCacheManager.getStats()
                // getStats() returns String, parse it for hits/misses if needed
                "✅ Active - $stats"
            } catch (e: Exception) {
                "❌ Error: ${e.message}"
            }
            diagnosticResults.add(
                DiagnosticResult(
                    name = "DNS Cache Server",
                    status = dnsCacheStatus,
                    details = "DNS caching system status"
                )
            )
            
            // Test 4: System Resources
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val totalMemGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            val availMemGB = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
            val usedMemGB = totalMemGB - availMemGB
            val memUsagePercent = (usedMemGB / totalMemGB) * 100.0
            
            val memStatus = if (memUsagePercent < 80) "✅ Healthy" else "⚠️ High Usage"
            diagnosticResults.add(
                DiagnosticResult(
                    name = "System Memory",
                    status = memStatus,
                    details = String.format(
                        "Used: %.2f GB / %.2f GB (%.1f%%)",
                        usedMemGB, totalMemGB, memUsagePercent
                    )
                )
            )
            
            // Build message
            val message = buildDiagnosticMessage(diagnosticResults)
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private data class DiagnosticResult(
        val name: String,
        val status: String,
        val details: String
    )
    
    private fun buildDiagnosticMessage(results: List<DiagnosticResult>): String {
        return buildString {
            appendLine("<b>🔍 SYSTEM DIAGNOSTIC</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            results.forEach { result ->
                appendLine("<b>${result.name}:</b> ${result.status}")
                appendLine("  ${result.details}")
                appendLine()
            }
            
            // Overall health
            val allHealthy = results.all { it.status.contains("✅") }
            val overallStatus = if (allHealthy) {
                "✅ <b>ALL SYSTEMS OPERATIONAL</b>"
            } else {
                "⚠️ <b>SOME ISSUES DETECTED</b>"
            }
            
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Overall Status: $overallStatus")
            appendLine()
            appendLine("💡 Use /help for more commands")
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

