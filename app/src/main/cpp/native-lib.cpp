#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_fireairplay_receiver_server_AirPlayNativeWrapper_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++ (NDK Ready for UxPlay Integration)";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fireairplay_receiver_server_AirPlayNativeWrapper_initNative(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("AirPlay Native Core Initialized");
    // Future: Initialize UxPlay decrypters, Ed25519/Curve25519 keys here
}
