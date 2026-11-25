#include "sensor_engine.h"

#include <algorithm>
#include <cmath>

#include <android/log.h>

#define LOG_TAG "DiplomkaNative-Sensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {
constexpr float kRadiansToDegrees = 57.29577951308232f;
constexpr int kSensorLooperId = 3;
}

SensorEngine::~SensorEngine() {
    stop();
}

bool SensorEngine::start(ALooper* looper) {
    if (queue_) return true;
    manager_ = ASensorManager_getInstanceForPackage("com.diplomka.app");
    if (!manager_) {
        LOGW("Sensor manager unavailable");
        return false;
    }
    rotationVector_ = ASensorManager_getDefaultSensor(manager_, ASENSOR_TYPE_ROTATION_VECTOR);
    if (!rotationVector_) {
        LOGW("Rotation vector sensor unavailable");
        return false;
    }
    queue_ = ASensorManager_createEventQueue(manager_, looper, kSensorLooperId, nullptr, nullptr);
    if (!queue_) {
        LOGW("Unable to create sensor event queue");
        return false;
    }
    if (ASensorEventQueue_enableSensor(queue_, rotationVector_) < 0) {
        LOGW("Unable to enable rotation vector sensor");
        stop();
        return false;
    }
    ASensorEventQueue_setEventRate(queue_, rotationVector_, 20000);
    LOGI("Rotation vector sensor enabled");
    return true;
}

void SensorEngine::stop() {
    if (queue_ && rotationVector_) ASensorEventQueue_disableSensor(queue_, rotationVector_);
    if (queue_ && manager_) ASensorManager_destroyEventQueue(manager_, queue_);
    queue_ = nullptr;
    rotationVector_ = nullptr;
    manager_ = nullptr;
    filter_.reset();
    lastTimestampNs_ = 0;
}

void SensorEngine::poll() {
    if (!queue_) return;
    ASensorEvent event{};
    while (ASensorEventQueue_getEvents(queue_, &event, 1) > 0) {
        if (event.type != ASENSOR_TYPE_ROTATION_VECTOR) continue;
        const bool reliable = event.vector.status != ASENSOR_STATUS_UNRELIABLE;
        if (!reliable) { accuracy_ = 0; continue; }

        const float x = event.data[0];
        const float y = event.data[1];
        const float z = event.data[2];
        const float w = event.data[3];
        // Android's azimuth is the heading of the device's top edge (+Y),
        // not a generic yaw around the device Z axis. These matrix terms are
        // equivalent to SensorManager.getOrientation() for a portrait device.
        const float rotationR1 = 2.0f * (x * y - w * z);
        const float rotationR4 = 1.0f - 2.0f * (x * x + z * z);
        const float azimuth = std::atan2(rotationR1, rotationR4);
        accuracy_ = static_cast<int>(event.vector.status);
        const float dt = lastTimestampNs_ > 0 && event.timestamp > lastTimestampNs_
            ? static_cast<float>(event.timestamp - lastTimestampNs_) / 1000000000.0f
            : 0.02f;
        lastTimestampNs_ = event.timestamp;
        filter_.update(azimuth * kRadiansToDegrees, dt, reliable);
    }
}
