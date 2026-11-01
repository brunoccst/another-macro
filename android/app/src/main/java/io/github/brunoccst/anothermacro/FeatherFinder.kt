package io.github.brunoccst.anothermacro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Finds the scratchbook cat's feather bubble. The bubble is semi-transparent and
 * its size changes with each map's zoom, so the white feather shape is matched on
 * a binarized, half-resolution frame at three scales.
 */
class FeatherFinder private constructor(private val templates: List<Mat>) {

    fun find(frame: Frame): Pair<Double, Point> {
        val g = frame.gray
        val top = (g.rows() * 0.09).toInt()
        val bottom = (g.rows() * 0.76).toInt()
        val band = g.submat(top, bottom, 0, g.cols())
        val half = Mat()
        Imgproc.resize(band, half, Size(0.0, 0.0), 0.5, 0.5, Imgproc.INTER_AREA)
        band.release()
        val bin = Mat()
        Imgproc.threshold(half, bin, 180.0, 255.0, Imgproc.THRESH_BINARY)
        half.release()
        var best = 0.0
        var at = Point(0.0, 0.0)
        for (t in templates) {
            if (t.rows() > bin.rows() || t.cols() > bin.cols()) continue
            val r = Mat()
            Imgproc.matchTemplate(bin, t, r, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(r)
            r.release()
            if (mm.maxVal > best) {
                best = mm.maxVal
                val cx = 2 * (mm.maxLoc.x + t.cols() / 2.0)
                val cy = top + 2 * (mm.maxLoc.y + t.rows() / 2.0)
                at = Point(cx / g.cols(), cy / g.rows())
            }
        }
        bin.release()
        return best to at
    }

    companion object {
        fun load(ctx: Context): FeatherFinder {
            val bytes = ctx.assets.open("templates/cat_scratchbook/cat_feather_bubble.png").use { it.readBytes() }
            val g = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_GRAYSCALE)
            val h = g.rows(); val w = g.cols()
            val inner = g.submat((h * 0.18).toInt(), (h * 0.82).toInt(), (w * 0.18).toInt(), (w * 0.82).toInt())
            val bin = Mat()
            Imgproc.threshold(inner, bin, 215.0, 255.0, Imgproc.THRESH_BINARY)
            val ts = listOf(0.95, 1.1, 1.3).map { k ->
                Mat().also { Imgproc.resize(bin, it, Size(0.0, 0.0), k / 2, k / 2, Imgproc.INTER_AREA) }
            }
            return FeatherFinder(ts)
        }
    }
}
