package io.github.brunoccst.anothermacro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * The whole daily Cat Scratchbook, unattended:
 *  1. open Diary → Cat Scratchbook and read today's clues with on-device OCR,
 *     matching them against the wiki's clue table (riddles.yaml);
 *  2. travel to the clue's place on the World Map (era → find the place's label by
 *     OCR, panning and switching continents as needed → Yes);
 *  3. steer toward the feather on the area map (party marker vs feather icon);
 *  4. hunt: walk in short steps and tap the cat's feather bubble;
 *  5. back to the book (a cat that ran away leaves a new clue) until all are found.
 */
object CatDaily {
    data class Clue(val key: String, val text: String, val location: String, val era: String)

    private val DIARY = Point(0.329, 0.922)
    private val MAP_BUTTON = Point(0.385, 0.917)
    private val CLOSE = Point(0.88, 0.03)
    private val MINIMAP = Point(0.917, 0.12)
    private val ERA = mapOf("Antiquity" to Point(0.1525, 0.185), "Present" to Point(0.2283, 0.185), "Future" to Point(0.3029, 0.185))
    private val EXPAND = Point(0.844, 0.935)
    // "found" messages that replace a clue once its cat is back home
    private val FOUND = listOf("So, you have returned", "Nya! I did think you would be returning", "You took so long", "I've been beside myself")

    fun run(ctx: Context): String {
        val riddles = loadRiddles(ctx)
        val failed = HashSet<String>()
        val done = ArrayList<String>()
        repeat(8) {
            ensureField(ctx)
            val (pending, unknown) = readClues(ctx, riddles)
            MacroLog.i("clues: " + (pending.joinToString { "${it.location} (${it.era})" }.ifEmpty { "none left" }) +
                if (unknown.isNotEmpty()) "; unreadable: ${unknown.joinToString(" | ")}" else "")
            val next = pending.firstOrNull { it.key !in failed && supported(it) }
            if (next == null) {
                val left = pending.filter { it.key !in done }
                return if (left.isEmpty() && unknown.isEmpty()) "all cats found today (${done.size} this run)"
                else "found ${done.size}; still open: ${left.joinToString { it.location }}${if (unknown.isNotEmpty()) "; unread clues: ${unknown.size}" else ""}"
            }
            MacroLog.i("going for the cat in ${next.location} (${next.era})")
            val ok = travel(ctx, next) && steer(ctx) && hunt(ctx)
            if (ok) { done += next.key; ctx.count("cats_found") } else { failed += next.key; ctx.count("cats_missed") }
        }
        return "stopped after 8 rounds; found ${done.size}"
    }

    private fun supported(c: Clue) = c.era in ERA && !c.location.contains("Enter from") && !c.location.contains("quick destination")

