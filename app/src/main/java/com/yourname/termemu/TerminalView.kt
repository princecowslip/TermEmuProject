package com.yourname.termemu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.max
import kotlin.math.min

/**
 * TerminalView
 *
 * Front-end rendering and input layer for the terminal emulator.
 *
 * - Calculates character cell dimensions from a monospace font using FontMetrics.
 * - Renders the terminal grid onto a Canvas using a transformation Matrix.
 * - Handles touch events: tap-to-focus, finger-drag scroll via GestureDetector.
 * - Exposes an InputConnection to the system IME for text input.
 * - Notifies attached listeners when cell dimensions change (for MediaOverlayView).
 * - Caps the in-memory line buffer at [MAX_LINES] to prevent unbounded growth.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Constants ───────────────────────────────────────────────────────────

    companion object {
        private const val MAX_LINES        = 5_000   // rolling line cap
        private const val TRIM_TO_LINES    = 4_000   // trim back to this on overflow
        private const val SCROLL_FLING_FACTOR = 0.25f
    }

    // ── Font & cell metrics ─────────────────────────────────────────────────

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 40f
        color    = Color.parseColor("#FFEFEFEF")
    }

    var cellWidth:  Float = 0f; private set
    var cellHeight: Float = 0f; private set
    var cellAscent: Float = 0f; private set

    @Suppress("unused")
    private val transformMatrix = Matrix()  // retained for future use (zoom/pan)

    // ── Terminal buffer ─────────────────────────────────────────────────────
    // Access is synchronised on bufferLock; always acquire before reading or
    // writing outputLines from any thread.

    private val bufferLock  = Any()
    private val outputLines = ArrayDeque<String>(MAX_LINES + 1)
    private var scrollOffset = 0f

    // ── Session reference ───────────────────────────────────────────────────

    private var session: TerminalSession? = null

    /** Callback invoked when cell dimensions are recalculated (e.g. on resize). */
    var onCellDimensionsChanged: ((cellW: Float, cellH: Float) -> Unit)? = null

    // ── Gesture detector for smooth touch-drag scroll ───────────────────────

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                scrollOffset -= distanceY
                clampScroll()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                scrollOffset += velocityY * SCROLL_FLING_FACTOR
                clampScroll()
                invalidate()
                return true
            }

            override fun onDown(e: MotionEvent) = true
        }
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────

    init {
        isFocusable          = true
        isFocusableInTouchMode = true
        contentDescription   = context.getString(R.string.terminal_view_desc)
        recalculateCellDimensions()
    }

    fun attachSession(session: TerminalSession) {
        this.session = session
    }

    /**
     * Append raw bytes from the PTY reader thread to the output buffer.
     * Thread-safe: synchronises on [bufferLock] before mutating [outputLines].
     */
    fun appendOutput(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        synchronized(bufferLock) {
            // Split on newlines and merge the first chunk into the last existing line
            // so that partial lines (mid-sequence updates) are handled correctly.
            val parts = text.split('\n')
            if (outputLines.isEmpty()) {
                parts.forEach { outputLines.addLast(it) }
            } else {
                outputLines[outputLines.size - 1] += parts[0]
                for (i in 1 until parts.size) outputLines.addLast(parts[i])
            }
            // Trim to avoid unbounded growth
            if (outputLines.size > MAX_LINES) {
                repeat(outputLines.size - TRIM_TO_LINES) { outputLines.removeFirst() }
            }
        }
        postInvalidate()
    }

    // ── Measurement & drawing ──────────────────────────────────────────────

    private fun recalculateCellDimensions() {
        val fm    = textPaint.fontMetrics
        cellHeight = fm.descent - fm.ascent
        cellAscent = -fm.ascent
        cellWidth  = textPaint.measureText("M")
        onCellDimensionsChanged?.invoke(cellWidth, cellHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateCellDimensions()
        session?.updateWindowSize(
            cols = (w / cellWidth).toInt(),
            rows = (h / cellHeight).toInt()
        )
        clampScroll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#FF1C1C1C"))

        val lines: List<String>
        synchronized(bufferLock) { lines = outputLines.toList() }

        val visibleRows = (height / cellHeight).toInt() + 1

        // scrollOffset <= 0: 0 = pinned to bottom (most recent), negative = scrolled up.
        // Convert pixel offset to whole-row and sub-row components so that:
        //   (a) startLine pulls the correct lines from the buffer, and
        //   (b) pixelOffset provides smooth sub-row scrolling.
        val scrolledRows = if (cellHeight > 0f) (-scrollOffset / cellHeight).toInt() else 0
        val pixelOffset  = scrollOffset + scrolledRows * cellHeight   // fractional row remainder
        val startLine    = max(0, lines.size - visibleRows - scrolledRows)

        lines.drop(startLine).forEachIndexed { rowIndex, line ->
            val y       = rowIndex * cellHeight + cellAscent + pixelOffset
            val screenY = y - cellAscent          // top of cell in view coordinates
            if (screenY + cellHeight >= 0 && screenY <= height) {
                canvas.drawText(line, 0f, y, textPaint)
            }
        }
    }

    // ── Touch & IME ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Tap-to-focus / keyboard show on ACTION_DOWN is handled by GestureDetector.onDown
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            showSoftKeyboard()
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType   = EditorInfo.TYPE_NULL
        outAttrs.imeOptions  = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this, session)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = event.toTerminalBytes() ?: return super.onKeyDown(keyCode, event)
        session?.sendInput(bytes)
        return true
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun clampScroll() {
        // scrollOffset <= 0 (0 = bottom / most recent; negative = scrolled up)
        val lineCount   = synchronized(bufferLock) { outputLines.size }
        val maxScroll   = 0f
        val minScroll   = if (cellHeight > 0f) {
            -(max(0, lineCount) * cellHeight - height).coerceAtLeast(0f)
        } else 0f
        scrollOffset = scrollOffset.coerceIn(minScroll, maxScroll)
    }

    private fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

