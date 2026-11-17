package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.FlowEngine
import io.github.brunoccst.anothermacro.FlowScenario
import io.github.brunoccst.anothermacro.LogicEntry
import io.github.brunoccst.anothermacro.MacroSteps

/**
 * The way into the logic pack: the app creates this class by name (Logic.kt), from the downloaded
 * pack when it has one that fits, else from the copy built into the app. Everything in this
 * package ships in the pack; code outside it (the "shell") must never name a class in here.
 *
 * A change here or anywhere in this package reaches a phone with the same shell in a few
 * minutes, without an install (docs/logic-pack.md).
 */
class Entry : LogicEntry {
    /** Every step a macro may use: the generic ones and the game plugin's (see the list at the top of Flow.kt). */
    override val actions: Set<String> = ACTIONS

    /** Steps that run basic macros in code: macros using them take over those macros' options. */
    override val stepUses: Map<String, List<String>> = STEP_USES

    /** The app's "How it works" words for the steps the game plugin runs in code. */
    override fun explain(action: String, arg: Any?, env: MacroSteps.Env): List<MacroSteps.Step>? = PLUGIN.explain(action, arg, env)

    override fun label(action: String, arg: Any?): String? = PLUGIN.label(action, arg)

    override fun engine(scenario: FlowScenario): FlowEngine = FlowRun(scenario, PLUGIN)

    companion object {
        /** The game this logic plays: its own steps beside the generic ones ([GamePlugin]). */
        val PLUGIN: GamePlugin = AnotherEdenPlugin
        val STEP_USES = PLUGIN.stepUses
        val ACTIONS = FlowRun.STEPS + PLUGIN.actions
    }
}
