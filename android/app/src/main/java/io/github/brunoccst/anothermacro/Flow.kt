package io.github.brunoccst.anothermacro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * A macro defined as data (a .yaml file) instead of code.
 *
 *   name: grind-battles
 *   description: ...
 *   templates: [grind_battles]          # template folders besides "common" (and the user's own)
 *   params:  {battles: {default: 10, help: "..."}}
 *   screens: [battle_result, battle_command, field]   # priority order
 *   define_screens: {my_screen: {require: [my_template]}}   # optional, for your own screens
 *   unknown_timeout_s: 45
 *   setup:   [actions]                   # once at the start
 *   handlers: {field: [actions], battle_command: [actions]}
 *   unknown: [actions]                   # when no screen matches
 *
 * Actions (one key each):
 *   tap: template_name | {x: expr, y: expr}      tap_exact: {x: expr, y: expr}
 *   drag: {from: [x, y], to: [x, y], ms: expr}    walk: {dir: expr, ms: expr}   (-1 left, 1 right, 'up', 'down')
 *   back: true        wait: seconds        launch: package        log: "text {expr}"
 *   set: {var: expr, ...}       count: stat_name        stop: "text {expr}"
 *   if: expr  then: [actions]  else: [actions]          repeat: {times: expr, do: [actions]}
 *   reset_view: true            (forget the last view for view_moved())
 *   find_feather: true          (cat scratchbook: tap the feather bubble if visible; sets `tapped`)
 *   read_cat_clues: true        (cat scratchbook: sets `report` to today's clues and their places)
 *   cat_daily: true             (cat scratchbook: read clues by OCR, travel to each cat, find it; stops with a summary)
 *
 * Expression functions: found('t'), captured('t'), game_in_front(), view_moved(), now(), pick(i, a, b, ...)
 */
class FlowScenario(private val spec: Map<String, Any?>, val source: String) : Scenario() {
    override val name = spec["name"] as? String ?: throw IllegalArgumentException("$source: missing name")
    override val description = spec["description"] as? String ?: ""
    override val templateFolders: List<String> = list(spec["templates"]).map { it.toString() }
    override val params: Map<String, Param> = map(spec["params"]).entries.associate { (k, v) ->
        val m = mapOrNull(v)
        k.toString() to Param(Expr.str(m?.get("default") ?: v), m?.get("help")?.toString() ?: "")
    }
    override val screens: List<String> = list(spec["screens"]).map { it.toString() }
    override val unknownTimeoutS = (spec["unknown_timeout_s"] as? Number)?.toDouble() ?: 45.0
    val screenDefs: Map<String, ScreenDef> = map(spec["define_screens"]).entries.associate { (k, v) ->
        val m = mapOrNull(v)
        k.toString() to ScreenDef(k.toString(), list(m?.get("require")).map { it.toString() }, list(m?.get("reject")).map { it.toString() })
    }
    private val handlers = map(spec["handlers"]).entries.associate { (k, v) -> k.toString() to list(v) }

    private val vars = HashMap<String, Any?>()
    private var lastView: Mat? = null
    private var feather: FeatherFinder? = null

    override fun setup(ctx: Context) {
        vars.clear()
        lastView?.release(); lastView = null
        for ((k, v) in ctx.params) vars[k] = typed(v)
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
            "view_moved" -> viewMoved(ctx)
            "now" -> System.currentTimeMillis() / 1000.0
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
        const val FEATHER_MIN = 0.78
        const val TAP_LATENCY_S = 0.4

        @Suppress("UNCHECKED_CAST")
        private fun mapOrNull(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.let { it as Map<String, Any?> }
        private fun map(v: Any?): Map<String, Any?> = mapOrNull(v) ?: emptyMap()

        private fun list(v: Any?): List<Any?> = when (v) { null -> emptyList(); is List<*> -> v; else -> listOf(v) }

        /** Params arrive as text: make numbers numbers and true/false booleans. */
        fun typed(v: String): Any = v.trim().let { t ->
            t.toDoubleOrNull() ?: when (t.lowercase()) { "true", "yes", "on" -> true; "false", "no", "off" -> false; else -> t }
        }

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String, source: String): FlowScenario {
            val spec = mapOrNull(Load(LoadSettings.builder().build()).loadFromString(text))
                ?: throw IllegalArgumentException("$source: not a macro file")
            return FlowScenario(spec, source)
        }
    }
}
