/*
 * JNI Wrapper for HyperXray Go Library
 * 
 * This file provides JNI-compatible wrappers for Go-exported C functions.
 * Go exports functions as C functions (e.g., StartHyperTunnel), but JNI
 * requires Java_* prefixed functions. This wrapper bridges the gap.
 * 
 * Uses dlopen/dlsym to dynamically load Go library functions at runtime.
 * 
 * OPTIMIZATION: Log messages are buffered and flushed in batches to reduce
 * JNI overhead. See log_buffer.h for details.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <dlfcn.h>
#include <unistd.h>  // For usleep() in retry mechanism
#include <errno.h>   // For errno in verification
#include <sys/socket.h>  // For socket() in verification
#include <netinet/in.h>  // For AF_INET
#include <android/log.h>

// Log buffering for reduced JNI overhead
#include "log_buffer.h"

#define LOG_TAG "HyperXray-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Function pointer types
typedef int (*StartHyperTunnelFunc)(int, const char*, const char*, const char*, const char*, const char*, const char*, const char*, const char*);
typedef int (*StopHyperTunnelFunc)(void);
typedef char* (*GetTunnelStatsFunc)(void);
typedef long long (*GetHandshakeRTTFunc)(void);
typedef char* (*GetLastErrorFunc)(void);
typedef char* (*NativeGeneratePublicKeyFunc)(const char*);
typedef void (*FreeStringFunc)(char*);
typedef bool (*SocketProtectorFunc)(int);
typedef void (*SetSocketProtectorFunc)(SocketProtectorFunc);
typedef void (*ClearSocketProtectorFunc)(void);
typedef void (*ResetSocketProtectorStateFunc)(void);

// REMOVED: Multi-instance function pointer types
// These have been removed as part of architectural cleanup

// DNS function pointer types
typedef int (*InitDNSCacheFunc)(const char*);
typedef char* (*DNSCacheLookupFunc)(const char*);
typedef char* (*DNSCacheLookupAllFunc)(const char*);
typedef void (*DNSCacheSaveFunc)(const char*, const char*, long);
typedef char* (*DNSCacheGetMetricsFunc)(void);
typedef void (*DNSCacheClearFunc)(void);
typedef int (*DNSCacheCleanupExpiredFunc)(void);
typedef int (*StartDNSServerFunc)(int, const char*);
typedef int (*StopDNSServerFunc)(void);
typedef int (*IsDNSServerRunningFunc)(void);
typedef int (*GetDNSServerPortFunc)(void);
typedef char* (*GetDNSServerStatsFunc)(void);
typedef char* (*DNSResolveFunc)(const char*);

// Xray gRPC stats function pointer types
typedef bool (*IsXrayGrpcAvailableFunc)(void);
typedef char* (*GetXraySystemStatsFunc)(void);
typedef char* (*GetXrayTrafficStatsFunc)(void);
typedef char* (*GetXrayLogsFunc)(int);

// Global handle to Go library
static void* goLibHandle = NULL;
static int goLibraryLoaded = 0;

// Function pointers
static StartHyperTunnelFunc go_StartHyperTunnel = NULL;
static StopHyperTunnelFunc go_StopHyperTunnel = NULL;
static GetTunnelStatsFunc go_GetTunnelStats = NULL;
static GetHandshakeRTTFunc go_GetHandshakeRTT = NULL;
static GetLastErrorFunc go_GetLastError = NULL;
static NativeGeneratePublicKeyFunc go_NativeGeneratePublicKey = NULL;
static FreeStringFunc go_FreeString = NULL;
static SetSocketProtectorFunc go_SetSocketProtector = NULL;
static ClearSocketProtectorFunc go_ClearSocketProtector = NULL;
static ResetSocketProtectorStateFunc go_ResetSocketProtectorState = NULL;

// REMOVED: Multi-instance function pointers
// These have been removed as part of architectural cleanup

// DNS function pointers
static InitDNSCacheFunc go_InitDNSCache = NULL;
static DNSCacheLookupFunc go_DNSCacheLookup = NULL;
static DNSCacheLookupAllFunc go_DNSCacheLookupAll = NULL;
static DNSCacheSaveFunc go_DNSCacheSave = NULL;
static DNSCacheGetMetricsFunc go_DNSCacheGetMetrics = NULL;
static DNSCacheClearFunc go_DNSCacheClear = NULL;
static DNSCacheCleanupExpiredFunc go_DNSCacheCleanupExpired = NULL;
static StartDNSServerFunc go_StartDNSServer = NULL;
static StopDNSServerFunc go_StopDNSServer = NULL;
static IsDNSServerRunningFunc go_IsDNSServerRunning = NULL;
static GetDNSServerPortFunc go_GetDNSServerPort = NULL;
static GetDNSServerStatsFunc go_GetDNSServerStats = NULL;
static DNSResolveFunc go_DNSResolve = NULL;

// Xray gRPC stats function pointers
static IsXrayGrpcAvailableFunc go_IsXrayGrpcAvailable = NULL;
static GetXraySystemStatsFunc go_GetXraySystemStats = NULL;
static GetXrayTrafficStatsFunc go_GetXrayTrafficStats = NULL;
static GetXrayLogsFunc go_GetXrayLogs = NULL;

// Forward declarations
static void register_c_socket_creator(void);


// Socket protector function type (must match Go's socket_protector_func)
typedef bool (*socket_protector_func)(int fd);

/**
 * SocketProtectorState - Enhanced socket protector with retry mechanism
 * Tracks JNI references and retry configuration for robust socket protection
 */
typedef struct {
    JavaVM* jvm;              // JVM reference for thread attachment
    jobject vpnService;       // Global ref to VpnService instance
    jmethodID protectMethod;  // Method ID for protect(int fd)
    int maxRetries;           // Maximum retry attempts (default: 3)
    int retryDelayMs;         // Delay between retries in ms (default: 100)
    bool initialized;         // Whether protector is initialized
    bool verified;            // Whether protection has been verified
} SocketProtectorState;

// Global socket protector state with default values
static SocketProtectorState g_protectorState = {
    .jvm = NULL,
    .vpnService = NULL,
    .protectMethod = NULL,
    .maxRetries = 3,
    .retryDelayMs = 100,
    .initialized = false,
    .verified = false
};

// Legacy globals for backward compatibility (will be migrated to g_protectorState)
// NOTE: NOT static - exported for log_buffer.c to use via extern
JavaVM* g_jvm = NULL;
static jobject g_vpnService = NULL;
static jmethodID g_protectMethod = NULL;

// Export g_protector symbol for Go library
// Go library expects this symbol to be available during dlopen
// This must match the type expected by Go: socket_protector_func
// We export it as a strong symbol to ensure it's available when Go library loads
__attribute__((visibility("default"))) socket_protector_func g_protector = NULL;

// Forward declarations
static bool verify_socket_protection(void);

/**
 * Load Go library and resolve symbols
 * Returns 0 on success, negative error code on failure
 */
