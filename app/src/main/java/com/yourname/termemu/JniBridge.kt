package com.yourname.termemu

import java.io.FileDescriptor

/**
 * JniBridge
 *
 * Kotlin-side declaration layer for all native C++ methods.
 * Each `external` function maps to a corresponding implementation in jni_bridge.cpp.
 *
 * The shared library "termemu" is loaded once at class initialisation.
 */
object JniBridge {

    init {
        System.loadLibrary("termemu")
    }

    // ── PTY / process management ────────────────────────────────────────

    /**
     * Fork a child process attached to a new pseudoterminal.
     * @param command Absolute path to the executable.
     * @param args    Null-terminated argument array (command is args[0]).
     * @return [PtyResult] holding the master fd and child PID, or negative values on failure.
     */
    external fun createPty(command: String, args: Array<String>): PtyResult

    /**
     * Update the terminal window size for a given PTY master fd.
     * Internally calls ioctl(TIOCSWINSZ).
     */
    external fun resizePty(masterFd: Int, cols: Int, rows: Int)

    /**
     * Send SIGKILL to the given process.
     */
    external fun killProcess(pid: Int)

    /**
     * Wait for a process to exit and return its exit code.
     * Wraps waitpid(WNOHANG) in a polling loop.
     */
    external fun waitForProcess(pid: Int): Int

    /**
     * Close a raw file descriptor.
     */
    external fun closeFd(fd: Int)

    /**
     * Wrap a raw int fd into a Java [FileDescriptor] via reflection on the native side.
     */
    external fun fdToFileDescriptor(fd: Int): FileDescriptor

    // ── Media / Kitty graphics ──────────────────────────────────────────

    /**
     * Decode and rescale an intercepted Kitty graphics payload.
     * @param rawPixels  Raw image bytes (RGBA or compressed).
     * @param srcW       Source image width in pixels.
     * @param srcH       Source image height in pixels.
     * @param dstW       Target width in pixels (from cell grid calculation).
     * @param dstH       Target height in pixels.
     * @return           Rescaled ARGB_8888 pixel array for direct Bitmap creation.
     */
    external fun rescaleImage(
        rawPixels: ByteArray,
        srcW: Int, srcH: Int,
        dstW: Int, dstH: Int
    ): IntArray

    // ── Audio ───────────────────────────────────────────────────────────

    /**
     * Decode an audio frame from the container's PCM stream.
     * @param encoded Compressed audio bytes from the container.
     * @return        Decoded 16-bit PCM samples as a [ShortArray].
     */
    external fun decodeAudioFrame(encoded: ByteArray): ShortArray

    /**
     * Open the Oboe/AAudio low-latency output stream.
     * @param sampleRate    Preferred sample rate (e.g. 48000).
     * @param channelCount  Number of channels (1 = mono, 2 = stereo).
     * @return              0 on success, negative Oboe error code on failure.
     */
    external fun openAudioStream(sampleRate: Int, channelCount: Int): Int

    /**
     * Close and release the Oboe audio stream.
     */
    external fun closeAudioStream()

    /**
     * Flush the FFmpeg decoder and release codec resources.
     * Must be called on session teardown to avoid leaking AVCodecContext and related objects.
     */
    external fun flushAudioDecoder()

    // ── Data class returned by createPty ────────────────────────────────

    data class PtyResult(val masterFd: Int, val pid: Int)
}
