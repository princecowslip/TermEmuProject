package com.yourname.termemu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "AudioRenderer"

/**
 * AudioRenderer
 *
 * Low-latency audio playback layer.
 *
 * - Opens an Oboe/AAudio high-priority stream via [JniBridge.openAudioStream].
 * - Accepts decoded 16-bit PCM frames from the native audio decoder.
 * - Routes PCM data to an [AudioTrack] in streaming mode on a dedicated
 *   coroutine dispatcher to avoid blocking the UI or PTY reader threads.
 * - Falls back gracefully if Oboe fails to open the stream.
 */
class AudioRenderer {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioTrack: AudioTrack? = null
    private var sampleRate   = 48_000
    private var channelCount = 2
    private var isStarted    = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Initialise the audio pipeline.
     * @param sampleRate   Target sample rate (Hz). Defaults to 48 000.
     * @param channelCount Number of channels (1 = mono, 2 = stereo).
     */
    fun start(sampleRate: Int = 48_000, channelCount: Int = 2) {
        this.sampleRate   = sampleRate
        this.channelCount = channelCount

        // Open the native Oboe stream first (high-priority AAudio path)
        val result = JniBridge.openAudioStream(sampleRate, channelCount)
        if (result != 0) {
            Log.w(TAG, "Oboe stream open failed ($result); falling back to AudioTrack")
        }

        // Build an AudioTrack for the Java-side fallback / secondary path
        val channelMask = if (channelCount == 1)
            AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()

        audioTrack?.play()
        isStarted = true
        Log.i(TAG, "AudioRenderer started: ${sampleRate}Hz, ch=$channelCount")
    }

    /**
     * Queue decoded PCM audio data received from the native layer.
     * Called from the PTY reader or native callback thread.
     */
    fun onAudioData(pcm: ShortArray) {
        if (!isStarted) return
        scope.launch {
            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    /**
     * Convenience overload accepting raw bytes (interleaved signed 16-bit LE).
     */
    fun onAudioData(rawBytes: ByteArray) {
        val pcm = ShortArray(rawBytes.size / 2) { i ->
            ((rawBytes[i * 2 + 1].toInt() shl 8) or (rawBytes[i * 2].toInt() and 0xFF)).toShort()
        }
        onAudioData(pcm)
    }

    /** Stop playback and release all audio resources. */
    fun stop() {
        isStarted = false
        scope.cancel()
        audioTrack?.runCatching {
            stop()
            release()
        }
        audioTrack = null
        JniBridge.closeAudioStream()
        JniBridge.flushAudioDecoder()
        Log.i(TAG, "AudioRenderer stopped.")
    }
}
