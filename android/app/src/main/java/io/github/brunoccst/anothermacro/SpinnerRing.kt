package io.github.brunoccst.anothermacro

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * The state ring around the floating controls' handle, drawn in code.
 *
 * - [Mode.OFF]: nothing (idle).
 * - [Mode.SPIN]: a bright arc with a fading tail that circles the badge over a faint track
 *   (a macro is running), so it reads at a glance even over a busy game screen.
 * - [Mode.STILL]: a steady full ring (paused).
 *
 * The spin is timed from the clock in [draw] and redraws itself frame by frame only while
 * spinning, so it keeps turning even when the phone's animation scale is off.
 */
class SpinnerRing(private val strokePx: Float) : Drawable() {

    enum class Mode { OFF, SPIN, STILL }

    private var mode = Mode.OFF
    private var color = Color.TRANSPARENT
    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rotate = Matrix()
    private var gradient: SweepGradient? = null
    private var gradientKey = 0L

    /** Show [mode] in [color]; redraws only when something changed. */
    fun set(mode: Mode, color: Int) {
        if (mode == this.mode && color == this.color) return
        this.mode = mode
        this.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (mode == Mode.OFF) return
        val b = bounds
        val half = strokePx / 2f
        oval.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        track.strokeWidth = strokePx
        arc.strokeWidth = strokePx
        if (mode == Mode.STILL) {
            track.color = color
            canvas.drawOval(oval, track)
            return
        }
        // faint full track, then the comet: transparent tail -> full colour at the head
        track.color = withAlpha(color, TRACK_ALPHA)
        canvas.drawOval(oval, track)
        val cx = oval.centerX(); val cy = oval.centerY()
        val key = (color.toLong() shl 32) or (b.width().toLong() shl 16) or b.height().toLong()
        val g = gradient?.takeIf { gradientKey == key } ?: SweepGradient(
            cx, cy,
            intArrayOf(withAlpha(color, 0), withAlpha(color, 0), color, color),
            floatArrayOf(0f, 1f - SWEEP / 360f, 0.97f, 1f),
        ).also { gradient = it; gradientKey = key }
        val angle = (SystemClock.uptimeMillis() % TURN_MS) * 360f / TURN_MS
        rotate.setRotate(angle, cx, cy)
        g.setLocalMatrix(rotate)
        arc.shader = g
        // the arc ends at 360° of the gradient (its head), turned by the same angle
        canvas.drawArc(oval, angle - SWEEP, SWEEP - HEAD_GAP, false, arc)
        invalidateSelf() // next frame
    }

    override fun setAlpha(alpha: Int) {
        track.alpha = alpha; arc.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        track.colorFilter = colorFilter; arc.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        /** One full turn, ms. */
        private const val TURN_MS = 1000L
        /** Length of the arc (head plus fading tail), degrees. */
        private const val SWEEP = 270f
        /** Keeps the round cap of the head from overlapping the gradient's seam, degrees. */
        private const val HEAD_GAP = 4f
        /** Opacity of the track under the arc, 0..255. */
        private const val TRACK_ALPHA = 60
    }
}
