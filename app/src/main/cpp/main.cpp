#include <android_native_app_glue.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include <android/asset_manager.h>
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
};

void pushLog(AppState& state, const std::string& line) {
    std::lock_guard<std::mutex> lock(state.logMutex);
    state.commandLog.push_front(line);
    while (state.commandLog.size() > 40) state.commandLog.pop_back();
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

void initImGui(android_app* app, EglState& egl) {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
    ImGui::StyleColorsDark();
    // Larger touch targets for phone use
    ImGuiStyle& style = ImGui::GetStyle();
    style.TouchExtraPadding = ImVec2(8.0f, 8.0f);
    style.ItemSpacing = ImVec2(10.0f, 12.0f);
    style.FramePadding = ImVec2(12.0f, 10.0f);
    ImGui_ImplAndroid_Init(app->window);
    ImGui_ImplOpenGL3_Init("#version 300 es");
    io.DisplaySize = ImVec2(static_cast<float>(egl.width), static_cast<float>(egl.height));
}

void shutdownImGui() {
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplAndroid_Shutdown();
    ImGui::DestroyContext();
}

void uploadPreviewTexture(AppState& state, const uint8_t* rgba, int w, int h) {
    if (!rgba || w <= 0 || h <= 0) return;
    if (state.previewTexture == 0) {
        glGenTextures(1, &state.previewTexture);
    }
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
        pushLog(state, "Camera start FAILED (try " + std::to_string(state.cameraRetryCount) +
                        ") — check CAMERA permission");
    }
}

void drawUi(AppState& state, EglState& egl) {
    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();

    const ImGuiViewport* vp = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(vp->WorkPos);
    ImGui::SetNextWindowSize(vp->WorkSize);
    ImGui::Begin("Diplomka Native", nullptr,
                 ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                 ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoBringToFrontOnFocus);

    // Left: camera preview + overlays
    ImGui::BeginChild("preview", ImVec2(vp->WorkSize.x * 0.68f, 0), true);
    {
        const ImVec2 avail = ImGui::GetContentRegionAvail();
        if (state.previewTexture && state.previewTextureWidth > 0 && state.previewTextureHeight > 0) {
            const float aspect = static_cast<float>(state.previewTextureWidth) / state.previewTextureHeight;
            ImVec2 size = avail;
            if (size.x / size.y > aspect) size.x = size.y * aspect;
            else size.y = size.x / aspect;
            ImGui::Image(static_cast<ImTextureID>(static_cast<uintptr_t>(state.previewTexture)), size);

            const ImVec2 p0 = ImGui::GetItemRectMin();
            const ImVec2 p1 = ImGui::GetItemRectMax();
            ImDrawList* dl = ImGui::GetWindowDrawList();

            if (state.stopSignal) {
                dl->AddRectFilled(p0, p1, IM_COL32(180, 20, 20, 70));
                dl->AddText(ImVec2(p0.x + 12, p0.y + 12), IM_COL32(255, 80, 80, 255), "STOP");
            }
        } else {
            ImGui::TextUnformatted("Waiting for camera...");
            ImGui::Spacing();
            if (!state.cameraStarted) {
                ImGui::TextColored(ImVec4(1.0f, 0.4f, 0.3f, 1.0f),
                                   "Camera not started.\nGrant CAMERA permission\nand tap Restart Camera.");
            } else if (state.camera.framesReceived() == 0) {
                ImGui::TextColored(ImVec4(1.0f, 0.8f, 0.2f, 1.0f),
                                   "Camera open, no frames yet.\nPermission may still be pending.");
            }
        }
    }
    ImGui::EndChild();

    ImGui::SameLine();

    // Right rail: controls + log
    ImGui::BeginChild("rail", ImVec2(0, 0), true);
    {
        ImGui::Text("Diplomka Native C++");
        ImGui::Separator();

        if (ImGui::Button(state.torchOn ? "Torch OFF" : "Torch ON", ImVec2(-1, 48))) {
            state.torchOn = !state.torchOn;
            state.camera.setTorch(state.torchOn);
            pushLog(state, state.torchOn ? "Torch enabled" : "Torch disabled");
        }
        if (ImGui::Button(state.detectionEnabled ? "Detection ON" : "Detection OFF", ImVec2(-1, 48))) {
            state.detectionEnabled = !state.detectionEnabled;
            pushLog(state, state.detectionEnabled ? "Detection enabled" : "Detection paused");
        }
        if (ImGui::Button(state.showPose ? "Pose ON" : "Pose OFF", ImVec2(-1, 48))) {
            state.showPose = !state.showPose;
        }
        if (ImGui::Button("Restart Camera", ImVec2(-1, 48))) {
            state.camera.stop();
            state.cameraStarted = false;
            tryStartCamera(state);
        }

        ImGui::Separator();
        ImGui::Text("Cam: %s  frames=%d",
                    state.camera.isRunning() ? "RUN" : "OFF",
                    state.camera.framesReceived());
        ImGui::Text("Heading: %s", state.sensors.hasHeading() ?
                    (std::to_string(static_cast<int>(state.sensors.headingDegrees())) + " deg").c_str() : "--");
        ImGui::Text("Infer: %.1f ms", state.lastInferenceMs);
        ImGui::Text("Dets: %d  Poses: %d", state.detectionCount, state.poseCount);
        if (state.stopSignal) {
            ImGui::TextColored(ImVec4(1, 0.3f, 0.3f, 1), "STOP — obstacle near");
        }

        ImGui::Separator();
        ImGui::TextUnformatted("Command log");
        ImGui::BeginChild("log", ImVec2(0, 0), true);
        {
            std::lock_guard<std::mutex> lock(state.logMutex);
            for (const auto& line : state.commandLog) {
                ImGui::TextUnformatted(line.c_str());
            }
        }
        ImGui::EndChild();
    }
    ImGui::EndChild();

    ImGui::End();
    ImGui::Render();
    glViewport(0, 0, egl.width, egl.height);
    glClearColor(0.05f, 0.05f, 0.07f, 1.0f);
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
        default:
            break;
    }
}

int32_t handleInput(android_app* app, AInputEvent* event) {
    if (ImGui_ImplAndroid_HandleInputEvent(event))
        return 1;
    return 0;
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
        while (ALooper_pollOnce(state.animating ? 0 : 250, nullptr, &events, reinterpret_cast<void**>(&source)) >= 0) {
            if (source) source->process(app, source);
            if (app->destroyRequested) break;
        }
        if (app->destroyRequested) break;

        if (app->window && egl.display == EGL_NO_DISPLAY) {
            if (initEgl(app, egl)) {
                initImGui(app, egl);
                state.imguiInitialized = true;

                AAssetManager* assets = app->activity->assetManager;
                state.vision.initialize(assets);
                state.sensors.start(app->looper);

                // Slight delay: Java MainActivity may still be showing the permission dialog.
                tryStartCamera(state);
            }
        }

        if (egl.display == EGL_NO_DISPLAY || !state.animating) continue;

        // Auto-retry camera a few times if no frames (permission granted after first open attempt).
        if (state.cameraStarted && state.camera.framesReceived() == 0 && state.cameraRetryCount < 6) {
            const auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(now - state.lastCameraAttempt).count() >= 2) {
                LOGW("No frames after 2s — restarting camera (permission?)");
                state.camera.stop();
                tryStartCamera(state);
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
