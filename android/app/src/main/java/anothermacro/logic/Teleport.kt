package anothermacro.logic

import macroforge.*

/**
 * Every World Map trip, the step of the basic macro `teleport` (macros/basic/teleport.yaml):
 * Map → the era's tab → (Expand → the continent's entry) → the place's label, found by OCR and
 * by panning the map → the destination in the panel a label can open → Yes → the field.
 *
 * Other macros run it with [via] (`ctx.runBasic`): the cat scratchbook (CatDaily.kt) and the way
 * to the Another Dungeon door (RiftDoor.kt). Where it can't find the place, it tries the map the
 * era tab shows and then every entry of the Expand list, and remembers the continent it found
 * the place on ([KnownPlaces]). The places: docs/knowledge-base/learned/world-map.md.
 */
object Teleport {
    /** The basic macro that runs [go]. */
    const val MACRO = "teleport"

    /** How [go]'s stop reason starts when the party is at the place. */
    const val ARRIVED = "arrived at "

    /**
     * Where to: [era] = the era tab, [place] = the map label ("<label>, <continent>" names the
     * continent too), [continent] = the Expand entry to open first (null: learned, else searched),
     * [destination] = the entry to pick when the label opens a panel of destinations.
     */
    data class Trip(val era: String, val place: String, val continent: String? = null, val destination: String = "") {
        fun params() = mapOf("era" to era, "place" to place, "continent" to (continent ?: ""), "destination" to destination)

        companion object {
            /** The teleport macro's options. */
            fun of(params: Map<String, String>) = Trip(
                params["era"]?.trim().orEmpty(), params["place"]?.trim().orEmpty(),
                params["continent"]?.trim()?.takeIf { it.isNotEmpty() }, params["destination"]?.trim().orEmpty())
        }
    }

    /** Run the basic macro teleport for [trip] inside the calling macro: true when it got there. */
    fun via(ctx: Context, trip: Trip): Boolean {
        val why = ctx.runBasic(MACRO, trip.params())
        return why.startsWith(ARRIVED).also { if (!it) MacroLog.i("teleport to ${trip.place}: $why") }
    }

    private val MAP_BUTTON = Point(0.385, 0.917)
    private val CLOSE = Point(0.88, 0.03)
    private val PANEL_CLOSE = Point(0.825, 0.10) // the X of an island's panel on the world map
    // the era tabs' top half (a tap on their lower edge can land on a place label under it);
    // "???" is the fourth tab, right of Future
    internal val ERA = mapOf("Antiquity" to Point(0.1525, 0.135), "Present" to Point(0.2283, 0.135), "Future" to Point(0.3029, 0.135),
        "???" to Point(0.378, 0.15))
    private val EXPAND = Point(0.844, 0.935)
    /** Mean grey change (0-255) above which a world-map drag moved the view. */
    private const val MAP_MOVED = 4.0

    /** Places on islands that the Expand view lists apart from the continents. */
    private val REGIONS = mapOf(
        "Snake Bone Island" to listOf("Snake Tail Corabel", "Snake Spine Gabaraki", "Konium", "Konium Bridge", "Snake Liver Damaku",
            "Snake Neck Igoma", "Snake Dream", "Snake Skull Mescita"),
        "Astra Island" to listOf("Astra Pier", "Astra Forest", "Resttin", "Astra Mountains", "Astral Ruins", "Astrarium", "Astra Woodlands"),
        "Remulia" to listOf("Floating City - Remulia", "Corridor of Time Water", "Ark Exterior", "Ark Interior"),
    )
    /** Expand entries besides "... Continent" (searched when a place isn't where it was looked for). */
    private val REGION_NAMES = REGIONS.keys + listOf("Snake Bone", "Astra", "Ogres Spike", "Illusion World", "Underworld")
    private val CONTINENT_NAMES = listOf("Migleina", "Garulea", "Zerberiya", "Kiliphe")
    /** The islands' continents (Expand entries). */
    private val ISLAND_CONTINENT = mapOf("Snake Bone Island" to "Migleina Continent")

