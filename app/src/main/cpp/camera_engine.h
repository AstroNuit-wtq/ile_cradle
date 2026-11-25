#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <android/native_window.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCaptureRequest.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkImageReader.h>

// Non-blocking camera pipeline.
// UI thread must only call: requestStart / requestStop / clearFailure / setTorch / getters.
// A dedicated worker opens the device so the render loop never stalls.
enum class CameraState : int {
    Idle = 0,
    Starting,
    Streaming,
    Stopping,
    Failed
};

class CameraEngine {
public:
    using FrameConsumer = std::function<void(std::vector<uint8_t>&&, int, int)>;

    CameraEngine();
    ~CameraEngine();

    // Non-blocking. Posts work to the camera worker thread.
    void requestStart(FrameConsumer consumer);
    void requestStop();

    // Optional: call from UI to clear Failed and allow another requestStart.
    void clearFailure();

    CameraState state() const { return state_.load(); }
    bool isRunning() const { return state_.load() == CameraState::Streaming; }
    bool isSessionActive() const { return sessionActive_.load(); }
    int framesReceived() const { return framesReceived_.load(); }
    int frameWidth() const { return width_.load(); }
    int frameHeight() const { return height_.load(); }

    // Human-readable reason for Failed / last open attempt (thread-safe copy).
    std::string diagnostic() const;
    std::string lastError() const { return diagnostic(); }

    bool setTorch(bool enabled);

private:
    static void onImageAvailable(void* context, AImageReader* reader);
    static void onCameraDisconnected(void* context, ACameraDevice* device);
    static void onCameraError(void* context, ACameraDevice* device, int error);
    static void onSessionClosed(void* context, ACameraCaptureSession* session);
    static void onSessionReady(void* context, ACameraCaptureSession* session);
    static void onSessionActive(void* context, ACameraCaptureSession* session);

    void workerMain();
    void setDiagnostic(const std::string& msg);
    bool openPipeline();
    void closePipeline();
    bool chooseBackCamera();
    bool startRepeating();
    void consumeLatestImage(AImageReader* reader);

    std::thread worker_;
    std::mutex wakeMutex_;
    std::condition_variable wakeCv_;
    std::atomic<bool> workerExit_{false};
    std::atomic<bool> wantStart_{false};
    std::atomic<bool> wantStop_{false};

    std::mutex consumerMutex_;
    FrameConsumer consumer_;

    mutable std::mutex diagMutex_;
    std::string diagnostic_;

    std::atomic<CameraState> state_{CameraState::Idle};
    std::atomic<bool> sessionActive_{false};
    std::atomic<bool> repeatingStarted_{false};
    std::atomic<int> framesReceived_{0};
    std::atomic<int> width_{640};
    std::atomic<int> height_{480};

    // Camera2 objects — only touched on worker thread (or NDK callbacks).
    std::recursive_mutex pipelineMutex_;
    ACameraManager* manager_ = nullptr;
    ACameraDevice* device_ = nullptr;
    ACameraCaptureSession* session_ = nullptr;
    AImageReader* reader_ = nullptr;
    ANativeWindow* readerWindow_ = nullptr;
    ACameraOutputTarget* target_ = nullptr;
    ACaptureRequest* request_ = nullptr;
    ACaptureSessionOutputContainer* outputContainer_ = nullptr;
    ACaptureSessionOutput* sessionOutput_ = nullptr;
    std::string cameraId_;
    bool torchEnabled_ = false;
};
