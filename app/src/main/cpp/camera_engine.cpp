#include "camera_engine.h"

#include <algorithm>
#include <climits>
#include <cstring>
#include <string>
#include <vector>

#include <android/log.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>

#define LOG_TAG "DiplomkaNative-Camera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
inline uint8_t clampByte(int value) {
    return static_cast<uint8_t>(std::max(0, std::min(255, value)));
}

// Pick best YUV_420_888 output size for a camera (prefer ~720p).
bool pickYuvSize(ACameraManager* manager, const char* id, int* outW, int* outH) {
    if (!manager || !id || !outW || !outH) return false;
    ACameraMetadata* metadata = nullptr;
    if (ACameraManager_getCameraCharacteristics(manager, id, &metadata) != ACAMERA_OK || !metadata) {
        return false;
    }
    int bestW = 0, bestH = 0, bestScore = INT_MAX;
    ACameraMetadata_const_entry streamConfig{};
    if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &streamConfig) == ACAMERA_OK) {
        for (uint32_t j = 0; j + 3 < streamConfig.count; j += 4) {
            const int32_t fmt = streamConfig.data.i32[j + 0];
            const int32_t w = streamConfig.data.i32[j + 1];
            const int32_t h = streamConfig.data.i32[j + 2];
            const int32_t isInput = streamConfig.data.i32[j + 3];
            if (isInput != 0) continue;
            if (fmt != AIMAGE_FORMAT_YUV_420_888) continue;
            if (w < 320 || h < 240 || w > 1920 || h > 1080) continue;
            const int score = std::abs(w - 1280) + std::abs(h - 720);
            if (score < bestScore) {
                bestScore = score;
                bestW = w;
                bestH = h;
            }
        }
    }
    ACameraMetadata_free(metadata);
    if (bestW <= 0 || bestH <= 0) return false;
    *outW = bestW;
    *outH = bestH;
    return true;
}
} // namespace

CameraEngine::~CameraEngine() {
    stop();
}

void CameraEngine::setError(const char* msg) {
    lastError_ = msg ? msg : "unknown";
    LOGE("%s", lastError_.c_str());
}

bool CameraEngine::chooseBackCamera() {
    if (!manager_) return false;
    ACameraIdList* ids = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(manager_, &ids);
    if (status != ACAMERA_OK || !ids) {
        setError("getCameraIdList failed");
        return false;
    }

    LOGI("Found %d camera(s)", ids->numCameras);
    cameraId_.clear();
    std::string fallbackId;
    int fallbackW = 640, fallbackH = 480;

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
        ACameraMetadata_free(metadata);

        int w = 0, h = 0;
        if (!pickYuvSize(manager_, id, &w, &h)) {
            LOGW("Camera %s has no usable YUV_420_888 size", id);
            continue;
        }
        LOGI("Camera[%d] id=%s facing=%u yuv=%dx%d", i, id, static_cast<unsigned>(lensFacing), w, h);

        if (fallbackId.empty()) {
            fallbackId = id;
            fallbackW = w;
            fallbackH = h;
        }

        if (lensFacing == ACAMERA_LENS_FACING_BACK) {
            cameraId_ = id;
            width_ = w;
            height_ = h;
            LOGI("Selected BACK camera %s size %dx%d", cameraId_.c_str(), width_, height_);
            break;
        }
    }

    if (cameraId_.empty() && !fallbackId.empty()) {
        cameraId_ = fallbackId;
        width_ = fallbackW;
        height_ = fallbackH;
        LOGW("Using fallback camera %s at %dx%d", cameraId_.c_str(), width_, height_);
    }

    ACameraManager_deleteCameraIdList(ids);
    if (cameraId_.empty()) {
        setError("no camera with YUV_420_888");
        return false;
    }
    return true;
}

bool CameraEngine::startRepeating() {
    if (!session_ || !request_) return false;
    // Avoid spamming setRepeatingRequest if already active.
    if (repeatingStarted_.load() && sessionActive_.load()) return true;

    camera_status_t st = ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr);
    if (st != ACAMERA_OK) {
        LOGE("setRepeatingRequest failed: %d", st);
        // Do not treat as hard failure — session may not be ready yet; onSessionReady will retry.
        return false;
    }
    repeatingStarted_ = true;
    LOGI("Repeating request started");
    return true;
}