    /** The island a place is on (its label on the continent map opens a panel of its places), or null. */
    internal fun regionOf(place: String) = REGIONS.entries.firstOrNull { (_, places) -> places.any { Ocr.norm(it) == Ocr.norm(place) } }?.key

    /**
     * "<label>, <continent>": the map label and that continent's Expand entry (null when the name
     * carries none).
     */
    internal fun splitPlace(place: String): Pair<String, String?> {
        val m = Regex("""^(.+),\s*([A-Za-z]+)$""").find(place.trim()) ?: return place to null
        val c = CONTINENT_NAMES.firstOrNull { it.equals(m.groupValues[2], true) } ?: return place to null
        return m.groupValues[1].trim() to "$c Continent"
    }

    /**
     * The continent (Expand entry) to open for [place], if known: named in it, learned for the
     * place itself, else for another place on the same island, else the island's own continent.
     */
    internal fun continentFor(ctx: Context, place: String, era: String): String? {
        splitPlace(place).second?.let { return it }
        KnownPlaces.continent(ctx, place, era)?.let { return it }
        val region = regionOf(place) ?: return null
        REGIONS[region].orEmpty().firstNotNullOfOrNull { KnownPlaces.continent(ctx, it, era) }?.let { return it }
        return ISLAND_CONTINENT[region]
    }

    // --- the step ---------------------------------------------------------------------------

