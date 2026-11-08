package io.github.brunoccst.anothermacro

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone connector's MCP answers, as the relay passes them to Claude (no network, no device). */
class McpServerTest {
    private val json = ObjectMapper()
    private val calls = ArrayList<Pair<String, Map<String, Any?>>>()
    private val server = McpServer(
        listOf(
            McpServer.McpTool("screenshot", "a screenshot", emptyMap()),
            McpServer.McpTool("tap", "tap", mapOf("x" to mapOf("type" to "integer"), "y" to mapOf("type" to "integer")), listOf("x", "y")),
        ),
        "rules",
    ) { name, args ->
        calls += name to args
        when (name) {
            "screenshot" -> ToolResult("screenshot", "AAAA")
            "tap" -> if (args["x"] == 999) ToolResult("Refused by the app: shop", isError = true) else ToolResult("OK")
            else -> throw IllegalStateException("boom")
        }
    }

    private fun ask(body: String) = json.readTree(server.handle(body)!!)

    @Test fun initializeAnswersTheProtocolAndInstructions() {
        val r = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-ai","version":"1"}}}""")
        assertEquals(1, r["id"].asInt())
        assertEquals("2025-06-18", r["result"]["protocolVersion"].asText())
        assertTrue(r["result"]["capabilities"].has("tools"))
        assertEquals("another-macro-phone", r["result"]["serverInfo"]["name"].asText())
        assertEquals("rules", r["result"]["instructions"].asText())
        val other = ask("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
        assertEquals("a", other["id"].asText())
        assertEquals(McpServer.VERSIONS.first(), other["result"]["protocolVersion"].asText())
    }

    @Test fun notificationsGetNoAnswer() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test fun toolsListHasSchemas() {
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]["tools"]
        assertEquals(listOf("screenshot", "tap"), tools.map { it["name"].asText() })
        assertEquals("object", tools[0]["inputSchema"]["type"].asText())
        assertFalse(tools[0]["inputSchema"].has("required"))
        assertEquals(listOf("x", "y"), tools[1]["inputSchema"]["required"].map { it.asText() })
    }

    @Test fun toolCallsReturnTextImagesAndErrors() {
        val shot = ask("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"screenshot","arguments":{}}}""")["result"]
        assertEquals("image", shot["content"][0]["type"].asText())
        assertEquals("image/jpeg", shot["content"][0]["mimeType"].asText())
        assertEquals("AAAA", shot["content"][0]["data"].asText())
        assertFalse(shot["isError"].asBoolean())

        val ok = ask("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"tap","arguments":{"x":10,"y":20}}}""")["result"]
        assertEquals("OK", ok["content"][0]["text"].asText())
        assertEquals(10, (calls.last().second["x"] as Number).toInt())

        val refused = ask("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"tap","arguments":{"x":999,"y":20}}}""")["result"]
        assertTrue(refused["isError"].asBoolean())
        assertEquals("Refused by the app: shop", refused["content"][0]["text"].asText())
    }

    @Test fun unknownToolsAndMethodsAreErrors() {
        assertEquals(-32602, ask("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"buy_stones","arguments":{}}}""")["error"]["code"].asInt())
        assertEquals(-32601, ask("""{"jsonrpc":"2.0","id":7,"method":"resources/list"}""")["error"]["code"].asInt())
        assertEquals(-32700, ask("not json")["error"]["code"].asInt())
        assertEquals(1, ask("""{"jsonrpc":"2.0","id":8,"method":"ping"}""").let { if (it.has("result")) 1 else 0 })
    }
}
