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

bool initEgl(android_app* app, EglState& egl) {
    egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl.display == EGL_NO_DISPLAY) return false;
    if (!eglInitialize(egl.display, nullptr, nullptr)) return false;

    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint n = 0;
    if (!eglChooseConfig(egl.display, attribs, &config, 1, &n) || n == 0) return false;

    EGLint format = 0;
    eglGetConfigAttrib(egl.display, config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(app->window, 0, 0, format);

    egl.surface = eglCreateWindowSurface(egl.display, config, app->window, nullptr);
    if (egl.surface == EGL_NO_SURFACE) return false;

    const EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    egl.context = eglCreateContext(egl.display, config, EGL_NO_CONTEXT, ctxAttr);
    if (egl.context == EGL_NO_CONTEXT) return false;
    if (!eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context)) return false;

    eglQuerySurface(egl.display, egl.surface, EGL_WIDTH, &egl.width);
    eglQuerySurface(egl.display, egl.surface, EGL_HEIGHT, &egl.height);
    LOGI("EGL %dx%d", egl.width, egl.height);
    return egl.width > 0 && egl.height > 0;
}

void shutdownEgl(EglState& egl) {
    if (egl.display != EGL_NO_DISPLAY) {
        eglMakeCurrent(egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (egl.context != EGL_NO_CONTEXT) eglDestroyContext(egl.display, egl.context);
        if (egl.surface != EGL_NO_SURFACE) eglDestroySurface(egl.display, egl.surface);
        eglTerminate(egl.display);
    }
    egl = {};
}

void initImGui(android_app* app, EglState& egl) {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    float scale = std::clamp(egl.height / 900.0f, 1.2f, 2.5f);
    io.FontGlobalScale = scale * 0.65f;

    ImGui::StyleColorsDark();
    ImGuiStyle& st = ImGui::GetStyle();
    st.WindowRounding = 0;
    st.ChildRounding = 12;
    st.FrameRounding = 10;
    st.WindowPadding = ImVec2(10, 10);
    st.ItemSpacing = ImVec2(10, 10);
    st.FramePadding = ImVec2(12, 10);

    ImGui_ImplAndroid_Init(app->window);
    ImGui_ImplOpenGL3_Init("#version 300 es");
    io.DisplaySize = ImVec2((float)egl.width, (float)egl.height);
    io.DisplayFramebufferScale = ImVec2(1, 1);
}

void shutdownImGui() {
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplAndroid_Shutdown();
    ImGui::DestroyContext();
}

void uploadPreviewTexture(AppState& state, const uint8_t* rgba, int w, int h) {
    if (!rgba || w <= 0 || h <= 0) return;
    if (!state.previewTexture) glGenTextures(1, &state.previewTexture);
    glBindTexture(GL_TEXTURE_2D, state.previewTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (state.previewTextureWidth != w || state.previewTextureHeight != h) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        state.previewTextureWidth = w;
        state.previewTextureHeight = h;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    }
    glBindTexture(GL_TEXTURE_2D, 0);
}

float proximityFromBox(const RectF& box, int fw, int fh) {
    if (fw <= 0 || fh <= 0) return 0.f;
    float area = std::max(0.f, box.right - box.left) * std::max(0.f, box.bottom - box.top);
    return std::clamp(area / (float(fw) * float(fh)), 0.f, 1.f);
}

void tryStartCamera(AppState& state) {
    state.lastCameraAttempt = std::chrono::steady_clock::now();
    state.cameraRetryCount++;

    if (!jniHasCameraPermission(state.app)) {
        state.cameraStarted = false;
        pushLog(state, "CAMERA permission missing — open App Info");
        if (state.cameraRetryCount == 1) {
            if (jniOpenAppSettings(state.app)) pushLog(state, "Opened App Info; enable CAMERA and return");
        }
        return;
    }
    if (!state.hasFocus) {
        pushLog(state, "Waiting for window focus");
        return;
    }

    bool ok = state.camera.start([&state](std::vector<uint8_t>&& rgba, int w, int h) {
        std::lock_guard<std::mutex> lock(state.frameMutex);
        state.latestRgba = std::move(rgba);
        state.latestWidth = w;
        state.latestHeight = h;
        state.hasNewFrame = true;
    });
    state.cameraStarted = ok;
    pushLog(state, ok ? ("Camera OK try=" + std::to_string(state.cameraRetryCount))
                      : (std::string("Camera FAIL: ") + state.camera.lastError()));
}

void drawHitButton(const char* label, HitRect& outRect, bool active, const ImVec2& size) {
    ImVec2 p = ImGui::GetCursorScreenPos();
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImU32 col = active ? IM_COL32(30, 140, 90, 255) : IM_COL32(40, 100, 180, 255);
    ImU32 colBorder = IM_COL32(255, 255, 255, 40);
    dl->AddRectFilled(p, ImVec2(p.x + size.x, p.y + size.y), col, 12.0f);
    dl->AddRect(p, ImVec2(p.x + size.x, p.y + size.y), colBorder, 12.0f, 0, 2.0f);
    ImVec2 ts = ImGui::CalcTextSize(label);
    dl->AddText(ImVec2(p.x + (size.x - ts.x) * 0.5f, p.y + (size.y - ts.y) * 0.5f),
                IM_COL32(255, 255, 255, 255), label);
    outRect = {p.x, p.y, p.x + size.x, p.y + size.y};
    ImGui::Dummy(size);
}

void applyPendingAction(AppState& state) {
    int act = state.pendingAction.exchange(ActNone);
    if (act == ActNone) return;
    LOGI("Action %d", act);
    switch (act) {
        case ActTorch:
            state.torchOn = !state.torchOn;
            state.camera.setTorch(state.torchOn);
            pushLog(state, state.torchOn ? "Torch ON" : "Torch OFF");
            break;
        case ActDetect:
            state.detectionEnabled = !state.detectionEnabled;
            pushLog(state, state.detectionEnabled ? "Detect ON" : "Detect OFF");
            break;
        case ActPose:
            state.showPose = !state.showPose;
            pushLog(state, state.showPose ? "Pose ON" : "Pose OFF");
            break;
        case ActRestart:
            state.camera.stop();
            state.cameraStarted = false;
            tryStartCamera(state);
            break;
        default:
            break;
    }
}

void drawUi(AppState& state, EglState& egl) {
    ImGuiIO& io = ImGui::GetIO();
    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    io.DisplaySize = ImVec2((float)egl.width, (float)egl.height);
    ImGui::NewFrame();

    const float W = (float)egl.width;
    const float H = (float)egl.height;
    const float pad = 16.0f;
    const float btnH = 72.0f;

    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(ImVec2(W, H));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0, 0));
    ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0, 0, 0, 0));
    ImGui::Begin("root", nullptr,
                 ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                 ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoScrollbar |
                 ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoBringToFrontOnFocus);

    ImDrawList* dl = ImGui::GetWindowDrawList();

    if (state.previewTexture && state.previewTextureWidth > 0) {
        float ta = (float)state.previewTextureWidth / state.previewTextureHeight;
        float sa = W / H;
        ImVec2 size, off;
        if (sa > ta) { size = ImVec2(W, W / ta); off = ImVec2(0, (H - size.y) * 0.5f); }
        else { size = ImVec2(H * ta, H); off = ImVec2((W - size.x) * 0.5f, 0); }
        dl->AddImage((ImTextureID)(uintptr_t)state.previewTexture, off, ImVec2(off.x + size.x, off.y + size.y));
        if (state.stopSignal) {
            dl->AddRectFilled(ImVec2(0, 0), ImVec2(W, H), IM_COL32(180, 20, 20, 90));
            ImVec2 ts = ImGui::CalcTextSize("STOP");
            dl->AddText(ImVec2((W - ts.x) * 0.5f, H * 0.4f), IM_COL32(255, 60, 60, 255), "STOP");
        }
    } else {
        dl->AddRectFilled(ImVec2(0, 0), ImVec2(W, H), IM_COL32(12, 14, 18, 255));
        const char* msg = state.cameraStarted ? "Camera open - waiting frames" : "Camera offline";
        ImVec2 ts = ImGui::CalcTextSize(msg);
        dl->AddText(ImVec2((W - ts.x) * 0.5f, H * 0.36f), IM_COL32(230, 230, 240, 255), msg);
        if (!jniHasCameraPermission(state.app)) {
            const char* h = "Enable CAMERA in App Permissions";
            ImVec2 hs = ImGui::CalcTextSize(h);
            dl->AddText(ImVec2((W - hs.x) * 0.5f, H * 0.36f + ts.y + 12), IM_COL32(255, 160, 70, 255), h);
        }
    }

    ImGui::SetCursorPos(ImVec2(pad, pad));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.05f, 0.06f, 0.08f, 0.75f));
    ImGui::BeginChild("hud", ImVec2(W - pad * 2, 52), true, ImGuiWindowFlags_NoScrollbar);
    {
        const char* cam = !state.camera.isRunning() ? "OFF"
                         : (state.camera.framesReceived() > 0 ? "LIVE" : "NO FRAMES");
        int hdg = state.sensors.hasHeading() ? (int)state.sensors.headingDegrees() : -1;
        if (hdg >= 0)
            ImGui::Text("Diplomka | %s f=%d | HDG %d | t=%d",
                        cam, state.camera.framesReceived(), hdg, state.touchCount.load());
        else
            ImGui::Text("Diplomka | %s f=%d | HDG -- | t=%d",
                        cam, state.camera.framesReceived(), state.touchCount.load());
    }
    ImGui::EndChild();
    ImGui::PopStyleColor();

    float panelH = btnH * 2 + 96;
    ImGui::SetCursorPos(ImVec2(pad, H - panelH - pad));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.06f, 0.07f, 0.10f, 0.90f));
    ImGui::BeginChild("panel", ImVec2(W - pad * 2, panelH), true);
    {
        float inner = ImGui::GetContentRegionAvail().x;
        float gap = 12.0f;
        float half = (inner - gap) * 0.5f;

        drawHitButton(state.torchOn ? "Torch ON" : "Torch OFF", state.btnTorch, state.torchOn, ImVec2(half, btnH));
        ImGui::SameLine(0, gap);
        drawHitButton(state.detectionEnabled ? "Detect ON" : "Detect OFF", state.btnDetect, state.detectionEnabled, ImVec2(half, btnH));

        drawHitButton(state.showPose ? "Pose ON" : "Pose OFF", state.btnPose, state.showPose, ImVec2(half, btnH));
        ImGui::SameLine(0, gap);
        drawHitButton("Restart Camera", state.btnRestart, false, ImVec2(half, btnH));

        ImGui::Spacing();
        ImGui::Text("Infer %.0f ms | Dets %d | Poses %d", state.lastInferenceMs, state.detectionCount, state.poseCount);
        if (state.stopSignal) ImGui::TextColored(ImVec4(1, 0.3f, 0.3f, 1), "STOP - obstacle near");

        std::lock_guard<std::mutex> lock(state.logMutex);
        int n = 0;
        for (const auto& line : state.commandLog) {
            ImGui::TextColored(ImVec4(0.75f, 0.78f, 0.85f, 1), "%s", line.c_str());
            if (++n >= 2) break;
        }
    }
    ImGui::EndChild();
    ImGui::PopStyleColor();

    ImGui::End();
    ImGui::PopStyleColor();
    ImGui::PopStyleVar();

    ImGui::Render();
    glViewport(0, 0, egl.width, egl.height);
    glClearColor(0.04f, 0.045f, 0.06f, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    eglSwapBuffers(egl.display, egl.surface);
}

