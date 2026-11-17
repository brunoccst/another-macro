package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.Context
import io.github.brunoccst.anothermacro.MacroSteps

/**
 * What a game adds to the generic macro steps: its own steps (fights, dungeons, ...), the basic
 * macros those steps run in code, and their words for "How it works". The generic steps
 * ([FlowRun.STEPS]: tap, walk, run, ...) know nothing about the game; [FlowRun] hands every
 * other step to the plugin. [Entry.PLUGIN] names the game's plugin.
 *
 * This is the seam between the generic macro tool and one game (docs/game-profile.md): a game
 * with only YAML macros needs no plugin ([None]).
 */
interface GamePlugin {
    /** The steps this plugin runs (never one of [FlowRun.STEPS]). */
    val actions: Set<String>

    /**
     * The basic macros a step runs in code, by step: a macro using that step takes over their
     * options, as if it ran them with `run:` (InnerParams).
     */
    val stepUses: Map<String, List<String>> get() = emptyMap()

    /** A step of this plugin in plain words for "How it works" (null: only its name is shown). */
    fun explain(action: String, arg: Any?, env: MacroSteps.Env): List<MacroSteps.Step>? = null

    /** A new run of a macro: the plugin's state for that run (caches, last positions). */
    fun newRun(): Run

    /** One macro run's plugin steps. */
    interface Run {
        /** Run the step [action] (one of [actions]); [step] is the macro's step in full. */
        fun run(macro: Macro, action: String, step: Map<String, Any?>)
    }

    /** What a plugin step may use of the running macro. */
    interface Macro {
        val ctx: Context
        /** The macro's name (for errors and logs). */
        val name: String
        /** The macro's variables (its options and what `set:` stored): a step's result goes here. */
        val vars: MutableMap<String, Any?>
        /** A step's YAML value: strings are expressions, numbers and switches are themselves. */
        fun value(v: Any?): Any?
        /** "text with {expressions}". */
        fun text(v: Any?): String
    }

    /** No game steps: the generic ones only. */
    object None : GamePlugin {
        override val actions = emptySet<String>()
        override fun newRun() = object : Run {
            override fun run(macro: Macro, action: String, step: Map<String, Any?>) =
                throw IllegalArgumentException("${macro.name}: unknown action $action")
        }
    }
}
