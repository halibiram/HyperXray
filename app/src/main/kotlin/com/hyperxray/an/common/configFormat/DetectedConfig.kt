package com.hyperxray.an.common.configFormat

/**
 * Detected configuration data.
 * Contains config name, content, and optional metadata like enableWarp flag.
 */
data class DetectedConfig(
    val name: String,
    val content: String,
    val enableWarp: Boolean = false
) {
    /**
     * Backward compatibility: Convert Pair to DetectedConfig
     */
    constructor(pair: Pair<String, String>) : this(pair.first, pair.second, false)
    
    /**
     * Backward compatibility: Convert DetectedConfig to Pair
     */
    fun toPair(): Pair<String, String> = Pair(name, content)
}
