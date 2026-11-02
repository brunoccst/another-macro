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
    private val PANEL_CLOSE = Point(0.825, 0.10) // the X of an island's panel on the world map
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

    /** Places on islands that the Expand view lists apart from the continents (anothereden.wiki/w/Map). */
    private val REGIONS = mapOf(
        "Snake Bone Island" to listOf("Snake Tail Corabel", "Snake Spine Gabaraki", "Konium", "Konium Bridge", "Snake Liver Damaku",
            "Snake Neck Igoma", "Snake Dream", "Snake Skull Mescita"),
        "Astra Island" to listOf("Astra Pier", "Astra Forest", "Resttin", "Astra Mountains", "Astral Ruins", "Astrarium", "Astra Woodlands"),
        "Remulia" to listOf("Floating City - Remulia", "Corridor of Time Water", "Ark Exterior", "Ark Interior"),
    )
    private val REGION_NAMES = REGIONS.keys + listOf("Snake Bone", "Astra", "Ogres Spike")

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
        for (raw in rows) {
            // a found cat's row can carry the time left until the next clue ("4 hours left So, you have returned!")
            val row = raw.replace(Regex("""^\s*\d+\s*(hours?|minutes?|mins?)\s*left\s*""", RegexOption.IGNORE_CASE), "")
            if (FOUND.any { Ocr.similarity(row.take(it.length + 8), it) > 0.6 || Ocr.norm(row).startsWith(Ocr.norm(it)) }) continue
            val best = riddles.maxByOrNull { Ocr.similarity(row, it.text) }
            if (best != null && Ocr.similarity(row, best.text) >= 0.6) pending += best else unknown += row
        }
        return pending to unknown
    }

    // --- 2. world map travel ----------------------------------------------------
    fun travel(ctx: Context, clue: Clue): Boolean {
        val place = clue.location.substringBefore(" (").substringBefore(" –").substringBefore(" -").trim()
        // "Moonlight Forest (Forest Edge)" / "Nadara Volcano – Caldera Edge": the sub-destination
        val sub = Regex("""\(([^)]*)\)|[–-] (.+)$""").find(clue.location)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
        // the Map button sometimes ignores a tap (right after a hunt or a trip): retry until the world map shows
        var opened = false
        for (attempt in 1..3) {
            ensureField(ctx)
            ctx.tap(MAP_BUTTON); ctx.wait(2.5)
            if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) { opened = true; break }
            ctx.wait(1.5)
            if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) { opened = true; break }
            MacroLog.i("world map didn't open (try $attempt/3)")
        }
        if (!opened) return false
        ctx.tap(ERA.getValue(clue.era)); ctx.wait(2.0)
        // the place's own region (from the wiki's map list): islands show as a label on a continent's map
        val region = REGIONS.entries.firstOrNull { (_, places) -> places.any { Ocr.norm(it) == Ocr.norm(place) } }?.key
        if (searchAndGo(ctx, place, sub, region)) return true
        // not on this continent: try each one in the Expand view
        ctx.tap(EXPAND); ctx.wait(2.0)
        val expandText = ctx.readText()
        val continents = expandText.filter { l -> l.text.contains("Continent", true) || REGION_NAMES.any { Ocr.similarity(l.text, it) >= 0.8 } }
            .sortedByDescending { l -> region != null && Ocr.similarity(l.text, region) >= 0.8 }.map { it.text }
        MacroLog.i("regions (want ${region ?: "?"}): ${continents.joinToString().ifEmpty { "none read; saw: " + expandText.joinToString { it.text }.take(200) }}")
        for (name in continents) {
            if (ctx.readText().none { l -> l.text == name }) { ctx.tap(EXPAND); ctx.wait(2.0) }
            val line = ctx.readText().firstOrNull { it.text == name } ?: continue
            ctx.tap(line.center); ctx.wait(2.0)
            if (searchAndGo(ctx, place, sub, region)) return true
        }
        MacroLog.i("couldn't find $place on the map (not unlocked yet?)")
        ctx.tap(CLOSE); ctx.wait(1.5)
        return false
    }

    /**
     * Where to tap a label: its middle, unless the map is at its edge and the label still sits
     * against Expand (bottom right); then the end of the label away from that button.
     */
    private fun tapPoint(l: Ocr.Line): Point {
        val b = l.box
        if (!(l.center.x > 0.7 && l.center.y > 0.75)) return l.center
        // the label's frame reaches a little past its text
        val left = Point(b.x + b.w * 0.1, b.y + b.h * 0.3)
        val right = Point(minOf(b.x + b.w + 0.03, 0.915), b.y + b.h * 0.3)
        fun d(p: Point) = (p.x - EXPAND.x).let { it * it } + (p.y - EXPAND.y).let { it * it }
        return if (d(right) > d(left)) right else left
    }

    private fun nearButtons(p: Point) = (p.x > 0.7 && p.y > 0.75) || p.x > 0.85 || p.y < 0.28 || (p.x < 0.25 && p.y > 0.8)

    /** Pan around the visible map looking for [place]'s label; tap it and confirm the trip. */
    private fun isYes(l: Ocr.Line) = Ocr.norm(l.text) == "yes" && l.center.y > 0.4

    private fun searchAndGo(ctx: Context, place: String, sub: String, region: String? = null): Boolean {
        // look here first; then from the top-left corner, sweep the map row by row (snake order)
        val pans = ArrayList<Pair<Double, Double>>()
        pans += 0.0 to 0.0
        repeat(3) { pans += 0.6 to 0.4 }          // to the top-left corner (the drags stop at the edges)
        for (row in 0 until 3) {
            val step = if (row % 2 == 0) -0.4 else 0.4
            if (row > 0) pans += 0.0 to -0.35
            repeat(4) { pans += step to 0.0 }
        }
        for ((i, pan) in pans.withIndex()) {
            val (dx, dy) = pan
            val cornerDrag = i in 1..2              // no point reading while still on the way to the corner
            if (dx != 0.0 || dy != 0.0) {
                val from = Point(0.5 - dx / 2, 0.55 - dy / 2)
                ctx.drag(from, Point(from.x + dx, from.y + dy), 700); ctx.wait(1.0)
            }
            if (cornerDrag) continue
            var read = ctx.readText()
            MacroLog.i("map labels: " + read.filter { it.center.y > 0.12 }.joinToString { it.text }.take(300))
            fun placeLabel() = read.firstOrNull {
                it.center.y > 0.12 && (Ocr.norm(it.text) == Ocr.norm(place) || Ocr.similarity(it.text, place) >= 0.85)
            }
            var label = placeLabel()
            if (label == null && region != null) {
                // an island (e.g. Snake Bone Island) is one label on the continent map: it opens a panel of its places
                val island = read.firstOrNull {
                    it.center.y > 0.12 && (Ocr.similarity(it.text, region) >= 0.8 ||
                        (Ocr.norm(it.text).length >= 8 && Ocr.norm(region).startsWith(Ocr.norm(it.text))))
                }
                if (island != null) {
                    MacroLog.i("opening $region")
                    ctx.tap(island.center); ctx.wait(2.0)
                    read = ctx.readText()
                    label = placeLabel()
                    if (label == null) { MacroLog.i("$place not in the $region panel: ${read.joinToString { it.text }.take(200)}"); ctx.tap(PANEL_CLOSE); ctx.wait(1.5) }
                }
            }
            if (label == null) continue
            if (nearButtons(label.center)) {
                // too close to Expand / the era icons / the side buttons: drag it to the middle first
                ctx.drag(label.center, Point(0.45, 0.55), 700); ctx.wait(1.2)
                read = ctx.readText()
                label = placeLabel() ?: continue
            }
            ctx.tap(tapPoint(label)); ctx.wait(2.0)
            var lines = ctx.readText()
            if (lines.none { isYes(it) } && lines.none { it.text.startsWith("Moving to") }) {
                // some places open a panel of destinations (e.g. Unigan / Royal Theater / ...)
                val entries = lines.filter { it.center.x > 0.55 && it.center.y in 0.25..0.75 }
                val entry = (if (sub.isNotEmpty()) entries.maxByOrNull { Ocr.similarity(it.text, sub) }?.takeIf { Ocr.similarity(it.text, sub) > 0.7 } else null)
                    ?: entries.maxByOrNull { Ocr.similarity(it.text, place) }?.takeIf { Ocr.similarity(it.text, place) > 0.7 }
                if (entry != null) { ctx.tap(entry.center); ctx.wait(2.0); lines = ctx.readText() }
            }
            val yes = lines.firstOrNull { isYes(it) }
                ?: if (lines.any { it.text.startsWith("Moving to") }) Ocr.Line("Yes", Region(0.54, 0.53, 0.1, 0.08)) else null
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
        var lastRowY: Double? = null
        var nudge = 0
        repeat(16) {
            val read = readAreaMap(ctx) ?: return false.also { MacroLog.i("no feather on this map") }
            val feather = read.feather
            val party = read.party ?: return true.also { MacroLog.i("party marker not found on the map; hunting from here") }
            val dx = feather.x - party.x
            val dy = feather.y - party.y
            if (abs(dy) < ROW_TOL && abs(dx) < 0.05) return true
            if (abs(dy) < ROW_TOL) {
                // same street: one long hold, sized from the distance on the map
                hold(ctx, if (dx > 0) 1.0 else -1.0, abs(dx))
                return@repeat
            }
            val up = dy < 0
            val sameRowAsBefore = lastRowY?.let { abs(it - party.y) < ROW_TOL } ?: false
            lastRowY = party.y
            val conn = read.connectors(up)
                .minByOrNull { abs(it - party.x) + 0.5 * abs(it - feather.x) }
            if (conn == null) {
                MacroLog.i("no street connector ${if (up) "up" else "down"} on the map; walking toward the feather")
                hold(ctx, if (dx > 0) 1.0 else -1.0, abs(dx).coerceAtMost(0.08))
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
            fightIfBattle(ctx)
        }
        return true
    }

    /** Map units (fraction of the screen width) walked per second of holding. Measured in Unigan. */
    private val WALK_PER_S = 0.027
    private val ROW_TOL = 0.035

    private fun hold(ctx: Context, dir: Double, mapDist: Double) {
        val ms = (mapDist / WALK_PER_S * 1000).toLong().coerceIn(40, 12000)
        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), ms)
        ctx.wait(0.5)
    }

    /** A quick flick up or down: takes the path at a ground arrow (a slow hold only turns around). */
    private fun flick(ctx: Context, up: Boolean) {
        val (a, b) = if (up) 0.74 to 0.42 else 0.42 to 0.74
        ctx.drag(Point(0.5, a), Point(0.5, b), 150)
        ctx.wait(2.5)
    }

    private fun glide(ctx: Context, dx: Double, dy: Double, ms: Long) =
        ctx.drag(Point(0.5, 0.55), Point(0.5 + 0.06 * dx, 0.55 + 0.08 * dy), ms)

    /** What the area map shows: the feather, the party marker and the streets (light grey paths). */
    private class AreaMap(val feather: Point, val party: Point?, private val paths: Mat?, private val w: Int, private val h: Int) {
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
        fun release() { paths?.release() }
    }

    /** Open the area map (tap the minimap), find the feather and the party marker, close it. */
    private fun readAreaMap(ctx: Context): AreaMap? {
        lastMap?.release(); lastMap = null
        // right after a trip the map may not open yet (area banner, fade-in): try a few times
        repeat(3) { attempt ->
            readAreaMapOnce(ctx)?.let { lastMap = it; return it }
            MacroLog.i("feather not seen on the area map (try ${attempt + 1}/3)")
            ctx.wait(2.5)
        }
        return null
    }

    private var lastMap: AreaMap? = null

    private fun readAreaMapOnce(ctx: Context): AreaMap? {
        ctx.tap(MINIMAP); ctx.wait(2.0)
        val frame = ctx.refresh()
        val bgr = ctx.colorMat()
        ctx.tap(Point(0.5, 0.93)); ctx.wait(1.5)
        val feather = findMapFeather(ctx, frame) ?: run { bgr?.release(); return null }
        val party = bgr?.let { findPartyMarker(it, feather) }
        if (party == null && bgr != null) {
            // keep the map for debugging (app storage, not shared)
            Imgcodecs.imwrite(java.io.File(ctx.debugDir, "area-map.png").path, bgr)
        }
        val paths = bgr?.let { pathMask(it) }
        val w = bgr?.cols() ?: 0
        val h = bgr?.rows() ?: 0
        bgr?.release()
        return AreaMap(feather, party, paths, w, h)
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
            if (abs(cx - feather.x) < 0.02 && abs(cy - feather.y) < 0.04) continue
            val score = area * (1.0 - abs(aspect - 1.35))
            if (score > bestArea) { bestArea = score; best = Point(cx, cy) }
        }
        listOf(hsv, mask, labels, stats, cents).forEach { it.release() }
        if (best == null) MacroLog.i("map blobs (${bgr.cols()}x${bgr.rows()}): ${seen.joinToString(" ")}")
        return best
    }

    // --- 4. hunt -------------------------------------------------------------------
    /** Walk in short steps around here and tap the feather bubble; true once the book opens (found). */
    fun hunt(ctx: Context): Boolean {
        val ff = FeatherFinder.load(ctx)
        var dir = -1.0
        var pos = 0
        repeat(60) {
            fightIfBattle(ctx) // first: it takes its own screenshots
            var f1 = ctx.refresh()
            if (ctx.find("scratchbook_header") != null) return collect(ctx)
            if (ctx.find("field_menu_button") == null) { leaveTalk(ctx); f1 = ctx.refresh() }
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

    /**
     * Walking bumped into someone and a conversation opened: decline any offer
     * ("Do not participate", "No", ...) and tap through the lines until the field is back.
     */
    private fun leaveTalk(ctx: Context) {
        val decline = listOf("do not", "don t", "no", "cancel", "leave", "not now", "never mind")
        repeat(8) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null || ctx.find("scratchbook_header") != null) return
            val lines = ctx.readText()
            val no = lines.firstOrNull { l -> val n = Ocr.norm(l.text); decline.any { n == it || n.startsWith("$it ") } }
            if (no != null) {
                MacroLog.i("a conversation opened while walking: choosing '${no.text}'")
                ctx.tapExact(no.center)
            } else {
                ctx.tap(Point(0.5, 0.85)) // next line of the conversation
            }
            ctx.wait(1.5)
        }
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
            if (ctx.find("title_continue") != null) { ctx.tap(Point(0.5, 0.75)); ctx.wait(6.0) }
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
