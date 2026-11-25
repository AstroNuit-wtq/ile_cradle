cmake_minimum_required(VERSION 3.22.1)
project(diplomka_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ncnn_DIR)
    message(FATAL_ERROR "ncnn_DIR is required. The GitHub Actions workflow downloads the pinned NCNN Android package.")
endif()
find_package(ncnn REQUIRED CONFIG)
find_package(Threads REQUIRED)

include(FetchContent)
FetchContent_Declare(
    imgui
    GIT_REPOSITORY https://github.com/ocornut/imgui.git
    GIT_TAG v1.91.6
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(imgui)
if(NOT imgui_POPULATED)
    FetchContent_Populate(imgui)
endif()

set(IMGUI_DIR ${imgui_SOURCE_DIR})
add_library(imgui_android STATIC
    ${IMGUI_DIR}/imgui.cpp
    ${IMGUI_DIR}/imgui_draw.cpp
    ${IMGUI_DIR}/imgui_tables.cpp
    ${IMGUI_DIR}/imgui_widgets.cpp
    ${IMGUI_DIR}/backends/imgui_impl_android.cpp
    ${IMGUI_DIR}/backends/imgui_impl_opengl3.cpp
)
target_include_directories(imgui_android PUBLIC
    ${IMGUI_DIR}
    ${IMGUI_DIR}/backends
)
target_compile_definitions(imgui_android PUBLIC IMGUI_IMPL_OPENGL_ES3)

set(ANDROID_NDK_ROOT "${CMAKE_ANDROID_NDK}")
set(NATIVE_APP_GLUE ${ANDROID_NDK_ROOT}/sources/android/native_app_glue)

add_library(diplomka_native SHARED
    main.cpp
    camera_engine.cpp
    sensor_engine.cpp
    vision_engine.cpp
    ${NATIVE_APP_GLUE}/android_native_app_glue.c
)

target_include_directories(diplomka_native PRIVATE
    ${NATIVE_APP_GLUE}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(diplomka_native PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wno-unused-parameter -Wno-deprecated-declarations>
)

find_library(android-lib android)
find_library(log-lib log)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
find_library(camera-lib camera2ndk)
find_library(media-lib mediandk)

target_link_libraries(diplomka_native PRIVATE
    imgui_android
    ncnn
    Threads::Threads
    ${android-lib}
    ${log-lib}
    ${egl-lib}
    ${gles-lib}
    ${camera-lib}
    ${media-lib}
)

set_target_properties(diplomka_native PROPERTIES
    OUTPUT_NAME "diplomka_native"
)
