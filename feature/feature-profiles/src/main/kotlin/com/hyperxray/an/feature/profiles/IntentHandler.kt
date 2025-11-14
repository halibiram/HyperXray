package com.hyperxray.an.feature.profiles

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles intent processing for config sharing and import.
 * Moved from app module to feature-profiles to separate business logic.
 */
class IntentHandler(
    private val onContentReceived: suspend (String) -> Unit
) {
    private val TAG = "IntentHandler"
    private var lastProcessedIntentHash: Int = 0

    /**
     * Process share intent for config import.
     */
    fun processShareIntent(
        intent: Intent,
        contentResolver: android.content.ContentResolver,
        scope: CoroutineScope
    ) {
        val currentIntentHash = intent.hashCode()
        if (lastProcessedIntentHash == currentIntentHash) return
        lastProcessedIntentHash = currentIntentHash

        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.clipData?.getItemAt(0)?.uri?.let { uri ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                            text?.let { onContentReceived(it) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading shared file", e)
                        }
                    }
                }
            }

            Intent.ACTION_VIEW -> {
                intent.data?.toString()?.let { uriString ->
                    if (uriString.startsWith("hyperxray://")) {
                        scope.launch {
                            onContentReceived(uriString)
                        }
                    }
                }
            }
        }
    }
}

