#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#include <android/native_window.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCaptureRequest.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkImageReader.h>

class CameraEngine {
public:
    using FrameConsumer = std::function<void(std::vector<uint8_t>&&, int, int)>;

    CameraEngine() = default;
    ~CameraEngine();

    bool start(FrameConsumer consumer);
    void stop();
    bool setTorch(bool enabled);
    bool isRunning() const { return running_.load(); }
    bool isSessionActive() const { return sessionActive_.load(); }
    bool isTorchAvailable() const { return flashAvailable_.load(); }
    uint64_t frameCount() const { return frameCount_.load(); }
    int lastErrorCode() const { return lastErrorCode_.load(); }
    int frameWidth() const { return width_; }
    int frameHeight() const { return height_; }

private:
    static void onImageAvailable(void* context, AImageReader* reader);
    static void onCameraDisconnected(void* context, ACameraDevice* device);
    static void onCameraError(void* context, ACameraDevice* device, int error);
    static void onSessionClosed(void* context, ACameraCaptureSession* session);
    static void onSessionReady(void* context, ACameraCaptureSession* session);
    static void onSessionActive(void* context, ACameraCaptureSession* session);

    void consumeLatestImage(AImageReader* reader);
    bool chooseBackCamera();
    void setError(int errorCode, const char* operation);

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

    std::mutex lifecycleMutex_;
    std::mutex consumerMutex_;
    FrameConsumer consumer_;
    int width_ = 640;
    int height_ = 480;
    std::atomic<bool> running_{false};
    std::atomic<bool> sessionActive_{false};
    std::atomic<bool> repeatingStarted_{false};
    std::atomic<bool> flashAvailable_{false};
    std::atomic<uint64_t> frameCount_{0};
    std::atomic<int> lastErrorCode_{0};
    bool torchEnabled_ = false;
};
