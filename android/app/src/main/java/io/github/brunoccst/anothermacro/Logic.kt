package io.github.brunoccst.anothermacro

import android.content.Context
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Runs one YAML macro's steps (made by the logic pack for each run). */
interface FlowEngine {
    fun setup(ctx: io.github.brunoccst.anothermacro.Context)
    fun onScreen(ctx: io.github.brunoccst.anothermacro.Context, screen: String)
    fun onUnknown(ctx: io.github.brunoccst.anothermacro.Context)
}

/** What the logic pack offers the app (implemented by logic/Entry.kt). */
interface LogicEntry {
    /** The macro steps this logic knows. */
    val actions: Set<String>
    fun engine(scenario: FlowScenario): FlowEngine
}

/**
 * The logic pack: the code that changes most (macro steps, battles, dungeons, cats, Phantom Crystal,
 * routes: everything in the `logic` package) also ships apart from the app, as `logic.zip` next to
 * each branch's CI build. A phone whose installed app has the same shell (every other source file:
 * [BuildConfig.SHELL_ID]) downloads it and loads it at runtime, so a change to the logic needs no
 * app install (and no Play Protect prompt). Otherwise, or if the pack fails to load, the copy built
 * into the app runs.
 *
 * The shell never names a class of the `logic` package: it creates [ENTRY] by name with the
 * pack's class loader, which loads the `logic` package from the pack first and everything else
 * from the app.
 */
object Logic {
    const val PACKAGE = "io.github.brunoccst.anothermacro.logic"
    private const val ENTRY = "$PACKAGE.Entry"

    /** The logic in use: [source] says where it came from, [code] and [commit] are its build's. */
    class Loaded(val entry: LogicEntry, val source: String, val code: Long, val commit: String = "")

    private val bundled: Loaded by lazy {
        Loaded(Class.forName(ENTRY).getDeclaredConstructor().newInstance() as LogicEntry, "built in", BuildConfig.VERSION_CODE.toLong(), BuildConfig.COMMIT)
    }

    @Volatile private var pack: Loaded? = null
    @Volatile private var initialized = false

    /** The logic the next macro run uses. */
    fun current(): Loaded = pack ?: bundled

    /** Where the logic in use came from, for logs and Claude. */
    fun describe(): String = current().let { if (it === bundled) "built-in logic" else "logic pack ${it.source}" }

    /** The versionCode of the logic in use (for update checks: a newer CI build may only need a new pack). */
    fun code(): Long = maxOf(current().code, BuildConfig.VERSION_CODE.toLong())

    private fun dir(c: Context) = File(c.filesDir, "logic")

    /** Load the installed pack, if any and if it fits this app. Safe to call often. */
    fun init(c: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val current = File(dir(c), "current")
            if (!File(current, "classes.dex").exists()) return
            runCatching { pack = load(current) }
                .onSuccess { MacroLog.i("logic: using the logic pack ${pack?.source}") }
                .onFailure {
                    MacroLog.e("logic: the installed logic pack can't be used (${it.message}); using the built-in logic")
                    current.deleteRecursively()
                }
        }
    }

    /** Info about a pack, from its logic.json. */
    private class Info(val shell: String, val code: Long, val name: String, val branch: String, val commit: String)

    private fun info(dir: File): Info {
        val j = JSONObject(File(dir, "logic.json").readText())
        return Info(j.getString("shell"), j.getLong("code"), j.optString("name"), j.optString("branch"), j.optString("commit"))
    }

    private fun load(dir: File): Loaded {
        val i = info(dir)
        if (i.shell != BuildConfig.SHELL_ID) throw IllegalStateException("it was built for another app build (install the newest app)")
        val dex = File(dir, "classes.dex")
        // Android 14+ refuses to load writable code files
        dex.setReadOnly()
        val loader = PackFirstLoader(dex.path, File(dir, "oat").apply { mkdirs() }, Logic::class.java.classLoader!!)
        val entry = Class.forName(ENTRY, true, loader).getDeclaredConstructor().newInstance() as LogicEntry
        entry.actions // touch it: a pack that can't link fails here, not in a macro
        return Loaded(entry, "${i.name} (${i.branch}@${i.commit.take(7)})", i.code, i.commit)
    }

    /**
     * Download the logic pack at [url] and use it from the next macro run on. Throws if it doesn't
     * fit this app (the caller then installs the app instead). Blocking. A message for the log/Claude.
     */
    fun install(c: Context, url: String): String {
        init(c)
        val root = dir(c).apply { mkdirs() }
        val incoming = File(root, "incoming").apply { deleteRecursively(); mkdirs() }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            ZipInputStream(conn.inputStream).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // only the two files a pack has: nothing else is written
                    if (e.name != "classes.dex" && e.name != "logic.json") continue
                    File(incoming, e.name).outputStream().use { zip.copyTo(it) }
                }
            }
        } finally {
            conn.disconnect()
        }
        val loaded = try { load(incoming) } catch (e: Throwable) {
            incoming.deleteRecursively()
            throw IllegalStateException("the logic pack can't be used: ${e.message}", e)
        }
        // keep the new pack as "current" (a running macro keeps the old classes, already loaded)
        val current = File(root, "current")
        val old = File(root, "old-${System.currentTimeMillis()}")
        if (current.exists()) current.renameTo(old)
        if (!incoming.renameTo(current)) throw IllegalStateException("could not store the logic pack")
        root.listFiles()?.filter { it.name.startsWith("old-") }?.forEach { it.deleteRecursively() }
        // reload from its final place: the loader must point at files that stay
        pack = runCatching { load(current) }.getOrElse { loaded }
        MacroLog.i("logic: now using the logic pack ${pack?.source}")
        return "loaded the logic pack ${pack?.source}: no app install needed, the next macro run uses it"
    }

    /** Forget the downloaded pack: the built-in logic runs again. */
    fun reset(c: Context) {
        pack = null
        File(dir(c), "current").deleteRecursively()
    }

    /** Loads the `logic` package from the pack (never the app's built-in copy), everything else from the app. */
    private class PackFirstLoader(dex: String, oat: File, private val app: ClassLoader) :
        DexClassLoader(dex, oat.path, null, app) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("$PACKAGE.")) {
                synchronized(this) {
                    findLoadedClass(name)?.let { return it }
                    return findClass(name)
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
