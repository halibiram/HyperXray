package com.hyperxray.an.service.xray

import java.io.File

/**
 * Configuration data class for Xray-core execution.
 * Contains all necessary information to start Xray-core.
 */
data class XrayConfig(
    val configFile: File,
    val configContent: String,
    val instanceCount: Int
)






