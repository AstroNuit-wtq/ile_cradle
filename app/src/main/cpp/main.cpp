#include <android_native_app_glue.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include <android/asset_manager.h>
#include <android/configuration.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/sensor.h>
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
    bool imguiInitialized = false;
    bool cameraStarted = false;
    int cameraRetryCount = 0;
    std::chrono::steady_clock::time_point lastCameraAttempt{};

    float uiScale = 1.0f;
};

void pushLog(AppState& state, const std::string& line) {
    std::lock_guard<std::mutex> lock(state.logMutex);
    state.commandLog.push_front(line);
    while (state.commandLog.size() > 30) state.commandLog.pop_back();
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
    jclass activityClass = env->GetObjectClass(app->activity->clazz);
    jmethodID checkSelf = env->GetMethodID(activityClass, "checkSelfPermission", "(Ljava/lang/String;)I");
    jstring perm = env->NewStringUTF("android.permission.CAMERA");
    if (checkSelf && perm) {
        // PackageManager.PERMISSION_GRANTED == 0
        jint result = env->CallIntMethod(app->activity->clazz, checkSelf, perm);
        granted = (result == 0);
    }
    if (perm) env->DeleteLocalRef(perm);
    if (activityClass) env->DeleteLocalRef(activityClass);
    if (attached) vm->DetachCurrentThread();
    return granted;
}

bool initEgl(android_app* app, EglState& egl) {
    egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl.display == EGL_NO_DISPLAY) return false;
    if (!eglInitialize(egl.display, nullptr, nullptr)) return false;

    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(egl.display, attribs, &config, 1, &numConfigs) || numConfigs == 0) return false;

    EGLint format = 0;
    eglGetConfigAttrib(egl.display, config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(app->window, 0, 0, format);

    egl.surface = eglCreateWindowSurface(egl.display, config, app->window, nullptr);
    if (egl.surface == EGL_NO_SURFACE) return false;

    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    egl.context = eglCreateContext(egl.display, config, EGL_NO_CONTEXT, contextAttribs);
    if (egl.context == EGL_NO_CONTEXT) return false;
    if (!eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context)) return false;

    eglQuerySurface(egl.display, egl.surface, EGL_WIDTH, &egl.width);
    eglQuerySurface(egl.display, egl.surface, EGL_HEIGHT, &egl.height);
    return true;
}

void shutdownEgl(EglState& egl) {
    if (egl.display != EGL_NO_DISPLAY) {
        eglMakeCurrent(egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (egl.context != EGL_NO_CONTEXT) eglDestroyContext(egl.display, egl.context);
        if (egl.surface != EGL_NO_SURFACE) eglDestroySurface(egl.display, egl.surface);
        eglTerminate(egl.display);
    }
    egl.display = EGL_NO_DISPLAY;
    egl.surface = EGL_NO_SURFACE;
    egl.context = EGL_NO_CONTEXT;
}

float computeUiScale(android_app* app) {
    float scale = 2.0f;
    if (app && app->config) {
        const int32_t density = AConfiguration_getDensity(app->config);
        if (density > 0) scale = density / 160.0f;
    }
    // Clamp for phones: readable but not huge
    return std::clamp(scale, 1.5f, 3.5f);
}

void initImGui(android_app* app, EglState& egl, float uiScale) {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
    io.ConfigFlags |= ImGuiConfigFlags_IsTouchScreen;

    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();
    style.ScaleAllSizes(uiScale * 0.85f);
    style.TouchExtraPadding = ImVec2(12.0f * uiScale, 12.0f * uiScale);
    style.WindowRounding = 0.0f;
    style.ChildRounding = 12.0f;
    style.FrameRounding = 10.0f;
    style.GrabRounding = 8.0f;
    style.FramePadding = ImVec2(14.0f, 12.0f);
    style.ItemSpacing = ImVec2(12.0f, 14.0f);
    style.WindowPadding = ImVec2(12.0f, 12.0f);
    style.Colors[ImGuiCol_WindowBg] = ImVec4(0.06f, 0.07f, 0.09f, 0.92f);
    style.Colors[ImGuiCol_ChildBg] = ImVec4(0.08f, 0.09f, 0.12f, 0.88f);
    style.Colors[ImGuiCol_Button] = ImVec4(0.16f, 0.42f, 0.72f, 1.0f);
    style.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.22f, 0.52f, 0.85f, 1.0f);
    style.Colors[ImGuiCol_ButtonActive] = ImVec4(0.12f, 0.35f, 0.62f, 1.0f);
    style.Colors[ImGuiCol_Header] = ImVec4(0.16f, 0.42f, 0.72f, 0.55f);
    style.Colors[ImGuiCol_Text] = ImVec4(0.95f, 0.96f, 0.98f, 1.0f);

    // Larger default font for phone screens
    const float fontSize = 18.0f * uiScale;
    io.Fonts->Clear();
    io.Fonts->AddFontDefault();
    ImFontConfig cfg{};
    cfg.SizePixels = fontSize;
    cfg.OversampleH = 2;
    cfg.OversampleV = 2;
    cfg.PixelSnapH = true;
    io.Fonts->AddFontDefault(&cfg);
    io.FontDefault = io.Fonts->Fonts.back();

    ImGui_ImplAndroid_Init(app->window);
    ImGui_ImplOpenGL3_Init("#version 300 es");
    io.DisplaySize = ImVec2(static_cast<float>(egl.width), static_cast<float>(egl.height));
    io.DisplayFramebufferScale = ImVec2(1.0f, 1.0f);
}