void handleAppCmd(android_app* app, int32_t cmd) {
    auto* state = static_cast<AppState*>(app->userData);
    switch (cmd) {
        case APP_CMD_INIT_WINDOW:
            if (app->window) state->animating = true;
            break;
        case APP_CMD_TERM_WINDOW:
            state->animating = false;
            break;
        case APP_CMD_GAINED_FOCUS:
            state->hasFocus = true;
            state->animating = true;
            LOGI("GAINED_FOCUS");
            break;
        case APP_CMD_LOST_FOCUS:
            state->hasFocus = false;
            break;
        default:
            break;
    }
}

int32_t handleInput(android_app* app, AInputEvent* event) {
    auto* state = static_cast<AppState*>(app->userData);
    if (!state) return 0;

    if (AInputEvent_getType(event) != AINPUT_EVENT_TYPE_MOTION) return 0;

    const int32_t masked = AMotionEvent_getAction(event) & AMOTION_EVENT_ACTION_MASK;
    if (masked != AMOTION_EVENT_ACTION_DOWN && masked != AMOTION_EVENT_ACTION_POINTER_DOWN)
        return 1;

    const float x = AMotionEvent_getX(event, 0);
    const float y = AMotionEvent_getY(event, 0);
    state->touchCount.fetch_add(1);
    LOGI("TOUCH (%.0f, %.0f)", x, y);

    if (state->btnTorch.contains(x, y)) state->pendingAction.store(ActTorch);
    else if (state->btnDetect.contains(x, y)) state->pendingAction.store(ActDetect);
    else if (state->btnPose.contains(x, y)) state->pendingAction.store(ActPose);
    else if (state->btnRestart.contains(x, y)) state->pendingAction.store(ActRestart);

    return 1;
}

