package com.hyperxray.an.data.source

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.hyperxray.an.viewmodel.CoreStatsState
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

/**
 * Manages statistics file operations.
 * Saves and loads CoreStatsState to/from a JSON file.
 */
class StatFileManager(context: Context) {
    private val statFile: File
    private val gson = Gson()

    init {
        val filesDir = context.filesDir
        this.statFile = File(filesDir, STAT_FILE_NAME)
        Log.d(TAG, "Stat file path: ${statFile.absolutePath}")
    }

    @Synchronized
    fun saveStats(stats: CoreStatsState) {
        try {
            FileWriter(statFile, false).use { fileWriter ->
                gson.toJson(stats, fileWriter)
                fileWriter.flush()
            }
            Log.d(TAG, "Statistics saved successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Error saving statistics to file", e)
        }
    }

    fun loadStats(): CoreStatsState? {
        if (!statFile.exists()) {
            Log.d(TAG, "Stat file does not exist.")
            return null
        }
        try {
            FileReader(statFile).use { fileReader ->
                return gson.fromJson(fileReader, CoreStatsState::class.java)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading stat file", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing stat file", e)
            return null
        }
    }

    @Synchronized
    fun deleteStats() {
        if (statFile.exists()) {
            try {
                if (statFile.delete()) {
                    Log.d(TAG, "Stat file deleted successfully.")
                } else {
                    Log.w(TAG, "Failed to delete stat file.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting stat file", e)
            }
        } else {
            Log.d(TAG, "Stat file does not exist, no file to delete.")
        }
    }

    companion object {
        private const val TAG = "StatFileManager"
        private const val STAT_FILE_NAME = "stat.txt"
    }
}