void shutdownImGui() {
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplAndroid_Shutdown();
    ImGui::DestroyContext();
}

void uploadPreviewTexture(AppState& state, const uint8_t* rgba, int w, int h) {
    if (!rgba || w <= 0 || h <= 0) return;
    if (state.previewTexture == 0) glGenTextures(1, &state.previewTexture);
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

float proximityFromBox(const RectF& box, int frameW, int frameH) {
    if (frameW <= 0 || frameH <= 0) return 0.0f;
    const float area = std::max(0.0f, box.right - box.left) * std::max(0.0f, box.bottom - box.top);
    const float frameArea = static_cast<float>(frameW) * frameH;
    return std::clamp(area / frameArea, 0.0f, 1.0f);
}

void tryStartCamera(AppState& state) {
    state.lastCameraAttempt = std::chrono::steady_clock::now();
    state.cameraRetryCount++;

    if (!jniHasCameraPermission(state.app)) {
        state.cameraStarted = false;
        pushLog(state, "No CAMERA permission — allow in system dialog / Settings");
        LOGW("CAMERA permission not granted");
        return;
    }

    const bool ok = state.camera.start([&state](std::vector<uint8_t>&& rgba, int w, int h) {
        std::lock_guard<std::mutex> lock(state.frameMutex);
        state.latestRgba = std::move(rgba);
        state.latestWidth = w;
        state.latestHeight = h;
        state.hasNewFrame = true;
    });
    state.cameraStarted = ok;
    if (ok) {
        pushLog(state, "Camera started (try " + std::to_string(state.cameraRetryCount) + ")");
    } else {
        pushLog(state, "Camera open failed (try " + std::to_string(state.cameraRetryCount) + ")");
    }
}

bool bigButton(const char* label, const ImVec2& size, bool activeTone = false) {
    if (activeTone) {
        ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.15f, 0.55f, 0.35f, 1.0f));
        ImGui::PushStyleColor(ImGuiCol_ButtonHovered, ImVec4(0.20f, 0.65f, 0.42f, 1.0f));
        ImGui::PushStyleColor(ImGuiCol_ButtonActive, ImVec4(0.12f, 0.45f, 0.28f, 1.0f));
    }
    const bool pressed = ImGui::Button(label, size);
    if (activeTone) ImGui::PopStyleColor(3);
    return pressed;
}

