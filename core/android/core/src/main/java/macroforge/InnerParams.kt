package macroforge

/**
 * A macro takes over the options of the macros it runs, so it keeps its own settings for them
 * without declaring them again: a dungeon macro that fights with the basic macro battle gets
 * battle.skill_1 .. battle.skill_4 among its own options, and every dungeon can fight its own way.
 *
 * - Which macros: [FlowScenario.uses] (`run:` steps, a routine's steps, the basic macros its own
 *   steps run in code, `uses:`), and theirs in turn, each macro once: one set of battle options per
 *   outer macro, wherever the battles happen inside it. The key is "<macro>.<its option>".
 * - Left out: an option the caller's `run:` steps always give (the caller decides it, e.g.
 *   grind-battles' until_cleared from a map-clearing macro), and one the caller has itself under
 *   the same name (it passes its own, e.g. a dungeon's party to pick-party).
 * - Unset (never saved with the outer macro), an inner option is the inner macro's own: the app
 *   shows that value, and once saved the outer macro keeps its own copy.
 * - At run time [scoped] hands them down: battle.skill_1 becomes the battle macro's skill_1; other
 *   dotted keys pass on unchanged for the macros further in.
 */
object InnerParams {
    /** Fill [FlowScenario.innerParams] of every macro of [all] (call once they're all loaded). */
    fun link(all: Collection<Scenario>) {
        val byName = all.associateBy { it.name }
        for (s in all) if (s is FlowScenario) s.innerParams = collect(s, byName)
    }

    internal fun collect(root: FlowScenario, byName: Map<String, Scenario>): Map<String, Param> {
        val out = LinkedHashMap<String, Param>()
        val seen = hashSetOf(root.name)
        fun visit(outer: FlowScenario, path: List<String>) {
            for (u in outer.uses) {
                if (!seen.add(u)) continue
                val inner = byName[u] as? FlowScenario ?: continue
                val hidden = outer.givenTo[u].orEmpty() + outer.ownParams.keys
                val shown = inner.ownParams.filterKeys { it !in hidden }
                val via = path + u
                for ((k, p) in shown) {
                    out["$u.$k"] = p.copy(
                        showIf = p.showIf?.takeIf { it in shown }?.let { "$u.$it" },
                        beside = p.beside?.takeIf { it in shown }?.let { "$u.$it" },
                        from = u, via = via)
                }
                visit(inner, via)
            }
        }
        visit(root, emptyList())
        return out
    }

    /**
     * What a macro run inside one with [params] gets from it, for the macro [inner]: its own
     * settings for that macro ("battle.skill_1" -> "skill_1"), and the dotted keys for the macros
     * further in, as they are.
     */
    fun scoped(params: Map<String, String>, inner: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val prefix = "$inner."
        // the deeper keys first, so this macro's own (stripped) win over a same-named one
        for ((k, v) in params) if ('.' in k && !k.startsWith(prefix)) out[k] = v
        for ((k, v) in params) if (k.startsWith(prefix)) out[k.removePrefix(prefix)] = v
        return out
    }

    /**
     * The value an inner option [key] has while the outer macro hasn't saved its own: the first
     * macro along its way that has it saved ([Param.via]: the macros in between keep their own
     * copies too), else the inner macro's own setting, else its default. [saved] = a macro's saved options.
     */
    fun effective(key: String, p: Param, saved: (String) -> Map<String, String>): String {
        val from = p.from ?: return p.default
        for (m in p.via.dropLast(1)) saved(m)[key]?.let { return it }
        return saved(from)[p.innerKey(key)] ?: p.default
    }
}
