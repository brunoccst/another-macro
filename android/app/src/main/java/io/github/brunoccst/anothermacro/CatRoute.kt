package io.github.brunoccst.anothermacro

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * The fixed way to a Cat Scratchbook cat (cat_spots.yaml, paths from `another-macro cat-routes`).
 *
 *  1. point zero: World Map on the Present era, Baruoki's label dragged to the middle (CatDaily.pointZero);
 *     then the spot's era tab and Expand → continent where it's on another one;
 *  2. the place's label by OCR from the view the era tab opens (it's where that era's last trip
 *     went, so it isn't fixed), the sub-location, Yes;
 *  3. from the arrival spot, the fixed moves to where the cat starts (Route.hold / flicks, with the
 *     walk table scaled to this area map's zoom);
 *  4. the cat wanders along its street within `drift`: the hunt sweeps between those two ends.
 *
 * Every step falls back to the old way (OCR search of the world map, steering by the area map's
 * feather) when the data is missing or the screen doesn't look like the data says.
 */
object CatRoute {
    data class Spot(
        val location: String, val era: String, val continent: String?, val place: String, val sub: String,
        /** the cat is on a map the party walks to from [place] (not routed yet) */
        val walkTo: String?,
        val layout: AreaNav.Layout?, val arrival: Point?, val cat: Point?,
        val driftLeft: Double, val driftRight: Double, val walkScale: Double, val path: List<Route.Step>?,
    ) {
        /** continent null = reachable without Expand; a spot whose continent isn't known yet still starts at point zero */
        val fixedTravel get() = era.isNotEmpty()
        val fixedWalk get() = layout != null && arrival != null && cat != null && path != null
    }

    private var cache: Map<String, Spot>? = null

    @Suppress("UNCHECKED_CAST")
    fun spots(ctx: Context): Map<String, Spot> = cache ?: runCatching {
        val text = ctx.assets.open("templates/cat_scratchbook/cat_spots.yaml").bufferedReader().use { it.readText() }
        val data = Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Map<String, Any?>>
        data.mapValues { (k, v) -> parse(k, v) }
    }.onFailure { MacroLog.e("cat_spots.yaml unreadable", it) }.getOrDefault(emptyMap()).also { cache = it }

    private fun nums(v: Any?) = (v as? List<*>)?.map { (it as Number).toDouble() }
    private fun pt(v: Any?) = nums(v)?.let { Point(it[0], it[1]) }

    internal fun parse(location: String, v: Map<String, Any?>): Spot {
        val area = v["area"] as? Map<*, *>
        val segs = (area?.get("segs") as? List<*>)?.mapNotNull { nums(it) }?.map { AreaNav.Seg(it[0], it[1], it[2]) }
        val links = (area?.get("links") as? List<*>)?.mapNotNull { nums(it) }?.map { AreaNav.Link(it[0], it[1], it[2]) }.orEmpty()
        val drift = nums(area?.get("drift")) ?: listOf(0.0, 0.0)
        return Spot(
            location = location,
            era = v["era"]?.toString() ?: "",
            continent = v["continent"]?.toString(),
            place = v["place"]?.toString() ?: location,
            sub = v["sub"]?.toString() ?: "",
            walkTo = v["walk_to"]?.toString(),
            layout = segs?.let { AreaNav.Layout(it, links) },
            arrival = pt(area?.get("arrival")),
            cat = pt(area?.get("cat")),
            driftLeft = drift[0], driftRight = drift[1],
            walkScale = (area?.get("walk_scale") as? Number)?.toDouble() ?: 1.0,
            path = (v["path"] as? List<*>)?.let { Route.parseLeg(listOf("cat") + it).steps },
        )
    }

    fun spot(ctx: Context, location: String): Spot? = spots(ctx)[location]

    /** walk.yaml for this area map: the same hold walks the same way, but each map is drawn at its own zoom. */
    @Suppress("UNCHECKED_CAST")
    fun table(ctx: Context, scale: Double): Route.Table {
        val text = ctx.assets.open("templates/another_dungeon/walk.yaml").bufferedReader().use { it.readText() }
        val y = Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Any?>
        fun list(k: String) = (y[k] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        fun num(k: String, d: Double) = (y[k] as? Number)?.toDouble() ?: d
        return Route.Table(list("hold_ms"), list("distance").map { it * scale }.toDoubleArray(), num("beyond_per_s", 0.026) * scale,
            num("min_walk", 0.003), num("wall_margin", 0.008), num("battle_lag_ms", 0.0).toLong(), num("flick_change", 8.0),
            num("battle_hud_lag_ms", 150.0).toLong(), num("dark_level", 30.0), num("still_change", 1.5))
    }

    // --- 1-2. the world map ---------------------------------------------------------------
    /**
     * Point zero, then the spot's era tab and (only if set) its continent, then the place's label
     * by OCR: the view each era tab opens on is where that era's last trip went (measured on the
     * phone, 2026-11-03), so a label has no fixed screen spot; the search starts from that view.
     */
    fun travel(ctx: Context, s: Spot): Boolean {
        if (!CatDaily.pointZero(ctx)) return false
        if (!s.era.equals("Present", true)) CatDaily.tapEra(ctx, s.era)
        if (s.continent != null && !CatDaily.openContinent(ctx, s.continent)) {
            MacroLog.i("${s.place}: no '${s.continent}' in the Expand list"); return false
        }
        MacroLog.i("${s.place}: trip from point zero (${s.era}, ${s.continent ?: "no Expand"})")
        return CatDaily.searchAndGo(ctx, s.place, s.sub)
    }

    // --- 3. the area map ------------------------------------------------------------------
    /** Where the party is and where the plan has it, while walking a spot's path. */
    private class Walk(val l: AreaNav.Layout, val t: Route.Table, var p: Point, var plan: Point)

    /**
     * From the arrival spot to where the cat starts, by the fixed path. One look at the area map
     * first lines the layout up (the game draws a map a little left or right) and checks that the
     * feather is near the cat's start; a cat elsewhere is steered to the old way. Returns the
     * cat's street range (x from, x to, y) to sweep, or null if the caller should steer instead.
     */
    fun walk(ctx: Context, s: Spot, fight: () -> Boolean): Triple<Double, Double, Double>? {
        if (!s.fixedWalk) return null
        // every feather-like icon: a big grey quill-in-a-circle icon (seen in Ratle) matches the
        // feather template nearly as well, so the one nearest the cat's start is taken
        val view = AreaNav.look(ctx) { f -> AreaNav.findIcons(ctx, f, "templates/cat_scratchbook/map_feather_icon.png") }
        val arrival = s.arrival!!
        val party = view.candidates.minByOrNull { abs(it.x - arrival.x) + 3 * abs(it.y - arrival.y) }
        val shift = if (party != null && abs(party.y - arrival.y) < AreaNav.ROW_TOL && abs(party.x - arrival.x) < 0.025) party.x - arrival.x else 0.0
        val l = AreaNav.Layout(s.layout!!.segs.map { it.copy(x1 = it.x1 + shift, x2 = it.x2 + shift) }, s.layout.links.map { it.copy(x = it.x + shift) })
        val cat = Point(s.cat!!.x + shift, s.cat.y)
        val feather = view.targets.minByOrNull { abs(it.x - cat.x) + 2 * abs(it.y - cat.y) }
        MacroLog.i("${s.location}: party ${party?.let { fmt(it) } ?: "not seen"} (arrival ${fmt(arrival)}, offset ${"%.3f".format(shift)}), " +
            "feather ${feather?.let { fmt(it) } ?: "not seen"} (cat start ${fmt(s.cat)})")
        val catSeg = Route.segOf(l, cat) ?: return null
        val lo = maxOf(catSeg.x1, cat.x - s.driftLeft) - 0.02
        val hi = minOf(catSeg.x2, cat.x + s.driftRight) + 0.02
        if (feather != null && (abs(feather.y - catSeg.y) > AreaNav.ROW_TOL * 2 || feather.x < lo - 0.03 || feather.x > hi + 0.03)) {
            MacroLog.i("${s.location}: the feather isn't where this cat starts or wanders: fix area.cat/drift; steering by the map")
            return null
        }
        if (party == null || abs(party.y - arrival.y) >= AreaNav.ROW_TOL) {
            MacroLog.i("${s.location}: the party isn't at the arrival spot: steering by the map"); return null
        }
        val w = Walk(l, table(ctx, s.walkScale), Point(party.x, Route.segOf(l, party)?.y ?: arrival.y), Point(arrival.x + shift, arrival.y))
        MacroLog.i("${s.location}: path to the cat: ${s.path!!.joinToString(", ")}")
        Route.disturbed = false
        for (st in s.path) {
            when (st) {
                is Route.Step.Hold -> {
                    w.plan = Route.walked(l, w.plan, st.right, w.t.distance(st.ms.toDouble()))
                    walkTo(ctx, w, w.plan.x, fight)
                }
                is Route.Step.Flick -> if (!flick(ctx, w, st.up, fight)) return null
            }
        }
        if (Route.disturbed) { resync(ctx, w, "at the cat after a battle"); walkTo(ctx, w, w.plan.x, fight) }
        val seg = Route.segOf(l, w.p) ?: return null
        if (seg != Route.segOf(l, cat)) { MacroLog.i("${s.location}: not on the cat's street at the end of the path"); return null }
        return Triple(maxOf(seg.x1, cat.x - s.driftLeft), minOf(seg.x2, cat.x + s.driftRight), seg.y)
    }

    private fun walkTo(ctx: Context, w: Walk, x: Double, fight: () -> Boolean) {
        val s = Route.segOf(w.l, w.p)
        var d = x - w.p.x
        if (s != null && (abs(x - s.x1) < 1e-6 || abs(x - s.x2) < 1e-6) && abs(d) >= w.t.minWalk) d += if (d > 0) w.t.wallMargin else -w.t.wallMargin
        if (abs(d) < w.t.minWalk) return
        w.p = Route.hold(ctx, w.l, w.t, w.p, d > 0, w.t.holdMs(abs(d)).toLong(), fight)
        if (Route.unseen) {
            // a battle came during the hold without being seen: read the spot and walk the rest
            Route.unseen = false
            resync(ctx, w, "a battle came during the hold")
            val rest = x - w.p.x
            if (abs(rest) >= w.t.minWalk) w.p = Route.hold(ctx, w.l, w.t, w.p, rest > 0, w.t.holdMs(abs(rest)).toLong(), fight)
        }
    }

    private fun resync(ctx: Context, w: Walk, why: String) {
        Route.disturbed = false
        val seen = AreaNav.look(ctx) { emptyList() }.candidates.filter { Route.segOf(w.l, it) != null }
            .minByOrNull { abs(it.x - w.p.x) + 2 * abs(it.y - w.p.y) } ?: return
        val seg = Route.segOf(w.l, seen)!!
        MacroLog.i("$why: the map shows ${fmt(seen)}, followed ${fmt(w.p)}")
        w.p = Point(seen.x.coerceIn(seg.x1, seg.x2), seg.y)
    }

    /** Take the stairs the plan is at: a flick from on them, a little either side when it doesn't take. */
    private fun flick(ctx: Context, w: Walk, up: Boolean, fight: () -> Boolean): Boolean {
        if (Route.disturbed) resync(ctx, w, "before the stairs after a battle")
        val next = Route.climbed(w.l, w.plan, up) ?: run { MacroLog.i("no stairs ${if (up) "up" else "down"} at ${fmt(w.plan)} in the layout"); return false }
        for ((i, off) in listOf(0.0, -0.006, 0.006, -0.012, 0.012).withIndex()) {
            walkTo(ctx, w, w.plan.x + off, fight)
            ctx.wait(0.4)
            val before = ctx.refresh().gray.clone()
            AreaNav.flick(ctx, up)
            val after = ctx.refresh().gray.clone()
            val change = Route.change(before, after)
            before.release(); after.release()
            val battle = fight()
            MacroLog.i("flick ${if (up) "U" else "D"} (try ${i + 1}): screen change ${"%.1f".format(change)}${if (battle) " (a battle came)" else ""}")
            val took = (!battle && change >= w.t.flickChange) || AreaNav.look(ctx) { emptyList() }.candidates
                .any { abs(it.y - next.y) < AreaNav.ROW_TOL && abs(it.x - next.x) < 0.03 }
            if (took) {
                if (i > 0) MacroLog.i("stairs taken at x≈${"%.3f".format(w.p.x)} (data ${"%.3f".format(w.plan.x)}): fix the link in cat_spots.yaml")
                w.p = next; w.plan = next
                Route.disturbed = false; Route.unseen = false
                return true
            }
        }
        MacroLog.i("the stairs at ${fmt(w.plan)} didn't take: steering by the map")
        return false
    }

    // --- 4. drift --------------------------------------------------------------------------
    /**
     * The hunt's walks along the cat's street: toward one end of its drift range, then the other,
     * and back, in holds from the scaled walk table (the position stays known).
     */
    fun sweeper(ctx: Context, s: Spot, range: Triple<Double, Double, Double>, fight: () -> Boolean): (Int) -> Unit {
        val (lo, hi, y) = range
        val l = AreaNav.Layout(listOf(AreaNav.Seg(y, lo - 0.05, hi + 0.05)), emptyList())
        val t = table(ctx, s.walkScale)
        var p = Point(s.cat!!.x.coerceIn(lo, hi), y)
        var goal = hi
        // short walks (half the range at most): the bubble is looked for between them
        val step = ((hi - lo) / 2).coerceAtLeast(0.01)
        return { _ ->
            if (abs(goal - p.x) < t.minWalk) goal = if (goal == hi) lo else hi
            val d = (goal - p.x).coerceIn(-step, step)
            if (abs(d) >= t.minWalk) p = Route.hold(ctx, l, t, p, d > 0, t.holdMs(abs(d)).toLong(), fight)
        }
    }

    private fun fmt(p: Point) = "(${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})"
}
