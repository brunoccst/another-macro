package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.FlowEngine
import io.github.brunoccst.anothermacro.FlowScenario
import io.github.brunoccst.anothermacro.LogicEntry

/**
 * The way into the logic pack: the app creates this class by name (Logic.kt), from the downloaded
 * pack when it has one that fits, else from the copy built into the app. Everything in this
 * package ships in the pack; code outside it (the "shell") must never name a class in here.
 */
class Entry : LogicEntry {
    /** Every step a macro may use (see the list at the top of Flow.kt). */
    override val actions: Set<String> = ACTIONS

    override fun engine(scenario: FlowScenario): FlowEngine = FlowRun(scenario)

    companion object {
        val ACTIONS = setOf("if", "tap", "tap_exact", "drag", "walk", "back", "wait", "launch", "log", "status", "set", "count",
            "stop", "repeat", "reset_view", "find_feather", "read_cat_clues", "cat_daily", "fight", "run", "another_dungeon", "phantom_crystal")
    }
}
