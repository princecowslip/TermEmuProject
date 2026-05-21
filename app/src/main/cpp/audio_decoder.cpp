// audio_decoder.cpp
// Oboe/AAudio integration and FFmpeg-backed audio decoding pipeline.

#include "audio_decoder.h"

#include <android/log.h>
#include <mutex>
#include <memory>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
}

// Oboe C API (available via the Oboe AAR / prebuilt .so)
#include <oboe/Oboe.h>

#define LOG_TAG "AudioDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── FFmpeg / Oboe shared state ──────────────────────────────────────────────
// Declared here (above audio_open_stream) so that audio_open_stream can write
// them before the FFmpeg decoder reads them on first use.

namespace {
    static int g_sample_rate   = 48000;
    static int g_channel_count = 2;
} // namespace

// ── Oboe stream state ───────────────────────────────────────────────────────

static std::shared_ptr<oboe::AudioStream> g_stream;
static std::mutex                          g_stream_mutex;

int audio_open_stream(int sample_rate, int channel_count) {
    std::lock_guard<std::mutex> lock(g_stream_mutex);

    // Propagate parameters to the FFmpeg decoder so it initialises with correct values
    g_sample_rate   = sample_rate;
    g_channel_count = channel_count;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           .setPerformanceMode(oboe::PerformanceMode::LowLatency)
           .setSharingMode(oboe::SharingMode::Exclusive)
           .setFormat(oboe::AudioFormat::I16)
           .setSampleRate(sample_rate)
           .setChannelCount(channel_count)
           .setUsage(oboe::Usage::Media)
           .setContentType(oboe::ContentType::Music);

    oboe::Result result = builder.openStream(g_stream);
    if (result != oboe::Result::OK) {
        LOGE("Oboe openStream failed: %s", oboe::convertToText(result));
        // Retry with shared mode and default performance
        builder.setSharingMode(oboe::SharingMode::Shared)
               .setPerformanceMode(oboe::PerformanceMode::None);
        result = builder.openStream(g_stream);
        if (result != oboe::Result::OK) {
            LOGE("Oboe fallback openStream also failed: %s", oboe::convertToText(result));
            return static_cast<int>(result);
        }
    }

    g_stream->requestStart();
    LOGI("Oboe stream opened: sr=%d ch=%d", sample_rate, channel_count);
    return 0;
}

void audio_close_stream() {
    std::lock_guard<std::mutex> lock(g_stream_mutex);
    if (g_stream) {
        g_stream->requestStop();
        g_stream->close();
        g_stream.reset();
        LOGI("Oboe stream closed.");
    }
}

// ── FFmpeg decoder state ────────────────────────────────────────────────────

namespace {  // reopen same anonymous namespace for FfmpegDecoder + g_decoder
    struct FfmpegDecoder {
        AVCodecContext* ctx    = nullptr;
        AVPacket*       pkt    = nullptr;
        AVFrame*        frame  = nullptr;
        SwrContext*     swr    = nullptr;
        bool            inited = false;

        ~FfmpegDecoder() { release(); }

        bool init(int sample_rate, int channels) {
            // Auto-detect codec (default: PCM s16le for raw streams from container)
            const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_PCM_S16LE);
            if (!codec) { LOGE("avcodec_find_decoder failed"); return false; }

            ctx = avcodec_alloc_context3(codec);
            if (!ctx) return false;

            ctx->sample_rate = sample_rate;
            ctx->ch_layout   = (channels == 1)
                                ? AV_CHANNEL_LAYOUT_MONO
                                : AV_CHANNEL_LAYOUT_STEREO;
            ctx->sample_fmt  = AV_SAMPLE_FMT_S16;

            if (avcodec_open2(ctx, codec, nullptr) < 0) {
                LOGE("avcodec_open2 failed"); return false;
            }

            pkt   = av_packet_alloc();
            frame = av_frame_alloc();
            if (!pkt || !frame) return false;

            // SwrContext for sample format conversion if needed
            swr_alloc_set_opts2(&swr,
                &ctx->ch_layout, AV_SAMPLE_FMT_S16, sample_rate,
                &ctx->ch_layout, ctx->sample_fmt,    sample_rate,
                0, nullptr);
            swr_init(swr);

            inited = true;
            return true;
        }

        void release() {
            if (swr)   { swr_free(&swr);              swr   = nullptr; }
            if (frame) { av_frame_free(&frame);        frame = nullptr; }
            if (pkt)   { av_packet_free(&pkt);         pkt   = nullptr; }
            if (ctx)   { avcodec_free_context(&ctx);   ctx   = nullptr; }
            inited = false;
        }
    };

    static FfmpegDecoder g_decoder;
    static std::mutex    g_decoder_mutex;
} // anonymous namespace

// ── audio_decode_frame ──────────────────────────────────────────────────────

std::vector<int16_t> audio_decode_frame(const uint8_t* data, size_t len) {
    std::lock_guard<std::mutex> lock(g_decoder_mutex);

    if (!g_decoder.inited) {
        if (!g_decoder.init(g_sample_rate, g_channel_count)) {
            LOGE("Decoder init failed");
            return {};
        }
    }

    std::vector<int16_t> pcm_out;

    // For raw PCM streams, interpret bytes directly as int16_t samples
    size_t samples = len / sizeof(int16_t);
    pcm_out.resize(samples);
    std::memcpy(pcm_out.data(), data, samples * sizeof(int16_t));

    // Write to Oboe stream if open
    {
        std::lock_guard<std::mutex> sl(g_stream_mutex);
        if (g_stream && !pcm_out.empty()) {
            int32_t frames = static_cast<int32_t>(pcm_out.size() / g_channel_count);
            g_stream->write(pcm_out.data(), frames,
                            /* timeout ns */ 1'000'000LL);
        }
    }

    return pcm_out;
}

void audio_decoder_flush() {
    std::lock_guard<std::mutex> lock(g_decoder_mutex);
    g_decoder.release();
}
