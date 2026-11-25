#include "camera_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <climits>
#include <string>
#include <vector>

#include <android/log.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCaptureRequest.h>
#include <media/NdkImage.h>

#define LOG_TAG "DiplomkaNative-Camera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
inline uint8_t clampByte(int value) {
    return static_cast<uint8_t>(std::max(0, std::min(255, value)));
}
} // namespace

CameraEngine::~CameraEngine() {
    stop();
}

bool CameraEngine::chooseBackCamera() {
    if (!manager_) return false;
    ACameraIdList* ids = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(manager_, &ids);
    if (status != ACAMERA_OK || ids == nullptr) {
        lastError_ = "getCameraIdList failed";
        LOGE("%s: %d", lastError_.c_str(), status);
        return false;
    }

    LOGI("Found %d camera(s)", ids->numCameras);
    cameraId_.clear();
    std::string fallbackId;

    for (int i = 0; i < ids->numCameras; ++i) {
        const char* id = ids->cameraIds[i];
        ACameraMetadata* metadata = nullptr;
        if (ACameraManager_getCameraCharacteristics(manager_, id, &metadata) != ACAMERA_OK || !metadata) {
            continue;
        }

        ACameraMetadata_const_entry facing{};
        uint8_t lensFacing = 0xFF;
        if (ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &facing) == ACAMERA_OK && facing.count > 0) {
            lensFacing = facing.data.u8[0];
        }
        LOGI("Camera[%d] id=%s facing=%u", i, id, static_cast<unsigned>(lensFacing));

        // Prefer back camera; keep first id as fallback.
        if (fallbackId.empty()) fallbackId = id;

        int bestW = 640, bestH = 480;
        int bestScore = INT_MAX;
        bool hasYuv = false;
        ACameraMetadata_const_entry streamConfig{};
        if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &streamConfig) == ACAMERA_OK) {
            for (uint32_t j = 0; j + 3 < streamConfig.count; j += 4) {
                const int32_t fmt = streamConfig.data.i32[j + 0];
                const int32_t w = streamConfig.data.i32[j + 1];
                const int32_t h = streamConfig.data.i32[j + 2];
                const int32_t isInput = streamConfig.data.i32[j + 3];
                if (isInput != 0) continue;
                if (fmt != AIMAGE_FORMAT_YUV_420_888) continue;
                hasYuv = true;
                // Prefer ~720p landscape for preview throughput
                const int score = std::abs(w - 1280) + std::abs(h - 720);
                if (score < bestScore && w >= 320 && h >= 240 && w <= 1920 && h <= 1080) {
                    bestScore = score;
                    bestW = w;
                    bestH = h;
                }
            }
        }

        if (lensFacing == ACAMERA_LENS_FACING_BACK && hasYuv) {
            cameraId_ = id;
            width_ = bestW;
            height_ = bestH;
            ACameraMetadata_free(metadata);
            LOGI("Selected BACK camera %s size %dx%d", cameraId_.c_str(), width_, height_);
            break;
        }
        ACameraMetadata_free(metadata);
    }

    if (cameraId_.empty() && !fallbackId.empty()) {
        cameraId_ = fallbackId;
        width_ = 640;
        height_ = 480;
        LOGW("Using fallback camera %s at %dx%d", cameraId_.c_str(), width_, height_);
    }

    ACameraManager_deleteCameraIdList(ids);
    if (cameraId_.empty()) {
        lastError_ = "no camera ids";
        return false;
    }
    return true;
}

bool CameraEngine::startRepeating() {
    if (!session_ || !request_) return false;
    camera_status_t st = ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr);
    if (st != ACAMERA_OK) {
        LOGE("setRepeatingRequest failed: %d", st);
        lastError_ = "setRepeatingRequest failed";
        return false;
    }
    LOGI("Repeating request started");
    return true;
}

