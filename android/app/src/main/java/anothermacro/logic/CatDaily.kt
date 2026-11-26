package anothermacro.logic

import macroforge.logic.Entry

import macroforge.*

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * The whole daily Cat Scratchbook, unattended:
 *  1. open Diary → Cat Scratchbook and read today's clues with on-device OCR,
 *     matching them against the clue table (riddles.yaml);
 *  2. travel to the clue's place on the World Map: the fixed way from point zero where
 *     cat_spots.yaml has it (CatRoute), else era → find the place's label by OCR,
 *     panning and switching continents as needed → Yes;
 *  3. walk the spot's fixed path to the cat's start (CatRoute), else steer toward the
 *     feather on the area map (party marker vs feather icon); when the feather isn't seen,
 *     the quest "!" markers stand in for it;
 *     a spot with no fixed route is measured first (CatSurvey), so a later import routes it;
 *  4. hunt: walk in short steps and tap the cat's feather bubble (wider on each retry);
 *  5. back to the book (a found cat can leave a new clue) until all are found.
 */
object CatDaily {
    data class Clue(val key: String, val text: String, val location: String, val era: String)

    private val DIARY = Point(0.329, 0.922)
    private val CLOSE = Point(0.88, 0.03)
    // "found" messages that replace a clue once its cat is back home
    private val FOUND = listOf("So, you have returned", "Nya! I did think you would be returning", "You took so long", "I've been beside myself")

    /** The cat being looked for now ("cat 2 · Baruoki (Present)"), in front of each live log line. */
    private var cat = ""

    /** Spots measured this run for a fixed route (CatSurvey): the outcome names them. */
    private val surveyed = ArrayList<String>()

    /** Cats walked to by the area map instead of a fixed route (with why): the outcome names them. */
    private val looked = ArrayList<String>()

    /** Log a step and show it on the live log. */
    private fun step(text: String) = MacroLog.status("cat-scratchbook: " + (if (cat.isEmpty()) "" else "$cat: ") + text)

    /** Show a step's progress on the live log only (no log line each time). */
    private fun progress(text: String) = MacroLog.show("cat-scratchbook: " + (if (cat.isEmpty()) "" else "$cat: ") + text)

