package ca.psiphon;

/**
 * libsocks2tun.so JNI Wrapper
 * Based on decompiled HTTP Custom PsiphonTunnel.java
 * 
 * This library uses Psiphon Tunnel (BadVPN tun2socks 1.999.128 based)
 * 
 * Actual signature from decompiled code:
 * - runTun2Socks: (int, int, String, String, String, String, int) -> int
 * - terminateTun2Socks: () -> int
 * - enableUdpGwKeepalive: () -> int
 * - disableUdpGwKeepalive: () -> int
 */
public class PsiphonTunnel {
    static {
        System.loadLibrary("socks2tun");
    }
    
    /**
     * Starts tun2socks tunnel
     * 
     * @param tunFd VPN interface file descriptor
     * @param mtu Maximum Transmission Unit (typically 1500)
     * @param router Virtual network interface IP (e.g., "10.0.0.2")
     * @param netmask Subnet mask (e.g., "255.255.255.0")
     * @param socksServer Local SOCKS proxy address (e.g., "127.0.0.1:1080")
     * @param udpgwAddr UDP Gateway server address (e.g., "127.0.0.1:7300")
     * @param udpgwTransparent UDP transparent mode (0 = false, 1 = true)
     * @return 0 on success, non-zero on error
     */
    public static native int runTun2Socks(
        int tunFd,
        int mtu,
        String router,
        String netmask,
        String socksServer,
        String udpgwAddr,
        int udpgwTransparent
    );
    
    /**
     * Stops tun2socks tunnel
     * @return 0 on success, non-zero on error
     */
    private static native int terminateTun2Socks();
    
    /**
     * Enables UDP Gateway keepalive
     * @return 0 on success, non-zero on error
     */
    private static native int enableUdpGwKeepalive();
    
    /**
     * Disables UDP Gateway keepalive
     * @return 0 on success, non-zero on error
     */
    private static native int disableUdpGwKeepalive();
    
    /**
     * Log callback from native code
     * Called by native code to send logs to Java
     * Signature from binary: logTun2Socks(String level, String tag, String message)
     */
    public static void logTun2Socks(String level, String tag, String message) {
        android.util.Log.d("PsiphonTunnel", String.format("[%s] %s: %s", level, tag, message));
    }
}