static int loadGoLibrary(JNIEnv *env) {
    if (goLibraryLoaded && goLibHandle != NULL) {
        LOGD("Go library already loaded");
        return 0;
    }
    
    LOGI("Loading Go library...");
    
    // Try different library names/paths
    // First try to use already loaded library (via System.loadLibrary)
    // Then try dlopen with different flags
    
    // Method 1: Try to get handle of already loaded library
    dlerror(); // Clear errors
    goLibHandle = dlopen("libhyperxray.so", RTLD_LAZY | RTLD_NOLOAD);
    if (goLibHandle != NULL) {
        LOGI("Go library already loaded via System.loadLibrary, using existing handle");
    } else {
        // Method 2: Try to load with RTLD_LAZY (lazy symbol resolution)
        // This allows g_protector to be resolved later when actually used
        const char* lib_names[] = {
            "libhyperxray.so",              // Standard name
            NULL
        };
        
        for (int i = 0; lib_names[i] != NULL; i++) {
            LOGD("Attempting to load: %s", lib_names[i]);
            
            // Clear previous error
            dlerror();
            
            // Use RTLD_LAZY instead of RTLD_NOW to allow lazy symbol resolution
            // This fixes the "cannot locate symbol g_protector" error
            // g_protector is a static symbol in Go library, lazy loading allows it to resolve
            goLibHandle = dlopen(lib_names[i], RTLD_LAZY | RTLD_GLOBAL);
            
            if (goLibHandle != NULL) {
                LOGI("Successfully loaded: %s", lib_names[i]);
                break;
            }
            
            const char* error = dlerror();
            LOGE("Failed to load %s: %s", lib_names[i], error ? error : "unknown error");
        }
    }
    
    if (goLibHandle == NULL) {
        LOGE("CRITICAL: Could not load Go library with any name!");
        return -1;
    }
    
    // Clear any existing errors
    dlerror();
    
    // Resolve symbols
    LOGD("Resolving Go symbols...");
    
    go_StartHyperTunnel = (StartHyperTunnelFunc)dlsym(goLibHandle, "StartHyperTunnel");
    if (go_StartHyperTunnel == NULL) {
        LOGE("Failed to find StartHyperTunnel: %s", dlerror());
    } else {
        LOGD("Found StartHyperTunnel");
    }
    
    go_StopHyperTunnel = (StopHyperTunnelFunc)dlsym(goLibHandle, "StopHyperTunnel");
    if (go_StopHyperTunnel == NULL) {
        LOGE("Failed to find StopHyperTunnel: %s", dlerror());
    } else {
        LOGD("Found StopHyperTunnel");
    }
    
    go_GetTunnelStats = (GetTunnelStatsFunc)dlsym(goLibHandle, "GetTunnelStats");
    if (go_GetTunnelStats == NULL) {
        LOGE("Failed to find GetTunnelStats: %s", dlerror());
    } else {
        LOGD("Found GetTunnelStats");
    }
    
    go_GetHandshakeRTT = (GetHandshakeRTTFunc)dlsym(goLibHandle, "GetHandshakeRTT");
    if (go_GetHandshakeRTT == NULL) {
        LOGD("GetHandshakeRTT not found (optional): %s", dlerror());
    } else {
        LOGD("Found GetHandshakeRTT");
    }
    
    go_GetLastError = (GetLastErrorFunc)dlsym(goLibHandle, "GetLastError");
    if (go_GetLastError == NULL) {
        LOGD("GetLastError not found (optional)");
    } else {
        LOGD("Found GetLastError");
    }
    
    go_NativeGeneratePublicKey = (NativeGeneratePublicKeyFunc)dlsym(goLibHandle, "NativeGeneratePublicKey");
    if (go_NativeGeneratePublicKey == NULL) {
        LOGE("Failed to find NativeGeneratePublicKey: %s", dlerror());
    } else {
        LOGD("Found NativeGeneratePublicKey");
    }
    
    go_FreeString = (FreeStringFunc)dlsym(goLibHandle, "FreeString");
    if (go_FreeString == NULL) {
        LOGE("Failed to find FreeString: %s", dlerror());
    } else {
        LOGD("Found FreeString");
    }
    
    go_SetSocketProtector = (SetSocketProtectorFunc)dlsym(goLibHandle, "SetSocketProtector");
    if (go_SetSocketProtector == NULL) {
        LOGD("SetSocketProtector not found (optional): %s", dlerror());
    } else {
        LOGD("Found SetSocketProtector");
    }
    
    go_ClearSocketProtector = (ClearSocketProtectorFunc)dlsym(goLibHandle, "ClearSocketProtector");
    if (go_ClearSocketProtector == NULL) {
        LOGD("ClearSocketProtector not found (optional): %s", dlerror());
    } else {
        LOGD("Found ClearSocketProtector");
    }
    
    go_ResetSocketProtectorState = (ResetSocketProtectorStateFunc)dlsym(goLibHandle, "ResetSocketProtectorState");
    if (go_ResetSocketProtectorState == NULL) {
        LOGD("ResetSocketProtectorState not found (optional): %s", dlerror());
    } else {
        LOGD("Found ResetSocketProtectorState");
    }
    
    // REMOVED: Multi-instance symbol resolution
    // These symbols have been removed as part of architectural cleanup
    
    // Resolve DNS symbols
    LOGD("Resolving DNS symbols...");
    
    go_InitDNSCache = (InitDNSCacheFunc)dlsym(goLibHandle, "InitDNSCache");
    if (go_InitDNSCache != NULL) LOGD("Found InitDNSCache");
    
    go_DNSCacheLookup = (DNSCacheLookupFunc)dlsym(goLibHandle, "DNSCacheLookup");
    if (go_DNSCacheLookup != NULL) LOGD("Found DNSCacheLookup");
    
    go_DNSCacheLookupAll = (DNSCacheLookupAllFunc)dlsym(goLibHandle, "DNSCacheLookupAll");
    if (go_DNSCacheLookupAll != NULL) LOGD("Found DNSCacheLookupAll");
    
    go_DNSCacheSave = (DNSCacheSaveFunc)dlsym(goLibHandle, "DNSCacheSave");
    if (go_DNSCacheSave != NULL) LOGD("Found DNSCacheSave");
    
    go_DNSCacheGetMetrics = (DNSCacheGetMetricsFunc)dlsym(goLibHandle, "DNSCacheGetMetrics");
    if (go_DNSCacheGetMetrics != NULL) LOGD("Found DNSCacheGetMetrics");
    
    go_DNSCacheClear = (DNSCacheClearFunc)dlsym(goLibHandle, "DNSCacheClear");
    if (go_DNSCacheClear != NULL) LOGD("Found DNSCacheClear");
    
    go_DNSCacheCleanupExpired = (DNSCacheCleanupExpiredFunc)dlsym(goLibHandle, "DNSCacheCleanupExpired");
    if (go_DNSCacheCleanupExpired != NULL) LOGD("Found DNSCacheCleanupExpired");
    
    go_StartDNSServer = (StartDNSServerFunc)dlsym(goLibHandle, "StartDNSServer");
    if (go_StartDNSServer != NULL) LOGD("Found StartDNSServer");
    
    go_StopDNSServer = (StopDNSServerFunc)dlsym(goLibHandle, "StopDNSServer");
    if (go_StopDNSServer != NULL) LOGD("Found StopDNSServer");
    
    go_IsDNSServerRunning = (IsDNSServerRunningFunc)dlsym(goLibHandle, "IsDNSServerRunning");
    if (go_IsDNSServerRunning != NULL) LOGD("Found IsDNSServerRunning");
    
    go_GetDNSServerPort = (GetDNSServerPortFunc)dlsym(goLibHandle, "GetDNSServerPort");
    if (go_GetDNSServerPort != NULL) LOGD("Found GetDNSServerPort");
    
    go_GetDNSServerStats = (GetDNSServerStatsFunc)dlsym(goLibHandle, "GetDNSServerStats");
    if (go_GetDNSServerStats != NULL) LOGD("Found GetDNSServerStats");
    
    go_DNSResolve = (DNSResolveFunc)dlsym(goLibHandle, "DNSResolve");
    if (go_DNSResolve != NULL) LOGD("Found DNSResolve");
    
    // Resolve Xray gRPC stats symbols
    LOGD("Resolving Xray gRPC stats symbols...");
    
    go_IsXrayGrpcAvailable = (IsXrayGrpcAvailableFunc)dlsym(goLibHandle, "IsXrayGrpcAvailable");
    if (go_IsXrayGrpcAvailable != NULL) LOGD("Found IsXrayGrpcAvailable");
    
    go_GetXraySystemStats = (GetXraySystemStatsFunc)dlsym(goLibHandle, "GetXraySystemStats");
    if (go_GetXraySystemStats != NULL) LOGD("Found GetXraySystemStats");
    
    go_GetXrayTrafficStats = (GetXrayTrafficStatsFunc)dlsym(goLibHandle, "GetXrayTrafficStats");
    if (go_GetXrayTrafficStats != NULL) LOGD("Found GetXrayTrafficStats");
    
    go_GetXrayLogs = (GetXrayLogsFunc)dlsym(goLibHandle, "GetXrayLogs");
    if (go_GetXrayLogs != NULL) LOGD("Found GetXrayLogs");
    
    // Check if at least StartHyperTunnel is available
    if (go_StartHyperTunnel == NULL) {
        LOGE("Critical function StartHyperTunnel not found, library load failed");
        dlclose(goLibHandle);
        goLibHandle = NULL;
        return -2;
    }
    
    goLibraryLoaded = 1;
    LOGI("Go library loaded successfully, all symbols resolved");
    return 0;
}

