# Diplomka Native — Android C++

Полностью нативное Android-приложение (NDK / NativeActivity + Dear ImGui + NCNN).

**Нет Python. Нет Kotlin/Java в исходниках приложения.**

## Содержимое репозитория

| Файл | Назначение |
|------|------------|
| `.github/workflows/build-astro-cpp.yml` | GitHub Actions: сборка APK |
| `Diplomka_Native_CPP_Source.zip` | Исходники C++ (единственный вход для сборки) |
| `README.md` | Этот файл |

Сборка **не** коммитит APK, модели и intermediate-файлы в репозиторий. Всё собирается в `$RUNNER_TEMP`, APK публикуется в **Actions Artifacts**.

## Как собрать APK

1. **Actions** → **Build Diplomka Native C++ APK** → **Run workflow**
2. Или push изменений в `Diplomka_Native_CPP_Source.zip` / workflow
3. Скачать артефакт `Diplomka-Native-Cpp-APK` → `Diplomka-Native-C++-arm64-v8a.apk`

CI: JDK 17, Android SDK 35, NDK r30, CMake 3.22.1, Gradle 8.9, NCNN (SHA-256), YOLOv8n + YOLOv8n-pose.

## Что уже в исходниках (см. ZIP)

- Camera2 NDK + YUV→RGBA, worker thread
- NCNN YOLOv8n (объекты) + YOLOv8n-pose (скелет)
- NMS + сглаживание между кадрами
- Компас (rotation vector + circular filter)
- GPS last-known
- ImGui UI: 6 кнопок, стрелка подписей, скрываемый журнал

## Что ещё нужно (приоритеты)

См. `docs/TASKS_AND_STATUS.md` внутри ZIP: камера multi-lens/torch, сегментация, depth, полноценный GPS/карты, offline, Vulkan, тесты на устройстве.

## Важно

Подготовленный код и CI **не означают**, что APK уже проверен на телефоне. Каждую функцию нужно отдельно проверить на реальном устройстве.
