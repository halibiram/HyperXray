#include <jni.h>
#include <string.h>
#include <android/log.h>
#include "tun2sock.h"

#define TAG "Tun2SockWrapper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT void JNICALL
Java_com_hyperxray_an_service_managers_Tun2SockManager_start(JNIEnv *env, jobject thiz, jint tun_fd, jstring proxy_url) {
    const char *url = (*env)->GetStringUTFChars(env, proxy_url, 0);

    LOGD("Starting tun2sock with fd=%d, url=%s", tun_fd, url);

    // Call Go function
    // The Go function is exported as Start(int, char*)
    Start((int)tun_fd, (char*)url);

    (*env)->ReleaseStringUTFChars(env, proxy_url, url);
}

JNIEXPORT void JNICALL
Java_com_hyperxray_an_service_managers_Tun2SockManager_stop(JNIEnv *env, jobject thiz) {
    LOGD("Stopping tun2sock");
    Stop();
}
