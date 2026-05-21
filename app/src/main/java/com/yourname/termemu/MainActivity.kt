package com.yourname.termemu

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yourname.termemu.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException

private const val TAG = "MainActivity"

/**
 * MainActivity
 *
 * System entry point for TermEmu. Responsibilities:
 *  - Enable Android 16 edge-to-edge rendering and handle window insets.
 *  - Keep the screen on while a terminal session is active.
 *  - Dispatch predictive back gestures to the terminal session lifecycle.
 *  - Extract bundled assets (proot, scripts) to internal storage on first run.
 *  - Coordinate TerminalView, MediaOverlayView, and TerminalSession startup.
 *  - Start/stop TerminalForegroundService to keep the session alive when backgrounded.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var terminalSession: TerminalSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets  = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, imeInsets.bottom)
            )
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                terminalSession.sendInput(byteArrayOf(0x03)) // Ctrl+C
            }
        })

        terminalSession = TerminalSession(
            dataCallback       = { data -> binding.terminalView.appendOutput(data) },
            sessionEndCallback = { exitCode -> onSessionEnded(exitCode) }
        )

        binding.terminalView.attachSession(terminalSession)
        binding.mediaOverlayView.attachTerminalView(binding.terminalView)

        extractAssetsAndStart()
    }

    // ── Asset extraction & session bootstrap ──────────────────────────────

    /**
     * Copy all bundled assets to [filesDir]/assets/ so that shell scripts can
     * reference them via real POSIX paths. Assets inside the APK are not
     * accessible by path — they must be extracted first.
     * Only missing files are copied; proot + scripts are chmod'd executable.
     */
    private fun extractAssetsAndStart() {
        val assetsDir = File(filesDir, "assets").also { it.mkdirs() }

        listOf("start_debian.sh", "setup_debian.sh", "proot", "debian-rootfs.tar.xz")
            .forEach { name ->
                val dest = File(assetsDir, name)
                if (!dest.exists()) {
                    try {
                        assets.open(name).use { src -> dest.outputStream().use(src::copyTo) }
                        Log.i(TAG, "Extracted: $name")
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not extract '$name': ${e.message}")
                    }
                }
            }

        File(assetsDir, "proot").setExecutable(true, false)
        File(assetsDir, "start_debian.sh").setExecutable(true, false)
        File(assetsDir, "setup_debian.sh").setExecutable(true, false)

        // Run setup script (idempotent — exits immediately after first-run stamp)
        val internalDir = File(filesDir, ".termemu").also { it.mkdirs() }.absolutePath
        val setupScript = File(assetsDir, "setup_debian.sh").absolutePath

        terminalSession.start(
            command    = setupScript,
            args       = arrayOf(setupScript, internalDir),
            onComplete = { startDebianSession() }
        )
    }

    private fun startDebianSession() {
        val assetsDir   = File(filesDir, "assets")
        val startScript = File(assetsDir, "start_debian.sh").absolutePath
        val internalDir = File(filesDir, ".termemu").absolutePath

        terminalSession.start(
            command    = startScript,
            args       = arrayOf(startScript, internalDir),
            onComplete = null
        )

        startForegroundService(Intent(this, TerminalForegroundService::class.java))
    }

    private fun onSessionEnded(exitCode: Int) {
        runOnUiThread {
            binding.terminalView.appendOutput(
                "\r\n[Process exited with code $exitCode]\r\n".toByteArray()
            )
            stopService(Intent(this, TerminalForegroundService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.terminalView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalSession.destroy()
        stopService(Intent(this, TerminalForegroundService::class.java))
    }
}
