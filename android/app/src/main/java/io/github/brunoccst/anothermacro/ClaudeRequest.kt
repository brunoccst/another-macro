package io.github.brunoccst.anothermacro

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaClearToolUses20250919Edit
import com.anthropic.models.beta.messages.BetaComputerCursorPositionConfig
import com.anthropic.models.beta.messages.BetaComputerHoldKeyConfig
import com.anthropic.models.beta.messages.BetaComputerLeftMouseDownConfig
import com.anthropic.models.beta.messages.BetaComputerLeftMouseUpConfig
import com.anthropic.models.beta.messages.BetaComputerMiddleClickConfig
import com.anthropic.models.beta.messages.BetaComputerMouseMoveConfig
import com.anthropic.models.beta.messages.BetaComputerRightClickConfig
import com.anthropic.models.beta.messages.BetaComputerToolset20260801
import com.anthropic.models.beta.messages.BetaComputerToolsetConfigs
import com.anthropic.models.beta.messages.BetaComputerTripleClickConfig
import com.anthropic.models.beta.messages.BetaComputerTypeConfig
import com.anthropic.models.beta.messages.BetaContextManagementConfig
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams

/** What a tool did, for its tool_result: text, or a JPEG screenshot (base64). */
class ToolResult(val text: String, val imageJpegB64: String? = null, val isError: Boolean = false)

/**
 * The Messages API request Claude runs with: the computer use toolset, the app's own
 * tools and the request settings. Plain SDK builders with no Android code, so the
 * request shape is checked by a JVM unit test (ClaudeRequestTest).
 */
object ClaudeRequest {
    val BETAS = listOf(
        "context-management-2025-06-27",       // clear old screenshots server-side (keeps the cache and thinking valid)
        "thinking-display-updates-2026-08-18", // Claude's short progress notes between tool calls, shown on the live log
        "server-side-fallback-2026-07-01",     // a refused request is retried on a fallback model
    )

    /** A new request with everything but the messages. The tool list never changes during a task (keeps the cache). */
    fun builder(model: String, system: String, effort: String): MessageCreateParams.Builder {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000)
            .system(system)
            .thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.UPDATES).build())
            .outputConfig(BetaOutputConfig.builder().effort(when (effort) {
                "low" -> BetaOutputConfig.Effort.LOW
                "high" -> BetaOutputConfig.Effort.HIGH
                else -> BetaOutputConfig.Effort.MEDIUM
            }).build())
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .contextManagement(
                BetaContextManagementConfig.builder().addEdit(
                    BetaClearToolUses20250919Edit.builder()
                        .inputTokensTrigger(60_000)
                        .toolUsesKeep(6)
                        .build()
                ).build()
            )
            .fallbacksDefault()
            .addTool(computerToolset())
        BETAS.forEach { params.addBeta(it) }
        appTools().forEach { params.addTool(it) }
        return params
    }

    fun toolResult(id: String, toolset: String?, r: ToolResult): BetaToolResultBlockParam {
        val b = BetaToolResultBlockParam.builder().toolUseId(id)
        if (toolset != null) b.toolsetName(toolset)
        if (r.isError) b.isError(true)
        if (r.imageJpegB64 != null) {
            b.contentOfBlocks(listOf(
                BetaToolResultBlockParam.Content.Block.ofImage(
                    BetaImageBlockParam.builder().source(
                        BetaBase64ImageSource.builder().mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).data(r.imageJpegB64).build()
                    ).build()
                )
            ))
        } else {
            b.content(r.text)
        }
        return b.build()
    }

    fun computerToolset(): BetaComputerToolset20260801 = BetaComputerToolset20260801.builder()
        .configs(
            // the game has no keyboard or mouse: only screenshot, zoom, clicks, drag, scroll, key (Back) and wait stay on
            BetaComputerToolsetConfigs.builder()
                .type(BetaComputerTypeConfig.builder().enabled(false).build())
                .holdKey(BetaComputerHoldKeyConfig.builder().enabled(false).build())
                .rightClick(BetaComputerRightClickConfig.builder().enabled(false).build())
                .middleClick(BetaComputerMiddleClickConfig.builder().enabled(false).build())
                .tripleClick(BetaComputerTripleClickConfig.builder().enabled(false).build())
                .mouseMove(BetaComputerMouseMoveConfig.builder().enabled(false).build())
                .leftMouseDown(BetaComputerLeftMouseDownConfig.builder().enabled(false).build())
                .leftMouseUp(BetaComputerLeftMouseUpConfig.builder().enabled(false).build())
                .cursorPosition(BetaComputerCursorPositionConfig.builder().enabled(false).build())
                .build()
        )
        .build()

    fun appTools(): List<BetaTool> = listOf(
        tool("walk", "Move the party on a field map: hold the virtual stick in one direction for ms milliseconds (left/right to walk, up/down for doors and stairs).",
            mapOf("direction" to enumProp("left", "right", "up", "down"), "ms" to intProp("how long to hold, 200-6000")), listOf("direction")),
        tool("read_text", "On-device OCR: every text line on screen (or inside region) with its centre in screenshot pixels. Cheaper and more exact than a screenshot for labels and numbers.",
            mapOf("region" to boxProp()), emptyList()),
        tool("find_template", "Look for a known UI element (template) on the current screen; returns its centre in screenshot pixels and the match score, or not found.",
            mapOf("name" to strProp("template name, see list_templates")), listOf("name")),
        tool("list_templates", "Names of the UI templates this app knows (built-in and the user's own).", emptyMap(), emptyList()),
        tool("list_macros", "The macros installed in the app, with their descriptions and params.", emptyMap(), emptyList()),
        tool("run_macro", "Run an installed macro to its end (it runs on the phone without you, for free) and return why it stopped and its counters.",
            mapOf("name" to strProp("macro name"), "params" to mapOf("type" to "object", "description" to "param values, e.g. {\"battles\": 5}", "additionalProperties" to mapOf("type" to "string"))), listOf("name")),
        tool("save_template", "Save a small box of the current screen as a named template (a stable label or icon, no numbers or moving parts), usable in macros by name.",
            mapOf("name" to strProp("lowercase letters, digits and _, e.g. quest_board_title"), "region" to boxProp(), "notes" to strProp("what it is")), listOf("name", "region")),
        tool("save_macro", "Save a macro (YAML in the app's macro format) to the user's macros, replacing their own macro with the same name. Built-in macros can't be replaced.",
            mapOf("yaml" to strProp("the whole macro file")), listOf("yaml")),
        tool("ask_user", "Ask the user a question on screen and wait for their answer (up to 10 minutes). Use before anything that can't be undone.",
            mapOf("question" to strProp("short question"), "options" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "2-4 short answers")), listOf("question", "options")),
        tool("launch_game", "Open Another Eden (or bring it to the front).", emptyMap(), emptyList()),
    )

    fun tool(name: String, description: String, props: Map<String, Any>, required: List<String>): BetaTool {
        val properties = BetaTool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> properties.putAdditionalProperty(k, JsonValue.from(v)) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(BetaTool.InputSchema.builder().properties(properties.build()).required(required).build())
            .build()
    }

    fun strProp(d: String) = mapOf("type" to "string", "description" to d)
    fun intProp(d: String) = mapOf("type" to "integer", "description" to d)
    fun enumProp(vararg v: String) = mapOf("type" to "string", "enum" to v.toList())
    fun boxProp() = mapOf("type" to "array", "items" to mapOf("type" to "integer"), "minItems" to 4, "maxItems" to 4,
        "description" to "[x0, y0, x1, y1] in screenshot pixels")
}
