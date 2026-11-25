#pragma once

#include <algorithm>
#include <cmath>

// Circular low-pass filter for heading (degrees). Supports both rotation-vector
// updates (headingDeg, dt, reliable) and classic accel+mag tilt-compensated path.
class HeadingFilter {
public:
    explicit HeadingFilter(float alpha = 0.15f) : alpha_(alpha) {}

    void reset() {
        initialized_ = false;
        heading_ = 0.f;
    }

    // Rotation-vector path used by SensorEngine::poll()
    void update(float headingDeg, float /*dt*/, bool reliable) {
        if (!reliable) return;
        float heading = headingDeg;
        while (heading < 0.f) heading += 360.f;
        while (heading >= 360.f) heading -= 360.f;

        if (!initialized_) {
            heading_ = heading;
            initialized_ = true;
            return;
        }
        float diff = heading - heading_;
        if (diff > 180.f) diff -= 360.f;
        if (diff < -180.f) diff += 360.f;
        heading_ += alpha_ * diff;
        if (heading_ < 0.f) heading_ += 360.f;
        if (heading_ >= 360.f) heading_ -= 360.f;
    }

    // Legacy accel + magnetometer tilt-compensated compass
    void update(float ax, float ay, float az, float mx, float my, float mz) {
        float roll = std::atan2(ay, az);
        float pitch = std::atan2(-ax, std::sqrt(ay * ay + az * az));

        float mx2 = mx * std::cos(pitch) + mz * std::sin(pitch);
        float my2 = mx * std::sin(roll) * std::sin(pitch) + my * std::cos(roll) -
                    mz * std::sin(roll) * std::cos(pitch);

        float heading = std::atan2(-my2, mx2) * 180.f / 3.14159265f;
        if (heading < 0.f) heading += 360.f;

        if (!initialized_) {
            heading_ = heading;
            initialized_ = true;
        } else {
            float diff = heading - heading_;
            if (diff > 180.f) diff -= 360.f;
            if (diff < -180.f) diff += 360.f;
            heading_ += alpha_ * diff;
            if (heading_ < 0.f) heading_ += 360.f;
            if (heading_ >= 360.f) heading_ -= 360.f;
        }
    }

    float heading() const { return heading_; }
    bool hasHeading() const { return initialized_; }

private:
    float alpha_;
    float heading_ = 0.f;
    bool initialized_ = false;
};
