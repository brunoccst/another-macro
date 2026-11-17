package macroforge

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * What happened on the phone while nobody was watching: a macro ended (its outcome message), a
 * scheduled run started or could not start, Claude asked to tap something and the question moved
 * to a notification, the user answered it. Kept in a small file, so it survives an app restart
 * or a self-update, and each entry says whether Claude has been told about it yet: macro_status
 * lists the new ones once ("news"), so a later chat picks up what happened without the user
 * repeating it, and nothing is reported twice.
 *
 * Every new event also goes to [Alerts] (an Android notification) when it is one the user should
 * see: a person who isn't looking at the phone learns about it without asking Claude.
 */
object PhoneEvents {
    data class Event(
        val id: Long,
        val time: Long,
        /** outcome, schedule, approval. */
        val kind: String,
        val title: String,
        val text: String,
        /** False for a problem (a run that failed or got stuck, a scheduled run that couldn't start). */
        val ok: Boolean,
        val reported: Boolean = false,
    )

    /** How many events the file keeps (the oldest go first). */
    const val KEEP = 50

    private val json = ObjectMapper()
    @Volatile private var file: File? = null
    private val events = ArrayList<Event>()
    private var loaded = false

    fun init(c: android.content.Context) { if (file == null) useFile(File(c.filesDir, "events.json")) }

    /** Where the events live (tests use a temporary file). */
    @Synchronized fun useFile(f: File) {
        file = f
        events.clear()
        loaded = false
    }

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            json.readTree(f).forEach { n ->
                events += Event(
                    n.path("id").asLong(), n.path("time").asLong(), n.path("kind").asText(""),
                    n.path("title").asText(""), n.path("text").asText(""), n.path("ok").asBoolean(true), n.path("reported").asBoolean(false),
                )
            }
        }.onFailure { MacroLog.e("could not read the event list", it) }
    }

    @Synchronized private fun save() {
        val f = file ?: return
        val arr = json.createArrayNode()
        for (e in events) arr.addObject().put("id", e.id).put("time", e.time).put("kind", e.kind)
            .put("title", e.title).put("text", e.text).put("ok", e.ok).put("reported", e.reported)
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            json.writeValue(tmp, arr)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { MacroLog.e("could not save the event list", it) }
    }

    /** Keep a new event (the caller decides whether to notify). */
    @Synchronized fun add(kind: String, title: String, text: String, ok: Boolean, now: Long = System.currentTimeMillis()): Event {
        load()
        val id = maxOf(now, (events.lastOrNull()?.id ?: 0L) + 1)
        val e = Event(id, now, kind, title, text, ok)
        events += e
        while (events.size > KEEP) events.removeAt(0)
        save()
        return e
    }

    @Synchronized fun all(): List<Event> { load(); return events.toList() }

    @Synchronized fun last(kind: String? = null): Event? { load(); return events.lastOrNull { kind == null || it.kind == kind } }

    /** The events Claude hasn't been told about yet, oldest first; they count as told from now on. */
    @Synchronized fun takeUnreported(): List<Event> {
        load()
        val news = events.filter { !it.reported }
        if (news.isEmpty()) return news
        for (i in events.indices) if (!events[i].reported) events[i] = events[i].copy(reported = true)
        save()
        return news
    }

    /** Events as macro_status shows them. */
    fun describe(list: List<Event>, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US).apply { timeZone = zone }
        return list.joinToString("\n") { e ->
            "- ${fmt.format(java.util.Date(e.time))} ${if (e.ok) "" else "[problem] "}${e.title}" +
                (if (e.text.isBlank()) "" else "\n  " + e.text.replace("\n", "\n  "))
        }
    }
}
