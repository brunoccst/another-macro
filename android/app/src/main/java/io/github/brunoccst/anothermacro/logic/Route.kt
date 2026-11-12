package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * Walking a dungeon map by its fixed path (dungeons.yaml `path:`, made by
 * `another-macro dungeon-routes` from the layout) instead of looking at the area map.
 *
 * Steps: "R 1180" / "L 2950" hold right/left that many ms from rest; "U" / "D" flick
 * up/down at the stairs the party stands on. The party's position is followed on the
 * layout from where it arrived (the same rules as the planner, dungeon_routes.py):
 * a hold walks the distance of the hold-time table (walk.yaml; the party accelerates,
 * so it isn't time × speed), stopped by the street's end; a flick puts it on the
 * other end of the stairs.
 *
 * A hold is watched while the finger is down: when a battle starts (the field's menu
 * button goes away) the finger lifts, the time held until the battle is turned into a
 * distance, the battle is fought, and the rest is held again from rest (the party
 * stands still after a battle, so the ramp-up applies again).
 */
object Route {
    sealed class Step {
        data class Hold(val right: Boolean, val ms: Long) : Step() { override fun toString() = "${if (right) "R" else "L"} $ms" }
        data class Flick(val up: Boolean) : Step() { override fun toString() = if (up) "U" else "D" }
    }

    /** One stop of a path: a chest ([chest], 0-based) or the exit, and the steps to it. */
    data class Leg(val target: String, val chest: Int?, val steps: List<Step>)

    fun parseLeg(raw: List<*>): Leg {
        val target = raw.first().toString().trim()
        val chest = Regex("""^chest\s+(\d+)$""").find(target)?.groupValues?.get(1)?.toInt()?.minus(1)
        val steps = raw.drop(1).map { s ->
            val t = s.toString().trim().split(Regex("\\s+"))
            when (t[0]) {
                "R", "L" -> Step.Hold(t[0] == "R", t[1].toDouble().toLong())
                "U", "D" -> Step.Flick(t[0] == "U")
                else -> throw IllegalArgumentException("unknown path step '$s'")
            }
        }
        return Leg(target, chest, steps)
    }

    /** walk.yaml: hold time from rest ↔ distance on the area map. */
    class Table(
        private val ms: DoubleArray, private val dist: DoubleArray, private val beyondPerS: Double,
        val minWalk: Double, val wallMargin: Double, val lagMs: Long, val flickChange: Double,
        val hudLagMs: Long = 150, val darkLevel: Double = 30.0, val stillChange: Double = 1.5,
    ) {
        fun distance(holdMs: Double): Double {
            if (holdMs <= 0) return 0.0
            if (holdMs >= ms.last()) return dist.last() + (holdMs - ms.last()) / 1000 * beyondPerS
            val i = ms.indexOfFirst { it > holdMs }
            return dist[i - 1] + (dist[i] - dist[i - 1]) * (holdMs - ms[i - 1]) / (ms[i] - ms[i - 1])
        }

        fun holdMs(d: Double): Double {
            if (d <= 0) return 0.0
            if (d >= dist.last()) return ms.last() + (d - dist.last()) / beyondPerS * 1000
            val i = dist.indexOfFirst { it > d }
            return ms[i - 1] + (ms[i] - ms[i - 1]) * (d - dist[i - 1]) / (dist[i] - dist[i - 1])
        }
    }

    private var cached: Table? = null

    @Suppress("UNCHECKED_CAST")
    fun table(ctx: Context): Table = cached ?: run {
        val text = ctx.assets.open("templates/another_dungeon/walk.yaml").bufferedReader().use { it.readText() }
        val y = Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Any?>
        fun nums(k: String) = (y[k] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        fun num(k: String, d: Double) = (y[k] as? Number)?.toDouble() ?: d
        Table(nums("hold_ms"), nums("distance"), num("beyond_per_s", 0.026), num("min_walk", 0.003), num("wall_margin", 0.008),
            num("battle_lag_ms", 0.0).toLong(), num("flick_change", 8.0),
            num("battle_hud_lag_ms", 150.0).toLong(), num("dark_level", 30.0), num("still_change", 1.5)).also { cached = it }
    }

    // --- following the party on the layout -------------------------------------------
    fun segOf(l: AreaNav.Layout, p: Point): AreaNav.Seg? = l.segs.filter { abs(it.y - p.y) < AreaNav.ROW_TOL * 1.5 && p.x > it.x1 - 0.04 && p.x < it.x2 + 0.04 }
        .minByOrNull { abs(it.y - p.y) + maxOf(0.0, it.x1 - p.x, p.x - it.x2) }

    /** Where a walk of [d] right/left from [p] ends: stopped by the street's end. */
    fun walked(l: AreaNav.Layout, p: Point, right: Boolean, d: Double): Point {
        val s = segOf(l, p) ?: return Point(p.x + (if (right) d else -d), p.y)
        return Point((p.x + (if (right) d else -d)).coerceIn(s.x1, s.x2), s.y)
    }

    private fun linkSeg(l: AreaNav.Layout, x: Double, y: Double) =
        l.segs.firstOrNull { abs(it.y - y) < 0.02 && x in it.x1 - 0.02..it.x2 + 0.02 }

    /** The far end of the stairs nearest [p] going up/down (within [tol]), or null. */
    fun climbed(l: AreaNav.Layout, p: Point, up: Boolean, tol: Double = 0.02): Point? {
        val here = segOf(l, p) ?: return null
        var best: Pair<Double, Point>? = null
        for (ln in l.links) {
            val a = linkSeg(l, ln.x, ln.y1) ?: continue
            val b = linkSeg(l, ln.x, ln.y2) ?: continue
            val there = when (here) { a -> b; b -> a; else -> continue }
            if ((there.y < here.y) != up || abs(ln.x - p.x) > tol) continue
            if (best == null || abs(ln.x - p.x) < best.first) best = abs(ln.x - p.x) to Point(ln.x, there.y)
        }
        return best?.second
    }

    /**
     * Where the party is, read from the minimap ([MiniMap]) or, when that isn't clear, from the
     * area map ([areaMap], ~3 s). [near]: where it should be (fits far from these are left out).
     * With [MiniMap.compare] on, the area map is read too and both are logged (its read is used).
     */
    fun seen(ctx: Context, l: AreaNav.Layout, near: List<Point>, why: String, areaMap: () -> Point?): Point? {
        val t0 = System.currentTimeMillis()
        val mini = try { MiniMap.locate(ctx, l, near) } catch (e: Exception) { MacroLog.i("minimap: ${e.message}"); null }
        if (mini != null && !MiniMap.compare) {
            MacroLog.i("$why: the minimap shows (${"%.3f".format(mini.at.x)}, ${"%.3f".format(mini.at.y)}) (${System.currentTimeMillis() - t0}ms)")
            return mini.at
        }
        val area = areaMap()
        if (MiniMap.compare) MacroLog.i("minimap check ($why): minimap ${mini?.let { "(${"%.3f".format(it.at.x)}, ${"%.3f".format(it.at.y)}) score ${"%.2f".format(it.score)}/${"%.2f".format(it.other)}" } ?: "none"}, " +
            "area map ${area?.let { "(${"%.3f".format(it.x)}, ${"%.3f".format(it.y)})" } ?: "none"}" +
            (if (mini != null && area != null) ", off by ${"%.3f".format(mini.at.x - area.x)}" else ""))
        return area ?: mini?.at
    }

    // --- doing it --------------------------------------------------------------------
    private val PRESS = Point(0.5, 0.65)

    /**
     * Frame-to-frame view change below this is a standing party; above [WALK_STEP] it walks.
     * Walking reads 10-24 a frame; the encounter's screen stays at 0-6 (it shimmers a little)
     * (three castle traces, 2026-11-12).
     */
    private const val STILL_STEP = 7.0
    private const val WALK_STEP = 12.0
    /** Frames (about 40 ms each) of a still view, after walking, that end a hold to look for a battle. */
    private const val STILL_FRAMES = 6
    /** A still view this long (ms) after walking ends a hold to look for a battle. */
    private const val STILL_MS = 450L
    /** A hold's first walking frame later than this (ms) means the party started late... */
    private const val LATE_START_MS = 1000L
    /** ...by its time less this (when the first walking frame usually shows). */
    private const val NORMAL_START_MS = 700L

    /** A hold was cut short by a battle since this was last cleared: the position is less sure. */
    @Volatile var disturbed = false
    /** The last hold ran its full time, but a battle was there right after it (not seen during it). */
    @Volatile var unseen = false

    /**
     * Hold right/left for [ms] from [from], watching for a battle. A battle that interrupts
     * the hold is fought with [fight] and the rest of the distance is held again from rest.
     * Returns where the party is now, by the table.
     */
    fun hold(ctx: Context, l: AreaNav.Layout, t: Table, from: Point, right: Boolean, ms: Long, fight: () -> Boolean): Point {
        var p = from
        var left = t.distance(ms.toDouble())
        var holdMs = ms
        var cancels = 0
        var press = PRESS
        var noEffect = 0
        repeat(10) {
            // a battle is told by its own look (the screen going dark for the encounter, the battle
            // HUD), never by the field's buttons: the game hides those while the party walks
            var lastField = 0L
            var frames = 0
            var dark = 0
            // the encounter on the castle (trace 2026-11-12): the walking view (change 10-20 a frame)
            // stops dead, then a white flash (brightness ~65 → 139), then a still screen for seconds
            // before the battle HUD shows. Stop on the flash, or on a still view after walking
            var walking = false
            var still = 0
            var lastWalk = 0L
            var firstWalk = 0L
            var baseLevel = -1.0
            var why: String? = null
            var firstGray: org.opencv.core.Mat? = null
            var lastGray: org.opencv.core.Mat? = null
            // per frame: ms held, brightness, change from the frame before (logged when a battle
            // came that these checks missed, to learn what the encounter looks like)
            val trace = StringBuilder()
            var prevMs = -1000L
            val res = ctx.hold(press, Point(press.x + if (right) 0.1 else -0.1, press.y), holdMs) { held ->
                val f = ctx.refresh()
                frames++
                // the first and the latest view: a hold that walked scrolls the view
                val step = lastGray ?: firstGray
                val stepChange = step?.let { change(it, f.gray) } ?: 0.0
                if (firstGray == null) firstGray = f.gray.clone() else { lastGray?.release(); lastGray = f.gray.clone() }
                val level = org.opencv.core.Core.mean(f.gray).`val`[0]
                if (held - prevMs >= 200) { trace.append(" ").append(held).append(":").append("%.0f".format(level)).append("/").append("%.0f".format(stepChange)).append(if (walking) "w" else "").append(still); prevMs = held }
                when {
                    Battle.inBattle(ctx) -> { why = "battle screen"; true }
                    level < t.darkLevel -> { if (++dark >= 2) why = "dark screen (${"%.0f".format(level)})"; dark >= 2 }
                    baseLevel > 0 && level > 100 && level > baseLevel * 1.6 -> { why = "flash (${"%.0f".format(level)})"; true }
                    // by time, not frames: the capture sends few frames while the screen stands still
                    walking && stepChange < STILL_STEP && held - lastWalk >= STILL_MS -> { why = "view stood still"; true }
                    else -> {
                        dark = 0
                        // (a single shimmer frame of 7-11 in the encounter's still screen doesn't restart the count:
                        // seen 2026-11-12, a still screen for over a second that never ended the hold)
                        // (the screen brightening toward the flash moves too: not walking)
                        // only a walking frame (>= WALK_STEP) marks where the party still walked: a shimmer or the
                        // brightening before the flash (7-11) came up to a second after the party stopped
                        if (stepChange >= WALK_STEP && (baseLevel < 0 || level < baseLevel + 8)) { if (!walking) firstWalk = held; lastField = held; lastWalk = held; walking = true; still = 0 }
                        else if (stepChange < STILL_STEP) still++
                        baseLevel = if (baseLevel < 0) level else baseLevel * 0.9 + level * 0.1
                        false
                    }
                }
            }
            ctx.wait(0.4) // standing still again before the next hold
            val moved = if (firstGray != null && lastGray != null) change(firstGray!!, lastGray!!) else -1.0
            firstGray?.release(); lastGray?.release()
            val interrupted0 = res.stoppedAtMs != null || res.cancelled
            if (!interrupted0 && holdMs >= 600 && moved in 0.0..t.stillChange && noEffect < 2) {
                // the view barely moved: check on the map whether the party walked at all (the view
                // also stands still where the camera stops at a map's edge)
                val seen = seen(ctx, l, listOf(p), "the view barely moved") { AreaNav.look(ctx) { emptyList() }.candidates.filter { segOf(l, it) != null }
                    .minByOrNull { abs(it.x - p.x) + 2 * abs(it.y - p.y) } }
                if (seen != null && abs(seen.x - p.x) < 0.006 && abs(seen.y - p.y) < AreaNav.ROW_TOL) {
                    // it didn't (seen right after a flick onto a street): wait, press a little higher, hold again
                    noEffect++
                    MacroLog.i("hold ${if (right) "R" else "L"} $holdMs walked nothing (view change ${"%.1f".format(moved)}, map x ${"%.3f".format(seen.x)}): again from (0.5, 0.55)")
                    press = Point(0.5, 0.55)
                    ctx.wait(1.0)
                    return@repeat
                }
                if (seen != null) {
                    val s = segOf(l, seen)!!
                    MacroLog.i("hold ${if (right) "R" else "L"} $holdMs: the view barely moved but the map shows x ${"%.3f".format(seen.x)}")
                    return Point(seen.x.coerceIn(s.x1, s.x2), s.y)
                }
            }
            val interrupted = interrupted0
            // the flash (or the dead stop before it) comes seconds before the battle screen: wait for it
            // rather than taking the quiet screen for "no battle" and holding on into the encounter
            val battle = interrupted && (fight() || ((why?.startsWith("flash") == true || why == "view stood still") && awaitBattle(ctx) && fight()))
            val lag = if (why == "battle screen") t.hudLagMs else t.lagMs
            // the party sometimes starts late (right after a chest's popup the hold did nothing for ~1 s,
            // 2026-11-12); a walk usually shows by 0.5-0.9 s: count a later start as time not walked
            val lateStart = if (frames > 5 && firstWalk > LATE_START_MS) firstWalk - NORMAL_START_MS else 0L
            if (lateStart > 0) MacroLog.i("hold ${if (right) "R" else "L"} $holdMs: the party started walking late (${firstWalk}ms)")
            val walkedMs = when {
                !interrupted -> holdMs - lateStart
                // the party stops when the battle starts: the last frame that still looked like the field
                battle -> (lastField - lag - lateStart).coerceIn(0L, holdMs)
                else -> res.heldMs.coerceAtMost(holdMs)
            }
            MacroLog.i("hold ${if (right) "R" else "L"} $holdMs: ${frames} frames, held ${res.heldMs}ms, view change ${"%.1f".format(moved)}" +
                (if (res.stoppedAtMs != null) ", stopped at ${res.stoppedAtMs}ms: ${why ?: "?"}" else "") +
                (if (res.cancelled) ", gesture cancelled by the system" else "") +
                (if (interrupted) if (battle) ", battle fought" else ", no battle came" else ""))
            // what the screen did up to an encounter's flash (to learn why the dead stop before it wasn't caught)
            if (battle && why?.startsWith("flash") == true) MacroLog.i("hold trace (ms:brightness/change):$trace; view last moved at ${lastField}ms")
            val d = t.distance(walkedMs.toDouble())
            val pBefore = p
            val leftBefore = left
            p = walked(l, p, right, d)
            left -= d
            if (!interrupted) {
                // a battle the screen checks missed: the party stopped somewhere along the way. Its
                // screen can take seconds to show (the flash comes ~2 s after the party stops dead):
                // wait until it does or the field's menu is back
                val stoppedEarly = frames > 5 && lastField < holdMs - 250
                if (fight() || (stoppedEarly && awaitBattle(ctx, 6.0) && fight())) {
                    MacroLog.i("hold trace (ms:brightness/change):$trace")
                    if (stoppedEarly) {
                        // the view stopped moving before the hold's end: that's where the encounter stopped the party
                        val w = (lastField - t.lagMs - lateStart).coerceIn(0L, holdMs)
                        val dw = t.distance(w.toDouble())
                        p = walked(l, pBefore, right, dw)
                        left = leftBefore - dw
                        // (usually within ~0.005 of the area map's, but once 0.05 off after three battles in a
                        // row, 2026-11-12: stairs still read the map first)
                        disturbed = true
                        MacroLog.i("a battle came after ${w}ms of the ${holdMs}ms hold (the view stopped there); " +
                            "${"%.3f".format(left.coerceAtLeast(0.0))} left from (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})")
                        if (left < t.minWalk) return p
                        holdMs = t.holdMs(left).toLong()
                        return@repeat
                    }
                    if (frames > 5) {
                        // the view moved to the hold's end: the battle came as it ended, the party is where the hold put it
                        MacroLog.i("a battle came right as the hold ended: keeping (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})")
                        return p
                    }
                    MacroLog.i("a battle came during the hold without being seen: the position is unknown")
                    disturbed = true; unseen = true
                }
                return p
            }
            if (!battle && why == "view stood still") {
                // no battle: the party walked into a wall, or the camera stopped at the map's edge while
                // the party walks on. Read the spot on the map; hold on for the rest only when it's short
                val seen = seen(ctx, l, listOf(p), "stood still") { AreaNav.look(ctx) { emptyList() }.candidates.filter { segOf(l, it) != null }
                    .minByOrNull { abs(it.x - p.x) + 2 * abs(it.y - p.y) } }
                MacroLog.i("walk ${if (right) "right" else "left"} stood still after ${walkedMs}ms of $holdMs with no battle; the map shows ${seen?.let { "x ${"%.3f".format(it.x)}" } ?: "no marker"}")
                if (seen == null) return p
                val s = segOf(l, seen)!!
                p = Point(seen.x.coerceIn(s.x1, s.x2), s.y)
                val atEnd = if (right) p.x >= s.x2 - t.minWalk else p.x <= s.x1 + t.minWalk
                if (atEnd) return p
                // not at the street's end: the camera stopped, the party didn't; hold the rest unwatched for stillness
                val rest = t.distance(ms.toDouble()) - abs(p.x - from.x)
                if (rest < t.minWalk) return p
                ctx.drag(PRESS, Point(PRESS.x + if (right) 0.1 else -0.1, PRESS.y), t.holdMs(rest).toLong())
                ctx.wait(0.4)
                if (fight()) { disturbed = true; unseen = true; return p }
                return walked(l, p, right, rest)
            }
            // a battle seen during the hold stopped the party where the view stopped: the spot is followed
            // well enough (see above); anything else leaves it less sure
            disturbed = true
            MacroLog.i("walk ${if (right) "right" else "left"} stopped after ${walkedMs}ms of $holdMs" +
                (if (battle) " (battle)" else if (res.cancelled) " (gesture cancelled)" else "") +
                "; ${"%.3f".format(left.coerceAtLeast(0.0))} left from (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})")
            if (!battle && res.cancelled && ++cancels >= 2) {
                // the system keeps cancelling the watched hold: hold the rest in one plain stroke
                if (left >= t.minWalk) {
                    val rest = t.holdMs(left).toLong()
                    ctx.drag(PRESS, Point(PRESS.x + if (right) 0.1 else -0.1, PRESS.y), rest)
                    ctx.wait(0.4)
                    fight()
                    p = walked(l, p, right, left)
                }
                return p
            }
            if (left < t.minWalk) return p
            holdMs = t.holdMs(left).toLong()
        }
        return p
    }

    /**
     * After a hold stopped on an encounter's look: true once the battle screen shows (within
     * [seconds]). False when the field's menu button has stayed for [fieldMs] with no flash: the
     * party just stands there. (The encounter's frozen field can show the menu button too, for up
     * to ~2 s before its white flash; after the flash the battle is sure, so it's waited for.)
     */
    fun awaitBattle(ctx: Context, seconds: Double = 6.0, fieldMs: Long = 2500): Boolean {
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        var fieldSince = 0L
        var base = -1.0
        var flash = false
        while (System.currentTimeMillis() < end) {
            val f = ctx.refresh()
            if (Battle.inBattle(ctx)) return true
            val level = org.opencv.core.Core.mean(f.gray).`val`[0]
            if (base > 0 && level > 100 && level > base * 1.6) flash = true
            base = if (base < 0) level else minOf(base, level)
            val now = System.currentTimeMillis()
            if (!flash && ctx.find("field_menu_button") != null) { if (fieldSince == 0L) fieldSince = now else if (now - fieldSince >= fieldMs) return false }
            else fieldSince = 0L
            ctx.wait(0.15)
        }
        return false
    }

    /** How different two frames are (mean grey-level difference, small copies): a street change moves the whole view. */
    fun change(a: org.opencv.core.Mat, b: org.opencv.core.Mat): Double {
        val sa = org.opencv.core.Mat(); val sb = org.opencv.core.Mat(); val d = org.opencv.core.Mat()
        val size = org.opencv.core.Size(a.cols() / 8.0, a.rows() / 8.0)
        org.opencv.imgproc.Imgproc.resize(a, sa, size, 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
        org.opencv.imgproc.Imgproc.resize(b, sb, size, 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
        org.opencv.core.Core.absdiff(sa, sb, d)
        val v = org.opencv.core.Core.mean(d).`val`[0]
        listOf(sa, sb, d).forEach { it.release() }
        return v
    }
}
