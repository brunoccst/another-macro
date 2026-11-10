package io.github.brunoccst.anothermacro

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** One captured screen: grayscale, landscape, full resolution. */
class Frame(val gray: Mat) {
    val width: Int get() = gray.cols()
    val height: Int get() = gray.rows()

    /**
     * True when the whole frame is black: the screen is off, or the screen capture sends
     * nothing. Not a portrait app, and not proof the game is gone: [ScreenWake] turns the
     * screen on, and a macro stops if it stays black (see Context.refresh).
     */
    val allBlack: Boolean by lazy { isBlack(gray) }

    /**
     * True when a portrait app (home screen, Termux, ...) is in front instead of the
     * landscape game: the landscape capture then shows it pillar-boxed, with black
     * bars on both sides. An all-black frame is not one ([allBlack]).
     */
    val portraitAppInFront: Boolean get() {
        if (allBlack) return false
        val w = width
        val left = gray.submat(0, height, 0, w / 5)
        val right = gray.submat(0, height, w - w / 5, w)
        val dark = org.opencv.core.Core.mean(left).`val`[0] < 4 && org.opencv.core.Core.mean(right).`val`[0] < 4
        left.release(); right.release()
        return dark
    }

    companion object {
        /** Mean brightness under this (0..255) = black; our own overlays alone stay under it too. */
        const val BLACK_MEAN = 2.5

        fun isBlack(m: Mat): Boolean {
            val mean = org.opencv.core.Core.mean(m).`val`
            val channels = if (m.channels() >= 3) 3 else 1
            return (0 until channels).sumOf { mean[it] } / channels < BLACK_MEAN
        }
    }
}

/**
 * Screen capture through MediaProjection. The game only runs in landscape, so the
 * virtual display is created landscape-sized (long side x short side) and frames
 * map 1:1 to gesture coordinates while the game is in front.
 */
class Capture(projection: MediaProjection, val width: Int, val height: Int, densityDpi: Int) {
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay
    private val lock = Object()
    private var latest: Mat? = null
    private var latestRgba: Mat? = null
    private var frames = 0L
    /** When the capture last sent a frame (System.currentTimeMillis), 0 before the first. */
    @Volatile var lastFrameAt = 0L
        private set
    @Volatile var released = false
        private set

    init {
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val buf = plane.buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val rgba = Mat(image.height, rowPixels, CvType.CV_8UC4)
                rgba.put(0, 0, bytes)
                val gray = Mat()
                Imgproc.cvtColor(rgba.submat(0, image.height, 0, image.width), gray, Imgproc.COLOR_RGBA2GRAY)
                synchronized(lock) {
                    latest?.release()
                    latest = gray
                    latestRgba?.release()
                    latestRgba = rgba // kept (no copy) for the template capture tool
                    frames++
                    lastFrameAt = System.currentTimeMillis()
                    lock.notifyAll()
                }
            } finally {
                image.close()
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "another-macro", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    /**
     * The latest screen. The virtual display only produces images when the screen
     * changes, so a still screen returns the last one (after a short wait for a
     * fresh image, to see the result of the last input).
     */
    fun grab(freshWithinMs: Long = 250): Frame? {
        synchronized(lock) {
            val seen = frames
            val deadline = System.currentTimeMillis() + freshWithinMs
            while (frames == seen && System.currentTimeMillis() < deadline) {
                lock.wait((deadline - System.currentTimeMillis()).coerceAtLeast(1))
            }
            return latest?.let { Frame(it.clone()) }
        }
    }

    /** The latest screen in colour, as BGR (for saving templates). */
    fun grabColor(): Mat? = synchronized(lock) {
        val rgba = latestRgba ?: return null
        val bgr = Mat()
        Imgproc.cvtColor(rgba.submat(0, height, 0, width), bgr, Imgproc.COLOR_RGBA2BGR)
        bgr
    }

    /** How long ago the last frame came (null: none yet or released). */
    fun frameAgeMs(): Long? = if (released || lastFrameAt == 0L) null else System.currentTimeMillis() - lastFrameAt

    fun release() {
        released = true
        runCatching { display.release() }
        runCatching { reader.close() }
        thread.quitSafely()
        synchronized(lock) { latest?.release(); latest = null; latestRgba?.release(); latestRgba = null }
    }
}
