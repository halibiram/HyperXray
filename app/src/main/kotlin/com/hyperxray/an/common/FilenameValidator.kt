package com.hyperxray.an.common

import android.content.Context
import com.hyperxray.an.R
import java.util.regex.Pattern

/**
 * Validates filename strings to ensure they don't contain invalid characters.
 * Used for config file naming validation.
 */
object FilenameValidator {
    private const val INVALID_CHARS_PATTERN = "[\\\\/:*?\"<>|]"

    fun validateFilename(context: Context, name: String): String? {
        val trimmedName = name.trim()
        return when {
            trimmedName.isEmpty() -> context.getString(R.string.filename_empty)
            !isValidFilenameChars(trimmedName) -> context.getString(R.string.filename_invalid)
            else -> null
        }
    }

    private fun isValidFilenameChars(filename: String): Boolean {
        return !Pattern.compile(INVALID_CHARS_PATTERN).matcher(filename).find()
    }
}