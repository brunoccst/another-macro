package io.github.brunoccst.anothermacro

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
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
 * Claude plays the game for the user: a task in, an agent loop on the phone.
 *
 * Each step sends the conversation to the Messages API (the user's own key, their model
 * and effort), runs the tools Claude asks for and sends the results back, until Claude
 * is done, asks the user something, or a step/cost cap or Stop ends it. Taps go through
 * ClaudeSafety and Context.tap's purchase guard before they reach the screen.
 *
 * Tools: the computer use toolset (screenshot, zoom, click, drag, scroll, key=Back, wait;
 * the keyboard/mouse-only members are off) plus app tools for the game (walk, read_text,
 * find_template, macros, templates, ask_user, launch_game).
 */
object ClaudeAgent {
    private val stop = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    val isRunning get() = running.get()

    /** What the last task ended with (shown in the app). */
    @Volatile var lastResult: String = ""
        private set
    @Volatile var costUsd: Double = 0.0
        private set
    @Volatile var steps: Int = 0
        private set

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { listeners.forEach { runCatching { it() } }; Runner.changed() }

    fun requestStop() { stop.set(true); Runner.requestStop() }

    /** Longest screenshot side sent to Claude (a 3200x1440 screen becomes 1280x576, about 1,000 image tokens). */
    private const val SHOT_MAX = 1280
    private const val MAX_REFUSALS = 2

    /** Run [task] to the end on the calling thread. Returns how it ended. */
    fun run(app: android.content.Context, task: String, capture: Capture): String {
        if (!running.compareAndSet(false, true)) return "Claude is already running"
        stop.set(false)
        costUsd = 0.0
        steps = 0
        lastResult = "running"
        changed()
        InputService.instance?.keepScreenOn(true)
        val result = try {
            Session(app, task, capture).loop()
        } catch (s: StopScenario) {
            s.message ?: "stopped"
        } catch (e: UnauthorizedException) {
            "the API key was refused (401): check it in the Claude screen"
        } catch (e: PermissionDeniedException) {
            "the API key has no access to this model (403)"
        } catch (e: RateLimitException) {
            "rate limited by the API (429); try again in a minute"
        } catch (e: AnthropicServiceException) {
            "API error ${e.statusCode()}: ${e.message}"
        } catch (e: AnthropicIoException) {
            "network error: ${e.message}"
        } catch (t: Throwable) {
            MacroLog.e("Claude crashed", t)
            "error: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            InputService.instance?.keepScreenOn(false)
            running.set(false)
        }
        lastResult = result
        MacroLog.i("Claude: $result (${steps} steps, ${"$%.2f".format(costUsd)})")
        MacroLog.status("Claude: done")
        changed()
        return result
    }

    private class Session(val app: android.content.Context, val task: String, val capture: Capture) {
        val model = ClaudeSettings.model(app)
        val maxSteps = ClaudeSettings.maxSteps(app)
        val maxCost = ClaudeSettings.maxCostUsd(app)
        val client: AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(ClaudeSettings.apiKey(app) ?: throw StopScenario("no Claude API key: add yours in the Claude screen"))
            .build()
        val library = Library.load(app.assets, *templateFolders(app)).apply { addDir(Scenarios.userTemplates(app)) }
        val ctx = Context(capture, app.assets, Vision(library), emptyMap(), stop)
        val shotW = minOf(SHOT_MAX, capture.width)
        val shotH = (capture.height * shotW.toDouble() / capture.width).roundToInt()
        var refusals = 0

        fun loop(): String {
            MacroLog.status("Claude: starting")
            MacroLog.i("Claude task (${model.label}): $task")
            val example = runCatching { app.assets.open("macros/grind-battles.yaml").bufferedReader().use { it.readText() } }.getOrDefault("")
            val params = ClaudeRequest.builder(model.id, ClaudePrompt.system(shotW, shotH, example), ClaudeSettings.effort(app))
            params.addUserMessage(task)

            while (true) {
                if (stop.get()) return "stopped by the user"
                if (steps >= maxSteps) return "step cap reached ($maxSteps steps); raise it in the Claude screen if needed"
                if (costUsd >= maxCost) return "cost cap reached (${"$%.2f".format(maxCost)})"
                Runner.waitWhilePaused(stop)
                steps++
                val response = client.beta().messages().create(params.build())
                addCost(response)
                changed()
                params.addMessage(response)

                for (block in response.content()) {
                    block.thinking().map { it.thinking() }.filter { it.isNotBlank() }.ifPresent { MacroLog.status("Claude: ${it.trim().take(160)}") }
                    block.text().map { it.text() }.filter { it.isNotBlank() }.ifPresent { MacroLog.status("Claude: ${it.trim().take(160)}") }
                }
                val reason = response.stopReason().orElse(null)
                if (reason == BetaStopReason.REFUSAL) {
                    val why = response.stopDetails().map { it.toString() }.orElse("")
                    return "Claude declined this request $why".trim()
                }
                val uses = response.content().mapNotNull { it.toolUse().orElse(null) }
                if (uses.isEmpty()) {
                    val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
                    return if (reason == BetaStopReason.MAX_TOKENS) "stopped: Claude's answer was too long" else text.ifEmpty { "done" }
                }

                val results = ArrayList<BetaContentBlockParam>()
                var computerFailed = false
                for (use in uses) {
                    val isComputer = use.toolsetName().orElse(null) == "computer"
                    val r = if (isComputer && computerFailed) {
                        ToolResult("Not executed: an earlier computer action in this turn failed.", isError = true)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val input = (use.input(Map::class.java) as? Map<String, Any?>).orEmpty()
                        try {
                            if (stop.get()) throw StopScenario("stopped by the user")
                            Runner.waitWhilePaused(stop)
                            if (isComputer) computer(use.name(), input) else appTool(use.name(), input)
                        } catch (s: StopScenario) {
                            throw s
                        } catch (e: Exception) {
                            ToolResult("${use.name()} failed: ${e.message}", isError = true)
                        }
                    }
                    if (isComputer && r.isError) computerFailed = true
                    results += BetaContentBlockParam.ofToolResult(ClaudeRequest.toolResult(use.id(), if (isComputer) "computer" else null, r))
                }
                params.addUserMessageOfContentBlockParams(results)
            }
        }

