package com.hyperxray.an.feature.dashboard

/**
 * Represents the connection state of the VPN service.
 * Includes detailed connection stages for better user feedback.
 */
sealed class ConnectionState {
    /**
     * Service is disconnected and idle
     */
    object Disconnected : ConnectionState()
    
    /**
     * Service is in the process of connecting.
     * Contains information about the current connection stage.
     */
    data class Connecting(
        val stage: ConnectionStage,
        val progress: Float = 0f // 0.0 to 1.0
    ) : ConnectionState()
    
    /**
     * Service is successfully connected
     */
    object Connected : ConnectionState()
    
    /**
     * Service is in the process of disconnecting.
     * Contains information about the current disconnection stage.
     */
    data class Disconnecting(
        val stage: DisconnectionStage,
        val progress: Float = 0f // 0.0 to 1.0
    ) : ConnectionState()

    /**
     * Connection failed with an error message
     */
    data class Failed(
        val error: String
    ) : ConnectionState()
}

/**
 * Represents different stages during the connection process.
 */
enum class ConnectionStage(
    val displayName: String,
    val description: String
) {
    INITIALIZING("Initializing", "Preparing connection..."),
    RECONNECTING("Reconnecting", "Retrying connection..."),
    STARTING_VPN("Starting VPN", "Setting up VPN interface..."),
    STARTING_XRAY("Starting Xray", "Launching Xray core..."),
    ESTABLISHING("Establishing", "Connecting to server..."),
    VERIFYING("Verifying", "Verifying connection..."),
    CONNECTED("Connected", "Connection established!")
}

/**
 * Represents different stages during the disconnection process.
 */
enum class DisconnectionStage(
    val displayName: String,
    val description: String
) {
    STOPPING_XRAY("Stopping Xray", "Shutting down Xray core..."),
    CLOSING_TUNNEL("Closing Tunnel", "Terminating network tunnel..."),
    STOPPING_VPN("Stopping VPN", "Closing VPN interface..."),
    CLEANING_UP("Cleaning Up", "Releasing resources..."),
    DISCONNECTED("Disconnected", "Disconnection complete!")
}

