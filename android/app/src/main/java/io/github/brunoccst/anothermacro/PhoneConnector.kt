package io.github.brunoccst.anothermacro

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone connector: lets the user's own Claude chat (claude.ai, the Claude apps,
 * Claude Code threads) control the game, with no Termux and no API key.
 *
 * Claude's servers call a remote MCP server, which a phone can't host (no public address).
 * So the phone keeps a WebSocket open to the user's relay (relay/, a Cloudflare Worker on
 * their own account). The relay passes each MCP request from Claude to the phone and
 * the answer back. Requests run one at a time on one thread, through PhoneTools, so the
 * same safety layer applies to every tap.
 *
 * Off by default; turns itself off after [IDLE_OFF_MS] without tool calls.
 */
object PhoneConnector {
    enum class State { OFF, CONNECTING, ONLINE }

    @Volatile var state = State.OFF
        private set
    /** What the connector screen shows under the switch (last error, idle time, ...). */
    @Volatile var detail = ""
        private set
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { listeners.forEach { runCatching { it() } } }

    private const val IDLE_OFF_MS = 30 * 60_000L
    /** Claude's connector tool calls time out after 240 s; answer before that. */
    private const val CONFIRM_TIMEOUT_MS = 2 * 60_000L
    private const val SHOT_MAX = 1024
    private const val JPEG_QUALITY = 70

