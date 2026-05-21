// media_rescaler.cpp
// FFmpeg / libswscale implementation for scaling image data to cell grid dimensions.

#include "media_rescaler.h"

#include <android/log.h>
#include <vector>

extern "C" {
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "MediaRescaler"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── rgba_to_argb ────────────────────────────────────────────────────────────

void rgba_to_argb(const uint8_t* rgba, size_t pixels, uint32_t* argb) {
    for (size_t i = 0; i < pixels; ++i) {
        uint8_t r = rgba[i * 4 + 0];
        uint8_t g = rgba[i * 4 + 1];
        uint8_t b = rgba[i * 4 + 2];
        uint8_t a = rgba[i * 4 + 3];
        // Android Bitmap.Config.ARGB_8888: packed as 0xAARRGGBB
        argb[i] = (static_cast<uint32_t>(a) << 24)
                | (static_cast<uint32_t>(r) << 16)
                | (static_cast<uint32_t>(g) <<  8)
                |  static_cast<uint32_t>(b);
    }
}

// ── media_rescale ───────────────────────────────────────────────────────────

bool media_rescale(const uint8_t* src, int srcW, int srcH,
                   uint32_t* dst, int dstW, int dstH)
{
    if (!src || !dst || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
        LOGE("media_rescale: invalid arguments");
        return false;
    }

    // Choose scaling algorithm based on magnification direction
    int sws_flags = (dstW * dstH >= srcW * srcH)
                    ? SWS_BICUBIC   // upscaling
                    : SWS_BILINEAR; // downscaling

    SwsContext* sws = sws_getContext(
        srcW, srcH, AV_PIX_FMT_RGBA,
        dstW, dstH, AV_PIX_FMT_RGBA,
        sws_flags,
        nullptr, nullptr, nullptr
    );

    if (!sws) {
        LOGE("sws_getContext failed (%dx%d → %dx%d)", srcW, srcH, dstW, dstH);
        return false;
    }

    // Source slice setup
    const uint8_t* src_planes[4] = { src, nullptr, nullptr, nullptr };
    int            src_strides[4]= { srcW * 4, 0, 0, 0 };

    // Temporary RGBA output buffer
    std::vector<uint8_t> rgba_out(static_cast<size_t>(dstW * dstH * 4));
    uint8_t* dst_planes[4] = { rgba_out.data(), nullptr, nullptr, nullptr };
    int      dst_strides[4]= { dstW * 4, 0, 0, 0 };

    int rows = sws_scale(sws,
                         src_planes, src_strides, 0, srcH,
                         dst_planes, dst_strides);
    sws_freeContext(sws);

    if (rows != dstH) {
        LOGE("sws_scale produced %d rows, expected %d", rows, dstH);
        return false;
    }

    // Convert RGBA → Android ARGB_8888
    rgba_to_argb(rgba_out.data(), static_cast<size_t>(dstW * dstH), dst);

    LOGD("media_rescale: %dx%d → %dx%d OK", srcW, srcH, dstW, dstH);
    return true;
}
