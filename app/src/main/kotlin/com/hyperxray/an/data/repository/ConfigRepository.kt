package com.hyperxray.an.data.repository

import android.app.Application
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.core.network.NetworkModule
import com.hyperxray.an.data.source.FileManager
import com.hyperxray.an.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for managing configuration files, rule files, and related operations.
 * Encapsulates all file I/O operations related to configs.
 */
class ConfigRepository(
    private val application: Application,
    private val prefs: Preferences
) {
    private val fileManager: FileManager = FileManager(application, prefs)
    
    // Coroutine scope for download operations
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()
    
    // Download progress flows
    private val _geoipDownloadProgress = MutableStateFlow<String?>(null)
    val geoipDownloadProgress: StateFlow<String?> = _geoipDownloadProgress.asStateFlow()
    
    private val _geositeDownloadProgress = MutableStateFlow<String?>(null)
    val geositeDownloadProgress: StateFlow<String?> = _geositeDownloadProgress.asStateFlow()
    
    // Download jobs for cancellation
    private var geoipDownloadJob: Job? = null
    private var geositeDownloadJob: Job? = null

    /**
     * Load all config files from the files directory.
     * Maintains the order from preferences and updates selected config.
     */
    suspend fun loadConfigs() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "loadConfigs() called")
            AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: Starting config files loading")
            try {
                val filesDir = application.filesDir
                Log.d(TAG, "Loading configs from: ${filesDir.absolutePath}")
                AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: Loading from directory: ${filesDir.absolutePath}")
                
                val actualFiles =
                    filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                        ?: emptyList()
                Log.d(TAG, "Found ${actualFiles.size} config files in directory")
                AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: Found ${actualFiles.size} config files: ${actualFiles.map { it.name }}")
                
                val actualFilesByName = actualFiles.associateBy { it.name }
                val savedOrder = prefs.configFilesOrder
                Log.d(TAG, "Saved order has ${savedOrder.size} files")
                AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: Saved order has ${savedOrder.size} files: $savedOrder")

                val newOrder = mutableListOf<File>()
                val remainingActualFileNames = actualFilesByName.toMutableMap()

                savedOrder.forEach { filename ->
                    actualFilesByName[filename]?.let { file ->
                        newOrder.add(file)
                        remainingActualFileNames.remove(filename)
                    }
                }

                newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })
                Log.d(TAG, "Final config order has ${newOrder.size} files: ${newOrder.map { it.name }}")
                AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: Final order has ${newOrder.size} files: ${newOrder.map { it.name }}")

                _configFiles.value = newOrder
                prefs.configFilesOrder = newOrder.map { it.name }
                Log.d(TAG, "Config files StateFlow updated with ${newOrder.size} files")
                AiLogHelper.i(TAG, "✅ LOAD CONFIGS: StateFlow updated with ${newOrder.size} files")

                val currentSelectedPath = prefs.selectedConfigPath
                var fileToSelect: File? = null

                if (currentSelectedPath != null) {
                    Log.d(TAG, "Looking for selected config: $currentSelectedPath")
                    AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: Looking for selected config: $currentSelectedPath")
                    val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                    if (foundSelected != null) {
                        fileToSelect = foundSelected
                        Log.d(TAG, "Found selected config: ${foundSelected.name}")
                        AiLogHelper.d(TAG, "✅ LOAD CONFIGS: Found selected config: ${foundSelected.name}")
                    } else {
                        Log.w(TAG, "Selected config path not found in current files: $currentSelectedPath")
                        AiLogHelper.w(TAG, "⚠️ LOAD CONFIGS: Selected config path not found: $currentSelectedPath")
                    }
                }

                if (fileToSelect == null) {
                    fileToSelect = newOrder.firstOrNull()
                    Log.d(TAG, "No selected config found, using first file: ${fileToSelect?.name}")
                    AiLogHelper.d(TAG, "🔄 LOAD CONFIGS: No selected config, using first: ${fileToSelect?.name}")
                }

                _selectedConfigFile.value = fileToSelect
                prefs.selectedConfigPath = fileToSelect?.absolutePath
                Log.d(TAG, "Selected config file set to: ${fileToSelect?.name}")
                AiLogHelper.i(TAG, "✅ LOAD CONFIGS: Selected config set to: ${fileToSelect?.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadConfigs()", e)
                AiLogHelper.e(TAG, "❌ LOAD CONFIGS ERROR: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Update the selected config file.
     */
    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        prefs.selectedConfigPath = file?.absolutePath
    }

    /**
     * Create a new config file from template or empty.
     */
    suspend fun createConfigFile(assets: AssetManager): String? {
        return fileManager.createConfigFile(assets)
    }

    /**
     * Import config from clipboard.
     */
    suspend fun importConfigFromClipboard(): String? {
        return fileManager.importConfigFromClipboard()
    }

    /**
     * Import config from content string.
     */
    suspend fun importConfigFromContent(content: String): String? {
        return fileManager.importConfigFromContent(content)
    }

    /**
     * Delete a config file.
     */
    suspend fun deleteConfigFile(file: File): Boolean {
        return fileManager.deleteConfigFile(file)
    }

    /**
     * Rename a config file.
     */
    suspend fun renameConfigFile(oldFile: File, newFile: File, newContent: String): Boolean {
        return fileManager.renameConfigFile(oldFile, newFile, newContent)
    }

    /**
     * Extract assets (geoip.dat, geosite.dat) if needed.
     */
    fun extractAssetsIfNeeded() {
        fileManager.extractAssetsIfNeeded()
    }

    /**
     * Import a rule file (geoip.dat or geosite.dat) from URI.
     */
    suspend fun importRuleFile(uri: Uri, filename: String): Boolean {
        return fileManager.importRuleFile(uri, filename)
    }

    /**
     * Save a rule file from input stream.
     */
    suspend fun saveRuleFile(
        inputStream: InputStream,
        filename: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return fileManager.saveRuleFile(inputStream, filename, onProgress)
    }

    /**
     * Get rule file summary (geoip.dat or geosite.dat).
     */
    fun getRuleFileSummary(filename: String): String {
        return fileManager.getRuleFileSummary(filename)
    }

    /**
     * Restore default geoip.dat from assets.
     */
    suspend fun restoreDefaultGeoip(): Boolean {
        return fileManager.restoreDefaultGeoip()
    }

    /**
     * Restore default geosite.dat from assets.
     */
    suspend fun restoreDefaultGeosite(): Boolean {
        return fileManager.restoreDefaultGeosite()
    }
    
    /**
     * Download a rule file (geoip.dat or geosite.dat) from URL.
     * 
     * @param url URL to download from
     * @param fileName Name of the file ("geoip.dat" or "geosite.dat")
     * @param isServiceEnabled Whether VPN service is enabled (for proxy usage)
     * @param socksPort SOCKS5 proxy port (if service is enabled)
     * @return Result indicating success or failure with message
     */
    suspend fun downloadRuleFile(
        url: String,
        fileName: String,
        isServiceEnabled: Boolean,
        socksPort: Int
    ): Result<Unit> {
        val currentJob = if (fileName == "geoip.dat") geoipDownloadJob else geositeDownloadJob
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress for $fileName")
            return Result.failure(IllegalStateException("Download already in progress"))
        }

        val progressFlow = if (fileName == "geoip.dat") {
            prefs.geoipUrl = url
            _geoipDownloadProgress
        } else {
            prefs.geositeUrl = url
            _geositeDownloadProgress
        }

        // Track if download was successful
        var downloadError: Exception? = null
        
        val job = downloadScope.launch {
            val proxy = if (isServiceEnabled) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            } else {
                null
            }
            val client = NetworkModule.getHttpClientFactory().create(proxy)

            try {
                progressFlow.value = application.getString(com.hyperxray.an.R.string.connecting)

                val request = Request.Builder().url(url).build()
                val call = client.newCall(request)
                val response = call.await()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download file: ${response.code}")
                }

                val body = response.body ?: throw IOException("Response body is null")
                val totalBytes = body.contentLength()
                var bytesRead = 0L
                var lastProgress = -1

                body.byteStream().use { inputStream ->
                    val success = saveRuleFile(inputStream, fileName) { read ->
                        ensureActive()
                        bytesRead += read
                        if (totalBytes > 0) {
                            val progress = (bytesRead * 100 / totalBytes).toInt()
                            if (progress != lastProgress) {
                                progressFlow.value =
                                    application.getString(com.hyperxray.an.R.string.downloading, progress)
                                lastProgress = progress
                            }
                        } else {
                            if (lastProgress == -1) {
                                progressFlow.value =
                                    application.getString(com.hyperxray.an.R.string.downloading_no_size)
                                lastProgress = 0
                            }
                        }
                    }
                    if (!success) {
                        throw IOException("Failed to save rule file")
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled for $fileName")
                progressFlow.value = null
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Download timeout for $fileName: ${e.message}", e)
                progressFlow.value = application.getString(com.hyperxray.an.R.string.download_timeout)
                downloadError = e
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection failed for $fileName: ${e.message}", e)
                progressFlow.value = application.getString(com.hyperxray.an.R.string.connection_failed)
                downloadError = e
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "DNS resolution failed for $fileName: ${e.message}", e)
                progressFlow.value = application.getString(com.hyperxray.an.R.string.dns_failed)
                downloadError = e
            } catch (e: IOException) {
                Log.e(TAG, "IO error downloading $fileName: ${e.message}", e)
                progressFlow.value = application.getString(com.hyperxray.an.R.string.download_failed)
                downloadError = e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download rule file $fileName", e)
                progressFlow.value = application.getString(com.hyperxray.an.R.string.download_failed)
                downloadError = e
            }
        }

        if (fileName == "geoip.dat") {
            geoipDownloadJob = job
        } else {
            geositeDownloadJob = job
        }

        job.invokeOnCompletion { throwable ->
            // Clear progress after a delay so user can see the error message
            val hasError = throwable != null || downloadError != null
            if (hasError && throwable !is CancellationException) {
                downloadScope.launch {
                    kotlinx.coroutines.delay(3000) // Show error for 3 seconds
                    progressFlow.value = null
                }
            } else if (!hasError) {
                // Success - clear immediately
                progressFlow.value = null
            }
            
            if (fileName == "geoip.dat") {
                geoipDownloadJob = null
            } else {
                geositeDownloadJob = null
            }
        }

        return try {
            job.join()
            when {
                job.isCancelled -> Result.failure(CancellationException("Download cancelled"))
                downloadError != null -> Result.failure(downloadError!!)
                else -> Result.success(Unit)
            }
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            // This shouldn't happen since we catch all exceptions inside the job
            Log.e(TAG, "Unexpected error in downloadRuleFile", e)
            Result.failure(e)
        }
    }
    
    /**
     * Cancel an ongoing download.
     * 
     * @param fileName Name of the file to cancel download for ("geoip.dat" or "geosite.dat")
     */
    fun cancelDownload(fileName: String) {
        if (fileName == "geoip.dat") {
            geoipDownloadJob?.cancel()
        } else {
            geositeDownloadJob?.cancel()
        }
        Log.d(TAG, "Download cancellation requested for $fileName")
    }
    
    /**
     * Suspend extension function to await OkHttp Call response.
     */
    @kotlinx.coroutines.ExperimentalCoroutinesApi
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
    
    /**
     * Cleanup resources when repository is no longer needed.
     */
    fun cleanup() {
        downloadScope.cancel()
    }

    /**
     * Compress backup data (preferences + config files).
     */
    suspend fun compressBackupData(): ByteArray? {
        return fileManager.compressBackupData()
    }

    /**
     * Create backup file by compressing data and writing to URI.
     * All I/O operations are performed on Dispatchers.IO.
     * 
     * @param uri Destination URI for the backup file
     * @return Result indicating success or failure
     */
    suspend fun createBackup(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val compressedData = fileManager.compressBackupData()
                    ?: return@withContext Result.failure(IOException("Failed to compress backup data"))
                
                application.contentResolver.openOutputStream(uri).use { os ->
                    if (os == null) {
                        Log.e(TAG, "Failed to open output stream for backup URI: $uri")
                        return@withContext Result.failure(IOException("Failed to open output stream for backup URI"))
                    }
                    os.write(compressedData)
                    Log.d(TAG, "Backup successful to: $uri")
                    Result.success(Unit)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing backup data to URI: $uri", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during backup creation", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Decompress and restore backup data from URI.
     */
    suspend fun decompressAndRestore(uri: Uri): Boolean {
        return fileManager.decompressAndRestore(uri)
    }

    /**
     * Move config file in the list (for reordering).
     */
    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        prefs.configFilesOrder = currentList.map { it.name }
    }
    
    /**
     * Clear cached StateFlow values to force reload from disk.
     * This is useful when config files might have changed externally
     * or when stale cache is causing issues.
     */
    fun clearCache() {
        Log.d(TAG, "Clearing ConfigRepository cache")
        _configFiles.value = emptyList()
        _selectedConfigFile.value = null
        AiLogHelper.d(TAG, "🧹 CACHE CLEAR: ConfigRepository cache cleared")
    }
    
    /**
     * Force reload configs from disk, clearing cache first.
     * This ensures we get the latest config files and selected config.
     */
    suspend fun forceReloadConfigs() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Force reloading configs from disk")
            AiLogHelper.d(TAG, "🔄 FORCE RELOAD: Clearing cache and reloading configs")
            clearCache()
            loadConfigs()
            AiLogHelper.i(TAG, "✅ FORCE RELOAD: Configs reloaded from disk")
        }
    }

    companion object {
        private const val TAG = "ConfigRepository"
    }
}