        /** Cost from the API's usage numbers and list prices (cache writes at 1.25x input). */
        private fun addCost(m: BetaMessage) {
            val u = m.usage()
            val write = u.cacheCreationInputTokens().orElse(0L)
            val read = u.cacheReadInputTokens().orElse(0L)
            costUsd += (u.inputTokens() * model.input + write * model.input * 1.25 + read * model.cacheRead + u.outputTokens() * model.output) / 1_000_000.0
        }


        // --- the computer use toolset, mapped onto the phone -----------------------------
        private fun computer(name: String, input: Map<String, Any?>): ToolResult = when (name) {
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
                if (k in setOf("escape", "esc", "back", "browserback", "browser_back", "alt+left", "xf86back")) {
                    ctx.back(); Thread.sleep(600); ToolResult("OK (Android Back)")
                } else ToolResult("only Back is available on the phone (key: Escape)", isError = true)
            }
            "wait" -> { ctx.wait((num(input["duration"]) ?: 1.0).coerceIn(0.1, 60.0)); ToolResult("OK") }
            else -> ToolResult("$name is not available on the phone", isError = true)
        }


        /** Screen (or the [region] of it, in screenshot pixels) as a JPEG, longest side at most SHOT_MAX. */
        private fun screenshot(region: IntArray?): String {
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
            val f = minOf(1.0, SHOT_MAX.toDouble() / maxOf(crop.cols(), crop.rows()))
            val out = Mat()
            if (f < 1.0) Imgproc.resize(crop, out, Size(crop.cols() * f, crop.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA) else crop.copyTo(out)
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", out, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80))
            val b64 = android.util.Base64.encodeToString(buf.toArray(), android.util.Base64.NO_WRAP)
            buf.release(); out.release()
            if (crop !== bgr) crop.release()
            bgr.release()
            return b64
        }

        /** A tap Claude asked for, after the safety checks. */
        private fun tap(p: Point, times: Int): ToolResult {
            ctx.refresh()
            when (val v = ClaudeSafety.check(ctx, p)) {
                is ClaudeSafety.Verdict.Refuse -> return refused(v.reason)
                is ClaudeSafety.Verdict.Confirm -> if (!confirm("Claude wants to tap '${v.what}'. Allow it?")) {
                    return ToolResult("the user did not allow tapping '${v.what}'; don't try it again, pick another way or ask_user", isError = true)
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

        private fun swipe(from: Point, to: Point, ms: Long): ToolResult {
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
            return AskOverlay.ask(question, listOf("Allow", "Deny"), stop, timeoutMs = 5 * 60_000L) == 0
        }

        // --- app tools ----------------------------------------------------------------

        private fun appTool(name: String, input: Map<String, Any?>): ToolResult { return when (name) {
            "walk" -> {
                if (!ClaudeSafety.gameInFront(ctx.also { it.refresh() })) return refused("Another Eden is not in front; use launch_game first")
                val ms = (num(input["ms"]) ?: 1500.0).coerceIn(200.0, 6000.0).toLong()
                val (dx, dy) = when (input["direction"]) { "left" -> -0.06 to 0.0; "right" -> 0.06 to 0.0; "up" -> 0.0 to -0.08; "down" -> 0.0 to 0.08; else -> throw IllegalArgumentException("direction must be left, right, up or down") }
                ctx.drag(Point(0.5, 0.55), Point(0.5 + dx, 0.55 + dy), ms)
                ToolResult("OK")
            }
            "read_text" -> {
                val r = box(input["region"])
                val lines = if (r == null) ctx.readText() else ctx.readText(Region(r[0].toDouble() / shotW, r[1].toDouble() / shotH, (r[2] - r[0]).toDouble() / shotW, (r[3] - r[1]).toDouble() / shotH))
                ToolResult(if (lines.isEmpty()) "no text found" else lines.joinToString("\n") { l -> "\"${l.text}\" at (${px(l.center.x, shotW)}, ${px(l.center.y, shotH)})" })
            }
            "find_template" -> {
                val n = input["name"]?.toString().orEmpty()
                if (library.templates[n]?.available != true) ToolResult("unknown template '$n' (see list_templates)", isError = true)
                else {
                    ctx.refresh()
                    val m = ctx.find(n)
                    ToolResult(if (m == null) "'$n' not found on screen" else "'$n' found at (${px(m.center.x, shotW)}, ${px(m.center.y, shotH)}), score ${"%.2f".format(m.score)}")
                }
            }
            "list_templates" -> ToolResult(library.templates.values.filter { it.available }.joinToString(", ") { it.name })
            "list_macros" -> ToolResult(Scenarios.all(app).joinToString("\n") { s ->
                "${s.name}: ${s.description}" + if (s.params.isEmpty()) "" else "\n  params: " + s.params.entries.joinToString("; ") { (k, p) -> "$k=${p.default} (${p.help})" }
            })
            "run_macro" -> runMacro(input)
            "save_template" -> saveTemplate(input)
            "save_macro" -> saveMacro(input["yaml"]?.toString().orEmpty())
            "ask_user" -> {
                @Suppress("UNCHECKED_CAST")
                val options = (input["options"] as? List<Any?>)?.map { it.toString() }?.take(4)?.ifEmpty { null } ?: listOf("Yes", "No")
                MacroLog.status("Claude: waiting for you to answer")
                val i = AskOverlay.ask(input["question"]?.toString().orEmpty(), options, stop)
                ToolResult(if (i == null) "no answer (the user didn't respond)" else "the user answered: ${options[i]}")
            }
            "launch_game" -> { ctx.launchApp(ClaudeSafety.GAME); ctx.wait(4.0); ToolResult("OK (the game may still be loading)") }
            else -> ToolResult("unknown tool $name", isError = true)
        } }

        private fun runMacro(input: Map<String, Any?>): ToolResult {
            val name = input["name"]?.toString().orEmpty()
            val s = runCatching { Scenarios.get(app, name) }.getOrElse { return ToolResult("unknown macro '$name' (see list_macros)", isError = true) }
            if ((s as? FlowScenario)?.steps?.isNotEmpty() == true) return ToolResult("'$name' is a routine; run its steps one by one: ${s.steps.joinToString()}", isError = true)
            @Suppress("UNCHECKED_CAST")
            val given = (input["params"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
            MacroLog.status("Claude: running macro $name")
            val reason = Runner.run(s, given, capture, app.assets, Scenarios.userTemplates(app))
            if (stop.get()) throw StopScenario("stopped by the user")
            return ToolResult("$name stopped: $reason")
        }

        private fun saveTemplate(input: Map<String, Any?>): ToolResult {
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

        private fun saveMacro(yaml: String): ToolResult {
            val macro = try { FlowScenario.parse(yaml, "claude") } catch (e: Exception) { return ToolResult("not a valid macro: ${e.message}", isError = true) }
            val builtIn = app.assets.list("macros").orEmpty().any { f ->
                runCatching { FlowScenario.parse(app.assets.open("macros/$f").bufferedReader().use { it.readText() }, f).name }.getOrNull() == macro.name
            }
            if (builtIn) return ToolResult("'${macro.name}' is a built-in macro; pick another name", isError = true)
            java.io.File(Scenarios.userDir(app), "${macro.name}.yaml").writeText(yaml)
            MacroLog.i("Claude saved macro '${macro.name}'")
            return ToolResult("saved macro '${macro.name}' (screens: ${macro.screens.joinToString()}; params: ${macro.params.keys.joinToString()})")
        }

        // --- helpers ------------------------------------------------------------------
        private fun point(v: Any?): Point {
            val l = v as? List<*> ?: throw IllegalArgumentException("coordinate [x, y] needed")
            val x = (l[0] as Number).toDouble() / shotW
            val y = (l[1] as Number).toDouble() / shotH
            return Point(x.coerceIn(0.0, 0.999), y.coerceIn(0.0, 0.999))
        }

        private fun box(v: Any?): IntArray? {
            val l = v as? List<*> ?: return null
            if (l.size != 4) throw IllegalArgumentException("region must be [x0, y0, x1, y1]")
            val a = l.map { (it as Number).toInt() }
            return intArrayOf(minOf(a[0], a[2]).coerceIn(0, shotW), minOf(a[1], a[3]).coerceIn(0, shotH), maxOf(a[0], a[2]).coerceIn(0, shotW), maxOf(a[1], a[3]).coerceIn(0, shotH))
        }

        private fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull()
        private fun px(f: Double, size: Int) = (f * size).roundToInt()

    }

    private fun templateFolders(app: android.content.Context): Array<String> =
        (listOf("templates/common") + app.assets.list("templates").orEmpty().filter { it != "common" }.map { "templates/$it" }).toTypedArray()
}
