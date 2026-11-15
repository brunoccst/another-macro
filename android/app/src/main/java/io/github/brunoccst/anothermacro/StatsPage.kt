package io.github.brunoccst.anothermacro

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Statistics page: every macro run kept on this phone ([RunStats]). The overview shows all
 * macros (totals, runs and time per day, the most used ones); a macro's own page shows its totals,
 * graphs, what its runs collected and each run. Both can be cleared to start fresh.
 */
class StatsPage(private val a: MainActivity) {
    private fun dp(v: Int) = a.dp(v)

    private val overview = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
    private val detail = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)); visibility = View.GONE }
    private val scroll: ScrollView
    /** The macro whose page is open (null: the overview). */
    private var macro: String? = null
    /** How many of the open macro's runs the history lists. */
    private var shown = HISTORY_STEP
    private val listener: () -> Unit = { a.runOnUiThread { if (a.page == MainActivity.Page.STATS) refresh() } }

    val view: View

    init {
        scroll = ScrollView(a).apply {
            setBackgroundColor(Ui.BG)
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(overview); addView(detail) })
        }
        view = scroll
    }

    fun onResume() { RunStats.listeners += listener; if (a.page == MainActivity.Page.STATS) refresh() }
    fun onPause() { RunStats.listeners -= listener }

    /** Draw the open screen again with the latest runs. */
    fun refresh() {
        RunStats.init(a)
        val runs = RunStats.all()
        val m = macro
        if (m == null) drawOverview(runs) else drawMacro(m, runs.filter { it.macro == m })
    }

    /** Open [name]'s own statistics. */
    fun open(name: String) {
        macro = name
        shown = HISTORY_STEP
        refresh()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** Back to the overview; false when it is already shown. */
    fun back(): Boolean {
        if (macro == null) return false
        macro = null
        refresh()
        scroll.post { scroll.scrollTo(0, 0) }
        return true
    }

    // --- the overview ---------------------------------------------------------------------

    private fun drawOverview(runs: List<RunStats.Record>) {
        overview.visibility = View.VISIBLE
        detail.visibility = View.GONE
        overview.removeAllViews()
        val s = RunStats.summarize(runs)
        overview.addView(Ui.card(a).apply {
            addView(Ui.text(a, "Every macro run on this phone: how long it took, how it ended and what it collected. Kept only here.", 13f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(6)) })
            addView(Charts.tiles(a, listOf(
                "Runs" to s.runs.toString(),
                "Time spent" to RunStats.duration(s.totalMs),
                "Macros used" to runs.map { it.macro }.distinct().size.toString(),
                "Last run" to (s.last?.let { ago(it) } ?: "never"),
            )))
        })
        if (runs.isEmpty()) {
            overview.addView(Ui.card(a).apply {
                addView(Ui.text(a, "No runs yet. Start a macro with ▶ on the floating buttons; its run shows here when it stops.", 13.5f, Ui.MUTED))
            })
            return
        }

        val days = RunStats.perDay(runs, 14)
        overview.addView(Ui.section(a, "Runs per day · last 14 days"))
        overview.addView(Ui.card(a).apply {
            addView(Charts.Columns(a, days.map { (day, n, mins) -> Charts.Bar(shortDay(day), n.toDouble(), "${longDay(day)}: $n run(s), ${RunStats.duration((mins * 60000).toLong())}") }))
        })
        overview.addView(Ui.section(a, "Minutes per day · last 14 days"))
        overview.addView(Ui.card(a).apply {
            addView(Charts.Columns(a, days.map { (day, n, mins) -> Charts.Bar(shortDay(day), mins, "${longDay(day)}: ${RunStats.duration((mins * 60000).toLong())} in $n run(s)") }))
        })

        // the macros, most time spent first
        val byMacro = runs.groupBy { it.macro }.map { (name, rs) -> name to RunStats.summarize(rs) }.sortedByDescending { it.second.totalMs }
        overview.addView(Ui.section(a, "Time spent by macro"))
        overview.addView(Ui.card(a).apply {
            addView(Charts.rows(a, byMacro.take(8).map { (name, ms) -> Triple(name, ms.totalMs.toDouble(), RunStats.duration(ms.totalMs)) }))
        })
        overview.addView(Ui.section(a, "Macros · tap one for its runs"))
        val known = Scenarios.all(a).associateBy { it.name }
        for ((name, ms) in byMacro) {
            overview.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.pressable(a, Ui.CARD, 12)
                isClickable = true
                setOnClickListener { open(name) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                addView(Ui.icon(a, known[name]?.let { Groups.icon(it) } ?: R.drawable.ic_puzzle, 28).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, name, 15f, bold = true))
                    val done = ms.outcomes[RunStats.Outcome.DONE] ?: 0
                    addView(Ui.text(a, "${ms.runs} run(s) · ${RunStats.duration(ms.totalMs)} · ${pct(done, ms.runs)} ended by itself · last ${ms.last?.let { ago(it) } ?: "-"}", 12.5f, Ui.MUTED))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(a, "›", 22f, Ui.MUTED))
            })
        }
        overview.addView(Ui.button(a, "Clear all statistics", color = Ui.BAD) {
            confirm("Clear all statistics?", "Every macro's runs are removed from this phone, to start fresh. This can't be undone.") {
                RunStats.clear(); a.toast("Statistics cleared.")
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
    }

    // --- one macro --------------------------------------------------------------------------

    private fun drawMacro(name: String, runs: List<RunStats.Record>) {
        overview.visibility = View.GONE
        detail.visibility = View.VISIBLE
        detail.removeAllViews()
        detail.addView(Ui.text(a, "‹  All macros", 15f, Ui.ACCENT, bold = true).apply {
            setPadding(dp(4), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { back() }
        })
        val scenario = runCatching { Scenarios.get(a, name) }.getOrNull()
        val s = RunStats.summarize(runs)
        detail.addView(Ui.card(a).apply {
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Ui.icon(a, scenario?.let { Groups.icon(it) } ?: R.drawable.ic_puzzle, 32))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, name, 20f, bold = true))
                    addView(Ui.text(a, scenario?.let { Groups.of(it.group).title } ?: "Not in the macro library any more", 12f, Ui.MUTED))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (scenario != null) addView(Ui.text(a, "Open macro", 13f, Ui.ACCENT, bold = true).apply {
                    setPadding(dp(10), dp(8), dp(4), dp(8))
                    isClickable = true
                    setOnClickListener { a.show(MainActivity.Page.MACROS); a.macros.open(name) }
                })
            })
            addView(Ui.divider(a))
            if (runs.isEmpty()) { addView(Ui.text(a, "No runs of this macro yet (or its statistics were cleared).", 13.5f, Ui.MUTED)); return@apply }
            val done = s.outcomes[RunStats.Outcome.DONE] ?: 0
            addView(Charts.tiles(a, listOfNotNull(
                "Runs" to s.runs.toString(),
                "Time spent" to RunStats.duration(s.totalMs),
                "Average run" to RunStats.duration(s.avgMs),
                "Longest run" to RunStats.duration(s.longestMs),
                "Ended by itself" to pct(done, s.runs),
                "Started by Claude" to s.byConnector.toString(),
                "First run" to (s.first?.let { longDay(it) } ?: "-"),
                "Last run" to (s.last?.let { ago(it) } ?: "-"),
            )))
        })
        if (runs.isEmpty()) return

        val days = RunStats.perDay(runs, 30)
        detail.addView(Ui.section(a, "Runs per day · last 30 days"))
        detail.addView(Ui.card(a).apply {
            addView(Charts.Columns(a, days.map { (day, n, mins) -> Charts.Bar(shortDay(day), n.toDouble(), "${longDay(day)}: $n run(s), ${RunStats.duration((mins * 60000).toLong())}") }))
        })

        val recent = runs.sortedBy { it.start }.takeLast(30)
        detail.addView(Ui.section(a, "Length of the last ${recent.size} run(s) · minutes"))
        detail.addView(Ui.card(a).apply {
            addView(Charts.Columns(a, recent.map { r ->
                Charts.Bar(shortDay(r.start), r.ms / 60000.0, "${time(r.start)}: ${RunStats.duration(r.ms)}, ${r.outcome.label.lowercase()}", color(r.outcome))
            }))
            addView(Charts.legend(a, RunStats.Outcome.values().filter { (s.outcomes[it] ?: 0) > 0 }.map { it.label to color(it) }))
        })

        detail.addView(Ui.section(a, "How the runs ended"))
        detail.addView(Ui.card(a).apply {
            val outs = s.outcomes.entries.sortedByDescending { it.value }
            addView(Charts.rows(a, outs.map { Triple(it.key.label, it.value.toDouble(), "${it.value} · ${pct(it.value, s.runs)}") }) { i -> color(outs[i].key) })
            addView(Ui.text(a, "Stop reasons", 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
            addView(Charts.rows(a, s.reasons.take(8).map { Triple(it.first, it.second.toDouble(), it.second.toString()) }) { Ui.MUTED })
        })

        if (s.counts.isNotEmpty()) {
            detail.addView(Ui.section(a, "Counted over all runs"))
            detail.addView(Ui.card(a).apply {
                addView(Charts.table(a, s.counts.entries.sortedByDescending { it.value }.map { (k, v) ->
                    Ui.title(k) to (if (s.runs > 1) "$v  (${Charts.fmt(v.toDouble() / s.runs)} a run)" else "$v")
                }))
            })
        }

        if (s.dungeonRuns > 0) {
            detail.addView(Ui.section(a, "Dungeon runs"))
            detail.addView(Ui.card(a).apply {
                addView(Charts.table(a, listOfNotNull(
                    "Dungeon runs" to s.dungeonRuns.toString(),
                    *s.keys.entries.map { (k, v) -> "${Ui.title(k)} keys used" to v.toString() }.toTypedArray(),
                    if (s.chestsTotal > 0) "Chests opened" to "${s.chests} of ${s.chestsTotal} (${pct(s.chests, s.chestsTotal)})" else if (s.chests > 0) "Chests opened" to s.chests.toString() else null,
                    "White Keys dropped" to s.whiteKeys.toString(),
                    if (s.skipped > 0) "Skipped with a Cat Express Ticket" to s.skipped.toString() else null,
                )))
            })
        }

        if (s.items.isNotEmpty()) {
            detail.addView(Ui.section(a, "Items and rewards collected"))
            detail.addView(Ui.card(a).apply {
                addView(Charts.rows(a, s.items.take(12).map { Triple(it.first, it.second.toDouble(), "×${it.second}") }))
                if (s.items.size > 12) addView(Ui.text(a, "and ${s.items.size - 12} more", 12.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
                addView(Ui.text(a, "As read on the result screens; a misread name can show as its own item.", 12f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
            })
        }

        detail.addView(Ui.section(a, "Each run · newest first"))
        val newest = runs.sortedByDescending { it.start }
        for (r in newest.take(shown)) detail.addView(runCard(r))
        if (newest.size > shown) detail.addView(Ui.button(a, "Show ${minOf(HISTORY_STEP, newest.size - shown)} more") { shown += HISTORY_STEP; refresh() })

        detail.addView(Ui.button(a, "Clear this macro's statistics", color = Ui.BAD) {
            confirm("Clear $name's statistics?", "Its ${runs.size} run(s) are removed from this phone, to start fresh. Other macros keep theirs. This can't be undone.") {
                RunStats.clear(name); a.toast("$name's statistics cleared.")
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
    }

    /** One run: when, how long, how it ended; tap for the full reason, counters and reports. */
    private fun runCard(r: RunStats.Record): View = Ui.card(a).apply {
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        background = Ui.pressable(a, Ui.CARD, 18)
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(a, "${time(r.start)} · ${RunStats.duration(r.ms)}", 14f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.chip(a).apply { Ui.setChip(this, r.outcome.label, color(r.outcome)); (layoutParams as LinearLayout.LayoutParams).rightMargin = 0 })
        })
        val reason = Ui.text(a, r.reason, 12.5f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
        addView(reason)
        val more = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val bits = ArrayList<String>()
        if (r.source == "connector") bits += "started by Claude"
        r.routine?.let { bits += "a step of $it" }
        if (r.counts.isNotEmpty()) bits += r.counts.entries.joinToString(", ") { "${Ui.title(it.key).lowercase()} ${it.value}" }
        if (bits.isNotEmpty()) addView(Ui.text(a, bits.joinToString(" · "), 12.5f).apply { setPadding(0, dp(4), 0, 0) })
        for (rep in r.reports) more.addView(Ui.text(a, reportLine(rep), 12.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        addView(more)
        setOnClickListener {
            val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            reason.maxLines = if (open) 20 else 2
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reportLine(rep: Map<String, Any?>): String {
        val parts = ArrayList<String>()
        rep["dungeon"]?.let { parts += it.toString() }
        (rep["seconds"] as? Number)?.let { parts += RunStats.duration(it.toLong() * 1000) }
        rep["key"]?.let { parts += "${it} key" }
        if (rep["skipped"] == true) parts += "skipped with a ticket"
        (rep["chests"] as? Number)?.let { c -> parts += "chests $c" + ((rep["chests_total"] as? Number)?.let { "/$it" } ?: "") }
        (rep["battles"] as? Number)?.let { parts += "battles $it" }
        (rep["worlds"] as? Number)?.let { parts += "worlds $it" }
        if (rep["white_key"] == true) parts += "White Key"
        val got = listOf("items", "rewards").flatMap { k -> (rep[k] as? Map<String, Any?>).orEmpty().map { "${it.key} ×${it.value}" } }
        if (got.isNotEmpty()) parts += got.joinToString(", ")
        return "• " + parts.joinToString(" · ")
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun confirm(title: String, message: String, yes: () -> Unit) {
        android.app.AlertDialog.Builder(a).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> yes() }
            .show()
    }

    private fun pct(n: Int, of: Int) = if (of <= 0) "-" else "${(100.0 * n / of).toInt()}%"
    private fun shortDay(ms: Long) = SimpleDateFormat("d/M", Locale.getDefault()).format(Date(ms))
    private fun longDay(ms: Long) = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    private fun time(ms: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

    private fun ago(ms: Long): String {
        val min = (System.currentTimeMillis() - ms) / 60000
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 24 * 60 -> "${min / 60}h ago"
            min < 7 * 24 * 60 -> "${min / (24 * 60)}d ago"
            else -> longDay(ms)
        }
    }

    companion object {
        private const val HISTORY_STEP = 30
        private val STOPPED = Color.parseColor("#90A4AE")
        private val SAFETY = Color.parseColor("#BA68C8")

        fun color(o: RunStats.Outcome) = when (o) {
            RunStats.Outcome.DONE -> Ui.GOOD
            RunStats.Outcome.STOPPED -> STOPPED
            RunStats.Outcome.STUCK -> Ui.WARN
            RunStats.Outcome.SAFETY -> SAFETY
            RunStats.Outcome.ERROR -> Ui.BAD
        }
    }
}
