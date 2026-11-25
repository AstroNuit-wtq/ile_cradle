# Native architecture

```text
Android NativeActivity
  └── android_main (C++)
      ├── Dear ImGui + OpenGL ES 3 UI
      │   ├── live camera preview
      │   ├── detection and pose overlays
      │   ├── two-row expanded action labels
      │   └── hideable event log
      ├── CameraEngine
      │   └── Android NDK Camera2 → AImageReader YUV_420_888 → RGBA
      ├── VisionEngine (dedicated bounded worker thread)
      │   ├── latest-frame queue (one pending frame; old work is dropped)
      │   ├── NCNN YOLOv8n object detector (COCO)
      │   ├── NCNN YOLOv8n-pose keypoints (17 landmarks)
      │   ├── DFL decode + class-aware NMS
      │   └── short temporal box smoothing
      ├── SensorEngine
      │   └── Rotation Vector → CircularHeadingFilter
      └── Android framework JNI calls from C++
          ├── camera/location runtime permissions
          └── last-known GPS fix
```

## Compass filtering

The azimuth is extracted from the Android rotation-vector quaternion using the device top-edge
(+Y) orientation, then treated as a circular variable. The update uses the shortest signed
angular error, a time-constant low-pass filter and a one-frame jump gate. This avoids averaging
359° and 1° as if they were far apart. The app still needs device-specific calibration and
orientation checks on physical phones.

Neural inference runs on a dedicated worker thread. The camera callback only converts and
publishes the latest frame; a single-slot queue replaces stale pending work instead of growing
without bound. Pose inference runs less frequently than object detection to reduce CPU load.

## Object detection

YOLOv8n uses 80 COCO object classes. Candidate boxes are decoded from DFL distributions,
filtered by confidence, mapped back from the letterboxed 640×640 tensor and filtered with
class-aware NMS. A short IoU-based temporal blend damps rectangle jitter.

YOLOv8n-pose is configured as a separate model and is scheduled less frequently than the
main detector to reduce mobile CPU cost. Its 17 keypoints are drawn as a simple human skeleton.

## Statue recognition limitation

The downloaded COCO model does not contain a statue/monument label. A person detection that
remains still is not sufficient evidence of a statue. Do not relabel still people as statues.
A dedicated model trained with statue/monument/mannequin/person examples is needed.

## Build isolation

The workflow checks out the two uploaded files, unpacks source under the GitHub runner temp
folder, downloads native dependencies/models to temp, builds there and uploads the APK as a
workflow artifact. It does not generate or commit files into the repository checkout.
