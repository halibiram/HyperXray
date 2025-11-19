package org.bugs4u.proxyserver.core;

/**
 * Dummy Tun2Socks class for HTTP Custom native libraries compatibility
 * 
 * This class is required by libtun2socks.so which expects to find
 * org.bugs4u.proxyserver.core.Tun2Socks class for JNI callbacks.
 */
public class Tun2Socks {
    
    /**
     * Callback method for native library to send logs
     * Called from native code: libtun2socks.so
     * 
     * @param level Log level (ERROR, WARNING, INFO, DEBUG)
     * @param tag Log tag
     * @param message Log message
     */
    public static void logTun2Socks(String level, String tag, String message) {
        // Log to Android Logcat
        android.util.Log.d("Bugs4UTun2Socks", String.format("[%s] %s: %s", level, tag, message));
    }
}




