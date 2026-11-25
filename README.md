# Diplomka Native (C++)

Android arm64-v8a application for real-time camera preview, NCNN YOLOv8 detection/pose, compass heading, and proximity STOP signal.

## Stack

- **C++20** / NDK r30 / NativeActivity shell
- **Camera2 NDK** (`camera2ndk` + `AImageReader` YUV420)
- **NCNN** YOLOv8n + YOLOv8n-pose (models downloaded in CI)
- **Dear ImGui** for drawing only; **buttons use manual hit-test** (reliable touch)
- **Rotation-vector** sensor + circular heading filter
- Minimal **Java** `MainActivity` only for runtime CAMERA permission

## Build (GitHub Actions)

Workflow: `.github/workflows/build-astro-cpp.yml`

1. Actions → **Build Diplomka Native C++ APK** → Run workflow
2. Download artifact `Diplomka-Native-Cpp-APK`

Local requirements: JDK 17, Android SDK 35, NDK 30.0.16248370, CMake 3.22.1, Gradle 8.9, NCNN android arm64 package.

```bash
gradle -PncnnDir=/path/to/arm64-v8a/lib/cmake/ncnn :app:assembleRelease
```

## First run on device

1. Install APK
2. **Allow Camera** when prompted (or Settings → Apps → Diplomka → Permissions)
3. HUD shows `t=N` touch counter — should increase on every tap
4. HUD shows `f=N` frame counter — should increase when camera streams
5. Use **Restart Camera** if frames stay at 0 after granting permission

## Layout

```
app/src/main/
  java/.../MainActivity.java   # permission request only
  cpp/
    main.cpp                   # loop, UI hit-test, EGL/ImGui draw
    camera_engine.*            # Camera2 NDK
    vision_engine.*            # NCNN detect + pose
    sensor_engine.*            # heading
    heading_filter.h
    CMakeLists.txt
  AndroidManifest.xml
  res/                         # adaptive icon
```

## Raspberry Pi 4B

This APK targets **Android**. A Linux/V4L2 port of `vision_engine` + SDL/GLFW UI can share the same C++ vision/sensor code; it is not included in this Android package.

## License notes

Third-party: NCNN, ImGui, Android NDK headers — see `THIRD_PARTY_NOTICES.md`.
