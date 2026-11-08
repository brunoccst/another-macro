package io.github.brunoccst.anothermacro

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A macro's YAML as a diagram, so a user can see what it does without reading the file:
 * what it does once at the start, what it does on each screen (in the order screens are
 * checked), what it does when no screen is recognised, and the macros it runs.
 *
 * [build] turns the YAML into a small tree ([Section]s of [Node]s, plain Kotlin, unit-tested);
 * [render] draws that tree as boxes and arrows, with every "if" split into its yes / no paths.
 */
object MacroDiagram {
    sealed class Node {
        /** One step: [kind] picks its colour. */
        data class Step(val text: String, val kind: Kind = Kind.ACTION) : Node()
        /** An if: the condition, then the yes and no paths. */
        data class Branch(val condition: String, val yes: List<Node>, val no: List<Node>) : Node()
        /** A repeat: [times] times the [body]. */
        data class Loop(val times: String, val body: List<Node>) : Node()
        /** Another macro run here (tap it to open it). */
        data class Call(val macro: String, val detail: String) : Node()
    }

    enum class Kind { ACTION, WAIT, STOP, BUILTIN }

    data class Section(val title: String, val help: String, val nodes: List<Node>)

    @Suppress("UNCHECKED_CAST")
    fun build(spec: Map<String, Any?>): List<Section> {
        val out = ArrayList<Section>()
        val steps = list(spec["steps"])
        if (steps.isNotEmpty()) {
            out += Section("Routine", "These macros run one after another; one that fails doesn't stop the rest.",
                steps.map { Node.Call(it.toString(), "") })
            return out
        }
        val setup = actions(spec["setup"])
        if (setup.isNotEmpty()) out += Section("At the start", "Once, before looking at the screen.", setup)
        val handlers = (spec["handlers"] as? Map<String, Any?>).orEmpty()
        val screens = list(spec["screens"]).map { it.toString() }
        val order = screens + handlers.keys.filter { it !in screens }
        for ((i, screen) in order.withIndex()) {
            val nodes = actions(handlers[screen])
            out += Section("Screen ${i + 1}: ${words(screen)}",
                if (i == 0) "Checked first: the screens are checked in this order, the first one seen wins." else "",
                nodes.ifEmpty { listOf(Node.Step("nothing (just looks again)", Kind.WAIT)) })
        }
        val unknown = actions(spec["unknown"])
        val timeout = (spec["unknown_timeout_s"] as? Number)?.toInt() ?: 45
        out += Section("No screen recognised",
            "Gives up after ${timeout}s without a recognised screen.",
            unknown.ifEmpty { listOf(Node.Step("wait and look again", Kind.WAIT)) })
        return out
    }

    private fun actions(v: Any?): List<Node> = list(v).mapNotNull { a ->
        @Suppress("UNCHECKED_CAST")
        val m = a as? Map<String, Any?> ?: return@mapNotNull null
        node(m)
    }

    @Suppress("UNCHECKED_CAST")
    private fun node(a: Map<String, Any?>): Node {
        fun m(k: String) = (a[k] as? Map<String, Any?>).orEmpty()
        return when {
            "if" in a -> Node.Branch(condition(a["if"].toString()), actions(a["then"]), actions(a["else"]))
            "repeat" in a -> Node.Loop(m("repeat")["times"].toString(), actions(m("repeat")["do"]))
            "run" in a -> {
                val r = a["run"] as? Map<String, Any?>
                val parts = ArrayList<String>()
                (r?.get("params") as? Map<String, Any?>)?.let { p -> if (p.isNotEmpty()) parts += p.entries.joinToString(", ") { "${words(it.key)} = ${it.value}" } }
                list(r?.get("until")).takeIf { it.isNotEmpty() }?.let { u -> parts += "until back to: " + u.joinToString(", ") { words(it.toString()) } }
                Node.Call(FlowScenario.runTarget(a["run"]), parts.joinToString("; "))
            }
            "tap" in a -> Node.Step("Tap " + target(a["tap"]))
            "tap_exact" in a -> Node.Step("Tap exactly at " + target(a["tap_exact"]))
            "drag" in a -> Node.Step("Swipe from ${target(m("drag")["from"])} to ${target(m("drag")["to"])}")
            "walk" in a -> Node.Step("Walk ${direction(m("walk")["dir"])} for ${m("walk")["ms"] ?: 1500} ms")
            "back" in a -> Node.Step("Press Back")
            "wait" in a -> Node.Step("Wait ${a["wait"]} s", Kind.WAIT)
            "launch" in a -> Node.Step("Open the app ${a["launch"].toString().trim('\'')}")
            "log" in a -> Node.Step("Write in the log: " + text(a["log"]), Kind.WAIT)
            "status" in a -> Node.Step("Show: " + text(a["status"]), Kind.WAIT)
            "set" in a -> Node.Step("Remember " + m("set").entries.joinToString(", ") { "${words(it.key)} = ${it.value}" }, Kind.WAIT)
            "count" in a -> Node.Step("Count one more ${words(a["count"].toString())}", Kind.WAIT)
            "stop" in a -> Node.Step("Stop: " + text(a["stop"]), Kind.STOP)
            "reset_view" in a -> Node.Step("Forget the last view (for \"the view moved\")", Kind.WAIT)
            "fight" in a -> Node.Step("Fight the battle to its end with the skills from the options", Kind.BUILTIN)
            "find_feather" in a -> Node.Step("Look for the feather bubble and tap it", Kind.BUILTIN)
            "read_cat_clues" in a -> Node.Step("Read today's clues", Kind.BUILTIN)
            "cat_daily" in a -> Node.Step("Read the clues, travel to each place and find what they describe, then stop", Kind.BUILTIN)
            "another_dungeon" in a -> Node.Step("Play the Another Dungeon run \"${words(a["another_dungeon"].toString())}\" (enemies, chests, boss), then stop", Kind.BUILTIN)
            "phantom_crystal" in a -> Node.Step("Play the Phantom Crystal Dimension world by world, then stop", Kind.BUILTIN)
            else -> Node.Step(a.keys.joinToString())
        }
    }

