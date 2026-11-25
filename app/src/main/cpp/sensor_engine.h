#pragma once

#include <android/looper.h>
#include <android/sensor.h>
#include "heading_filter.h"

class SensorEngine {
public:
    ~SensorEngine();
    bool start(ALooper* looper);
    void stop();
    void poll();

    bool hasHeading() const { return filter_.hasValue(); }
    float headingDegrees() const { return filter_.value(); }
    int accuracy() const { return accuracy_; }

private:
    ASensorManager* manager_ = nullptr;
    const ASensor* rotationVector_ = nullptr;
    ASensorEventQueue* queue_ = nullptr;
    CircularHeadingFilter filter_;
    int accuracy_ = 0;
    int64_t lastTimestampNs_ = 0;
};
