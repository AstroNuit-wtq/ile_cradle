#include "camera_engine.h"

#include <algorithm>
#include <chrono>
#include <climits>
#include <cstring>
#include <sstream>

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

const char* statusName(camera_status_t s) {
    switch (s) {
        case ACAMERA_OK: return "OK";
        case ACAMERA_ERROR_UNKNOWN: return "UNKNOWN";
        case ACAMERA_ERROR_INVALID_PARAMETER: return "INVALID_PARAMETER";
        case ACAMERA_ERROR_CAMERA_DISCONNECTED: return "CAMERA_DISCONNECTED";
        case ACAMERA_ERROR_NOT_ENOUGH_MEMORY: return "NOT_ENOUGH_MEMORY";
        case ACAMERA_ERROR_METADATA_NOT_FOUND: return "METADATA_NOT_FOUND";
        case ACAMERA_ERROR_CAMERA_DEVICE: return "CAMERA_DEVICE";
        case ACAMERA_ERROR_CAMERA_SERVICE: return "CAMERA_SERVICE";
        case ACAMERA_ERROR_SESSION_CLOSED: return "SESSION_CLOSED";
        case ACAMERA_ERROR_INVALID_OPERATION: return "INVALID_OPERATION";
        case ACAMERA_ERROR_CAMERA_IN_USE: return "CAMERA_IN_USE";
        case ACAMERA_ERROR_MAX_CAMERA_IN_USE: return "MAX_CAMERA_IN_USE";
        case ACAMERA_ERROR_CAMERA_DISABLED: return "CAMERA_DISABLED";
        case ACAMERA_ERROR_PERMISSION_DENIED: return "PERMISSION_DENIED";
        default: return "OTHER";
    }
}

bool pickYuvSize(ACameraManager* manager, const char* id, int* outW, int* outH, std::string* sizesLog) {
    if (!manager || !id || !outW || !outH) return false;
    ACameraMetadata* metadata = nullptr;
    if (ACameraManager_getCameraCharacteristics(manager, id, &metadata) != ACAMERA_OK || !metadata) {
        return false;
    }
    int bestW = 0, bestH = 0, bestScore = INT_MAX;
    std::ostringstream oss;
    ACameraMetadata_const_entry streamConfig{};
    if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &streamConfig) == ACAMERA_OK) {
        int listed = 0;
        for (uint32_t j = 0; j + 3 < streamConfig.count; j += 4) {
            const int32_t fmt = streamConfig.data.i32[j + 0];
            const int32_t w = streamConfig.data.i32[j + 1];
            const int32_t h = streamConfig.data.i32[j + 2];
            const int32_t isInput = streamConfig.data.i32[j + 3];
            if (isInput != 0) continue;
            if (fmt != AIMAGE_FORMAT_YUV_420_888) continue;
            if (listed < 8) {
                if (listed) oss << ',';
                oss << w << 'x' << h;
                listed++;
            }
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
    if (sizesLog) *sizesLog = oss.str();
    if (bestW <= 0 || bestH <= 0) return false;
    *outW = bestW;
    *outH = bestH;
    return true;
}
} // namespace

CameraEngine::CameraEngine() {
    worker_ = std::thread([this] { workerMain(); });
}

CameraEngine::~CameraEngine() {
    workerExit_ = true;
    wantStop_ = true;
    wakeCv_.notify_all();
    if (worker_.joinable()) worker_.join();
}

void CameraEngine::setDiagnostic(const std::string& msg) {
    {
        std::lock_guard<std::mutex> lock(diagMutex_);
        diagnostic_ = msg;
    }
    LOGI("diag: %s", msg.c_str());
}

std::string CameraEngine::diagnostic() const {
    std::lock_guard<std::mutex> lock(diagMutex_);
    return diagnostic_;
}

void CameraEngine::requestStart(FrameConsumer consumer) {
    {
        std::lock_guard<std::mutex> lock(consumerMutex_);
        consumer_ = std::move(consumer);
    }
    wantStop_ = false;
    wantStart_ = true;
    wakeCv_.notify_all();
}

void CameraEngine::requestStop() {
    wantStart_ = false;
    wantStop_ = true;
    wakeCv_.notify_all();
}

void CameraEngine::clearFailure() {
    if (state_.load() == CameraState::Failed) {
        state_ = CameraState::Idle;
        setDiagnostic("cleared");
    }
}

