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
 * Port of cat-scratchbook.
 * read: open Diary → Cat Scratchbook, recognise today's clues, report where each cat is.
 * hunt: on the map where the cat is, walk in short pulses, find the cat's feather
 * bubble and tap it (leading the cat's motion), clear the rewards, stop.
 */
class CatScratchbook : Scenario() {
    override val name = "cat-scratchbook"
    override val description = "read: report today's cat clues and where they are; hunt: find the cat on this map."
    override val templateFolders = listOf("cat_scratchbook")
    override val params = linkedMapOf(
        "mode" to Param("read", "read = report today's clues; hunt = find the cat on the map you're on"),
        "first_walk" to Param("-1", "hunt: direction of the first walk, -1 = left, 1 = right"),
        "pulse_ms" to Param("300", "hunt: length of each short walk"),
        "max_walks" to Param("120", "hunt: give up after this many walks"),
        "span" to Param("8", "hunt: walks to either side of the start before turning"),
    )
    override val screens = listOf("scratchbook", "battle_result", "skill_list", "battle_command", "battle", "field")
    override val unknownTimeoutS = 20.0

    private var opened = false
    private var report: String? = null
    private var dir = -1
    private var walks = 0
    private var still = 0
    private var pos = 0
    private var found = false
    private var lastView: Mat? = null
    private var feather: FeatherFinder? = null

    override fun setup(ctx: Context) {
        dir = ctx.int("first_walk")
        if (ctx.str("mode") == "hunt") feather = FeatherFinder.load(ctx)
    }

    override fun onScreen(ctx: Context, screen: String) {
        val hunt = ctx.str("mode") == "hunt"
        when (screen) {
            "field" -> if (hunt) huntField(ctx) else readField(ctx)
            "scratchbook" -> if (hunt) {
                // finding the cat opens the book with reward banners: clear them, close it
                if ((ctx.stats["bubble_taps"] ?: 0) > 0 && !found) { found = true; ctx.count("cats_found") }
                repeat(3) { ctx.tap(Point(0.5, 0.65)); ctx.wait(1.2) }
                ctx.tap(CLOSE_BUTTON); ctx.wait(1.5)
            } else {
                if (report == null) report = readClues(ctx)
                ctx.tap(CLOSE_BUTTON); ctx.wait(1.5)
            }
            // a random encounter while hunting outside a town
            "battle_command" -> {
                lastView?.release(); lastView = null
                ctx.tap(if (ctx.find("battle_auto_button") != null) "battle_auto_button" else "battle_attack_button")
                ctx.wait(1.0)
            }
            "skill_list" -> { if (ctx.find("battle_attack_button") != null) ctx.tap("battle_attack_button") else ctx.back(); ctx.wait(0.8) }
            "battle_result" -> { ctx.tap(Point(0.5, 0.75)); ctx.wait(0.8) }
        }
    }

    private fun readField(ctx: Context) {
        report?.let { ctx.stop(it) }
        if (!opened) { opened = true; ctx.tap(DIARY_BUTTON); ctx.wait(2.0) }
    }

    private fun readClues(ctx: Context): String {
        val riddles = loadRiddles(ctx)
        val lib = ctx.vision.library.templates
        val known = riddles.keys.filter { lib[it]?.available == true && ctx.find(it) != null }
        ctx.stats["known_riddles"] = known.size
        val lines = known.map { name ->
            val r = riddles.getValue(name)
            val where = (r["location"] as? String ?: "location unknown") + ((r["era"] as? String)?.let { " ($it)" } ?: "")
            "$where: ${r["text"] ?: name}"
        }.toMutableList()
        val unknown = (3 - known.size).coerceAtLeast(0)
        ctx.stats["unknown_riddles"] = unknown
        if (unknown > 0) lines += "$unknown clue(s) not recognised yet"
        return lines.joinToString("; ").ifEmpty { "no clues recognised" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadRiddles(ctx: Context): Map<String, Map<String, Any?>> = try {
        val text = ctx.assets.open("templates/cat_scratchbook/riddles.yaml").bufferedReader().use { it.readText() }
        (Load(LoadSettings.builder().build()).loadFromString(text) as? Map<String, Map<String, Any?>>) ?: emptyMap()
    } catch (e: java.io.IOException) {
        emptyMap()
    }

    private fun huntField(ctx: Context) {
        if (found) ctx.stop("found the scratchbook cat")
        val ff = feather ?: return
        val f1 = ctx.frame ?: return
        val (score, p1) = ff.find(f1)
        if (score >= FEATHER_MIN) {
            // the bubble rides on the cat, which keeps walking: measure its speed from
            // a second frame and tap where it will be when the tap lands
            val t1 = System.currentTimeMillis()
            val (score2, p2) = ff.find(ctx.refresh())
            val target = if (score2 >= FEATHER_MIN) {
                val vx = (p2.x - p1.x) / ((System.currentTimeMillis() - t1).coerceAtLeast(50) / 1000.0)
                Point((p2.x + vx * TAP_LATENCY_S).coerceIn(0.02, 0.98), p2.y)
            } else p1
            ctx.tapExact(target)
            ctx.count("bubble_taps")
            ctx.wait(2.0)
            return
        }
        if (walks >= ctx.int("max_walks")) ctx.stop("no scratchbook cat found after $walks walks")
        if (!moved(ctx)) {
            still++
            if (still >= 2) { dir = -dir; still = 0 } // hit a wall: turn around
        } else still = 0
        val span = ctx.int("span")
        if (span > 0 && kotlin.math.abs(pos + dir) > span) dir = -dir
        ctx.drag(Point(0.5, 0.555), Point(0.5 + 0.0625 * dir, 0.555), ctx.int("pulse_ms").toLong())
        walks++; pos += dir
        ctx.wait(0.4)
    }

    private fun moved(ctx: Context): Boolean {
        val g = ctx.frame?.gray ?: return true
        val roi = g.submat(Rect(0, (g.rows() * 0.25).toInt(), (g.cols() * 0.8).toInt(), (g.rows() * 0.55).toInt()))
        val small = Mat()
        Imgproc.resize(roi, small, Size(160.0, 60.0))
        roi.release()
        val prev = lastView
        lastView = small
        if (prev == null) return true
        val diff = Mat()
        Core.absdiff(small, prev, diff)
        val m = Core.mean(diff).`val`[0]
        diff.release(); prev.release()
        return m > 4.0
    }

    companion object {
        val DIARY_BUTTON = Point(0.329, 0.922)
        val CLOSE_BUTTON = Point(0.88, 0.03)
        const val FEATHER_MIN = 0.78
        const val TAP_LATENCY_S = 0.4 // in-app capture is faster than adb's
    }
}

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
