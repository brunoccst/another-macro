package anothermacro.logic

import macroforge.logic.FlowRun
import macroforge.logic.GamePlugin

import macroforge.Context
import macroforge.Expr
import macroforge.MacroSteps
import macroforge.Point
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Another Eden's own macro steps ([GamePlugin]): battles, parties, Another Dungeons, the
 * Phantom Crystal Dimension and the cat scratchbook. Everything that knows the game's screens
 * and systems is reached from here; the generic steps are in [FlowRun].
 */
object AnotherEdenPlugin : GamePlugin {
    override val actions = setOf("fight", "pick_party", "another_dungeon", "phantom_crystal", "cat_daily", "find_feather", "read_cat_clues")

    /** Keep in step with the code: each step here runs these macros (ctx.runBasic / ctx.innerOptions). */
    override val stepUses = mapOf(
        // every fight: the battle macro's step with its skills; the party option: the basic macro pick-party
        "another_dungeon" to listOf("battle", "pick-party"),
        "cat_daily" to listOf("battle"),         // encounters on the way to a cat
    )

    override fun explain(action: String, arg: Any?, env: MacroSteps.Env) = HowItWorks.explain(action, arg, env)

    override fun label(action: String, arg: Any?): String? = when (action) {
        "fight" -> "Fight the battle to its end with the skills from the options"
        "find_feather" -> "Look for the feather bubble and tap it"
        "read_cat_clues" -> "Read today's clues"
        "cat_daily" -> "Read the clues, travel to each place and find what they describe, then stop"
        "another_dungeon" -> "Play the Another Dungeon run \"${arg.toString().replace('-', ' ')}\" (its steps are listed above), then stop"
        "phantom_crystal" -> "Play the Phantom Crystal Dimension world by world, then stop"
        "pick_party" -> "Open Menu → Party and put party $arg (its number in the list) in front"
        else -> null
    }

    override fun newRun(): GamePlugin.Run = Steps()

    private class Steps : GamePlugin.Run {
        private var feather: FeatherFinder? = null

        override fun run(macro: GamePlugin.Macro, action: String, step: Map<String, Any?>) {
            val ctx = macro.ctx
            when (action) {
                "find_feather" -> findFeather(ctx, macro.vars)
                "read_cat_clues" -> macro.vars["report"] = readCatClues(ctx)
                "cat_daily" -> ctx.stop(CatDaily.run(ctx))
                "fight" -> {
                    val prefix = step["fight"]?.toString()?.takeIf { it != "true" } ?: "skill_"
                    val outcome = Battle.fight(ctx) { Battle.Setup.from(ctx.params, prefix) }
                    macro.vars["outcome"] = outcome.name.lowercase()
                    if (outcome == Battle.Outcome.LOST) ctx.stop("party was defeated")
                }
                "another_dungeon" -> ctx.stop(AnotherDungeon.run(ctx, step["another_dungeon"].toString()))
                "phantom_crystal" -> ctx.stop(PhantomCrystal.run(ctx))
                "pick_party" -> {
                    val v = Expr.str(macro.value(step["pick_party"]))
                    val n = Party.number(v) ?: throw IllegalArgumentException("${macro.name}: pick_party: '$v' is not a party number")
                    macro.vars["result"] = Party.choose(ctx, n)
                }
                else -> throw IllegalArgumentException("${macro.name}: unknown action $action")
            }
        }

        private fun findFeather(ctx: Context, vars: MutableMap<String, Any?>) {
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
    }

    const val FEATHER_MIN = 0.78
    const val TAP_LATENCY_S = 0.4
}
