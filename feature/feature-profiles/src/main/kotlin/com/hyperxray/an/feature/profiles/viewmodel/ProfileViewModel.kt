package com.hyperxray.an.feature.profiles.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for profile list management.
 * Handles config file list operations, selection, and reordering.
 */
class ProfileViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    // Create Preferences internally to avoid circular dependency
    // Uses reflection to access Preferences class from app module at runtime
    private val prefs: Any by lazy {
        try {
            val app = getApplication<Application>()
            val prefsClass = Class.forName("com.hyperxray.an.prefs.Preferences")
            prefsClass.getConstructor(Application::class.java).newInstance(app)
        } catch (e: Exception) {
            throw RuntimeException("Failed to create Preferences instance", e)
        }
    }
    
    // Helper functions to access Preferences properties via reflection
    private fun getConfigFilesOrder(): List<String> {
        return try {
            val method = prefs.javaClass.getMethod("getConfigFilesOrder")
            @Suppress("UNCHECKED_CAST")
            method.invoke(prefs) as? List<String> ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun setConfigFilesOrder(order: List<String>) {
        try {
            val method = prefs.javaClass.getMethod("setConfigFilesOrder", List::class.java)
            method.invoke(prefs, order)
        } catch (e: Exception) {
            // Ignore if Preferences not available
        }
    }
    
    private fun getSelectedConfigPath(): String? {
        return try {
            val method = prefs.javaClass.getMethod("getSelectedConfigPath")
            method.invoke(prefs) as? String?
        } catch (e: Exception) {
            null
        }
    }
    
    private fun setSelectedConfigPath(path: String?) {
        try {
            val method = prefs.javaClass.getMethod("setSelectedConfigPath", String::class.java)
            method.invoke(prefs, path)
        } catch (e: Exception) {
            // Ignore if Preferences not available
        }
    }

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()

    init {
        refreshConfigFileList()
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        setConfigFilesOrder(currentList.map { it.name })
    }

    fun refreshConfigFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val filesDir = app.filesDir
            val actualFiles =
                filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                    ?: emptyList()
            val actualFilesByName = actualFiles.associateBy { it.name }
            val savedOrder = getConfigFilesOrder()

            val newOrder = mutableListOf<File>()
            val remainingActualFileNames = actualFilesByName.toMutableMap()

            savedOrder.forEach { filename ->
                actualFilesByName[filename]?.let { file ->
                    newOrder.add(file)
                    remainingActualFileNames.remove(filename)
                }
            }

            newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })

            _configFiles.value = newOrder
            setConfigFilesOrder(newOrder.map { it.name })

            val currentSelectedPath = getSelectedConfigPath()
            var fileToSelect: File? = null

            if (currentSelectedPath != null) {
                val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                if (foundSelected != null) {
                    fileToSelect = foundSelected
                }
            }

            if (fileToSelect == null) {
                fileToSelect = newOrder.firstOrNull()
            }

            _selectedConfigFile.value = fileToSelect
            setSelectedConfigPath(fileToSelect?.absolutePath)
        }
    }

    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        setSelectedConfigPath(file?.absolutePath)
    }
}

