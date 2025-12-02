LOCAL_PATH := $(call my-dir)

# ============================================================================
# JNI Wrapper Module
# ============================================================================
include $(CLEAR_VARS)

LOCAL_MODULE := hyperxray-jni
LOCAL_SRC_FILES := hyperxray-jni.c log_buffer.c

# Link against log library for Android logging and dl library for dlopen/dlsym
LOCAL_LDLIBS := -llog -ldl

# C flags
LOCAL_CFLAGS := -Wall -Wextra -O2

# Export all symbols (important for JNI)
# This ensures g_protector is available when Go library loads
# --export-dynamic makes all symbols available for dynamic linking
LOCAL_LDFLAGS := -Wl,--export-dynamic

# Include JNI headers
LOCAL_C_INCLUDES := $(JNI_H_INCLUDE)

# Build as shared library
include $(BUILD_SHARED_LIBRARY)

