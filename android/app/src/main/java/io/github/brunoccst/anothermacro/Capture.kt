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
    private var frames = 0L

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
                rgba.release()
                synchronized(lock) {
                    latest?.release()
                    latest = gray
                    frames++
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

    fun release() {
        runCatching { display.release() }
        runCatching { reader.close() }
        thread.quitSafely()
        synchronized(lock) { latest?.release(); latest = null }
    }
}
