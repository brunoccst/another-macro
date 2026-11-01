package io.github.brunoccst.anothermacro

import android.content.res.AssetManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class StopScenario(reason: String) : Exception(reason)

/** A user-tunable option, given as text (from the app's form or an intent extra). */
data class Param(val default: String, val help: String)

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
        return try { Ocr.read(bmp) } finally { bmp.recycle() }
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
        val (x, y) = px(point(target))
        input.tap(x, y)
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
    val isRunning get() = running.get()
    val isPaused get() = paused.get()
    var current: String? = null
        private set

    /** Notified (on any thread) whenever running/paused changes. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() = listeners.forEach { runCatching { it() } }

    fun requestStop() { stopRequested.set(true); paused.set(false); changed() }

    fun setPaused(p: Boolean) {
        if (!running.get()) return
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
                    scenario.onUnknown(ctx)
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
