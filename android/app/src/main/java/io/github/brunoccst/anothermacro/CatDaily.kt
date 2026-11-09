package io.github.brunoccst.anothermacro

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * The whole daily Cat Scratchbook, unattended:
 *  1. open Diary → Cat Scratchbook and read today's clues with on-device OCR,
 *     matching them against the wiki's clue table (riddles.yaml);
 *  2. travel to the clue's place on the World Map: the fixed way from point zero where
 *     cat_spots.yaml has it (CatRoute), else era → find the place's label by OCR,
 *     panning and switching continents as needed → Yes;
 *  3. walk the spot's fixed path to the cat's start (CatRoute), else steer toward the
 *     feather on the area map (party marker vs feather icon);
 *  4. hunt: walk in short steps and tap the cat's feather bubble;
 *  5. back to the book (a cat that ran away leaves a new clue) until all are found.
 */
object CatDaily {
    data class Clue(val key: String, val text: String, val location: String, val era: String)

    private val DIARY = Point(0.329, 0.922)
    private val MAP_BUTTON = Point(0.385, 0.917)
    private val CLOSE = Point(0.88, 0.03)
    private val PANEL_CLOSE = Point(0.825, 0.10) // the X of an island's panel on the world map
    private val ERA = mapOf("Antiquity" to Point(0.1525, 0.185), "Present" to Point(0.2283, 0.185), "Future" to Point(0.3029, 0.185))
    private val EXPAND = Point(0.844, 0.935)
    // "found" messages that replace a clue once its cat is back home
    private val FOUND = listOf("So, you have returned", "Nya! I did think you would be returning", "You took so long", "I've been beside myself")

    /** The cat being looked for now ("cat 2 · Baruoki (Present)"), in front of each live log line. */
    private var cat = ""

    /** Log a step and show it on the live log. */
    private fun step(text: String) = MacroLog.status("cat-scratchbook: " + (if (cat.isEmpty()) "" else "$cat: ") + text)

    /** Show a step's progress on the live log only (no log line each time). */
    private fun progress(text: String) = MacroLog.show("cat-scratchbook: " + (if (cat.isEmpty()) "" else "$cat: ") + text)

    fun run(ctx: Context): String {
        cat = ""
        val riddles = loadRiddles(ctx)
        val failed = HashSet<String>()
        val done = ArrayList<String>()
        val skipped = HashSet<String>()
        repeat(8) { round ->
            cat = ""
            step(if (round == 0) "reading today's clues" else "reading the clues again (${done.size} found so far)")
            ensureField(ctx)
            val (pending, unknown) = readClues(ctx, riddles)
            MacroLog.i("clues: " + (pending.joinToString { "${it.location} (${it.era})" }.ifEmpty { "none left" }) +
                if (unknown.isNotEmpty()) "; unreadable: ${unknown.joinToString(" | ")}" else "")
            for (c in pending) if (!supported(c) && skipped.add(c.key)) {
                MacroLog.i("skipping the cat in ${c.location} (${c.era.ifEmpty { "no era" }}): " +
                    if (c.era !in ERA) "its era isn't on the world map's era buttons" else "it can't be reached from the world map (not supported yet)")
                ctx.count("cats_skipped")
            }
            // a cat on a map past the travel point (cat_spots.yaml walk_to) isn't reachable yet: left open
            val next = pending.firstOrNull { it.key !in failed && supported(it) && CatRoute.spot(ctx, it.location)?.walkTo == null }
            if (next == null) {
                val left = pending.filter { it.key !in done }
                return if (left.isEmpty() && unknown.isEmpty()) "all cats found today (${done.size} this run)"
                else "found ${done.size}; still open: ${left.joinToString { it.location }}${if (unknown.isNotEmpty()) "; unread clues: ${unknown.size}" else ""}"
            }
            cat = "cat ${done.size + failed.size + 1} · ${next.location} (${next.era})"
            step("going there (clue: \"${next.text.take(60)}\")")
            val miss = go(ctx, next)
            if (miss == null) {
                done += next.key; ctx.count("cats_found")
                step("found! (${done.size} found this run)")
            } else {
                failed += next.key; ctx.count("cats_missed")
                step("missed: $miss")
            }
        }
        cat = ""
        return "stopped after 8 rounds; found ${done.size}"
    }