    private val json = ObjectMapper()
    private val enabled = AtomicBoolean(false)
    private val stop = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "connector") }
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var tools: PhoneTools? = null
    private var server: McpServer? = null
    @Volatile private var lastCall = 0L
    private var retryMs = 2000L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    val isOn get() = enabled.get()

    /** Stop the tool call running now (the floating ■ and the notification's Stop). The connector stays on. */
    fun requestStop() = stop.set(true)

    fun start(app: android.content.Context) {
        val url = ClaudeSettings.relayUrl(app)
        val token = ClaudeSettings.phoneToken(app)
        if (url.isEmpty() || token == null) { set(State.OFF, "set the relay address and make tokens first"); return }
        if (!url.startsWith("https://")) { set(State.OFF, "the relay address must start with https://"); return }
        if (MacroService.capture == null) { set(State.OFF, "allow screen capture first (main screen)"); return }
        if (!enabled.compareAndSet(false, true)) return
        lastCall = System.currentTimeMillis()
        retryMs = 2000L
        MacroLog.i("phone connector on")
        connect(app.applicationContext, url, token)
        scheduleIdleCheck(app.applicationContext)
    }

    fun stopConnector(why: String = "off") {
        if (!enabled.compareAndSet(true, false)) return
        stop.set(true)
        socket?.close(1000, "off")
        socket = null
        MacroLog.i("phone connector off ($why)")
        set(State.OFF, why)
    }

    private fun set(s: State, d: String) { state = s; detail = d; changed() }

    private fun connect(app: android.content.Context, url: String, token: String) {
        if (!enabled.get()) return
        set(State.CONNECTING, "connecting to the relay")
        val req = Request.Builder().url("$url/phone").header("Authorization", "Bearer $token").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryMs = 2000L
                set(State.ONLINE, "online: Claude can use the phone")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                worker.execute { answer(app, webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                if (response?.code == 401 || response?.code == 403) {
                    stopConnector("the relay refused the phone token: put the new tokens in the relay")
                    return
                }
                retry(app, url, token, "relay unreachable (${response?.code ?: t.javaClass.simpleName}); retrying")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                retry(app, url, token, "relay closed the connection; reconnecting")
            }
        })
    }

    private fun retry(app: android.content.Context, url: String, token: String, why: String) {
        if (!enabled.get()) return
        set(State.CONNECTING, why)
        val wait = retryMs
        retryMs = (retryMs * 2).coerceAtMost(60_000L)
        main.postDelayed({ connect(app, url, token) }, wait)
    }

    private fun scheduleIdleCheck(app: android.content.Context) {
        main.postDelayed({
            if (!enabled.get()) return@postDelayed
            if (System.currentTimeMillis() - lastCall > IDLE_OFF_MS && !Runner.isRunning) stopConnector("turned off after 30 minutes without use")
            else scheduleIdleCheck(app)
        }, 60_000L)
    }

    /** One message from the relay: {"id": "...", "body": "<JSON-RPC>"}; the answer goes back with the same id. */
    private fun answer(app: android.content.Context, ws: WebSocket, text: String) {
        val msg = runCatching { json.readTree(text) }.getOrNull() ?: return
        val id = msg.path("id").asText("")
        val body = msg.path("body").asText("")
        val out = try {
            server(app)?.handle(body)
        } catch (t: Throwable) {
            MacroLog.e("connector request failed", t)
            null
        }
        val reply = json.createObjectNode().put("id", id)
        if (out != null) reply.put("body", out) else reply.putNull("body")
        ws.send(json.writeValueAsString(reply))
    }

    /** The MCP server over this capture (made again when the capture changes). */
    private fun server(app: android.content.Context): McpServer? {
        val cap = MacroService.capture
        if (tools?.capture !== cap) { tools = null; server = null }
        server?.let { return it }
        if (cap == null) {
            return McpServer(TOOLS, "The phone's screen capture is off.") { name, args ->
                if (name == "update_app") updateApp(app, args)
                else ToolResult("screen capture is off on the phone: open Another Macro and allow screen capture", isError = true)
            }
        }
        val t = PhoneTools(app, cap, stop, shotMax = SHOT_MAX, jpegQuality = JPEG_QUALITY, confirmTimeoutMs = CONFIRM_TIMEOUT_MS)
        tools = t
        return McpServer(TOOLS, ClaudePrompt.connector(t.shotW, t.shotH)) { name, args -> call(t, name, args) }.also { server = it }
    }

    /** Tools that touch the screen: not while a macro is driving it. */
    private val DRIVES = setOf("tap", "swipe", "back", "walk", "launch_game")

    private fun call(t: PhoneTools, name: String, a: Map<String, Any?>): ToolResult {
        lastCall = System.currentTimeMillis()
        if (Runner.isRunning && name in DRIVES) {
            return ToolResult("a macro (${Runner.current}) is running; use macro_status, or stop_macro first", isError = true)
        }
        stop.set(false)
        if (!Runner.isRunning) MacroLog.show("Claude (connector): $name") // a running macro keeps its own status line
        return try {
            when (name) {
                "screenshot" -> ToolResult("screenshot", t.screenshot(null))
                "zoom" -> ToolResult("zoom", t.screenshot(t.box(a["region"]) ?: throw IllegalArgumentException("region [x0, y0, x1, y1] needed")))
                "tap" -> t.tap(t.point(listOf(a["x"], a["y"])), if (a["double"] == true) 2 else 1)
                "swipe" -> t.swipe(t.point(a["from"]), t.point(a["to"]), (t.num(a["ms"]) ?: 450.0).coerceIn(100.0, 6000.0).toLong())
                "back" -> t.back()
                "wait" -> t.wait(t.num(a["seconds"])?.coerceAtMost(30.0))
                "walk" -> t.walk(a)
                "read_text" -> t.readText(a)
                "find_template" -> t.findTemplate(a)
                "list_templates" -> t.listTemplates()
                "list_macros" -> t.listMacros()
                "read_macro" -> t.readMacro(a)
                "start_macro" -> t.startMacro(a)
                "macro_status" -> t.macroStatus()
                "stop_macro" -> t.stopMacro()
                "save_template" -> t.saveTemplate(a)
                "save_macro" -> t.saveMacro(a["yaml"]?.toString().orEmpty())
                "launch_game" -> t.launchGame()
                "update_app" -> updateApp(t.app, a)
                else -> ToolResult("unknown tool $name", isError = true)
            }
        } catch (s: StopScenario) {
            val why = s.message ?: "stopped"
            when {
                why == "stop requested" || stop.get() -> ToolResult("stopped by the user on the phone", isError = true)
                why.startsWith("safety stop") -> {
                    main.post { stopConnector(why) }
                    ToolResult("$why. The phone connector has turned itself off; the user can turn it on again in the app.", isError = true)
                }
                else -> ToolResult(why, isError = true)
            }
        }
    }

    /**
     * Install the newest CI build of a branch (default: the one the phone follows, "main" at first)
     * with no taps from the user: the app restarts and comes back with capture and the connector on.
     */
    private fun updateApp(app: android.content.Context, a: Map<String, Any?>): ToolResult {
        if (Runner.isRunning) return ToolResult("a macro (${Runner.current}) is running: stop_macro first", isError = true)
        val branch = a["branch"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: Updater.channel(app)
        val newer = try { Updater.checkDev(branch) } catch (e: Exception) { return ToolResult("can't check '$branch': ${e.message}", isError = true) }
            ?: return ToolResult("up to date: already the newest build of '$branch' (${BuildConfig.VERSION_NAME})")
        if (!Updater.canInstall(app)) {
            main.post { Updater.openInstallPermission(app) }
            return ToolResult("the user must allow \"Install unknown apps\" for Another Macro once (the settings page is now open on the phone), then call update_app again", isError = true)
        }
        // the download and install run after this answer: the app is replaced (and restarts) at the end
        Thread {
            val r = runCatching { Updater.update(app, branch) }
            r.onSuccess { MacroLog.i(it) }.onFailure { MacroLog.e("update failed: ${it.message}") }
        }.start()
        return ToolResult("updating to ${newer.version} from '$branch' (this phone has ${BuildConfig.VERSION_NAME}). The app restarts and turns " +
            "screen capture and the connector back on by itself, then opens the game: wait about 90 seconds (the connector is offline meanwhile), " +
            "then call macro_status to check.")
    }

    private fun str(d: String) = mapOf("type" to "string", "description" to d)
    private fun int(d: String) = mapOf("type" to "integer", "description" to d)
    private fun xy(d: String) = mapOf("type" to "array", "items" to mapOf("type" to "integer"), "minItems" to 2, "maxItems" to 2, "description" to d)
    private fun box() = mapOf("type" to "array", "items" to mapOf("type" to "integer"), "minItems" to 4, "maxItems" to 4, "description" to "[x0, y0, x1, y1] in screenshot pixels")

    /** The connector's tools (flat names: Claude chats have no computer use toolset). */
    val TOOLS: List<McpServer.McpTool> = listOf(
        McpServer.McpTool("screenshot", "Take a screenshot of the phone (Another Eden, landscape). Coordinates for the other tools are pixels in this image.", emptyMap()),
        McpServer.McpTool("zoom", "A closer screenshot of one part of the screen, for small text or icons.", mapOf("region" to box()), listOf("region")),
        McpServer.McpTool("tap", "Tap the screen at (x, y) in screenshot pixels. The app refuses taps on purchase screens and asks the user on the phone before risky taps.",
            mapOf("x" to int("x in screenshot pixels"), "y" to int("y in screenshot pixels"), "double" to mapOf("type" to "boolean", "description" to "double tap")), listOf("x", "y")),
        McpServer.McpTool("swipe", "Swipe (drag a finger) from one point to another, in screenshot pixels: scroll lists, pan maps.",
            mapOf("from" to xy("[x, y] start"), "to" to xy("[x, y] end"), "ms" to int("duration, 100-6000, default 450")), listOf("from", "to")),
        McpServer.McpTool("back", "Press Android Back (closes most game dialogs).", emptyMap()),
        McpServer.McpTool("wait", "Wait a few seconds (up to 30) for the game to load or animate.", mapOf("seconds" to mapOf("type" to "number", "description" to "0.1-30")), listOf("seconds")),
        McpServer.McpTool("walk", "Move the party on a field map: hold the virtual stick in one direction for ms milliseconds (left/right to walk, up/down for doors and stairs).",
            mapOf("direction" to mapOf("type" to "string", "enum" to listOf("left", "right", "up", "down")), "ms" to int("how long to hold, 200-6000")), listOf("direction")),
        McpServer.McpTool("read_text", "On-device OCR: every text line on screen (or inside region) with its centre in screenshot pixels. Cheaper and more exact than a screenshot for labels and numbers.",
            mapOf("region" to box())),
        McpServer.McpTool("find_template", "Look for a known UI element (template) on screen; returns its centre in screenshot pixels and the match score.",
            mapOf("name" to str("template name, see list_templates")), listOf("name")),
        McpServer.McpTool("list_templates", "Names of the UI templates the app knows (built-in and the user's own).", emptyMap()),
        McpServer.McpTool("list_macros", "The macros installed in the app, with their descriptions and params.", emptyMap()),
        McpServer.McpTool("read_macro", "The YAML of an installed macro (the user's own or a built-in YAML one; grind-battles is a full example).",
            mapOf("name" to str("macro name")), listOf("name")),
        McpServer.McpTool("start_macro", "Start an installed macro; it runs on the phone without you. Returns at once: follow it with macro_status.",
            mapOf("name" to str("macro name"), "params" to mapOf("type" to "object", "description" to "param values, e.g. {\"battles\": 5}", "additionalProperties" to mapOf("type" to "string"))), listOf("name")),
        McpServer.McpTool("macro_status", "Whether a macro is running, what it is doing now, why the last one stopped, and the last log lines.", emptyMap()),
        McpServer.McpTool("stop_macro", "Stop the running macro.", emptyMap()),
        McpServer.McpTool("save_template", "Save a small box of the current screen as a named template (a stable label or icon, no numbers or moving parts), usable in macros by name.",
            mapOf("name" to str("lowercase letters, digits and _, e.g. quest_board_title"), "region" to box(), "notes" to str("what it is")), listOf("name", "region")),
        McpServer.McpTool("save_macro", "Save a macro (YAML in the app's macro format) to the user's macros, replacing their own macro with that name. Built-in macros can't be replaced.",
            mapOf("yaml" to str("the whole macro file")), listOf("yaml")),
        McpServer.McpTool("launch_game", "Open Another Eden (or bring it to the front).", emptyMap()),
        McpServer.McpTool("update_app", "Update the Another Macro app itself to the newest CI build of a GitHub branch of brunoccst/another-macro, with no taps from the user. The app restarts; the connector is back in about 90 s.",
            mapOf("branch" to str("branch whose build to install, e.g. main (default: the branch the phone follows now)"))),
    )
}
