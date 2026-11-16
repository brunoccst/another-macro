package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.FlowEngine
import io.github.brunoccst.anothermacro.FlowScenario
import io.github.brunoccst.anothermacro.LogicEntry

/**
 * The way into the logic pack: the app creates this class by name (Logic.kt), from the downloaded
 * pack when it has one that fits, else from the copy built into the app. Everything in this
 * package ships in the pack; code outside it (the "shell") must never name a class in here.
 *
 * A change here or anywhere in this package reaches a phone with the same shell in a few
 * minutes, without an install (docs/logic-pack.md).
 */
class Entry : LogicEntry {
    /** Every step a macro may use (see the list at the top of Flow.kt). */
    override val actions: Set<String> = ACTIONS

    /** Steps that run basic macros in code: macros using them take over those macros' options. */
    override val stepUses: Map<String, List<String>> = STEP_USES

    override fun engine(scenario: FlowScenario): FlowEngine = FlowRun(scenario)

    companion object {
        /** Keep in step with the code: each step here runs these macros (ctx.runBasic / ctx.innerOptions). */
        val STEP_USES = mapOf(
            // every fight: the battle macro's step with its skills; the party option: the basic macro pick-party
            "another_dungeon" to listOf("battle", "pick-party"),
            "cat_daily" to listOf("battle"),         // encounters on the way to a cat
        )
        val ACTIONS = setOf("if", "tap", "tap_exact", "drag", "walk", "back", "home", "leave_ad", "close_game", "wait", "launch", "log", "status", "set", "count",
            "stop", "repeat", "reset_view", "find_feather", "read_cat_clues", "cat_daily", "fight", "run", "another_dungeon", "phantom_crystal", "pick_party")
    }
}