    // --- 1. clues ----------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    fun loadRiddles(ctx: Context): List<Clue> = try {
        val text = ctx.assets.open("templates/cat_scratchbook/riddles.yaml").bufferedReader().use { it.readText() }
        val data = Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Map<String, Any?>>
        data.map { (k, v) -> Clue(k, v["text"].toString(), v["location"].toString(), v["era"]?.toString() ?: "") }
    } catch (e: Exception) {
        MacroLog.e("riddles.yaml unreadable", e); emptyList()
    }

    /** Open the book, OCR the three rows: (clues still to find, rows that matched nothing). */
    fun readClues(ctx: Context, riddles: List<Clue>): Pair<List<Clue>, List<String>> {
        ctx.tap(DIARY); ctx.wait(2.5)
        ctx.refresh()
        if (ctx.find("scratchbook_header") == null) { ctx.wait(1.5); ctx.refresh() }
        val lines = ctx.readText().filter { it.center.x in 0.33..0.72 && it.center.y in 0.15..0.62 }
        ctx.tap(CLOSE); ctx.wait(1.5)
        val rows = listOf(0.15..0.30, 0.30..0.45, 0.45..0.62).map { band ->
            lines.filter { it.center.y in band }.sortedBy { it.box.y }.joinToString(" ") { it.text }
        }.filter { it.isNotBlank() }
        val pending = ArrayList<Clue>()
        val unknown = ArrayList<String>()
        for (row in rows) {
            if (FOUND.any { Ocr.similarity(row.take(it.length + 8), it) > 0.6 || Ocr.norm(row).startsWith(Ocr.norm(it)) }) continue
            val best = riddles.maxByOrNull { Ocr.similarity(row, it.text) }
            if (best != null && Ocr.similarity(row, best.text) >= 0.6) pending += best else unknown += row
        }
        return pending to unknown
    }

    // --- 2. world map travel ----------------------------------------------------
    fun travel(ctx: Context, clue: Clue): Boolean {
        val place = clue.location.substringBefore(" (").substringBefore(" –").substringBefore(" -").trim()
        ctx.tap(MAP_BUTTON); ctx.wait(2.5)
        if (ctx.readText().none { Ocr.norm(it.text) == "world map" }) { ctx.wait(1.5) }
        ctx.tap(ERA.getValue(clue.era)); ctx.wait(2.0)
        if (searchAndGo(ctx, place)) return true
        // not on this continent: try each one in the Expand view
        ctx.tap(EXPAND); ctx.wait(2.0)
        val continents = ctx.readText().filter { it.text.contains("Continent", ignoreCase = true) }.map { it.text }
        for (name in continents) {
            if (ctx.readText().none { it.text.contains("Continent", true) }) { ctx.tap(EXPAND); ctx.wait(2.0) }
            val line = ctx.readText().firstOrNull { it.text == name } ?: continue
            ctx.tap(line.center); ctx.wait(2.0)
            if (searchAndGo(ctx, place)) return true
        }
        MacroLog.i("couldn't find $place on the map (not unlocked yet?)")
        ctx.tap(CLOSE); ctx.wait(1.5)
        return false
    }

    /** Pan around the visible map looking for [place]'s label; tap it and confirm the trip. */
    private fun searchAndGo(ctx: Context, place: String): Boolean {
        val pans = listOf(0.0 to 0.0, -0.35 to 0.0, 0.0 to -0.35, 0.35 to 0.0, 0.35 to 0.0, 0.0 to 0.35, 0.0 to 0.35, -0.35 to 0.0, -0.35 to 0.0)
        for ((dx, dy) in pans) {
            if (dx != 0.0 || dy != 0.0) { ctx.drag(Point(0.5, 0.55), Point(0.5 + dx, 0.55 + dy), 700); ctx.wait(1.0) }
            val label = ctx.readText().firstOrNull {
                it.center.y > 0.12 && (Ocr.norm(it.text) == Ocr.norm(place) || Ocr.similarity(it.text, place) >= 0.85)
            } ?: continue
            ctx.tap(label.center); ctx.wait(2.0)
            val yes = ctx.readText().firstOrNull { Ocr.norm(it.text) == "yes" }
            if (yes == null) {
                MacroLog.i("$place: no travel dialog (already here, or not a travel point)")
                ctx.tap(CLOSE); ctx.wait(1.5)
                return true // probably already there
            }
            ctx.tap(yes.center)
            ctx.count("trips")
            return waitForField(ctx, 30)
        }
        return false
    }

    // --- 3. steer on the area map -------------------------------------------------
    /** Walk toward the feather shown on the area map. True once the party is close to it. */
    fun steer(ctx: Context): Boolean {
        var verticalTries = 0
        var lastParty: Point? = null
        repeat(14) {
            val (feather, party) = readAreaMap(ctx) ?: return false.also { MacroLog.i("no feather on this map") }
            if (party == null) { MacroLog.i("party marker not found on the map; hunting from here"); return true }
            val dx = feather.x - party.x
            val dy = feather.y - party.y
            if (abs(dy) < 0.045 && abs(dx) < 0.05) return true
            if (abs(dy) < 0.045) {
                // same street: walk straight toward it (time scaled by the distance on the map)
                glide(ctx, if (dx > 0) 1.0 else -1.0, 0.0, (abs(dx) * 9000).toLong().coerceIn(400, 7000))
            } else {
                val moved = lastParty?.let { abs(it.y - party.y) > 0.02 } ?: true
                if (!moved) verticalTries++
                if (verticalTries % 2 == 1) {
                    // the stairs/path to the other street isn't here: step along the street first
                    glide(ctx, if (dx > 0) 1.0 else -1.0, 0.0, 900)
                }
                glide(ctx, 0.0, if (dy > 0) 1.0 else -1.0, 1600)
            }
            lastParty = party
            fightIfBattle(ctx)
        }
        return true
    }

    private fun glide(ctx: Context, dx: Double, dy: Double, ms: Long) =
        ctx.drag(Point(0.5, 0.55), Point(0.5 + 0.06 * dx, 0.55 + 0.08 * dy), ms)

    /** Open the area map (tap the minimap), find the feather and the party marker, close it. */
    private fun readAreaMap(ctx: Context): Pair<Point, Point?>? {
        ctx.tap(MINIMAP); ctx.wait(2.0)
        val frame = ctx.refresh()
        val bgr = ctx.colorMat()
        ctx.tap(Point(0.5, 0.93)); ctx.wait(1.5)
        val feather = findMapFeather(ctx, frame) ?: run { bgr?.release(); return null }
        val party = bgr?.let { findPartyMarker(it, feather) }
        bgr?.release()
        return feather to party
    }

    private var mapFeather: Mat? = null

    private fun findMapFeather(ctx: Context, frame: Frame): Point? {
        val t = mapFeather ?: ctx.assets.open("templates/cat_scratchbook/map_feather_icon.png").use { it.readBytes() }
            .let { Imgcodecs.imdecode(MatOfByte(*it), Imgcodecs.IMREAD_GRAYSCALE) }.also { mapFeather = it }
        var best = 0.0
        var at: Point? = null
        for (k in listOf(0.8, 1.0, 1.25)) {
            val s = Mat(); Imgproc.resize(t, s, Size(0.0, 0.0), k, k)
            val r = Mat(); Imgproc.matchTemplate(frame.gray, s, r, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(r)
            if (mm.maxVal > best) {
                best = mm.maxVal
                at = Point((mm.maxLoc.x + s.cols() / 2.0) / frame.width, (mm.maxLoc.y + s.rows() / 2.0) / frame.height)
            }
            s.release(); r.release()
        }
        return if (best >= 0.75) at else null
    }

    /** The party marker: a gold-rimmed eye, wider than tall, pointing the way the party faces. */
    private fun findPartyMarker(bgr: Mat, feather: Point): Point? {
        val hsv = Mat(); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat(); Core.inRange(hsv, Scalar(12.0, 40.0, 140.0), Scalar(35.0, 200.0, 255.0), mask)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Mat.ones(5, 5, org.opencv.core.CvType.CV_8U))
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cents)
        val w = bgr.cols().toDouble(); val h = bgr.rows().toDouble()
        var best: Point? = null
        var bestArea = 0.0
        for (k in 1 until n) {
            val bw = stats.get(k, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(k, Imgproc.CC_STAT_HEIGHT)[0]
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val cx = cents.get(k, 0)[0] / w
            val cy = cents.get(k, 1)[0] / h
            val aspect = bw / bh
            if (area < 600 || aspect < 1.2 || aspect > 1.9 || bw < 40 || bw > 110) continue
            if (abs(cx - feather.x) < 0.02 && abs(cy - feather.y) < 0.04) continue
            if (area > bestArea) { bestArea = area; best = Point(cx, cy) }
        }
        listOf(hsv, mask, labels, stats, cents).forEach { it.release() }
        return best
    }

    // --- 4. hunt -------------------------------------------------------------------
    /** Walk in short steps around here and tap the feather bubble; true once the book opens (found). */
    fun hunt(ctx: Context): Boolean {
        val ff = FeatherFinder.load(ctx)
        var dir = -1.0
        var pos = 0
        repeat(60) {
            val f1 = ctx.refresh()
            if (ctx.find("scratchbook_header") != null) return collect(ctx)
            fightIfBattle(ctx)
            val (score, p1) = ff.find(f1)
            if (score >= 0.78) {
                val t1 = System.currentTimeMillis()
                val (score2, p2) = ff.find(ctx.refresh())
                val target = if (score2 >= 0.78) {
                    val vx = (p2.x - p1.x) / ((System.currentTimeMillis() - t1).coerceAtLeast(50) / 1000.0)
                    Point((p2.x + vx * 0.4).coerceIn(0.02, 0.98), p2.y)
                } else p1
                ctx.tapExact(target)
                ctx.count("bubble_taps")
                ctx.wait(3.0)
                ctx.refresh()
                if (ctx.find("scratchbook_header") != null) return collect(ctx)
                return@repeat
            }
            if (abs(pos + dir) > 6) dir = -dir
            glide(ctx, dir, 0.0, 300)
            pos += dir.toInt()
            ctx.wait(0.4)
        }
        MacroLog.i("no feather bubble near the cat's spot")
        return false
    }

    /** Clear the reward banners over the book and close it. */
    private fun collect(ctx: Context): Boolean {
        repeat(4) { ctx.tap(Point(0.5, 0.65)); ctx.wait(1.2) }
        ctx.tap(CLOSE); ctx.wait(1.5)
        return true
    }

    // --- helpers -----------------------------------------------------------------
    private fun ensureField(ctx: Context) {
        if (!waitForField(ctx, 20)) ctx.stop("not on the field (open the game on any map first)")
    }

    private fun waitForField(ctx: Context, seconds: Int): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) return true
            fightIfBattle(ctx)
            ctx.wait(1.0)
        }
        return false
    }

    /** A random encounter on the way: auto/attack until it's over. */
    private fun fightIfBattle(ctx: Context) {
        var guard = 0
        while (guard++ < 60) {
            ctx.refresh()
            when {
                ctx.find("battle_attack_button") != null ->
                    ctx.tap(if (ctx.find("battle_auto_button") != null) "battle_auto_button" else "battle_attack_button")
                ctx.find("result_banner") != null -> ctx.tap(Point(0.5, 0.75))
                ctx.find("battle_hud") != null -> {}
                else -> return
            }
            ctx.wait(1.0)
        }
    }
}
