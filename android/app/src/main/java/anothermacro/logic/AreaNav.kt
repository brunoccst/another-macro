package anothermacro.logic

import macroforge.*

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Walking to a spot shown on the area map (tap the minimap to open it), shared by the cat
 * routine (the feather) and the Another Dungeons (chests, exits).
 *
 * The area map shows the streets as light grey lines, the party as a gold "eye" marker and
 * icons for points of interest. Left/right is one long hold sized from the distance on the
 * map; changing street is a quick flick up/down while standing on the ground arrow, found
 * where a grey connector leaves the party's street.
 */
object AreaNav {
    val MINIMAP = Point(0.917, 0.12)

    /**
     * Where to tap the minimap: [MINIMAP], or another spot on it when this app's floating handle (the
     * round badge, which the user can drag) sits over that point and would swallow the tap.
     */
    fun minimap(ctx: Context): Point {
        val area = runCatching { InputService.instance?.controls()?.second }.getOrNull() ?: return MINIMAP
        val f = ctx.frame ?: return MINIMAP
        fun free(p: Point) = !area.contains((p.x * f.width).toInt(), (p.y * f.height).toInt())
        return listOf(MINIMAP, Point(0.875, 0.10), Point(0.965, 0.2), Point(0.86, 0.2)).firstOrNull { free(it) }
            ?: MINIMAP.also { MacroLog.i("the floating handle covers the minimap: drag it elsewhere") }
    }
    private val MAP_CLOSE = Point(0.5, 0.93)

    /** Map units (fraction of the screen width) walked per second of holding. Measured in Unigan. */
    const val WALK_PER_S = 0.027
    const val ROW_TOL = 0.035

    /** What one look at the area map showed. */
    class View(val targets: List<Point>, val party: Point?, val text: List<Ocr.Line>, private val paths: Mat?, private val w: Int, private val h: Int,
               /** Every blob that could be the party marker, best first (gold things on screen can look like it). */
               val candidates: List<Point> = listOfNotNull(party)) {
        /** x of the paths leaving the party's street up (or down), normalized. */
        fun connectors(up: Boolean): List<Double> {
            val p = party ?: return emptyList()
            val m = paths ?: return emptyList()
            val y0 = (p.y * h).toInt()
            // from the edge of the party's street toward the next one
            val (from, to) = if (up) (y0 - 100) to (y0 - 22) else (y0 + 22) to (y0 + 100)
            if (from < 0 || to >= h) return emptyList()
            val band = ByteArray((to - from) * w)
            m.submat(from, to, 0, w).let { sub -> sub.clone().also { c -> c.get(0, 0, band); c.release() }; sub.release() }
            val cols = ArrayList<Int>()
            for (x in 0 until w step 2) {
                var on = 0
                for (y in 0 until to - from) if (band[y * w + x].toInt() != 0) on++
                if (on >= (to - from) * 0.35) cols += x  // map icons cover parts of a path
            }
            val groups = ArrayList<MutableList<Int>>()
            for (c in cols) if (groups.isNotEmpty() && c - groups.last().last() <= 6) groups.last() += c else groups += mutableListOf(c)
            return groups.filter { it.last() - it.first() in 6..80 }.map { (it.first() + it.last()) / 2.0 / w }
        }

        /** The streets and stairs this look's street mask shows (see [streetsOf]); null if the map didn't open. */
        fun streets(): Layout? {
            val m = paths ?: return null
            if (w == 0 || h == 0) return null
            val bytes = ByteArray(w * h)
            m.clone().also { c -> c.get(0, 0, bytes); c.release() }
            return streetsOf(bytes, w, h)
        }

        fun release() { paths?.release() }
    }

    private var last: View? = null

    /**
     * The layout of a map whose area map scrolls up and down with the party (Sea of Ice: a
     * see-through overlay drawn higher when the party is on a lower street). While set, every
     * look lines this layout up with the streets it shows and gives the party's spot in the
     * layout's y (as the map is drawn with the party on its arrival street). Null: no scrolling.
     */
    @Volatile var align: Layout? = null

