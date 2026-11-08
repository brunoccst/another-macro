package io.github.brunoccst.anothermacro

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class StopScenario(reason: String) : Exception(reason)

/**
 * A user-tunable option, given as text (from the app's form or an intent extra).
 * [choices] makes the form show a dropdown; [type] "bool" a checkbox; otherwise a text box.
 */
data class Param(val default: String, val help: String, val choices: List<String> = emptyList(), val type: String = "text",
                 /** Shown in the app only while this switch param is on (e.g. the boss skills under boss_setup). */
                 val showIf: String? = null) {
    val isBool get() = type == "bool"
}

/** What a handler gets: the current frame, vision, input and helpers. Mirrors the Python tool's Context. */
class Context(
    private val capture: Capture,
    private val files: LibraryFiles,
    val vision: Vision,
    val params: Map<String, String>,
    private val stopRequested: AtomicBoolean,
    /** How many macros this one runs inside (Flow's run:); 0 for the macro the user started. */
    val depth: Int = 0,
) {
    val stats = LinkedHashMap<String, Int>()
    var frame: Frame? = null
        private set
    var screen: String? = null
    private val input: InputService
        get() = InputService.instance ?: throw StopScenario("taps are off: enable Another Macro in Accessibility settings")

    fun int(name: String) = params.getValue(name).trim().toInt()
    fun double(name: String) = params.getValue(name).trim().toDouble()
    fun str(name: String) = params.getValue(name).trim()

    fun count(stat: String, by: Int = 1) { stats[stat] = (stats[stat] ?: 0) + by }

    fun refresh(): Frame {
        frame?.gray?.release()
        val f = capture.grab() ?: throw StopScenario("no screen capture (allow screen capture first)")
        frame = f
        return f
    }

    fun find(template: String): Match? = frame?.let { vision.find(it, template) }

    /** The current screen in colour as BGR (caller releases it). */
    fun colorMat(): org.opencv.core.Mat? = capture.grabColor()

    /** The current screen in colour (RGBA bitmap), for text recognition. */
    fun colorBitmap(): android.graphics.Bitmap? {
        val bgr = capture.grabColor() ?: return null
        val rgba = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.cvtColor(bgr, rgba, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA)
        bgr.release()
        val bmp = android.graphics.Bitmap.createBitmap(rgba.cols(), rgba.rows(), android.graphics.Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }

    /** Text lines on screen right now (on-device OCR), with normalized positions. */
    fun readText(): List<Ocr.Line> {
        Thread.sleep(150) // let the latest frame arrive
        val bmp = colorBitmap() ?: return emptyList()
        // the live log overlay is on the screen capture too: never read our own words as the game's
        val own = LiveLog.area
        return try { Ocr.read(bmp).filter { l -> own == null || !own.contains(l.center) } } finally { bmp.recycle() }
    }

    /** Text in one part of the screen only (faster: a smaller image to read). */
    fun readText(part: Region): List<Ocr.Line> {
        val bmp = colorBitmap() ?: return emptyList()
        val (l, t, r, b) = part.toPx(bmp.width, bmp.height).toList()
        val crop = android.graphics.Bitmap.createBitmap(bmp, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
        bmp.recycle()
        return try {
            Ocr.read(crop).map { line ->
                Ocr.Line(line.text, Region(part.x + line.box.x * part.w, part.y + line.box.y * part.h, line.box.w * part.w, line.box.h * part.h))
            }
        } finally { crop.recycle() }
    }

    private fun px(p: Point): Pair<Float, Float> = p.toPx(capture.width, capture.height)

    private fun point(target: Any): Point = when (target) {
        is Point -> target
        is Match -> jitter(target.region)
        is Region -> jitter(target)
        is String -> find(target)?.let { jitter(it.region) } ?: throw IllegalStateException("'$target' not visible")
        else -> throw IllegalArgumentException("can't tap $target")
    }

    // land somewhere in the middle half of a button, like a finger would
    private fun jitter(r: Region) = Point(r.x + r.w * Random.nextDouble(0.25, 0.75), r.y + r.h * Random.nextDouble(0.25, 0.75))

    fun tap(target: Any) {
        val p = point(target)
        guardPurchases(target as? String, blind = target !is String)
        val (x, y) = px(p)
        input.tap(x, y)
    }

    // --- safety: no purchase, ever --------------------------------------------------
    /** Templates that may be tapped while the shop is open: the free commercial, its dialogs, the X. */
    private val SHOP_SAFE = setOf("shop_watch_button", "shop_close_button", "dialog_close_button", "dialog_ok_button", "confirm_ok_button")

    /**
     * Before every tap: if the Shop or a purchase popup is on screen, only [SHOP_SAFE] templates may
     * be tapped. Anything else (above all a blind tap at a fixed spot) is refused: the macro cancels
     * the popup, closes the shop and stops. A blind tap also checks the text on screen for a
     * purchase popup (it covers the shop's title).
     */
    private fun guardPurchases(template: String?, blind: Boolean) {
        if (template != null && template in SHOP_SAFE) return
        val f = capture.grab() ?: return
        val inShop = try { vision.find(f, "shop_title") != null } finally { f.gray.release() }
        val lines = if (blind || inShop) readText() else emptyList()
        val norm = lines.map { Ocr.norm(it.text) }
        val purchase = norm.any { it == "purchase" || it.startsWith("purchase for") || it.contains("remaining purchases") }
        if (!inShop && !purchase) return
        MacroLog.i("SAFETY: refused a ${template ?: "blind"} tap on the ${if (purchase) "purchase popup" else "shop"}")
        lines.firstOrNull { Ocr.norm(it.text) == "cancel" }?.let { input.tap(px(it.center).first, px(it.center).second); Thread.sleep(1500) }
        val g = capture.grab()
        if (g != null) {
            try { vision.find(g, "shop_close_button")?.let { m -> val (x, y) = px(m.region.center); input.tap(x, y) } } finally { g.gray.release() }
        }
        throw StopScenario("safety stop: the shop or a purchase popup was on screen (no tap made there)")
    }

    /** Press at [start], slide to [end] and keep holding until [durationMs] is up (walking). */
    fun drag(start: Point, end: Point, durationMs: Long) {
        val (x1, y1) = px(start)
        val (x2, y2) = px(end)
        input.stroke(x1, y1, x2, y2, durationMs)
    }

    /**
     * What a [hold] did: how long the finger was down, and when [watch] asked to stop
     * (ms after the press; null when it ran its full time), [cancelled] if the system ended it early.
     */
    data class HoldResult(val heldMs: Long, val stoppedAtMs: Long?, val cancelled: Boolean)

    /**
     * Like [drag], but the screen can be watched while the finger is down: [watch] runs over and
     * over with the ms held so far, and returning true lifts the finger there (a battle started).
     */
    fun hold(start: Point, end: Point, durationMs: Long, watch: (Long) -> Boolean): HoldResult {
        val (x1, y1) = px(start)
        val (x2, y2) = px(end)
        val h = input.startHold(x1, y1, x2, y2, durationMs)
        var stoppedAt: Long? = null
        val giveUp = System.currentTimeMillis() + durationMs + 5000
        while (h.done.count > 0 && System.currentTimeMillis() < giveUp) {
            if (stopRequested.get()) h.stop.set(true)
            if (stoppedAt == null && h.startedAt != 0L) {
                val t = h.heldMs
                if (watch(t)) { stoppedAt = t; h.stop.set(true) }
            } else h.done.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        h.stop.set(true)
        h.done.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (stopRequested.get()) throw StopScenario("stop requested")
        return HoldResult(h.heldMs.coerceAtMost(durationMs + 500), stoppedAt, h.cancelled)
    }

    fun back() { input.back() }

    /** Tap exactly at [p] (no jitter): for moving targets aimed at precisely. */
    fun tapExact(p: Point) {
        guardPurchases(null, blind = false)
        val (x, y) = px(p)
        input.tap(x, y)
    }

    fun launchApp(pkg: String) = input.launchApp(pkg)

    /** Package of the app in front (from accessibility window events), or null if unknown. */
    val foreground: String? get() = InputService.instance?.foreground

    /** The macro library's files (templates, data). */
    val assets get() = files

    /** Find another macro by name, for Flow's run: (set by [Runner.run]). */
    var findMacro: (String) -> Scenario? = { null }

    /** The user's own captured templates, added to every library (set by [Runner.run]). */
    var userTemplates: java.io.File? = null

    /** Another macro's options as the user chose them in the app, over its defaults (set by [Runner.run]). */
    var optionsOf: (String) -> Map<String, String> = { emptyMap() }

    /**
     * Run the basic macro [name] here (see [Runner.runInside]) with its own options as the user
     * chose them, plus [given]; its stop reason.
     */
    fun runBasic(name: String, given: Map<String, String> = emptyMap(), until: List<String> = emptyList()): String {
        val sub = findMacro(name) ?: throw IllegalStateException("the basic macro '$name' is missing: update the macros")
        return Runner.runInside(sub, given, this, until)
    }

    /** A context for a macro run inside this one: same screen, input, stop and pause. */
    fun child(vision: Vision, params: Map<String, String>) =
        Context(capture, files, vision, params, stopRequested, depth + 1).also {
            it.debugDir = debugDir; it.findMacro = findMacro; it.userTemplates = userTemplates; it.optionsOf = optionsOf
        }

    /** Where macros may leave debug images (the app's own storage). */
    var debugDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")

    fun bool(name: String) = params.getValue(name).trim().lowercase() in setOf("1", "true", "yes", "on", "y")

    fun wait(seconds: Double) {
        val end = System.currentTimeMillis() + (seconds * Random.nextDouble(0.9, 1.2) * 1000).toLong()
        while (System.currentTimeMillis() < end) {
            if (stopRequested.get()) throw StopScenario("stop requested")
            Thread.sleep(50)
        }
        Runner.waitWhilePaused(stopRequested)
    }

    fun stop(reason: String): Nothing = throw StopScenario(reason)
}

abstract class Scenario {
    abstract val name: String
    abstract val description: String
    /** Which group the app lists it under (Groups: daily, dungeon, farm, game, other). */
    open val group: String = "other"
    /** Its icon in the app (chest, paw, crystal, key, swords, sun, power); empty = its group's. */
    open val icon: String = ""
    open val params: Map<String, Param> = emptyMap()
    /** Asset folders (under templates/) whose library.yaml this scenario uses, besides "common". */
    open val templateFolders: List<String> = emptyList()
    /** Screens to recognise, in priority order (first match wins). */
    abstract val screens: List<String>
    open val unknownTimeoutS: Double = 45.0
    open val tickS: Double = 0.4

    open fun setup(ctx: Context) {}
    open fun onUnknown(ctx: Context) {}
    /** Called with the recognised screen's name. */
    abstract fun onScreen(ctx: Context, screen: String)

    fun summary(stats: Map<String, Int>) =
        if (stats.isEmpty()) "nothing counted" else stats.entries.joinToString(", ") { "${it.key}=${it.value}" }
}

/**
 * Macros are .yaml files: the macro library downloaded from the GitHub project
 * (files/library/macros, see [MacroLibrary]) and the custom ones Claude saved through the
 * phone connector (files/macros), which are listed under Custom.
 */
object Scenarios {
    fun userDir(context: android.content.Context) = java.io.File(context.filesDir, "macros").apply { mkdirs() }
    fun userTemplates(context: android.content.Context) = java.io.File(context.filesDir, "templates/user").apply { mkdirs() }

    fun all(context: android.content.Context): List<Scenario> {
        val found = LinkedHashMap<String, Scenario>()
        for (s in parseAll(MacroLibrary.files(context))) found[s.name] = s
        for (f in userDir(context).listFiles().orEmpty().filter { it.name.endsWith(".yaml") }.sortedBy { it.name }) {
            // a library macro keeps its name: a custom one can't replace it
            load({ f.readText() }, f.name, custom = true)?.let { if (it.name !in found) found[it.name] = it }
        }
        return found.values.toList()
    }

    /** Every macro of a library: the .yaml files under macros/ (one folder per group). */
    fun parseAll(files: LibraryFiles): List<Scenario> {
        val out = ArrayList<Scenario>()
        fun walk(dir: String) {
            for (n in files.list(dir)) {
                val path = "$dir/$n"
                if (files.isDir(path)) walk(path)
                else if (n.endsWith(".yaml")) load({ files.open(path).bufferedReader().use { it.readText() } }, path)?.let { out += it }
            }
        }
        walk("macros")
        return out
    }

    fun get(context: android.content.Context, name: String) =
        all(context).firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown macro '$name'")

    private fun load(read: () -> String, source: String, custom: Boolean = false): Scenario? = try {
        val s = FlowScenario.parse(read(), source, custom)
        if (s.unsupported.isNotEmpty()) {
            MacroLog.i("macro ${s.name} needs a newer app (unknown steps: ${s.unsupported.joinToString()})")
            null
        } else s
    } catch (e: Exception) {
        MacroLog.e("could not load macro $source: ${e.message}")
        null
    }
}

/** The run loop: screenshot, work out the screen, call its handler, repeat. Same design as the Python runner. */
object Runner {
    /** How long a macro run inside another ignores the caller's screen still showing (see [runInside]). */
    const val UNTIL_GRACE_MS = 12_000L
    private val stopRequested = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    /** A macro is running (one at a time: it drives the screen). */
    val isRunning get() = running.get()
    val isPaused get() = paused.get()
    var current: String? = null
        private set

    /** Notified (on any thread) whenever running/paused changes. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    internal fun changed() = listeners.forEach { runCatching { it() } }

    fun requestStop() { stopRequested.set(true); paused.set(false); changed() }

    fun setPaused(p: Boolean) {
        if (!isRunning) return
        paused.set(p)
        MacroLog.i(if (p) "paused" else "resumed")
        changed()
    }

    /** Block while paused; throw if a stop comes in meanwhile. */
    internal fun waitWhilePaused(stop: AtomicBoolean) {
        while (paused.get()) {
            if (stop.get()) throw StopScenario("stop requested")
            Thread.sleep(150)
        }
    }

    private var lastAnrCheck = 0L

    /**
     * Android's "<app> isn't responding" dialog (the game froze): close the app so the
     * macro can relaunch it. Checked only while Android's own window is in front.
     */
    private fun closeNotRespondingDialog(ctx: Context): Boolean {
        val fg = ctx.foreground ?: return false
        if (fg != "android" && fg != "com.android.systemui") return false
        val now = System.currentTimeMillis()
        if (now - lastAnrCheck < 3000) return false
        lastAnrCheck = now
        val lines = ctx.readText()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if ("not responding" !in all && "isn t responding" !in all && "isnt responding" !in all) return false
        val button = listOf("close app", "stop now", "close").firstNotNullOfOrNull { want ->
            lines.firstOrNull { Ocr.norm(it.text) == want }
        } ?: return false.also { MacroLog.i("app-not-responding dialog seen but no close button read: $all") }
        MacroLog.i("app-not-responding dialog: tapping '${button.text}'")
        ctx.count("not_responding_closed")
        ctx.tapExact(button.center)
        ctx.wait(3.0)
        return true
    }

    /** The params a run uses: the macro's defaults, then [given] (only names it has). */
    fun resolve(scenario: Scenario, given: Map<String, String>) =
        scenario.params.mapValues { it.value.default } + given.filterKeys { it in scenario.params }

    private fun library(scenario: Scenario, files: LibraryFiles, userTemplates: java.io.File?): Library {
        val library = Library.load(files, "templates/common", *scenario.templateFolders.map { "templates/$it" }.toTypedArray())
        userTemplates?.let { library.addDir(it) }
        (scenario as? FlowScenario)?.let { library.addScreens(it.screenDefs) }
        return library
    }

    /**
     * Run [scenario] until it stops; the reason. [given] are its params (missing ones: defaults).
     * Templates and data come from the macro library ([MacroLibrary]).
     */
    fun run(scenario: Scenario, given: Map<String, String>, capture: Capture, app: android.content.Context): String {
        if (!running.compareAndSet(false, true)) return "a macro is already running"
        stopRequested.set(false)
        paused.set(false)
        current = scenario.name
        changed()
        val files = MacroLibrary.files(app)
        val userTemplates = Scenarios.userTemplates(app)
        val params = resolve(scenario, given)
        val ctx = Context(capture, files, Vision(library(scenario, files, userTemplates)), params, stopRequested)
        ctx.debugDir = java.io.File(app.filesDir, "debug").apply { mkdirs() }
        ctx.userTemplates = userTemplates
        ctx.findMacro = { n -> runCatching { Scenarios.get(app, n) }.getOrNull() }
        ctx.optionsOf = { n -> ctx.findMacro(n)?.let { resolve(it, MacroParams.load(app, n)) } ?: emptyMap() }
        var reason = "finished"
        InputService.instance?.keepScreenOn(true)
        MacroLog.i("running ${scenario.name} with $params")
        MacroLog.status("${scenario.name}: starting")
        try {
            loop(scenario, ctx)
        } catch (s: StopScenario) {
            reason = s.message ?: "stopped"
        } catch (t: Throwable) {
            reason = "error: ${t.javaClass.simpleName}: ${t.message}"
            MacroLog.e("macro crashed", t)
        } finally {
            InputService.instance?.keepScreenOn(false)
            ctx.frame?.gray?.release()
            running.set(false)
            paused.set(false)
            changed()
        }
        MacroLog.i("stopped: $reason — ${scenario.summary(ctx.stats)}")
        return reason
    }

    /**
     * Run [sub] inside the running macro (Flow's `run:` step) and return its stop reason, for the
     * caller to go on. It starts from its own options as the user chose them in the app (so a
     * basic macro's settings, e.g. the battle macro's skills, apply wherever it is run), then [given]. It ends like any macro (its own stop), or as soon as one of [until] (the
     * caller's screens) shows: "back to <screen>". A stop request or a safety stop ends the caller
     * too. Its counters are added to the caller's.
     */
    fun runInside(sub: Scenario, given: Map<String, String>, parent: Context, until: List<String>): String {
        if (parent.depth >= 4) throw IllegalStateException("macros run inside each other too deep (at ${sub.name})")
        val lib = library(sub, parent.assets, parent.userTemplates)
        // the caller's templates and screens too: [until] is made of them
        lib.addMissing(parent.vision.library)
        val params = resolve(sub, parent.optionsOf(sub.name) + given)
        val ctx = parent.child(Vision(lib), params)
        MacroLog.i("running ${sub.name} inside it with $params")
        val reason = try {
            loop(sub, ctx, until)
            "finished"
        } catch (s: StopScenario) {
            s.message ?: "stopped"
        } finally {
            ctx.frame?.gray?.release()
            for ((k, v) in ctx.stats) parent.count(k, v)
        }
        MacroLog.i("${sub.name} ended: $reason")
        if (reason == "stop requested" || reason.startsWith("safety stop")) throw StopScenario(reason)
        return reason
    }

    /** The run loop proper: setup, then screenshot → screen → handler until a stop. */
    private fun loop(scenario: Scenario, ctx: Context, until: List<String> = emptyList()) {
        val library = ctx.vision.library
        val missing = scenario.screens.flatMap { library.screens[it]?.require ?: emptyList() }.filter { it in library.missing() }
        if (missing.isNotEmpty()) MacroLog.i("templates not captured yet: ${missing.distinct().joinToString()}")
        scenario.setup(ctx)
        var unknownSince: Long? = null
        var last: String? = "-"
        // [until] counts once the caller's screen was gone at least once (what was started took
        // over), or after a grace time (it never did): not while the caller's screen still lingers
        val started = System.currentTimeMillis()
        var away = false
        while (true) {
            if (stopRequested.get()) throw StopScenario("stop requested")
            waitWhilePaused(stopRequested)
            val frame = ctx.refresh()
            ctx.screen = ctx.vision.identify(frame, scenario.screens)
            if (ctx.screen == null && until.isNotEmpty()) {
                val back = ctx.vision.identify(frame, until)
                if (back == null) away = true
                else if (away || System.currentTimeMillis() - started > UNTIL_GRACE_MS) ctx.stop("back to $back")
                else { Thread.sleep((scenario.tickS * 1000).toLong()); continue }
            } else away = true
            if (ctx.screen != last) {
                MacroLog.i("screen: ${ctx.screen ?: "unknown"}")
                last = ctx.screen
            }
            val screen = ctx.screen
            if (screen == null) {
                val now = System.currentTimeMillis()
                unknownSince = unknownSince ?: now
                if (now - unknownSince > scenario.unknownTimeoutS * 1000) {
                    ctx.stop("stuck on an unrecognised screen for ${scenario.unknownTimeoutS.toInt()}s")
                }
                if (!closeNotRespondingDialog(ctx)) scenario.onUnknown(ctx)
            } else {
                unknownSince = null
                scenario.onScreen(ctx, screen)
            }
            Thread.sleep((scenario.tickS * 1000).toLong())
        }
    }
}