/**
 * JNI_OnLoad - Called when library is loaded by System.loadLibrary()
 */
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called");
    
    // Initialize log buffer for batched JNI logging
    log_buffer_init();
    
    // Ensure g_protector is initialized to NULL before Go library loads
    // This ensures the symbol exists when Go library tries to resolve it
    g_protector = NULL;
    LOGD("g_protector initialized to NULL");
    
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNI environment");
        return JNI_ERR;
    }
    
    // Load Go library during JNI initialization
    if (loadGoLibrary(env) != 0) {
        LOGE("Failed to load Go library during JNI_OnLoad");
        // Don't return error - library might be loaded later
    }
    
    LOGI("JNI_OnLoad completed");
    return JNI_VERSION_1_6;
}

/**
 * JNI_OnUnload - Called when library is unloaded
 */
JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnUnload called");
    
    // Cleanup log buffer (flushes remaining logs)
    log_buffer_cleanup();
    
    if (goLibHandle != NULL) {
        dlclose(goLibHandle);
        goLibHandle = NULL;
        goLibraryLoaded = 0;
    }
}

/*
 * ============================================================================
 * JNI FUNCTION IMPLEMENTATIONS
 * 
 * CRITICAL: Function names MUST match exactly:
 * Java_<package>_<class>_<method>
 * where package separators (.) are replaced with underscores (_)
 * ============================================================================
 */

/**
 * Start the VPN tunnel
 * 
 * Java signature: private external fun startHyperTunnel(
 *     tunFd: Int,
 *     wgConfigJSON: String,
 *     xrayConfigJSON: String,
 *     warpEndpoint: String,
 *     warpPrivateKey: String,
 *     nativeLibDir: String,
 *     filesDir: String,
 *     tunnelMode: String,
 *     masqueConfig: String
 * ): Int
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_startHyperTunnel(
    JNIEnv *env,
    jobject thiz,
    jint tunFd,
    jstring wgConfigJSON,
    jstring xrayConfigJSON,
    jstring warpEndpoint,
    jstring warpPrivateKey,
    jstring nativeLibDir,
    jstring filesDir,
    jstring tunnelMode,
    jstring masqueConfig
) {
    LOGI("startHyperTunnel called with tunFd=%d", tunFd);
    
    // Verify protector is initialized
    if (g_protectMethod == NULL || !g_protectorState.initialized) {
        LOGE("[Protector] ❌ Socket protector not initialized! Call initSocketProtector first!");
        return -30;
    }
    
    // Verify socket protection is working before starting tunnel
    if (!g_protectorState.verified) {
        LOGI("[Protector] Running socket protection verification before tunnel start...");
        if (!verify_socket_protection()) {
            LOGE("[Protector] ❌ Socket protection verification failed!");
            return -35;  // Error code for verification failed
        }
    }
    
    // Ensure Go library is loaded
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return -100;  // Error code for library not loaded
        }
    }
    
    if (go_StartHyperTunnel == NULL) {
        LOGE("StartHyperTunnel function pointer is NULL");
        return -101;  // Error code for function not found
    }
    
    // Convert Java strings to C strings
    const char* wgConfigC = NULL;
    const char* xrayConfigC = NULL;
    const char* warpEndpointC = NULL;
    const char* warpPrivateKeyC = NULL;
    const char* nativeLibDirC = NULL;
    const char* filesDirC = NULL;
    const char* tunnelModeC = NULL;
    const char* masqueConfigC = NULL;
    
    if (wgConfigJSON != NULL) {
        wgConfigC = (*env)->GetStringUTFChars(env, wgConfigJSON, NULL);
        if (wgConfigC == NULL) {
            LOGE("Failed to convert wgConfigJSON to C string");
            return -102;
        }
    }
    
    if (xrayConfigJSON != NULL) {
        xrayConfigC = (*env)->GetStringUTFChars(env, xrayConfigJSON, NULL);
        if (xrayConfigC == NULL) {
            LOGE("Failed to convert xrayConfigJSON to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            return -102;
        }
    }
    
    if (warpEndpoint != NULL) {
        warpEndpointC = (*env)->GetStringUTFChars(env, warpEndpoint, NULL);
        if (warpEndpointC == NULL) {
            LOGE("Failed to convert warpEndpoint to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
            return -102;
        }
    }
    
    if (warpPrivateKey != NULL) {
        warpPrivateKeyC = (*env)->GetStringUTFChars(env, warpPrivateKey, NULL);
        if (warpPrivateKeyC == NULL) {
            LOGE("Failed to convert warpPrivateKey to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
            if (warpEndpointC) (*env)->ReleaseStringUTFChars(env, warpEndpoint, warpEndpointC);
            return -102;
        }
    }
    
    // Convert nativeLibDir and filesDir
    if (nativeLibDir != NULL) {
        nativeLibDirC = (*env)->GetStringUTFChars(env, nativeLibDir, NULL);
        if (nativeLibDirC == NULL) {
            LOGE("Failed to convert nativeLibDir to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
            if (warpEndpointC) (*env)->ReleaseStringUTFChars(env, warpEndpoint, warpEndpointC);
            if (warpPrivateKeyC) (*env)->ReleaseStringUTFChars(env, warpPrivateKey, warpPrivateKeyC);
            return -102;
        }
    }
    
    if (filesDir != NULL) {
        filesDirC = (*env)->GetStringUTFChars(env, filesDir, NULL);
        if (filesDirC == NULL) {
            LOGE("Failed to convert filesDir to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
            if (warpEndpointC) (*env)->ReleaseStringUTFChars(env, warpEndpoint, warpEndpointC);
            if (warpPrivateKeyC) (*env)->ReleaseStringUTFChars(env, warpPrivateKey, warpPrivateKeyC);
            if (nativeLibDirC) (*env)->ReleaseStringUTFChars(env, nativeLibDir, nativeLibDirC);
            return -102;
        }
    }
    
    // Convert tunnelMode and masqueConfig (MASQUE over Xray feature)
    if (tunnelMode != NULL) {
        tunnelModeC = (*env)->GetStringUTFChars(env, tunnelMode, NULL);
        if (tunnelModeC == NULL) {
            LOGE("Failed to convert tunnelMode to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
            if (warpEndpointC) (*env)->ReleaseStringUTFChars(env, warpEndpoint, warpEndpointC);
            if (warpPrivateKeyC) (*env)->ReleaseStringUTFChars(env, warpPrivateKey, warpPrivateKeyC);
            if (nativeLibDirC) (*env)->ReleaseStringUTFChars(env, nativeLibDir, nativeLibDirC);
            if (filesDirC) (*env)->ReleaseStringUTFChars(env, filesDir, filesDirC);
            return -102;
        }
    }
    
    if (masqueConfig != NULL) {
        masqueConfigC = (*env)->GetStringUTFChars(env, masqueConfig, NULL);
        if (masqueConfigC == NULL) {
            LOGE("Failed to convert masqueConfig to C string");
            if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
            if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
            if (warpEndpointC) (*env)->ReleaseStringUTFChars(env, warpEndpoint, warpEndpointC);
            if (warpPrivateKeyC) (*env)->ReleaseStringUTFChars(env, warpPrivateKey, warpPrivateKeyC);
            if (nativeLibDirC) (*env)->ReleaseStringUTFChars(env, nativeLibDir, nativeLibDirC);
            if (filesDirC) (*env)->ReleaseStringUTFChars(env, filesDir, filesDirC);
            if (tunnelModeC) (*env)->ReleaseStringUTFChars(env, tunnelMode, tunnelModeC);
            return -102;
        }
    }
    
    LOGD("Calling Go StartHyperTunnel with tunnelMode=%s...", tunnelModeC ? tunnelModeC : "wireguard");
    
    // Call Go function with all parameters
    int result = go_StartHyperTunnel(
        (int)tunFd,
        wgConfigC ? wgConfigC : "",
        xrayConfigC ? xrayConfigC : "",
        warpEndpointC ? warpEndpointC : "",
        warpPrivateKeyC ? warpPrivateKeyC : "",
        nativeLibDirC ? nativeLibDirC : "",
        filesDirC ? filesDirC : "",
        tunnelModeC ? tunnelModeC : "wireguard",
        masqueConfigC ? masqueConfigC : "{}"
    );
    
    LOGI("Go StartHyperTunnel returned: %d", result);
    
    // Release strings
    if (wgConfigC) (*env)->ReleaseStringUTFChars(env, wgConfigJSON, wgConfigC);
    if (xrayConfigC) (*env)->ReleaseStringUTFChars(env, xrayConfigJSON, xrayConfigC);
    if (warpEndpointC) (*env)->ReleaseStringUTFChars(env, warpEndpoint, warpEndpointC);
    if (warpPrivateKeyC) (*env)->ReleaseStringUTFChars(env, warpPrivateKey, warpPrivateKeyC);
    if (nativeLibDirC) (*env)->ReleaseStringUTFChars(env, nativeLibDir, nativeLibDirC);
    if (filesDirC) (*env)->ReleaseStringUTFChars(env, filesDir, filesDirC);
    if (tunnelModeC) (*env)->ReleaseStringUTFChars(env, tunnelMode, tunnelModeC);
    if (masqueConfigC) (*env)->ReleaseStringUTFChars(env, masqueConfig, masqueConfigC);
    
    return result;
}

/**
 * Stop the VPN tunnel
 * 
 * Java signature: private external fun stopHyperTunnel(): Int
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_stopHyperTunnel(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    LOGI("stopHyperTunnel called");
    
    if (!goLibraryLoaded || go_StopHyperTunnel == NULL) {
        LOGE("Go library not loaded or stopHyperTunnel not available");
        return -100;
    }
    
    int result = go_StopHyperTunnel();
    LOGI("Go StopHyperTunnel returned: %d", result);
    
    return result;
}

/**
 * Get tunnel statistics
 * 
 * Java signature: private external fun getTunnelStats(): String
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getTunnelStats(
    JNIEnv *env,
    jobject thiz __attribute__((unused))
) {
    LOGD("getTunnelStats called");
    
    if (!goLibraryLoaded || go_GetTunnelStats == NULL) {
        LOGE("Go library not loaded or getTunnelStats not available");
        return (*env)->NewStringUTF(env, "{}");
    }
    
    char* stats = go_GetTunnelStats();
    if (stats == NULL) {
        return (*env)->NewStringUTF(env, "{}");
    }
    
    jstring result = (*env)->NewStringUTF(env, stats);
    
    // Free the string allocated by Go
    if (go_FreeString != NULL) {
        go_FreeString(stats);
    }
    
    return result;
}

/**
 * Get WireGuard handshake RTT in milliseconds
 * 
 * Java signature: private external fun getHandshakeRTT(): Long
 */
