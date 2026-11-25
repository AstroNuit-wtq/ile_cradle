#include "camera_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
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
        LOGE("ACameraManager_getCameraIdList failed: %d", status);
        return false;
    }

    cameraId_.clear();
    for (int i = 0; i < ids->numCameras; ++i) {
        ACameraMetadata* metadata = nullptr;
        if (ACameraManager_getCameraCharacteristics(manager_, ids->cameraIds[i], &metadata) != ACAMERA_OK || !metadata) {
            continue;
        }
        ACameraMetadata_const_entry facing{};
        const bool hasFacing = ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &facing) == ACAMERA_OK;
        if (hasFacing && facing.count > 0 && facing.data.u8[0] == ACAMERA_LENS_FACING_BACK) {
            cameraId_ = ids->cameraIds[i];
            // Prefer a moderate preview size (~720p or closest to 640x480).
            ACameraMetadata_const_entry streamConfig{};
            if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &streamConfig) == ACAMERA_OK) {
                int bestW = 640, bestH = 480;
                int bestScore = INT32_MAX;
                for (uint32_t j = 0; j + 3 < streamConfig.count; j += 4) {
                    const int32_t fmt = streamConfig.data.i32[j + 0];
                    const int32_t w   = streamConfig.data.i32[j + 1];
                    const int32_t h   = streamConfig.data.i32[j + 2];
                    const int32_t isInput = streamConfig.data.i32[j + 3];
                    if (isInput != 0) continue;
                    if (fmt != AIMAGE_FORMAT_YUV_420_888) continue;
                    // Prefer landscape-ish sizes around 640–1280 width.
                    const int score = std::abs(w - 960) + std::abs(h - 720);
                    if (score < bestScore && w >= 320 && h >= 240 && w <= 1920) {
                        bestScore = score;
                        bestW = w;
                        bestH = h;
                    }
                }
                width_ = bestW;
                height_ = bestH;
                LOGI("Selected stream size %dx%d for camera %s", width_, height_, cameraId_.c_str());
            }
            ACameraMetadata_free(metadata);
            break;
        }
        ACameraMetadata_free(metadata);
    }

    if (cameraId_.empty() && ids->numCameras > 0) {
        cameraId_ = ids->cameraIds[0];
        LOGW("No back camera, falling back to %s", cameraId_.c_str());
    }
    ACameraManager_deleteCameraIdList(ids);
    return !cameraId_.empty();
}

bool CameraEngine::start(FrameConsumer consumer) {
    if (running_) return true;
    // A disconnected or previously failed session may leave handles allocated.
    if (manager_ || device_ || session_ || reader_ || request_) stop();
    {
        std::lock_guard<std::mutex> lock(consumerMutex_);
        consumer_ = std::move(consumer);
    }

    manager_ = ACameraManager_create();
    if (!manager_ || !chooseBackCamera()) {
        LOGE("No camera found");
        stop();
        return false;
    }

    media_status_t mediaStatus = AImageReader_new(width_, height_, AIMAGE_FORMAT_YUV_420_888, 4, &reader_);
    if (mediaStatus != AMEDIA_OK || !reader_) {
        LOGE("AImageReader_new failed: %d (size %dx%d)", mediaStatus, width_, height_);
        stop();
        return false;
    }

    AImageReader_ImageListener listener{};
    listener.context = this;
    listener.onImageAvailable = &CameraEngine::onImageAvailable;
    AImageReader_setImageListener(reader_, &listener);
    AImageReader_getWindow(reader_, &readerWindow_);

    ACameraDevice_StateCallbacks deviceCallbacks{};
    deviceCallbacks.context = this;
    deviceCallbacks.onDisconnected = &CameraEngine::onCameraDisconnected;
    deviceCallbacks.onError = &CameraEngine::onCameraError;

    camera_status_t cameraStatus = ACameraManager_openCamera(manager_, cameraId_.c_str(), &deviceCallbacks, &device_);
    if (cameraStatus != ACAMERA_OK || !device_) {
        LOGE("openCamera failed: %d (permission?)", cameraStatus);
        stop();
        return false;
    }

    cameraStatus = ACameraOutputTarget_create(readerWindow_, &target_);
    if (cameraStatus != ACAMERA_OK || !target_) {
        LOGE("ACameraOutputTarget_create failed: %d", cameraStatus);
        stop();
        return false;
    }

    cameraStatus = ACameraDevice_createCaptureRequest(device_, TEMPLATE_PREVIEW, &request_);
    if (cameraStatus != ACAMERA_OK || !request_) {
        LOGE("createCaptureRequest failed: %d", cameraStatus);
        stop();
        return false;
    }
    ACaptureRequest_addTarget(request_, target_);

    ACaptureSessionOutputContainer_create(&outputContainer_);
    ACaptureSessionOutput_create(readerWindow_, &sessionOutput_);
    if (!outputContainer_ || !sessionOutput_ ||
        ACaptureSessionOutputContainer_add(outputContainer_, sessionOutput_) != ACAMERA_OK) {
        LOGE("Capture output setup failed");
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
        LOGE("createCaptureSession failed: %d", cameraStatus);
        stop();
        return false;
    }

    cameraStatus = ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr);
    if (cameraStatus != ACAMERA_OK) {
        LOGE("setRepeatingRequest failed: %d", cameraStatus);
        stop();
        return false;
    }

    running_ = true;
    framesReceived_ = 0;
    LOGI("Camera started: %s (%dx%d)", cameraId_.c_str(), width_, height_);
    return true;
}

void CameraEngine::stop() {
    running_ = false;
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
        LOGE("updateRepeatingRequest for torch failed: %d", status);
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
    if (status != AMEDIA_OK || !image) {
        if (status != AMEDIA_ERROR_INVALID_PARAMETER) {
            LOGW("acquireLatestImage status=%d", status);
        }
        return;
    }

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
        for (int y = 0; y < height; ++y) {
            const int uvY = y / 2;
            for (int x = 0; x < width; ++x) {
                const int yIndex = y * rowY + x * pixelY;
                const int uIndex = uvY * rowU + (x / 2) * pixelU;
                const int vIndex = uvY * rowV + (x / 2) * pixelV;
                if (yIndex >= lenY || uIndex >= lenU || vIndex >= lenV) continue;

                const int yy = std::max(0, static_cast<int>(planeY[yIndex]) - 16);
                const int uu = static_cast<int>(planeU[uIndex]) - 128;
                const int vv = static_cast<int>(planeV[vIndex]) - 128;
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
            if (n == 1 || n % 60 == 0) {
                LOGI("Frame #%d delivered (%dx%d)", n, width, height);
            }
        }
    } else {
        LOGW("YUV plane read failed ok=%d w=%d h=%d", ok ? 1 : 0, width, height);
    }

    AImage_delete(image);
}

void CameraEngine::onCameraDisconnected(void* context, ACameraDevice*) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        LOGE("Camera disconnected");
        self->running_ = false;
    }
}

void CameraEngine::onCameraError(void* context, ACameraDevice*, int error) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        LOGE("Camera error: %d", error);
        self->running_ = false;
    }
}

void CameraEngine::onSessionClosed(void*, ACameraCaptureSession*) {
    LOGI("Capture session closed");
}
void CameraEngine::onSessionReady(void*, ACameraCaptureSession*) {
    LOGI("Capture session ready");
}
void CameraEngine::onSessionActive(void*, ACameraCaptureSession*) {
    LOGI("Capture session active");
}
