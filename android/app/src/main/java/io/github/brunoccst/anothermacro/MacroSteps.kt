package io.github.brunoccst.anothermacro

/**
 * "How it works" in plain words: the steps a macro takes, in order, as the user would describe
 * them, with the macros it runs opened up into their own steps and with the options that change a
 * step named next to it (as they are set now).
 *
 * Where the steps come from:
 * - a macro's `how:` list (macro file): text lines, `if:`/`then:`/`else:` on its options, `run:`
 *   for a macro it runs (opened up into that macro's steps, with the options it would run with),
 *   and any step of the logic (e.g. `another_dungeon: dogma_tower`), which the logic describes
 *   itself from its code and data ([LogicEntry.explain]: a dungeon's maps, chests and route);
 * - a routine: its macros, one after another, each opened up;
 * - no `how:`: the logic's own steps the macro uses, else the macros it runs.
 *
 * [build] is plain Kotlin (unit-tested); MacroDiagram draws the result.
 */
object MacroSteps {
    /**
     * One step: [text]; [sub] its own steps; [macro] the macro it runs (tap to open it); [note] the
     * options that decided it, as set now ("Clear first: on"); [skipped] a step an option leaves out.
     */
    data class Step(val text: String, val sub: List<Step> = emptyList(), val macro: String? = null,
                    val note: String = "", val skipped: Boolean = false,
                    /** What runs it, shown small above the text ("macro pick-party"). */
                    val label: String = "")

    /** What [LogicEntry.explain] can look at. */
    interface Env {
        /** The macro's options as set now (its own and the ones it takes over, "battle.skill_1"). */
        val options: Map<String, String>
        /** A file of the macro library (templates/another_dungeon/dungeons.yaml), or null. */
        fun file(path: String): String?
        /** The steps of the macro [name] when this macro runs it with [given] (null: no such macro). */
        fun steps(name: String, given: Map<String, String> = emptyMap()): List<Step>?
        /** The description of the macro [name] ("" if none). */
        fun about(name: String): String
    }

    /** How deep macros run by macros are opened up (a routine → a macro → the macros it runs). */
    const val DEPTH = 3

    /**
     * The steps of [macro] with its options [options] (already its effective values: defaults,
     * saved and as edited). [find] gives another macro by name, [saved] a macro's own saved options,
     * [file] a library file, [explain] the logic's description of its own steps.
     */
    fun build(
        macro: FlowScenario,
        options: Map<String, String>,
        find: (String) -> FlowScenario?,
        saved: (String) -> Map<String, String> = { emptyMap() },
        file: (String) -> String? = { null },
        explain: (String, Any?, Env) -> List<Step>? = { _, _, _ -> null },
        depth: Int = 0,
    ): List<Step> {
        val env = object : Env {
            override val options = options
            override fun file(path: String) = file(path)
            override fun about(name: String) = find(name)?.description.orEmpty()
            override fun steps(name: String, given: Map<String, String>): List<Step>? {
                val inner = find(name) ?: return null
                if (depth >= DEPTH) return emptyList()
                return build(inner, innerOptions(inner, options, saved(name), given, name), find, saved, file, explain, depth + 1)
            }
        }
        val spec = macro.spec
        // a routine: its macros one after another, each with its own saved options
        if (macro.steps.isNotEmpty()) return macro.steps.map { name -> callStep(name, emptyMap(), "", env, routine = true) }
        val how = spec["how"]
        if (how != null) return how(how, env, explain)
        // no how: the logic's own steps it uses, else the macros it runs
        val known = Logic.current().entry.actions
        val builtin = firstBuiltin(spec, known)
        if (builtin != null) explain(builtin.first, builtin.second, env)?.let { return it }
        return macro.uses.map { callStep(it, emptyMap(), "", env) }
    }