JNIEXPORT jlong JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getHandshakeRTT(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    LOGD("getHandshakeRTT called");
    
    if (!goLibraryLoaded || go_GetHandshakeRTT == NULL) {
        LOGD("Go library not loaded or getHandshakeRTT not available, returning default");
        return 50L; // Default fallback
    }
    
    long long rtt = go_GetHandshakeRTT();
    LOGD("Go GetHandshakeRTT returned: %lld ms", rtt);
    
    return (jlong)rtt;
}

/**
 * Generate public key from private key
 * 
 * Java signature: private external fun nativeGeneratePublicKey(privateKeyBase64: String): String
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_nativeGeneratePublicKey(
    JNIEnv *env,
    jobject thiz,
    jstring privateKeyBase64
) {
    LOGD("nativeGeneratePublicKey called");
    
    if (!goLibraryLoaded || go_NativeGeneratePublicKey == NULL) {
        LOGE("Go library not loaded or nativeGeneratePublicKey not available");
        return (*env)->NewStringUTF(env, "");
    }
    
    if (privateKeyBase64 == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    
    const char* privateKeyC = (*env)->GetStringUTFChars(env, privateKeyBase64, NULL);
    if (privateKeyC == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    
    char* publicKey = go_NativeGeneratePublicKey(privateKeyC);
    (*env)->ReleaseStringUTFChars(env, privateKeyBase64, privateKeyC);
    
    if (publicKey == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    
    jstring result = (*env)->NewStringUTF(env, publicKey);
    
    // Free the string allocated by Go
    if (go_FreeString != NULL) {
        go_FreeString(publicKey);
    }
    
    return result;
}

/**
 * Free string allocated by Go
 * 
 * Java signature: private external fun freeString(str: String)
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_freeString(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring str
) {
    LOGD("freeString called");
    
    if (!goLibraryLoaded || go_FreeString == NULL || str == NULL) {
        return;
    }
    
    const char* strC = (*env)->GetStringUTFChars(env, str, NULL);
    if (strC != NULL) {
        // Note: This is a workaround - Go's FreeString expects char* allocated by Go
        // In practice, this function may not be needed if we always free strings in JNI
        (*env)->ReleaseStringUTFChars(env, str, strC);
    }
}

/**
 * Get last error from Go library
 * 
 * Java signature: private external fun getLastNativeError(): String
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getLastNativeError(
    JNIEnv *env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded || go_GetLastError == NULL) {
        return (*env)->NewStringUTF(env, "Go library not loaded");
    }
    
    char* error = go_GetLastError();
    jstring result = (*env)->NewStringUTF(env, error ? error : "");
    if (error && go_FreeString) {
        go_FreeString(error);
    }
    return result;
}

/**
 * Check if native library is ready
 * 
 * Java signature: private external fun isNativeLibraryReady(): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_isNativeLibraryReady(
    JNIEnv *env,
    jobject thiz __attribute__((unused))
) {
    LOGD("isNativeLibraryReady called, goLibraryLoaded=%d", goLibraryLoaded);
    
    // Try to load library if not already loaded
    if (!goLibraryLoaded) {
        LOGI("Go library not loaded, attempting to load...");
        int result = loadGoLibrary(env);
        if (result != 0) {
            LOGE("Failed to load Go library in isNativeLibraryReady: %d", result);
            return JNI_FALSE;
        }
    }
    
    return goLibraryLoaded ? JNI_TRUE : JNI_FALSE;
}

/**
 * Load Go library from specific path
 * 
 * Java signature: private external fun loadGoLibraryWithPath(path: String): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_loadGoLibraryWithPath(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring path
) {
    LOGD("loadGoLibraryWithPath called");
    
    if (goLibraryLoaded) {
        LOGI("Go library already loaded");
        return JNI_TRUE;
    }
    
    if (path == NULL) {
        LOGE("Path is null");
        return JNI_FALSE;
    }
    
    const char* pathC = (*env)->GetStringUTFChars(env, path, NULL);
    if (pathC == NULL) {
        return JNI_FALSE;
    }
    
    LOGI("Attempting to load Go library from: %s", pathC);
    
    // Clear previous error
    dlerror();
    
    // Try to load with RTLD_LAZY | RTLD_GLOBAL
    goLibHandle = dlopen(pathC, RTLD_LAZY | RTLD_GLOBAL);
    
    (*env)->ReleaseStringUTFChars(env, path, pathC);
    
    if (goLibHandle == NULL) {
        const char* error = dlerror();
        LOGE("Failed to load library from path: %s", error ? error : "unknown error");
        return JNI_FALSE;
    }
    
    LOGI("Successfully loaded library from path");
    
    // Resolve symbols
    LOGD("Resolving Go symbols...");
    
    go_StartHyperTunnel = (StartHyperTunnelFunc)dlsym(goLibHandle, "StartHyperTunnel");
    if (go_StartHyperTunnel == NULL) {
        LOGE("Failed to find StartHyperTunnel: %s", dlerror());
        dlclose(goLibHandle);
        goLibHandle = NULL;
        return JNI_FALSE;
    }
    
    // Resolve other symbols (copy-paste from loadGoLibrary)
    go_StopHyperTunnel = (StopHyperTunnelFunc)dlsym(goLibHandle, "StopHyperTunnel");
    go_GetTunnelStats = (GetTunnelStatsFunc)dlsym(goLibHandle, "GetTunnelStats");
    go_GetHandshakeRTT = (GetHandshakeRTTFunc)dlsym(goLibHandle, "GetHandshakeRTT");
    go_GetLastError = (GetLastErrorFunc)dlsym(goLibHandle, "GetLastError");
    go_NativeGeneratePublicKey = (NativeGeneratePublicKeyFunc)dlsym(goLibHandle, "NativeGeneratePublicKey");
    go_FreeString = (FreeStringFunc)dlsym(goLibHandle, "FreeString");
    go_SetSocketProtector = (SetSocketProtectorFunc)dlsym(goLibHandle, "SetSocketProtector");
    go_ClearSocketProtector = (ClearSocketProtectorFunc)dlsym(goLibHandle, "ClearSocketProtector");
    go_ResetSocketProtectorState = (ResetSocketProtectorStateFunc)dlsym(goLibHandle, "ResetSocketProtectorState");
    
    // REMOVED: Multi-instance symbol resolution
    // These symbols have been removed as part of architectural cleanup
    
    // DNS
    go_InitDNSCache = (InitDNSCacheFunc)dlsym(goLibHandle, "InitDNSCache");
    go_DNSCacheLookup = (DNSCacheLookupFunc)dlsym(goLibHandle, "DNSCacheLookup");
    go_DNSCacheLookupAll = (DNSCacheLookupAllFunc)dlsym(goLibHandle, "DNSCacheLookupAll");
    go_DNSCacheSave = (DNSCacheSaveFunc)dlsym(goLibHandle, "DNSCacheSave");
    go_DNSCacheGetMetrics = (DNSCacheGetMetricsFunc)dlsym(goLibHandle, "DNSCacheGetMetrics");
    go_DNSCacheClear = (DNSCacheClearFunc)dlsym(goLibHandle, "DNSCacheClear");
    go_DNSCacheCleanupExpired = (DNSCacheCleanupExpiredFunc)dlsym(goLibHandle, "DNSCacheCleanupExpired");
    go_StartDNSServer = (StartDNSServerFunc)dlsym(goLibHandle, "StartDNSServer");
    go_StopDNSServer = (StopDNSServerFunc)dlsym(goLibHandle, "StopDNSServer");
    go_IsDNSServerRunning = (IsDNSServerRunningFunc)dlsym(goLibHandle, "IsDNSServerRunning");
    go_GetDNSServerPort = (GetDNSServerPortFunc)dlsym(goLibHandle, "GetDNSServerPort");
    go_GetDNSServerStats = (GetDNSServerStatsFunc)dlsym(goLibHandle, "GetDNSServerStats");
    go_DNSResolve = (DNSResolveFunc)dlsym(goLibHandle, "DNSResolve");
    
    goLibraryLoaded = 1;
    return JNI_TRUE;
}

/*
 * ============================================================================
 * REMOVED: MULTI-INSTANCE JNI FUNCTIONS
 * ============================================================================
 * 
 * All multi-instance JNI functions have been removed as part of
 * architectural cleanup. Xray-core is now managed directly through
 * startHyperTunnel() which embeds Xray-core.
 * 
 * Removed functions:
 * - Java_com_hyperxray_an_vpn_HyperVpnService_initMultiInstanceManager
 * - Java_com_hyperxray_an_vpn_HyperVpnService_startMultiInstances
 * - Java_com_hyperxray_an_vpn_HyperVpnService_stopMultiInstance
 * - Java_com_hyperxray_an_vpn_HyperVpnService_stopAllMultiInstances
 * - Java_com_hyperxray_an_vpn_HyperVpnService_getMultiInstanceStatus
 * - Java_com_hyperxray_an_vpn_HyperVpnService_getAllMultiInstancesStatus
 * - Java_com_hyperxray_an_vpn_HyperVpnService_getMultiInstanceCount
 * - Java_com_hyperxray_an_vpn_HyperVpnService_isMultiInstanceRunning
 */