bool CameraEngine::start(FrameConsumer consumer) {
    if (running_) return true;
    if (manager_ || device_ || session_ || reader_ || request_) stop();
    {
        std::lock_guard<std::mutex> lock(consumerMutex_);
        consumer_ = std::move(consumer);
    }
    lastError_.clear();
    sessionActive_ = false;
    framesReceived_ = 0;

    manager_ = ACameraManager_create();
    if (!manager_ || !chooseBackCamera()) {
        LOGE("No camera found");
        if (lastError_.empty()) lastError_ = "no camera";
        stop();
        return false;
    }

    media_status_t mediaStatus = AImageReader_new(width_, height_, AIMAGE_FORMAT_YUV_420_888, 8, &reader_);
    if (mediaStatus != AMEDIA_OK || !reader_) {
        LOGE("AImageReader_new failed: %d size=%dx%d", mediaStatus, width_, height_);
        // Retry with safe default
        width_ = 640;
        height_ = 480;
        mediaStatus = AImageReader_new(width_, height_, AIMAGE_FORMAT_YUV_420_888, 8, &reader_);
        if (mediaStatus != AMEDIA_OK || !reader_) {
            lastError_ = "AImageReader_new failed";
            stop();
            return false;
        }
    }

    AImageReader_ImageListener listener{};
    listener.context = this;
    listener.onImageAvailable = &CameraEngine::onImageAvailable;
    AImageReader_setImageListener(reader_, &listener);
    AImageReader_getWindow(reader_, &readerWindow_);
    if (!readerWindow_) {
        lastError_ = "ImageReader window null";
        LOGE("%s", lastError_.c_str());
        stop();
        return false;
    }

    ACameraDevice_StateCallbacks deviceCallbacks{};
    deviceCallbacks.context = this;
    deviceCallbacks.onDisconnected = &CameraEngine::onCameraDisconnected;
    deviceCallbacks.onError = &CameraEngine::onCameraError;

    camera_status_t cameraStatus = ACameraManager_openCamera(manager_, cameraId_.c_str(), &deviceCallbacks, &device_);
    if (cameraStatus != ACAMERA_OK || !device_) {
        LOGE("openCamera failed: %d", cameraStatus);
        lastError_ = "openCamera failed (permission or in use)";
        stop();
        return false;
    }

    cameraStatus = ACameraOutputTarget_create(readerWindow_, &target_);
    if (cameraStatus != ACAMERA_OK || !target_) {
        lastError_ = "OutputTarget create failed";
        LOGE("%s: %d", lastError_.c_str(), cameraStatus);
        stop();
        return false;
    }

    cameraStatus = ACameraDevice_createCaptureRequest(device_, TEMPLATE_PREVIEW, &request_);
    if (cameraStatus != ACAMERA_OK || !request_) {
        lastError_ = "createCaptureRequest failed";
        LOGE("%s: %d", lastError_.c_str(), cameraStatus);
        stop();
        return false;
    }
    ACaptureRequest_addTarget(request_, target_);

    // AE / AF continuous for preview
    {
        uint8_t afMode = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        ACaptureRequest_setEntry_u8(request_, ACAMERA_CONTROL_AF_MODE, 1, &afMode);
        uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
        ACaptureRequest_setEntry_u8(request_, ACAMERA_CONTROL_AE_MODE, 1, &aeMode);
    }

    ACaptureSessionOutputContainer_create(&outputContainer_);
    ACaptureSessionOutput_create(readerWindow_, &sessionOutput_);
    if (!outputContainer_ || !sessionOutput_ ||
        ACaptureSessionOutputContainer_add(outputContainer_, sessionOutput_) != ACAMERA_OK) {
        lastError_ = "session output setup failed";
        LOGE("%s", lastError_.c_str());
        stop();
        return false;
    }

    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    sessionCallbacks.context = this;
    sessionCallbacks.onClosed = &CameraEngine::onSessionClosed;
    sessionCallbacks.onReady = &CameraEngine::onSessionReady;
    sessionCallbacks.onActive = &CameraEngine::onSessionActive;

    cameraStatus = ACameraDevice_createCaptureSession(device_, outputContainer_, &sessionCallbacks, &session_);
    if (cameraStatus != ACAMERA_OK || !session_) {
        lastError_ = "createCaptureSession failed";
        LOGE("%s: %d", lastError_.c_str(), cameraStatus);
        stop();
        return false;
    }

    // Some devices need repeating only after onSessionActive; still try now and again in callback.
    startRepeating();

    running_ = true;
    LOGI("Camera pipeline ready: id=%s %dx%d", cameraId_.c_str(), width_, height_);
    return true;
}

void CameraEngine::stop() {
    running_ = false;
    sessionActive_ = false;
    if (session_) {
        ACameraCaptureSession_stopRepeating(session_);
        ACameraCaptureSession_close(session_);
        session_ = nullptr;
    }
    if (request_) {
        ACaptureRequest_free(request_);
        request_ = nullptr;
    }
    if (target_) {
        ACameraOutputTarget_free(target_);
        target_ = nullptr;
    }
    if (sessionOutput_) {
        ACaptureSessionOutput_free(sessionOutput_);
        sessionOutput_ = nullptr;
    }
    if (outputContainer_) {
        ACaptureSessionOutputContainer_free(outputContainer_);
        outputContainer_ = nullptr;
    }
    if (device_) {
        ACameraDevice_close(device_);
        device_ = nullptr;
    }
    if (reader_) {
        AImageReader_delete(reader_);
        reader_ = nullptr;
        readerWindow_ = nullptr;
    }
    if (manager_) {
        ACameraManager_delete(manager_);
        manager_ = nullptr;
    }
    cameraId_.clear();
    {
        std::lock_guard<std::mutex> lock(consumerMutex_);
        consumer_ = nullptr;
    }
}

