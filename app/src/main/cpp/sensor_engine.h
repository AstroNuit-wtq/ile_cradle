#pragma once

#include <android/looper.h>
#include <android/sensor.h>
#include "heading_filter.h"

class SensorEngine {
public:
    SensorEngine() = default;
    ~SensorEngine();

    bool start(ALooper* looper);
    void stop();
    void poll();

    float headingDegrees() const { return filter_.heading(); }
    bool hasHeading() const { return filter_.hasHeading(); }

private:
    ASensorManager* manager_ = nullptr;
    ASensorEventQueue* queue_ = nullptr;
    const ASensor* rotationVector_ = nullptr;

    HeadingFilter filter_;
    int64_t lastTimestampNs_ = 0;
    int accuracy_ = 0;
};