    /**
     * Open the area map, find the [targets] (given the grey frame) and the party marker, close it.
     * [readText] also OCRs the map (its title names the area).
     */
    fun look(ctx: Context, readText: Boolean = false, targets: (Frame) -> List<Point>): View {
        last?.release(); last = null
        // only from the field: a battle that just started would take these taps (the close tap at
        // the bottom middle lands on the 4th character's portrait)
        ctx.refresh()
        if (ctx.find("field_menu_button") == null) return View(emptyList(), null, emptyList(), null, 0, 0)
        ctx.tap(minimap(ctx)); ctx.wait(1.3)
        val frame = ctx.refresh()
        if (Battle.inBattle(ctx)) return View(emptyList(), null, emptyList(), null, 0, 0)
        val first = ctx.colorMat()
        // the party marker pulses (shrinks, fades): if this frame caught it faint, take another
        val bgr = if (first != null && findPartyMarkers(first, emptyList()).isEmpty()) {
            Thread.sleep(350)
            val again = ctx.colorMat()
            if (again != null) { first.release(); again } else first
        } else first
        val text = if (readText) ctx.readText() else emptyList()
        val found = targets(frame)
        ctx.tap(MAP_CLOSE); ctx.wait(1.0)
        val dy = align?.let { l -> bgr?.let { verticalShift(it, l) } } ?: 0.0
        val cands = (bgr?.let { findPartyMarkers(it, found) } ?: emptyList()).map { if (dy != 0.0) Point(it.x, it.y - dy) else it }
        val party = cands.firstOrNull()
        if (party == null && bgr != null) {
            // keep the map for debugging (app storage, not shared)
            Imgcodecs.imwrite(java.io.File(ctx.debugDir, "area-map.png").path, bgr)
        }
        val paths = bgr?.let { pathMask(it) }
        val view = View(found, party, text, paths, bgr?.cols() ?: 0, bgr?.rows() ?: 0, cands)
        bgr?.release()
        last = view
        return view
    }

    /**
     * Walk until the party stands by the point [target] picks from a fresh look at the map
     * (null: nothing left to walk to → false). [onStep] runs after each street change (fight
     * an encounter that started on the way).
     */
    fun steer(ctx: Context, look: () -> View?, target: (View) -> Point?, onStep: () -> Unit, tries: Int = 16): Boolean {
        var lastRowY: Double? = null
        var nudge = 0
        repeat(tries) {
            val view = look() ?: return false
            val goal = target(view) ?: return false
            val party = view.party ?: return true.also { MacroLog.i("party marker not found on the map; assuming we're there") }
            val dx = goal.x - party.x
            val dy = goal.y - party.y
            if (abs(dy) < ROW_TOL && abs(dx) < 0.05) return true
            if (abs(dy) < ROW_TOL) {
                // same street: one long hold, sized from the distance on the map
                hold(ctx, if (dx > 0) 1.0 else -1.0, abs(dx))
                onStep()
                return@repeat
            }
            val up = dy < 0
            val sameRowAsBefore = lastRowY?.let { abs(it - party.y) < ROW_TOL } ?: false
            lastRowY = party.y
            val conn = view.connectors(up).minByOrNull { abs(it - party.x) + 0.5 * abs(it - goal.x) }
            if (conn == null) {
                MacroLog.i("no street connector ${if (up) "up" else "down"} on the map; walking toward the target")
                hold(ctx, if (dx > 0) 1.0 else -1.0, abs(dx).coerceAtMost(0.08))
                onStep()
                return@repeat
            }
            val toConn = conn - party.x
            if (abs(toConn) > 0.012) hold(ctx, if (toConn > 0) 1.0 else -1.0, abs(toConn))
            // the street changes on a quick flick while standing on the ground arrow;
            // if the last flick did nothing, shuffle a little either side of the connector first
            if (sameRowAsBefore) {
                nudge++
                val side = if (nudge % 2 == 1) 1.0 else -1.0
                hold(ctx, side, 0.006 * ((nudge + 1) / 2))
            } else nudge = 0
            flick(ctx, up)
            onStep()
        }
        // out of tries: not there (the caller decides whether to search from here anyway)
        return false
    }

