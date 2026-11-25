#include "vision_engine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <utility>

#include <android/log.h>

#define LOG_TAG "DiplomkaNative-Vision"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// COCO class names (80 classes) used by the YOLOv8n detector.
const char* kCocoNames[80] = {
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
    "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
    "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
};

// Simple IoU for temporal stabilization.
float iou(const RectF& a, const RectF& b) {
    const float x1 = std::max(a.left, b.left);
    const float y1 = std::max(a.top, b.top);
    const float x2 = std::min(a.right, b.right);
    const float y2 = std::min(a.bottom, b.bottom);
    const float inter = std::max(0.0f, x2 - x1) * std::max(0.0f, y2 - y1);
    const float areaA = std::max(0.0f, a.right - a.left) * std::max(0.0f, a.bottom - a.top);
    const float areaB = std::max(0.0f, b.right - b.left) * std::max(0.0f, b.bottom - b.top);
    const float uni = areaA + areaB - inter;
    return uni > 1e-6f ? inter / uni : 0.0f;
}

} // namespace

bool VisionEngine::loadModel(ncnn::Net& net, AAssetManager* assets, const char* paramName, const char* binName) {
    AAsset* paramAsset = AAssetManager_open(assets, paramName, AASSET_MODE_BUFFER);
    AAsset* binAsset = AAssetManager_open(assets, binName, AASSET_MODE_BUFFER);
    if (!paramAsset || !binAsset) {
        if (paramAsset) AAsset_close(paramAsset);
        if (binAsset) AAsset_close(binAsset);
        LOGW("Missing model asset: %s / %s", paramName, binName);
        return false;
    }

    const void* paramData = AAsset_getBuffer(paramAsset);
    const void* binData = AAsset_getBuffer(binAsset);
    const off_t paramLen = AAsset_getLength(paramAsset);
    const off_t binLen = AAsset_getLength(binAsset);

    net.opt.use_vulkan_compute = false;
    net.opt.num_threads = 2;

    int ret = net.load_param_mem(static_cast<const char*>(paramData));
    if (ret != 0) {
        LOGW("load_param_mem failed for %s: %d", paramName, ret);
        AAsset_close(paramAsset);
        AAsset_close(binAsset);
        return false;
    }
    ret = net.load_model(static_cast<const unsigned char*>(binData));
    AAsset_close(paramAsset);
    AAsset_close(binAsset);
    if (ret != 0) {
        LOGW("load_model failed for %s: %d", binName, ret);
        return false;
    }
    LOGI("Loaded %s + %s", paramName, binName);
    return true;
}

bool VisionEngine::initialize(AAssetManager* assets) {
    if (!assets) return false;
    detectorReady_ = loadModel(detector_, assets, "yolov8n.ncnn.param", "yolov8n.ncnn.bin");
    poseReady_ = loadModel(pose_, assets, "yolov8n_pose.ncnn.param", "yolov8n_pose.ncnn.bin");
    return detectorReady_ || poseReady_;
}

std::vector<Detection> VisionEngine::runDetector(const uint8_t* rgba, int width, int height) {
    std::vector<Detection> out;
    if (!detectorReady_ || !rgba || width <= 0 || height <= 0) return out;

    const int target = 320;
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgba, ncnn::Mat::PIXEL_RGBA2RGB, width, height, target, target);
    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(nullptr, norm_vals);

    ncnn::Extractor ex = detector_.create_extractor();
    ex.input("in0", in);

    ncnn::Mat out0;
    if (ex.extract("out0", out0) != 0) return out;

    // YOLOv8 output layout: [1, 84, N] or [1, N, 84] depending on export.
    // We handle the common [1, 84, N] case (class scores + 4 box coords).
    const int num_classes = 80;
    int num_proposals = 0;
    bool channels_first = false;
    if (out0.dims == 3) {
        if (out0.w == num_classes + 4) {
            num_proposals = out0.h;
            channels_first = false;
        } else if (out0.h == num_classes + 4) {
            num_proposals = out0.w;
            channels_first = true;
        } else {
            return out;
        }
    } else {
        return out;
    }

    const float conf_thresh = 0.35f;
    const float nms_thresh = 0.45f;

    std::vector<Detection> candidates;
    for (int i = 0; i < num_proposals; ++i) {
        float cx, cy, w, h;
        float bestScore = 0.f;
        int bestClass = -1;
        if (channels_first) {
            // [1, 84, N]
            cx = out0.channel(0)[i];
            cy = out0.channel(1)[i];
            w  = out0.channel(2)[i];
            h  = out0.channel(3)[i];
            for (int c = 0; c < num_classes; ++c) {
                float s = out0.channel(4 + c)[i];
                if (s > bestScore) { bestScore = s; bestClass = c; }
            }
        } else {
            // [1, N, 84]
            const float* row = out0.row(i);
            cx = row[0]; cy = row[1]; w = row[2]; h = row[3];
            for (int c = 0; c < num_classes; ++c) {
                float s = row[4 + c];
                if (s > bestScore) { bestScore = s; bestClass = c; }
            }
        }
        if (bestScore < conf_thresh || bestClass < 0) continue;

        Detection d;
        d.classId = bestClass;
        d.label = (bestClass >= 0 && bestClass < 80) ? kCocoNames[bestClass] : "object";
        d.confidence = bestScore;
        // Scale from letterboxed target back to original (approx, no padding correction).
        const float sx = static_cast<float>(width) / target;
        const float sy = static_cast<float>(height) / target;
        d.box.left   = (cx - w * 0.5f) * sx;
        d.box.top    = (cy - h * 0.5f) * sy;
        d.box.right  = (cx + w * 0.5f) * sx;
        d.box.bottom = (cy + h * 0.5f) * sy;
        // Heuristic: tall stationary person-like box may be a statue / monument.
        const float bw = d.box.right - d.box.left;
        const float bh = d.box.bottom - d.box.top;
        if (d.classId == 0 && bh > bw * 1.6f && d.confidence > 0.5f) {
            d.possibleStatue = true;
        }
        candidates.push_back(d);
    }

    // Greedy NMS
    std::sort(candidates.begin(), candidates.end(), [](const Detection& a, const Detection& b) {
        return a.confidence > b.confidence;
    });
    std::vector<bool> suppressed(candidates.size(), false);
    for (size_t i = 0; i < candidates.size(); ++i) {
        if (suppressed[i]) continue;
        out.push_back(candidates[i]);
        for (size_t j = i + 1; j < candidates.size(); ++j) {
            if (!suppressed[j] && iou(candidates[i].box, candidates[j].box) > nms_thresh) {
                suppressed[j] = true;
            }
        }
    }
    return out;
}