void engineLoop(android_app* app) {
    AppState state;
    state.app = app;
    app->userData = &state;
    app->onAppCmd = handleAppCmd;
    app->onInputEvent = handleInput;

    EglState egl{};

    while (!app->destroyRequested) {
        int events = 0;
        android_poll_source* source = nullptr;
        while (ALooper_pollOnce(state.animating ? 0 : 200, nullptr, &events, reinterpret_cast<void**>(&source)) >= 0) {
            if (source) source->process(app, source);
            if (app->destroyRequested) break;
        }
        if (app->destroyRequested) break;

        if (app->window && egl.display == EGL_NO_DISPLAY) {
            if (initEgl(app, egl)) {
                initImGui(app, egl);
                state.imguiInitialized = true;
                state.vision.initialize(app->activity->assetManager);
                state.sensors.start(app->looper);
            }
        }

        if (egl.display != EGL_NO_DISPLAY && app->window) {
            EGLint w = 0, h = 0;
            eglQuerySurface(egl.display, egl.surface, EGL_WIDTH, &w);
            eglQuerySurface(egl.display, egl.surface, EGL_HEIGHT, &h);
            if (w > 0 && h > 0) { egl.width = w; egl.height = h; }
        }

        if (egl.display == EGL_NO_DISPLAY || !state.animating) continue;

        applyPendingAction(state);

        if (state.hasFocus) {
            const bool hasPerm = jniHasCameraPermission(state.app);
            if (!hasPerm) {
                auto now = std::chrono::steady_clock::now();
                auto sec = std::chrono::duration_cast<std::chrono::seconds>(now - state.lastCameraAttempt).count();
                if (state.cameraRetryCount == 0 || sec >= 4) {
                    tryStartCamera(state);
                }
            } else if (!state.cameraStarted || state.camera.framesReceived() == 0) {
                auto now = std::chrono::steady_clock::now();
                auto sec = std::chrono::duration_cast<std::chrono::seconds>(now - state.lastCameraAttempt).count();
                const int waitSec = state.cameraStarted ? 5 : 2;
                if (state.cameraRetryCount == 0 || (sec >= waitSec && state.cameraRetryCount < 16)) {
                    if (state.cameraStarted) state.camera.stop();
                    tryStartCamera(state);
                }
            }
        }

        state.sensors.poll();

        std::vector<uint8_t> frameCopy;
        int fw = 0, fh = 0;
        {
            std::lock_guard<std::mutex> lock(state.frameMutex);
            if (state.hasNewFrame) {
                frameCopy.swap(state.latestRgba);
                fw = state.latestWidth;
                fh = state.latestHeight;
                state.hasNewFrame = false;
            }
        }

        if (!frameCopy.empty()) {
            uploadPreviewTexture(state, frameCopy.data(), fw, fh);
            bool runPose = state.showPose && (state.detectionCount % 2 == 0);
            VisionFrame vf = state.vision.processRgba(frameCopy.data(), fw, fh, state.detectionEnabled, runPose);
            state.lastInferenceMs = vf.inferenceMs;
            state.detectionCount = (int)vf.detections.size();
            state.poseCount = (int)vf.people.size();
            bool near = false;
            for (const auto& d : vf.detections)
                if (proximityFromBox(d.box, fw, fh) > 0.22f) near = true;
            state.stopSignal = near;
        }

        drawUi(state, egl);
    }

    state.camera.stop();
    state.sensors.stop();
    if (state.imguiInitialized && ImGui::GetCurrentContext()) shutdownImGui();
    if (state.previewTexture) glDeleteTextures(1, &state.previewTexture);
    shutdownEgl(egl);
}

} // namespace

void android_main(android_app* app) {
    engineLoop(app);
}