    /** found('shop_close_button') -> "shop close button is on screen", and so on. */
    fun condition(expr: String): String = expr
        .replace(Regex("""not\s+found\('([^']+)'\)""")) { "${words(it.groupValues[1])} is not on screen" }
        .replace(Regex("""found\('([^']+)'\)""")) { "${words(it.groupValues[1])} is on screen" }
        .replace(Regex("""not\s+game_in_front\(\)""")) { "the game is not in front" }
        .replace("game_in_front()", "the game is in front")
        .replace(Regex("""not\s+view_moved\(\)""")) { "the view didn't move" }
        .replace("view_moved()", "the view moved")
        .replace(Regex("""text_has\('([^']+)'\)""")) { "the screen says \"${it.groupValues[1]}\"" }
        .replace(Regex("""captured\('([^']+)'\)""")) { "${words(it.groupValues[1])} is captured" }
        .replace("now()", "now")
        .replace("stats.", "")
        .replace(" == ", " is ")
        .let { it.replace(Regex("""\b[a-z]+(?:_[a-z0-9]+)+\b""")) { w -> words(w.value) } } + "?"

    private fun target(v: Any?): String = when (v) {
        is List<*> -> "(${v.joinToString(", ")})"
        is Map<*, *> -> "(${v["x"]}, ${v["y"]})"
        null -> "?"
        else -> words(v.toString())
    }

    private fun direction(v: Any?) = when (v?.toString()) {
        "-1", "-1.0" -> "left"; "1", "1.0" -> "right"; "up", "'up'" -> "up"; "down", "'down'" -> "down"
        else -> "(${words(v.toString())})"
    }

    /** Text with {expressions}: keep it, the braces show what's filled in. */
    private fun text(v: Any?) = v.toString().trim('\'', '"')

    /** "shop_close_button" -> "shop close button". */
    fun words(s: String) = s.replace('_', ' ')

    private fun list(v: Any?): List<Any?> = when (v) { null -> emptyList(); is List<*> -> v; else -> listOf(v) }

    // --- drawing --------------------------------------------------------------------------

    private val COND get() = Color.parseColor("#FFB74D")
    private val CALL get() = Color.parseColor("#66BB6A")

    /** The diagram of [sections] as views; tapping a macro that's run opens it ([open]). */
    fun render(a: android.content.Context, sections: List<Section>, open: (String) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        for ((i, s) in sections.withIndex()) {
            addView(Ui.text(a, s.title, 14.5f, Ui.TEXT, bold = true).apply { setPadding(0, Ui.dp(a, if (i == 0) 0 else 16), 0, Ui.dp(a, 2)) })
            if (s.help.isNotBlank()) addView(Ui.text(a, s.help, 12f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(a, 6)) })
            addView(column(a, s.nodes, open))
        }
    }

    private fun column(a: android.content.Context, nodes: List<Node>, open: (String) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        for ((i, n) in nodes.withIndex()) {
            if (i > 0) addView(arrow(a))
            addView(nodeView(a, n, open))
        }
    }

    private fun nodeView(a: android.content.Context, n: Node, open: (String) -> Unit): View = when (n) {
        is Node.Step -> box(a, n.text, when (n.kind) {
            Kind.ACTION -> Ui.ACCENT; Kind.WAIT -> Ui.MUTED; Kind.STOP -> Ui.BAD; Kind.BUILTIN -> Color.parseColor("#BA68C8")
        })
        is Node.Call -> box(a, "▶  Run macro ${n.macro}" + (if (n.detail.isNotBlank()) "\n${n.detail}" else "") + "   ›", CALL).apply {
            isClickable = true
            setOnClickListener { open(n.macro) }
        }
        is Node.Loop -> LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(box(a, "↻  Repeat ${n.times} times:", COND))
            addView(path(a, "", n.body, COND, open))
        }
        is Node.Branch -> LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(box(a, "◆  ${n.condition}", COND))
            addView(path(a, "yes", n.yes, Ui.GOOD, open))
            addView(path(a, "no", n.no, Ui.BAD, open))
        }
    }

    /** One way out of an if (or a loop's body): a coloured rail on the left, its steps beside it. */
    private fun path(a: android.content.Context, label: String, nodes: List<Node>, color: Int, open: (String) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(Ui.dp(a, 10), Ui.dp(a, 4), 0, Ui.dp(a, 2))
        addView(View(a).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(Ui.dp(a, 2), LinearLayout.LayoutParams.MATCH_PARENT))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 8), 0, 0, 0)
            if (label.isNotEmpty()) addView(Ui.text(a, label, 11.5f, color, bold = true).apply { setPadding(0, 0, 0, Ui.dp(a, 3)) })
            addView(if (nodes.isEmpty()) Ui.text(a, "go on", 12f, Ui.MUTED) else column(a, nodes, open))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun box(a: android.content.Context, text: String, color: Int): TextView = Ui.text(a, text, 13f).apply {
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(a, 10).toFloat()
            setColor(Ui.CARD_ALT)
            setStroke(Ui.dp(a, 1), color)
        }
        setPadding(Ui.dp(a, 10), Ui.dp(a, 7), Ui.dp(a, 10), Ui.dp(a, 7))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun arrow(a: android.content.Context) = Ui.text(a, "↓", 12f, Ui.MUTED).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 28), LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}