std::vector<PersonPose> VisionEngine::runPose(const uint8_t* rgba, int width, int height) {
    std::vector<PersonPose> out;
    if (!poseReady_ || !rgba || width <= 0 || height <= 0) return out;

    const int target = 320;
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgba, ncnn::Mat::PIXEL_RGBA2RGB, width, height, target, target);
    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(nullptr, norm_vals);

    ncnn::Extractor ex = pose_.create_extractor();
    ex.input("in0", in);

    ncnn::Mat out0;
    if (ex.extract("out0", out0) != 0) return out;

    // Expected layout for YOLOv8-pose: [1, 56, N] = 4 box + 1 score + 17*3 keypoints.
    int num_proposals = 0;
    bool channels_first = false;
    if (out0.dims == 3) {
        if (out0.h == 56) {
            num_proposals = out0.w;
            channels_first = true;
        } else if (out0.w == 56) {
            num_proposals = out0.h;
            channels_first = false;
        } else {
            return out;
        }
    } else {
        return out;
    }

    const float conf_thresh = 0.4f;
    const float sx = static_cast<float>(width) / target;
    const float sy = static_cast<float>(height) / target;

    for (int i = 0; i < num_proposals; ++i) {
        float score, cx, cy, w, h;
        if (channels_first) {
            cx = out0.channel(0)[i];
            cy = out0.channel(1)[i];
            w  = out0.channel(2)[i];
            h  = out0.channel(3)[i];
            score = out0.channel(4)[i];
        } else {
            const float* row = out0.row(i);
            cx = row[0]; cy = row[1]; w = row[2]; h = row[3]; score = row[4];
        }
        if (score < conf_thresh) continue;

        PersonPose p;
        p.confidence = score;
        p.box.left   = (cx - w * 0.5f) * sx;
        p.box.top    = (cy - h * 0.5f) * sy;
        p.box.right  = (cx + w * 0.5f) * sx;
        p.box.bottom = (cy + h * 0.5f) * sy;

        for (int k = 0; k < 17; ++k) {
            float kx, ky, kc;
            if (channels_first) {
                kx = out0.channel(5 + k * 3 + 0)[i];
                ky = out0.channel(5 + k * 3 + 1)[i];
                kc = out0.channel(5 + k * 3 + 2)[i];
            } else {
                const float* row = out0.row(i);
                kx = row[5 + k * 3 + 0];
                ky = row[5 + k * 3 + 1];
                kc = row[5 + k * 3 + 2];
            }
            p.keypoints[k].x = kx * sx;
            p.keypoints[k].y = ky * sy;
            p.keypoints[k].confidence = kc;
        }
        out.push_back(p);
    }
    return out;
}

std::vector<Detection> VisionEngine::stabilize(std::vector<Detection> detections) {
    // Simple temporal hysteresis: prefer previous box if IoU is high.
    for (auto& current : detections) {
        for (const auto& previous : previousDetections_) {
            if (current.classId == previous.classId && iou(current.box, previous.box) > 0.4f) {
                const float oldWeight = 0.55f;
                current.box.left   = oldWeight * previous.box.left   + (1.0f - oldWeight) * current.box.left;
                current.box.top    = oldWeight * previous.box.top    + (1.0f - oldWeight) * current.box.top;
                current.box.right  = oldWeight * previous.box.right  + (1.0f - oldWeight) * current.box.right;
                current.box.bottom = oldWeight * previous.box.bottom + (1.0f - oldWeight) * current.box.bottom;
                break;
            }
        }
    }
    previousDetections_ = detections;
    return detections;
}

VisionFrame VisionEngine::processRgba(const uint8_t* rgba, int width, int height, bool enabled, bool runPoseThisFrame) {
    VisionFrame frame;
    frame.frameWidth = width;
    frame.frameHeight = height;
    frame.detectorReady = detectorReady_;
    frame.poseReady = poseReady_;

    if (!enabled || !rgba || width <= 0 || height <= 0) {
        return frame;
    }

    const auto t0 = std::chrono::steady_clock::now();

    if (detectorReady_) {
        auto dets = runDetector(rgba, width, height);
        frame.detections = stabilize(std::move(dets));
    }

    if (poseReady_ && runPoseThisFrame) {
        frame.people = runPose(rgba, width, height);
        previousPeople_ = frame.people;
    } else {
        frame.people = previousPeople_;
    }

    const auto t1 = std::chrono::steady_clock::now();
    frame.inferenceMs = std::chrono::duration<float, std::milli>(t1 - t0).count();
    ++frameNumber_;
    return frame;
}
