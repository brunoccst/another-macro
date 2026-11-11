package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*
import io.github.brunoccst.anothermacro.FlowScenario.Companion.list
import io.github.brunoccst.anothermacro.FlowScenario.Companion.map
import io.github.brunoccst.anothermacro.FlowScenario.Companion.mapOrNull
import io.github.brunoccst.anothermacro.FlowScenario.Companion.runTarget
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Runs a YAML macro's steps (see the format at the top of FlowScenario in Flow.kt). Part of the
 * logic pack: a new step or expression function goes here and in [Entry.ACTIONS], and reaches the
 * phone without an app install.
 */
class FlowRun(private val scenario: FlowScenario) : FlowEngine {
    private val name = scenario.name
    private val spec = scenario.spec
    private val handlers = map(spec["handlers"]).entries.associate { (k, v) -> k.toString() to list(v) }
    private val vars = HashMap<String, Any?>()
    private var lastView: Mat? = null
    private var feather: FeatherFinder? = null

    override fun setup(ctx: Context) {
        vars.clear()
        lastView?.release(); lastView = null
        for ((k, v) in ctx.params) vars[k] = FlowScenario.typed(v)
        runActions(ctx, list(spec["setup"]))
    }

    override fun onScreen(ctx: Context, screen: String) = runActions(ctx, handlers[screen].orEmpty())
    override fun onUnknown(ctx: Context) = runActions(ctx, list(spec["unknown"]))

    // --- actions ---------------------------------------------------------------
    private fun runActions(ctx: Context, actions: List<Any?>) {
        for (a in actions) runAction(ctx, mapOrNull(a) ?: throw IllegalArgumentException("$name: action must be a map, got $a"))
    }

    private fun runAction(ctx: Context, a: Map<String, Any?>) {
        when {
            "if" in a -> runActions(ctx, list(if (truthy(ctx, a["if"])) a["then"] else a["else"]))
            "tap" in a -> when (val t = a["tap"]) {
                is String -> ctx.tap(t)
                else -> ctx.tap(point(ctx, t))
            }
            "tap_exact" in a -> ctx.tapExact(point(ctx, a["tap_exact"]))
            "drag" in a -> {
                val m = map(a["drag"])
                ctx.drag(point(ctx, m["from"]), point(ctx, m["to"]), num(ctx, m["ms"] ?: 300).toLong())
            }
            "walk" in a -> {
                // walking is a virtual stick: press mid-screen and slide toward the direction
                val m = map(a["walk"])
                val d = value(ctx, m["dir"])
                val (dx, dy) = when (Expr.str(d)) { "up" -> 0.0 to -0.08; "down" -> 0.0 to 0.08; else -> 0.06 * Expr.num(d) to 0.0 }
                ctx.drag(Point(0.5, 0.55), Point(0.5 + dx, 0.55 + dy), num(ctx, m["ms"] ?: 1500).toLong())
            }
            "back" in a -> ctx.back()
            "wait" in a -> ctx.wait(num(ctx, a["wait"]))
            "launch" in a -> ctx.launchApp(Expr.str(value(ctx, a["launch"])))
            "log" in a -> MacroLog.i(text(ctx, a["log"]))
            "status" in a -> MacroLog.status("$name: ${text(ctx, a["status"])}")
            "set" in a -> map(a["set"]).forEach { (k, v) -> vars[k.toString()] = value(ctx, v) }
            "count" in a -> ctx.count(a["count"].toString())
            "stop" in a -> ctx.stop(text(ctx, a["stop"]))
            "repeat" in a -> {
                val m = map(a["repeat"])
                repeat(num(ctx, m["times"]).toInt()) { runActions(ctx, list(m["do"])) }
            }
            "reset_view" in a -> { lastView?.release(); lastView = null }
            "find_feather" in a -> findFeather(ctx)
            "read_cat_clues" in a -> vars["report"] = readCatClues(ctx)
            "cat_daily" in a -> ctx.stop(CatDaily.run(ctx))
            "fight" in a -> {
                val prefix = a["fight"]?.toString()?.takeIf { it != "true" } ?: "skill_"
                val outcome = Battle.fight(ctx) { Battle.Setup.from(ctx.params, prefix) }
                vars["outcome"] = outcome.name.lowercase()
                if (outcome == Battle.Outcome.LOST) ctx.stop("party was defeated")
            }
            "run" in a -> {
                val target = runTarget(a["run"])
                val m = mapOrNull(a["run"])
                val sub = ctx.findMacro(target) ?: throw IllegalArgumentException("$name: run: unknown macro '$target'")
                val given = map(m?.get("params")).entries.associate { (k, v) -> k to Expr.str(value(ctx, v)) }
                val until = list(m?.get("until")).map { it.toString() }
                vars["result"] = Runner.runInside(sub, given, ctx, until)
            }
            "another_dungeon" in a -> ctx.stop(AnotherDungeon.run(ctx, a["another_dungeon"].toString()))
            "phantom_crystal" in a -> ctx.stop(PhantomCrystal.run(ctx))
            else -> throw IllegalArgumentException("$name: unknown action ${a.keys}")
        }
    }