void CameraEngine::workerMain() {
    while (!workerExit_.load()) {
        bool doStart = false;
        bool doStop = false;
        {
            std::unique_lock<std::mutex> lock(wakeMutex_);
            wakeCv_.wait_for(lock, std::chrono::milliseconds(200), [&] {
                return workerExit_.load() || wantStart_.load() || wantStop_.load();
            });
            if (workerExit_.load()) break;
            doStop = wantStop_.exchange(false);
            doStart = wantStart_.exchange(false);
        }

        if (doStop) {
            state_ = CameraState::Stopping;
            closePipeline();
            state_ = CameraState::Idle;
            setDiagnostic("stopped");
        }

        if (doStart) {
            if (state_.load() == CameraState::Streaming || manager_) {
                closePipeline();
            }
            state_ = CameraState::Starting;
            setDiagnostic("opening…");
            framesReceived_ = 0;
            sessionActive_ = false;
            repeatingStarted_ = false;
            if (openPipeline()) {
                state_ = CameraState::Streaming;
                setDiagnostic(std::string("streaming ") + std::to_string(width_.load()) + "x" +
                              std::to_string(height_.load()) + " id=" + cameraId_);
            } else {
                closePipeline();
                state_ = CameraState::Failed;
            }
        }
    }
    closePipeline();
}

bool CameraEngine::chooseBackCamera() {
    if (!manager_) return false;
    ACameraIdList* ids = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(manager_, &ids);
    if (status != ACAMERA_OK || !ids) {
        setDiagnostic(std::string("getCameraIdList failed: ") + statusName(status));
        return false;
    }

    LOGI("Found %d camera(s)", ids->numCameras);
    cameraId_.clear();
    std::string fallbackId;
    int fallbackW = 640, fallbackH = 480;
    std::string sizesSummary;

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
        std::string sizes;
        if (!pickYuvSize(manager_, id, &w, &h, &sizes)) {
            LOGW("Camera %s: no usable YUV_420_888 in 320..1920", id);
            continue;
        }
        LOGI("Camera[%d] id=%s facing=%u pick=%dx%d sizes=[%s]", i, id,
             static_cast<unsigned>(lensFacing), w, h, sizes.c_str());
        sizesSummary += std::string(id) + ":" + sizes + "; ";

        if (fallbackId.empty()) {
            fallbackId = id;
            fallbackW = w;
            fallbackH = h;
        }
        if (lensFacing == ACAMERA_LENS_FACING_BACK) {
            cameraId_ = id;
            width_ = w;
            height_ = h;
            break;
        }
    }

    if (cameraId_.empty() && !fallbackId.empty()) {
        cameraId_ = fallbackId;
        width_ = fallbackW;
        height_ = fallbackH;
        LOGW("Using fallback camera %s at %dx%d", cameraId_.c_str(), width_.load(), height_.load());
    }

    ACameraManager_deleteCameraIdList(ids);
    if (cameraId_.empty()) {
        setDiagnostic(std::string("no YUV_420_888 camera. seen: ") + sizesSummary);
        return false;
    }
    return true;
}

bool CameraEngine::startRepeating() {
    std::lock_guard<std::recursive_mutex> lock(pipelineMutex_);
    if (!session_ || !request_) return false;
    camera_status_t st = ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr);
    if (st != ACAMERA_OK) {
        LOGE("setRepeatingRequest: %s (%d)", statusName(st), st);
        return false;
    }
    repeatingStarted_ = true;
    LOGI("Repeating request started");
    return true;
}

