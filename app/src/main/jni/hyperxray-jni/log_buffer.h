/**
 * Log Buffer for Batched JNI Logging
 * 
 * This header defines the log buffering mechanism to reduce JNI overhead.
 * Instead of calling Java for every log message, logs are buffered and
 * flushed in batches.
 * 
 * Flush triggers:
 * - Buffer full (LOG_BUFFER_MAX_ENTRIES)
 * - Buffer size exceeds LOG_BUFFER_MAX_BYTES
 * - Time interval exceeded (LOG_BUFFER_FLUSH_INTERVAL_MS)
 * - ERROR level log (immediate flush)
 */

#ifndef LOG_BUFFER_H
#define LOG_BUFFER_H

#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>

// Buffer configuration
#define LOG_BUFFER_MAX_ENTRIES 50
#define LOG_BUFFER_MAX_BYTES 4096
#define LOG_BUFFER_FLUSH_INTERVAL_MS 500
#define LOG_TAG_MAX_LEN 64
#define LOG_LEVEL_MAX_LEN 8
#define LOG_MESSAGE_MAX_LEN 512

// Log entry structure
typedef struct {
    char tag[LOG_TAG_MAX_LEN];
    char level[LOG_LEVEL_MAX_LEN];
    char message[LOG_MESSAGE_MAX_LEN];
    int64_t timestamp_ms;
} LogEntry;

// Log buffer structure
typedef struct {
    LogEntry entries[LOG_BUFFER_MAX_ENTRIES];
    int count;
    int total_bytes;
    int64_t last_flush_time_ms;
    pthread_mutex_t mutex;
    bool initialized;
} LogBuffer;

// Initialize the log buffer (call once at startup)
void log_buffer_init(void);

// Cleanup the log buffer (call at shutdown)
void log_buffer_cleanup(void);

// Add a log entry to the buffer
// Returns true if buffer was flushed
bool log_buffer_add(const char* tag, const char* level, const char* message);

// Force flush the buffer to Java
void log_buffer_flush(void);

// Check if buffer should be flushed based on time
void log_buffer_check_time_flush(void);

// Get current time in milliseconds
int64_t get_current_time_ms(void);

#endif // LOG_BUFFER_H