// ── Minimal InputConnection bridge ─────────────────────────────────────────

private class TerminalInputConnection(
    private val view:    TerminalView,
    private val session: TerminalSession?
) : android.view.inputmethod.BaseInputConnection(view, false) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.toString()?.toByteArray(Charsets.UTF_8)?.let { session?.sendInput(it) }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { session?.sendInput(byteArrayOf(0x7F)) } // DEL
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val bytes = event.toTerminalBytes()
            if (bytes != null) { session?.sendInput(bytes); return true }
        }
        return super.sendKeyEvent(event)
    }
}

// ── KeyEvent → terminal byte sequence ─────────────────────────────────────

private fun KeyEvent.toTerminalBytes(): ByteArray? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER      -> byteArrayOf(0x0D)
    KeyEvent.KEYCODE_TAB        -> byteArrayOf(0x09)
    KeyEvent.KEYCODE_DEL        -> byteArrayOf(0x7F)
    KeyEvent.KEYCODE_ESCAPE     -> byteArrayOf(0x1B)
    KeyEvent.KEYCODE_DPAD_UP    -> byteArrayOf(0x1B, 0x5B, 0x41)
    KeyEvent.KEYCODE_DPAD_DOWN  -> byteArrayOf(0x1B, 0x5B, 0x42)
    KeyEvent.KEYCODE_DPAD_RIGHT -> byteArrayOf(0x1B, 0x5B, 0x43)
    KeyEvent.KEYCODE_DPAD_LEFT  -> byteArrayOf(0x1B, 0x5B, 0x44)
    else -> {
        // Use the full Unicode code point and encode to UTF-8 rather than
        // truncating to a single byte (which breaks chars > U+007F).
        val cp = unicodeChar
        if (cp > 0) Character.toString(cp).toByteArray(Charsets.UTF_8) else null
    }
}
