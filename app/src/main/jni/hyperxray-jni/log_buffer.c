/**
 * Log Buffer Implementation for Batched JNI Logging
 * 
 * Reduces JNI overhead by buffering log messages and flushing them
 * in batches to Java. This significantly reduces:
 * - JNI thread attachment/detachment overhead
 * - Java string allocation and GC pressure
 * - Context switching between native and Java
 */

#include "log_buffer.h"
#include <jni.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#define LOG_TAG "LogBuffer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global log buffer
static LogBuffer g_log_buffer = {
    .count = 0,
    .total_bytes = 0,
    .last_flush_time_ms = 0,
    .initialized = false
};

// External JNI references (defined in hyperxray-jni.c)
extern JavaVM* g_jvm;
extern jobject g_serviceInstance;

// Batch logging method ID (will be set during initialization)
static jmethodID g_logBatchMethodID = NULL;
static bool g_batch_method_checked = false;

// Forward declaration
static void flush_to_java(LogEntry* entries, int count);

int64_t get_current_time_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void log_buffer_init(void) {
    if (g_log_buffer.initialized) {
        return;
    }
    
    pthread_mutex_init(&g_log_buffer.mutex, NULL);
    g_log_buffer.count = 0;
    g_log_buffer.total_bytes = 0;
    g_log_buffer.last_flush_time_ms = get_current_time_ms();
    g_log_buffer.initialized = true;
    
    LOGI("Log buffer initialized (max_entries=%d, max_bytes=%d, flush_interval=%dms)",
         LOG_BUFFER_MAX_ENTRIES, LOG_BUFFER_MAX_BYTES, LOG_BUFFER_FLUSH_INTERVAL_MS);
}

void log_buffer_cleanup(void) {
    if (!g_log_buffer.initialized) {
        return;
    }
    
    // Flush any remaining logs
    log_buffer_flush();
    
    pthread_mutex_destroy(&g_log_buffer.mutex);
    g_log_buffer.initialized = false;
    
    LOGI("Log buffer cleaned up");
}

bool log_buffer_add(const char* tag, const char* level, const char* message) {
    if (!g_log_buffer.initialized) {
        log_buffer_init();
    }
    
    bool flushed = false;
    bool is_error = (level != NULL && (level[0] == 'E' || level[0] == 'e'));
    
    pthread_mutex_lock(&g_log_buffer.mutex);
    
    // Check if we need to flush before adding
    int64_t now = get_current_time_ms();
    bool time_exceeded = (now - g_log_buffer.last_flush_time_ms) >= LOG_BUFFER_FLUSH_INTERVAL_MS;
    bool buffer_full = g_log_buffer.count >= LOG_BUFFER_MAX_ENTRIES;
    bool size_exceeded = g_log_buffer.total_bytes >= LOG_BUFFER_MAX_BYTES;
    
    if ((buffer_full || size_exceeded || time_exceeded) && g_log_buffer.count > 0) {
        // Copy entries for flushing
        LogEntry entries_copy[LOG_BUFFER_MAX_ENTRIES];
        int count_copy = g_log_buffer.count;
        memcpy(entries_copy, g_log_buffer.entries, count_copy * sizeof(LogEntry));
        
        // Reset buffer
        g_log_buffer.count = 0;
        g_log_buffer.total_bytes = 0;
        g_log_buffer.last_flush_time_ms = now;
        
        pthread_mutex_unlock(&g_log_buffer.mutex);
        
        // Flush outside of lock
        flush_to_java(entries_copy, count_copy);
        flushed = true;
        
        pthread_mutex_lock(&g_log_buffer.mutex);
    }
    
    // Add new entry
    if (g_log_buffer.count < LOG_BUFFER_MAX_ENTRIES) {
        LogEntry* entry = &g_log_buffer.entries[g_log_buffer.count];
        
        // Copy tag (truncate if needed)
        if (tag != NULL) {
            strncpy(entry->tag, tag, LOG_TAG_MAX_LEN - 1);
            entry->tag[LOG_TAG_MAX_LEN - 1] = '\0';
        } else {
            entry->tag[0] = '\0';
        }
        
        // Copy level
        if (level != NULL) {
            strncpy(entry->level, level, LOG_LEVEL_MAX_LEN - 1);
            entry->level[LOG_LEVEL_MAX_LEN - 1] = '\0';
        } else {
            strcpy(entry->level, "DEBUG");
        }
        
        // Copy message (truncate if needed)
        if (message != NULL) {
            strncpy(entry->message, message, LOG_MESSAGE_MAX_LEN - 1);
            entry->message[LOG_MESSAGE_MAX_LEN - 1] = '\0';
        } else {
            entry->message[0] = '\0';
        }
        
        entry->timestamp_ms = now;
        
        // Update counters
        g_log_buffer.count++;
        g_log_buffer.total_bytes += strlen(entry->tag) + strlen(entry->level) + strlen(entry->message);
    }
    
    pthread_mutex_unlock(&g_log_buffer.mutex);
    
    // For ERROR level, flush immediately after adding
    if (is_error) {
        log_buffer_flush();
        flushed = true;
    }
    
    return flushed;
}

