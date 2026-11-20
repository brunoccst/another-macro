package anothermacro.logic

import macroforge.*

import kotlin.math.abs

/**
 * Measuring a cat spot that has no fixed route yet (cat_spots.yaml `area: null`), right after the
 * trip, before the hunt: what a person used to measure by hand for each spot.
 *
 *  1. one look at the area map: the streets and stairs (AreaNav.streetsOf), the party marker
 *     (the arrival) and the feather (the cat's start);
 *  2. the walk scale: hold R (or L, whichever side of the street has room) for 3000 ms from rest
 *     and compare the distance the marker moved with walk.yaml's (each area map has its own zoom);
 *  3. the drift: the feather read a few more times, a few seconds apart.
 *
 * The result is logged as one "MEASURED cat spot: {json}" line (macro_status with
 * find "MEASURED cat spot" shows it) and kept in the app's storage (cat_spots_measured.json), so
 * a spot is measured once. `another-macro cat-routes --import <log>` turns the lines into
 * cat_spots.yaml areas and fixed paths; a person checks them first (the street reading is
 * approximate), and the next live run of that cat tests the route.
 */
object CatSurvey {
    private const val FEATHER_ICON = "templates/cat_scratchbook/map_feather_icon.png"
    private const val SCALE_HOLD_MS = 3000L
    /** Seconds between the drift reads of the feather. */
    private const val DRIFT_GAP_S = 5.0
    private const val DRIFT_READS = 3
    private const val MIN_DRIFT = 0.015

    private fun file(ctx: Context) = java.io.File(ctx.debugDir.parentFile, "cat_spots_measured.json")

    private fun saved(ctx: Context): org.json.JSONObject = runCatching {
        file(ctx).takeIf { it.exists() }?.let { org.json.JSONObject(it.readText()) }
    }.getOrNull() ?: org.json.JSONObject()

    /** Whether this phone measured [location] already (the line is logged again instead). */
    fun measured(ctx: Context, location: String): org.json.JSONObject? = saved(ctx).optJSONObject(location)

    /** The log line a repo thread imports (`another-macro cat-routes --import`). */
    fun line(m: org.json.JSONObject) = "MEASURED cat spot: $m"

