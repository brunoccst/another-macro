package anothermacro.logic

import macroforge.*

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * The fixed way to a Cat Scratchbook cat (cat_spots.yaml, paths from `another-macro cat-routes`).
 *
 *  1-2. the trip: the basic macro teleport (Teleport.kt) with the spot's era, continent, place
 *     and sub-location;
 *  2b. the spot's `approach` (maps with no area map between the travel point and the cat's map);
 *     a spot with an `approach` and `sweep_ms` but no area map ends there: the hunt sweeps only
 *     that short range (a fixed route);
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
        /** the cat is on a map the party walks to from [place]; [layout] is then that map's */
        val walkTo: String?,
        /** flicks right after the trip, before [layout] (true = up): the cat is on another floor */
        val enter: List<Boolean>,
        /** fixed moves (holds in ms, flicks) across maps without an area map, after [enter], before [layout] */
        val approach: List<Route.Step>,
        val layout: AreaNav.Layout?, val arrival: Point?, val cat: Point?,
        val driftLeft: Double, val driftRight: Double, val walkScale: Double, val path: List<Route.Step>?,
        /** [approach] ends by the cat and there is no area map: the cat wanders this many ms of walking left, right of there */
        val sweepMs: Pair<Long, Long>? = null,
    ) {
        /** continent null = learned or searched by the teleport */
        val fixedTravel get() = era.isNotEmpty()
        val fixedWalk get() = layout != null && arrival != null && cat != null && path != null
        /** no area map, but a measured approach and sweep: a fixed route all the same */
        val approachRoute get() = layout == null && approach.isNotEmpty() && sweepMs != null
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
            enter = (v["enter"] as? List<*>)?.mapNotNull { when (it?.toString()?.trim()) { "U" -> true; "D" -> false; else -> null } }.orEmpty(),
            approach = (v["approach"] as? List<*>)?.let { Route.parseLeg(listOf("approach") + it).steps }.orEmpty(),
            layout = segs?.let { AreaNav.Layout(it, links) },
            arrival = pt(area?.get("arrival")),
            cat = pt(area?.get("cat")),
            driftLeft = drift[0], driftRight = drift[1],
            walkScale = (area?.get("walk_scale") as? Number)?.toDouble() ?: 1.0,
            path = (v["path"] as? List<*>)?.let { Route.parseLeg(listOf("cat") + it).steps },
            sweepMs = nums(v["sweep_ms"])?.takeIf { it.size == 2 }?.let { it[0].toLong() to it[1].toLong() },
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
    /** The spot's trip for the basic macro teleport: its era, place, continent (when set) and sub-location. */
    fun trip(s: Spot) = Teleport.Trip(s.era, s.place, s.continent, s.sub)

    // --- 2b. maps without an area map ------------------------------------------------------
    /**
     * Walk the spot's `approach`: plain holds and flicks across the maps between the travel point
     * and the cat's map (no area map to walk by, so no position is read). A battle during a hold is
     * fought and that hold made again. True when the screen shows the field after the last move.
     */
    fun approach(ctx: Context, s: Spot, fight: () -> Boolean): Boolean {
        if (s.approach.isEmpty()) return true
        settle(ctx)
        MacroLog.i("${s.location}: approach ${s.approach.joinToString(", ")}")
        for (st in s.approach) {
            when (st) {
                is Route.Step.Flick -> { AreaNav.flick(ctx, st.up); settle(ctx) }
                is Route.Step.Hold -> holdMs(ctx, s, st.right, st.ms, fight)
            }
        }
        settle(ctx)
        return ctx.find("field_menu_button") != null
    }

    /** Hold the stick right/left for [ms]; a battle during the hold is fought and the hold made again (up to 3 tries). */
    private fun holdMs(ctx: Context, s: Spot, right: Boolean, ms: Long, fight: () -> Boolean) {
        for (attempt in 1..3) {
            ctx.drag(Point(0.5, 0.65), Point(0.5 + if (right) 0.1 else -0.1, 0.65), ms)
            ctx.wait(0.5)
            ctx.refresh()
            if (!Battle.inBattle(ctx)) break
            MacroLog.i("${s.location}: a battle came during a ${if (right) "R" else "L"} $ms hold (try $attempt): fighting, then again")
            fight()
            settle(ctx)
        }
    }

    /**
     * The hunt's walks for an [Spot.approachRoute]: from where the approach ended, out to the left end
     * of `sweep_ms`, then across to the right end and back, in short holds (the bubble is looked for
     * between them).
     */
    fun msSweeper(ctx: Context, s: Spot, fight: () -> Boolean): (Int) -> Unit {
        val (left, right) = s.sweepMs!!
        var pos = 0L // ms of walking right of where the approach ended
        var goal = -left
        val step = maxOf((left + right) / 4, 200L)
        return { _ ->
            if (pos == goal) goal = if (goal == right) -left else right
            val d = (goal - pos).coerceIn(-step, step)
            if (d != 0L) {
                holdMs(ctx, s, d > 0, kotlin.math.abs(d).coerceIn(200L, 6000L), fight)
                pos += d
            }
        }
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
        settle(ctx)
        for (up in s.enter) {
            // the trip lands on another floor than the cat's: flick there first
            MacroLog.i("${s.location}: flick ${if (up) "U" else "D"} to the cat's floor")
            AreaNav.flick(ctx, up)
            settle(ctx)
        }
        // every feather-like icon: other icons can match the feather template nearly as well, so the
        // one nearest the cat's start is taken
        val arrival = s.arrival!!
        var view = AreaNav.look(ctx) { f -> AreaNav.findIcons(ctx, f, "templates/cat_scratchbook/map_feather_icon.png") }
        // after a walk across (walk_to) the party stops wherever the hold ended, so only a marker read
        // on a street of the layout tells where it is: look again while the map shows none there
        for (i in 1..2) {
            if (s.walkTo == null || view.candidates.any { Route.segOf(s.layout!!, it) != null }) break
            MacroLog.i("${s.location}: party marker not on a street of the layout (${view.candidates.take(3).joinToString { fmt(it) }}); looking again")
            settle(ctx)
            view = AreaNav.look(ctx) { f -> AreaNav.findIcons(ctx, f, "templates/cat_scratchbook/map_feather_icon.png") }
        }
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
        // the data wins over the icons (the cat's spot is fixed); a mismatch is only logged, and the
        // caller steers by the map only if the sweep there doesn't find the cat
        if (feather != null && (abs(feather.y - catSeg.y) > AreaNav.ROW_TOL * 2 || feather.x < lo - 0.03 || feather.x > hi + 0.03))
            MacroLog.i("${s.location}: the feather icon read at ${fmt(feather)} isn't where this cat starts or wanders " +
                "(a look-alike icon, or fix area.cat/drift); following the path anyway")
        // the trip didn't land at the usual arrival (Baruoki: two different spots on two days) but the
        // marker is on a street of the layout: walk the layout from there, reading the map as it goes
        val atArrival = party != null && abs(party.y - arrival.y) < AreaNav.ROW_TOL && abs(party.x - arrival.x) < 0.025
        var elsewhere = view.candidates.firstOrNull { Route.segOf(l, it) != null }
        if (!atArrival && elsewhere != null) {
            // a second look: the marker pulses and a gold map icon can pass for it (Ratle's inn), so
            // only a spot read twice, with nothing at the arrival, counts
            val again = AreaNav.look(ctx) { emptyList() }.candidates
            val near = again.any { abs(it.y - arrival.y) < AreaNav.ROW_TOL && abs(it.x - (arrival.x + shift)) < 0.025 }
            if (near || again.none { abs(it.x - elsewhere!!.x) < 0.02 && abs(it.y - elsewhere!!.y) < AreaNav.ROW_TOL }) elsewhere = null
        }
        if (!atArrival && elsewhere != null) {
            MacroLog.i("${s.location}: the party is at ${fmt(elsewhere)}, not the usual arrival ${fmt(arrival)}: walking the layout to the cat")
            settle(ctx)
            if (!AreaNav.walkLayout(ctx, l, cat, onStep = { fight() })) { MacroLog.i("${s.location}: couldn't walk the layout to the cat"); return null }
            return Triple(maxOf(catSeg.x1, cat.x - s.driftLeft), minOf(catSeg.x2, cat.x + s.driftRight), catSeg.y)
        }
        // only a marker read close to the arrival counts (in Ratle a gold inn icon at (0.548, 0.509)
        // passes the marker filter while the real marker is faint)
        val start = if (atArrival)
            Point(party!!.x, Route.segOf(l, party)?.y ?: arrival.y)
            else Point(arrival.x + shift, arrival.y).also { MacroLog.i("${s.location}: party marker not read at the arrival; taking the arrival spot") }
        val w = Walk(l, table(ctx, s.walkScale), start, Point(arrival.x + shift, arrival.y))
        settle(ctx) // the area map just closed
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
        val from = w.p
        val before = ctx.refresh().gray.clone()
        w.p = Route.hold(ctx, w.l, w.t, w.p, d > 0, w.t.holdMs(abs(d)).toLong(), fight)
        val after = ctx.refresh().gray.clone()
        val change = Route.change(before, after)
        before.release(); after.release()
        if (change < STILL && !Route.disturbed) {
            // the view didn't move: the hold came too soon (area banner, fade-in) and walked nothing;
            // hold it again from the same spot
            MacroLog.i("hold ${if (d > 0) "R" else "L"}: view change ${"%.1f".format(change)}, the party didn't move; again")
            settle(ctx)
            w.p = Route.hold(ctx, w.l, w.t, from, d > 0, w.t.holdMs(abs(d)).toLong(), fight)
        }
        if (Route.unseen) {
            // a battle came during the hold without being seen: read the spot and walk the rest
            Route.unseen = false
            resync(ctx, w, "a battle came during the hold")
            val rest = x - w.p.x
            if (abs(rest) >= w.t.minWalk) w.p = Route.hold(ctx, w.l, w.t, w.p, rest > 0, w.t.holdMs(abs(rest)).toLong(), fight)
        }
    }

    /** Less view change than this (mean grey level, 0-255) after a hold: the party didn't walk. */
    private const val STILL = 2.0

    /** On the field and still for a moment: right after a trip or the area map, a hold can be ignored. */
    private fun settle(ctx: Context) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) break
            ctx.wait(0.5)
        }
        ctx.wait(1.0)
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
