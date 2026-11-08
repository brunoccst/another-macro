package io.github.brunoccst.anothermacro

import android.content.res.AssetManager
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
    private val assetManager: AssetManager,
    val vision: Vision,
    val params: Map<String, String>,
    private val stopRequested: AtomicBoolean,
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

    val assets get() = assetManager

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
 * Macros are .yaml files: the built-in ones shipped in the APK (assets/macros, from the
 * repo's macros/ folder) and the user's own in the app's storage (files/macros), which
 * win when a name is used twice.
 */
object Scenarios {
    fun userDir(context: android.content.Context) = java.io.File(context.filesDir, "macros").apply { mkdirs() }
    fun userTemplates(context: android.content.Context) = java.io.File(context.filesDir, "templates/user").apply { mkdirs() }

    fun all(context: android.content.Context): List<Scenario> {
        val found = LinkedHashMap<String, Scenario>()
        val assets = context.assets
        for (f in assets.list("macros").orEmpty().filter { it.endsWith(".yaml") }.sorted()) {
            load({ assets.open("macros/$f").bufferedReader().use { it.readText() } }, "macros/$f")?.let { found[it.name] = it }
        }
        for (f in userDir(context).listFiles().orEmpty().filter { it.name.endsWith(".yaml") }.sortedBy { it.name }) {
            load({ f.readText() }, f.name)?.let { found[it.name] = it }
        }
        return found.values.toList()
    }

    fun get(context: android.content.Context, name: String) =
        all(context).firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown macro '$name'")

    private fun load(read: () -> String, source: String): Scenario? = try {
        FlowScenario.parse(read(), source)
    } catch (e: Exception) {
        MacroLog.e("could not load macro $source: ${e.message}")
        null
    }
}

/** The run loop: screenshot, work out the screen, call its handler, repeat. Same design as the Python runner. */
object Runner {
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

    fun run(scenario: Scenario, given: Map<String, String>, capture: Capture, assets: AssetManager, userTemplates: java.io.File? = null): String {
        if (!running.compareAndSet(false, true)) return "a macro is already running"
        stopRequested.set(false)
        paused.set(false)
        current = scenario.name
        changed()
        val params = scenario.params.mapValues { it.value.default } + given.filterKeys { it in scenario.params }
        val library = Library.load(assets, "templates/common", *scenario.templateFolders.map { "templates/$it" }.toTypedArray())
        userTemplates?.let { library.addDir(it) }
        (scenario as? FlowScenario)?.let { library.addScreens(it.screenDefs) }
        val ctx = Context(capture, assets, Vision(library), params, stopRequested)
        userTemplates?.parentFile?.parentFile?.let { ctx.debugDir = java.io.File(it, "debug").apply { mkdirs() } }
        var reason = "finished"
        InputService.instance?.keepScreenOn(true)
        MacroLog.i("running ${scenario.name} with $params")
        MacroLog.status("${scenario.name}: starting")
        try {
            val missing = scenario.screens.flatMap { library.screens[it]?.require ?: emptyList() }.filter { it in library.missing() }
            if (missing.isNotEmpty()) MacroLog.i("templates not captured yet: ${missing.distinct().joinToString()}")
            scenario.setup(ctx)
            var unknownSince: Long? = null
            var last: String? = "-"
            while (true) {
                if (stopRequested.get()) throw StopScenario("stop requested")
                waitWhilePaused(stopRequested)
                val frame = ctx.refresh()
                ctx.screen = ctx.vision.identify(frame, scenario.screens)
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
}
