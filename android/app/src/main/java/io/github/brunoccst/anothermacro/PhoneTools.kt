package io.github.brunoccst.anothermacro

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * What Claude can do on the phone, shared by the in-app agent (ClaudeAgent, the user's
 * API key) and the phone connector (PhoneConnector, the user's Claude chat through the
 * relay). Every tap and swipe goes through ClaudeSafety and Context.tap's purchase guard.
 *
 * Coordinates are screenshot pixels: screenshots are at most [shotMax] px on the long side.
 * [confirmTimeoutMs] is how long the Allow/Deny overlay waits before a risky tap is denied.
 */
class PhoneTools(
    val app: android.content.Context,
    val capture: Capture,
    val stop: AtomicBoolean,
    private val shotMax: Int = 1280,
    private val jpegQuality: Int = 80,
    private val confirmTimeoutMs: Long = 5 * 60_000L,
) {
    val library = Library.load(app.assets, *templateFolders(app)).apply { addDir(Scenarios.userTemplates(app)) }
    val ctx = Context(capture, app.assets, Vision(library), emptyMap(), stop)
    val shotW = minOf(shotMax, capture.width)
    val shotH = (capture.height * shotW.toDouble() / capture.width).roundToInt()
    private var refusals = 0

    // --- the computer use toolset, mapped onto the phone ---------------------------------
    fun computer(name: String, input: Map<String, Any?>): ToolResult = when (name) {
        "screenshot" -> ToolResult("screenshot", screenshot(null))
        "zoom" -> ToolResult("zoom", screenshot(box(input["region"]) ?: throw IllegalArgumentException("region [x0, y0, x1, y1] needed")))
        "left_click" -> tap(point(input["coordinate"]), times = 1)
        "double_click" -> tap(point(input["coordinate"]), times = 2)
        "left_click_drag" -> swipe(point(input["start_coordinate"]), point(input["coordinate"]), 450)
        "scroll" -> {
            val from = input["coordinate"]?.let { point(it) } ?: Point(0.5, 0.5)
            val amount = (num(input["scroll_amount"]) ?: 3.0).coerceIn(1.0, 10.0)
            val d = (0.06 * amount).coerceAtMost(0.45)
            // scrolling down moves the content up: the finger slides up
            val to = when (input["scroll_direction"]) {
                "down" -> Point(from.x, from.y - d)
                "up" -> Point(from.x, from.y + d)
                "left" -> Point(from.x + d, from.y)
                "right" -> Point(from.x - d, from.y)
                else -> throw IllegalArgumentException("scroll_direction must be up, down, left or right")
            }
            swipe(from, Point(to.x.coerceIn(0.01, 0.99), to.y.coerceIn(0.01, 0.99)), 400)
        }
        "key" -> {
            val k = input["text"]?.toString()?.lowercase().orEmpty()
            if (k in setOf("escape", "esc", "back", "browserback", "browser_back", "alt+left", "xf86back")) back()
            else ToolResult("only Back is available on the phone (key: Escape)", isError = true)
        }
        "wait" -> wait(num(input["duration"]))
        else -> ToolResult("$name is not available on the phone", isError = true)
    }

    fun back(): ToolResult { ctx.back(); Thread.sleep(600); return ToolResult("OK (Android Back)") }
    fun wait(seconds: Double?): ToolResult { ctx.wait((seconds ?: 1.0).coerceIn(0.1, 60.0)); return ToolResult("OK") }

    /** Screen (or the [region] of it, in screenshot pixels) as a JPEG, longest side at most shotMax. */
    fun screenshot(region: IntArray?): String {
        Thread.sleep(250) // the result of the last input
        val bgr = LiveLog.without { capture.grabColor() } ?: throw StopScenario("no screen capture (allow screen capture first)")
        val scale = capture.width.toDouble() / shotW
        val crop: Mat = if (region == null) bgr else {
            val x0 = (region[0] * scale).roundToInt().coerceIn(0, capture.width - 1)
            val y0 = (region[1] * scale).roundToInt().coerceIn(0, capture.height - 1)
            val x1 = (region[2] * scale).roundToInt().coerceIn(x0 + 1, capture.width)
            val y1 = (region[3] * scale).roundToInt().coerceIn(y0 + 1, capture.height)
            Mat(bgr, Rect(x0, y0, x1 - x0, y1 - y0))
        }
        val f = minOf(1.0, shotMax.toDouble() / maxOf(crop.cols(), crop.rows()))
        val out = Mat()
        if (f < 1.0) Imgproc.resize(crop, out, Size(crop.cols() * f, crop.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA) else crop.copyTo(out)
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", out, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality))
        val b64 = android.util.Base64.encodeToString(buf.toArray(), android.util.Base64.NO_WRAP)
        buf.release(); out.release()
        if (crop !== bgr) crop.release()
        bgr.release()
        return b64
    }

    /** A tap Claude asked for, after the safety checks. */
    fun tap(p: Point, times: Int): ToolResult {
        ctx.refresh()
        when (val v = ClaudeSafety.check(ctx, p)) {
            is ClaudeSafety.Verdict.Refuse -> return refused(v.reason)
            is ClaudeSafety.Verdict.Confirm -> if (!confirm("Claude wants to tap '${v.what}'. Allow it?")) {
                return ToolResult("the user did not allow tapping '${v.what}'; don't try it again, pick another way or ask the user", isError = true)
            }
            ClaudeSafety.Verdict.Ok -> {}
        }
        try {
            repeat(times) { i -> if (i > 0) Thread.sleep(120); ctx.tap(p) }
        } catch (s: StopScenario) {
            if (s.message == "stop requested") throw s
            return refused(s.message ?: "the purchase guard refused the tap")
        }
        return ToolResult("OK")
    }

    fun swipe(from: Point, to: Point, ms: Long): ToolResult {
        ctx.refresh()
        when (val v = ClaudeSafety.check(ctx, from)) {
            is ClaudeSafety.Verdict.Refuse -> return refused(v.reason)
            is ClaudeSafety.Verdict.Confirm -> if (!confirm("Claude wants to swipe on '${v.what}'. Allow it?")) {
                return ToolResult("the user did not allow it", isError = true)
            }
            ClaudeSafety.Verdict.Ok -> {}
        }
        ctx.drag(from, to, ms)
        return ToolResult("OK")
    }

    private fun refused(reason: String): ToolResult {
        refusals++
        MacroLog.i("SAFETY (Claude): refused: $reason")
        if (refusals >= MAX_REFUSALS && reason.contains("purchase")) throw StopScenario("safety stop: Claude tried to tap a purchase screen twice")
        return ToolResult("Refused by the app: $reason", isError = true)
    }

    private fun confirm(question: String): Boolean {
        MacroLog.status("Claude: waiting for you to answer")
        return AskOverlay.ask(question, listOf("Allow", "Deny"), stop, timeoutMs = confirmTimeoutMs) == 0
    }

    // --- app tools -------------------------------------------------------------------------

    fun walk(input: Map<String, Any?>): ToolResult {
        if (!ClaudeSafety.gameInFront(ctx.also { it.refresh() })) return refused("Another Eden is not in front; use launch_game first")
        val ms = (num(input["ms"]) ?: 1500.0).coerceIn(200.0, 6000.0).toLong()
        val (dx, dy) = when (input["direction"]) {
            "left" -> -0.06 to 0.0; "right" -> 0.06 to 0.0; "up" -> 0.0 to -0.08; "down" -> 0.0 to 0.08
            else -> throw IllegalArgumentException("direction must be left, right, up or down")
        }
        ctx.drag(Point(0.5, 0.55), Point(0.5 + dx, 0.55 + dy), ms)
        return ToolResult("OK")
    }

    fun readText(input: Map<String, Any?>): ToolResult {
        val r = box(input["region"])
        val lines = if (r == null) ctx.readText() else ctx.readText(Region(r[0].toDouble() / shotW, r[1].toDouble() / shotH, (r[2] - r[0]).toDouble() / shotW, (r[3] - r[1]).toDouble() / shotH))
        return ToolResult(if (lines.isEmpty()) "no text found" else lines.joinToString("\n") { l -> "\"${l.text}\" at (${px(l.center.x, shotW)}, ${px(l.center.y, shotH)})" })
    }

    fun findTemplate(input: Map<String, Any?>): ToolResult {
        val n = input["name"]?.toString().orEmpty()
        if (library.templates[n]?.available != true) return ToolResult("unknown template '$n' (see list_templates)", isError = true)
        ctx.refresh()
        val m = ctx.find(n)
        return ToolResult(if (m == null) "'$n' not found on screen" else "'$n' found at (${px(m.center.x, shotW)}, ${px(m.center.y, shotH)}), score ${"%.2f".format(m.score)}")
    }

    fun listTemplates() = ToolResult(library.templates.values.filter { it.available }.joinToString(", ") { it.name })

    fun listMacros() = ToolResult(Scenarios.all(app).joinToString("\n") { s ->
        "${s.name}: ${s.description}" + if (s.params.isEmpty()) "" else "\n  params: " + s.params.entries.joinToString("; ") { (k, p) -> "$k=${p.default} (${p.help})" }
    })

    fun launchGame(): ToolResult { ctx.launchApp(ClaudeSafety.GAME); ctx.wait(4.0); return ToolResult("OK (the game may still be loading)") }

    private fun macro(input: Map<String, Any?>): Pair<Scenario, Map<String, String>> {
        val name = input["name"]?.toString().orEmpty()
        val s = runCatching { Scenarios.get(app, name) }.getOrElse { throw IllegalArgumentException("unknown macro '$name' (see list_macros)") }
        if ((s as? FlowScenario)?.steps?.isNotEmpty() == true) throw IllegalArgumentException("'$name' is a routine; run its steps one by one: ${s.steps.joinToString()}")
        @Suppress("UNCHECKED_CAST")
        val given = (input["params"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
        return s to given
    }

    /** Run a macro to its end on this thread (the in-app agent). */
    fun runMacro(input: Map<String, Any?>): ToolResult {
        val (s, given) = macro(input)
        MacroLog.status("Claude: running macro ${s.name}")
        val reason = Runner.run(s, given, capture, app.assets, Scenarios.userTemplates(app))
        if (stop.get()) throw StopScenario("stopped by the user")
        return ToolResult("${s.name} stopped: $reason")
    }

    /** Start a macro in the background and return at once (the connector: tool calls time out after 240 s). */
    fun startMacro(input: Map<String, Any?>): ToolResult {
        if (Runner.isRunning) return ToolResult("a macro is already running (${Runner.current}); see macro_status or stop_macro", isError = true)
        val (s, given) = macro(input)
        Background.result = null
        Background.name = s.name
        MacroLog.i("Claude started macro ${s.name}")
        Background.thread = Thread({
            val reason = Runner.run(s, given, capture, app.assets, Scenarios.userTemplates(app))
            Background.result = reason
        }, "macro").apply { start() }
        Thread.sleep(500)
        return ToolResult("started ${s.name}; check it with macro_status (it runs on the phone without you)")
    }

    fun macroStatus(): ToolResult {
        val running = Runner.isRunning
        val log = MacroLog.text().lines().takeLast(15).joinToString("\n")
        val head = when {
            running -> "running: ${Runner.current} — now: ${MacroLog.shown.ifEmpty { "-" }}"
            Background.name != null -> "not running; ${Background.name} stopped: ${Background.result ?: "?"}"
            else -> "no macro running"
        }
        return ToolResult("$head\n\nlast log lines:\n$log")
    }

    fun stopMacro(): ToolResult {
        if (!Runner.isRunning) return ToolResult("no macro running")
        Runner.requestStop()
        Background.thread?.join(15_000)
        return ToolResult("stop requested; ${Background.name ?: "the macro"} stopped: ${Background.result ?: "still stopping"}")
    }

    fun saveTemplate(input: Map<String, Any?>): ToolResult {
        val name = input["name"]?.toString().orEmpty()
        if (!TemplateCapture.validName(name)) return ToolResult("name must be lowercase letters, digits and _ (2-41 characters)", isError = true)
        val r = box(input["region"]) ?: return ToolResult("region [x0, y0, x1, y1] needed", isError = true)
        val scale = capture.width.toDouble() / shotW
        val rect = Rect((r[0] * scale).roundToInt(), (r[1] * scale).roundToInt(), ((r[2] - r[0]) * scale).roundToInt().coerceAtLeast(4), ((r[3] - r[1]) * scale).roundToInt().coerceAtLeast(4))
        val path = LiveLog.without { TemplateCapture.saveScreen(app) } ?: return ToolResult("no screen capture", isError = true)
        TemplateCapture.save(app, path, name, rect, input["notes"]?.toString().orEmpty())
        library.addDir(Scenarios.userTemplates(app))
        val m = TemplateCapture.test(app, path, name)
        return ToolResult("saved template '$name'" + (m?.let { ", it matches this screen (score ${"%.2f".format(it.score)})" } ?: ", but it doesn't match this screen; try a larger box"))
    }

    fun saveMacro(yaml: String): ToolResult {
        val macro = try { FlowScenario.parse(yaml, "claude") } catch (e: Exception) { return ToolResult("not a valid macro: ${e.message}", isError = true) }
        val builtIn = app.assets.list("macros").orEmpty().any { f ->
            runCatching { FlowScenario.parse(app.assets.open("macros/$f").bufferedReader().use { it.readText() }, f).name }.getOrNull() == macro.name
        }
        if (builtIn) return ToolResult("'${macro.name}' is a built-in macro; pick another name", isError = true)
        java.io.File(Scenarios.userDir(app), "${macro.name}.yaml").writeText(yaml)
        MacroLog.i("Claude saved macro '${macro.name}'")
        return ToolResult("saved macro '${macro.name}' (screens: ${macro.screens.joinToString()}; params: ${macro.params.keys.joinToString()})")
    }

    fun readMacro(input: Map<String, Any?>): ToolResult {
        val name = input["name"]?.toString().orEmpty()
        val user = java.io.File(Scenarios.userDir(app), "$name.yaml")
        if (user.isFile) return ToolResult(user.readText())
        for (f in app.assets.list("macros").orEmpty()) {
            val text = app.assets.open("macros/$f").bufferedReader().use { it.readText() }
            if (runCatching { FlowScenario.parse(text, f).name }.getOrNull() == name) return ToolResult(text)
        }
        return ToolResult("no YAML macro named '$name' (built-in Kotlin macros have no YAML)", isError = true)
    }

    fun askUser(input: Map<String, Any?>): ToolResult {
        @Suppress("UNCHECKED_CAST")
        val options = (input["options"] as? List<Any?>)?.map { it.toString() }?.take(4)?.ifEmpty { null } ?: listOf("Yes", "No")
        MacroLog.status("Claude: waiting for you to answer")
        val i = AskOverlay.ask(input["question"]?.toString().orEmpty(), options, stop)
        return ToolResult(if (i == null) "no answer (the user didn't respond)" else "the user answered: ${options[i]}")
    }

    // --- helpers ---------------------------------------------------------------------------
    fun point(v: Any?): Point {
        val l = v as? List<*> ?: throw IllegalArgumentException("coordinate [x, y] needed")
        if (l.size != 2) throw IllegalArgumentException("coordinate must be [x, y]")
        val x = (l[0] as Number).toDouble() / shotW
        val y = (l[1] as Number).toDouble() / shotH
        return Point(x.coerceIn(0.0, 0.999), y.coerceIn(0.0, 0.999))
    }

    fun box(v: Any?): IntArray? {
        val l = v as? List<*> ?: return null
        if (l.size != 4) throw IllegalArgumentException("region must be [x0, y0, x1, y1]")
        val a = l.map { (it as Number).toInt() }
        return intArrayOf(minOf(a[0], a[2]).coerceIn(0, shotW), minOf(a[1], a[3]).coerceIn(0, shotH), maxOf(a[0], a[2]).coerceIn(0, shotW), maxOf(a[1], a[3]).coerceIn(0, shotH))
    }

    fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull()
    private fun px(f: Double, size: Int) = (f * size).roundToInt()

    /** The macro Claude started with start_macro (one at a time, like every macro). */
    private object Background {
        @Volatile var thread: Thread? = null
        @Volatile var name: String? = null
        @Volatile var result: String? = null
    }

    companion object {
        const val MAX_REFUSALS = 2

        fun templateFolders(app: android.content.Context): Array<String> =
            (listOf("templates/common") + app.assets.list("templates").orEmpty().filter { it != "common" }.map { "templates/$it" }).toTypedArray()
    }
}
