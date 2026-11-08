package io.github.brunoccst.anothermacro

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * The macro library: the macros (.yaml), their templates and their data, downloaded from the
 * GitHub project instead of built into the app, so macro fixes and new macros arrive without an
 * app update.
 *
 * CI packs the repo's `macros/` and template folders into `macros.zip` on every push and publishes
 * it as the pre-release `macros-<branch>` (see .github/workflows/macros.yml). The app follows the
 * same branch as its own updates (a release build: main), falling back to main when that branch
 * has no pack. The pack's notes carry the commit it was built from; a different commit than the
 * installed one is an update. Installing unpacks into a fresh folder and swaps it in, so a failed
 * download never leaves half a library.
 *
 * Layout on the phone (files/library): macros/<group>/<name>.yaml, templates/<folder>/library.yaml
 * and PNGs (templates/common: the shared ones), version.json.
 */
object MacroLibrary {
    private const val REPO = "brunoccst/another-macro"
    private const val CHECK_EVERY_MS = 15 * 60_000L

    fun dir(c: Context) = File(c.filesDir, "library")
    fun files(c: Context) = LibraryFiles(dir(c))

    /** Notified (on any thread) after a new library was installed. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** What the app shows about the library: "", "checking…", the last error. */
    @Volatile var state: String = ""
        private set

    data class Installed(val commit: String, val branch: String, val built: String)
    data class Pack(val tag: String, val branch: String, val commit: String, val url: String)

    fun installed(c: Context): Installed? = runCatching {
        val j = JSONObject(File(dir(c), "version.json").readText())
        Installed(j.getString("commit"), j.optString("branch"), j.optString("built"))
    }.getOrNull()

    fun isInstalled(c: Context) = installed(c) != null

    /** The branch whose macro packs this phone follows: the app's own update channel. */
    fun channel(c: Context): String = if (BuildConfig.VERSION_NAME.contains("-dev")) Updater.channel(c) else "main"

    /** "claude/x" -> "macros-claude-x" (same rule as the dev builds' tags). */
    fun tag(branch: String) = "macros-" + branch.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")

    /** The newest pack of [branch], or null if CI hasn't published one. Blocking. */
    fun latest(branch: String): Pack? {
        val tag = tag(branch)
        val conn = (URL("https://api.github.com/repos/$REPO/releases/tags/$tag").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val commit = Regex("""commit:\s*([0-9a-f]{7,40})""").find(json.optString("body"))?.groupValues?.get(1)
                ?: throw IllegalStateException("the macro pack's notes carry no commit")
            val assets = json.getJSONArray("assets")
            val zip = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == "macros.zip" } ?: throw IllegalStateException("the macro pack has no macros.zip")
            return Pack(tag, branch, commit, zip.getString("browser_download_url"))
        } finally {
            conn.disconnect()
        }
    }

    /** The pack to install if it differs from the installed one, else null. Blocking. */
    fun checkNewer(c: Context): Pack? {
        val branch = channel(c)
        val pack = latest(branch) ?: (if (branch != "main") latest("main") else null)
            ?: throw IllegalStateException("no macro pack published yet")
        return if (pack.commit != installed(c)?.commit) pack else null
    }

    /** Download [pack] and swap it in. Blocking. */
    fun install(c: Context, pack: Pack) {
        val root = dir(c)
        val fresh = File(c.filesDir, "library.new").apply { deleteRecursively(); mkdirs() }
        val conn = (URL(pack.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            conn.inputStream.use { unzip(it, fresh) }
        } finally {
            conn.disconnect()
        }
        val macros = Scenarios.parseAll(LibraryFiles(fresh))
        if (macros.isEmpty()) { fresh.deleteRecursively(); throw IllegalStateException("the macro pack holds no macros") }
        File(fresh, "version.json").writeText(JSONObject()
            .put("commit", pack.commit).put("branch", pack.branch)
            .put("built", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            .toString())
        val old = File(c.filesDir, "library.old").apply { deleteRecursively() }
        if (root.exists() && !root.renameTo(old)) throw IllegalStateException("could not replace the old library")
        if (!fresh.renameTo(root)) { old.renameTo(root); throw IllegalStateException("could not install the new library") }
        old.deleteRecursively()
        MacroLog.i("macros updated to ${pack.branch}@${pack.commit.take(7)} (${macros.size} macros)")
        listeners.forEach { runCatching { it() } }
    }

    private val busy = AtomicBoolean(false)
    @Volatile private var lastCheck = 0L

    /**
     * Check for a newer pack and install it, off the main thread. Skipped while a macro runs (it
     * reads the library) and, unless [force], within 15 minutes of the last check when a library
     * is installed. [done] gets a line for the app (on the worker thread).
     */
    fun updateAsync(c: Context, force: Boolean = false, done: (String) -> Unit = {}) {
        val app = c.applicationContext
        if (Runner.isRunning) { done("a macro is running: macro updates wait until it stops"); return }
        if (!force && isInstalled(app) && System.currentTimeMillis() - lastCheck < CHECK_EVERY_MS) return
        if (!busy.compareAndSet(false, true)) return
        lastCheck = System.currentTimeMillis()
        Thread {
            try {
                state = "checking for macro updates…"
                val msg = try {
                    val pack = checkNewer(app)
                    if (pack == null) "macros are up to date"
                    else if (Runner.isRunning) "a macro is running: macro updates wait until it stops"
                    else { state = "downloading the macros…"; install(app, pack); "macros updated" }
                } catch (e: Exception) {
                    MacroLog.e("macro update failed: ${e.message}")
                    "could not update the macros: ${e.message}"
                }
                state = if (msg.startsWith("could not")) msg else ""
                done(msg)
            } finally { busy.set(false) }
        }.start()
    }

    /** Unpack [zip] into [into], refusing entries that would land outside it. */
    internal fun unzip(zip: InputStream, into: File) {
        val base = into.canonicalPath + File.separator
        ZipInputStream(zip.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("bad entry in the macro pack: ${e.name}")
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }
}

/** Read-only files of the macro library (what the APK's assets used to be). */
class LibraryFiles(val root: File) {
    fun open(path: String): InputStream {
        val f = File(root, path)
        if (!f.isFile) throw FileNotFoundException(path)
        return f.inputStream()
    }

    /** Names in a folder of the library, sorted; empty when there is none. */
    fun list(path: String): Array<String> = File(root, path).list()?.sorted()?.toTypedArray() ?: emptyArray()

    fun isDir(path: String) = File(root, path).isDirectory
}

/**
 * Each macro's own options, as the user last chose them (Macros → Select this macro). Every run
 * starts from them: from the floating buttons, a routine's steps and Claude's start_macro.
 */
object MacroParams {
    private const val PREFS = "macro_params"

    fun load(c: Context, name: String): Map<String, String> {
        val saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null)
        if (saved == null) {
            // before options were kept per macro, only the last run's were: that macro's, and its
            // skills for any macro with skills (the battle macro took them over from the fight macros)
            val last = LastRun.load(c) ?: return emptyMap()
            return if (last.first == name) last.second else last.second.filterKeys { it.startsWith("skill_") }
        }
        return decode(saved)
    }

    fun save(c: Context, name: String, params: Map<String, String>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, encode(params)).apply()
    }

    internal fun encode(params: Map<String, String>) = params.entries.joinToString("\n") { "${it.key}=${it.value}" }
    internal fun decode(s: String) = s.lines().mapNotNull {
        val kv = it.split("=", limit = 2)
        if (kv.size == 2) kv[0] to kv[1] else null
    }.toMap()
}
