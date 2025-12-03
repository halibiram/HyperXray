package com.hyperxray.an.common

/**
 * TunnelMode defines the available tunneling modes for the VPN.
 * 
 * - WIREGUARD: Uses WireGuard protocol over Xray (UDP-based, low latency)
 * - MASQUE: Uses MASQUE protocol over Xray (HTTP/3-based, high censorship resistance)
 */
enum class TunnelMode(val displayName: String, val description: String) {
    WIREGUARD(
        displayName = "WireGuard over Xray",
        description = "UDP tabanlı, düşük gecikme"
    ),
    MASQUE(
        displayName = "MASQUE over Xray", 
        description = "HTTP/3 tabanlı, yüksek sansür direnci"
    );

    companion object {
        /**
         * Returns the default tunnel mode.
         */
        fun default(): TunnelMode = WIREGUARD

        /**
         * Parses a string to TunnelMode, returning default if invalid.
         */
        fun fromString(value: String?): TunnelMode {
            return when (value?.lowercase()) {
                "masque" -> MASQUE
                "wireguard" -> WIREGUARD
                else -> default()
            }
        }
    }
}