    fun hold(ctx: Context, dir: Double, mapDist: Double) {
        val ms = (mapDist / WALK_PER_S * 1000).toLong().coerceIn(40, 12000)
        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), ms)
        ctx.wait(0.5)
    }

    /** A quick flick up or down: takes the path at a ground arrow (a slow hold only turns around). */
    /**
     * [flick], but only as long as the climb moves the view (at least 0.6 s, at most 2.5 s)
     * instead of a fixed 2.5 s: a climb is usually over well before.
     */
    fun flickSettled(ctx: Context, up: Boolean) {
        val (a, b) = if (up) 0.74 to 0.42 else 0.42 to 0.74
        ctx.drag(Point(0.5, a), Point(0.5, b), 150)
        val end = System.currentTimeMillis() + 2500
        ctx.wait(0.6)
        var prev = ctx.refresh().gray.clone()
        var stable = 0
        while (System.currentTimeMillis() < end) {
            ctx.wait(0.12)
            val g = ctx.refresh().gray
            val c = Route.change(prev, g)
            prev.release(); prev = g.clone()
            if (c < 2.0) { if (++stable >= 2) break } else stable = 0
        }
        prev.release()
    }

    fun flick(ctx: Context, up: Boolean) {
        val (a, b) = if (up) 0.74 to 0.42 else 0.42 to 0.74
        ctx.drag(Point(0.5, a), Point(0.5, b), 150)
        ctx.wait(2.5)
    }

    /**
     * How much lower than [l] the area map draws its streets now (screen-height fraction; negative:
     * higher), by sliding the layout up and down over the streets that show. 0 when no fit is clear.
     * The streets are light grey (most maps) or a see-through grey-blue over a dark sea (Sea of Ice).
     */
    internal fun verticalShift(bgr: Mat, l: Layout): Double {
        val w = 512
        val h = Math.round(bgr.rows() * w.toDouble() / bgr.cols()).toInt()
        val small = Mat(); Imgproc.resize(bgr, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val hsv = Mat(); Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)
        // (wide: Sea of Ice streets read from S 20/V 135 grey to S 90/V 80 blue, 2026-11-15)
        val streets = Mat(); Core.inRange(hsv, Scalar(0.0, 0.0, 90.0), Scalar(180.0, 60.0, 230.0), streets)
        val blue = Mat(); Core.inRange(hsv, Scalar(90.0, 20.0, 60.0), Scalar(140.0, 130.0, 170.0), blue)
        Core.bitwise_or(streets, blue, streets)
        val right = (w * 0.93).toInt()
        streets.submat(0, h, right, w).setTo(Scalar(0.0))
        val model = Mat(h, w, org.opencv.core.CvType.CV_8U)
        val both = Mat()
        val th = 0.024 * h
        fun pt(x: Double, y: Double) = org.opencv.core.Point(Math.round(x).toDouble(), Math.round(y).toDouble())
        var best = 0.0; var bestDy = 0.0; var atZero = 0.0
        // from 0.16 higher to 0.10 lower, in steps of 0.004
        for (step in -40..25) {
            val dy = step * 0.004
            model.setTo(Scalar(0.0))
            for (s in l.segs) Imgproc.rectangle(model, pt(s.x1 * w, (s.y + dy) * h - th / 2), pt(s.x2 * w, (s.y + dy) * h + th / 2), Scalar(255.0), -1)
            for (k in l.links) Imgproc.rectangle(model, pt(k.x * w - th / 2, (k.y1 + dy) * h), pt(k.x * w + th / 2, (k.y2 + dy) * h), Scalar(255.0), -1)
            val total = Core.countNonZero(model)
            if (total == 0) continue
            Core.bitwise_and(model, streets, both)
            val score = Core.countNonZero(both).toDouble() / total
            if (step == 0) atZero = score
            if (score > best) { best = score; bestDy = dy }
        }
        listOf(small, hsv, streets, blue, model, both).forEach { it.release() }
        // the right layout fits 0.89-0.95, another map's up to 0.65 (Sea of Ice screenshots, 2026-11-15)
        val ok = best >= 0.75 && (bestDy == 0.0 || best - atZero >= 0.05)
        MacroLog.i("area map drawn ${"%.3f".format(bestDy)} lower (fit ${"%.2f".format(best)}, unshifted ${"%.2f".format(atZero)})${if (ok) "" else ": not clear, taken as 0"}")
        return if (ok) bestDy else 0.0
    }

    /** The streets on the area map: light grey, unsaturated lines (the side buttons are cut off). */
    private fun pathMask(bgr: Mat): Mat {
        val hsv = Mat(); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat(); Core.inRange(hsv, Scalar(0.0, 0.0, 125.0), Scalar(180.0, 25.0, 215.0), mask)
        hsv.release()
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Mat.ones(9, 9, org.opencv.core.CvType.CV_8U))
        val right = (mask.cols() * 0.93).toInt()
        mask.submat(0, mask.rows(), right, mask.cols()).setTo(Scalar(0.0))
        return mask
    }

    /** The party marker: a gold-rimmed eye, wider than tall, away from the [targets] (chest icons are gold too). */
    private fun findPartyMarkers(bgr: Mat, targets: List<Point>): List<Point> {
        val hsv = Mat(); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat(); Core.inRange(hsv, Scalar(12.0, 40.0, 140.0), Scalar(35.0, 200.0, 255.0), mask)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Mat.ones(5, 5, org.opencv.core.CvType.CV_8U))
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cents)
        val w = bgr.cols().toDouble(); val h = bgr.rows().toDouble()
        val found = ArrayList<Pair<Double, Point>>()
        val seen = ArrayList<String>()
        for (k in 1 until n) {
            val bw = stats.get(k, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(k, Imgproc.CC_STAT_HEIGHT)[0]
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val cx = cents.get(k, 0)[0] / w
            val cy = cents.get(k, 1)[0] / h
            val aspect = bw / bh
            if (area >= 300) seen += "(${"%.3f".format(cx)},${"%.3f".format(cy)} ${bw.toInt()}x${bh.toInt()} a${area.toInt()})"
            // the eye is wider than tall (quest "!" markers are taller); its size follows the map's zoom
            if (area < 220 || aspect < 1.05 || aspect > 1.9 || bw < 24 || bw > 110) continue
            if (targets.any { abs(cx - it.x) < 0.02 && abs(cy - it.y) < 0.04 }) continue
            found += area * (1.0 - abs(aspect - 1.35)) to Point(cx, cy)
        }
        listOf(hsv, mask, labels, stats, cents).forEach { it.release() }
        if (found.isEmpty()) MacroLog.i("map blobs (${bgr.cols()}x${bgr.rows()}): ${seen.joinToString(" ")}")
        return found.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * Quest "!" markers on the area map: green badges. One can sit on top of another icon
     * (e.g. the cat's feather) and hide it. Biggest first; the side buttons are left out.
     */
    fun findQuestMarkers(bgr: Mat): List<Point> {
        val hsv = Mat(); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat(); Core.inRange(hsv, Scalar(40.0, 110.0, 110.0), Scalar(85.0, 255.0, 255.0), mask)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Mat.ones(5, 5, org.opencv.core.CvType.CV_8U))
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cents)
        val w = bgr.cols().toDouble(); val h = bgr.rows().toDouble()
        val found = ArrayList<Pair<Double, Point>>()
        for (k in 1 until n) {
            val bw = stats.get(k, Imgproc.CC_STAT_WIDTH)[0] / w
            val bh = stats.get(k, Imgproc.CC_STAT_HEIGHT)[0] / h
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val cx = cents.get(k, 0)[0] / w
            val cy = cents.get(k, 1)[0] / h
            // a badge a few % of the screen, roughly as tall as wide (in screen units), not a long line
            if (bw < 0.008 || bw > 0.06 || bh < 0.015 || bh > 0.13) continue
            val aspect = (bw * w) / (bh * h)
            if (aspect < 0.35 || aspect > 1.6) continue
            if (area < 0.35 * bw * w * bh * h) continue // mostly filled, not a ring of grass
            if (cx > 0.93 || cy < 0.08 || cy > 0.92) continue
            found += area to Point(cx, cy)
        }
        listOf(hsv, mask, labels, stats, cents).forEach { it.release() }
        return found.sortedByDescending { it.first }.map { it.second }
    }

    /** Every place an icon (an asset PNG) shows on the grey [frame], at a few sizes, best first. */
    fun findIcons(ctx: Context, frame: Frame, asset: String, min: Double = 0.75, cache: MutableMap<String, Mat> = icons): List<Point> {
        val t = cache.getOrPut(asset) {
            runCatching { ctx.assets.open(asset).use { it.readBytes() } }.getOrNull()
                ?.let { Imgcodecs.imdecode(MatOfByte(*it), Imgcodecs.IMREAD_GRAYSCALE) } ?: Mat()
        }
        if (t.empty()) return emptyList()
        val hits = ArrayList<Pair<Double, Point>>()
        for (k in listOf(0.8, 1.0, 1.25)) {
            val s = Mat(); Imgproc.resize(t, s, Size(0.0, 0.0), k, k)
            if (s.rows() >= frame.gray.rows() || s.cols() >= frame.gray.cols()) { s.release(); continue }
            val r = Mat(); Imgproc.matchTemplate(frame.gray, s, r, Imgproc.TM_CCOEFF_NORMED)
            // take peaks one by one, blanking each so the next copy of the icon shows
            repeat(12) {
                val mm = Core.minMaxLoc(r)
                if (mm.maxVal < min) return@repeat
                val p = Point((mm.maxLoc.x + s.cols() / 2.0) / frame.width, (mm.maxLoc.y + s.rows() / 2.0) / frame.height)
                hits += mm.maxVal to p
                Imgproc.rectangle(r, org.opencv.core.Point(mm.maxLoc.x - s.cols() / 2.0, mm.maxLoc.y - s.rows() / 2.0),
                    org.opencv.core.Point(mm.maxLoc.x + s.cols() / 2.0, mm.maxLoc.y + s.rows() / 2.0), Scalar(-1.0), -1)
            }
            s.release(); r.release()
        }
        // the sizes find the same icons: keep one per spot
        val out = ArrayList<Point>()
        for ((_, p) in hits.sortedByDescending { it.first }) if (out.none { abs(it.x - p.x) < 0.02 && abs(it.y - p.y) < 0.03 }) out += p
        return out
    }

    private val icons = HashMap<String, Mat>()

    /**
     * The streets ([Seg]) and the stairs between them ([Link]) on a street mask ([w] x [h], row
     * by row, non-zero = street): a street is a band of rows a few % of the screen thick that
     * holds a long horizontal run; a link is a narrow vertical run joining two bands. Map icons
     * cover parts of a street, so short gaps are bridged. Approximate (a stone floor is noisy):
     * it is what the cat survey starts from, checked by a person before it becomes a route.
     */
    fun streetsOf(mask: ByteArray, w: Int, h: Int): Layout {
        fun on(x: Int, y: Int) = mask[y * w + x].toInt() != 0
        val gap = (w * 0.03).toInt()
        val xMax = (w * 0.93).toInt()
        val yTop = (h * 0.12).toInt()
        val yBot = (h * 0.90).toInt()
        /** Runs of true in [cols] (gaps up to [gap] bridged) at least [min] long, as (from, to). */
        fun runs(cols: BooleanArray, min: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            var start = -1; var end = -1
            for (x in cols.indices) if (cols[x]) {
                if (start < 0) start = x else if (x - end > gap) { if (end - start + 1 >= min) out += intArrayOf(start, end); start = x }
                end = x
            }
            if (start >= 0 && end - start + 1 >= min) out += intArrayOf(start, end)
            return out
        }
        // 1. rows holding a long run
        val streetRow = BooleanArray(h)
        for (y in yTop until yBot) streetRow[y] = runs(BooleanArray(xMax) { on(it, y) }, (w * 0.05).toInt()).isNotEmpty()
        // 2. bands of such rows (a one-row break bridged), a street's thickness
        class Band(val top: Int, val bottom: Int, val segs: List<IntArray>) { val y get() = (top + bottom) / 2.0 / h }
        val bands = ArrayList<Band>()
        var y = yTop
        while (y < yBot) {
            if (!streetRow[y]) { y++; continue }
            val top = y
            var bottom = y
            while (y < yBot && (streetRow[y] || (y + 1 < yBot && streetRow[y + 1]))) { if (streetRow[y]) bottom = y; y++ }
            val thick = bottom - top + 1
            if (thick < h * 0.008 || thick > h * 0.07) continue  // a speck, or a plaza / big grey area
            val cols = BooleanArray(xMax) { x -> (top..bottom).count { on(x, it) } * 2 >= thick }
            val segs = runs(cols, (w * 0.03).toInt())
            if (segs.isNotEmpty()) bands += Band(top, bottom, segs)
        }
        val segs = bands.flatMap { b -> b.segs.map { Seg(b.y, it[0].toDouble() / w, it[1].toDouble() / w) } }
        // 3. links: a narrow column of street pixels filling the gap between two bands, the street
        // above and the one below both reaching it (bands in between must have no street there)
        val links = ArrayList<Link>()
        fun covers(b: Band, x: Int, slack: Int) = b.segs.any { x >= it[0] - slack && x <= it[1] + slack }
        for (i in bands.indices) for (j in i + 1 until bands.size) {
            val a = bands[i]; val b = bands[j]
            val rows = (a.bottom + 1 until b.top).filter { r -> (i + 1 until j).none { r in bands[it].top..bands[it].bottom } }
            if (rows.size < 3) continue
            val cols = BooleanArray(xMax) { x ->
                covers(a, x, gap) && covers(b, x, gap) && (i + 1 until j).none { covers(bands[it], x, 0) } &&
                    rows.count { on(x, it) } >= rows.size * 0.7
            }
            val groups = ArrayList<IntArray>()
            for (x in 0 until xMax) if (cols[x]) {
                if (groups.isNotEmpty() && x - groups.last()[1] <= 3) groups.last()[1] = x else groups += intArrayOf(x, x)
            }
            for (g in groups) {
                val width = g[1] - g[0] + 1
                if (width < w * 0.003 || width > w * 0.05) continue
                links += Link((g[0] + g[1]) / 2.0 / w, a.y, b.y)
            }
        }
        return Layout(segs, links)
    }

    // --- walking a known layout (maps whose unexplored parts are hidden) ---------------
    /** A street: a row at height [y] from [x1] to [x2] (area-map coordinates, 0..1 of the screen). */
    data class Seg(val y: Double, val x1: Double, val x2: Double)
    /** Stairs/arrow between two streets at [x]: from height [y1] up/down to [y2]. */
    data class Link(val x: Double, val y1: Double, val y2: Double)
    class Layout(val segs: List<Seg>, val links: List<Link>)

    /** Map units walked per second of holding; re-measured after every undisturbed walk. */
    var speed = WALK_PER_S

    private sealed class Move {
        data class Walk(val x: Double) : Move()
        data class Climb(val up: Boolean) : Move()
    }

    private fun segOf(l: Layout, p: Point): Seg? = l.segs.filter { abs(it.y - p.y) < ROW_TOL * 1.5 && p.x > it.x1 - 0.04 && p.x < it.x2 + 0.04 }
        .minByOrNull { abs(it.y - p.y) + maxOf(0.0, it.x1 - p.x, p.x - it.x2) }

    /** Shortest way from [from] to [to] over the layout's streets and stairs (Dijkstra on a small graph). */
    private fun route(l: Layout, from: Point, to: Point): List<Move>? {
        val a = segOf(l, from) ?: return null
        val b = segOf(l, to) ?: return null
        data class Node(val seg: Seg, val x: Double)
        val nodes = ArrayList<Node>()
        nodes += Node(a, from.x.coerceIn(a.x1, a.x2)); nodes += Node(b, to.x.coerceIn(b.x1, b.x2))
        val linkEnds = HashMap<Int, Pair<Int, Int>>()
        for ((k, ln) in l.links.withIndex()) {
            val s1 = l.segs.firstOrNull { abs(it.y - ln.y1) < 0.02 && ln.x in it.x1 - 0.02..it.x2 + 0.02 } ?: continue
            val s2 = l.segs.firstOrNull { abs(it.y - ln.y2) < 0.02 && ln.x in it.x1 - 0.02..it.x2 + 0.02 } ?: continue
            nodes += Node(s1, ln.x); nodes += Node(s2, ln.x)
            linkEnds[k] = (nodes.size - 2) to (nodes.size - 1)
        }
        val n = nodes.size
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        dist[0] = 0.0
        val climbs = HashSet<Pair<Int, Int>>()
        linkEnds.values.forEach { (i, j) -> climbs += i to j; climbs += j to i }
        repeat(n) {
            val u = (0 until n).filter { !done[it] }.minByOrNull { dist[it] } ?: return@repeat
            if (dist[u] == Double.MAX_VALUE) return@repeat
            done[u] = true
            for (v in 0 until n) {
                if (done[v]) continue
                val w = when {
                    (u to v) in climbs -> 0.06
                    nodes[u].seg == nodes[v].seg -> abs(nodes[u].x - nodes[v].x)
                    else -> continue
                }
                if (dist[u] + w < dist[v]) { dist[v] = dist[u] + w; prev[v] = u }
            }
        }
        if (dist[1] == Double.MAX_VALUE) return null
        val path = ArrayList<Int>()
        var c = 1
        while (c != -1) { path += c; c = prev[c] }
        path.reverse()
        val moves = ArrayList<Move>()
        for (i in 1 until path.size) {
            val u = nodes[path[i - 1]]; val v = nodes[path[i]]
            if ((path[i - 1] to path[i]) in climbs) moves += Move.Climb(v.seg.y < u.seg.y) else moves += Move.Walk(v.x)
        }
        return moves
    }

    /** How far [a] → [b] is over the layout's streets (climbs count a little): for planning an order. */
    fun pathCost(layout: Layout, a: Point, b: Point): Double {
        val moves = route(layout, a, b) ?: return 9.0
        var x = a.x
        var cost = 0.0
        for (m in moves) when (m) {
            is Move.Walk -> { cost += abs(m.x - x); x = m.x }
            is Move.Climb -> cost += 0.06
        }
        return cost
    }

    /**
     * Walk to [goal] over a known [layout]: read the party marker, plan, do the first move,
     * look again. [onStep] runs after every move and says whether a battle interrupted it.
     */
    fun walkLayout(ctx: Context, layout: Layout, goal: Point, onStep: () -> Boolean, tries: Int = 24): Boolean {
        var stuck = 0
        var nudge = 0
        var climbedAt: Point? = null
        var lastWalk: Double? = null
        var lastTarget = -1.0
        var overshot = false
        var gain = 1.0
        var known: Point? = null
        var expect: Point? = null
        var lastParty: Point? = null
        repeat(tries) {
            // where we are: worked out from the last undisturbed walk (no map needed), else read on the map
            val reckoned = known
            known = null
            val view = if (reckoned == null) look(ctx) { emptyList() } else null
            // chests already walked past show on the map in gold too: take the candidate on a street
            // nearest to where the party should be, not just the most marker-like blob
            val onStreets = view?.candidates?.filter { segOf(layout, it) != null }.orEmpty()
            val party = reckoned ?: expect?.let { e -> onStreets.minByOrNull { abs(it.x - e.x) + 2 * abs(it.y - e.y) } } ?: onStreets.firstOrNull()
            if (party == null) {
                MacroLog.i("party marker not on the known streets (saw ${view?.candidates?.take(3)})")
                // (a battle that started is fought first: the map can't open over it)
                if (onStep()) return@repeat
                if (++stuck >= 5) return false
                return@repeat
            }
            if (reckoned == null) pendingSpeed?.let { (x0, ms, d) ->
                // learn this map's walking speed from an undisturbed walk (a wall stops it early)
                val moved = abs(party.x - x0)
                if (ms > 400 && moved > 0.01 && moved < abs(d) * 1.6) speed = (0.5 * speed + 0.5 * moved / (ms / 1000.0)).coerceIn(0.01, 0.08)
                pendingSpeed = null
            }
            val moves = route(layout, party, goal) ?: return false.also { MacroLog.i("no route from $party to $goal") }
            MacroLog.i("at (${"%.3f".format(party.x)}, ${"%.3f".format(party.y)}) → (${"%.3f".format(goal.x)}, ${"%.3f".format(goal.y)}): ${moves.joinToString(" ")}")
            // a walk can be skipped when we're nearly there, but stairs only take the party standing on them
            val first = moves.withIndex().firstOrNull { (i, m) ->
                // (the shortest step moves ~0.01: once a step overshot the stairs, take them from here)
                val tol = if (moves.getOrNull(i + 1) is Move.Climb) (if (overshot) 0.012 else 0.005) else 0.012
                !(m is Move.Walk && abs(m.x - party.x) < tol)
            }?.value ?: return true
            if (lastParty != null && abs(lastParty!!.x - party.x) < 0.005 && abs(lastParty!!.y - party.y) < 0.01) stuck++ else stuck = 0
            if (stuck >= 5) return false.also { MacroLog.i("not moving any more at $party") }
            lastParty = party
            when (first) {
                is Move.Walk -> {
                    val d = first.x - party.x
                    // walking past the spot and back: shorter steps each time (and the stairs taken from closer)
                    if (lastWalk != null && lastWalk!! * d < 0 && abs(lastTarget - first.x) < 0.001) { overshot = true; gain *= 0.5 } else if (!overshot) gain = 1.0
                    lastWalk = d; lastTarget = first.x
                    val ms = (abs(d) / speed * 1000 * gain).toLong().coerceIn(40, 12000)
                    ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * (if (d > 0) 1 else -1), 0.65), ms)
                    ctx.wait(0.5)
                    expect = Point(first.x, party.y)
                    val battle = onStep()
                    if (!battle && gain == 1.0) pendingSpeed = Triple(party.x, ms, d)
                    // an undisturbed walk to a street's end stops at the wall: trust it without a look
                    // (stairs need the real position, so a walk before them is checked on the map)
                    val idx = moves.indexOf(first)
                    val seg = segOf(layout, party)
                    val toEnd = seg != null && (abs(first.x - seg.x1) < 0.006 || abs(first.x - seg.x2) < 0.006)
                    if (!battle && toEnd && moves.getOrNull(idx + 1) !is Move.Climb) {
                        known = Point(first.x, party.y)
                        if (idx == moves.size - 1) return true
                    }
                }
                is Move.Climb -> {
                    lastWalk = null; overshot = false; gain = 1.0
                    // the last flick here did nothing: shuffle a little either side of the stairs first
                    if (climbedAt?.let { abs(it.x - party.x) < 0.01 && abs(it.y - party.y) < ROW_TOL } == true) {
                        nudge++
                        hold(ctx, if (nudge % 2 == 1) 1.0 else -1.0, 0.003 * ((nudge + 1) / 2))
                    } else nudge = 0
                    climbedAt = party
                    expect = null
                    flick(ctx, first.up)
                    onStep()
                }
            }
        }
        MacroLog.i("gave up walking to $goal")
        return false
    }

    private var pendingSpeed: Triple<Double, Long, Double>? = null
}