/*
 * ============================================================================
 * DNS JNI FUNCTIONS
 * ============================================================================
 */

/**
 * Initialize DNS cache
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_initDNSCache(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring cacheDir
) {
    LOGI("initDNSCache called");
    
    if (!goLibraryLoaded || go_InitDNSCache == NULL) {
        LOGE("Go library not loaded or initDNSCache not available");
        return -100;
    }
    
    const char* cacheDirC = (*env)->GetStringUTFChars(env, cacheDir, NULL);
    int result = go_InitDNSCache(cacheDirC ? cacheDirC : "");
    if (cacheDirC) (*env)->ReleaseStringUTFChars(env, cacheDir, cacheDirC);
    
    return result;
}

/**
 * Lookup DNS from cache
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_dnsCacheLookup(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring hostname
) {
    if (!goLibraryLoaded || go_DNSCacheLookup == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    
    const char* hostnameC = (*env)->GetStringUTFChars(env, hostname, NULL);
    char* result = go_DNSCacheLookup(hostnameC ? hostnameC : "");
    if (hostnameC) (*env)->ReleaseStringUTFChars(env, hostname, hostnameC);
    
    jstring jResult = (*env)->NewStringUTF(env, result ? result : "");
    if (result && go_FreeString) go_FreeString(result);
    
    return jResult;
}

/**
 * Lookup all DNS IPs from cache
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_dnsCacheLookupAll(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring hostname
) {
    if (!goLibraryLoaded || go_DNSCacheLookupAll == NULL) {
        return (*env)->NewStringUTF(env, "[]");
    }
    
    const char* hostnameC = (*env)->GetStringUTFChars(env, hostname, NULL);
    char* result = go_DNSCacheLookupAll(hostnameC ? hostnameC : "");
    if (hostnameC) (*env)->ReleaseStringUTFChars(env, hostname, hostnameC);
    
    jstring jResult = (*env)->NewStringUTF(env, result ? result : "[]");
    if (result && go_FreeString) go_FreeString(result);
    
    return jResult;
}

/**
 * Save DNS resolution to cache
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_dnsCacheSave(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring hostname,
    jstring ipsJSON,
    jlong ttl
) {
    if (!goLibraryLoaded || go_DNSCacheSave == NULL) {
        return;
    }
    
    const char* hostnameC = (*env)->GetStringUTFChars(env, hostname, NULL);
    const char* ipsC = (*env)->GetStringUTFChars(env, ipsJSON, NULL);
    
    go_DNSCacheSave(hostnameC ? hostnameC : "", ipsC ? ipsC : "[]", (long)ttl);
    
    if (hostnameC) (*env)->ReleaseStringUTFChars(env, hostname, hostnameC);
    if (ipsC) (*env)->ReleaseStringUTFChars(env, ipsJSON, ipsC);
}

/**
 * Get DNS cache metrics
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getDNSCacheMetrics(
    JNIEnv *env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded || go_DNSCacheGetMetrics == NULL) {
        return (*env)->NewStringUTF(env, "{}");
    }
    
    char* result = go_DNSCacheGetMetrics();
    jstring jResult = (*env)->NewStringUTF(env, result ? result : "{}");
    if (result && go_FreeString) go_FreeString(result);
    
    return jResult;
}

/**
 * Clear DNS cache
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_dnsCacheClear(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    if (goLibraryLoaded && go_DNSCacheClear != NULL) {
        go_DNSCacheClear();
    }
}

/**
 * Cleanup expired DNS cache entries
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_dnsCacheCleanupExpired(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded || go_DNSCacheCleanupExpired == NULL) {
        return 0;
    }
    return go_DNSCacheCleanupExpired();
}

/**
 * Start DNS server
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_startDNSServer(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jint port,
    jstring upstreamDNS
) {
    LOGI("startDNSServer called on port %d", port);
    
    if (!goLibraryLoaded || go_StartDNSServer == NULL) {
        LOGE("Go library not loaded or startDNSServer not available");
        return -100;
    }
    
    const char* upstreamC = upstreamDNS != NULL ? 
        (*env)->GetStringUTFChars(env, upstreamDNS, NULL) : NULL;
    
    int result = go_StartDNSServer((int)port, upstreamC ? upstreamC : "");
    
    if (upstreamC) (*env)->ReleaseStringUTFChars(env, upstreamDNS, upstreamC);
    
    LOGI("startDNSServer returned: %d", result);
    return result;
}

/**
 * Stop DNS server
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_stopDNSServer(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    LOGI("stopDNSServer called");
    
    if (!goLibraryLoaded || go_StopDNSServer == NULL) {
        return -100;
    }
    
    return go_StopDNSServer();
}

/**
 * Check if DNS server is running
 */
JNIEXPORT jboolean JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_isDNSServerRunning(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded || go_IsDNSServerRunning == NULL) {
        return JNI_FALSE;
    }
    return go_IsDNSServerRunning() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get DNS server port
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getDNSServerPort(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded || go_GetDNSServerPort == NULL) {
        return 0;
    }
    return go_GetDNSServerPort();
}