    // --- builtins ----------------------------------------------------------------
    private fun findFeather(ctx: Context) {
        val ff = feather ?: FeatherFinder.load(ctx).also { feather = it }
        vars["tapped"] = false
        val f1 = ctx.frame ?: return
        val (score, p1) = ff.find(f1)
        if (score < FEATHER_MIN) return
        // the bubble rides on the cat, which keeps walking: lead its motion
        val t1 = System.currentTimeMillis()
        val (score2, p2) = ff.find(ctx.refresh())
        val target = if (score2 >= FEATHER_MIN) {
            val vx = (p2.x - p1.x) / ((System.currentTimeMillis() - t1).coerceAtLeast(50) / 1000.0)
            Point((p2.x + vx * TAP_LATENCY_S).coerceIn(0.02, 0.98), p2.y)
        } else p1
        ctx.tapExact(target)
        vars["tapped"] = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun readCatClues(ctx: Context): String {
        val riddles = try {
            val text = ctx.assets.open("templates/cat_scratchbook/riddles.yaml").bufferedReader().use { it.readText() }
            Load(LoadSettings.builder().build()).loadFromString(text) as? Map<String, Map<String, Any?>> ?: emptyMap()
        } catch (e: java.io.IOException) { emptyMap() }
        val lib = ctx.vision.library.templates
        val known = riddles.keys.filter { lib[it]?.available == true && ctx.find(it) != null }
        ctx.stats["known_riddles"] = known.size
        val lines = known.map { n ->
            val r = riddles.getValue(n)
            val where = (r["location"] as? String ?: "location unknown") + ((r["era"] as? String)?.let { " ($it)" } ?: "")
            "$where: ${r["text"] ?: n}"
        }.toMutableList()
        val unknown = (3 - known.size).coerceAtLeast(0)
        ctx.stats["unknown_riddles"] = unknown
        if (unknown > 0) lines += "$unknown clue(s) not recognised yet"
        return lines.joinToString("; ")
    }

    private fun viewMoved(ctx: Context): Boolean {
        val g = ctx.frame?.gray ?: return true
        val roi = g.submat(Rect(0, (g.rows() * 0.25).toInt(), (g.cols() * 0.8).toInt(), (g.rows() * 0.55).toInt()))
        val small = Mat()
        Imgproc.resize(roi, small, Size(160.0, 60.0), 0.0, 0.0, Imgproc.INTER_AREA)
        roi.release()
        val prev = lastView
        lastView = small
        if (prev == null) return true
        val diff = Mat()
        Core.absdiff(small, prev, diff)
        val m = Core.mean(diff).`val`[0]
        diff.release(); prev.release()
        return m > 4.0
    }

    // --- values --------------------------------------------------------------------
    private fun env(ctx: Context): Expr.Env {
        val macro = name
        return object : Expr.Env {
        override fun variable(name: String): Any? = when {
            name.startsWith("stats.") -> (ctx.stats[name.removePrefix("stats.")] ?: 0).toDouble()
            name in vars -> vars[name]
            else -> throw IllegalArgumentException("$macro: unknown name '$name'")
        }

        override fun call(name: String, args: List<Any?>): Any? = when (name) {
            "found" -> ctx.find(Expr.str(args[0])) != null
            "captured" -> ctx.vision.library.templates[Expr.str(args[0])]?.available == true
            "game_in_front" -> ctx.frame?.portraitAppInFront != true
            // a commercial in front: the game's package, but an activity other than the game's own
            "ad_in_front" -> InputService.instance?.let { s ->
                s.foreground == ClaudeSafety.GAME && s.foregroundActivity.let { a -> a != null && a != GAME_ACTIVITY }
            } ?: false
            "view_moved" -> viewMoved(ctx)
            "now" -> System.currentTimeMillis() / 1000.0
            // on-screen text (OCR) contains this, loosely (case and punctuation ignored)
            "text_has" -> Ocr.norm(Expr.str(args[0])).let { want -> ctx.readText().any { Ocr.norm(it.text).contains(want) } }
            // the text on screen (OCR), optionally only between heights y0..y1, for a log line (e.g. what a reward was)
            "screen_text" -> {
                val y0 = if (args.isNotEmpty()) Expr.num(args[0]) else 0.0
                val y1 = if (args.size > 1) Expr.num(args[1]) else 1.0
                ctx.readText().filter { it.center.y in y0..y1 }.joinToString(" ") { it.text }.take(160)
            }
            "pick" -> args[Expr.num(args[0]).toInt().coerceIn(1, args.size - 1)]
            "abs" -> kotlin.math.abs(Expr.num(args[0]))
            else -> throw IllegalArgumentException("$macro: unknown function $name()")
        }
        }
    }

    /** A YAML value: strings are expressions, numbers/booleans are themselves. */
    private fun value(ctx: Context, v: Any?): Any? = when (v) {
        is String -> Expr.eval(v, env(ctx))
        is Int, is Long -> (v as Number).toDouble()
        else -> v
    }

    private fun num(ctx: Context, v: Any?) = Expr.num(value(ctx, v))
    private fun truthy(ctx: Context, v: Any?) = Expr.truthy(value(ctx, v))

    /** "text with {expressions}" */
    private fun text(ctx: Context, v: Any?): String =
        Regex("""\{([^}]+)\}""").replace(v.toString()) { Expr.str(Expr.eval(it.groupValues[1], env(ctx))) }

    private fun point(ctx: Context, v: Any?): Point = when (v) {
        is List<*> -> Point(num(ctx, v[0]), num(ctx, v[1]))
        is Map<*, *> -> map(v).let { Point(num(ctx, it["x"]), num(ctx, it["y"])) }
        else -> throw IllegalArgumentException("$name: not a point: $v")
    }


    companion object {
        /** The game's own activity; a commercial plays in another activity of the game's package. */
        const val GAME_ACTIVITY = FlowScenario.GAME_ACTIVITY

        const val FEATHER_MIN = 0.78
        const val TAP_LATENCY_S = 0.4
    }
}
