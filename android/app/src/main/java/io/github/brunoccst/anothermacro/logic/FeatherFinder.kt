package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*

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
 * a binarized, half-resolution frame at five scales (some maps, e.g. tree-top villages,
 * are zoomed further in or out than the map the template was cut on).
 */
class FeatherFinder private constructor(private val templates: List<Mat>, private val bubbles: List<Mat>) {

    fun find(frame: Frame): Pair<Double, Point> {
        val g = frame.gray
        val top = (g.rows() * 0.09).toInt()
        val bottom = (g.rows() * 0.76).toInt()
        val band = g.submat(top, bottom, 0, g.cols())
        val half = Mat()
        Imgproc.resize(band, half, Size(0.0, 0.0), 0.5, 0.5, Imgproc.INTER_AREA)
        band.release()
        // two binarizations: plain brightness (the bubble over a dark field), and brightness
        // against the local background (white top-hat) for a bubble over a bright sky, where
        // a fixed threshold turns the whole sky white (Snake Skull Mescita's sunset, 2026-11-12)
        val plain = Mat()
        Imgproc.threshold(half, plain, 180.0, 255.0, Imgproc.THRESH_BINARY)
        val hat = Mat()
        Imgproc.morphologyEx(half, hat, Imgproc.MORPH_TOPHAT,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0)))
        Imgproc.threshold(hat, hat, 40.0, 255.0, Imgproc.THRESH_BINARY)
        var best = 0.0
        var at = Point(0.0, 0.0)
        // the whole bubble (disc, rim and feather) on the plain grey frame: it tells a small
        // bubble from bright scenery better than the feather's shape alone (half-res frames
        // of the sunset map: bubbles 0.81-0.84, scenery at most 0.72). Counted a hair above the
        // feather-shape score so 0.78 still means "a bubble".
        for (t in bubbles) {
            if (t.rows() > half.rows() || t.cols() > half.cols()) continue
            val r = Mat()
            Imgproc.matchTemplate(half, t, r, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(r)
            r.release()
            if (mm.maxVal + 0.01 > best) {
                best = mm.maxVal + 0.01
                val cx = 2 * (mm.maxLoc.x + t.cols() / 2.0)
                val cy = top + 2 * (mm.maxLoc.y + t.rows() / 2.0)
                at = Point(cx / g.cols(), cy / g.rows())
            }
        }
        half.release()
        for (bin in listOf(plain, hat)) for (t in templates) {
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
        plain.release(); hat.release()
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
            val ts = listOf(0.65, 0.8, 0.95, 1.1, 1.3, 1.5).map { k ->
                Mat().also { Imgproc.resize(bin, it, Size(0.0, 0.0), k / 2, k / 2, Imgproc.INTER_AREA) }
            }
            val bs = listOf(0.5, 0.55, 0.6, 0.7, 0.8, 0.9, 1.0, 1.15, 1.3).map { k ->
                Mat().also { Imgproc.resize(g, it, Size(0.0, 0.0), k / 2, k / 2, Imgproc.INTER_AREA) }
            }
            return FeatherFinder(ts, bs)
        }
    }
}
