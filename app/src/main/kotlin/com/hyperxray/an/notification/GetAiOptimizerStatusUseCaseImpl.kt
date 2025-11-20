package com.hyperxray.an.notification

import android.content.Context
import com.hyperxray.an.AppInitializer
import com.hyperxray.an.HyperXrayApplication
import com.hyperxray.an.feature.telegram.domain.usecase.GetAiOptimizerStatusUseCase
import com.hyperxray.an.telemetry.OptimizerOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetAiOptimizerStatusUseCase
 */
class GetAiOptimizerStatusUseCaseImpl(
    private val context: Context
) : GetAiOptimizerStatusUseCase {
    override suspend fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as? HyperXrayApplication
            val appInitializer = app?.getAppInitializer()
            
            // Get OptimizerOrchestrator and DeepPolicyModel from AppInitializer
            val optimizer = appInitializer?.getOptimizerOrchestrator()
            val deepModel = appInitializer?.getDeepPolicyModel()
            
            val message = buildAiStatusMessage(optimizer, deepModel)
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun buildAiStatusMessage(
        optimizer: OptimizerOrchestrator?,
        deepModel: com.hyperxray.an.telemetry.DeepPolicyModel?
    ): String {
        return buildString {
            appendLine("<b>🤖 AI OPTIMIZER STATUS</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            // OptimizerOrchestrator Status
            if (optimizer != null) {
                appendLine("<b>🔄 OptimizerOrchestrator:</b>")
                appendLine("Status: <b>✅ Active</b>")
                appendLine("Total Cycles: <b>${optimizer.getCycleCount()}</b>")
                
                val rewardHistory = optimizer.getRewardHistory()
                if (rewardHistory.isNotEmpty()) {
                    val avgReward = rewardHistory.average()
                    val recentAvg = optimizer.getAverageReward(10) ?: 0.0
                    val trending = optimizer.isRewardTrendingUpward()
                    val trendEmoji = if (trending) "📈" else "📉"
                    
                    appendLine("Total Rewards: <b>${rewardHistory.size}</b>")
                    appendLine("Avg Reward (all): <b>${String.format("%.2f", avgReward)}</b>")
                    appendLine("Avg Reward (last 10): <b>${String.format("%.2f", recentAvg)}</b>")
                    appendLine("Trend: <b>$trendEmoji ${if (trending) "Upward" else "Downward"}</b>")
                    
                    if (rewardHistory.size >= 2) {
                        appendLine("Last Reward: <b>${String.format("%.2f", rewardHistory.last())}</b>")
                    }
                } else {
                    appendLine("Rewards: <b>No data yet</b>")
                }
                appendLine()
            } else {
                appendLine("<b>🔄 OptimizerOrchestrator:</b>")
                appendLine("Status: <b>❌ Not Initialized</b>")
                appendLine()
            }
            
            // DeepPolicyModel Status
            if (deepModel != null) {
                appendLine("<b>🧠 DeepPolicyModel:</b>")
                appendLine("Status: <b>✅ Active</b>")
                
                val isLoaded = deepModel.isModelLoaded()
                val isVerified = deepModel.isModelVerified()
                val activeEPs = deepModel.getActiveExecutionProviders()
                
                appendLine("Model Loaded: <b>${if (isLoaded) "✅ Yes" else "❌ No"}</b>")
                appendLine("Model Verified: <b>${if (isVerified) "✅ Yes" else "❌ No"}</b>")
                
                if (activeEPs.isNotEmpty()) {
                    appendLine("Execution Providers:")
                    activeEPs.forEach { ep ->
                        val epIcon = when {
                            ep.contains("NNAPI", ignoreCase = true) -> "📱"
                            ep.contains("GPU", ignoreCase = true) -> "🎮"
                            ep.contains("CPU", ignoreCase = true) -> "💻"
                            else -> "⚙️"
                        }
                        appendLine("  $epIcon <b>$ep</b>")
                    }
                } else {
                    appendLine("Execution Providers: <b>None</b>")
                }
                appendLine()
            } else {
                appendLine("<b>🧠 DeepPolicyModel:</b>")
                appendLine("Status: <b>❌ Not Initialized</b>")
                appendLine()
            }
            
            // RealityBandit Status (if optimizer is available)
            if (optimizer != null) {
                try {
                    val bandit = optimizer.getBandit()
                    val arms = bandit.getAllArms()
                    val totalSelections = bandit.getTotalSelections()
                    
                    appendLine("<b>🎰 RealityBandit:</b>")
                    appendLine("Status: <b>✅ Active</b>")
                    appendLine("Available Arms: <b>${arms.size}</b>")
                    appendLine("Total Selections: <b>$totalSelections</b>")
                    
                    if (arms.isNotEmpty()) {
                        appendLine()
                        appendLine("<b>Top Arms (by avg reward):</b>")
                        val sortedArms = arms.sortedByDescending { it.averageReward }.take(5)
                        sortedArms.forEachIndexed { index, arm ->
                            val rankEmoji = when (index) {
                                0 -> "🥇"
                                1 -> "🥈"
                                2 -> "🥉"
                                else -> "   "
                            }
                            appendLine("$rankEmoji <b>${arm.armId}:</b> reward=${String.format("%.2f", arm.averageReward)}, pulls=${arm.pullCount}")
                        }
                    }
                } catch (e: Exception) {
                    // Failed to get bandit status, skip
                }
            }
            
            // Overall Status
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            val overallStatus = if (optimizer != null && deepModel != null && deepModel.isModelLoaded()) {
                "✅ <b>FULLY OPERATIONAL</b>"
            } else if (optimizer != null || deepModel != null) {
                "⚠️ <b>PARTIALLY OPERATIONAL</b>"
            } else {
                "❌ <b>NOT OPERATIONAL</b>"
            }
            appendLine("Overall Status: $overallStatus")
        }
    }
}