void drawUi(AppState& state, EglState& egl) {
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(egl.width), static_cast<float>(egl.height));

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();

    const ImGuiViewport* vp = ImGui::GetMainViewport();
    const float W = vp->WorkSize.x;
    const float H = vp->WorkSize.y;
    const float pad = 12.0f * state.uiScale;
    const float btnH = 52.0f * state.uiScale;

    // Full-screen root (transparent so we can draw camera behind via Image)
    ImGui::SetNextWindowPos(vp->WorkPos);
    ImGui::SetNextWindowSize(vp->WorkSize);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0, 0));
    ImGui::Begin("root", nullptr,
                 ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                 ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoBringToFrontOnFocus |
                 ImGuiWindowFlags_NoScrollbar);

    // ---- Camera layer (full screen) ----
    {
        const ImVec2 p0 = ImGui::GetWindowPos();
        ImDrawList* dl = ImGui::GetWindowDrawList();
        if (state.previewTexture && state.previewTextureWidth > 0) {
            // Cover-fit the preview
            const float texAspect = static_cast<float>(state.previewTextureWidth) / state.previewTextureHeight;
            const float screenAspect = W / H;
            ImVec2 size, off;
            if (screenAspect > texAspect) {
                size = ImVec2(W, W / texAspect);
                off = ImVec2(0, (H - size.y) * 0.5f);
            } else {
                size = ImVec2(H * texAspect, H);
                off = ImVec2((W - size.x) * 0.5f, 0);
            }
            dl->AddImage(static_cast<ImTextureID>(static_cast<uintptr_t>(state.previewTexture)),
                         ImVec2(p0.x + off.x, p0.y + off.y),
                         ImVec2(p0.x + off.x + size.x, p0.y + off.y + size.y));

            if (state.stopSignal) {
                dl->AddRectFilled(p0, ImVec2(p0.x + W, p0.y + H), IM_COL32(180, 20, 20, 90));
                const char* stop = "STOP";
                const ImVec2 ts = ImGui::CalcTextSize(stop);
                dl->AddText(ImVec2(p0.x + (W - ts.x) * 0.5f, p0.y + H * 0.42f),
                            IM_COL32(255, 70, 70, 255), stop);
            }
        } else {
            dl->AddRectFilled(p0, ImVec2(p0.x + W, p0.y + H), IM_COL32(12, 14, 18, 255));
            const char* msg = state.cameraStarted
                                  ? "Camera open \xe2\x80\x94 waiting for frames..."
                                  : "Camera offline";
            const ImVec2 ts = ImGui::CalcTextSize(msg);
            dl->AddText(ImVec2(p0.x + (W - ts.x) * 0.5f, p0.y + H * 0.40f),
                        IM_COL32(220, 220, 230, 255), msg);
            if (!jniHasCameraPermission(state.app)) {
                const char* hint = "Grant CAMERA permission, then tap Restart";
                const ImVec2 hs = ImGui::CalcTextSize(hint);
                dl->AddText(ImVec2(p0.x + (W - hs.x) * 0.5f, p0.y + H * 0.40f + ts.y + 12),
                            IM_COL32(255, 160, 80, 255), hint);
            }
        }
    }

    // ---- Top HUD ----
    ImGui::SetCursorPos(ImVec2(pad, pad));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.05f, 0.06f, 0.08f, 0.72f));
    ImGui::BeginChild("hud", ImVec2(W - pad * 2, 56.0f * state.uiScale), true,
                      ImGuiWindowFlags_NoScrollbar);
    {
        const char* camState = state.camera.isRunning()
                                   ? (state.camera.framesReceived() > 0 ? "LIVE" : "NO FRAMES")
                                   : "OFF";
        ImGui::Text("Diplomka  |  %s  frames=%d", camState, state.camera.framesReceived());
        ImGui::SameLine(std::max(10.0f, W - pad * 2 - 220.0f * state.uiScale));
        if (state.sensors.hasHeading()) {
            ImGui::Text("HDG %d\xc2\xb0", static_cast<int>(state.sensors.headingDegrees()));
        } else {
            ImGui::TextUnformatted("HDG --");
        }
    }
    ImGui::EndChild();
    ImGui::PopStyleColor();

    // ---- Bottom control panel ----
    const float panelH = (btnH * 2) + pad * 4 + 70.0f * state.uiScale;
    ImGui::SetCursorPos(ImVec2(pad, H - panelH - pad));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.06f, 0.07f, 0.10f, 0.88f));
    ImGui::BeginChild("controls", ImVec2(W - pad * 2, panelH), true);
    {
        const float innerW = ImGui::GetContentRegionAvail().x;
        const float gap = 10.0f * state.uiScale;
        const float half = (innerW - gap) * 0.5f;

        if (bigButton(state.torchOn ? "Torch  ON" : "Torch  OFF", ImVec2(half, btnH), state.torchOn)) {
            state.torchOn = !state.torchOn;
            state.camera.setTorch(state.torchOn);
            pushLog(state, state.torchOn ? "Torch enabled" : "Torch disabled");
        }
        ImGui::SameLine(0, gap);
        if (bigButton(state.detectionEnabled ? "Detect  ON" : "Detect  OFF", ImVec2(half, btnH), state.detectionEnabled)) {
            state.detectionEnabled = !state.detectionEnabled;
            pushLog(state, state.detectionEnabled ? "Detection on" : "Detection off");
        }

        if (bigButton(state.showPose ? "Pose  ON" : "Pose  OFF", ImVec2(half, btnH), state.showPose)) {
            state.showPose = !state.showPose;
        }
        ImGui::SameLine(0, gap);
        if (bigButton("Restart Camera", ImVec2(half, btnH))) {
            state.camera.stop();
            state.cameraStarted = false;
            tryStartCamera(state);
        }

        ImGui::Spacing();
        ImGui::Separator();
        ImGui::Text("Infer %.0f ms   |   Dets %d   |   Poses %d",
                    state.lastInferenceMs, state.detectionCount, state.poseCount);
        if (state.stopSignal) {
            ImGui::TextColored(ImVec4(1.0f, 0.35f, 0.3f, 1.0f), "STOP — obstacle near");
        }

        // Compact log (last 3 lines)
        {
            std::lock_guard<std::mutex> lock(state.logMutex);
            int shown = 0;
            for (const auto& line : state.commandLog) {
                ImGui::TextColored(ImVec4(0.75f, 0.78f, 0.85f, 1.0f), "%s", line.c_str());
                if (++shown >= 3) break;
            }
        }
    }
    ImGui::EndChild();
    ImGui::PopStyleColor();

    ImGui::End();
    ImGui::PopStyleVar();

    ImGui::Render();
    glViewport(0, 0, egl.width, egl.height);
    glClearColor(0.04f, 0.045f, 0.06f, 1.0f);
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
            state->animating = true;
            break;
        case APP_CMD_LOST_FOCUS:
            state->animating = false;
            break;
        case APP_CMD_CONFIG_CHANGED:
            if (app->config) state->uiScale = computeUiScale(app);
            break;
        default:
            break;
    }
}