/**
 * Get DNS server stats
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getDNSServerStats(
    JNIEnv *env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded || go_GetDNSServerStats == NULL) {
        return (*env)->NewStringUTF(env, "{}");
    }
    
    char* result = go_GetDNSServerStats();
    jstring jResult = (*env)->NewStringUTF(env, result ? result : "{}");
    if (result && go_FreeString) go_FreeString(result);
    
    return jResult;
}

/**
 * Resolve DNS using native resolver
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_dnsResolve(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jstring hostname
) {
    if (!goLibraryLoaded || go_DNSResolve == NULL) {
        return (*env)->NewStringUTF(env, "[]");
    }
    
    const char* hostnameC = (*env)->GetStringUTFChars(env, hostname, NULL);
    char* result = go_DNSResolve(hostnameC ? hostnameC : "");
    if (hostnameC) (*env)->ReleaseStringUTFChars(env, hostname, hostnameC);
    
    jstring jResult = (*env)->NewStringUTF(env, result ? result : "[]");
    if (result && go_FreeString) go_FreeString(result);
    
    return jResult;
}

// Global reference to HyperVpnService instance and logToAiLogHelper method
// NOTE: NOT static - exported for log_buffer.c to use via extern
jobject g_serviceInstance = NULL;
static jmethodID g_logToAiLogHelperMethodID = NULL;

/**
 * Socket protector callback with retry mechanism - called from Go
 * 
 * This function implements robust socket protection with:
 * - Configurable retry attempts (default: 3)
 * - Delay between retries (default: 100ms)
 * - Thread re-attachment on JNI_EDETACHED
 * - Detailed logging for debugging
 */
static bool socket_protector_callback_with_retry(int fd) {
    // Check if protector is initialized
    if (!g_protectorState.initialized) {
        LOGE("[Protector] ❌ Protector not initialized! fd=%d", fd);
        return false;
    }
    
    if (g_protectorState.jvm == NULL || g_protectorState.vpnService == NULL || 
        g_protectorState.protectMethod == NULL) {
        LOGE("[Protector] ❌ Protector state invalid! jvm=%p, vpnService=%p, protectMethod=%p",
             g_protectorState.jvm, g_protectorState.vpnService, g_protectorState.protectMethod);
        return false;
    }
    
    JNIEnv* env = NULL;
    bool needDetach = false;
    int maxRetries = g_protectorState.maxRetries;
    int retryDelayMs = g_protectorState.retryDelayMs;
    
    // Attach to JVM if needed
    int getEnvResult = (*g_protectorState.jvm)->GetEnv(g_protectorState.jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        LOGD("[Protector] Thread detached, attaching to JVM...");
        if ((*g_protectorState.jvm)->AttachCurrentThread(g_protectorState.jvm, &env, NULL) != 0) {
            LOGE("[Protector] ❌ Failed to attach thread for fd=%d", fd);
            return false;
        }
        needDetach = true;
        LOGD("[Protector] ✅ Thread attached successfully");
    } else if (getEnvResult != JNI_OK) {
        LOGE("[Protector] ❌ Failed to get JNI environment: %d", getEnvResult);
        return false;
    }
    
    // Retry loop for socket protection
    bool result = false;
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        // Call VpnService.protect(fd)
        jboolean protectResult = (*env)->CallBooleanMethod(
            env, g_protectorState.vpnService, g_protectorState.protectMethod, fd);
        
        // Check for JNI exceptions
        if ((*env)->ExceptionCheck(env)) {
            LOGE("[Protector] ❌ JNI exception during protect() call, attempt %d/%d", attempt, maxRetries);
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            
            if (attempt < maxRetries) {
                LOGD("[Protector] Retrying in %dms...", retryDelayMs);
                usleep(retryDelayMs * 1000); // Convert ms to microseconds
                continue;
            }
        } else if (protectResult) {
            result = true;
            if (attempt > 1) {
                LOGI("[Protector] ✅ protect(%d) succeeded on attempt %d/%d", fd, attempt, maxRetries);
            } else {
                LOGD("[Protector] ✅ protect(%d) = true", fd);
            }
            break;
        } else {
            LOGE("[Protector] ❌ protect(%d) returned false, attempt %d/%d", fd, attempt, maxRetries);
            
            if (attempt < maxRetries) {
                LOGD("[Protector] Retrying in %dms...", retryDelayMs);
                usleep(retryDelayMs * 1000);
            }
        }
    }
    
    if (!result) {
        LOGE("[Protector] ❌ All %d protection attempts failed for fd=%d", maxRetries, fd);
    }
    
    // Detach thread if we attached it
    if (needDetach) {
        (*g_protectorState.jvm)->DetachCurrentThread(g_protectorState.jvm);
    }
    
    return result;
}

// Legacy callback for backward compatibility (delegates to retry version)
static bool socket_protector_callback(int fd) {
    // Use legacy globals if new state not initialized
    if (!g_protectorState.initialized) {
        // Fallback to legacy behavior
        if (g_jvm == NULL || g_vpnService == NULL || g_protectMethod == NULL) {
            LOGE("Protector not initialized!");
            return false;
        }
        
        JNIEnv* env;
        bool needDetach = false;
        
        int getEnvResult = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
        if (getEnvResult == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
                LOGE("Failed to attach thread");
                return false;
            }
            needDetach = true;
        } else if (getEnvResult != JNI_OK) {
            LOGE("Failed to get JNI environment");
            return false;
        }
        
        jboolean result = (*env)->CallBooleanMethod(env, g_vpnService, g_protectMethod, fd);
        
        if (needDetach) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        
        LOGI("protect(%d) = %s", fd, result ? "true" : "false");
        return result;
    }
    
    // Use new retry mechanism
    return socket_protector_callback_with_retry(fd);
}

