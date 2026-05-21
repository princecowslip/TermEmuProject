// jni_bridge.cpp
// JNI implementation layer: bridges Kotlin JVM calls to native POSIX pipelines.

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "pty_manager.h"
#include "kitty_parser.h"
#include "media_rescaler.h"
#include "audio_decoder.h"

#define LOG_TAG "JniBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Cached JVM class/field references ──────────────────────────────────────

static jclass    g_ptyResultClass   = nullptr;
static jmethodID g_ptyResultCtor    = nullptr;
static JavaVM*   g_jvm              = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Cache PtyResult class and constructor for fast repeated allocation
    jclass local = env->FindClass("com/yourname/termemu/JniBridge$PtyResult");
    if (!local) { LOGE("PtyResult class not found"); return JNI_ERR; }
    g_ptyResultClass = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_ptyResultCtor = env->GetMethodID(g_ptyResultClass, "<init>", "(II)V");
    if (!g_ptyResultCtor) { LOGE("PtyResult ctor not found"); return JNI_ERR; }

    LOGI("JNI_OnLoad complete");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env && g_ptyResultClass) env->DeleteGlobalRef(g_ptyResultClass);
}

// ── PTY management ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_yourname_termemu_JniBridge_createPty(
    JNIEnv* env, jobject /*thiz*/,
    jstring command, jobjectArray args)
{
    const char* cmd = env->GetStringUTFChars(command, nullptr);

    jsize argc = env->GetArrayLength(args);
    std::vector<std::string> argVec;
    argVec.reserve(argc);
    for (jsize i = 0; i < argc; ++i) {
        auto jstr    = reinterpret_cast<jstring>(env->GetObjectArrayElement(args, i));
        const char* utf = env->GetStringUTFChars(jstr, nullptr);
        argVec.emplace_back(utf);                 // copy into std::string
        env->ReleaseStringUTFChars(jstr, utf);    // release immediately after copy
        env->DeleteLocalRef(jstr);
    }

    PtyResult result = pty_create(cmd, argVec);
    env->ReleaseStringUTFChars(command, cmd);

    LOGI("createPty: pid=%d fd=%d", result.pid, result.master_fd);
    return env->NewObject(g_ptyResultClass, g_ptyResultCtor,
                          static_cast<jint>(result.master_fd),
                          static_cast<jint>(result.pid));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_termemu_JniBridge_resizePty(
    JNIEnv* /*env*/, jobject /*thiz*/, jint masterFd, jint cols, jint rows)
{
    pty_resize(masterFd, static_cast<unsigned short>(cols), static_cast<unsigned short>(rows));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_termemu_JniBridge_killProcess(
    JNIEnv* /*env*/, jobject /*thiz*/, jint pid)
{
    pty_kill(pid);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_termemu_JniBridge_waitForProcess(
    JNIEnv* /*env*/, jobject /*thiz*/, jint pid)
{
    return static_cast<jint>(pty_wait(pid));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_termemu_JniBridge_closeFd(
    JNIEnv* /*env*/, jobject /*thiz*/, jint fd)
{
    pty_close_fd(fd);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_yourname_termemu_JniBridge_fdToFileDescriptor(
    JNIEnv* env, jobject /*thiz*/, jint fd)
{
    // Obtain java.io.FileDescriptor and set its 'fd' field
    jclass fdClass   = env->FindClass("java/io/FileDescriptor");
    jmethodID fdCtor = env->GetMethodID(fdClass, "<init>", "()V");
    jfieldID  fdFd   = env->GetFieldID(fdClass, "fd", "I");

    jobject fdObj = env->NewObject(fdClass, fdCtor);
    env->SetIntField(fdObj, fdFd, fd);
    env->DeleteLocalRef(fdClass);
    return fdObj;
}

// ── Image rescaling ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jintArray JNICALL
Java_com_yourname_termemu_JniBridge_rescaleImage(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray rawPixels,
    jint srcW, jint srcH,
    jint dstW, jint dstH)
{
    jsize len = env->GetArrayLength(rawPixels);
    jbyte* src = env->GetByteArrayElements(rawPixels, nullptr);

    std::vector<uint32_t> dst(dstW * dstH);
    media_rescale(
        reinterpret_cast<const uint8_t*>(src), srcW, srcH,
        dst.data(), dstW, dstH
    );

    env->ReleaseByteArrayElements(rawPixels, src, JNI_ABORT);

    jintArray result = env->NewIntArray(static_cast<jsize>(dst.size()));
    env->SetIntArrayRegion(result, 0,
                           static_cast<jsize>(dst.size()),
                           reinterpret_cast<const jint*>(dst.data()));
    return result;
}

// ── Audio decoding ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_yourname_termemu_JniBridge_decodeAudioFrame(
    JNIEnv* env, jobject /*thiz*/, jbyteArray encoded)
{
    jsize len    = env->GetArrayLength(encoded);
    jbyte* bytes = env->GetByteArrayElements(encoded, nullptr);

    std::vector<int16_t> pcm = audio_decode_frame(
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len)
    );

    env->ReleaseByteArrayElements(encoded, bytes, JNI_ABORT);

    jshortArray result = env->NewShortArray(static_cast<jsize>(pcm.size()));
    env->SetShortArrayRegion(result, 0,
                             static_cast<jsize>(pcm.size()),
                             reinterpret_cast<const jshort*>(pcm.data()));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_termemu_JniBridge_openAudioStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint channelCount)
{
    return static_cast<jint>(audio_open_stream(sampleRate, channelCount));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_termemu_JniBridge_closeAudioStream(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    audio_close_stream();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_termemu_JniBridge_flushAudioDecoder(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    audio_decoder_flush();
}
