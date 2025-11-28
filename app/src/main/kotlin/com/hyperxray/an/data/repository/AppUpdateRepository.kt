package com.hyperxray.an.data.repository

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.R
import com.hyperxray.an.core.network.NetworkModule
import com.hyperxray.an.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "AppUpdateRepository"

/**
 * Data class representing update information.
 */
data class UpdateInfo(
    val latestVersion: String,
    val isUpdateAvailable: Boolean
)

/**
 * Repository for managing app update checks and downloads.
 * Handles network calls and version comparison logic.
 */
class AppUpdateRepository(
    private val application: Application,
    private val prefs: Preferences
) {
    /**
     * Checks for available app updates by querying the release API.
     * 
     * @param isServiceEnabled Whether VPN service is enabled (for proxy usage)
     * @return Result containing UpdateInfo if successful, or failure with exception
     */
    suspend fun checkForUpdates(isServiceEnabled: Boolean): Result<UpdateInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val proxy = if (isServiceEnabled) {
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort))
                } else {
                    null
                }
                val client = NetworkModule.getHttpClientFactory().createHttpClient(proxy)

                val request = Request.Builder()
                    .url(application.getString(R.string.source_url) + "/releases/latest")
                    .head()
                    .build()

                val response = client.newCall(request).await()
                val location = response.request.url.toString()
                val latestTag = location.substringAfterLast("/tag/v")
                Log.d(TAG, "Latest version tag: $latestTag")
                
                val isUpdateAvailable = compareVersions(latestTag) > 0
                Result.success(UpdateInfo(latestVersion = latestTag, isUpdateAvailable = isUpdateAvailable))
            } catch (e: CancellationException) {
                Log.d(TAG, "Update check cancelled")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Opens the browser to download the new version.
     * 
     * @param versionTag Version tag to download (e.g., "1.0.0")
     */
    fun downloadNewVersion(versionTag: String) {
        val url = application.getString(R.string.source_url) + "/releases/tag/v$versionTag"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
    }

    /**
     * Compares two version strings.
     * 
     * @param version1 Version string to compare (e.g., "1.0.0")
     * @return Positive value if version1 > current version, negative if version1 < current version, 0 if equal
     */
    fun compareVersions(version1: String): Int {
        val parts1 = version1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = BuildConfig.VERSION_NAME.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    /**
     * Suspend extension function to await OkHttp Call response.
     */
    @ExperimentalCoroutinesApi
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(Result.success(response))
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWith(Result.failure(e))
            }
        })
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }
}

















