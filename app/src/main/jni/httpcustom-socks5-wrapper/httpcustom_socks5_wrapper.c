/*
 * HTTP Custom SOCKS5 Wrapper
 * 
 * This wrapper provides a JNI interface compatible with hev-socks5-tunnel
 * for HTTP Custom's SOCKS5 libraries (libsocks, libsocks2tun, libtun2socks)
 * 
 * Note: This is a placeholder implementation. The actual JNI interface
 * for HTTP Custom libraries needs to be determined through reverse engineering
 * or documentation.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/mman.h>
#include <errno.h>
#include <sys/types.h>

#define TAG "HttpCustomSocks5Wrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// JNI Hook structures
typedef struct JNINativeInterface_ {
    void* reserved0;
    void* reserved1;
    void* reserved2;
    void* reserved3;
    jint (*GetVersion)(JNIEnv*);
    jclass (*DefineClass)(JNIEnv*, const char*, jobject, const jbyte*, jsize);
    jclass (*FindClass)(JNIEnv*, const char*);
    // ... (other JNI functions)
} JNINativeInterface;

// Global ClassLoader reference for class preloading
static jobject g_classLoader = NULL;

// Original FindClass function pointer (for hook restoration)
static jclass (*original_FindClass)(JNIEnv*, const char*) = NULL;

// Flag to prevent infinite recursion in hooked FindClass
static __thread int in_custom_findclass = 0;

// Function pointers for HTTP Custom libraries
// These will be populated when libraries are loaded
static void* libsocks_handle = NULL;
static void* libsocks2tun_handle = NULL;
static void* libtun2socks_handle = NULL;

// HTTP Custom JNI function types (from detailed report)
// libsocks2tun.so uses Psiphon Tunnel
// JNI signature: (IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I
// Parameters: tunFd, mtu, vpnIp, netmask, socksServer, udpgwServer, transparentDNS
// Return type: int (confirmed from HTTP Custom source code analysis)
// Default UDP gateway: "127.0.0.1:7300" (from HTTP Custom source code)
typedef jint (*psiphon_run_tun2socks_func)(JNIEnv*, jclass, jint, jint, jstring, jstring, jstring, jstring, jint);
typedef void (*psiphon_terminate_tun2socks_func)(JNIEnv*, jclass);

// libtun2socks.so uses Bugs4U Tun2Socks
// JNI signature: (IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I
// Parameters: tunFd, mtu, vpnIp, netmask, socksServer, dnsgwServer, udpgwServer, transparentDNS
typedef jint (*bugs4u_run_tun2socks_func)(JNIEnv*, jclass, jint, jint, jstring, jstring, jstring, jstring, jstring, jint);
typedef void (*bugs4u_terminate_tun2socks_func)(JNIEnv*, jclass);

static psiphon_run_tun2socks_func psiphon_run = NULL;
static psiphon_terminate_tun2socks_func psiphon_terminate = NULL;
static bugs4u_run_tun2socks_func bugs4u_run = NULL;
static bugs4u_terminate_tun2socks_func bugs4u_terminate = NULL;

// Track which library is active
static int active_library = 0; // 0 = none, 1 = psiphon, 2 = bugs4u
static JavaVM* java_vm = NULL;

// Global references to classes for libsocks2tun.so callbacks
static jclass g_psiphonTunnelClass = NULL;
static jmethodID g_logTun2SocksMethod = NULL;

// Store parameters for HTTP Custom JNI calls
static struct {
    jint tunFd;
    jint mtu;
    jstring vpnIp;
    jstring netmask;
    jstring socksServer;
    jstring udpgwServer;  // For Psiphon
    jstring dnsgwServer;  // For Bugs4U
    jint transparentDNS;
} httpcustom_params;

/**
 * Custom FindClass implementation that intercepts FindClass calls
 * and returns cached application classes when needed
 */
