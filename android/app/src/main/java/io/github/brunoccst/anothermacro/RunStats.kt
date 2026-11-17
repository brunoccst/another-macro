package io.github.brunoccst.anothermacro

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * The Statistics page's data: one line of JSON per macro run (files/stats/runs.jsonl), written
 * when a run ends ([Runner.run]). Kept on the phone only; the user can clear it (all of it, or
 * one macro's) from the Statistics page.
 *
 * A run keeps its counters (the macro's `count:` stats: battles, chests, cats found...) and the
 * run reports the dungeon macros wrote meanwhile (files/runs.log: one per dungeon run, with time,
 * chests, items, rewards, key, White Key).
 */
object RunStats {
    /** Runs kept at most (the oldest go first). */
    const val MAX_RUNS = 5000

    /** How a run ended. */
    enum class Outcome(val label: String) {
        DONE("Ended by itself"),
        STOPPED("Stopped by you"),
        STUCK("Got stuck"),
        SAFETY("Safety stop"),
        ERROR("Error"),
    }

    data class Record(
        val macro: String,
        /** When it started and ended (ms since 1970). */
        val start: Long,
        val end: Long,
        /** The stop reason, as the log and macro_status show it. */
        val reason: String,
        /** The macro's counters (battles, chests, ...), the ones of macros it ran included. */
        val counts: Map<String, Int> = emptyMap(),
        /** The run reports written meanwhile (one per round of a macro, e.g. a dungeon run), as read from runs.log. */
        val reports: List<Map<String, Any?>> = emptyList(),
        /** Who started it: "app" (the floating buttons), "connector" (Claude) or "schedule" (the daily schedule). */
        val source: String = "app",
        /** The routine it was a step of, if any. */
        val routine: String? = null,
        /** What the macro said it got (the `got:` step: chest and ad rewards, inbox items), added up. */
        val items: Map<String, Int> = emptyMap(),
        /** What it was doing when it ended (the live log's last words), for "where it got stuck". */
        val step: String? = null,
        /** The last screen it recognised ("macro: screen"). */
        val screen: String? = null,
    ) {
        val ms get() = (end - start).coerceAtLeast(0)
        val outcome get() = classify(reason)
    }

    fun classify(reason: String): Outcome {
        val r = reason.trim().lowercase()
        return when {
            r == "stop requested" -> Outcome.STOPPED
            r.startsWith("safety stop") -> Outcome.SAFETY
            r.startsWith("error:") || r.startsWith("no screen capture") || r.startsWith("taps are off") -> Outcome.ERROR
            r.startsWith("stuck on") || r.startsWith("the screen stayed black") -> Outcome.STUCK
            else -> Outcome.DONE
        }
    }

    /** A stop reason without its numbers and details, to group alike runs ("Sea of Ice done — 13m 2s; ..." → "Sea of Ice done"). */
    fun reasonGroup(reason: String): String {
        val head = reason.substringBefore(" — ").substringBefore(";").trim()
        return head.replace(Regex("\\d+"), "n").take(70).ifEmpty { "(no reason)" }
    }

    // --- the file -------------------------------------------------------------------------

    private val json = ObjectMapper()
    private val lock = Any()
    private var dir: File? = null
    private var appends = 0

    fun init(context: android.content.Context) { if (dir == null) dir = File(context.filesDir, "stats") }

    /** For tests: keep the runs in [d]. */
    internal fun useDir(d: File) { dir = d }

    private fun file(): File? = dir?.let { it.mkdirs(); File(it, "runs.jsonl") }

    fun toJson(r: Record): String {
        val m = LinkedHashMap<String, Any?>()
        m["macro"] = r.macro; m["start"] = r.start; m["end"] = r.end; m["reason"] = r.reason
        if (r.counts.isNotEmpty()) m["counts"] = r.counts
        if (r.reports.isNotEmpty()) m["reports"] = r.reports
        m["source"] = r.source
        if (r.routine != null) m["routine"] = r.routine
        if (r.items.isNotEmpty()) m["items"] = r.items
        if (!r.step.isNullOrEmpty()) m["step"] = r.step
        if (!r.screen.isNullOrEmpty()) m["screen"] = r.screen
        return json.writeValueAsString(m)
    }

    @Suppress("UNCHECKED_CAST")
    fun fromJson(line: String): Record? = runCatching {
        val m = json.readValue(line, Map::class.java) as Map<String, Any?>
        Record(
            macro = m["macro"]?.toString() ?: return null,
            start = (m["start"] as? Number)?.toLong() ?: return null,
            end = (m["end"] as? Number)?.toLong() ?: return null,
            reason = m["reason"]?.toString().orEmpty(),
            counts = (m["counts"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }.toMap(),
            reports = (m["reports"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> },
            source = m["source"]?.toString() ?: "app",
            routine = m["routine"]?.toString(),
            items = (m["items"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }.toMap(),
            step = m["step"]?.toString(),
            screen = m["screen"]?.toString(),
        )
    }.getOrNull()

    /** Save a finished run. */
    fun record(r: Record) {
        val f = file() ?: return
        synchronized(lock) {
            runCatching {
                f.appendText(toJson(r) + "\n")
                // trim now and then, not on every run
                if (f.length() > 4_000_000 || ++appends % 50 == 0) {
                    val lines = f.readLines().filter { it.isNotBlank() }
                    if (lines.size > MAX_RUNS) f.writeText(lines.takeLast(MAX_RUNS).joinToString("\n", postfix = "\n"))
                }
            }.onFailure { MacroLog.e("could not save the run's statistics", it) }
        }
        listeners.forEach { runCatching { it() } }
    }

    /** Every run kept, oldest first. */
    fun all(): List<Record> {
        val f = file() ?: return emptyList()
        return synchronized(lock) { if (f.exists()) f.readLines().mapNotNull { if (it.isBlank()) null else fromJson(it) } else emptyList() }
    }

    /** Forget every run ([macro] null) or only [macro]'s. */
    fun clear(macro: String? = null) {
        val f = file() ?: return
        synchronized(lock) {
            when {
                macro == null -> f.delete()
                f.exists() -> {
                    val keep = f.readLines().filter { it.isNotBlank() && fromJson(it)?.macro != macro }
                    f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
                }
                else -> {}
            }
        }
        listeners.forEach { runCatching { it() } }
    }

    /** Notified (on any thread) when runs are saved or cleared. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // --- run reports (macros append them to files/runs.log; game.yaml `reports:` says how they show) ---

    /** Where runs.log ends now: the reports after it belong to the run starting. */
    fun reportsMark(filesDir: File): Long = File(filesDir, "runs.log").let { if (it.exists()) it.length() else 0L }

    /** The run reports written to runs.log after [mark]. */
    @Suppress("UNCHECKED_CAST")
    fun reportsSince(filesDir: File, mark: Long): List<Map<String, Any?>> {
        val f = File(filesDir, "runs.log")
        if (!f.exists() || f.length() <= mark) return emptyList()
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(mark)
                val bytes = ByteArray((raf.length() - mark).coerceAtMost(2_000_000).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }
                    .mapNotNull { runCatching { json.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // --- totals ------------------------------------------------------------------------------

    data class Summary(
        val runs: Int,
        val totalMs: Long,
        val avgMs: Long,
        val longestMs: Long,
        val shortestMs: Long,
        val first: Long?,
        val last: Long?,
        val outcomes: Map<Outcome, Int>,
        /** Grouped stop reasons, most frequent first. */
        val reasons: List<Pair<String, Int>>,
        /** The counters added up. */
        val counts: Map<String, Int>,
        /** Chest items and rewards from the run reports, added up (most first). */
        val items: List<Pair<String, Int>>,
        /** The run reports (game.yaml `reports:`), and their totals as (label, value), in field order. */
        val reports: Int,
        val reportTotals: List<Pair<String, String>>,
        /** The outcome card's lines of the report totals (fields with `card:`), as (label, total). */
        val cardTotals: List<Pair<String, Int>>,
        val byConnector: Int,
    )

    /** The totals of [reports] by the profile's fields: (label, value) for the Statistics page, and the card's (label, total). */
    fun reportTotals(reports: List<Map<String, Any?>>, fields: List<GameProfile.ReportField> = GameProfile.current.reports.fields):
        Pair<List<Pair<String, String>>, List<Pair<String, Int>>> {
        val rows = ArrayList<Pair<String, String>>()
        val card = ArrayList<Pair<String, Int>>()
        for (f in fields) {
            val values = reports.mapNotNull { it[f.key] }
            when {
                values.isEmpty() -> {}
                values.all { it is Boolean } -> {
                    val n = values.count { it == true }
                    if (f.total.isNotEmpty() && (n > 0 || f.card.isNotEmpty())) rows += f.total to n.toString()
                    if (f.card.isNotEmpty() && n > 0) card += f.card to n
                }
                values.all { it is Number } -> {
                    val n = values.sumOf { (it as Number).toInt() }
                    if (f.total.isNotEmpty()) {
                        val of = if (f.of.isEmpty()) 0 else reports.sumOf { (it[f.of] as? Number)?.toInt() ?: 0 }
                        rows += f.total to (if (of > 0) "$n of $of (${n * 100 / of}%)" else n.toString())
                    }
                    if (f.card.isNotEmpty() && n > 0) card += f.card to n
                }
                else -> if (f.total.isNotEmpty()) values.map { it.toString().lowercase() }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount().forEach { (v, n) -> rows += f.total.replace("{Value}", v.replace('_', ' ').replaceFirstChar { it.uppercase() }).replace("{value}", v) to n.toString() }
            }
        }
        return rows to card
    }

    fun summarize(runs: List<Record>): Summary {
        val counts = LinkedHashMap<String, Int>()
        val items = LinkedHashMap<String, Int>()
        for (r in runs) {
            for ((k, v) in r.counts) counts[k] = (counts[k] ?: 0) + v
            for ((k, v) in r.items) items[k] = (items[k] ?: 0) + v
            for (rep in r.reports) {
                for (part in listOf("items", "rewards")) {
                    @Suppress("UNCHECKED_CAST")
                    (rep[part] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.let { items[k] = (items[k] ?: 0) + it.toInt() } }
                }
            }
        }
        val ms = runs.map { it.ms }
        val reports = runs.flatMap { it.reports }
        val (totals, card) = reportTotals(reports)
        return Summary(
            runs = runs.size,
            totalMs = ms.sum(),
            avgMs = if (ms.isEmpty()) 0 else ms.sum() / ms.size,
            longestMs = ms.maxOrNull() ?: 0,
            shortestMs = ms.minOrNull() ?: 0,
            first = runs.minOfOrNull { it.start },
            last = runs.maxOfOrNull { it.start },
            outcomes = Outcome.values().associateWith { o -> runs.count { it.outcome == o } }.filterValues { it > 0 },
            reasons = runs.groupingBy { reasonGroup(it.reason) }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
            counts = counts,
            items = items.entries.sortedByDescending { it.value }.map { it.key to it.value },
            reports = reports.size, reportTotals = totals, cardTotals = card,
            byConnector = runs.count { it.source == "connector" },
        )
    }

    /**
     * Per day for the last [days] days (today last): the runs and their minutes, as
     * (day start ms, runs, minutes). Days are local days ([zone]).
     */
    fun perDay(runs: List<Record>, days: Int, now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<Triple<Long, Int, Double>> {
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
        }
        val out = ArrayList<Triple<Long, Int, Double>>()
        repeat(days) {
            val from = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val to = cal.timeInMillis
            val inDay = runs.filter { it.start in from until to }
            out += Triple(from, inDay.size, inDay.sumOf { it.ms } / 60000.0)
        }
        return out
    }

    /** "1h 05m", "13m 25s", "42s". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
            s >= 60 -> "%dm %02ds".format(s / 60, s % 60)
            else -> "${s}s"
        }
    }
}
