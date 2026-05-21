cmake_minimum_required(VERSION 4.1.2)
project(termemu CXX)

# ── C++ standard & security flags ─────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(
    -Wall -Wextra -Wpedantic
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -frtti
    -fexceptions
)

# ── Include directories ────────────────────────────────────────────────────
# Adjust FFMPEG_ROOT and OBOE_ROOT paths to match your prebuilt locations.
set(FFMPEG_ROOT "${CMAKE_SOURCE_DIR}/../../../third_party/ffmpeg/${ANDROID_ABI}")
set(OBOE_ROOT   "${CMAKE_SOURCE_DIR}/../../../third_party/oboe")

include_directories(
    ${CMAKE_SOURCE_DIR}
    ${FFMPEG_ROOT}/include
    ${OBOE_ROOT}/include
)

# ── Source files ───────────────────────────────────────────────────────────
add_library(termemu SHARED
    jni_bridge.cpp
    pty_manager.cpp
    kitty_parser.cpp
    media_rescaler.cpp
    audio_decoder.cpp
)

# ── Pre-built FFmpeg static libraries ─────────────────────────────────────
foreach(LIB avcodec avformat avutil swscale swresample)
    add_library(${LIB} STATIC IMPORTED)
    set_target_properties(${LIB} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/lib${LIB}.a"
    )
endforeach()

# ── Pre-built Oboe shared library ──────────────────────────────────────────
add_library(oboe SHARED IMPORTED)
set_target_properties(oboe PROPERTIES
    IMPORTED_LOCATION "${OBOE_ROOT}/libs/${ANDROID_ABI}/liboboe.so"
)

# ── System NDK libraries ───────────────────────────────────────────────────
find_library(log-lib   log)
find_library(android-lib android)
find_library(aaudio-lib AAudio)

# ── Link everything together ───────────────────────────────────────────────
target_link_libraries(termemu
    # FFmpeg
    avcodec avformat avutil swscale swresample
    # Audio
    oboe
    ${aaudio-lib}
    # Android / NDK
    ${log-lib}
    ${android-lib}
)
