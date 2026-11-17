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
        /** The run reports written meanwhile (dungeon runs), as read from runs.log. */
        val reports: List<Map<String, Any?>> = emptyList(),
        /** Who started it: "app" (the floating buttons) or "connector" (Claude). */
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

    // --- run reports (dungeon macros append them to files/runs.log) -----------------------

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
        /** Dungeon runs in the reports, and of them: by key colour, with a White Key, skipped with a ticket. */
        val dungeonRuns: Int,
        val keys: Map<String, Int>,
        val whiteKeys: Int,
        val skipped: Int,
        val chests: Int,
        val chestsTotal: Int,
        val byConnector: Int,
    )

    fun summarize(runs: List<Record>): Summary {
        val counts = LinkedHashMap<String, Int>()
        val items = LinkedHashMap<String, Int>()
        val keys = LinkedHashMap<String, Int>()
        var dungeonRuns = 0; var white = 0; var skipped = 0; var chests = 0; var chestsTotal = 0
        for (r in runs) {
            for ((k, v) in r.counts) counts[k] = (counts[k] ?: 0) + v
            for ((k, v) in r.items) items[k] = (items[k] ?: 0) + v
            for (rep in r.reports) {
                dungeonRuns++
                rep["key"]?.toString()?.lowercase()?.takeIf { it.isNotBlank() }?.let { keys[it] = (keys[it] ?: 0) + 1 }
                if (rep["white_key"] == true) white++
                if (rep["skipped"] == true) skipped++
                chests += (rep["chests"] as? Number)?.toInt() ?: 0
                chestsTotal += (rep["chests_total"] as? Number)?.toInt() ?: 0
                for (part in listOf("items", "rewards")) {
                    @Suppress("UNCHECKED_CAST")
                    (rep[part] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.let { items[k] = (items[k] ?: 0) + it.toInt() } }
                }
            }
        }
        val ms = runs.map { it.ms }
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
            dungeonRuns = dungeonRuns, keys = keys, whiteKeys = white, skipped = skipped,
            chests = chests, chestsTotal = chestsTotal,
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
