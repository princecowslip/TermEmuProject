package com.yourname.termemu

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

private const val TAG             = "TerminalSession"
private const val READ_BUFFER_SIZE = 8192

/**
 * TerminalSession
 *
 * Lifecycle supervisor for a single PTY-backed terminal session.
 *
 * - Calls the native [JniBridge] to create a POSIX pseudoterminal (forkpty).
 * - Spawns a background reader thread that drains the PTY master fd
 *   and delivers data to the caller via [dataCallback].
 * - Exposes [sendInput] to write bytes from the UI thread into the PTY.
 * - Notifies [sessionEndCallback] when the child process exits.
 * - Accepts an optional [onComplete] callback so callers can chain sessions
 *   (e.g. run setup_debian.sh then start_debian.sh).
 */
class TerminalSession(
    private val dataCallback:       (ByteArray) -> Unit,
    private val sessionEndCallback: (exitCode: Int) -> Unit
) {
    private var ptyFd:    Int = -1
    @Volatile private var childPid: Int = -1

    private var masterFd:     FileDescriptor?  = null
    private var inputStream:  FileInputStream?  = null
    private var outputStream: FileOutputStream? = null

    private var readerThread: Thread? = null

    @Volatile private var running = false

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start a shell/script session.
     *
     * @param command    Path to the executable.
     * @param args       Command-line arguments.
     * @param onComplete Optional callback invoked on the reader thread after the
     *                   process exits with code 0.  Use this to chain a follow-up
     *                   session (e.g. setup → main shell) without blocking the UI.
     */
    fun start(command: String, args: Array<String>, onComplete: (() -> Unit)? = null) {
        // Tear down any previous session before starting a new one
        if (running) destroy()

        val result = JniBridge.createPty(command, args)
        if (result.masterFd < 0 || result.pid < 0) {
            Log.e(TAG, "Failed to create PTY (fd=${result.masterFd}, pid=${result.pid})")
            sessionEndCallback(-1)
            return
        }

        ptyFd    = result.masterFd
        childPid = result.pid
        masterFd = JniBridge.fdToFileDescriptor(ptyFd)

        inputStream  = FileInputStream(masterFd)
        outputStream = FileOutputStream(masterFd)

        running = true
        readerThread = Thread({ readLoop(onComplete) }, "TerminalReader").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "Session started: pid=$childPid, fd=$ptyFd, cmd=$command")
    }

    /** Write raw bytes to the PTY (e.g. keyboard input). */
    fun sendInput(data: ByteArray) {
        try {
            outputStream?.write(data)
        } catch (e: IOException) {
            Log.w(TAG, "sendInput error: ${e.message}")
        }
    }

    /** Notify the kernel of a terminal window resize. */
    fun updateWindowSize(cols: Int, rows: Int) {
        if (ptyFd >= 0) JniBridge.resizePty(ptyFd, cols, rows)
    }

    /** Terminate the session and release all resources. */
    fun destroy() {
        running = false
        readerThread?.interrupt()

        val pid = childPid
        childPid = -1  // clear before waitForProcess to avoid double-wait in readLoop finally

        try {
            if (pid > 0) JniBridge.killProcess(pid)
        } catch (e: Exception) {
            Log.w(TAG, "destroy kill: ${e.message}")
        }

        inputStream?.runCatching  { close() }
        outputStream?.runCatching { close() }

        if (ptyFd >= 0) {
            JniBridge.closeFd(ptyFd)
            ptyFd = -1
        }

        masterFd     = null
        inputStream  = null
        outputStream = null
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun readLoop(onComplete: (() -> Unit)?) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var exitCode = -1
        try {
            while (running) {
                val n = inputStream?.read(buffer) ?: break
                if (n < 0) break
                if (n > 0) dataCallback(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "readLoop I/O error: ${e.message}")
        } finally {
            // Snapshot pid before it can be cleared by destroy()
            val pid = childPid
            exitCode = if (pid > 0) JniBridge.waitForProcess(pid) else -1
            Log.i(TAG, "Session ended: pid=$pid, exit=$exitCode")
            sessionEndCallback(exitCode)
            if (exitCode == 0) onComplete?.invoke()
        }
    }
}
