package ca.psiphon;

import android.util.Log;

/**
 * PsiphonTunnel JNI wrapper for libsocks2tun.so
 * 
 * This class provides the JNI interface expected by HTTP Custom's libsocks2tun.so
 * Based on decompiled HTTP Custom PsiphonTunnel.java
 */
public class PsiphonTunnel {
    private static final String TAG = "PsiphonTunnel";
    
    static {
        try {
            System.loadLibrary("socks2tun");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libsocks2tun.so", e);
        }
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
     * Called by native code (libsocks2tun.so) to send logs to Java
     * 
     * @param level Log level (e.g., "INFO", "ERROR", "DEBUG")
     * @param tag Log tag
     * @param message Log message
     */
    public static void logTun2Socks(String level, String tag, String message) {
        // Forward logs to Android Log
        int logLevel;
        if (level == null) {
            logLevel = Log.DEBUG;
        } else {
            switch (level.toUpperCase()) {
                case "ERROR":
                case "ERR":
                    logLevel = Log.ERROR;
                    break;
                case "WARN":
                case "WARNING":
                    logLevel = Log.WARN;
                    break;
                case "INFO":
                    logLevel = Log.INFO;
                    break;
                case "DEBUG":
                default:
                    logLevel = Log.DEBUG;
                    break;
            }
        }
        
        String logTag = (tag != null && !tag.isEmpty()) ? TAG + "/" + tag : TAG;
        Log.println(logLevel, logTag, message);
    }
}
