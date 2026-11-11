package io.github.brunoccst.anothermacro

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
 *   status: "text {expr}"       (log it and show it on the live log: what the macro is doing now)
 *   set: {var: expr, ...}       count: stat_name        stop: "text {expr}"
 *   if: expr  then: [actions]  else: [actions]          repeat: {times: expr, do: [actions]}
 *   reset_view: true            (forget the last view for view_moved())
 *   fight: skill_       (fight the battle on screen until the field is back, with skills from params
 *                        skill_1..skill_4 = auto|1-4; sets `outcome` to won/lost/none; stops on a wipe)
 *   run: watch-ad | {macro: watch-ad, params: {ad_wait_s: expr}, until: [shop]}
 *                       (run another macro here, then go on: the basic macros are made for this. It ends
 *                        with its own stop, or when one of [until] (this macro's screens) shows; sets
 *                        `result` to how it ended. A stop request or a safety stop ends this macro too)
 *   another_dungeon: dogma_tower   (run that Another Dungeon; see AnotherDungeon.kt)
 *   phantom_crystal: true          (the Phantom Crystal Dimension with a White Key; see PhantomCrystal.kt)
 *   find_feather: true          (cat scratchbook: tap the feather bubble if visible; sets `tapped`)
 *   read_cat_clues: true        (cat scratchbook: sets `report` to today's clues and their places)
 *   cat_daily: true             (cat scratchbook: read clues by OCR, travel to each cat, find it; stops with a summary)
 *
 * The steps run in logic/FlowRun.kt, which ships in the downloadable logic pack (see Logic.kt).
 *
 * Expression functions: found('t'), captured('t'), text_has('s'), screen_text(y0, y1), game_in_front(), ad_in_front(), view_moved(), now(), pick(i, a, b, ...)
 */
class FlowScenario(val spec: Map<String, Any?>, val source: String,
                   /** Saved by Claude through the phone connector, not from the macro library: listed under Custom. */
                   val custom: Boolean = false) : Scenario() {
    override val name = spec["name"] as? String ?: throw IllegalArgumentException("$source: missing name")
    override val description = spec["description"] as? String ?: ""
    /** The group the file asks for: for a custom macro, where it belongs once it's published to the library. */
    val declaredGroup = spec["group"] as? String ?: "other"
    override val group = if (custom) "custom" else declaredGroup
    override val icon = spec["icon"] as? String ?: ""
    override val templateFolders: List<String> = list(spec["templates"]).map { it.toString() }
    override val params: Map<String, Param> = map(spec["params"]).entries.associate { (k, v) ->
        val m = mapOrNull(v)
        val choices = list(m?.get("choices")).map { Expr.str(it) }
        val default = m?.get("default") ?: v
        val type = m?.get("type")?.toString() ?: if (default is Boolean) "bool" else "text"
        k.toString() to Param(Expr.str(default), m?.get("help")?.toString() ?: "", choices, type, m?.get("show_if")?.toString())
    }
    override val screens: List<String> = list(spec["screens"]).map { it.toString() }
    /** A routine: other macros run one after another (steps: [start-game, daily-ads, ...]). */
    val steps: List<String> = list(spec["steps"]).map { it.toString() }
    override val unknownTimeoutS = (spec["unknown_timeout_s"] as? Number)?.toDouble() ?: 45.0
    val screenDefs: Map<String, ScreenDef> = map(spec["define_screens"]).entries.associate { (k, v) ->
        val m = mapOrNull(v)
        k.toString() to ScreenDef(k.toString(), list(m?.get("require")).map { it.toString() }, list(m?.get("reject")).map { it.toString() })
    }
    private val handlers = map(spec["handlers"]).entries.associate { (k, v) -> k.toString() to list(v) }

    /** Steps the loaded logic doesn't know (a macro written for newer logic): it can't run here. */
    val unsupported: List<String> get() = Logic.current().entry.actions.let { known -> buildList {
        fun walk(actions: Any?) {
            for (a in list(actions)) {
                val m = mapOrNull(a) ?: continue
                m.keys.firstOrNull { it in known }?.let { k ->
                    when (k) {
                        "if" -> { walk(m["then"]); walk(m["else"]) }
                        "repeat" -> walk(map(m["repeat"])["do"])
                        else -> {}
                    }
                } ?: add(m.keys.joinToString())
            }
        }
        walk(spec["setup"]); walk(spec["unknown"]); handlers.values.forEach { walk(it) }
    }.distinct() }

    /** The macros this one runs with `run:` (for the app: what it's built from). */
    val uses: List<String> = buildList {
        fun walk(actions: Any?) {
            for (a in list(actions)) {
                val m = mapOrNull(a) ?: continue
                when {
                    "if" in m -> { walk(m["then"]); walk(m["else"]) }
                    "repeat" in m -> walk(map(m["repeat"])["do"])
                    "run" in m -> add(runTarget(m["run"]))
                }
            }
        }
        walk(spec["setup"]); walk(spec["unknown"]); handlers.values.forEach { walk(it) }
        addAll(steps)
    }.distinct()

    /** Runs the steps: made by the loaded logic pack each time the macro starts (see Logic.kt). */
    private var engine: FlowEngine? = null

    override fun setup(ctx: Context) {
        engine = Logic.current().entry.engine(this).also { it.setup(ctx) }
    }

    override fun onScreen(ctx: Context, screen: String) = engine!!.onScreen(ctx, screen)
    override fun onUnknown(ctx: Context) = engine!!.onUnknown(ctx)

    companion object {
        /** The game's own activity; a commercial plays in another activity of the game's package. */
        const val GAME_ACTIVITY = "net.wrightflyer.toybox.AppActivity"


        /** `run: name` or `run: {macro: name, ...}` -> name. */
        fun runTarget(v: Any?): String = (mapOrNull(v)?.get("macro") ?: v).toString()

        // also used by the logic pack's FlowRun: their signatures are part of the shell's API
        @Suppress("UNCHECKED_CAST")
        fun mapOrNull(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.let { it as Map<String, Any?> }
        fun map(v: Any?): Map<String, Any?> = mapOrNull(v) ?: emptyMap()

        fun list(v: Any?): List<Any?> = when (v) { null -> emptyList(); is List<*> -> v; else -> listOf(v) }

        /** Params arrive as text: make numbers numbers and true/false booleans. */
        fun typed(v: String): Any = v.trim().let { t ->
            t.toDoubleOrNull() ?: when (t.lowercase()) { "true", "yes", "on" -> true; "false", "no", "off" -> false; else -> t }
        }

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String, source: String, custom: Boolean = false): FlowScenario {
            val spec = mapOrNull(Load(LoadSettings.builder().build()).loadFromString(text))
                ?: throw IllegalArgumentException("$source: not a macro file")
            return FlowScenario(spec, source, custom)
        }
    }
}
