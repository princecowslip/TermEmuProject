// audio_decoder.h
// High-performance interface for audio decoding and Oboe stream management.

#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

/**
 * AudioStreamConfig
 *
 * Describes the desired Oboe/AAudio output stream configuration.
 */
struct AudioStreamConfig {
    int sample_rate   = 48000;
    int channel_count = 2;        // 1 = mono, 2 = stereo
    int bit_depth     = 16;       // PCM_16BIT
};

/**
 * Open a high-priority Oboe/AAudio output stream.
 *
 * Attempts AAUDIO_PERFORMANCE_MODE_LOW_LATENCY first; falls back to
 * AAUDIO_PERFORMANCE_MODE_NONE if the hardware does not support it.
 *
 * @param sample_rate   Desired sample rate (Hz).
 * @param channel_count Desired channel count (1 or 2).
 * @return 0 on success, negative Oboe error code on failure.
 */
int audio_open_stream(int sample_rate, int channel_count);

/**
 * Close and release the active Oboe audio stream.
 * Safe to call even if the stream was never opened.
 */
void audio_close_stream();

/**
 * Decode one frame of compressed audio using FFmpeg.
 *
 * The input is expected to be a raw encoded packet (e.g. AAC, MP3, or Opus)
 * as forwarded from a container application running inside the Linux PRoot env.
 *
 * @param data    Pointer to encoded audio packet bytes.
 * @param len     Byte length of the packet.
 * @return        Decoded 16-bit signed PCM samples (interleaved if stereo).
 *                Returns an empty vector on decode failure.
 */
std::vector<int16_t> audio_decode_frame(const uint8_t* data, size_t len);

/**
 * Flush the FFmpeg decoder and release codec resources.
 * Should be called when the audio stream ends or on session teardown.
 */
void audio_decoder_flush();
