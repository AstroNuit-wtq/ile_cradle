# Diplomka Native C++ 2.2

Android arm64-v8a + Raspberry Pi 4B native C++ application.

## Android

- NativeActivity, Camera2 NDK, Dear ImGui, NCNN YOLOv8n / YOLOv8n-pose
- Version `2.2.0-native-audit` (versionCode 4)
- Camera session starts repeating request only in `onSessionReady`
- Touch routed through ImGui Android backend + action handlers

### Build (GitHub Actions)

1. Place `Diplomka_Native_CPP_Source.zip` in the repository root **or** keep sources in `app/`
2. Run workflow **Build Diplomka Native C++ APK**
3. Download artifact `Diplomka-Native-Cpp-APK`

### Device notes

- Grant **CAMERA** in system settings before first run (`hasCode=false` does not show a runtime dialog)
- HUD shows frame counter and touch counter for diagnostics

## Raspberry Pi 4B

See `raspberry_pi/README.md`.

```bash
BUILD_JOBS=2 bash raspberry_pi/build_pi.sh
bash raspberry_pi/run_pi.sh
```

Requires Raspberry Pi OS 64-bit, CSI camera, SDL2, OpenGL ES.