int32_t handleInput(android_app* app, AInputEvent* event) {
    // Always feed ImGui; return 1 so the event is considered handled.
    if (ImGui_ImplAndroid_HandleInputEvent(event) != 0) return 1;
    // Still consume motion events so the system does not steal them.
    if (AInputEvent_getType(event) == AINPUT_EVENT_TYPE_MOTION) return 1;
    return 0;
}

void engineLoop(android_app* app) {
    AppState state;
    state.app = app;
    state.uiScale = computeUiScale(app);
    app->userData = &state;
    app->onAppCmd = handleAppCmd;
    app->onInputEvent = handleInput;

    EglState egl{};

    while (!app->destroyRequested) {
        int events = 0;
        android_poll_source* source = nullptr;
        while (ALooper_pollOnce(state.animating ? 0 : 250, nullptr, &events, reinterpret_cast<void**>(&source)) >= 0) {
            if (source) source->process(app, source);
            if (app->destroyRequested) break;
        }
        if (app->destroyRequested) break;

        if (app->window && egl.display == EGL_NO_DISPLAY) {
            if (initEgl(app, egl)) {
                state.uiScale = computeUiScale(app);
                initImGui(app, egl, state.uiScale);
                state.imguiInitialized = true;

                AAssetManager* assets = app->activity->assetManager;
                state.vision.initialize(assets);
                state.sensors.start(app->looper);
                tryStartCamera(state);
            }
        }

        // Update surface size if rotated / changed
        if (egl.display != EGL_NO_DISPLAY && app->window) {
            EGLint w = 0, h = 0;
            eglQuerySurface(egl.display, egl.surface, EGL_WIDTH, &w);
            eglQuerySurface(egl.display, egl.surface, EGL_HEIGHT, &h);
            if (w > 0 && h > 0 && (w != egl.width || h != egl.height)) {
                egl.width = w;
                egl.height = h;
            }
        }

        if (egl.display == EGL_NO_DISPLAY || !state.animating) continue;

        // Retry camera after permission grant
        if ((!state.cameraStarted || state.camera.framesReceived() == 0) && state.cameraRetryCount < 8) {
            const auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(now - state.lastCameraAttempt).count() >= 2) {
                if (jniHasCameraPermission(state.app)) {
                    LOGW("Retrying camera after permission/check...");
                    state.camera.stop();
                    tryStartCamera(state);
                } else {
                    state.lastCameraAttempt = now;
                }
            }
        }

        state.sensors.poll();

        std::vector<uint8_t> frameCopy;
        int fw = 0, fh = 0;
        {
            std::lock_guard<std::mutex> lock(state.frameMutex);
            if (state.hasNewFrame) {
                frameCopy = state.latestRgba;
                fw = state.latestWidth;
                fh = state.latestHeight;
                state.hasNewFrame = false;
            }
        }

        if (!frameCopy.empty()) {
            uploadPreviewTexture(state, frameCopy.data(), fw, fh);
            const bool runPose = state.showPose && (state.detectionCount % 2 == 0);
            VisionFrame vf = state.vision.processRgba(frameCopy.data(), fw, fh, state.detectionEnabled, runPose);
            state.lastInferenceMs = vf.inferenceMs;
            state.detectionCount = static_cast<int>(vf.detections.size());
            state.poseCount = static_cast<int>(vf.people.size());

            bool near = false;
            for (const auto& d : vf.detections) {
                if (proximityFromBox(d.box, fw, fh) > 0.22f) near = true;
            }
            state.stopSignal = near;
            if (near) {
                static int lastStopLog = -1;
                if (state.detectionCount != lastStopLog) {
                    pushLog(state, "STOP: obstacle proximity high");
                    lastStopLog = state.detectionCount;
                }
            }
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