bool CameraEngine::start(FrameConsumer consumer) {
    std::lock_guard<std::mutex> life(lifecycleMutex_);
    if (running_.load()) return true;

    // Clean any half-open state without re-entering stop()'s lock.
    if (manager_ || device_ || session_ || reader_ || request_) {
        // Inline teardown (stop already locks — call stop carefully)
    }
    stopUnlocked();

    {
        std::lock_guard<std::mutex> lock(consumerMutex_);
        consumer_ = std::move(consumer);
    }
    lastError_.clear();
    sessionActive_ = false;
    repeatingStarted_ = false;
    framesReceived_ = 0;

    manager_ = ACameraManager_create();
    if (!manager_ || !chooseBackCamera()) {
        if (lastError_.empty()) setError("no camera");
        stopUnlocked();
        return false;
    }

    media_status_t mediaStatus = AImageReader_new(width_, height_, AIMAGE_FORMAT_YUV_420_888, 4, &reader_);
    if (mediaStatus != AMEDIA_OK || !reader_) {
        LOGE("AImageReader_new failed: %d size=%dx%d — retry 640x480", mediaStatus, width_, height_);
        width_ = 640;
        height_ = 480;
        mediaStatus = AImageReader_new(width_, height_, AIMAGE_FORMAT_YUV_420_888, 4, &reader_);
        if (mediaStatus != AMEDIA_OK || !reader_) {
            setError("AImageReader_new failed");
            stopUnlocked();
            return false;
        }
    }

    AImageReader_ImageListener listener{};
    listener.context = this;
    listener.onImageAvailable = &CameraEngine::onImageAvailable;
    AImageReader_setImageListener(reader_, &listener);

    readerWindow_ = nullptr;
    AImageReader_getWindow(reader_, &readerWindow_);
    if (!readerWindow_) {
        setError("ImageReader window null");
        stopUnlocked();
        return false;
    }

    ACameraDevice_StateCallbacks deviceCallbacks{};
    deviceCallbacks.context = this;
    deviceCallbacks.onDisconnected = &CameraEngine::onCameraDisconnected;
    deviceCallbacks.onError = &CameraEngine::onCameraError;

    camera_status_t cameraStatus =
        ACameraManager_openCamera(manager_, cameraId_.c_str(), &deviceCallbacks, &device_);
    if (cameraStatus != ACAMERA_OK || !device_) {
        LOGE("openCamera failed: %d", cameraStatus);
        if (cameraStatus == ACAMERA_ERROR_PERMISSION_DENIED) {
            setError("openCamera permission denied — grant CAMERA in system settings");
        } else if (cameraStatus == ACAMERA_ERROR_CAMERA_IN_USE) {
            setError("openCamera: camera in use");
        } else {
            setError("openCamera failed");
        }
        stopUnlocked();
        return false;
    }

    cameraStatus = ACameraOutputTarget_create(readerWindow_, &target_);
    if (cameraStatus != ACAMERA_OK || !target_) {
        setError("OutputTarget create failed");
        stopUnlocked();
        return false;
    }

    cameraStatus = ACameraDevice_createCaptureRequest(device_, TEMPLATE_PREVIEW, &request_);
    if (cameraStatus != ACAMERA_OK || !request_) {
        setError("createCaptureRequest failed");
        stopUnlocked();
        return false;
    }
    ACaptureRequest_addTarget(request_, target_);

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
        setError("session output setup failed");
        stopUnlocked();
        return false;
    }

    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    sessionCallbacks.context = this;
    sessionCallbacks.onClosed = &CameraEngine::onSessionClosed;
    sessionCallbacks.onReady = &CameraEngine::onSessionReady;
    sessionCallbacks.onActive = &CameraEngine::onSessionActive;

    cameraStatus = ACameraDevice_createCaptureSession(device_, outputContainer_, &sessionCallbacks, &session_);
    if (cameraStatus != ACAMERA_OK || !session_) {
        LOGE("createCaptureSession failed: %d", cameraStatus);
        setError("createCaptureSession failed");
        stopUnlocked();
        return false;
    }

    // IMPORTANT: do NOT call setRepeatingRequest here.
    // Many devices only accept repeating after onSessionReady / onSessionActive.
    running_ = true;
    LOGI("Camera pipeline created: id=%s %dx%d (waiting session ready for repeating)",
         cameraId_.c_str(), width_, height_);
    return true;
}

// Teardown without taking lifecycleMutex_ (caller already holds it, or destructor).
void CameraEngine::stopUnlocked() {
    running_ = false;
    sessionActive_ = false;
    repeatingStarted_ = false;

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

void CameraEngine::stop() {
    std::lock_guard<std::mutex> life(lifecycleMutex_);
    stopUnlocked();
}

bool CameraEngine::setTorch(bool enabled) {
    std::lock_guard<std::mutex> life(lifecycleMutex_);
    if (!request_ || !session_ || !running_.load()) return false;
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
    if (!running_.load()) return;
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

    if (ok && width > 0 && height > 0 && planeY && planeU && planeV && pixelY > 0) {
        std::vector<uint8_t> rgba(static_cast<size_t>(width) * static_cast<size_t>(height) * 4);
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
        self->sessionActive_ = false;
        self->repeatingStarted_ = false;
        self->lastError_ = "disconnected";
    }
}

void CameraEngine::onCameraError(void* context, ACameraDevice*, int error) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        LOGE("Camera error: %d", error);
        self->running_ = false;
        self->sessionActive_ = false;
        self->repeatingStarted_ = false;
        self->lastError_ = "camera error " + std::to_string(error);
    }
}

void CameraEngine::onSessionClosed(void*, ACameraCaptureSession*) {
    LOGI("Capture session closed");
}

void CameraEngine::onSessionReady(void* context, ACameraCaptureSession*) {
    LOGI("Capture session ready");
    auto* self = static_cast<CameraEngine*>(context);
    if (self && self->running_.load()) {
        self->startRepeating();
    }
}

void CameraEngine::onSessionActive(void* context, ACameraCaptureSession*) {
    LOGI("Capture session active");
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        self->sessionActive_ = true;
        if (self->running_.load()) {
            self->startRepeating();
        }
    }
}