static jclass custom_FindClass(JNIEnv* env, const char* name) {
    // Debug: Log all FindClass calls to verify hook is working
    if (name != NULL) {
        LOGI("🔍 custom_FindClass called: %s", name);
    } else {
        LOGI("🔍 custom_FindClass called: (NULL)");
    }

    // Prevent infinite recursion
    if (in_custom_findclass) {
        LOGW("⚠️ custom_FindClass: Recursion detected, using original");
        if (original_FindClass != NULL) {
            return original_FindClass(env, name);
        }
        return NULL;
    }

    in_custom_findclass = 1;

    // Check if this is a class we need to intercept
    if (name != NULL) {
        // Handle ca/psiphon/PsiphonTunnel
        if (strcmp(name, "ca/psiphon/PsiphonTunnel") == 0) {
            LOGI("🔧 FindClass hook intercepted: %s", name);
            if (g_psiphonTunnelClass != NULL) {
                LOGI("✅ Returning cached PsiphonTunnel class from hook");
                in_custom_findclass = 0;
                return g_psiphonTunnelClass;
            } else {
                LOGW("⚠️ PsiphonTunnel class requested but not cached yet");
            }
        }

        // Handle com/hyperxray/an/service/TProxyService (if needed)
        if (strcmp(name, "com/hyperxray/an/service/TProxyService") == 0) {
            LOGI("🔧 FindClass hook intercepted: %s", name);
            // Try to load it using ClassLoader
            if (g_classLoader != NULL && original_FindClass != NULL) {
                jclass classLoaderClass = original_FindClass(env, "java/lang/ClassLoader");
                if (classLoaderClass != NULL) {
                    jmethodID loadClassMethod = (*env)->GetMethodID(env, classLoaderClass,
                        "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
                    if (loadClassMethod != NULL) {
                        jstring className = (*env)->NewStringUTF(env, "com.hyperxray.an.service.TProxyService");
                        jclass tproxyClass = (jclass)(*env)->CallObjectMethod(env, g_classLoader,
                            loadClassMethod, className);
                        (*env)->DeleteLocalRef(env, className);
                        (*env)->DeleteLocalRef(env, classLoaderClass);

                        if (tproxyClass != NULL) {
                            LOGI("✅ Loaded TProxyService class via ClassLoader from hook");
                            in_custom_findclass = 0;
                            return tproxyClass;
                        }
                    }
                    (*env)->DeleteLocalRef(env, classLoaderClass);
                }
            }
        }
    }

    // For all other classes, use original FindClass
    jclass result = NULL;
    if (original_FindClass != NULL) {
        result = original_FindClass(env, name);
    }

    in_custom_findclass = 0;
    return result;
}

/**
 * Hook JNIEnv's FindClass to intercept application class lookups
 * This allows libsocks2tun.so to find classes even from native threads
 * 
 * CRITICAL: Each thread has its own JNIEnv with its own function table.
 * We must hook each JNIEnv separately. This function checks if THIS
 * specific JNIEnv is already hooked before hooking it.
 */
static int hook_findclass(JNIEnv* env) {
    if (env == NULL) {
        LOGE("Cannot hook FindClass: env is NULL");
        return -1;
    }

    // Get JNI function table
    JNINativeInterface** env_ptr = (JNINativeInterface**)env;
    JNINativeInterface* jni_interface = *env_ptr;

    if (jni_interface == NULL || jni_interface->FindClass == NULL) {
        LOGE("Cannot hook FindClass: JNI interface invalid");
        return -1;
    }

    // Check if THIS JNIEnv is already hooked (each JNIEnv has its own function table)
    if (jni_interface->FindClass == custom_FindClass) {
        LOGI("FindClass already hooked for this JNIEnv");
        return 0;
    }

    // Save original FindClass (only need to save once, all JNIEnvs use same implementation)
    // But we still need to hook each JNIEnv's function table separately
    if (original_FindClass == NULL) {
        original_FindClass = jni_interface->FindClass;
        LOGI("Saved original FindClass pointer: %p", (void*)original_FindClass);
    } else {
        // Verify it's the same implementation (should be, but check for safety)
        if (original_FindClass != jni_interface->FindClass && 
            jni_interface->FindClass != custom_FindClass) {
            LOGW("⚠️ Different FindClass implementation detected: %p vs %p",
                 (void*)original_FindClass, (void*)jni_interface->FindClass);
            // Update to use this one (shouldn't happen, but handle it)
            original_FindClass = jni_interface->FindClass;
        }
    }

    // Make function table writable
    size_t page_size = sysconf(_SC_PAGESIZE);
    void* page_start = (void*)((uintptr_t)jni_interface & ~(page_size - 1));

    LOGI("Attempting to make JNI function table writable at %p (page: %p)",
         (void*)jni_interface, page_start);

    if (mprotect(page_start, page_size * 2, PROT_READ | PROT_WRITE) != 0) {
        LOGE("❌ mprotect failed to make memory writable: %s (errno: %d)",
             strerror(errno), errno);
        LOGW("⚠️ FindClass hook failed - will rely on pre-loading only");
        original_FindClass = NULL;
        return -1;
    }

    LOGI("✅ Memory made writable, installing FindClass hook...");

    // Replace FindClass pointer
    jni_interface->FindClass = custom_FindClass;

    // Make function table read-only again
    if (mprotect(page_start, page_size * 2, PROT_READ) != 0) {
        LOGW("⚠️ mprotect failed to restore read-only protection: %s", strerror(errno));
        // Not critical, continue anyway
    }

    LOGI("✅ FindClass hook installed successfully");
    LOGI("   Original FindClass: %p", (void*)original_FindClass);
    LOGI("   Custom FindClass: %p", (void*)custom_FindClass);

    return 0;
}

// Pre-load classes using ClassLoader so FindClass can find them
// This is safer than hooking FindClass directly, which requires modifying read-only memory
static int preload_classes_with_classloader(JNIEnv* env) {
    if (env == NULL) {
        LOGE("Cannot preload classes: env is NULL");
        return -1;
    }
    
    if (g_classLoader == NULL) {
        LOGW("⚠️ ClassLoader not available, cannot preload classes");
        return -1;
    }
    
    // Get FindClass function from JNIEnv
    JNINativeInterface** env_ptr = (JNINativeInterface**)env;
    JNINativeInterface* jni_interface = *env_ptr;
    if (jni_interface == NULL) {
        LOGE("Cannot preload classes: jni_interface is NULL");
        return -1;
    }
    
    jclass (*findClassFunc)(JNIEnv*, const char*) = jni_interface->FindClass;
    
    if (findClassFunc == NULL) {
        LOGE("Cannot preload classes: FindClass function not available");
        return -1;
    }
    
    // Get ClassLoader class
    jclass classLoaderClass = findClassFunc(env, "java/lang/ClassLoader");
    if (classLoaderClass == NULL) {
        LOGE("Cannot preload classes: ClassLoader class not found");
        return -1;
    }
    
    jmethodID loadClassMethod = (*env)->GetMethodID(env, classLoaderClass, 
        "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClassMethod == NULL) {
        (*env)->DeleteLocalRef(env, classLoaderClass);
        LOGE("Cannot preload classes: loadClass method not found");
        return -1;
    }
    
    // Pre-load PsiphonTunnel class using ClassLoader
    jstring psiphonClassName = (*env)->NewStringUTF(env, "ca.psiphon.PsiphonTunnel");
    if (psiphonClassName != NULL) {
        jclass psiphonClass = (jclass)(*env)->CallObjectMethod(env, g_classLoader, 
            loadClassMethod, psiphonClassName);
        if (psiphonClass != NULL) {
            LOGI("✅ Pre-loaded PsiphonTunnel class using ClassLoader");
            
            // Store as global reference
            if (g_psiphonTunnelClass == NULL) {
                g_psiphonTunnelClass = (*env)->NewGlobalRef(env, psiphonClass);
                
                // Get the logTun2Socks method ID
                g_logTun2SocksMethod = (*env)->GetStaticMethodID(env, psiphonClass, 
                    "logTun2Socks", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                if (g_logTun2SocksMethod != NULL) {
                    LOGI("✅ Found logTun2Socks method");
                }
            }
            
            (*env)->DeleteLocalRef(env, psiphonClass);
        } else {
            jthrowable exc = (*env)->ExceptionOccurred(env);
            if (exc) {
                (*env)->ExceptionClear(env);
                LOGW("⚠️ Failed to preload PsiphonTunnel class");
            }
        }
        (*env)->DeleteLocalRef(env, psiphonClassName);
    }
    
    // Also try FindClass - this may work if class is already in the system classloader
    // or if we've pre-loaded it in this thread's context
    jclass psiphonClassFind = findClassFunc(env, "ca/psiphon/PsiphonTunnel");
    if (psiphonClassFind != NULL) {
        LOGI("✅ PsiphonTunnel class accessible via FindClass");
        if (g_psiphonTunnelClass == NULL) {
            g_psiphonTunnelClass = (*env)->NewGlobalRef(env, psiphonClassFind);
        }
        (*env)->DeleteLocalRef(env, psiphonClassFind);
    } else {
        LOGW("⚠️ PsiphonTunnel class not accessible via FindClass (will rely on ClassLoader)");
    }
    
    (*env)->DeleteLocalRef(env, classLoaderClass);
    return 0;
}

// Setup ClassLoader for class preloading
static int setup_classloader(JNIEnv* env) {
    if (env == NULL) {
        LOGE("Cannot setup ClassLoader: env is NULL");
        return -1;
    }
    
    if (g_classLoader != NULL) {
        LOGI("ClassLoader already set up");
        return 0;
    }
    
    // Clear any pending exceptions
    jthrowable exc = (*env)->ExceptionOccurred(env);
    if (exc) {
        (*env)->ExceptionClear(env);
    }
    
    // Get FindClass function from JNIEnv
    JNINativeInterface** env_ptr = (JNINativeInterface**)env;
    if (env_ptr == NULL) {
        LOGE("Cannot setup ClassLoader: env_ptr is NULL");
        return -1;
    }
    
    JNINativeInterface* jni_interface = *env_ptr;
    if (jni_interface == NULL) {
        LOGE("Cannot setup ClassLoader: jni_interface is NULL");
        return -1;
    }
    
    jclass (*findClassFunc)(JNIEnv*, const char*) = jni_interface->FindClass;
    if (findClassFunc == NULL) {
        LOGE("Cannot setup ClassLoader: FindClass function is NULL");
        return -1;
    }
    
    // Try to get ClassLoader from PsiphonTunnel class
    jclass psiphonClass = NULL;
    exc = NULL;
    psiphonClass = findClassFunc(env, "ca/psiphon/PsiphonTunnel");
    exc = (*env)->ExceptionOccurred(env);
    if (exc) {
        (*env)->ExceptionClear(env);
        LOGW("⚠️ PsiphonTunnel class not found yet (will retry later)");
    }
    
    if (psiphonClass != NULL) {
        jclass classClass = findClassFunc(env, "java/lang/Class");
        exc = (*env)->ExceptionOccurred(env);
        if (exc) {
            (*env)->ExceptionClear(env);
            classClass = NULL;
        }
        
        if (classClass != NULL) {
            jmethodID getClassLoaderMethod = (*env)->GetMethodID(env, classClass, 
                "getClassLoader", "()Ljava/lang/ClassLoader;");
            exc = (*env)->ExceptionOccurred(env);
            if (exc) {
                (*env)->ExceptionClear(env);
                getClassLoaderMethod = NULL;
            }
            
            if (getClassLoaderMethod != NULL) {
                jobject loader = (*env)->CallObjectMethod(env, psiphonClass, getClassLoaderMethod);
                exc = (*env)->ExceptionOccurred(env);
                if (exc) {
                    (*env)->ExceptionClear(env);
                    loader = NULL;
                }
                
                if (loader != NULL) {
                    g_classLoader = (*env)->NewGlobalRef(env, loader);
                    LOGI("✅ Got ClassLoader from PsiphonTunnel class");
                    (*env)->DeleteLocalRef(env, loader);
                    (*env)->DeleteLocalRef(env, classClass);
                    (*env)->DeleteLocalRef(env, psiphonClass);
                    return 0;
                }
            }
            (*env)->DeleteLocalRef(env, classClass);
        }
        (*env)->DeleteLocalRef(env, psiphonClass);
    }
    
    // Try to get ClassLoader from TProxyService class
    jclass tproxyClass = NULL;
    exc = NULL;
    tproxyClass = findClassFunc(env, "com/hyperxray/an/service/TProxyService");
    exc = (*env)->ExceptionOccurred(env);
    if (exc) {
        (*env)->ExceptionClear(env);
        LOGW("⚠️ TProxyService class not found yet (will retry later)");
    }
    
    if (tproxyClass != NULL) {
        jclass classClass = findClassFunc(env, "java/lang/Class");
        exc = (*env)->ExceptionOccurred(env);
        if (exc) {
            (*env)->ExceptionClear(env);
            classClass = NULL;
        }
        
        if (classClass != NULL) {
            jmethodID getClassLoaderMethod = (*env)->GetMethodID(env, classClass, 
                "getClassLoader", "()Ljava/lang/ClassLoader;");
            exc = (*env)->ExceptionOccurred(env);
            if (exc) {
                (*env)->ExceptionClear(env);
                getClassLoaderMethod = NULL;
            }
            
            if (getClassLoaderMethod != NULL) {
                jobject loader = (*env)->CallObjectMethod(env, tproxyClass, getClassLoaderMethod);
                exc = (*env)->ExceptionOccurred(env);
                if (exc) {
                    (*env)->ExceptionClear(env);
                    loader = NULL;
                }
                
                if (loader != NULL) {
                    g_classLoader = (*env)->NewGlobalRef(env, loader);
                    LOGI("✅ Got ClassLoader from TProxyService class");
                    (*env)->DeleteLocalRef(env, loader);
                    (*env)->DeleteLocalRef(env, classClass);
                    (*env)->DeleteLocalRef(env, tproxyClass);
                    return 0;
                }
            }
            (*env)->DeleteLocalRef(env, classClass);
        }
        (*env)->DeleteLocalRef(env, tproxyClass);
    }
    
    LOGW("⚠️ Failed to get ClassLoader from any known class (will retry when classes are loaded)");
    return -1;
}

// Thread arguments structure for Psiphon Tunnel
struct psiphon_thread_args {
    jclass clazz;  // Store jclass as global ref, not JNIEnv
    jint tunFd;
    jint mtu;
    jstring vpnIp;
    jstring netmask;
    jstring socksServer;
    jstring udpgw;
    jint transparentDNS;
    psiphon_run_tun2socks_func psiphon_run;
};

// Thread function to run Psiphon Tunnel (blocking function)
static void* psiphon_run_thread(void* arg) {
    LOGI("🚀 psiphon_run_thread started (thread ID: %lu)", (unsigned long)pthread_self());
    
    struct psiphon_thread_args* args = (struct psiphon_thread_args*)arg;
    if (args == NULL) {
        LOGE("psiphon_run_thread: args is NULL");
        return NULL;
    }
    
    LOGI("🔍 psiphon_run_thread: Attaching to JVM...");
    // CRITICAL: Attach thread to JVM before making any JNI calls
    JNIEnv* env = NULL;
    if (java_vm == NULL) {
        LOGE("psiphon_run_thread: java_vm is NULL, cannot attach thread");
        free(args);
        return NULL;
    }
    
    jint attach_result = (*java_vm)->AttachCurrentThread(java_vm, &env, NULL);
    if (attach_result != JNI_OK || env == NULL) {
        LOGE("psiphon_run_thread: Failed to attach thread to JVM (result: %d)", attach_result);
        free(args);
        return NULL;
    }
    
    LOGI("✅ psiphon_run_thread: Successfully attached to JVM");
    
    // Ensure ClassLoader is set up for this thread
    setup_classloader(env);

    // Pre-load classes using ClassLoader for this thread
    // This ensures libsocks2tun.so's FindClass calls can find classes
    preload_classes_with_classloader(env);

    // CRITICAL: Install FindClass hook for THIS thread's JNIEnv
    // Each thread has its own JNIEnv, so we need to hook it separately
    LOGI("🔧 Installing FindClass hook for native thread JNIEnv (thread ID: %lu)...", (unsigned long)pthread_self());
    
    // Verify JNIEnv is valid before hooking
    JNINativeInterface** env_ptr = (JNINativeInterface**)env;
    JNINativeInterface* jni_interface = *env_ptr;
    if (jni_interface != NULL) {
        LOGI("🔍 Native thread JNIEnv FindClass pointer before hook: %p", (void*)jni_interface->FindClass);
    }
    
    int hook_result = hook_findclass(env);
    if (hook_result == 0) {
        LOGI("✅ FindClass hook installed for native thread");
        // Verify hook was installed
        if (jni_interface != NULL) {
            LOGI("🔍 Native thread JNIEnv FindClass pointer after hook: %p (should be %p)", 
                 (void*)jni_interface->FindClass, (void*)custom_FindClass);
            if (jni_interface->FindClass == custom_FindClass) {
                LOGI("✅ Hook verification: FindClass pointer correctly replaced");
            } else {
                LOGE("❌ Hook verification FAILED: FindClass pointer not replaced!");
            }
        }
    } else {
        LOGW("⚠️ FindClass hook failed in native thread (result: %d)", hook_result);
    }
    
    // CRITICAL: Set thread's context classloader so FindClass can find the class
    // FindClass in JNI uses the thread's context classloader, not the system classloader
    if (g_classLoader != NULL) {
        jclass threadClass = (*env)->FindClass(env, "java/lang/Thread");
        if (threadClass != NULL) {
            jmethodID currentThreadMethod = (*env)->GetStaticMethodID(env, threadClass, 
                "currentThread", "()Ljava/lang/Thread;");
            jmethodID setContextClassLoaderMethod = (*env)->GetMethodID(env, threadClass, 
                "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
            
            if (currentThreadMethod != NULL && setContextClassLoaderMethod != NULL) {
                jobject currentThread = (*env)->CallStaticObjectMethod(env, threadClass, currentThreadMethod);
                if (currentThread != NULL) {
                    (*env)->CallVoidMethod(env, currentThread, setContextClassLoaderMethod, g_classLoader);
                    LOGI("✅ Set thread's context classloader");
                    (*env)->DeleteLocalRef(env, currentThread);
                }
            }
            (*env)->DeleteLocalRef(env, threadClass);
        }
    }
    
    // Try FindClass one more time after setting context classloader
    // This ensures the class is available in thread context
    // CRITICAL: Clear any pending exceptions before FindClass
    jthrowable exc = (*env)->ExceptionOccurred(env);
    if (exc) {
        (*env)->ExceptionClear(env);
    }
    
    jclass psiphonClassCheck = (*env)->FindClass(env, "ca/psiphon/PsiphonTunnel");
    exc = (*env)->ExceptionOccurred(env);
    if (exc) {
        (*env)->ExceptionClear(env);
        LOGW("⚠️ FindClass failed for PsiphonTunnel in native thread (will use global reference)");
        psiphonClassCheck = NULL;
    }
    
    if (psiphonClassCheck != NULL) {
        LOGI("✅ PsiphonTunnel class accessible via FindClass after setting context classloader");
        (*env)->DeleteLocalRef(env, psiphonClassCheck);
    } else {
        LOGW("⚠️ PsiphonTunnel class still not accessible via FindClass, will rely on global reference");
    }
    
    LOGI("Psiphon Tunnel thread started, calling runTun2Socks...");
    
    // CRITICAL: libsocks2tun.so will try to find ca.psiphon.PsiphonTunnel class internally
    // for callbacks (logTun2Socks method). The native code uses FindClass which uses the
    // system classloader, not the thread context classloader.
    // 
    // Solution: Pre-load the class in this thread's context by calling a static method
    // that forces class initialization. This ensures the class is available when
    // libsocks2tun.so tries to find it.
    
    // CRITICAL: libsocks2tun.so may try to find both PsiphonTunnel and TProxyService classes
    // We need to pre-load both classes in this thread's context using the correct classloader
    
    // First, get the classloader from a known class (we'll use the class from args if available)
    jobject classLoader = NULL;
    if (args->clazz != NULL) {
        // Get classloader from the PsiphonTunnel class we already have
        jclass classClass = (*env)->FindClass(env, "java/lang/Class");
        if (classClass != NULL) {
            jmethodID getClassLoaderMethod = (*env)->GetMethodID(env, classClass, 
                "getClassLoader", "()Ljava/lang/ClassLoader;");
            if (getClassLoaderMethod != NULL) {
                classLoader = (*env)->CallObjectMethod(env, args->clazz, getClassLoaderMethod);
                if (classLoader != NULL) {
                    classLoader = (*env)->NewGlobalRef(env, classLoader);
                    LOGI("✅ Got classloader from PsiphonTunnel class");
                }
            }
            (*env)->DeleteLocalRef(env, classClass);
        }
    }
    
    // If we don't have a classloader yet, try to get it from TProxyService
    if (classLoader == NULL) {
        jclass tproxyServiceClass = (*env)->FindClass(env, "com/hyperxray/an/service/TProxyService");
        if (tproxyServiceClass != NULL) {
            jclass classClass = (*env)->FindClass(env, "java/lang/Class");
            if (classClass != NULL) {
                jmethodID getClassLoaderMethod = (*env)->GetMethodID(env, classClass, 
                    "getClassLoader", "()Ljava/lang/ClassLoader;");
                if (getClassLoaderMethod != NULL) {
                    jobject tempLoader = (*env)->CallObjectMethod(env, tproxyServiceClass, getClassLoaderMethod);
                    if (tempLoader != NULL) {
                        classLoader = (*env)->NewGlobalRef(env, tempLoader);
                        LOGI("✅ Got classloader from TProxyService class");
                    }
                }
                (*env)->DeleteLocalRef(env, classClass);
            }
            (*env)->DeleteLocalRef(env, tproxyServiceClass);
        }
    }
    
    // Now pre-load both classes using the classloader
    if (classLoader != NULL) {
        jclass classLoaderClass = (*env)->FindClass(env, "java/lang/ClassLoader");
        if (classLoaderClass != NULL) {
            jmethodID loadClassMethod = (*env)->GetMethodID(env, classLoaderClass, 
                "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            if (loadClassMethod != NULL) {
                // Pre-load PsiphonTunnel class
                jstring psiphonClassName = (*env)->NewStringUTF(env, "ca.psiphon.PsiphonTunnel");
                jclass psiphonClass = (jclass)(*env)->CallObjectMethod(env, classLoader, loadClassMethod, psiphonClassName);
                if (psiphonClass != NULL) {
                    LOGI("✅ Pre-loaded PsiphonTunnel class using classloader");
                    
                    // CRITICAL: Store as global reference so libsocks2tun.so can find it
                    // Also try to make it accessible via FindClass by calling it once
                    if (g_psiphonTunnelClass == NULL) {
                        g_psiphonTunnelClass = (*env)->NewGlobalRef(env, psiphonClass);
                        LOGI("✅ Stored PsiphonTunnel class as global reference");
                        
                        // Get the logTun2Socks method ID for potential use
                        g_logTun2SocksMethod = (*env)->GetStaticMethodID(env, psiphonClass, 
                            "logTun2Socks", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                        if (g_logTun2SocksMethod != NULL) {
                            LOGI("✅ Found logTun2Socks method");
                        } else {
                            jthrowable exc = (*env)->ExceptionOccurred(env);
                            if (exc) {
                                (*env)->ExceptionClear(env);
                            }
                            LOGW("⚠️ Failed to find logTun2Socks method");
                        }
                    }
                    
                    (*env)->DeleteLocalRef(env, psiphonClass);
                } else {
                    jthrowable exc = (*env)->ExceptionOccurred(env);
                    if (exc) {
                        (*env)->ExceptionClear(env);
                        LOGE("⚠️ Failed to pre-load PsiphonTunnel class");
                    }
                }
                (*env)->DeleteLocalRef(env, psiphonClassName);
                
                // Pre-load TProxyService class (libsocks2tun.so may need it)
                jstring tproxyClassName = (*env)->NewStringUTF(env, "com.hyperxray.an.service.TProxyService");
                jclass tproxyClass = (jclass)(*env)->CallObjectMethod(env, classLoader, loadClassMethod, tproxyClassName);
                if (tproxyClass != NULL) {
                    LOGI("✅ Pre-loaded TProxyService class using classloader");
                    (*env)->DeleteLocalRef(env, tproxyClass);
                } else {
                    jthrowable exc = (*env)->ExceptionOccurred(env);
                    if (exc) {
                        (*env)->ExceptionClear(env);
                        LOGE("⚠️ Failed to pre-load TProxyService class");
                    }
                }
                (*env)->DeleteLocalRef(env, tproxyClassName);
            }
            (*env)->DeleteLocalRef(env, classLoaderClass);
        }
        
        // Cleanup classloader
        (*env)->DeleteGlobalRef(env, classLoader);
    } else {
        LOGE("⚠️ Failed to get classloader, classes may not be accessible from native code");
    }
    
    // CRITICAL: Try FindClass to make the class accessible to libsocks2tun.so
    // libsocks2tun.so uses FindClass internally, so we need to ensure the class is
    // accessible via FindClass. This may work if we've already loaded it via classloader.
    jclass psiphonClass = (*env)->FindClass(env, "ca/psiphon/PsiphonTunnel");
    if (psiphonClass != NULL) {
        LOGI("✅ Found PsiphonTunnel class using FindClass");
        
        // Store as global reference if not already stored
        if (g_psiphonTunnelClass == NULL) {
            g_psiphonTunnelClass = (*env)->NewGlobalRef(env, psiphonClass);
            LOGI("✅ Stored PsiphonTunnel class as global reference from FindClass");
            
            // Get the logTun2Socks method ID
            g_logTun2SocksMethod = (*env)->GetStaticMethodID(env, psiphonClass, 
                "logTun2Socks", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            if (g_logTun2SocksMethod != NULL) {
                LOGI("✅ Found logTun2Socks method");
            }
        }
        
        (*env)->DeleteLocalRef(env, psiphonClass);
    } else {
        jthrowable exc = (*env)->ExceptionOccurred(env);
        if (exc) {
            (*env)->ExceptionClear(env);
        }
        LOGW("⚠️ FindClass failed for PsiphonTunnel - libsocks2tun.so may not find it");
        
        // If we have a global reference from classloader, try to use it
        if (g_psiphonTunnelClass != NULL) {
            LOGI("✅ Using global reference to PsiphonTunnel class (from classloader)");
        }
    }
    
    // Use the class from args (already found in main thread with correct classloader)
    if (args->clazz == NULL) {
        LOGE("No PsiphonTunnel class available in args, cannot proceed");
        (*java_vm)->DetachCurrentThread(java_vm);
        free(args);
        return NULL;
    }
    
    LOGI("Using PsiphonTunnel class from main thread (classloader-safe)");
    
    // Call the blocking function - this will block until terminated
    // Note: This function may not return immediately if SOCKS5 connection fails
    // but it will eventually return when the connection is established or fails
    jint result = args->psiphon_run(
        env, args->clazz, args->tunFd, args->mtu,
        args->vpnIp, args->netmask, args->socksServer, args->udpgw, args->transparentDNS
    );
    
    // Check for JNI exceptions
    exc = (*env)->ExceptionOccurred(env);
    if (exc) {
        (*env)->ExceptionClear(env);
        LOGE("❌ Psiphon Tunnel threw an exception in thread");
    } else {
        // Function returned - check result
        if (result == 0) {
            LOGI("✅ Psiphon Tunnel runTun2Socks called successfully (function is blocking)");
        } else {
            LOGW("⚠️ Psiphon Tunnel runTun2Socks returned non-zero: %d (this may be normal if function is blocking)", result);
            // Note: Non-zero return doesn't necessarily mean failure for blocking functions
            // The function may return error codes but still be running
        }
    }
    
    // Cleanup global references
    if (env != NULL) {
        if (args->vpnIp != NULL) {
            (*env)->DeleteGlobalRef(env, args->vpnIp);
        }
        if (args->netmask != NULL) {
            (*env)->DeleteGlobalRef(env, args->netmask);
        }
        if (args->socksServer != NULL) {
            (*env)->DeleteGlobalRef(env, args->socksServer);
        }
        if (args->udpgw != NULL) {
            (*env)->DeleteGlobalRef(env, args->udpgw);
        }
        if (args->clazz != NULL) {
            (*env)->DeleteGlobalRef(env, args->clazz);
        }
    }
    
    // Detach from JVM before thread exits
    if (java_vm != NULL) {
        (*java_vm)->DetachCurrentThread(java_vm);
    }
    
    // Free arguments
    free(args);
    
    LOGI("Psiphon Tunnel thread exiting");
    return NULL;
}

/**
 * Load HTTP Custom SOCKS5 libraries
 * Returns 0 on success, -1 on error
 */
static int load_httpcustom_libraries(JNIEnv* env) {
    if (libsocks_handle != NULL) {
        return 0; // Already loaded
    }

    // Setup ClassLoader before loading libraries
    setup_classloader(env);

    // Pre-load classes using ClassLoader so libsocks2tun.so's FindClass calls can find them
    // This is safer than hooking FindClass (which requires modifying read-only memory)
    if (env != NULL) {
        int preload_result = preload_classes_with_classloader(env);
        if (preload_result == 0) {
            LOGI("✅ Classes pre-loaded successfully");
        } else {
            LOGW("⚠️ Failed to preload classes, libsocks2tun.so may not find classes");
        }

        // CRITICAL: Install FindClass hook to intercept class lookups from libsocks2tun.so
        // This is necessary because libsocks2tun.so runs in native threads with
        // system class loader context, which can't see application classes
        LOGI("Installing FindClass hook for libsocks2tun.so compatibility...");
        int hook_result = hook_findclass(env);
        if (hook_result == 0) {
            LOGI("✅ FindClass hook installed successfully");
        } else {
            LOGW("⚠️ FindClass hook failed - relying on pre-loading and context classloader");
        }
    }
    
    // Try to load libraries
    libsocks_handle = dlopen("libsocks.so", RTLD_LAZY);
    if (!libsocks_handle) {
        LOGE("Failed to load libsocks.so: %s", dlerror());
        return -1;
    }
    
    libsocks2tun_handle = dlopen("libsocks2tun.so", RTLD_LAZY);
    if (!libsocks2tun_handle) {
        LOGE("Failed to load libsocks2tun.so: %s", dlerror());
        dlclose(libsocks_handle);
        libsocks_handle = NULL;
        return -1;
    }
    
    libtun2socks_handle = dlopen("libtun2socks.so", RTLD_LAZY);
    if (!libtun2socks_handle) {
        LOGE("Failed to load libtun2socks.so: %s", dlerror());
        dlclose(libsocks_handle);
        dlclose(libsocks2tun_handle);
        libsocks_handle = NULL;
        libsocks2tun_handle = NULL;
        return -1;
    }
    
    LOGI("HTTP Custom SOCKS5 libraries loaded successfully");
    
    // Resolve Psiphon Tunnel functions from libsocks2tun.so
    psiphon_run = (psiphon_run_tun2socks_func)dlsym(libsocks2tun_handle, 
        "Java_ca_psiphon_PsiphonTunnel_runTun2Socks");
    psiphon_terminate = (psiphon_terminate_tun2socks_func)dlsym(libsocks2tun_handle,
        "Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks");
    
    // Resolve Bugs4U Tun2Socks functions from libtun2socks.so
    bugs4u_run = (bugs4u_run_tun2socks_func)dlsym(libtun2socks_handle,
        "Java_org_bugs4u_proxyserver_core_Tun2Socks_runTun2Socks");
    bugs4u_terminate = (bugs4u_terminate_tun2socks_func)dlsym(libtun2socks_handle,
        "Java_org_bugs4u_proxyserver_core_Tun2Socks_terminateTun2Socks");
    
    if (!psiphon_run || !psiphon_terminate) {
        LOGE("Failed to resolve Psiphon Tunnel functions");
    }
    if (!bugs4u_run || !bugs4u_terminate) {
        LOGE("Failed to resolve Bugs4U Tun2Socks functions");
    }
    
    return 0;
}

/**
 * JNI function: TProxyStartService
 * Compatible with hev-socks5-tunnel interface
 * Maps to HTTP Custom's Psiphon or Bugs4U Tun2Socks functions
 * 
 * Note: This function signature is for compatibility with hev-socks5-tunnel.
 * For HTTP Custom libraries, use TProxyStartServiceWithParams instead.
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_service_TProxyService_TProxyStartService(JNIEnv *env, jclass clazz, jstring config_path, jint fd) {
    // This is the old interface - fallback to hev-socks5-tunnel
    // HTTP Custom libraries require more parameters
    LOGE("TProxyStartService called with old interface - use TProxyStartServiceWithParams for HTTP Custom");
}

/**
 * JNI function: TProxyStartServiceWithParams
 * New interface for HTTP Custom libraries with full parameter support
 * 
 * Parameters:
 * - tunFd: TUN file descriptor
 * - mtu: MTU size
 * - vpnIp: VPN IP address (e.g., "10.0.0.2")
 * - netmask: VPN netmask (e.g., "255.255.255.0")
 * - socksServer: SOCKS5 server address:port (e.g., "127.0.0.1:1082")
 * - dnsgwServer: DNS gateway server (for Bugs4U only, can be empty for Psiphon)
 * - udpgwServer: UDP gateway server (optional, can be empty)
 * - transparentDNS: Transparent DNS flag (0 or 1)
 * - usePsiphon: Use Psiphon Tunnel (1) or Bugs4U Tun2Socks (0)
 */
JNIEXPORT jint JNICALL
Java_com_hyperxray_an_service_TProxyService_TProxyStartServiceWithParams(
    JNIEnv *env, 
    jclass clazz, 
    jint tunFd,
    jint mtu,
    jstring vpnIp,
    jstring netmask,
    jstring socksServer,
    jstring dnsgwServer,
    jstring udpgwServer,
    jint transparentDNS,
    jint usePsiphon
) {
    LOGI("📞 TProxyStartServiceWithParams called: tunFd=%d, mtu=%d, usePsiphon=%d", tunFd, mtu, usePsiphon);
    
    if (load_httpcustom_libraries(env) != 0) {
        LOGE("Failed to load HTTP Custom libraries");
        return -1;
    }
    
    // Store parameters
    httpcustom_params.tunFd = tunFd;
    httpcustom_params.mtu = mtu;
    httpcustom_params.vpnIp = (*env)->NewGlobalRef(env, vpnIp);
    httpcustom_params.netmask = (*env)->NewGlobalRef(env, netmask);
    httpcustom_params.socksServer = (*env)->NewGlobalRef(env, socksServer);
    httpcustom_params.udpgwServer = (*env)->NewGlobalRef(env, udpgwServer);
    httpcustom_params.transparentDNS = transparentDNS;
    
    if (dnsgwServer != NULL) {
        httpcustom_params.dnsgwServer = (*env)->NewGlobalRef(env, dnsgwServer);
    } else {
        httpcustom_params.dnsgwServer = NULL;
    }
    
    // Get string values for logging
    const char* vpnIpStr = (*env)->GetStringUTFChars(env, vpnIp, NULL);
    const char* netmaskStr = (*env)->GetStringUTFChars(env, netmask, NULL);
    const char* socksServerStr = (*env)->GetStringUTFChars(env, socksServer, NULL);
    const char* udpgwServerStr = udpgwServer != NULL ? (*env)->GetStringUTFChars(env, udpgwServer, NULL) : "";
    const char* dnsgwServerStr = dnsgwServer != NULL ? (*env)->GetStringUTFChars(env, dnsgwServer, NULL) : "";
    
    LOGI("TProxyStartServiceWithParams: tunFd=%d, mtu=%d, vpnIp=%s, netmask=%s, socksServer=%s, usePsiphon=%d",
         tunFd, mtu, vpnIpStr ? vpnIpStr : "NULL", netmaskStr ? netmaskStr : "NULL", 
         socksServerStr ? socksServerStr : "NULL", usePsiphon);
    
    jint result = -1;
    
    // Try Psiphon Tunnel first (if requested)
    if (usePsiphon && psiphon_run != NULL && psiphon_terminate != NULL) {
        LOGI("Using Psiphon Tunnel (libsocks2tun.so)");
        // Psiphon: 7 parameters (no dnsgwServer)
        // According to HTTP Custom source code analysis:
        // - Default UDP gateway: "127.0.0.1:7300"
        // - Empty string causes "BAddr_Parse2 failed" error
        // - NULL or empty string should be replaced with default value
        jstring udpgw = udpgwServer;
        const char* udpgwStr = NULL;
        jboolean isNewString = JNI_FALSE;
        
        if (udpgw != NULL) {
            udpgwStr = (*env)->GetStringUTFChars(env, udpgw, NULL);
            // Check if empty string
            if (udpgwStr != NULL && strlen(udpgwStr) == 0) {
                // Empty string - replace with default
                (*env)->ReleaseStringUTFChars(env, udpgw, udpgwStr);
                udpgw = (*env)->NewStringUTF(env, "127.0.0.1:7300");
                isNewString = JNI_TRUE;
                LOGI("UDP gateway is empty, using default: 127.0.0.1:7300");
            }
        } else {
            // NULL - use default
            udpgw = (*env)->NewStringUTF(env, "127.0.0.1:7300");
            isNewString = JNI_TRUE;
            LOGI("UDP gateway not provided, using default: 127.0.0.1:7300");
        }
        
        // Call Psiphon Tunnel - it returns int (confirmed from HTTP Custom source code)
        // According to HTTP Custom source code analysis:
        // - runTun2Socks is called in a separate thread (p2.a Runnable)
        // - The function is blocking and doesn't return until terminated
        // - Return code is not checked in HTTP Custom
        // - We should check for exceptions immediately after calling
        // 
        // CRITICAL: The function is blocking, so we must call it in a separate thread
        // to prevent blocking the JNI call. This matches HTTP Custom's behavior.
        // Clear any pending exceptions first
        (*env)->ExceptionClear(env);
        LOGI("Calling Psiphon Tunnel runTun2Socks in background thread...");
        
        // Create a new thread to run the blocking function
        // This prevents the JNI call from blocking indefinitely
        pthread_t thread;
        struct psiphon_thread_args* args = malloc(sizeof(struct psiphon_thread_args));
        if (args == NULL) {
            LOGE("Failed to allocate memory for thread arguments");
            result = -1;
        } else {
            // Store arguments for the thread
            // CRITICAL: Find and store PsiphonTunnel class as global ref
            // The native library expects ca.psiphon.PsiphonTunnel class
            jclass psiphonClass = (*env)->FindClass(env, "ca/psiphon/PsiphonTunnel");
            if (psiphonClass == NULL) {
                LOGE("Failed to find ca.psiphon.PsiphonTunnel class");
                // Check for pending exception
                jthrowable exc = (*env)->ExceptionOccurred(env);
                if (exc) {
                    (*env)->ExceptionClear(env);
                }
                // Cleanup and return error
                free(args);
                result = -1;
            } else {
                args->clazz = (*env)->NewGlobalRef(env, psiphonClass);
                (*env)->DeleteLocalRef(env, psiphonClass);
                args->tunFd = tunFd;
                args->mtu = mtu;
                args->vpnIp = (*env)->NewGlobalRef(env, vpnIp);
                args->netmask = (*env)->NewGlobalRef(env, netmask);
                args->socksServer = (*env)->NewGlobalRef(env, socksServer);
                args->udpgw = (*env)->NewGlobalRef(env, udpgw);
                args->transparentDNS = transparentDNS;
                args->psiphon_run = psiphon_run;
                
                // Note: Thread will attach to JVM in psiphon_run_thread function
                
                // Create thread
                int thread_result = pthread_create(&thread, NULL, psiphon_run_thread, args);
                if (thread_result != 0) {
                    LOGE("Failed to create thread for Psiphon Tunnel (error: %d)", thread_result);
                    // Cleanup on failure
                    if (args->clazz != NULL) {
                        (*env)->DeleteGlobalRef(env, args->clazz);
                    }
                    if (args->vpnIp != NULL) {
                        (*env)->DeleteGlobalRef(env, args->vpnIp);
                    }
                    if (args->netmask != NULL) {
                        (*env)->DeleteGlobalRef(env, args->netmask);
                    }
                    if (args->socksServer != NULL) {
                        (*env)->DeleteGlobalRef(env, args->socksServer);
                    }
                    if (args->udpgw != NULL) {
                        (*env)->DeleteGlobalRef(env, args->udpgw);
                    }
                    free(args);
                    result = -1;
                } else {
                    // Thread created successfully - detach it so it can run independently
                    pthread_detach(thread);
                    
                    // Return success immediately (function is running in background)
                    // The thread will log success/failure internally
                    result = 0;
                    LOGI("✅ Psiphon Tunnel runTun2Socks thread started successfully (function is blocking)");
                }
            }
        }
        
        // Clean up string references
        if (udpgwStr && !isNewString) {
            (*env)->ReleaseStringUTFChars(env, udpgwServer, udpgwStr);
        }
        if (isNewString && udpgw != NULL) {
            (*env)->DeleteLocalRef(env, udpgw);
        }
        
        // Check result
        if (result == 0) {
            active_library = 1;
            LOGI("Psiphon Tunnel started successfully");
        } else {
            LOGE("Psiphon Tunnel start failed");
        }
    }
    // Fallback to Bugs4U Tun2Socks (libtun2socks.so)
    else if (!usePsiphon && bugs4u_run != NULL && bugs4u_terminate != NULL) {
        LOGI("Using Bugs4U Tun2Socks (libtun2socks.so)");
        // Bugs4U: 8 parameters (includes dnsgwServer)
        // If dnsgwServer is NULL, use empty string
        jstring dnsgw = dnsgwServer;
        if (dnsgw == NULL) {
            dnsgw = (*env)->NewStringUTF(env, "");
        }
        // If udpgwServer is NULL, use empty string
        jstring udpgw = udpgwServer;
        if (udpgw == NULL) {
            udpgw = (*env)->NewStringUTF(env, "");
        }
        result = bugs4u_run(env, clazz, tunFd, mtu, vpnIp, netmask, socksServer, dnsgw, udpgw, transparentDNS);
        if (dnsgw != dnsgwServer && dnsgw != NULL) {
            (*env)->DeleteLocalRef(env, dnsgw);
        }
        if (udpgw != udpgwServer && udpgw != NULL) {
            (*env)->DeleteLocalRef(env, udpgw);
        }
        if (result == 0) {
            active_library = 2;
            LOGI("Bugs4U Tun2Socks started successfully");
        } else {
            LOGE("Bugs4U Tun2Socks start failed with result: %d", result);
        }
    } else {
        LOGE("No valid HTTP Custom library functions available");
        result = -1;
    }
    
    // Release string references
    if (vpnIpStr) (*env)->ReleaseStringUTFChars(env, vpnIp, vpnIpStr);
    if (netmaskStr) (*env)->ReleaseStringUTFChars(env, netmask, netmaskStr);
    if (socksServerStr) (*env)->ReleaseStringUTFChars(env, socksServer, socksServerStr);
    if (udpgwServerStr && udpgwServer != NULL) (*env)->ReleaseStringUTFChars(env, udpgwServer, udpgwServerStr);
    if (dnsgwServerStr && dnsgwServer != NULL) (*env)->ReleaseStringUTFChars(env, dnsgwServer, dnsgwServerStr);
    
    return result;
}

/**
 * JNI function: TProxyStopService
 * Compatible with hev-socks5-tunnel interface
 */
JNIEXPORT void JNICALL
Java_com_hyperxray_an_service_TProxyService_TProxyStopService(JNIEnv *env, jclass clazz) {
    LOGI("TProxyStopService called");
    
    if (active_library == 0) {
        LOGW("No active library to stop");
        return;
    }
    
    // Get JNIEnv if needed (for thread safety)
    JNIEnv* current_env = env;
    if (current_env == NULL && java_vm != NULL) {
        (*java_vm)->AttachCurrentThread(java_vm, &current_env, NULL);
    }
    
    if (current_env == NULL) {
        LOGE("Failed to get JNI environment for stop");
        return;
    }
    
    // Stop the active library (static methods use jclass, not jobject)
    if (active_library == 1 && psiphon_terminate != NULL) {
        LOGI("Stopping Psiphon Tunnel");
        psiphon_terminate(current_env, clazz);
    } else if (active_library == 2 && bugs4u_terminate != NULL) {
        LOGI("Stopping Bugs4U Tun2Socks");
        bugs4u_terminate(current_env, clazz);
    }
    
    // Cleanup stored parameters
    if (current_env != NULL) {
        if (httpcustom_params.vpnIp != NULL) {
            (*current_env)->DeleteGlobalRef(current_env, httpcustom_params.vpnIp);
            httpcustom_params.vpnIp = NULL;
        }
        if (httpcustom_params.netmask != NULL) {
            (*current_env)->DeleteGlobalRef(current_env, httpcustom_params.netmask);
            httpcustom_params.netmask = NULL;
        }
        if (httpcustom_params.socksServer != NULL) {
            (*current_env)->DeleteGlobalRef(current_env, httpcustom_params.socksServer);
            httpcustom_params.socksServer = NULL;
        }
        if (httpcustom_params.udpgwServer != NULL) {
            (*current_env)->DeleteGlobalRef(current_env, httpcustom_params.udpgwServer);
            httpcustom_params.udpgwServer = NULL;
        }
        if (httpcustom_params.dnsgwServer != NULL) {
            (*current_env)->DeleteGlobalRef(current_env, httpcustom_params.dnsgwServer);
            httpcustom_params.dnsgwServer = NULL;
        }
    }
    
    active_library = 0;
    LOGI("TProxyStopService completed");
}

/**
 * JNI function: TProxyGetStats
 * Compatible with hev-socks5-tunnel interface
 * Returns: [txPackets, txBytes, rxPackets, rxBytes]
 */
JNIEXPORT jlongArray JNICALL
Java_com_hyperxray_an_service_TProxyService_TProxyGetStats(JNIEnv *env, jclass clazz) {
    // TODO: Get actual stats from HTTP Custom libraries
    // For now, return null (fallback to Xray stats)
    return NULL;
}

/**
 * JNI_OnLoad - Initialize JNI
 * CRITICAL: Cache PsiphonTunnel class and method ID here so libsocks2tun.so
 * can find them even from its own threads (which don't have class loader context)
 */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    java_vm = vm;
    LOGI("HTTP Custom SOCKS5 Wrapper JNI_OnLoad");
    
    // Get JNIEnv to setup ClassLoader and cache classes
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) == JNI_OK) {
        if (env != NULL) {
            // Clear any pending exceptions
            jthrowable exc = (*env)->ExceptionOccurred(env);
            if (exc) {
                (*env)->ExceptionClear(env);
            }
            
            // Setup ClassLoader early
            setup_classloader(env);
            
            // Pre-load classes early so they're ready when libraries are loaded
            preload_classes_with_classloader(env);

            // Install FindClass hook early in JNI_OnLoad
            // This ensures the hook is active before any libraries try to find classes
            LOGI("Installing FindClass hook in JNI_OnLoad...");
            int hook_result = hook_findclass(env);
            if (hook_result == 0) {
                LOGI("✅ FindClass hook installed in JNI_OnLoad");
            } else {
                LOGW("⚠️ FindClass hook failed in JNI_OnLoad");
            }

            // CRITICAL: Cache PsiphonTunnel class and method ID for libsocks2tun.so
            // libsocks2tun.so will call FindClass from its own threads, which don't have
            // class loader context. By caching the class here, we ensure it's available.
            if (g_psiphonTunnelClass == NULL) {
                // Try FindClass first (may work if class is already loaded)
                jclass psiphonClass = (*env)->FindClass(env, "ca/psiphon/PsiphonTunnel");
                exc = (*env)->ExceptionOccurred(env);
                if (exc) {
                    (*env)->ExceptionClear(env);
                    psiphonClass = NULL;
                }
                
                if (psiphonClass == NULL && g_classLoader != NULL) {
                    // FindClass failed, try using ClassLoader
                    jclass classLoaderClass = (*env)->FindClass(env, "java/lang/ClassLoader");
                    exc = (*env)->ExceptionOccurred(env);
                    if (exc) {
                        (*env)->ExceptionClear(env);
                        classLoaderClass = NULL;
                    }
                    
                    if (classLoaderClass != NULL) {
                        jmethodID loadClassMethod = (*env)->GetMethodID(env, classLoaderClass, 
                            "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
                        exc = (*env)->ExceptionOccurred(env);
                        if (exc) {
                            (*env)->ExceptionClear(env);
                            loadClassMethod = NULL;
                        }
                        
                        if (loadClassMethod != NULL) {
                            jstring className = (*env)->NewStringUTF(env, "ca.psiphon.PsiphonTunnel");
                            psiphonClass = (jclass)(*env)->CallObjectMethod(env, g_classLoader, 
                                loadClassMethod, className);
                            exc = (*env)->ExceptionOccurred(env);
                            if (exc) {
                                (*env)->ExceptionClear(env);
                                psiphonClass = NULL;
                            }
                            (*env)->DeleteLocalRef(env, className);
                        }
                        (*env)->DeleteLocalRef(env, classLoaderClass);
                    }
                }
                
                if (psiphonClass != NULL) {
                    // Cache as global reference
                    g_psiphonTunnelClass = (*env)->NewGlobalRef(env, psiphonClass);
                    LOGI("✅ Cached PsiphonTunnel class as global reference in JNI_OnLoad");
                    
                    // Cache method ID
                    g_logTun2SocksMethod = (*env)->GetStaticMethodID(env, psiphonClass, 
                        "logTun2Socks", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                    exc = (*env)->ExceptionOccurred(env);
                    if (exc) {
                        (*env)->ExceptionClear(env);
                        g_logTun2SocksMethod = NULL;
                    }
                    
                    if (g_logTun2SocksMethod != NULL) {
                        LOGI("✅ Cached logTun2Socks method ID in JNI_OnLoad");
                    } else {
                        LOGW("⚠️ Failed to cache logTun2Socks method ID");
                    }
                    
                    (*env)->DeleteLocalRef(env, psiphonClass);
                } else {
                    LOGW("⚠️ Failed to cache PsiphonTunnel class in JNI_OnLoad (will retry later)");
                }
            } else {
                LOGI("PsiphonTunnel class already cached");
            }
        }
    }
    
    return JNI_VERSION_1_4;
}

/**
 * Unhook FindClass and restore original function pointer
 */
static void unhook_findclass(JNIEnv* env) {
    if (env == NULL || original_FindClass == NULL) {
        return;
    }

    // Get JNI function table
    JNINativeInterface** env_ptr = (JNINativeInterface**)env;
    JNINativeInterface* jni_interface = *env_ptr;

    if (jni_interface == NULL) {
        return;
    }

    // Make function table writable
    size_t page_size = sysconf(_SC_PAGESIZE);
    void* page_start = (void*)((uintptr_t)jni_interface & ~(page_size - 1));

    if (mprotect(page_start, page_size * 2, PROT_READ | PROT_WRITE) == 0) {
        // Restore original FindClass
        jni_interface->FindClass = original_FindClass;

        // Make read-only again
        mprotect(page_start, page_size * 2, PROT_READ);

        LOGI("✅ FindClass hook removed");
    }

    original_FindClass = NULL;
}

/**
 * Cleanup on library unload
 */
JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *vm, void *reserved) {
    LOGI("HTTP Custom SOCKS5 Wrapper JNI_OnUnload");
    
    // Stop service if running
    if (active_library != 0) {
        JNIEnv* env = NULL;
        if (vm != NULL && (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) == JNI_OK) {
            // Note: We can't call terminate without jclass, so just cleanup
            if (env != NULL) {
                if (httpcustom_params.vpnIp != NULL) {
                    (*env)->DeleteGlobalRef(env, httpcustom_params.vpnIp);
                    httpcustom_params.vpnIp = NULL;
                }
                if (httpcustom_params.netmask != NULL) {
                    (*env)->DeleteGlobalRef(env, httpcustom_params.netmask);
                    httpcustom_params.netmask = NULL;
                }
                if (httpcustom_params.socksServer != NULL) {
                    (*env)->DeleteGlobalRef(env, httpcustom_params.socksServer);
                    httpcustom_params.socksServer = NULL;
                }
                if (httpcustom_params.udpgwServer != NULL) {
                    (*env)->DeleteGlobalRef(env, httpcustom_params.udpgwServer);
                    httpcustom_params.udpgwServer = NULL;
                }
                if (httpcustom_params.dnsgwServer != NULL) {
                    (*env)->DeleteGlobalRef(env, httpcustom_params.dnsgwServer);
                    httpcustom_params.dnsgwServer = NULL;
                }
            }
        }
        active_library = 0;
    }
    
    // Cleanup global references and hooks
    if (java_vm != NULL) {
        JNIEnv* env = NULL;
        if ((*java_vm)->GetEnv(java_vm, (void**)&env, JNI_VERSION_1_4) == JNI_OK) {
            if (env != NULL) {
                // Unhook FindClass before cleanup
                unhook_findclass(env);

                if (g_psiphonTunnelClass != NULL) {
                    (*env)->DeleteGlobalRef(env, g_psiphonTunnelClass);
                    g_psiphonTunnelClass = NULL;
                    g_logTun2SocksMethod = NULL;
                    LOGI("Cleaned up PsiphonTunnel global references");
                }

                if (g_classLoader != NULL) {
                    (*env)->DeleteGlobalRef(env, g_classLoader);
                    g_classLoader = NULL;
                    LOGI("Cleaned up ClassLoader global reference");
                }
            }
        }
    }
    
    
    // Unload libraries
    if (libsocks_handle) {
        dlclose(libsocks_handle);
        libsocks_handle = NULL;
    }
    if (libsocks2tun_handle) {
        dlclose(libsocks2tun_handle);
        libsocks2tun_handle = NULL;
    }
    if (libtun2socks_handle) {
        dlclose(libtun2socks_handle);
        libtun2socks_handle = NULL;
    }
    
    java_vm = NULL;
}