    /**
     * Travel to the clue's cat and find it: the fixed way (cat_spots.yaml, CatRoute) where the spot
     * is measured, the searching way where it isn't or where the screen doesn't match the data.
     */
    /** null when found, else why it was missed. */
    private fun go(ctx: Context, clue: Clue): String? {
        val spot = CatRoute.spot(ctx, clue.location)
        val fight = { Battle.fight(ctx) { Battle.Setup.AUTO }.also { if (it == Battle.Outcome.LOST) ctx.stop("party was defeated") } == Battle.Outcome.WON }
        val arrived = if (spot != null && spot.fixedTravel) {
            CatRoute.travel(ctx, spot) || run {
                if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) closeMap(ctx)
                travel(ctx, clue)
            }
        } else travel(ctx, clue)
        if (!arrived) return "couldn't travel there"
        if (spot != null && spot.fixedWalk) {
            ctx.count("fixed_routes")
            step("following the fixed route to the cat")
            // the cat can be next to the arrival (Ratle: its bubble shows at once): look before moving
            if (hunt(ctx, walk = {}, tries = 1)) return null
            val range = CatRoute.walk(ctx, spot, fight)
            if (range != null && hunt(ctx, CatRoute.sweeper(ctx, spot, range, fight), tries = 16)) return null
            MacroLog.i("${clue.location}: no cat on the fixed route${if (range == null) " (left the path)" else " after sweeping its range"}: steering by the map")
            ctx.count("fixed_route_fallbacks")
        } else {
            MacroLog.i("${clue.location}: no fixed route yet (`another-macro cat-routes --todo`)")
        }
        return when {
            !steer(ctx) -> "couldn't walk to the feather on the area map"
            !hunt(ctx) -> "no cat found near the feather"
            else -> null
        }
    }

    /** Places on islands that the Expand view lists apart from the continents (anothereden.wiki/w/Map). */
    private val REGIONS = mapOf(
        "Snake Bone Island" to listOf("Snake Tail Corabel", "Snake Spine Gabaraki", "Konium", "Konium Bridge", "Snake Liver Damaku",
            "Snake Neck Igoma", "Snake Dream", "Snake Skull Mescita"),
        "Astra Island" to listOf("Astra Pier", "Astra Forest", "Resttin", "Astra Mountains", "Astral Ruins", "Astrarium", "Astra Woodlands"),
        "Remulia" to listOf("Floating City - Remulia", "Corridor of Time Water", "Ark Exterior", "Ark Interior"),
    )
    private val REGION_NAMES = REGIONS.keys + listOf("Snake Bone", "Astra", "Ogres Spike")

    /** The island a place is on (its label on the continent map opens a panel of its places), or null. */
    internal fun regionOf(place: String) = REGIONS.entries.firstOrNull { (_, places) -> places.any { Ocr.norm(it) == Ocr.norm(place) } }?.key

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
        if (!openWorldMap(ctx, clue.era)) return false
        // the place's own region (from the wiki's map list): islands show as a label on a continent's map
        val region = regionOf(place)
        // learned before: go straight to its continent
        val known = KnownPlaces.continent(ctx, place, clue.era)
        if (known != null) {
            MacroLog.i("$place: known to be on $known")
            step("looking for $place on $known")
            ctx.tap(EXPAND); ctx.wait(2.0)
            val line = ctx.readText().firstOrNull { Ocr.similarity(it.text, known) >= 0.8 }
            if (line != null) {
                ctx.tap(line.center); ctx.wait(2.0)
                if (searchAndGo(ctx, place, sub, region)) return true
            }
            MacroLog.i("$place wasn't where it was last time; searching")
        }
        step("looking for $place on the map shown")
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
            step("looking for $place on $name")
            ctx.tap(line.center); ctx.wait(2.0)
            if (searchAndGo(ctx, place, sub, region)) {
                KnownPlaces.learn(ctx, place, clue.era, name)
                return true
            }
        }
        MacroLog.i("couldn't find $place on the map (not unlocked yet?)")
        ctx.tap(CLOSE); ctx.wait(1.5)
        return false
    }

    /** Open the World Map on [era]'s tab. The Map button sometimes ignores a tap (right after a hunt or a trip): retried. */
    internal fun openWorldMap(ctx: Context, era: String): Boolean {
        var opened = false
        step("opening the world map")
        for (attempt in 1..3) {
            ensureField(ctx)
            ctx.tap(MAP_BUTTON); ctx.wait(2.5)
            if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) { opened = true; break }
            ctx.wait(1.5)
            if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) { opened = true; break }
            MacroLog.i("world map didn't open (try $attempt/3)")
        }
        if (!opened) return false.also { MacroLog.i("the world map never opened") }
        step("on the world map: $era")
        tapEra(ctx, era)
        return true
    }

    internal fun tapEra(ctx: Context, era: String) { ctx.tap(ERA.getValue(era)); ctx.wait(2.0) }

    /** Expand → tap [continent]'s entry (by its text). */
    internal fun openContinent(ctx: Context, continent: String): Boolean {
        ctx.tap(EXPAND); ctx.wait(2.0)
        val line = ctx.readText().firstOrNull { Ocr.similarity(it.text, continent) >= 0.8 } ?: return false
        ctx.tap(line.center); ctx.wait(2.0)
        return true
    }

    /**
     * Point zero (the user): the World Map on the Present era with Baruoki's label on screen. Baruoki
     * is the game's first map, so every account has it (other places, and even the Expand button,
     * only show once the story unlocks them). It sits by the map's west edge: the Present tab
     * opens with it at about (0.185, 0.509) and a drag doesn't move the map further (phone,
     * 2026-11-03); after a Present trip elsewhere the map is dragged west until it shows.
     */
    internal fun pointZero(ctx: Context): Boolean {
        if (!openWorldMap(ctx, "Present")) return false
        fun baruoki() = ctx.readText().firstOrNull { it.center.y > 0.12 && Ocr.similarity(it.text, "Baruoki") >= 0.8 }
        var label = baruoki()
        if (label == null && openContinent(ctx, "Migleina Continent")) label = baruoki()
        repeat(4) {
            if (label != null) return@repeat
            ctx.drag(Point(0.3, 0.55), Point(0.8, 0.55), 700); ctx.wait(1.0)
            label = baruoki()
        }
        val at = label ?: run { MacroLog.i("point zero: no Baruoki label on the Present map"); return false }
        MacroLog.i("point zero: Baruoki at (${"%.3f".format(at.center.x)}, ${"%.3f".format(at.center.y)})")
        return true
    }

    internal fun closeMap(ctx: Context) { ctx.tap(CLOSE); ctx.wait(1.5) }

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

    internal fun searchAndGo(ctx: Context, place: String, sub: String, region: String? = null): Boolean {
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
            return goVia(ctx, label, place, sub)
        }
        return false
    }

    /** Tap a place's [label] on the world map, pick [sub] in its destinations panel if one opens, confirm the trip. */
    internal fun goVia(ctx: Context, label: Ocr.Line, place: String, sub: String): Boolean {
        run {
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
            step("travelling to $place${if (sub.isNotEmpty()) " ($sub)" else ""}")
            return waitForField(ctx, 30).also { if (!it) MacroLog.i("the field didn't come back after the trip") }
        }
    }

    // --- 3. steer on the area map -------------------------------------------------
    /** Walk toward the feather shown on the area map (shared map walking: AreaNav). True once close to it. */
    fun steer(ctx: Context): Boolean {
        step("walking toward the feather on the area map")
        return AreaNav.steer(ctx, look = {
            // right after a trip the map may not open yet (area banner, fade-in): try a few times
            var view: AreaNav.View? = null
            for (attempt in 1..3) {
                val v = AreaNav.look(ctx) { f -> AreaNav.findIcons(ctx, f, "templates/cat_scratchbook/map_feather_icon.png").take(1) }
                if (v.targets.isNotEmpty()) { view = v; break }
                MacroLog.i("feather not seen on the area map (try $attempt/3)")
                ctx.wait(2.5)
            }
            if (view == null) MacroLog.i("no feather on this map")
            view
        }, target = { it.targets.firstOrNull() }, onStep = { fightIfBattle(ctx) })
    }

    private fun glide(ctx: Context, dx: Double, dy: Double, ms: Long) =
        ctx.drag(Point(0.5, 0.55), Point(0.5 + 0.06 * dx, 0.55 + 0.08 * dy), ms)

    // --- 4. hunt -------------------------------------------------------------------
    /**
     * Walk in short steps around here and tap the feather bubble; true once the book opens (found).
     * [walk] (i-th walk) replaces the pulses left and right, e.g. CatRoute's sweep of the cat's drift range.
     */
    fun hunt(ctx: Context, walk: ((Int) -> Unit)? = null, tries: Int = 60): Boolean {
        val ff = FeatherFinder.load(ctx)
        var dir = -1.0
        var pos = 0
        var taps = 0
        step("looking for the cat's feather bubble")
        repeat(tries) { i ->
            progress("looking for the cat's feather bubble (walk ${i + 1}/$tries${if (taps > 0) ", $taps taps" else ""})")
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
                taps++
                MacroLog.i("tapped the feather bubble (tap $taps)")
                ctx.wait(3.0)
                ctx.refresh()
                if (ctx.find("scratchbook_header") != null) return collect(ctx)
                return@repeat
            }
            if (walk != null) { walk(i); ctx.wait(0.4); return@repeat }
            if (abs(pos + dir) > 6) dir = -dir
            glide(ctx, dir, 0.0, 300)
            pos += dir.toInt()
            ctx.wait(0.4)
        }
        MacroLog.i("no feather bubble near the cat's spot after 60 walks ($taps taps)")
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
        step("cat found: collecting the rewards")
        // what the banner says ("Obtained ..."): only its own lines, not the book's other clue rows behind it
        val banner = ctx.readText().filter { l -> Ocr.norm(l.text).let { "obtain" in it || "received" in it } }
            .joinToString(" | ") { it.text }.take(200)
        if (banner.isNotBlank()) MacroLog.i("reward banner: $banner")
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

    /** A random encounter on the way: the basic macro battle fights it (its skills). */
    private fun fightIfBattle(ctx: Context) {
        ctx.refresh()
        if (!Battle.inBattle(ctx)) return
        val why = ctx.runBasic("battle")
        if (why == "party was defeated") ctx.stop(why)
    }
}

