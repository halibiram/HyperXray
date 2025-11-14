package com.hyperxray.an.xray.runtime

/**
 * Represents the runtime status of the Xray-core binary.
 * 
 * This sealed class provides type-safe status events that can be observed
 * through Flow APIs.
 */
sealed class XrayRuntimeStatus {
    /**
     * Xray-core is not running.
     */
    object Stopped : XrayRuntimeStatus() {
        override fun toString() = "Stopped"
    }

    /**
     * Xray-core is starting up.
     */
    object Starting : XrayRuntimeStatus() {
        override fun toString() = "Starting"
    }

    /**
     * Xray-core is running successfully.
     * 
     * @param processId The process ID of the running Xray-core instance
     * @param apiPort The API port number for gRPC communication
     */
    data class Running(
        val processId: Long,
        val apiPort: Int
    ) : XrayRuntimeStatus() {
        override fun toString() = "Running(pid=$processId, apiPort=$apiPort)"
    }

    /**
     * Xray-core is stopping.
     */
    object Stopping : XrayRuntimeStatus() {
        override fun toString() = "Stopping"
    }

    /**
     * Xray-core encountered an error.
     * 
     * @param message Error message describing what went wrong
     * @param throwable Optional exception that caused the error
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : XrayRuntimeStatus() {
        override fun toString() = "Error(message=$message)"
    }

    /**
     * Xray-core process exited unexpectedly.
     * 
     * @param exitCode The exit code of the process
     * @param message Optional message describing the exit
     */
    data class ProcessExited(
        val exitCode: Int,
        val message: String? = null
    ) : XrayRuntimeStatus() {
        override fun toString() = "ProcessExited(exitCode=$exitCode, message=$message)"
    }
}

