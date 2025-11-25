LOCAL_PATH := $(call my-dir)

# Prebuilt Go shared library
include $(CLEAR_VARS)
LOCAL_MODULE := tun2sock
LOCAL_SRC_FILES := ../../jniLibs/$(TARGET_ARCH_ABI)/libtun2sock.so
include $(PREBUILT_SHARED_LIBRARY)

# JNI Wrapper
include $(CLEAR_VARS)
LOCAL_MODULE := tun2sock-wrapper
LOCAL_SRC_FILES := tun2sock-wrapper.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../include
LOCAL_SHARED_LIBRARIES := tun2sock
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