void log_buffer_flush(void) {
    if (!g_log_buffer.initialized) {
        return;
    }
    
    pthread_mutex_lock(&g_log_buffer.mutex);
    
    if (g_log_buffer.count == 0) {
        pthread_mutex_unlock(&g_log_buffer.mutex);
        return;
    }
    
    // Copy entries for flushing
    LogEntry entries_copy[LOG_BUFFER_MAX_ENTRIES];
    int count_copy = g_log_buffer.count;
    memcpy(entries_copy, g_log_buffer.entries, count_copy * sizeof(LogEntry));
    
    // Reset buffer
    g_log_buffer.count = 0;
    g_log_buffer.total_bytes = 0;
    g_log_buffer.last_flush_time_ms = get_current_time_ms();
    
    pthread_mutex_unlock(&g_log_buffer.mutex);
    
    // Flush outside of lock
    flush_to_java(entries_copy, count_copy);
}

void log_buffer_check_time_flush(void) {
    if (!g_log_buffer.initialized) {
        return;
    }
    
    pthread_mutex_lock(&g_log_buffer.mutex);
    
    int64_t now = get_current_time_ms();
    bool should_flush = (now - g_log_buffer.last_flush_time_ms) >= LOG_BUFFER_FLUSH_INTERVAL_MS;
    
    if (should_flush && g_log_buffer.count > 0) {
        // Copy entries for flushing
        LogEntry entries_copy[LOG_BUFFER_MAX_ENTRIES];
        int count_copy = g_log_buffer.count;
        memcpy(entries_copy, g_log_buffer.entries, count_copy * sizeof(LogEntry));
        
        // Reset buffer
        g_log_buffer.count = 0;
        g_log_buffer.total_bytes = 0;
        g_log_buffer.last_flush_time_ms = now;
        
        pthread_mutex_unlock(&g_log_buffer.mutex);
        
        // Flush outside of lock
        flush_to_java(entries_copy, count_copy);
    } else {
        pthread_mutex_unlock(&g_log_buffer.mutex);
    }
}

/**
 * Flush log entries to Java via JNI batch call
 */
static void flush_to_java(LogEntry* entries, int count) {
    if (count == 0) {
        return;
    }
    
    if (g_jvm == NULL || g_serviceInstance == NULL) {
        LOGD("JNI not ready, dropping %d log entries", count);
        return;
    }
    
    JNIEnv* env = NULL;
    bool need_detach = false;
    
    // Get JNI environment
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        status = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        if (status != JNI_OK) {
            LOGE("Failed to attach thread for batch logging");
            return;
        }
        need_detach = true;
    } else if (status != JNI_OK) {
        LOGE("Failed to get JNI environment for batch logging");
        return;
    }
    
    // Get batch method ID if not cached
    if (!g_batch_method_checked) {
        g_batch_method_checked = true;
        jclass clazz = (*env)->GetObjectClass(env, g_serviceInstance);
        if (clazz != NULL) {
            g_logBatchMethodID = (*env)->GetMethodID(
                env, clazz, "logBatchToAiLogHelper",
                "([Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V"
            );
            if (g_logBatchMethodID == NULL) {
                (*env)->ExceptionClear(env);
                LOGD("logBatchToAiLogHelper not found, batch logging disabled");
            } else {
                LOGI("Batch logging method found and cached");
            }
            (*env)->DeleteLocalRef(env, clazz);
        }
    }
    
    if (g_logBatchMethodID == NULL) {
        // Fallback: batch method not available, skip (logs already went to Android logcat)
        if (need_detach) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }
    
    // Create String arrays
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        LOGE("Failed to find String class");
        if (need_detach) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }
    
    jobjectArray tagsArray = (*env)->NewObjectArray(env, count, stringClass, NULL);
    jobjectArray levelsArray = (*env)->NewObjectArray(env, count, stringClass, NULL);
    jobjectArray messagesArray = (*env)->NewObjectArray(env, count, stringClass, NULL);
    
    if (tagsArray == NULL || levelsArray == NULL || messagesArray == NULL) {
        LOGE("Failed to create String arrays for batch logging");
        if (tagsArray) (*env)->DeleteLocalRef(env, tagsArray);
        if (levelsArray) (*env)->DeleteLocalRef(env, levelsArray);
        if (messagesArray) (*env)->DeleteLocalRef(env, messagesArray);
        (*env)->DeleteLocalRef(env, stringClass);
        if (need_detach) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }
    
    // Fill arrays
    for (int i = 0; i < count; i++) {
        jstring jTag = (*env)->NewStringUTF(env, entries[i].tag);
        jstring jLevel = (*env)->NewStringUTF(env, entries[i].level);
        jstring jMessage = (*env)->NewStringUTF(env, entries[i].message);
        
        if (jTag) {
            (*env)->SetObjectArrayElement(env, tagsArray, i, jTag);
            (*env)->DeleteLocalRef(env, jTag);
        }
        if (jLevel) {
            (*env)->SetObjectArrayElement(env, levelsArray, i, jLevel);
            (*env)->DeleteLocalRef(env, jLevel);
        }
        if (jMessage) {
            (*env)->SetObjectArrayElement(env, messagesArray, i, jMessage);
            (*env)->DeleteLocalRef(env, jMessage);
        }
    }
    
    // Call batch method
    (*env)->CallVoidMethod(env, g_serviceInstance, g_logBatchMethodID,
                          tagsArray, levelsArray, messagesArray);
    
    // Check for exceptions
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    
    // Cleanup
    (*env)->DeleteLocalRef(env, tagsArray);
    (*env)->DeleteLocalRef(env, levelsArray);
    (*env)->DeleteLocalRef(env, messagesArray);
    (*env)->DeleteLocalRef(env, stringClass);
    
    if (need_detach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    
    LOGD("Flushed %d log entries to Java", count);
}