    /** The options a macro run inside another gets: its defaults, its saved ones, what the outer macro keeps for it, what the run gives. */
    fun innerOptions(inner: FlowScenario, outer: Map<String, String>, saved: Map<String, String>, given: Map<String, String>, name: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, p) in inner.params) out[k] = p.default
        out.putAll(saved)
        out.putAll(InnerParams.scoped(outer, name))
        out.putAll(given)
        return out
    }

    private fun how(v: Any?, env: Env, explain: (String, Any?, Env) -> List<Step>?): List<Step> = FlowScenario.list(v).flatMap { e ->
        val m = FlowScenario.mapOrNull(e)
        when {
            m == null -> listOf(Step(fill(e.toString(), env.options)))
            "if" in m -> {
                val cond = m["if"].toString()
                val yes = runCatching { Expr.truthy(Expr.eval(cond, optionEnv(env.options))) }.getOrDefault(false)
                val note = optionsIn(cond, env.options)
                // the options that chose the branch: under its first step
                how(if (yes) m["then"] else m["else"], env, explain).mapIndexed { i, s -> if (i == 0 && s.note.isEmpty() && note.isNotEmpty()) s.copy(note = note) else s }
            }
            "run" in m -> {
                val name = FlowScenario.runTarget(m["run"])
                // the values a run: gives are expressions ('ads', battles + 1): their value with the options as set
                val given = FlowScenario.map(m["params"]).mapValues { (_, v) ->
                    val raw = Expr.str(v)
                    runCatching { Expr.str(Expr.eval(raw, optionEnv(env.options))) }.getOrDefault(raw)
                }
                listOf(callStep(name, given, m["text"]?.toString()?.let { fill(it, env.options) } ?: "", env))
            }
            "text" in m -> listOf(Step(fill(m["text"].toString(), env.options), how(m["sub"], env, explain),
                note = m["note"]?.toString()?.let { fill(it, env.options) } ?: ""))
            else -> {
                val (k, arg) = m.entries.first()
                explain(k, arg, env) ?: listOf(Step(MacroDiagram.words(k) + (arg?.let { ": " + MacroDiagram.words(it.toString()) } ?: "")))
            }
        }
    }

    private fun callStep(name: String, given: Map<String, String>, text: String, env: Env, routine: Boolean = false): Step {
        val about = env.about(name)
        val head = text.ifEmpty { "Run $name" + if (about.isNotBlank()) ": " + about.removePrefix("Basic: ").trimEnd('.') else "" }
        val sub = env.steps(name, given)
        return if (sub == null) Step("$head (macro not found)", macro = null) else Step(head, sub, macro = name,
            note = if (routine) "with its own options" else given.entries.joinToString(", ") { "${title(it.key)}: ${shown(it.value)}" }.let { if (it.isEmpty()) it else "with $it" },
            label = "macro $name")
    }

    /** The first step of the logic the macro uses (its handlers, setup, unknown): its name and argument. */
    private fun firstBuiltin(spec: Map<String, Any?>, known: Set<String>): Pair<String, Any?>? {
        val plain = setOf("if", "tap", "tap_exact", "drag", "walk", "back", "home", "leave_ad", "force_close", "wait", "launch",
            "log", "status", "set", "count", "got", "stop", "repeat", "reset_view", "run")
        fun walk(v: Any?): Pair<String, Any?>? {
            for (a in FlowScenario.list(v)) {
                val m = FlowScenario.mapOrNull(a) ?: continue
                when {
                    "if" in m -> (walk(m["then"]) ?: walk(m["else"]))?.let { return it }
                    "repeat" in m -> walk(FlowScenario.map(m["repeat"])["do"])?.let { return it }
                    else -> m.entries.firstOrNull { it.key in known && it.key !in plain }?.let { return it.key to it.value }
                }
            }
            return null
        }
        return walk(spec["setup"]) ?: FlowScenario.map(spec["handlers"]).values.firstNotNullOfOrNull { walk(it) } ?: walk(spec["unknown"])
    }

    /** "{runs} runs" with the option's value; unknown names stay as they are. */
    fun fill(text: String, options: Map<String, String>): String =
        Regex("""\{([a-zA-Z0-9_.\-]+)\}""").replace(text) { mr -> options[mr.groupValues[1]]?.let { shown(it) } ?: mr.value }

    /** The options [expr] looks at, as set now: "Clear first: on, Fish: off". */
    fun optionsIn(expr: String, options: Map<String, String>): String =
        Regex("""[a-zA-Z_][a-zA-Z0-9_.\-]*""").findAll(expr).map { it.value }.filter { it in options }.distinct()
            .joinToString(", ") { "${title(it)}: ${shown(options.getValue(it))}" }

    private fun optionEnv(options: Map<String, String>) = object : Expr.Env {
        override fun variable(name: String): Any? = options[name]?.let { FlowScenario.typed(it) }
        override fun call(name: String, args: List<Any?>): Any? = null
    }

    /** An option's value for people: true/false as on/off, 4.0 as 4. */
    fun shown(v: String): String = when (v.trim().lowercase()) {
        "true", "yes" -> "on"; "false", "no" -> "off"
        else -> v.trim().removeSuffix(".0")
    }

    /** "battle.skill_1" -> "Battle: Skill 1", "clear_first" -> "Clear first". */
    fun title(key: String): String = key.split('.').joinToString(": ") { part ->
        part.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
    }
}
