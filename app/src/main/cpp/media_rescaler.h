// media_rescaler.h
// Interface for scaling raw image data to match terminal cell grid dimensions.

#pragma once

#include <cstdint>
#include <cstddef>

/**
 * RescaleConfig
 *
 * Describes a media rescaling operation.
 * Pixel format is assumed to be packed RGBA (4 bytes per pixel) for both
 * source and destination.
 */
struct RescaleConfig {
    int src_width   = 0;
    int src_height  = 0;
    int dst_width   = 0;
    int dst_height  = 0;
};

/**
 * Rescale raw RGBA pixel data from (srcW × srcH) to (dstW × dstH).
 *
 * Uses FFmpeg libswscale (SWS_BILINEAR) for high-quality downscaling and
 * SWS_BICUBIC for upscaling. The destination buffer must be pre-allocated
 * with at least (dstW * dstH) uint32_t elements.
 *
 * @param src    Source RGBA pixel buffer.
 * @param srcW   Source width in pixels.
 * @param srcH   Source height in pixels.
 * @param dst    Pre-allocated destination ARGB_8888 buffer (ARGB for Android Bitmap compat).
 * @param dstW   Destination width in pixels.
 * @param dstH   Destination height in pixels.
 * @return       true on success, false if libswscale context allocation fails.
 */
bool media_rescale(const uint8_t* src, int srcW, int srcH,
                   uint32_t* dst, int dstW, int dstH);

/**
 * Convert a decoded RGBA byte buffer to Android-compatible ARGB_8888 layout.
 * On most platforms RGBA→ARGB is a simple channel swap (R↔A, G→G, B→B).
 *
 * @param rgba   Input RGBA buffer (4 bytes per pixel).
 * @param pixels Number of pixels.
 * @param argb   Output ARGB_8888 buffer (must be pre-allocated, same size).
 */
void rgba_to_argb(const uint8_t* rgba, size_t pixels, uint32_t* argb);