    fun run(ctx: Context): String {
        cat = ""
        surveyed.clear()
        looked.clear()
        val riddles = loadRiddles(ctx)
        val failed = HashSet<String>()
        // why each missed cat was missed, in the order missed: the stop reason lists them, so a
        // run nobody watched still says which cats need work (Statistics page, macro_status)
        val missed = LinkedHashMap<String, String>()
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
                    if (c.era !in Teleport.ERA) "its era isn't on the world map's era buttons" else "it can't be reached from the world map (not supported yet)")
                ctx.count("cats_skipped")
            }
            val next = pending.firstOrNull { it.key !in failed && supported(it) }
            if (next == null) {
                val left = pending.filter { it.key !in done }
                fun why(c: Clue) = when {
                    c.key in failed -> "missed"
                    !supported(c) -> "skipped: not reachable from the world map yet"
                    else -> "open"
                }
                val open = left.filter { it.key !in missed }
                return (if (left.isEmpty() && unknown.isEmpty()) "all cats found today (${done.size} this run)"
                else "found ${done.size}" + missedText(missed) +
                    (if (open.isNotEmpty()) "; still open: ${open.joinToString { "${it.location} (${why(it)})" }}" else "") +
                    (if (unknown.isNotEmpty()) "; unread clues: ${unknown.size}" else "")) + surveyText(surveyed) + lookedText(looked)
            }
            cat = "cat ${done.size + failed.size + 1} · ${next.location} (${next.era})"
            step("going there (clue: \"${next.text.take(60)}\")")
            val miss = go(ctx, next)
            if (miss == null) {
                done += next.key; ctx.count("cats_found")
                step("found! (${done.size} found this run)")
            } else {
                failed += next.key; ctx.count("cats_missed")
                missed[next.key] = "${next.location} (${next.era}): $miss"
                step("missed: $miss")
                MacroLog.i("MISSED cat: ${missed[next.key]}")
            }
        }
        cat = ""
        return "stopped after 8 rounds; found ${done.size}" + missedText(missed) + surveyText(surveyed) + lookedText(looked)
    }

    /** "; measured for a fixed route: Konium" or "" (the log has each spot's MEASURED line to import). */
    internal fun surveyText(spots: List<String>) =
        if (spots.isEmpty()) "" else "; measured for a fixed route: " + spots.joinToString()

    /** "; LOOKED AROUND (no fixed path) for: Konium (no fixed route)" or "" (every cat came by its fixed route). */
    internal fun lookedText(spots: List<String>) =
        if (spots.isEmpty()) "" else "; LOOKED AROUND (no fixed path) for: " + spots.joinToString()

    /** "; missed: Zarbo (Present): couldn't travel there, ..." or "" when none was missed. */
    internal fun missedText(missed: Map<String, String>) =
        if (missed.isEmpty()) "" else "; missed ${missed.size}: " + missed.values.joinToString(", ")

    /**
     * Travel to the clue's cat and find it: the fixed way (cat_spots.yaml, CatRoute) where the spot
     * is measured, the searching way where it isn't or where the screen doesn't match the data.
     */
    /** null when found, else why it was missed. */
    private fun go(ctx: Context, clue: Clue): String? {
        val spot = CatRoute.spot(ctx, clue.location)
        // a battle on the fixed route (also asked when there may be none): the battle macro's step
        // with the skills this macro has for it (battle.skill_*), not plain auto attacks
        val skills = Battle.Setup.from(ctx.innerOptions("battle"), "skill_")
        val fight = { Battle.fight(ctx) { skills }.also { if (it == Battle.Outcome.LOST) ctx.stop("party was defeated") } == Battle.Outcome.WON }
        // "X (Enter from Y)": travel to Y, then walk across into X
        val via = ENTER_FROM.find(clue.location)
        val walkTo = spot?.walkTo ?: via?.groupValues?.get(1)?.trim()
        val tripClue = when {
            via != null -> clue.copy(location = via.groupValues[2].trim())
            // the spot's travel point when the cat's map isn't one (walk_to, or a door: enter)
            spot != null && (spot.walkTo != null || spot.enter.isNotEmpty() || spot.approach.isNotEmpty()) -> clue.copy(location = spot.place)
            else -> clue
        }
        // the spot's own trip where cat_spots.yaml has it, else the clue's place (basic macro teleport)
        val arrived = Teleport.via(ctx, if (spot != null && spot.fixedTravel) CatRoute.trip(spot) else trip(tripClue))
        if (!arrived) return "couldn't travel there"
        Teleport.lastSearch?.let { looked += "${clue.location} (world map: $it)" }
        if (spot != null && spot.enter.isNotEmpty() && !spot.fixedWalk) {
            // a door or stairs right at the arrival (the fixed route flicks these itself)
            for (up in spot.enter) {
                MacroLog.i("${clue.location}: flick ${if (up) "U" else "D"} from the arrival")
                AreaNav.flick(ctx, up)
                ctx.wait(2.5)
                waitForField(ctx, 15)
            }
        }
        if (spot != null && spot.approach.isNotEmpty()) {
            // the fixed way across maps that have no area map (their own moves, not the cat's path)
            step("walking the approach to ${clue.location}")
            if (!CatRoute.approach(ctx, spot, fight)) MacroLog.i("${clue.location}: the field isn't showing after the approach")
            val title = mapTitle(ctx)
            MacroLog.i("${clue.location}: after the approach on '${title.ifEmpty { "?" }}'")
            if (title.isNotEmpty() && !names(title, clue.location)) {
                looked += "${clue.location} (approach ended on '$title')"
            }
        }
        if (walkTo != null && !walkAcross(ctx, walkTo)) return "couldn't walk across to $walkTo"
        if (spot != null && spot.fixedWalk) {
            ctx.count("fixed_routes")
            step("following the fixed route to the cat")
            // the cat's bubble can show at the arrival: look before moving
            if (hunt(ctx, walks = 1, walk = {})) return null
            val range = CatRoute.walk(ctx, spot, fight)
            if (range != null && hunt(ctx, walks = 16, walk = CatRoute.sweeper(ctx, spot, range, fight))) return null
            MacroLog.i("${clue.location}: no cat on the fixed route${if (range == null) " (left the path)" else " after sweeping its range"}: steering by the map")
            ctx.count("fixed_route_fallbacks")
            looked += "${clue.location} (${if (range == null) "left the fixed route" else "no cat along the fixed route"})"
        } else {
            MacroLog.i("${clue.location}: no fixed route yet (`another-macro cat-routes --todo`)")
            looked += "${clue.location} (no fixed route)"
            // measure it now, before the hunt moves the party: the next import makes it a fixed route
            if (spot?.fixedWalk != true && CatSurvey.measure(ctx, clue.location, fight) != null) surveyed += clue.location
        }
        return search(ctx)
    }

    private fun supported(c: Clue) = c.era in Teleport.ERA && !c.location.contains("quick destination")

    /** "<map> (Enter from <place>)": the cat is on a map the party walks to from a travel point. */
    private val ENTER_FROM = Regex("""^(.*?)\s*\(Enter from ([^)]*)\)""")

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

    // --- 2. world map travel: the basic macro teleport (Teleport.kt) ----------
    /** "<place> (<sub-location>)" / "<place> – <sub-location>": the trip to the clue's place. */
    internal fun trip(clue: Clue): Teleport.Trip {
        val place = clue.location.substringBefore(" (").substringBefore(" –").substringBefore(" -").trim()
        val sub = Regex("""\(([^)]*)\)|[–-] (.+)$""").find(clue.location)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return Teleport.Trip(clue.era, place, destination = sub)
    }

    fun travel(ctx: Context, clue: Clue): Boolean = Teleport.via(ctx, trip(clue))

    internal fun closeMap(ctx: Context) = Teleport.closeMap(ctx)

    // --- 3. steer on the area map -------------------------------------------------
    private const val FEATHER_ICON = "templates/cat_scratchbook/map_feather_icon.png"

    /**
     * Walk to the cat's spot and hunt for its bubble there; null once found, else why not.
     * The spot is the feather on the area map; when a quest "!" marker hides the feather,
     * the markers are tried one by one. A walk that doesn't reach the spot still hunts from
     * where it stopped, and each later pass hunts wider.
     */
    /** Which side of the travel point leads to an "Enter from" map (-1 left, 1 right), measured on the phone. */
    private val ACROSS_SIDE = mapOf(
        Ocr.norm("Karek Swampland") to 1.0,   // its exit is at the east end
    )

    private fun mapTitle(ctx: Context): String = Teleport.mapTitle(ctx)

    private fun names(title: String, map: String) =
        title.isNotEmpty() && (Ocr.similarity(title, map) >= 0.8 || Ocr.norm(title).contains(Ocr.norm(map)))

    /**
     * From the travel point, walk off the map's side into [target] (a clue's "Enter from" map).
     * The side measured for [target] first (ACROSS_SIDE), else left, checking the area map's title
     * after each long hold; a different map than [target] means the wrong exit, so walk back
     * and try the right side.
     */
    internal fun walkAcross(ctx: Context, target: String): Boolean {
        step("walking across to $target")
        val origin = mapTitle(ctx)
        MacroLog.i("walking across from '${origin.ifEmpty { "?" }}' to $target")
        if (names(origin, target)) return true
        val first = ACROSS_SIDE[Ocr.norm(target)] ?: -1.0
        for (dir in listOf(first, -first)) {
            for (i in 1..6) {
                AreaNav.hold(ctx, dir, 0.3)           // about 11 s: the next exit or the map's end
                ctx.wait(2.5)                         // a map change fades out and in
                fightIfBattle(ctx)
                val title = mapTitle(ctx)
                MacroLog.i("walk ${if (dir < 0) "left" else "right"} $i: on '${title.ifEmpty { "?" }}'")
                if (names(title, target)) { MacroLog.i("reached $target"); return true }
                if (title.isNotEmpty() && origin.isNotEmpty() && !names(title, origin)) {
                    // another map: back through the same exit, then the other side
                    MacroLog.i("this side leads to '$title', not $target: walking back")
                    for (k in 1..3) {
                        AreaNav.hold(ctx, -dir, 0.15); ctx.wait(2.5); fightIfBattle(ctx)
                        if (names(mapTitle(ctx), origin)) break
                    }
                    break
                }
            }
        }
        MacroLog.i("no way across to $target found on either side")
        return false
    }

    fun search(ctx: Context): String? {
        val ff = FeatherFinder.load(ctx)
        val tried = ArrayList<Point>()
        var lastWhy = "no cat found near the feather"
        for (pass in 0 until 3) {
            val spot = steer(ctx, tried)
            if (hunt(ctx, ff, span = 6 + 4 * pass, walks = if (pass == 0) 60 else 40)) return null
            if (spot == null) return "no feather on the area map and no cat around the arrival"
            if (spot.second) { tried += spot.first; lastWhy = "no cat at the quest markers that could hide the feather" }
            else lastWhy = "no cat found near the feather (searched ${pass + 1}x, wider each time)"
        }
        return lastWhy
    }

    /**
     * Walk toward the cat's spot on the area map (shared map walking: AreaNav).
     * Returns the spot and whether it is a quest marker standing in for a hidden feather,
     * or null when the map shows neither (the hunt then starts where the party stands).
     */
    fun steer(ctx: Context, skip: List<Point> = emptyList()): Pair<Point, Boolean>? {
        step("walking toward the feather on the area map")
        var spot: Pair<Point, Boolean>? = null
        val reached = AreaNav.steer(ctx, look = {
            // right after a trip the map may not open yet (area banner, fade-in): try a few times
            var view: AreaNav.View? = null
            for (attempt in 1..3) {
                // an encounter (frequent on field maps such as Karek Swampland) keeps the map shut:
                // fight it and wait for the field before opening the map
                waitForField(ctx, 15)
                var underMarker = false
                val v = AreaNav.look(ctx) { f ->
                    val (p, m) = catSpot(ctx, f, skip)
                    underMarker = m
                    listOfNotNull(p)
                }
                if (v.targets.isNotEmpty()) { view = v; spot = v.targets.first() to underMarker; break }
                MacroLog.i("feather not seen on the area map (try $attempt/3)")
                ctx.wait(2.5)
            }
            if (view == null) MacroLog.i("no feather on this map")
            view
        }, target = { it.targets.firstOrNull() }, onStep = { fightIfBattle(ctx) })
        if (spot != null && !reached) MacroLog.i("couldn't reach the spot on the area map; hunting from here")
        return spot
    }

    /**
     * Where the cat is on the open area map: the feather icon; else a feather partly hidden
     * by a quest "!" marker (a weaker match right by one); else a quest marker not tried yet
     * (true: it only may hide the feather).
     */
    private fun catSpot(ctx: Context, f: Frame, skip: List<Point>): Pair<Point?, Boolean> {
        AreaNav.findIcons(ctx, f, FEATHER_ICON).firstOrNull()?.let { return it to false }
        val bgr = ctx.colorMat() ?: return null to false
        val markers = AreaNav.findQuestMarkers(bgr)
        bgr.release()
        if (markers.isEmpty()) return null to false
        fun near(a: Point, b: Point) = abs(a.x - b.x) < 0.045 && abs(a.y - b.y) < 0.07
        val partial = AreaNav.findIcons(ctx, f, FEATHER_ICON, min = 0.5).firstOrNull { p -> markers.any { near(it, p) } }
        if (partial != null) {
            MacroLog.i("feather partly hidden by a quest marker at (${"%.2f".format(partial.x)}, ${"%.2f".format(partial.y)})")
            return partial to false
        }
        val left = markers.filter { m -> skip.none { near(it, m) } }
        val m = left.firstOrNull() ?: return null to false
        MacroLog.i("feather not seen; ${markers.size} quest marker(s) could hide it, trying the one at (${"%.2f".format(m.x)}, ${"%.2f".format(m.y)})")
        return m to true
    }

    private fun glide(ctx: Context, dx: Double, dy: Double, ms: Long) =
        ctx.drag(Point(0.5, 0.55), Point(0.5 + 0.06 * dx, 0.55 + 0.08 * dy), ms)

    // --- 4. hunt -------------------------------------------------------------------
    /**
     * Walk in short steps around here, up to [span] steps either side, and tap the feather
     * bubble; true once the book opens (found). [walk] (i-th walk) replaces the pulses left
     * and right, e.g. CatRoute's sweep of the cat's drift range.
     */
    fun hunt(ctx: Context, ff: FeatherFinder = FeatherFinder.load(ctx), span: Int = 6, walks: Int = 60,
             walk: ((Int) -> Unit)? = null): Boolean {
        var dir = -1.0
        var pos = 0
        var taps = 0
        step("looking for the cat's feather bubble")
        repeat(walks) { i ->
            progress("looking for the cat's feather bubble (walk ${i + 1}/$walks${if (taps > 0) ", $taps taps" else ""})")
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
            if (abs(pos + dir) > span) dir = -dir
            glide(ctx, dir, 0.0, 300)
            pos += dir.toInt()
            ctx.wait(0.4)
        }
        MacroLog.i("no feather bubble within $span steps of here after $walks walks ($taps taps)")
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
