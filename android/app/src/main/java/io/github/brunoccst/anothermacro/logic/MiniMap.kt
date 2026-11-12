package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Reads the party's spot from the small minimap in the field's top-right corner, without
 * opening the area map (~3 s a look).
 *
 * The minimap draws the same layout as the area map at about half its size (0.49 of the
 * area map's screen fractions, the same on both axes; castle screenshots 2026-11-12):
 * light-grey streets on a dark red background, the party as a gold ring (a teardrop when it
 * faces up), only the parts already walked. It scrolls with the party, so where the layout
 * sits in it is found every time: the layout is drawn at that scale and slid over the grey
 * streets that show; the party's marker, taken through that fit, gives the spot.
 *
 * A read is kept only when the fit is clear: most of the grey lies on the layout's streets,
 * and no other fit (a spot more than 0.012 away along the street, or another floor) comes
 * close. Just after arriving little is drawn yet, and a lone street fits anywhere along a
 * longer one: those reads are left out (null).
 */
object MiniMap {
    /** The minimap's scale: a step of 1.0 on the area map is this much of the screen. */
    const val SCALE = 0.49
    /** Where the minimap is drawn (screen fractions): the top-right corner, above the menu button. */
    private const val LEFT = 0.83
    private const val BOTTOM = 0.19
    /** A street's thickness on the minimap (screen-height fraction). */
    private const val THICK = 0.0125
    private const val WORK_WIDTH = 1400.0
    private const val MIN_SCORE = 0.5
    private const val MAX_OTHER = 0.8

    /** Check mode: also read the area map wherever the minimap is read, log both, use the area map's. */
    @Volatile var compare = false

    data class Read(val at: Point, val score: Double, val other: Double)

    private var cacheKey: Any? = null
    private var cacheSize = 0 to 0
    private var cached: Mat? = null

    private fun render(l: AreaNav.Layout, w: Int, h: Int, pad: Int): Mat {
        if (cacheKey === l && cacheSize == (w to h) && cached != null) return cached!!
        cached?.release()
        val sx = SCALE * w; val sy = SCALE * h; val th = THICK * h
        val big = Mat((sy + 2 * pad).toInt(), (sx + 2 * pad).toInt(), CvType.CV_32F, Scalar(-1.0))
        fun box(x1: Double, y1: Double, x2: Double, y2: Double) = Imgproc.rectangle(big,
            org.opencv.core.Point(Math.round(x1).toDouble(), Math.round(y1).toDouble()),
            org.opencv.core.Point(Math.round(x2).toDouble(), Math.round(y2).toDouble()), Scalar(1.0), -1)
        for (s in l.segs) box(s.x1 * sx + pad, s.y * sy + pad - th / 2, s.x2 * sx + pad, s.y * sy + pad + th / 2)
        for (k in l.links) box(k.x * sx + pad - th / 2, k.y1 * sy + pad, k.x * sx + pad + th / 2, k.y2 * sy + pad)
        cacheKey = l; cacheSize = w to h; cached = big
        return big
    }

    /**
     * The party's spot on [l] from the minimap of the current frame, or null when it can't be
     * read clearly. Only fits that put the party within [tol] (x) and 0.1 (y) of one of
     * [near] are considered (the followed spot; after a flick, both ends of the stairs).
     */
    fun locate(ctx: Context, l: AreaNav.Layout, near: List<Point>, tol: Double = 0.08): Read? {
        val bgr = ctx.colorMat() ?: return null
        try {
            return locate(bgr, l, near, tol)
        } finally { bgr.release() }
    }

    fun locate(bgr: Mat, l: AreaNav.Layout, near: List<Point>, tol: Double = 0.08): Read? {
        val fw = bgr.cols(); val fh = bgr.rows()
        val x0 = (LEFT * fw).toInt()
        val full = bgr.submat(Rect(x0, 0, fw - x0, (BOTTOM * fh).toInt()))
        // at most 1400 px wide (a step of 0.0015 a pixel): the full frame took ~1 s a read
        val k = minOf(1.0, WORK_WIDTH / fw)
        val w = (fw * k).toInt(); val h = (fh * k).toInt()
        val crop = if (k < 1.0) Mat().also { Imgproc.resize(full, it, org.opencv.core.Size(0.0, 0.0), k, k, Imgproc.INTER_AREA); full.release() } else full
        val hsv = Mat(); Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_BGR2HSV); crop.release()
        // streets: light grey (the white door icons and the gold marker are brighter, left out)
        val barU = Mat(); Core.inRange(hsv, Scalar(0.0, 0.0, 95.0), Scalar(180.0, 45.0, 175.0), barU)
        val marker = Mat(); Core.inRange(hsv, Scalar(8.0, 30.0, 185.0), Scalar(40.0, 255.0, 255.0), marker)
        hsv.release()
        try {
            val m = markerCenter(marker) ?: return null
            val bars = Core.countNonZero(barU)
            if (bars < 20) return null
            val bar = Mat(); barU.convertTo(bar, CvType.CV_32F, 1.0 / 255)
            val pad = (0.2 * w).toInt()
            val big = render(l, w, h, pad)
            if (bar.rows() > big.rows() || bar.cols() > big.cols()) { bar.release(); return null }
            val r = Mat(); Imgproc.matchTemplate(big, bar, r, Imgproc.TM_CCORR); bar.release()
            val rw = r.cols(); val rh = r.rows()
            val score = FloatArray(rw * rh); r.get(0, 0, score); r.release()
            val sx = SCALE * w; val sy = SCALE * h
            fun px(u: Int) = (u + m.first - pad) / sx
            fun py(v: Int) = (v + m.second - pad) / sy
            fun ok(x: Double, y: Double) = near.any { abs(x - it.x) < tol && abs(y - it.y) < 0.1 }
            var best = -1e9; var bu = 0; var bv = 0
            for (v in 0 until rh) {
                val y = py(v)
                if (near.none { abs(y - it.y) < 0.1 }) continue
                for (u in 0 until rw) {
                    val s = score[v * rw + u]
                    if (s > best && ok(px(u), y)) { best = s.toDouble(); bu = u; bv = v }
                }
            }
            if (best < -1e8) return null
            val bx = px(bu); val by = py(bv)
            var other = -1e9
            for (v in 0 until rh) {
                val y = py(v)
                if (near.none { abs(y - it.y) < 0.1 }) continue
                for (u in 0 until rw) {
                    val s = score[v * rw + u]
                    if (s <= other) continue
                    val x = px(u)
                    if ((abs(x - bx) > 0.012 || abs(y - by) > 0.06) && ok(x, y)) other = s.toDouble()
                }
            }
            val sc = best / bars; val ot = other / bars
            if (sc < MIN_SCORE || ot > MAX_OTHER * sc) {
                MacroLog.i("minimap: no clear fit (score ${"%.2f".format(sc)}, next ${"%.2f".format(ot)})")
                return null
            }
            // the marker sits a little above its street (a teardrop when it faces up): take the street
            val seg = Route.segOf(l, Point(bx, by)) ?: return null
            return Read(Point(bx.coerceIn(seg.x1, seg.x2), seg.y), sc, ot)
        } finally { barU.release(); marker.release() }
    }

    /** The gold ring's centre: its dark middle when the ring shows closed, else the blob's centre. */
    private fun markerCenter(mask: Mat): Pair<Double, Double>? {
        val lab = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, lab, stats, cents)
        try {
            if (n < 2) return null
            var i = 1
            for (k in 2 until n) if (stats.get(k, Imgproc.CC_STAT_AREA)[0] > stats.get(i, Imgproc.CC_STAT_AREA)[0]) i = k
            if (stats.get(i, Imgproc.CC_STAT_AREA)[0] < 4) return null
            val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt(); val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt(); val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val ids = IntArray(bw * bh); lab.submat(Rect(x, y, bw, bh)).let { sub -> val c = sub.clone(); c.get(0, 0, ids); c.release(); sub.release() }
            // outside = not the blob and reachable from the box's border; a hole = neither
            val inside = BooleanArray(bw * bh) { ids[it] == i }
            val outside = BooleanArray(bw * bh)
            val stack = ArrayDeque<Int>()
            for (u in 0 until bw) { stack.add(u); stack.add((bh - 1) * bw + u) }
            for (v in 0 until bh) { stack.add(v * bw); stack.add(v * bw + bw - 1) }
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                if (inside[p] || outside[p]) continue
                outside[p] = true
                val u = p % bw; val v = p / bw
                if (u > 0) stack.add(p - 1); if (u < bw - 1) stack.add(p + 1)
                if (v > 0) stack.add(p - bw); if (v < bh - 1) stack.add(p + bw)
            }
            var hx = 0.0; var hy = 0.0; var hn = 0
            var ax = 0.0; var ay = 0.0; var an = 0
            for (p in 0 until bw * bh) {
                val u = p % bw; val v = p / bw
                if (inside[p]) { ax += u; ay += v; an++ } else if (!outside[p]) { hx += u; hy += v; hn++ }
            }
            return if (hn > 0) (x + hx / hn) to (y + hy / hn) else (x + ax / an) to (y + ay / an)
        } finally { lab.release(); stats.release(); cents.release() }
    }
}
