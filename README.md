# Diplomka Native — Android C++ rewrite

This project is a clean native Android rewrite of the supplied Diplomka source archive.
The Android application code is C++ (Android NDK / NativeActivity). There are no Kotlin,
Java or Python application source files and no Python runtime is packaged into the APK.
Gradle build configuration uses Groovy DSL.

## What the CI build expects

The companion workflow file is `build-astro-cpp.yml`. Put it in the repository at
`.github/workflows/build-astro-cpp.yml`.

On a push to a branch that changes the sources or the workflow, or from **Actions → Build Diplomka Native
C++ APK → Run workflow**, the job:

1. Checks out the repository.
2. Installs JDK 17, Android SDK 35, NDK r30 and CMake 3.22.1.
3. Uses the checked-out native sources (or extracts a ZIP if present).
4. Downloads the versioned NCNN Android CPU runtime (SHA-256 checked) and YOLOv8n object/pose models from a fixed upstream model tag.
5. Fetches Dear ImGui source during CMake configure.
6. Builds an arm64-v8a APK and uploads it as a 14-day GitHub Actions artifact.

The build directory, downloaded models and third-party dependencies live under
`$RUNNER_TEMP` or Gradle's runner cache. The workflow does not write generated files
back to the checked-out repository and does not commit anything.

## Source layout

- `app/src/main/cpp/main.cpp` — NativeActivity loop, ImGui UI, Android permission and GPS JNI calls.
- `app/src/main/cpp/camera_engine.*` — NDK Camera2 capture and YUV-to-RGBA conversion.
- `app/src/main/cpp/sensor_engine.*` — native rotation-vector sensor input.
- `app/src/main/cpp/heading_filter.h` — circular compass filter with spike rejection.
- `app/src/main/cpp/vision_engine.*` — NCNN object detection and human-pose inference.
- `tests/heading_filter_test.cpp` — desktop test for compass wrap-around and spike rejection.

## Build output

Actions artifact: `Diplomka-Native-Cpp-APK`

APK: `Diplomka-Native-C++-arm64-v8a.apk`

The APK is signed with the standard debug key so it can be installed for testing. Do not
publish this APK to Google Play. A private release keystore and signing secrets must be
configured before distribution.

## Requirements and limitations

- The CI package currently targets `arm64-v8a` devices and Android 8.0 (API 26) or newer.
- Camera frames are captured with Android NDK Camera2 and processed locally.
- YOLOv8n detects the 80 COCO classes. YOLOv8n-pose estimates 17 human body landmarks.
- A stationary person-like detection is not automatically called a statue. The pretrained
  COCO model has no dedicated statue class; a separately trained statue/monument model and
  a labelled dataset are required for dependable recognition.
- The app intentionally does not show made-up metric distances or walking speed. A reliable
  metric range needs a depth sensor, stereo calibration, or a validated depth model.
- The current GPS panel reads Android's last-known location. Continuous location updates,
  route calculation, online/offline map tiles, media/video import and map-file import are
  not part of this first native port yet.
- The compass uses Android's fused rotation-vector sensor and a circular low-pass filter.
  Heading still needs physical calibration and testing on the target phone.
- NCNN model weights are fetched in CI from the upstream ncnn-android-yolov8 project. Review
  upstream model/license terms before redistribution. The base YOLO weights are covered by
  Ultralytics licensing terms; check whether AGPL-3.0 or a commercial license applies to
  your distribution scenario.

## Local compass test

```bash
g++ -std=c++17 -Wall -Wextra -Werror tests/heading_filter_test.cpp -o heading_filter_test
./heading_filter_test
```

The Android APK itself should be built by the supplied GitHub Actions workflow, which
installs the matching Android SDK/NDK and downloads its native dependencies.
