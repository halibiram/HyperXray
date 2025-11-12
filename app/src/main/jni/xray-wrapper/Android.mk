LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := xray-wrapper
LOCAL_SRC_FILES := xray-wrapper.c
LOCAL_CFLAGS := -Wall -Wextra -O2
LOCAL_LDLIBS := -ldl

# Build as executable, not shared library
include $(BUILD_EXECUTABLE)







