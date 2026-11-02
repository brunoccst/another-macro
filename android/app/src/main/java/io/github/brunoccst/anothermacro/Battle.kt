package io.github.brunoccst.anothermacro

/**
 * Fighting one battle, shared by every macro that can end up in one (grind-battles,
 * the cat routine's random encounters, Another Dungeons).
 *
 * A [Setup] says, per front-row character (1-4), which skill to use: "auto" leaves the
 * character to the game, "1".."4" picks that skill from the character's skill row.
 * When all four are "auto" the battle's Auto button is used; otherwise the chosen
 * skills are set on the first command screen of the battle (the game keeps them for
 * the following turns) and Attack is pressed every turn.
 */
object Battle {
    /** The four front-row portraits on the command screen (2400x1080, 20:9). */
    val PORTRAIT_X = listOf(0.161, 0.267, 0.373, 0.479)
    const val PORTRAIT_Y = 0.917

    /**
     * The skill row that opens above the portraits: five boxes, the normal attack first
     * and then skills 1-4 (the last box, 0.8075, was measured; the others are spaced like
     * the portraits).
     */
    val SKILL_X = listOf(0.3835, 0.4895, 0.5955, 0.7015, 0.8075)
    const val SKILL_ROW_Y = 0.763

    val SKILL_CHOICES = listOf("auto", "1", "2", "3", "4")

    data class Setup(val skills: List<String>) {
        val allAuto get() = skills.all { it == "auto" }
        override fun toString() = skills.withIndex().joinToString(" ") { (i, s) -> "${i + 1}:$s" }

        companion object {
            val AUTO = Setup(List(4) { "auto" })

            /** From params `<prefix>1` .. `<prefix>4` (e.g. skill_1, boss_skill_1); missing ones are auto. */
            fun from(params: Map<String, String>, prefix: String) = Setup((1..4).map { i ->
                params["$prefix$i"]?.trim()?.lowercase()?.removeSuffix(".0")?.takeIf { it in SKILL_CHOICES } ?: "auto"
            })
        }
    }

    enum class Outcome { WON, LOST, NONE }

    fun inBattle(ctx: Context) = ctx.find("battle_attack_button") != null || ctx.find("battle_hud") != null ||
        ctx.find("result_banner") != null

    /**
     * Fight the battle on screen until the field is back. [pick] chooses the setup on the
     * first command screen (so a caller can tell a boss from the mobs by looking at it).
     * NONE when no battle was on screen.
     */
    fun fight(ctx: Context, pick: (Context) -> Setup): Outcome {
        var seen = false
        var won = false
        var setup: Setup? = null
        var skillsSet = false
        var quiet = 0
        val end = System.currentTimeMillis() + 15 * 60_000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            when {
                ctx.find("defeat_banner") != null -> return Outcome.LOST
                ctx.find("result_banner") != null -> {
                    seen = true; won = true; quiet = 0
                    ctx.tap(Point(0.5, 0.75))
                }
                ctx.find("skill_list_open") != null -> {
                    // a skill list left open (a skill that couldn't be picked): close it with its portrait
                    seen = true; quiet = 0
                    ctx.tap(Point(PORTRAIT_X[0], PORTRAIT_Y))
                }
                ctx.find("battle_attack_button") != null -> {
                    seen = true; quiet = 0
                    val s = setup ?: pick(ctx).also { setup = it; MacroLog.i("battle: skills $it") }
                    if (s.allAuto) {
                        ctx.tap(if (ctx.find("battle_auto_button") != null) "battle_auto_button" else "battle_attack_button")
                    } else {
                        if (!skillsSet) { setSkills(ctx, s); skillsSet = true }
                        ctx.count("turns")
                        ctx.tap("battle_attack_button")
                    }
                }
                ctx.find("battle_hud") != null -> { seen = true; quiet = 0 }
                ctx.find("field_menu_button") != null -> {
                    if (!seen) return Outcome.NONE
                    if (won) ctx.count("battles_won")
                    return Outcome.WON
                }
                else -> {
                    // the battle's intro, or the screens after the results (level up, drops)
                    if (!seen && ++quiet > 4) return Outcome.NONE
                    if (won) ctx.tap(Point(0.5, 0.75))
                }
            }
            ctx.wait(1.0)
        }
        ctx.stop("a battle lasted more than 15 minutes")
    }

    /** Tap each non-auto character's portrait and its chosen skill. */
    private fun setSkills(ctx: Context, s: Setup) {
        for ((i, skill) in s.skills.withIndex()) {
            val n = skill.toIntOrNull() ?: continue
            ctx.tap(Point(PORTRAIT_X[i], PORTRAIT_Y)); ctx.wait(0.8)
            ctx.refresh()
            if (ctx.vision.library.templates["skill_list_open"]?.available == true && ctx.find("skill_list_open") == null) {
                MacroLog.i("character ${i + 1}: the skill list didn't open")
                continue
            }
            ctx.tapExact(Point(SKILL_X[n], SKILL_ROW_Y)); ctx.wait(0.8)
            ctx.refresh()
            if (ctx.find("skill_list_open") != null) {
                MacroLog.i("character ${i + 1}: skill $n couldn't be picked (not enough MP?); leaving the list")
                ctx.tap(Point(PORTRAIT_X[i], PORTRAIT_Y)); ctx.wait(0.8)
            }
        }
    }
}
