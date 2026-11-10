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

/** A tool's answer: text, or a JPEG screenshot (base64), and whether it failed. */
class ToolResult(val text: String, val imageJpegB64: String? = null, val isError: Boolean = false)

/**
 * What Claude can do on the phone through the phone connector (PhoneConnector, the user's
 * Claude chat through the relay). Every tap and swipe goes through ClaudeSafety and
 * Context.tap's purchase guard.
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
    val library = Library.load(MacroLibrary.files(app), *templateFolders(app)).apply { addDir(Scenarios.userTemplates(app)) }
    val ctx = Context(capture, MacroLibrary.files(app), Vision(library), emptyMap(), stop)
    val shotW = minOf(shotMax, capture.width)
    val shotH = (capture.height * shotW.toDouble() / capture.width).roundToInt()
    private var refusals = 0

    fun back(): ToolResult { ctx.back(); Thread.sleep(600); return ToolResult("OK (Android Back)") }
    fun wait(seconds: Double?): ToolResult { ctx.wait((seconds ?: 1.0).coerceIn(0.1, 60.0)); return ToolResult("OK") }

    /**
     * Screen (or the [region] of it, in screenshot pixels) as a JPEG, longest side at most shotMax.
     * An all-black frame turns the screen on and is taken again; if it stays black the answer
     * says why, next to the image, so Claude doesn't take it for a game screen.
     */
    fun screenshot(region: IntArray?): ToolResult {
        Thread.sleep(250) // the result of the last input
        var bgr = LiveLog.without { capture.grabColor() } ?: throw StopScenario("no screen capture (allow screen capture first)")
        var note = ""
        if (Frame.isBlack(bgr)) {
            if (ScreenWake.wakeNow()) {
                bgr.release()
                capture.grab(1_000)?.gray?.release() // a fresh frame after waking
                bgr = LiveLog.without { capture.grabColor() } ?: throw StopScenario("no screen capture (allow screen capture first)")
                note = "The screen was off; it was turned on for this screenshot. "
            }
            if (Frame.isBlack(bgr)) note += "WARNING: this frame is ALL BLACK, it is not a game screen: " +
                ScreenWake.blackReason(ScreenWake.isOn(), capture.frameAgeMs()) + ". Don't tap blind: call wake_screen and look again."
        }
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
        return ToolResult(note, b64)
    }

    /** Turn the screen on if it is off (the connector also does it before each tool) and say how it is. */
    fun wakeScreen(): ToolResult {
        val wasOff = ScreenWake.wakeNow()
        capture.grab(1_000)?.let { f ->
            val black = f.allBlack
            f.gray.release()
            val state = screenState(black)
            val head = if (wasOff) "the screen was off; " + (if (ScreenWake.isOn() == true) "it is on now" else "it could not be turned on") else "the screen was already on"
            return ToolResult("$head. $state" + if (black) ". " + ScreenWake.blackReason(ScreenWake.isOn(), capture.frameAgeMs()) else "", isError = black)
        }
        return ToolResult("no screen capture: " + ScreenWake.blackReason(ScreenWake.isOn(), null), isError = true)
    }

    /** One line on the screen and the capture (black = the last frame was all black, null = not checked). */
    fun screenState(black: Boolean?): String = ScreenWake.describe(ScreenWake.isOn(), capture.frameAgeMs(), black)

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
        if (ctx.refresh().allBlack) return refused("the screen capture is all black: call wake_screen, then screenshot")
        if (!ClaudeSafety.gameInFront(ctx)) return refused("Another Eden is not in front; use launch_game first")
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

    /** Start a macro in the background and return at once (the connector: tool calls time out after 240 s). */
    fun startMacro(input: Map<String, Any?>): ToolResult {
        if (Runner.isRunning) return ToolResult("a macro is already running (${Runner.current}); see macro_status or stop_macro", isError = true)
        val (s, given) = macro(input)
        Background.result = null
        Background.name = s.name
        MacroLog.i("Claude started macro ${s.name}")
        Background.thread = Thread({
            val reason = Runner.run(s, MacroParams.load(app, s.name) + given, capture, app)
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
        // a black frame is the usual reason for "the game isn't in front" loops: say it plainly
        val black = capture.grab(0)?.let { f -> f.allBlack.also { f.gray.release() } }
        val warn = if (black == true || ScreenWake.isOn() == false) "\nWARNING: " + ScreenWake.blackReason(ScreenWake.isOn(), capture.frameAgeMs()) + " (wake_screen turns it on)" else ""
        return ToolResult("$head\n${screenState(black)}$warn\n\nlast log lines:\n$log")
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
        if (macro.unsupported.isNotEmpty()) return ToolResult("this app doesn't know these steps: ${macro.unsupported.joinToString()}", isError = true)
        val inLibrary = Scenarios.parseAll(MacroLibrary.files(app)).any { it.name == macro.name }
        if (inLibrary) return ToolResult("'${macro.name}' is a macro of the library; pick another name", isError = true)
        java.io.File(Scenarios.userDir(app), "${macro.name}.yaml").writeText(yaml)
        MacroLog.i("Claude saved custom macro '${macro.name}'")
        val publish = if (macro.declaredGroup in setOf("custom", "other")) "" else
            " It is a draft: its group '${macro.declaredGroup}' is a library category, so once it works publish it to the GitHub repo " +
                "(macros/${macro.declaredGroup}/${macro.name}.yaml, built from the basic macros) and delete this copy with delete_macro."
        return ToolResult("saved custom macro '${macro.name}' (listed under Custom; screens: ${macro.screens.joinToString()}; params: ${macro.params.keys.joinToString()}).$publish")
    }

    /** Remove a custom macro (e.g. once it is published to the library). */
    fun deleteMacro(input: Map<String, Any?>): ToolResult {
        val name = input["name"]?.toString().orEmpty()
        val f = Scenarios.userDir(app).listFiles().orEmpty().firstOrNull { f ->
            f.name.endsWith(".yaml") && runCatching { FlowScenario.parse(f.readText(), f.name).name }.getOrNull() == name
        } ?: return ToolResult("no custom macro named '$name' (library macros are changed in the repo)", isError = true)
        f.delete()
        MacroLog.i("Claude deleted custom macro '$name'")
        return ToolResult("deleted custom macro '$name'")
    }

    fun readMacro(input: Map<String, Any?>): ToolResult {
        val name = input["name"]?.toString().orEmpty()
        val user = java.io.File(Scenarios.userDir(app), "$name.yaml")
        if (user.isFile) return ToolResult(user.readText())
        val s = Scenarios.parseAll(MacroLibrary.files(app)).firstOrNull { it.name == name } as? FlowScenario
            ?: return ToolResult("no macro named '$name' (see list_macros)", isError = true)
        return ToolResult(MacroLibrary.files(app).open(s.source).bufferedReader().use { it.readText() })
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
            (listOf("templates/common") + MacroLibrary.files(app).list("templates").filter { it != "common" }.map { "templates/$it" }).toTypedArray()
    }
}