    /** The whole trip, from the field; the stop reason ([ARRIVED] + the place, or why not). */
    fun go(ctx: Context, trip: Trip): String {
        if (trip.place.isEmpty()) return "no place to travel to"
        if (trip.era !in ERA) return "unknown era '${trip.era}' (one of ${ERA.keys.joinToString()})"
        val place = splitPlace(trip.place).first
        val sub = trip.destination
        val region = regionOf(place)
        val where = place + if (sub.isNotEmpty()) " ($sub)" else ""
        if (!waitForField(ctx, 20)) return "not on the field"
        if (!openWorldMap(ctx, trip.era)) return "the world map didn't open"
        val known = trip.continent ?: continentFor(ctx, trip.place, trip.era)
        if (known != null) {
            MacroLog.i("$place: on $known")
            // the era tab can open on that continent already: one look before Expand
            labelInView(ctx, place)?.let { return result(goVia(ctx, it, place, sub), where) }
            var opened = openContinent(ctx, known)
            if (!opened) {
                // the era tab tap may not have switched the tab (the map was still opening): again
                MacroLog.i("no '$known' in the Expand list: the ${trip.era} tab again")
                closeExpand(ctx)
                tapEra(ctx, trip.era)
                labelInView(ctx, place)?.let { return result(goVia(ctx, it, place, sub), where) }
                opened = openContinent(ctx, known)
            }
            if (opened) {
                step("looking for $place on $known")
                if (searchAndGo(ctx, place, sub, region)) return ARRIVED + where
                MacroLog.i("$place wasn't on $known; searching")
            } else {
                MacroLog.i("$place: no '$known' in the Expand list; looking on this map")
                closeExpand(ctx)
            }
        }
        step("looking for $place on the map shown")
        if (ctx.readText().none { Ocr.norm(it.text) == "world map" }) return "the world map closed"
        if (searchAndGo(ctx, place, sub, region)) return ARRIVED + where
        // not on this map: each entry of the Expand list
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
                KnownPlaces.learn(ctx, place, trip.era, name)
                return ARRIVED + where
            }
        }
        closeMap(ctx)
        return "couldn't find $place on the world map (not unlocked yet?)"
    }

    private fun result(ok: Boolean, where: String) = if (ok) ARRIVED + where else "couldn't travel to $where"

    private fun step(text: String) = MacroLog.status("teleport: $text")

    /** The field (or a battle that started on arrival: the caller fights it); taps through the title screen. */
    private fun waitForField(ctx: Context, seconds: Int): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null || Battle.inBattle(ctx)) return true
            if (ctx.find("title_continue") != null) { ctx.tap(Point(0.5, 0.75)); ctx.wait(6.0) }
            ctx.wait(1.0)
        }
        return false
    }

    /** Open the World Map on [era]'s tab. A tap the Map button ignores is retried. */
    internal fun openWorldMap(ctx: Context, era: String): Boolean {
        step("opening the world map")
        for (attempt in 1..3) {
            if (attempt > 1) waitForField(ctx, 10)
            ctx.tap(MAP_BUTTON); ctx.wait(2.5)
            if (onWorldMap(ctx)) {
                step("on the world map: $era")
                tapEra(ctx, era)
                return true
            }
            ctx.wait(1.5)
            if (onWorldMap(ctx)) {
                step("on the world map: $era")
                tapEra(ctx, era)
                return true
            }
            MacroLog.i("world map didn't open (try $attempt/3)")
        }
        MacroLog.i("the world map never opened")
        return false
    }

    private fun onWorldMap(ctx: Context) = ctx.readText().any { Ocr.norm(it.text) == "world map" }

    /**
     * The [era] tab. A tab that is greyed out (the continent in view has no map in that era)
     * doesn't take the tap and the view stays the same: then a continent first (Expand →
     * [BRIDGE], else the first other "... Continent" entry), and the tab again. The same unchanged
     * view also shows when the tab was already open; the continent switch then costs a few seconds.
     */
    internal fun tapEra(ctx: Context, era: String) {
        val before = ctx.refresh().gray.clone()
        ctx.tap(ERA.getValue(era)); ctx.wait(2.0)
        // a label under the tab took the tap: say No to the trip it offers
        if (sayNo(ctx)) {
            MacroLog.i("the $era tab tap opened a trip prompt: No")
            ctx.tap(ERA.getValue(era)); ctx.wait(2.0)
        }
        val change = Route.change(before, ctx.refresh().gray)
        before.release()
        if (change >= MAP_MOVED) return
        MacroLog.i("the $era tab didn't change the view (change ${"%.1f".format(change)}): a continent first, then the tab")
        val expand = ctx.readText().firstOrNull { Ocr.norm(it.text) == "expand" && it.center.y > 0.75 }
        ctx.tap(expand?.center ?: EXPAND); ctx.wait(2.0)
        val entries = ctx.readText().filter { it.text.contains("Continent", true) }
        val pick = entries.firstOrNull { Ocr.similarity(it.text, BRIDGE) >= 0.8 } ?: entries.firstOrNull()
        if (pick == null) { MacroLog.i("no continent in the Expand list"); closeExpand(ctx); return }
        MacroLog.i("continent first: ${pick.text}")
        ctx.tap(pick.center); ctx.wait(2.0)
        ctx.tap(ERA.getValue(era)); ctx.wait(2.0)
        sayNo(ctx)
    }

    /** The continent picked before an era tab that doesn't open from the continent in view (see [tapEra]). */
    private const val BRIDGE = "Migleina Continent"

    /** "Moving to ...?" on screen: No. Whether it was there. */
    private fun sayNo(ctx: Context): Boolean {
        val lines = ctx.readText()
        if (lines.none { it.text.startsWith("Moving to") }) return false
        val no = lines.firstOrNull { Ocr.norm(it.text) == "no" && it.center.y > 0.4 }
        ctx.tap(no?.center ?: Point(0.412, 0.568)); ctx.wait(1.0)
        return true
    }

    /** Expand → tap [continent]'s entry (by its text). */
    internal fun openContinent(ctx: Context, continent: String): Boolean {
        val expand = ctx.readText().firstOrNull { Ocr.norm(it.text) == "expand" && it.center.y > 0.75 }
        ctx.tap(expand?.center ?: EXPAND); ctx.wait(2.0)
        val line = ctx.readText().firstOrNull { Ocr.similarity(it.text, continent) >= 0.8 } ?: return false
        ctx.tap(line.center); ctx.wait(2.0)
        return true
    }

    /** The Expand list left open (one of its entries shows): Expand again folds it. */
    private fun closeExpand(ctx: Context) {
        val open = ctx.readText().any { l -> l.text.contains("Continent", true) || REGION_NAMES.any { Ocr.similarity(l.text, it) >= 0.8 } }
        if (!open) return
        val expand = ctx.readText().firstOrNull { Ocr.norm(it.text) == "expand" && it.center.y > 0.75 }
        ctx.tap(expand?.center ?: EXPAND); ctx.wait(1.5)
    }

    /** Close the World Map (after saying No to a trip prompt a stray tap opened). */
    internal fun closeMap(ctx: Context) {
        sayNo(ctx)
        ctx.tap(CLOSE); ctx.wait(1.5)
    }

    /** [place]'s label in the view as it is (no panning), clear of the map's buttons. */
    private fun labelInView(ctx: Context, place: String): Ocr.Line? =
        withWrapped(ctx.readText()).firstOrNull { it.center.y > 0.12 && isPlace(it, place) }?.takeIf { !nearButtons(it.center) }

    private fun isPlace(l: Ocr.Line, place: String) = Ocr.norm(l.text) == Ocr.norm(place) || Ocr.similarity(l.text, place) >= 0.85

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

    private fun isYes(l: Ocr.Line) = Ocr.norm(l.text) == "yes" && l.center.y > 0.4

    /** Pan around the visible map looking for [place]'s label; tap it and confirm the trip. */
    internal fun searchAndGo(ctx: Context, place: String, sub: String, region: String? = null): Boolean {
        // look here first; then from the top-left corner, sweep the map row by row (snake order)
        val pans = ArrayList<Pair<Double, Double>>()
        pans += 0.0 to 0.0
        // to the top-left corner (the drags stop at the edges): five drags, so the sweep starts at the edge
        repeat(5) { pans += 0.6 to 0.4 }
        // five rows, to cover a continent's map at the zoom a trip leaves it at; the drags stop at the edge
        for (row in 0 until 5) {
            val step = if (row % 2 == 0) -0.4 else 0.4
            if (row > 0) pans += 0.0 to -0.35
            repeat(6) { pans += step to 0.0 }   // six pans to reach a wide map's east end
        }
        var seen: List<Ocr.Line> = emptyList()
        for ((i, pan) in pans.withIndex()) {
            val (dx, dy) = pan
            val cornerDrag = i in 1..4              // no point reading while still on the way to the corner
            if (dx != 0.0 || dy != 0.0) {
                // a drag that starts on a label doesn't move the map: try the next free start until the view moves
                if (cornerDrag || i == 5) seen = ctx.readText()
                val before = ctx.refresh().gray.clone()
                for ((k, from) in dragStarts(dx, dy, seen).take(3).withIndex()) {
                    ctx.drag(from, Point(from.x + dx, from.y + dy), 700); ctx.wait(1.0)
                    val change = Route.change(before, ctx.refresh().gray)
                    if (change >= MAP_MOVED) break
                    // at an edge the map stops too: one retry tells the two apart
                    if (k == 0) MacroLog.i("world map drag (${"%.2f".format(dx)}, ${"%.2f".format(dy)}) from (${"%.2f".format(from.x)}, ${"%.2f".format(from.y)}) didn't move the view (change ${"%.1f".format(change)})")
                    else break
                }
                before.release()
            }
            if (cornerDrag) continue
            var read = ctx.readText()
            seen = read
            MacroLog.i("map labels: " + read.filter { it.center.y > 0.12 }.joinToString { it.text }.take(300))
            fun placeLabel() = withWrapped(read).firstOrNull { it.center.y > 0.12 && isPlace(it, place) }
            var label = placeLabel()
            if (label == null && region != null) {
                // an island is one label on the continent map: it opens a panel of its places
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

    /**
     * [lines] plus each label that wraps onto a second line, joined into one line ("Nazrik, City of"
     * over "Shadows"): the second line starts right under the first and overlaps it across.
     */
    internal fun withWrapped(lines: List<Ocr.Line>): List<Ocr.Line> {
        val joined = ArrayList<Ocr.Line>()
        for (a in lines) for (b in lines) {
            if (a === b) continue
            val ab = a.box; val bb = b.box
            val gap = bb.y - (ab.y + ab.h)
            val overlap = minOf(ab.x + ab.w, bb.x + bb.w) - maxOf(ab.x, bb.x)
            if (gap < -ab.h * 0.3 || gap > ab.h * 0.8 || overlap < minOf(ab.w, bb.w) * 0.5) continue
            val x = minOf(ab.x, bb.x); val y = ab.y
            joined += Ocr.Line("${a.text} ${b.text}", Region(x, y, maxOf(ab.x + ab.w, bb.x + bb.w) - x, bb.y + bb.h - y))
        }
        return lines + joined
    }

    /**
     * Where a world-map drag of ([dx], [dy]) starts: the middle, unless a place label from the last
     * read ([labels]) is there (a drag that starts on a label doesn't move the map).
     */
    internal fun dragStart(dx: Double, dy: Double, labels: List<Ocr.Line>): Point = dragStarts(dx, dy, labels).first()

    /**
     * Where a world-map drag of ([dx], [dy]) can start, best first: off every place label from the
     * last read ([labels]), with the whole drag on the map (below the era tabs, clear of the screen
     * edges). Labels sit thick on some maps, so starts across the width are tried.
     */
    internal fun dragStarts(dx: Double, dy: Double, labels: List<Ocr.Line>): List<Point> {
        fun free(p: Point) = labels.none { l ->
            val b = l.box
            p.x in (b.x - 0.03)..(b.x + b.w + 0.03) && p.y in (b.y - 0.05)..(b.y + b.h + 0.05)
        }
        fun inside(p: Point) = p.x in 0.04..0.96 && p.y in 0.22..0.9
        val all = ArrayList<Point>()
        for (ox in listOf(0.0, -0.12, 0.12, -0.24, 0.24, -0.36, 0.36)) for (y in listOf(0.55, 0.42, 0.68, 0.35, 0.75)) {
            val p = Point(0.5 - dx / 2 + ox, y - dy / 2)
            if (inside(p) && inside(Point(p.x + dx, p.y + dy))) all += p
        }
        if (all.isEmpty()) all += Point(0.5 - dx / 2, 0.55 - dy / 2)
        return all.filter { free(it) } + all.filterNot { free(it) }
    }

    /** Tap a place's [label] on the world map, pick [sub] in its destinations panel if one opens, confirm the trip. */
    internal fun goVia(ctx: Context, label: Ocr.Line, place: String, sub: String): Boolean {
        ctx.tap(tapPoint(label)); ctx.wait(2.0)
        var lines = ctx.readText()
        if (lines.none { isYes(it) } && lines.none { it.text.startsWith("Moving to") }) {
            // some places open a panel of destinations
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
            // already here only if the area map says so (a place that isn't a travel point gives no dialog either)
            waitForField(ctx, 10)
            val title = mapTitle(ctx)
            if (title.isNotEmpty() && !sameMap(title, place)) {
                MacroLog.i("$place: the party is on '$title', not $place: not travelling there this way")
                return false
            }
            return true
        }
        ctx.tap(yes.center)
        ctx.count("trips")
        step("travelling to $place${if (sub.isNotEmpty()) " ($sub)" else ""}")
        return waitForField(ctx, 30).also { if (!it) MacroLog.i("the field didn't come back after the trip") }
    }

    /** The area map's title (the map the party is on), or "" if the map didn't open. */
    internal fun mapTitle(ctx: Context): String =
        AreaNav.look(ctx, readText = true) { emptyList() }.text.filter { it.center.y < 0.12 }.joinToString(" ") { it.text }.trim()

    /**
     * Whether an area map's [title] is [location]'s map: a "(...)" after the name is left out,
     * and either name may hold the other ("Zero Point" for "Zero Point - East").
     */
    internal fun sameMap(title: String, location: String): Boolean {
        val t = Ocr.norm(title)
        val l = Ocr.norm(location.substringBefore(" ("))
        return t.isNotEmpty() && l.isNotEmpty() && (Ocr.similarity(t, l) >= 0.8 || t.contains(l) || l.contains(t))
    }
}
