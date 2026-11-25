#pragma once

#include <algorithm>
#include <cmath>

// Pure C++ circular low-pass filter, kept separate for unit testing.
class HeadingFilter {
public:
    explicit HeadingFilter(float alpha = 0.15f) : alpha_(alpha) {}

    void reset() {
        initialized_ = false;
        heading_ = 0.f;
    }

    void update(float ax, float ay, float az, float mx, float my, float mz) {
        // Simple tilt-compensated compass
        float roll = std::atan2(ay, az);
        float pitch = std::atan2(-ax, std::sqrt(ay * ay + az * az));

        float mx2 = mx * std::cos(pitch) + mz * std::sin(pitch);
        float my2 = mx * std::sin(roll) * std::sin(pitch) + my * std::cos(roll) - mz * std::sin(roll) * std::cos(pitch);

        float heading = std::atan2(-my2, mx2) * 180.f / 3.14159265f;
        if (heading < 0.f) heading += 360.f;

        if (!initialized_) {
            heading_ = heading;
            initialized_ = true;
        } else {
            // Circular lerp
            float diff = heading - heading_;
            if (diff > 180.f) diff -= 360.f;
            if (diff < -180.f) diff += 360.f;
            heading_ += alpha_ * diff;
            if (heading_ < 0.f) heading_ += 360.f;
            if (heading_ >= 360.f) heading_ -= 360.f;
        }
    }

    float heading() const { return heading_; }

private:
    float alpha_;
    float heading_ = 0.f;
    bool initialized_ = false;
};
