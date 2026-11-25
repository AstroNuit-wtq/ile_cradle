#pragma once

#include <algorithm>
#include <cmath>

// Pure C++ circular low-pass filter, kept separate so the wrap-around and
// spike-rejection behavior can be unit-tested on a desktop toolchain.
class CircularHeadingFilter {
public:
    bool update(float rawDegrees, float deltaSeconds, bool reliable = true) {
        if (!reliable || !std::isfinite(rawDegrees)) return false;
        rawDegrees = normalize(rawDegrees);
        const float dt = std::clamp(deltaSeconds, 0.005f, 0.25f);
        if (!initialized_) {
            heading_ = rawDegrees;
            initialized_ = true;
            return true;
        }
        const float delta = shortestDelta(heading_, rawDegrees);
        const float maxStep = std::max(18.0f, 540.0f * dt);
        if (std::fabs(delta) > maxStep * 2.0f) return false;
        constexpr float timeConstantSeconds = 0.22f;
        const float alpha = 1.0f - std::exp(-dt / timeConstantSeconds);
        heading_ = normalize(heading_ + delta * alpha);
        return true;
    }

    bool hasValue() const { return initialized_; }
    float value() const { return heading_; }
    void reset() { initialized_ = false; heading_ = 0.0f; }

private:
    static float normalize(float value) {
        value = std::fmod(value, 360.0f);
        if (value < 0.0f) value += 360.0f;
        return value;
    }
    static float shortestDelta(float from, float to) {
        float delta = normalize(to) - normalize(from);
        if (delta > 180.0f) delta -= 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        return delta;
    }

    bool initialized_ = false;
    float heading_ = 0.0f;
};
