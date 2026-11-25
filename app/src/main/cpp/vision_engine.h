#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include <android/asset_manager.h>
#include <net.h>

struct RectF {
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
};

struct Detection {
    int classId = -1;
    std::string label;
    float confidence = 0.0f;
    RectF box;
    bool possibleStatue = false;
};

struct BodyPoint {
    float x = 0.0f;
    float y = 0.0f;
    float confidence = 0.0f;
};

struct PersonPose {
    RectF box;
    float confidence = 0.0f;
    std::array<BodyPoint, 17> keypoints{};
};

struct VisionFrame {
    std::vector<Detection> detections;
    std::vector<PersonPose> people;
    bool detectorReady = false;
    bool poseReady = false;
    float inferenceMs = 0.0f;
    int frameWidth = 0;
    int frameHeight = 0;
};

class VisionEngine {
public:
    bool initialize(AAssetManager* assets);
    VisionFrame processRgba(const uint8_t* rgba, int width, int height, bool enabled, bool runPoseThisFrame);
    bool isDetectorReady() const { return detectorReady_; }
    bool isPoseReady() const { return poseReady_; }

private:
    bool loadModel(ncnn::Net& net, AAssetManager* assets, const char* paramName, const char* binName);
    std::vector<Detection> runDetector(const uint8_t* rgba, int width, int height);
    std::vector<PersonPose> runPose(const uint8_t* rgba, int width, int height);
    std::vector<Detection> stabilize(std::vector<Detection> detections);

    ncnn::Net detector_;
    ncnn::Net pose_;
    bool detectorReady_ = false;
    bool poseReady_ = false;
    uint64_t frameNumber_ = 0;
    std::vector<Detection> previousDetections_;
    std::vector<PersonPose> previousPeople_;
};
