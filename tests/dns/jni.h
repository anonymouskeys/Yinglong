/* Minimal JNI test double. Production compilation uses Android NDK jni.h. */
#ifndef TEST_JNI_H
#define TEST_JNI_H
#include <stddef.h>
#define JNI_OK 0
#define JNI_VERSION_1_6 0x10006
typedef void *jobject;
typedef void *jclass;
typedef void *jmethodID;
typedef void *jstring;
typedef int jint;
typedef const struct JNINativeInterface *JNIEnv;
typedef const struct JNIInvokeInterface *JavaVM;
struct JNINativeInterface {
    jclass (*FindClass)(JNIEnv *, const char *);
    jobject (*NewGlobalRef)(JNIEnv *, jobject);
    void (*DeleteLocalRef)(JNIEnv *, jobject);
    jmethodID (*GetStaticMethodID)(JNIEnv *, jclass, const char *, const char *);
    jstring (*NewStringUTF)(JNIEnv *, const char *);
    jobject (*CallStaticObjectMethod)(JNIEnv *, jclass, jmethodID, ...);
    int (*ExceptionCheck)(JNIEnv *);
    void (*ExceptionClear)(JNIEnv *);
    const char *(*GetStringUTFChars)(JNIEnv *, jstring, void *);
    void (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);
};
struct JNIInvokeInterface {
    jint (*GetEnv)(JavaVM *, void **, jint);
    jint (*AttachCurrentThread)(JavaVM *, JNIEnv **, void *);
    jint (*DetachCurrentThread)(JavaVM *);
};
#endif
