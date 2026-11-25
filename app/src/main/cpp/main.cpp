#include <android_native_app_glue.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include <android/asset_manager.h>
#include <android/configuration.h>
#include <android/input.h>
#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>

#include "imgui.h"
#include "imgui_impl_android.h"
#include "imgui_impl_opengl3.h"

#include "camera_engine.h"
#include "sensor_engine.h"
#include "vision_engine.h"

#define LOG_TAG "DiplomkaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct EglState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    EGLint width = 0;
    EGLint height = 0;
};

struct HitRect {
    float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool contains(float x, float y) const {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }
};

struct AppState {
    android_app* app = nullptr;
    CameraEngine camera;
    SensorEngine sensors;
    VisionEngine vision;

    std::mutex frameMutex;
    std::vector<uint8_t> latestRgba;
    int latestWidth = 0;
    int latestHeight = 0;
    bool hasNewFrame = false;

    GLuint previewTexture = 0;
    int previewTextureWidth = 0;
    int previewTextureHeight = 0;

    bool torchOn = false;
    bool detectionEnabled = true;
    bool showPose = true;
    bool stopSignal = false;

    std::deque<std::string> commandLog;
    std::mutex logMutex;

    float lastInferenceMs = 0.0f;
    int detectionCount = 0;
    int poseCount = 0;

    bool animating = true;
    bool hasFocus = false;
    bool imguiInitialized = false;
    bool cameraStarted = false;
    int cameraRetryCount = 0;
    std::chrono::steady_clock::time_point lastCameraAttempt{};

    HitRect btnTorch{}, btnDetect{}, btnPose{}, btnRestart{};
    std::atomic<int> touchCount{0};
    std::atomic<int> pendingAction{0};
};

enum Action : int { ActNone = 0, ActTorch = 1, ActDetect = 2, ActPose = 3, ActRestart = 4 };

void pushLog(AppState& state, const std::string& line) {
    std::lock_guard<std::mutex> lock(state.logMutex);
    state.commandLog.push_front(line);
    while (state.commandLog.size() > 16) state.commandLog.pop_back();
}

bool jniHasCameraPermission(android_app* app) {
    if (!app || !app->activity || !app->activity->vm || !app->activity->clazz) return false;
    JavaVM* vm = app->activity->vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) return false;
        attached = true;
    }
    bool granted = false;
    jclass cls = env->GetObjectClass(app->activity->clazz);
    jmethodID mid = env->GetMethodID(cls, "checkSelfPermission", "(Ljava/lang/String;)I");
    jstring perm = env->NewStringUTF("android.permission.CAMERA");
    if (mid && perm) {
        granted = env->CallIntMethod(app->activity->clazz, mid, perm) == 0;
    }
    if (perm) env->DeleteLocalRef(perm);
    if (cls) env->DeleteLocalRef(cls);
    if (attached) vm->DetachCurrentThread();
    return granted;
}

bool jniOpenAppSettings(android_app* app) {
    if (!app || !app->activity || !app->activity->vm || !app->activity->clazz) return false;
    JavaVM* vm = app->activity->vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) return false;
        attached = true;
    }
    bool ok = false;
    jclass activityClass = env->GetObjectClass(app->activity->clazz);
    jmethodID getPackageName = env->GetMethodID(activityClass, "getPackageName", "()Ljava/lang/String;");
    jmethodID startActivity = env->GetMethodID(activityClass, "startActivity", "(Landroid/content/Intent;)V");
    jclass intentClass = env->FindClass("android/content/Intent");
    jclass uriClass = env->FindClass("android/net/Uri");
    jmethodID intentCtor = env->GetMethodID(intentClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID setData = env->GetMethodID(intentClass, "setData", "(Landroid/net/Uri;)Landroid/content/Intent;");
    jmethodID parse = env->GetStaticMethodID(uriClass, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jstring action = env->NewStringUTF("android.settings.APPLICATION_DETAILS_SETTINGS");
    jobject intent = env->NewObject(intentClass, intentCtor, action);
    jstring pkg = (jstring)env->CallObjectMethod(app->activity->clazz, getPackageName);
    const char* pkgChars = env->GetStringUTFChars(pkg, nullptr);
    std::string uriStr = std::string("package:") + (pkgChars ? pkgChars : "com.diplomka.app");
    if (pkgChars) env->ReleaseStringUTFChars(pkg, pkgChars);
    jstring uriJ = env->NewStringUTF(uriStr.c_str());
    jobject uri = env->CallStaticObjectMethod(uriClass, parse, uriJ);
    env->CallObjectMethod(intent, setData, uri);
    if (startActivity && intent) {
        env->CallVoidMethod(app->activity->clazz, startActivity, intent);
        if (!env->ExceptionCheck()) ok = true;
        else env->ExceptionClear();
    }
    if (uriJ) env->DeleteLocalRef(uriJ);
    if (uri) env->DeleteLocalRef(uri);
    if (pkg) env->DeleteLocalRef(pkg);
    if (action) env->DeleteLocalRef(action);
    if (intent) env->DeleteLocalRef(intent);
    if (uriClass) env->DeleteLocalRef(uriClass);
    if (intentClass) env->DeleteLocalRef(intentClass);
    if (activityClass) env->DeleteLocalRef(activityClass);
    if (attached) vm->DetachCurrentThread();
    return ok;
}