bool CameraEngine::openPipeline() {
    std::lock_guard<std::recursive_mutex> lock(pipelineMutex_);

    manager_ = ACameraManager_create();
    if (!manager_) {
        setDiagnostic("ACameraManager_create returned null");
        return false;
    }
    if (!chooseBackCamera()) {
        return false;
    }

    const int w = width_.load();
    const int h = height_.load();
    media_status_t mediaStatus = AImageReader_new(w, h, AIMAGE_FORMAT_YUV_420_888, 4, &reader_);
    if (mediaStatus != AMEDIA_OK || !reader_) {
        width_ = 640;
        height_ = 480;
        mediaStatus = AImageReader_new(640, 480, AIMAGE_FORMAT_YUV_420_888, 4, &reader_);
        if (mediaStatus != AMEDIA_OK || !reader_) {
            setDiagnostic(std::string("AImageReader_new failed status=") + std::to_string(mediaStatus) +
                          " size=" + std::to_string(w) + "x" + std::to_string(h));
            return false;
        }
    }

    AImageReader_ImageListener listener{};
    listener.context = this;
    listener.onImageAvailable = &CameraEngine::onImageAvailable;
    AImageReader_setImageListener(reader_, &listener);
    AImageReader_getWindow(reader_, &readerWindow_);
    if (!readerWindow_) {
        setDiagnostic("ImageReader native window is null");
        return false;
    }

    ACameraDevice_StateCallbacks deviceCallbacks{};
    deviceCallbacks.context = this;
    deviceCallbacks.onDisconnected = &CameraEngine::onCameraDisconnected;
    deviceCallbacks.onError = &CameraEngine::onCameraError;

    camera_status_t cameraStatus =
        ACameraManager_openCamera(manager_, cameraId_.c_str(), &deviceCallbacks, &device_);
    if (cameraStatus != ACAMERA_OK || !device_) {
        setDiagnostic(std::string("openCamera(") + cameraId_ + ") " + statusName(cameraStatus) +
                      " (" + std::to_string(cameraStatus) + ")");
        return false;
    }

    cameraStatus = ACameraOutputTarget_create(readerWindow_, &target_);
    if (cameraStatus != ACAMERA_OK || !target_) {
        setDiagnostic(std::string("ACameraOutputTarget_create ") + statusName(cameraStatus));
        return false;
    }

    cameraStatus = ACameraDevice_createCaptureRequest(device_, TEMPLATE_PREVIEW, &request_);
    if (cameraStatus != ACAMERA_OK || !request_) {
        setDiagnostic(std::string("createCaptureRequest ") + statusName(cameraStatus));
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
        setDiagnostic("session output container setup failed");
        return false;
    }

    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    sessionCallbacks.context = this;
    sessionCallbacks.onClosed = &CameraEngine::onSessionClosed;
    sessionCallbacks.onReady = &CameraEngine::onSessionReady;
    sessionCallbacks.onActive = &CameraEngine::onSessionActive;

    cameraStatus = ACameraDevice_createCaptureSession(device_, outputContainer_, &sessionCallbacks, &session_);
    if (cameraStatus != ACAMERA_OK || !session_) {
        setDiagnostic(std::string("createCaptureSession ") + statusName(cameraStatus) +
                      " size=" + std::to_string(width_.load()) + "x" + std::to_string(height_.load()));
        return false;
    }

    LOGI("Pipeline created id=%s %dx%d (await session ready)", cameraId_.c_str(), width_.load(), height_.load());
    return true;
}

void CameraEngine::closePipeline() {
    std::lock_guard<std::recursive_mutex> lock(pipelineMutex_);
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
}

bool CameraEngine::setTorch(bool enabled) {
    std::lock_guard<std::recursive_mutex> lock(pipelineMutex_);
    if (!request_ || !session_ || state_.load() != CameraState::Streaming) return false;
    uint8_t mode = enabled ? ACAMERA_FLASH_MODE_TORCH : ACAMERA_FLASH_MODE_OFF;
    camera_status_t status = ACaptureRequest_setEntry_u8(request_, ACAMERA_FLASH_MODE, 1, &mode);
    if (status != ACAMERA_OK) return false;
    status = ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr);
    if (status != ACAMERA_OK) return false;
    torchEnabled_ = enabled;
    return true;
}

void CameraEngine::onImageAvailable(void* context, AImageReader* reader) {
    auto* self = static_cast<CameraEngine*>(context);
    if (self) self->consumeLatestImage(reader);
}

void CameraEngine::consumeLatestImage(AImageReader* reader) {
    if (state_.load() != CameraState::Streaming) return;
    AImage* image = nullptr;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);
    if (status != AMEDIA_OK || !image) {
        if (status != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
            LOGE("acquireLatestImage status=%d", status);
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
                LOGI("Frame #%d (%dx%d)", n, width, height);
            }
        }
    } else {
        LOGE("YUV plane read failed w=%d h=%d", width, height);
    }

    AImage_delete(image);
}

void CameraEngine::onCameraDisconnected(void* context, ACameraDevice*) {
    auto* self = static_cast<CameraEngine*>(context);
    if (!self) return;
    LOGE("Camera disconnected");
    self->setDiagnostic("camera disconnected");
    self->state_ = CameraState::Failed;
    self->sessionActive_ = false;
    self->repeatingStarted_ = false;
}

void CameraEngine::onCameraError(void* context, ACameraDevice*, int error) {
    auto* self = static_cast<CameraEngine*>(context);
    if (!self) return;
    LOGE("Camera error %d", error);
    self->setDiagnostic(std::string("camera device error code=") + std::to_string(error));
    self->state_ = CameraState::Failed;
    self->sessionActive_ = false;
    self->repeatingStarted_ = false;
}

void CameraEngine::onSessionClosed(void*, ACameraCaptureSession*) {
    LOGI("Capture session closed");
}

void CameraEngine::onSessionReady(void* context, ACameraCaptureSession*) {
    LOGI("Capture session ready");
    auto* self = static_cast<CameraEngine*>(context);
    if (self && self->state_.load() == CameraState::Streaming) {
        self->startRepeating();
    }
}

void CameraEngine::onSessionActive(void* context, ACameraCaptureSession*) {
    LOGI("Capture session active");
    auto* self = static_cast<CameraEngine*>(context);
    if (self) {
        self->sessionActive_ = true;
        if (self->state_.load() == CameraState::Streaming) {
            self->startRepeating();
        }
    }
}