    /**
     * Measure the spot the party just arrived at for [location]; null when the area map didn't
     * give enough (no party marker or no streets). [fight] fights a battle that comes.
     */
    fun measure(ctx: Context, location: String, fight: () -> Boolean): org.json.JSONObject? {
        measured(ctx, location)?.let {
            MacroLog.i("$location: measured on an earlier run, not again (import it into cat_spots.yaml)")
            MacroLog.i(line(it))
            return null
        }
        MacroLog.status("cat-scratchbook: measuring $location for a fixed route")
        settle(ctx)
        val notes = ArrayList<String>()
        // 1. streets, arrival, cat
        val first = AreaNav.look(ctx, readText = true) { f -> AreaNav.findIcons(ctx, f, FEATHER_ICON) }
        val title = first.text.filter { it.center.y < 0.12 }.joinToString(" ") { it.text }.trim()
        val layout = first.streets()
        val party = first.candidates.firstOrNull()
        if (layout == null || layout.segs.isEmpty() || party == null) {
            MacroLog.i("$location: survey stopped: ${if (party == null) "no party marker" else "no streets"} on the area map")
            return null
        }
        val arrivalSeg = Route.segOf(layout, party)
        val arrival = Point(party.x, arrivalSeg?.y ?: party.y)
        if (arrivalSeg == null) notes += "the party marker is on no street read"
        val feathers = first.targets
        // the cat's feather: the best match on a street (other icons can match too)
        val cat0 = feathers.firstOrNull { Route.segOf(layout, it) != null } ?: feathers.firstOrNull()
        if (cat0 == null) notes += "no feather on the area map (a quest marker may hide it)"
        val catSeg = cat0?.let { Route.segOf(layout, it) }
        if (cat0 != null && catSeg == null) notes += "the feather is on no street read (another floor?)"
        MacroLog.i("$location: survey: ${layout.segs.size} streets, ${layout.links.size} stairs, party ${fmt(party)}, " +
            "feather ${cat0?.let { fmt(it) } ?: "not seen"}${if (title.isNotEmpty()) ", map '$title'" else ""}")

        // 2. walk scale: R/L 3000 from rest along the arrival's street, where there's room
        val table = CatRoute.table(ctx, 1.0)
        val expect = table.distance(SCALE_HOLD_MS.toDouble())
        var scale: Double? = null
        val xs = ArrayList<Double>()
        cat0?.let { xs += it.x }
        if (arrivalSeg != null) {
            val roomR = arrivalSeg.x2 - arrival.x
            val roomL = arrival.x - arrivalSeg.x1
            val right = roomR >= roomL
            val room = maxOf(roomR, roomL)
            if (room < expect * 0.6) notes += "no room on the arrival street for the walk-scale hold"
            else {
                Route.disturbed = false; Route.unseen = false
                Route.hold(ctx, layout, table, arrival, right, SCALE_HOLD_MS, fight)
                val battle = Route.disturbed || Route.unseen
                Route.disturbed = false; Route.unseen = false
                settle(ctx)
                val v = AreaNav.look(ctx) { f -> AreaNav.findIcons(ctx, f, FEATHER_ICON) }
                val p = v.candidates.filter { abs(it.y - arrival.y) < AreaNav.ROW_TOL }.minByOrNull { abs(it.x - arrival.x - (if (right) expect else -expect)) }
                feather(v.targets, cat0, catSeg)?.let { xs += it.x }
                when {
                    battle -> notes += "a battle came during the walk-scale hold"
                    p == null -> notes += "party marker not seen after the walk-scale hold"
                    else -> {
                        val moved = abs(p.x - arrival.x)
                        val atEnd = (if (right) arrivalSeg.x2 - p.x else p.x - arrivalSeg.x1) < 0.008
                        val s = moved / expect
                        MacroLog.i("$location: hold ${if (right) "R" else "L"} $SCALE_HOLD_MS walked ${"%.4f".format(moved)} (walk scale ${"%.2f".format(s)}${if (atEnd) ", stopped at the street's end" else ""})")
                        if (atEnd) notes += "the walk-scale hold reached the street's end (scale is a minimum)"
                        if (s in 0.3..3.0) scale = Math.round(s * 100) / 100.0 else notes += "walk scale ${"%.2f".format(s)} out of range"
                    }
                }
            }
        }

        // 3. drift: more reads of the feather, a few seconds apart
        if (cat0 != null) repeat(DRIFT_READS) {
            ctx.wait(DRIFT_GAP_S)
            settle(ctx)
            feather(AreaNav.look(ctx) { f -> AreaNav.findIcons(ctx, f, FEATHER_ICON) }.targets, cat0, catSeg)?.let { xs += it.x }
        }

        val o = org.json.JSONObject()
        o.put("location", location)
        if (title.isNotEmpty()) o.put("map", title)
        o.put("segs", org.json.JSONArray(layout.segs.map { org.json.JSONArray(listOf(r(it.y), r(it.x1), r(it.x2))) }))
        o.put("links", org.json.JSONArray(layout.links.map { org.json.JSONArray(listOf(r(it.x), r(it.y1), r(it.y2))) }))
        o.put("arrival", org.json.JSONArray(listOf(r(arrival.x), r(arrival.y))))
        if (cat0 != null) {
            o.put("cat", org.json.JSONArray(listOf(r(cat0.x), r(catSeg?.y ?: cat0.y))))
            val lo = xs.minOrNull() ?: cat0.x
            val hi = xs.maxOrNull() ?: cat0.x
            o.put("drift", org.json.JSONArray(listOf(r(maxOf(MIN_DRIFT, cat0.x - lo)), r(maxOf(MIN_DRIFT, hi - cat0.x)))))
            o.put("feather_reads", org.json.JSONArray(xs.map { r(it) }))
        }
        scale?.let { o.put("walk_scale", it) }
        if (notes.isNotEmpty()) o.put("notes", org.json.JSONArray(notes))
        o.put("date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
        val all = saved(ctx).put(location, o)
        runCatching { file(ctx).writeText(all.toString(1)) }.onFailure { MacroLog.e("could not save the cat survey", it) }
        MacroLog.i(line(o))
        ctx.count("cat_spots_measured")
        return o
    }

    /** The feather read nearest the first one, on the same street (the cat stays on its street). */
    private fun feather(targets: List<Point>, cat0: Point?, seg: AreaNav.Seg?): Point? {
        if (cat0 == null) return null
        return targets.filter { abs(it.y - (seg?.y ?: cat0.y)) < AreaNav.ROW_TOL && (seg == null || (it.x >= seg.x1 - 0.02 && it.x <= seg.x2 + 0.02)) }
            .minByOrNull { abs(it.x - cat0.x) }
    }

    /** On the field and still for a moment (right after a trip or the area map, a hold can be ignored). */
    private fun settle(ctx: Context) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) break
            ctx.wait(0.5)
        }
        ctx.wait(1.0)
    }

    private fun r(v: Double) = Math.round(v * 1000) / 1000.0
    private fun fmt(p: Point) = "(${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})"
}
