package com.yourname.termemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "MediaOverlayView"

/** Immutable frame descriptor stored in the pending-frames list. */
private data class MediaFrame(val col: Int, val row: Int, val bitmap: Bitmap)

/**
 * MediaOverlayView
 *
 * Hardware-accelerated SurfaceView overlay for rendering inline Kitty graphics
 * protocol media frames.
 *
 * - Tracks cell-grid changes reported by [TerminalView] to keep media frames
 *   pixel-perfectly aligned with character cells.
 * - Renders rescaled [Bitmap] frames onto the SurfaceHolder canvas from a
 *   dedicated coroutine dispatcher, avoiding main-thread jank.
 * - Uses [CopyOnWriteArrayList] for [pendingFrames] so that the render coroutine
 *   can safely iterate a snapshot while the main thread adds/clears frames.
 */
class MediaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val renderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var terminalView: TerminalView? = null

    // Cached cell dimensions — written on main thread, read on render coroutine.
    // Declared @Volatile so the render coroutine always sees the latest values.
    @Volatile private var cellW = 0f
    @Volatile private var cellH = 0f

    // CopyOnWriteArrayList: writes from the main thread are thread-safe;
    // the render coroutine iterates a stable snapshot without locking.
    private val pendingFrames = CopyOnWriteArrayList<MediaFrame>()

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(R.string.media_overlay_desc)
    }

    /** Wire up to a TerminalView to receive cell dimension updates. */
    fun attachTerminalView(tv: TerminalView) {
        terminalView = tv
        cellW = tv.cellWidth
        cellH = tv.cellHeight
        tv.onCellDimensionsChanged = { w, h ->
            cellW = w
            cellH = h
        }
    }

    /**
     * Schedule a bitmap to be painted at character grid position (col, row).
     * Safe to call from any thread.
     */
    fun renderFrame(col: Int, row: Int, bitmap: Bitmap) {
        pendingFrames.add(MediaFrame(col, row, bitmap))
        drawPendingFrames()
    }

    /** Clear all media overlays from the surface. Safe to call from any thread. */
    fun clearFrames() {
        pendingFrames.clear()
        renderScope.launch {
            val canvas = holder.lockCanvas() ?: return@launch
            try {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    // ── Internal rendering ─────────────────────────────────────────────────

    private fun drawPendingFrames() {
        renderScope.launch {
            val canvas: Canvas = holder.lockCanvas() ?: return@launch
            try {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                // Iterate a CopyOnWrite snapshot — safe without further locking
                for (frame in pendingFrames) {
                    val dest = cellRect(frame.col, frame.row, frame.bitmap)
                    canvas.drawBitmap(frame.bitmap, null, dest, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "drawPendingFrames error: ${e.message}")
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun cellRect(col: Int, row: Int, bmp: Bitmap): Rect {
        val left   = (col * cellW).toInt()
        val top    = (row * cellH).toInt()
        val right  = left + bmp.width
        val bottom = top  + bmp.height
        return Rect(left, top, right, bottom)
    }

    // ── SurfaceHolder.Callback ─────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        drawPendingFrames()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        drawPendingFrames()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderScope.cancel()
    }
}