/**
 * Which continent (Expand-view entry) each place is on, so a trip doesn't search every map.
 * Seeded from the APK (templates/cat_scratchbook/known_places.yaml); what the routine learns
 * goes to the app's own storage (files/known_places.json) and wins over the seed.
 */
object KnownPlaces {
    private data class Entry(val era: String, val continent: String)

    private var cache: MutableMap<String, Entry>? = null

    private fun file(ctx: Context) = java.io.File(ctx.debugDir.parentFile, "known_places.json")

    @Synchronized
    private fun all(ctx: Context): MutableMap<String, Entry> = cache ?: LinkedHashMap<String, Entry>().also { m ->
        runCatching {
            val text = ctx.assets.open("templates/cat_scratchbook/known_places.yaml").bufferedReader().use { it.readText() }
            (Load(LoadSettings.builder().build()).loadFromString(text) as? Map<*, *>)?.forEach { (k, v) ->
                val e = v as? Map<*, *> ?: return@forEach
                m[Ocr.norm(k.toString())] = Entry(e["era"].toString(), e["continent"].toString())
            }
        }.onFailure { MacroLog.e("known places seed unreadable", it) }
        runCatching {
            val f = file(ctx)
            if (f.exists()) {
                val o = org.json.JSONObject(f.readText())
                for (k in o.keys()) o.getJSONObject(k).let { m[k] = Entry(it.getString("era"), it.getString("continent")) }
            }
        }.onFailure { MacroLog.e("known places file unreadable", it) }
        cache = m
    }

    fun continent(ctx: Context, place: String, era: String): String? =
        all(ctx)[Ocr.norm(place)]?.takeIf { it.era.equals(era, true) }?.continent

    @Synchronized
    fun learn(ctx: Context, place: String, era: String, continent: String) {
        val m = all(ctx)
        val key = Ocr.norm(place)
        if (m[key] == Entry(era, continent)) return
        m[key] = Entry(era, continent)
        val o = org.json.JSONObject()
        m.forEach { (k, e) -> o.put(k, org.json.JSONObject().put("era", e.era).put("continent", e.continent)) }
        runCatching { file(ctx).writeText(o.toString(1)) }.onFailure { MacroLog.e("could not save known places", it) }
        MacroLog.i("learned: $place is on $continent ($era)")
    }
}