bool CameraEngine::setTorch(bool enabled) {
    if (!request_ || !session_) return false;
    uint8_t mode = enabled ? ACAMERA_FLASH_MODE_TORCH : ACAMERA_FLASH_MODE_OFF;
    camera_status_t status = ACaptureRequest_setEntry_u8(request_, ACAMERA_FLASH_MODE, 1, &mode);
    if (status != ACAMERA_OK) {
        LOGE("setFlashMode failed: %d", status);
        return false;
    }
    status = ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr);
    if (status != ACAMERA_OK) {
        LOGE("torch update repeating failed: %d", status);
        return false;
    }
    torchEnabled_ = enabled;
    LOGI("Torch %s", enabled ? "ON" : "OFF");
    return true;
}

void CameraEngine::onImageAvailable(void* context, AImageReader* reader) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) self->consumeLatestImage(reader);
}

void CameraEngine::consumeLatestImage(AImageReader* reader) {
    if (!running_) return;
    AImage* image = nullptr;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);
    if (status != AMEDIA_OK || !image) return;

    int32_t width = 0, height = 0;
    AImage_getWidth(image, &width);
    AImage_getHeight(image, &height);

    uint8_t *planeY = nullptr, *planeU = nullptr, *planeV = nullptr;
    int32_t lenY = 0, lenU = 0, lenV = 0;
    int32_t rowY = 0, rowU = 0, rowV = 0;
    int32_t pixelY = 0, pixelU = 0, pixelV = 0;

    const bool ok =
        AImage_getPlaneData(image, 0, &planeY, &lenY) == AMEDIA_OK &&
        AImage_getPlaneData(image, 1, &planeU, &lenU) == AMEDIA_OK &&
        AImage_getPlaneData(image, 2, &planeV, &lenV) == AMEDIA_OK &&
        AImage_getPlaneRowStride(image, 0, &rowY) == AMEDIA_OK &&
        AImage_getPlaneRowStride(image, 1, &rowU) == AMEDIA_OK &&
        AImage_getPlaneRowStride(image, 2, &rowV) == AMEDIA_OK &&
        AImage_getPlanePixelStride(image, 0, &pixelY) == AMEDIA_OK &&
        AImage_getPlanePixelStride(image, 1, &pixelU) == AMEDIA_OK &&
        AImage_getPlanePixelStride(image, 2, &pixelV) == AMEDIA_OK;

    if (ok && width > 0 && height > 0 && planeY && planeU && planeV) {
        std::vector<uint8_t> rgba(static_cast<size_t>(width) * static_cast<size_t>(height) * 4);
        // Fast path: if UV are interleaved (pixelStride==2) typical on Android
        for (int y = 0; y < height; ++y) {
            const int uvY = y >> 1;
            const uint8_t* rowYPtr = planeY + y * rowY;
            const uint8_t* rowUPtr = planeU + uvY * rowU;
            const uint8_t* rowVPtr = planeV + uvY * rowV;
            for (int x = 0; x < width; ++x) {
                const int yy = std::max(0, static_cast<int>(rowYPtr[x * pixelY]) - 16);
                const int uu = static_cast<int>(rowUPtr[(x >> 1) * pixelU]) - 128;
                const int vv = static_cast<int>(rowVPtr[(x >> 1) * pixelV]) - 128;
                const int c = 298 * yy;
                const size_t index = (static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 4;
                rgba[index + 0] = clampByte((c + 409 * vv + 128) >> 8);
                rgba[index + 1] = clampByte((c - 100 * uu - 208 * vv + 128) >> 8);
                rgba[index + 2] = clampByte((c + 516 * uu + 128) >> 8);
                rgba[index + 3] = 255;
            }
        }

        FrameConsumer callback;
        {
            std::lock_guard<std::mutex> lock(consumerMutex_);
            callback = consumer_;
        }
        if (callback) {
            callback(std::move(rgba), width, height);
            const int n = ++framesReceived_;
            if (n == 1 || (n % 90) == 0) {
                LOGI("Frame #%d (%dx%d) yStride=%d uPix=%d vPix=%d", n, width, height, rowY, pixelU, pixelV);
            }
        }
    }

    AImage_delete(image);
}

void CameraEngine::onCameraDisconnected(void* context, ACameraDevice*) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        LOGE("Camera disconnected");
        self->running_ = false;
        self->lastError_ = "disconnected";
    }
}

void CameraEngine::onCameraError(void* context, ACameraDevice*, int error) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        LOGE("Camera error: %d", error);
        self->running_ = false;
        self->lastError_ = "camera error " + std::to_string(error);
    }
}

void CameraEngine::onSessionClosed(void*, ACameraCaptureSession*) {
    LOGI("Capture session closed");
}

void CameraEngine::onSessionReady(void* context, ACameraCaptureSession*) {
    LOGI("Capture session ready");
    auto* self = static_cast<CameraEngine*>(context);
    if (self && self->running_) self->startRepeating();
}

void CameraEngine::onSessionActive(void* context, ACameraCaptureSession*) {
    LOGI("Capture session active");
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        self->sessionActive_ = true;
        if (self->running_) self->startRepeating();
    }
}