/**
 * Set AiLogHelper callback
 * 
 * Java signature: private external fun setAiLogHelperCallback()
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_setAiLogHelperCallback(
    JNIEnv *env,
    jobject thiz
) {
    LOGD("setAiLogHelperCallback called");
    
    // Store JVM for later use
    (*env)->GetJavaVM(env, &g_jvm);
    
    // Get class and method ID
    jclass clazz = (*env)->GetObjectClass(env, thiz);
    if (clazz == NULL) {
        LOGE("Failed to get HyperVpnService class");
        return;
    }
    
    g_logToAiLogHelperMethodID = (*env)->GetMethodID(
        env, clazz, "logToAiLogHelper", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    );
    if (g_logToAiLogHelperMethodID == NULL) {
        LOGE("Failed to get logToAiLogHelper method ID");
        return;
    }
    
    // Create global reference to service instance
    if (g_serviceInstance != NULL) {
        (*env)->DeleteGlobalRef(env, g_serviceInstance);
    }
    g_serviceInstance = (*env)->NewGlobalRef(env, thiz);
    if (g_serviceInstance == NULL) {
        LOGE("Failed to create global reference to service instance");
        return;
    }
    
    LOGI("AiLogHelper callback set successfully");
}

/**
 * Initialize socket protector
 * 
 * Java signature: private external fun initSocketProtector()
 * 
 * CRITICAL: This function is called on every VPN start.
 * It must reset Go-side caches to ensure fresh state for new session.
 * This prevents routing loops caused by stale physical IP or DNS cache.
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_initSocketProtector(
    JNIEnv* env,
    jobject thiz
) {
    LOGI("[Protector] Initializing socket protector with retry mechanism...");
    
    // Ensure Go library is loaded
    if (!goLibraryLoaded) {
        LOGD("[Protector] Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("[Protector] ❌ Failed to load Go library");
            g_protectorState.initialized = false;
            return;
        }
    }
    
    // CRITICAL: Reset Go-side state FIRST if this is a re-initialization
    // This clears stale physical IP cache and DNS cache from previous session
    if (g_protectorState.initialized && go_ResetSocketProtectorState != NULL) {
        LOGI("[Protector] Re-initialization detected, resetting Go-side state...");
        go_ResetSocketProtectorState();
        LOGI("[Protector] ✅ Go-side state reset (caches invalidated)");
    }
    
    // Save JVM reference to both legacy and new state
    (*env)->GetJavaVM(env, &g_jvm);
    g_protectorState.jvm = g_jvm;
    
    // Save VpnService reference (global ref to prevent GC)
    // Clean up old references first
    if (g_vpnService != NULL) {
        (*env)->DeleteGlobalRef(env, g_vpnService);
    }
    if (g_protectorState.vpnService != NULL && g_protectorState.vpnService != g_vpnService) {
        (*env)->DeleteGlobalRef(env, g_protectorState.vpnService);
    }
    
    g_vpnService = (*env)->NewGlobalRef(env, thiz);
    g_protectorState.vpnService = g_vpnService;
    
    if (g_vpnService == NULL) {
        LOGE("[Protector] ❌ Failed to create global reference to VpnService");
        g_protectorState.initialized = false;
        return;
    }
    
    // Get protect method ID
    jclass clazz = (*env)->GetObjectClass(env, thiz);
    if (clazz == NULL) {
        LOGE("[Protector] ❌ Failed to get HyperVpnService class");
        g_protectorState.initialized = false;
        return;
    }
    
    g_protectMethod = (*env)->GetMethodID(env, clazz, "protect", "(I)Z");
    g_protectorState.protectMethod = g_protectMethod;
    
    if (g_protectMethod == NULL) {
        LOGE("[Protector] ❌ Failed to find protect(int) method!");
        g_protectorState.initialized = false;
        return;
    }
    
    // Mark as initialized
    g_protectorState.initialized = true;
    g_protectorState.verified = false; // Will be verified on first use or explicit call
    
    // Set g_protector to the callback function with retry mechanism
    // This ensures the symbol is available when Go library needs it
    g_protector = socket_protector_callback;
    LOGD("[Protector] g_protector set to socket_protector_callback (with retry support)");
    
    // Register callback with Go (this also invalidates caches in Go)
    if (go_SetSocketProtector != NULL) {
        go_SetSocketProtector(socket_protector_callback);
        LOGI("[Protector] ✅ Socket protector initialized successfully!");
        LOGI("[Protector]    maxRetries=%d, retryDelayMs=%d", 
             g_protectorState.maxRetries, g_protectorState.retryDelayMs);
    } else {
        LOGE("[Protector] ❌ SetSocketProtector function not available in Go library!");
        g_protectorState.initialized = false;
    }
    
    // Register C socket creator with Go library
    // This allows Go to create sockets via C, bypassing Go runtime's socket management
    register_c_socket_creator();
}

/**
 * Cleanup socket protector
 * 
 * Java signature: private external fun cleanupSocketProtector()
 * 
 * CRITICAL: This function must be called when VPN service is destroyed.
 * It clears both JNI and Go-side state to prevent stale callbacks
 * which can cause routing loops on VPN restart.
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_cleanupSocketProtector(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    LOGI("[Protector] Cleaning up socket protector (JNI + Go state)...");
    
    // STEP 1: Clear Go-side protector and caches FIRST
    // This ensures Go doesn't try to use stale JNI callback
    if (go_ClearSocketProtector != NULL) {
        LOGI("[Protector] Clearing Go-side socket protector and caches...");
        go_ClearSocketProtector();
        LOGI("[Protector] ✅ Go-side protector cleared");
    } else {
        LOGD("[Protector] go_ClearSocketProtector not available, skipping Go cleanup");
    }
    
    // STEP 2: Clear g_protector callback pointer
    // This prevents any pending Go calls from using stale callback
    g_protector = NULL;
    LOGD("[Protector] g_protector set to NULL");
    
    // STEP 3: Clean up JNI global references
    if (g_vpnService != NULL) {
        (*env)->DeleteGlobalRef(env, g_vpnService);
        g_vpnService = NULL;
        LOGD("[Protector] VpnService global ref deleted");
    }
    g_protectMethod = NULL;
    
    // STEP 4: Clean up protector state struct
    g_protectorState.jvm = NULL;
    g_protectorState.vpnService = NULL;
    g_protectorState.protectMethod = NULL;
    g_protectorState.initialized = false;
    g_protectorState.verified = false;
    // Keep maxRetries and retryDelayMs at default values for next init
    
    LOGI("[Protector] ✅ Socket protector fully cleaned up (JNI + Go)");
}

// Function pointer types for C-based socket creation
typedef int (*CreateProtectedSocketFunc)(int domain, int type, int protocol);
typedef int (*CreateProtectedSocketTCPFunc)(void);
typedef int (*CreateProtectedSocketUDPFunc)(void);
typedef void (*SetCSocketCreatorFunc)(CreateProtectedSocketFunc);

// Function pointers for C socket creation
static SetCSocketCreatorFunc go_SetCSocketCreator = NULL;

/**
 * Create a protected TCP socket using C (bypasses Go runtime)
 * 
 * This function creates a socket directly in C, protects it via VpnService.protect(),
 * and returns the fd. This bypasses Go's runtime socket management which can
 * interfere with socket protection.
 * 
 * @return socket fd on success, -1 on error
 */
static int create_protected_socket_c(int domain, int type, int protocol) {
    LOGD("[CSocket] Creating protected socket: domain=%d, type=%d, protocol=%d", domain, type, protocol);
    
    // Create socket using C (not Go's syscall.Socket)
    int fd = socket(domain, type, protocol);
    if (fd < 0) {
        LOGE("[CSocket] ❌ Failed to create socket: errno=%d", errno);
        return -1;
    }
    
    LOGD("[CSocket] Socket created: fd=%d", fd);
    
    // Protect the socket immediately
    if (g_protector != NULL) {
        bool protected = g_protector(fd);
        if (!protected) {
            LOGE("[CSocket] ❌ Failed to protect socket fd=%d", fd);
            close(fd);
            return -1;
        }
        LOGD("[CSocket] ✅ Socket fd=%d protected successfully", fd);
    } else {
        LOGE("[CSocket] ❌ g_protector is NULL, cannot protect socket fd=%d", fd);
        close(fd);
        return -1;
    }
    
    return fd;
}

/**
 * Create a protected TCP socket (convenience function)
 */
static int create_protected_tcp_socket(void) {
    return create_protected_socket_c(AF_INET, SOCK_STREAM, 0);
}

/**
 * Create a protected UDP socket (convenience function)
 */
static int create_protected_udp_socket(void) {
    return create_protected_socket_c(AF_INET, SOCK_DGRAM, 0);
}

/**
 * Register C socket creator with Go library
 * This allows Go to call back into C to create protected sockets
 */
static void register_c_socket_creator(void) {
    if (go_SetCSocketCreator == NULL) {
        // Try to resolve the symbol
        if (goLibHandle != NULL) {
            go_SetCSocketCreator = (SetCSocketCreatorFunc)dlsym(goLibHandle, "SetCSocketCreator");
            if (go_SetCSocketCreator == NULL) {
                LOGD("[CSocket] SetCSocketCreator not found in Go library (optional)");
                return;
            }
        } else {
            LOGD("[CSocket] Go library not loaded, cannot register C socket creator");
            return;
        }
    }
    
    // Register our C socket creator with Go
    go_SetCSocketCreator(create_protected_socket_c);
    LOGI("[CSocket] ✅ C socket creator registered with Go library");
}

/**
 * Verify socket protection is working correctly
 * Creates a test socket, protects it, and verifies the result
 * 
 * @return true if protection is verified working, false otherwise
 */
static bool verify_socket_protection(void) {
    LOGI("[Protector] Verifying socket protection...");
    
    // Check if protector is initialized
    if (!g_protectorState.initialized) {
        LOGE("[Protector] ❌ Verification failed: protector not initialized");
        return false;
    }
    
    if (g_protector == NULL) {
        LOGE("[Protector] ❌ Verification failed: g_protector is NULL");
        return false;
    }
    
    // Create a test socket
    int testFd = socket(AF_INET, SOCK_STREAM, 0);
    if (testFd < 0) {
        LOGE("[Protector] ❌ Verification failed: could not create test socket (errno=%d)", errno);
        return false;
    }
    
    LOGD("[Protector] Created test socket fd=%d", testFd);
    
    // Try to protect the test socket
    bool protectResult = g_protector(testFd);
    
    // Close the test socket
    close(testFd);
    
    if (protectResult) {
        g_protectorState.verified = true;
        LOGI("[Protector] ✅ Verification PASSED: socket protection is working");
        return true;
    } else {
        g_protectorState.verified = false;
        LOGE("[Protector] ❌ Verification FAILED: protect() returned false for test socket");
        return false;
    }
}

