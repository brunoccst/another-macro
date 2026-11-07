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
import java.util.concurrent.atomic.AtomicBoolean

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
        val tools = PhoneTools(app, capture, stop, shotMax = SHOT_MAX)

        fun loop(): String {
            MacroLog.status("Claude: starting")
            MacroLog.i("Claude task (${model.label}): $task")
            val example = runCatching { app.assets.open("macros/grind-battles.yaml").bufferedReader().use { it.readText() } }.getOrDefault("")
            val params = ClaudeRequest.builder(model.id, ClaudePrompt.system(tools.shotW, tools.shotH, example), ClaudeSettings.effort(app))
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
                            if (isComputer) tools.computer(use.name(), input) else appTool(use.name(), input)
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

        private fun appTool(name: String, input: Map<String, Any?>): ToolResult = when (name) {
            "walk" -> tools.walk(input)
            "read_text" -> tools.readText(input)
            "find_template" -> tools.findTemplate(input)
            "list_templates" -> tools.listTemplates()
            "list_macros" -> tools.listMacros()
            "run_macro" -> tools.runMacro(input)
            "save_template" -> tools.saveTemplate(input)
            "save_macro" -> tools.saveMacro(input["yaml"]?.toString().orEmpty())
            "ask_user" -> tools.askUser(input)
            "launch_game" -> tools.launchGame()
            else -> ToolResult("unknown tool $name", isError = true)
        }
    }
}
