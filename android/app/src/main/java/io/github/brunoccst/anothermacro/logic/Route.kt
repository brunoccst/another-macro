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

    // --- doing it --------------------------------------------------------------------
    private val PRESS = Point(0.5, 0.65)

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
                if (held - prevMs >= 200) { trace.append(" ").append(held).append(":").append("%.0f".format(level)).append("/").append("%.0f".format(stepChange)); prevMs = held }
                when {
                    Battle.inBattle(ctx) -> { why = "battle screen"; true }
                    level < t.darkLevel -> { if (++dark >= 2) why = "dark screen (${"%.0f".format(level)})"; dark >= 2 }
                    else -> { dark = 0; lastField = held; false }
                }
            }
            ctx.wait(0.4) // standing still again before the next hold
            val moved = if (firstGray != null && lastGray != null) change(firstGray!!, lastGray!!) else -1.0
            firstGray?.release(); lastGray?.release()
            val interrupted0 = res.stoppedAtMs != null || res.cancelled
            if (!interrupted0 && holdMs >= 600 && moved in 0.0..t.stillChange && noEffect < 2) {
                // the view barely moved: check on the map whether the party walked at all (the view
                // also stands still where the camera stops at a map's edge)
                val seen = AreaNav.look(ctx) { emptyList() }.candidates.filter { segOf(l, it) != null }
                    .minByOrNull { abs(it.x - p.x) + 2 * abs(it.y - p.y) }
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
            val battle = interrupted && fight()
            val lag = if (why == "battle screen") t.hudLagMs else t.lagMs
            val walkedMs = when {
                !interrupted -> holdMs
                // the party stops when the battle starts: the last frame that still looked like the field
                battle -> (lastField - lag).coerceIn(0L, holdMs)
                else -> res.heldMs.coerceAtMost(holdMs)
            }
            MacroLog.i("hold ${if (right) "R" else "L"} $holdMs: ${frames} frames, held ${res.heldMs}ms, view change ${"%.1f".format(moved)}" +
                (if (res.stoppedAtMs != null) ", stopped at ${res.stoppedAtMs}ms: ${why ?: "?"}" else "") +
                (if (res.cancelled) ", gesture cancelled by the system" else "") +
                (if (interrupted) if (battle) ", battle fought" else ", no battle came" else ""))
            val d = t.distance(walkedMs.toDouble())
            p = walked(l, p, right, d)
            left -= d
            if (!interrupted) {
                // a battle the screen checks missed: the party stopped somewhere along the way
                if (fight()) {
                    MacroLog.i("a battle came during the hold without being seen: the position is unknown")
                    MacroLog.i("hold trace (ms:brightness/change):$trace")
                    disturbed = true; unseen = true
                }
                return p
            }
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
