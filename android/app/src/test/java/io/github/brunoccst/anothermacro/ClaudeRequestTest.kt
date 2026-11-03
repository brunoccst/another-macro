package io.github.brunoccst.anothermacro

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request Claude runs with, serialized as the SDK sends it (no network, no device). */
class ClaudeRequestTest {
    private fun body(model: String = "claude-opus-5-5", build: (com.anthropic.models.beta.messages.MessageCreateParams.Builder) -> Unit = {}): JsonNode {
        val b = ClaudeRequest.builder(model, "system text", "medium")
        b.addUserMessage("finish the cat scratchbook")
        build(b)
        val mapper = jsonMapper()
        return mapper.readTree(mapper.writeValueAsString(b.build()._body()))
    }

    @Test fun requestSettings() {
        val j = body()
        assertEquals("claude-opus-5-5", j["model"].asText())
        assertEquals("adaptive", j["thinking"]["type"].asText())
        assertEquals("updates", j["thinking"]["display"].asText())
        assertEquals("medium", j["output_config"]["effort"].asText())
        assertEquals("default", j["fallbacks"].asText())
        assertEquals("ephemeral", j["cache_control"]["type"].asText())
        assertEquals("clear_tool_uses_20250919", j["context_management"]["edits"][0]["type"].asText())
        assertFalse("no forced tool choice (400 on Opus 5.5)", j.has("tool_choice"))
        assertFalse("no sampling params", j.has("temperature"))
    }

    @Test fun haikuHasNoServerSideFallback() {
        val j = body("claude-haiku-5-5")
        assertEquals("claude-haiku-5-5", j["model"].asText())
        assertFalse("Haiku 5.5 has no server-side fallback", j.has("fallbacks"))
        assertTrue(j["tools"].any { it["type"]?.asText() == "computer_toolset_20260801" })
        assertTrue(ClaudeRequest.hasFallback("claude-sonnet-5-5"))
        assertFalse(ClaudeRequest.hasFallback("claude-haiku-5-5"))
    }

    @Test fun computerToolsetHasNoNameAndOnlyPhoneActions() {
        val tools = body()["tools"]
        val toolset = tools.first { it["type"]?.asText() == "computer_toolset_20260801" }
        assertFalse("the toolset entry takes no name", toolset.has("name"))
        assertFalse(toolset.has("display_width_px"))
        val configs = toolset["configs"]
        for (off in listOf("type", "hold_key", "right_click", "middle_click", "triple_click", "mouse_move", "left_mouse_down", "left_mouse_up", "cursor_position")) {
            assertEquals("$off is off", false, configs[off]["enabled"].asBoolean())
        }
        for (on in listOf("screenshot", "zoom", "left_click", "left_click_drag", "scroll", "key", "wait")) {
            assertFalse("$on stays on", configs.has(on) && !configs[on]["enabled"].asBoolean(true))
        }
    }

    @Test fun appToolsAreValidSchemas() {
        val tools = body()["tools"].filter { it.has("name") }
        val names = tools.map { it["name"].asText() }
        assertEquals(listOf("walk", "read_text", "find_template", "list_templates", "list_macros", "run_macro", "save_template", "save_macro", "ask_user", "launch_game"), names)
        for (t in tools) {
            assertEquals(t["name"].asText(), "object", t["input_schema"]["type"].asText())
            assertTrue(t["description"].asText().isNotBlank())
            val props = t["input_schema"]["properties"]
            t["input_schema"]["required"]?.forEach { assertTrue("${t["name"]}: required ${it.asText()} is a property", props.has(it.asText())) }
        }
        assertEquals(listOf("left", "right", "up", "down"), tools.first { it["name"].asText() == "walk" }["input_schema"]["properties"]["direction"]["enum"].map { it.asText() })
    }

    @Test fun computerResultsEchoTheToolsetAndCarryTheScreenshot() {
        val j = body { b: com.anthropic.models.beta.messages.MessageCreateParams.Builder ->
            b.addUserMessageOfContentBlockParams(listOf(
                BetaContentBlockParam.ofToolResult(ClaudeRequest.toolResult("toolu_1", "computer", ToolResult("screenshot", "AAAA"))),
                BetaContentBlockParam.ofToolResult(ClaudeRequest.toolResult("toolu_2", "computer", ToolResult("Refused by the app: shop", isError = true))),
                BetaContentBlockParam.ofToolResult(ClaudeRequest.toolResult("toolu_3", null, ToolResult("\"Quest\" at (100, 200)"))),
            ))
        }
        val results = j["messages"].last()["content"]
        assertEquals("computer", results[0]["toolset_name"].asText())
        assertEquals("image", results[0]["content"][0]["type"].asText())
        assertEquals("image/jpeg", results[0]["content"][0]["source"]["media_type"].asText())
        assertEquals("computer", results[1]["toolset_name"].asText())
        assertTrue(results[1]["is_error"].asBoolean())
        assertFalse("app tools have no toolset", results[2].has("toolset_name"))
        assertEquals("\"Quest\" at (100, 200)", results[2]["content"].asText())
    }
}
