#pragma once

#include <android/looper.h>
#include <android/sensor.h>
#include "heading_filter.h"

class SensorEngine {
public:
    SensorEngine();
    ~SensorEngine();

    bool start();
    void stop();

    float headingDegrees() const { return filter_.heading(); }
    float proximityCm() const { return proximityCm_; }
    bool hasProximity() const { return hasProximity_; }

private:
    static int sensorCallback(int fd, int events, void* data);
    void processEvents();

    ASensorManager* manager_ = nullptr;
    ASensorEventQueue* queue_ = nullptr;
    const ASensor* accel_ = nullptr;
    const ASensor* mag_ = nullptr;
    const ASensor* proximity_ = nullptr;

    HeadingFilter filter_;
    float proximityCm_ = 100.f;
    bool hasProximity_ = false;
    bool running_ = false;
};