/**
 * Check if socket protector is verified
 * 
 * Java signature: private external fun isSocketProtectorVerified(): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_isSocketProtectorVerified(
    JNIEnv* env __attribute__((unused)),
    jobject thiz __attribute__((unused))
) {
    // If already verified, return cached result
    if (g_protectorState.verified) {
        LOGD("[Protector] Already verified, returning true");
        return JNI_TRUE;
    }
    
    // Try to verify now
    bool result = verify_socket_protection();
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Call AiLogHelper from native code (BUFFERED VERSION)
 * This function is called from Go code via CGO
 * 
 * OPTIMIZATION: Instead of calling JNI for every log message, logs are
 * buffered and flushed in batches to reduce JNI overhead.
 * 
 * Flush triggers:
 * - Buffer full (50 entries)
 * - Buffer size exceeds 4KB
 * - Time interval exceeded (500ms)
 * - ERROR level log (immediate flush)
 */
void callAiLogHelper(const char* tag, const char* level, const char* message) {
    // Always log to Android logcat immediately (low overhead)
    int priority = ANDROID_LOG_DEBUG;
    if (level != NULL) {
        switch (level[0]) {
            case 'E': case 'e': priority = ANDROID_LOG_ERROR; break;
            case 'W': case 'w': priority = ANDROID_LOG_WARN; break;
            case 'I': case 'i': priority = ANDROID_LOG_INFO; break;
            default: priority = ANDROID_LOG_DEBUG; break;
        }
    }
    __android_log_print(priority, tag ? tag : "HyperXray", "%s", message ? message : "");
    
    // If JNI not ready, skip buffering (already logged to logcat)
    if (g_jvm == NULL || g_serviceInstance == NULL) {
        return;
    }
    
    // Add to buffer (will flush automatically when needed)
    log_buffer_add(tag, level, message);
}

/**
 * Legacy single-message JNI call (kept for backward compatibility)
 * Use callAiLogHelper() instead for buffered logging
 */
void callAiLogHelperDirect(const char* tag, const char* level, const char* message) {
    if (g_jvm == NULL || g_serviceInstance == NULL || g_logToAiLogHelperMethodID == NULL) {
        // Fallback to Android log if callback not set
        __android_log_print(
            level[0] == 'E' ? ANDROID_LOG_ERROR :
            level[0] == 'W' ? ANDROID_LOG_WARN :
            level[0] == 'I' ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG,
            tag, "%s", message
        );
        return;
    }
    
    JNIEnv* env;
    int attached = 0;
    
    // Get JNI environment
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        // Attach current thread
        status = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        if (status != JNI_OK) {
            LOGE("Failed to attach thread for AiLogHelper");
            return;
        }
        attached = 1;
    } else if (status != JNI_OK) {
        LOGE("Failed to get JNI environment for AiLogHelper");
        return;
    }
    
    // Create Java strings
    jstring jTag = (*env)->NewStringUTF(env, tag);
    jstring jLevel = (*env)->NewStringUTF(env, level);
    jstring jMessage = (*env)->NewStringUTF(env, message);
    
    if (jTag == NULL || jLevel == NULL || jMessage == NULL) {
        LOGE("Failed to create Java strings for AiLogHelper");
        if (attached) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }
    
    // Call logToAiLogHelper method
    (*env)->CallVoidMethod(env, g_serviceInstance, g_logToAiLogHelperMethodID, jTag, jLevel, jMessage);
    
    // Check for exceptions
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    
    // Clean up
    (*env)->DeleteLocalRef(env, jTag);
    (*env)->DeleteLocalRef(env, jLevel);
    (*env)->DeleteLocalRef(env, jMessage);
    
    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/**
 * Check if Xray gRPC is available (for XrayStatsManager)
 * 
 * Java signature: private external fun isXrayGrpcAvailableNative(): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_com_hyperxray_an_core_monitor_XrayStatsManager_isXrayGrpcAvailableNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return JNI_FALSE;
        }
    }
    
    if (go_IsXrayGrpcAvailable == NULL) {
        LOGD("IsXrayGrpcAvailable function not available");
        return JNI_FALSE;
    }
    
    bool result = go_IsXrayGrpcAvailable();
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Check if Xray gRPC is available (for HyperVpnService)
 * 
 * Java signature: private external fun isXrayGrpcAvailableNative(): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_isXrayGrpcAvailableNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return JNI_FALSE;
        }
    }
    
    if (go_IsXrayGrpcAvailable == NULL) {
        LOGD("IsXrayGrpcAvailable function not available");
        return JNI_FALSE;
    }
    
    bool result = go_IsXrayGrpcAvailable();
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get Xray system stats from native gRPC client
 * 
 * Java signature: private external fun getXraySystemStatsNative(): String?
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_core_monitor_XrayStatsManager_getXraySystemStatsNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return NULL;
        }
    }
    
    if (go_GetXraySystemStats == NULL) {
        LOGD("GetXraySystemStats function not available");
        return NULL;
    }
    
    char* result = go_GetXraySystemStats();
    if (result == NULL) {
        return NULL;
    }
    
    jstring jresult = (*env)->NewStringUTF(env, result);
    
    // Free the C string
    if (go_FreeString != NULL) {
        go_FreeString(result);
    } else {
        free(result);
    }
    
    return jresult;
}

/**
 * Get Xray system stats from native gRPC client (for HyperVpnService)
 * 
 * Java signature: private external fun getXraySystemStatsNative(): String?
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getXraySystemStatsNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return NULL;
        }
    }
    
    if (go_GetXraySystemStats == NULL) {
        LOGD("GetXraySystemStats function not available");
        return NULL;
    }
    
    char* result = go_GetXraySystemStats();
    if (result == NULL) {
        return NULL;
    }
    
    jstring jresult = (*env)->NewStringUTF(env, result);
    
    // Free the C string
    if (go_FreeString != NULL) {
        go_FreeString(result);
    } else {
        free(result);
    }
    
    return jresult;
}

/**
 * Get Xray traffic stats from native gRPC client (for HyperVpnService)
 * 
 * Java signature: private external fun getXrayTrafficStatsNative(): String?
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_vpn_HyperVpnService_getXrayTrafficStatsNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return NULL;
        }
    }
    
    if (go_GetXrayTrafficStats == NULL) {
        LOGD("GetXrayTrafficStats function not available");
        return NULL;
    }
    
    char* result = go_GetXrayTrafficStats();
    if (result == NULL) {
        return NULL;
    }
    
    jstring jresult = (*env)->NewStringUTF(env, result);
    
    // Free the C string
    if (go_FreeString != NULL) {
        go_FreeString(result);
    } else {
        free(result);
    }
    
    return jresult;
}

/**
 * Get Xray traffic stats from native gRPC client
 * 
 * Java signature: private external fun getXrayTrafficStatsNative(): String?
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_core_monitor_XrayStatsManager_getXrayTrafficStatsNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused))
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return NULL;
        }
    }
    
    if (go_GetXrayTrafficStats == NULL) {
        LOGD("GetXrayTrafficStats function not available");
        return NULL;
    }
    
    char* result = go_GetXrayTrafficStats();
    if (result == NULL) {
        return NULL;
    }
    
    jstring jresult = (*env)->NewStringUTF(env, result);
    
    // Free the C string
    if (go_FreeString != NULL) {
        go_FreeString(result);
    } else {
        free(result);
    }
    
    return jresult;
}

/**
 * Get Xray logs from native log channel
 * 
 * Java signature: private external fun getXrayLogsNative(maxCount: Int): String?
 */
JNIEXPORT jstring JNICALL
Java_com_hyperxray_an_core_monitor_XrayLogManager_getXrayLogsNative(
    JNIEnv* env,
    jobject thiz __attribute__((unused)),
    jint maxCount
) {
    if (!goLibraryLoaded) {
        LOGD("Go library not loaded, attempting to load...");
        if (loadGoLibrary(env) != 0) {
            LOGE("Failed to load Go library");
            return NULL;
        }
    }
    
    if (go_GetXrayLogs == NULL) {
        LOGD("GetXrayLogs function not available");
        return NULL;
    }
    
    char* result = go_GetXrayLogs((int)maxCount);
    if (result == NULL) {
        return NULL;
    }
    
    jstring jresult = (*env)->NewStringUTF(env, result);
    
    // Free the C string
    if (go_FreeString != NULL) {
        go_FreeString(result);
    } else {
        free(result);
    }
    
    return jresult;
}
