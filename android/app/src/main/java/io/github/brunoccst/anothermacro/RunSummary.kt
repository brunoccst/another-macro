package io.github.brunoccst.anothermacro

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The outcome message: when a macro ends, one card over the game says how it went. A run that
 * ended by itself lists what it got (chest items, rewards, the `got:` step's items) and what it
 * did; one that failed says why and where it was (its last step and screen). A macro that loops
 * (dungeon runs 1-10 or "all") is one run, so the card comes once, after the last loop; a
 * routine shows one card for all its steps when it ends.
 *
 * Shown for runs started on the phone (floating buttons, the app); runs Claude starts through the
 * connector put the same text in macro_status instead. The user can turn the card off in
 * System → Settings. Built from the same [RunStats.Record] the Statistics page keeps.
 */
object RunSummary {
    private const val PREFS = "run_summary"

    data class Outcome(
        val title: String,
        /** False when the run stopped on a problem (stuck, error, safety stop, a step that failed). */
        val ok: Boolean,
        val lines: List<String>,
        /** The macro whose statistics the card links to. */
        val macro: String,
    ) {
        val text get() = title + "\n" + lines.joinToString("\n")
    }

    /** The last outcome (for macro_status), and when it was made. */
    @Volatile var last: Outcome? = null
        private set

    fun enabled(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean("on", true)

    fun setEnabled(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        MacroLog.i("outcome message ${if (on) "on" else "off"}")
        if (!on) hide()
    }

    // --- the text -------------------------------------------------------------------------------

    private fun pretty(macro: String) = macro.replace('-', ' ').replaceFirstChar { it.uppercase() }

    /** The outcome of one run (a loop of dungeon runs included). */
    fun of(r: RunStats.Record): Outcome {
        val o = r.outcome
        val name = pretty(r.macro)
        val title = when (o) {
            RunStats.Outcome.DONE -> "$name finished"
            RunStats.Outcome.STOPPED -> "$name stopped by you"
            RunStats.Outcome.STUCK -> "$name got stuck"
            RunStats.Outcome.SAFETY -> "$name: safety stop"
            RunStats.Outcome.ERROR -> "$name failed"
        } + " (${RunStats.duration(r.ms)})"
        val lines = ArrayList<String>()
        lines += (if (o == RunStats.Outcome.DONE) "Result: " else "Why: ") + r.reason
        if (o != RunStats.Outcome.DONE) lines += where(r)
        lines += body(listOf(r))
        return Outcome(title, o == RunStats.Outcome.DONE || o == RunStats.Outcome.STOPPED, lines, r.macro)
    }

    /** The outcome of a routine: each step's result, then everything its steps got. */
    fun ofRoutine(routine: String, steps: List<RunStats.Record>, missing: List<String>, end: String, ms: Long): Outcome {
        val bad = steps.filter { it.outcome != RunStats.Outcome.DONE }
        val name = pretty(routine)
        val title = when {
            end == "stop requested" -> "$name stopped by you"
            bad.isEmpty() && missing.isEmpty() -> "$name finished"
            else -> "$name finished with problems"
        } + " (${steps.size} step(s), ${RunStats.duration(ms)})"
        val lines = ArrayList<String>()
        for (s in steps) lines += "${mark(s.outcome)} ${pretty(s.macro)}: ${s.reason}"
        for (m in missing) lines += "✕ $m: unknown macro (update the macros)"
        if (end.startsWith("safety stop")) lines += "The remaining steps were skipped after the safety stop."
        for (s in bad.filter { it.outcome != RunStats.Outcome.STOPPED }) lines += "${pretty(s.macro)} — " + where(s).removePrefix("Where: ")
        lines += body(steps)
        return Outcome(title, bad.none { it.outcome != RunStats.Outcome.STOPPED } && missing.isEmpty(), lines, routine)
    }

    private fun mark(o: RunStats.Outcome) = when (o) {
        RunStats.Outcome.DONE -> "✓"
        RunStats.Outcome.STOPPED -> "■"
        else -> "✕"
    }

    /** Where a run was when it ended: its last step (the live log's words) and screen. */
    private fun where(r: RunStats.Record): String {
        val bits = ArrayList<String>()
        r.step?.takeIf { it.isNotBlank() }?.let { bits += "doing \"$it\"" }
        r.screen?.takeIf { it.isNotBlank() }?.let { bits += "last screen seen: $it" }
        return "Where: " + bits.joinToString("; ").ifEmpty { "it hadn't recognised any screen yet" }
    }

    /** What the runs got and did: dungeon runs one per line, the items added up, the counters. */
    private fun body(runs: List<RunStats.Record>): List<String> {
        val lines = ArrayList<String>()
        val reports = runs.flatMap { it.reports }
        if (reports.size > 1) lines += "Runs: ${reports.size}"
        for (rep in reports.take(12)) lines += reportLine(rep)
        if (reports.size > 12) lines += "• … and ${reports.size - 12} more (see Statistics)"
        val s = RunStats.summarize(runs)
        lines += if (s.items.isEmpty()) "Got: nothing read" else "Got: " + s.items.joinToString(", ") { "${it.first} ×${it.second}" }
        if (s.whiteKeys > 0) lines += "White Key: ${s.whiteKeys}"
        val did = s.counts.filterValues { it > 0 }
        if (did.isNotEmpty()) lines += "Counted: " + did.entries.joinToString(", ") { "${Ui.title(it.key).lowercase()} ${it.value}" }
        return lines
    }

    /** One dungeon run report as a line (also the Statistics page's). */
    @Suppress("UNCHECKED_CAST")
    fun reportLine(rep: Map<String, Any?>): String {
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

    // --- reading items from a reward text -------------------------------------------------------

    /** Words before "x5" that are the game's dialog, not the item's name. */
    private val NOT_NAME = setOf("rewards", "reward", "received", "receive", "the", "items", "item", "below", "have", "has", "been",
        "you", "obtained", "got", "ok", "close", "and", "of", "with", "rewarded", "thank", "for", "watching")

    /**
     * The items a reward text names, with their numbers: "Rewards received: 1 / Chronos Stone x5"
     * → {Chronos Stone: 5}; "You have been rewarded 10 Chronos Stones." → {Chronos Stones: 10}.
     * Names are the words right before "x5" / "×5" (or after a leading number), dialog words left out.
     */
    fun parseItems(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val words = text.replace("×", " x").replace(Regex("""(?i)(?<=\s|^)x\s+(\d)"""), "x$1").split(Regex("""\s+""")).filter { it.isNotEmpty() }
        fun isWord(w: String) = Regex("""^[A-Za-z][A-Za-z'’.&-]*$""").matches(w) && w.lowercase().trim('.') !in NOT_NAME
        fun add(name: List<String>, n: Int) {
            val key = name.joinToString(" ").trim('.', ',', ':', ' ')
            if (key.isNotEmpty() && n > 0) out[key] = (out[key] ?: 0) + n
        }
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val times = Regex("""^(?i)x(\d[\d,]*)[.,;]?$""").find(w)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (times != null) {
                // "Chronos Stone x5": up to 5 words back
                val name = ArrayList<String>()
                var j = i - 1
                while (j >= 0 && name.size < 5 && isWord(words[j].trimEnd(',', ':'))) { name.add(0, words[j].trimEnd(',', ':')); j-- }
                add(name, times)
                i++; continue
            }
            val lead = Regex("""^(\d[\d,]*)$""").find(w)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (lead != null && i + 1 < words.size && isWord(words[i + 1])) {
                // "rewarded 10 Chronos Stones.": the words after the number, up to the sentence end
                val name = ArrayList<String>()
                var j = i + 1
                while (j < words.size && name.size < 5 && isWord(words[j].trimEnd('.', ',', '!'))) {
                    name += words[j].trimEnd('.', ',', '!')
                    if (words[j].endsWith('.') || words[j].endsWith(',') || words[j].endsWith('!')) break
                    j++
                }
                // a number followed by a name that ends in "x5" is that item's own count: skip it
                if (j < words.size && j > i + 1 && Regex("""^(?i)x\d""").containsMatchIn(words.getOrElse(j) { "" })) { i = j; continue }
                add(name, lead)
                i = j + 1; continue
            }
            i++
        }
        return out
    }

    // --- the card -------------------------------------------------------------------------------

    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null

    /** A run (or routine) ended: keep its outcome, log it, and show the card if it is switched on. */
    fun finished(app: android.content.Context, o: Outcome, show: Boolean = true) {
        last = o
        MacroLog.i("outcome: ${o.text.replace("\n", " | ")}")
        if (show && enabled(app)) this.show(o)
    }

    /** Take the card off the screen (a new run starts, or the user turned it off). */
    fun hide() {
        main.post {
            val v = shown ?: return@post
            val s = InputService.instance
            if (s != null) runCatching { (s.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
            shown = null
        }
    }

    private fun show(o: Outcome) {
        val service = InputService.instance ?: return
        main.post {
            val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            shown?.let { runCatching { wm.removeView(it) } }
            shown = null
            val dm = service.resources.displayMetrics
            fun dp(v: Int) = Ui.dp(service, v)
            val accent = if (o.ok) Ui.GOOD else Ui.BAD
            val body = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                for (l in o.lines) addView(Ui.text(service, l, 13f, if (l.startsWith("•")) Ui.MUTED else Ui.TEXT).apply { setPadding(0, dp(3), 0, 0) })
            }
            val box = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(12))
                background = Ui.rounded(Ui.OVERLAY_BG, dp(16).toFloat(), accent, dp(2))
                addView(Ui.text(service, "Another Macro", 12f, Ui.ACCENT))
                addView(Ui.text(service, o.title, 16f, accent, bold = true).apply { setPadding(0, dp(2), 0, dp(6)) })
                addView(ScrollView(service).apply { addView(body) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    setPadding(0, dp(10), 0, 0)
                    fun small(b: android.widget.TextView, left: Int) = b.apply {
                        textSize = 14f
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = left }
                    }
                    addView(small(Ui.button(service, "Statistics") {
                        hide()
                        runCatching {
                            service.startActivity(android.content.Intent(service, MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(MainActivity.EXTRA_PAGE, "stats").putExtra(MainActivity.EXTRA_MACRO, o.macro))
                        }
                    }, 0))
                    addView(small(Ui.button(service, "OK", primary = true) { hide() }, dp(8)))
                })
            }
            val w = (maxOf(dm.widthPixels, dm.heightPixels) * 0.5).toInt()
            val h = (minOf(dm.widthPixels, dm.heightPixels) * 0.85).toInt()
            // measured first, so a short card stays short and a long one scrolls
            box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
            val lp = WindowManager.LayoutParams(
                w, box.measuredHeight.coerceAtMost(h),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // taps outside the card still reach the game
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            runCatching { wm.addView(box, lp); shown = box }.onFailure { MacroLog.e("could not show the outcome message", it) }
        }
    }
}
