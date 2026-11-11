package io.github.brunoccst.anothermacro

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The phone connector's MCP server: JSON-RPC in, JSON-RPC out. The relay passes each
 * request Claude sends to its /mcp endpoint (Streamable HTTP) to the phone as text, and
 * the answer back. Only what Claude's connectors use: initialize, ping, tools/list and
 * tools/call. Plain JVM code, so it is unit tested without a device (McpServerTest).
 */
class McpServer(
    private val tools: List<McpTool>,
    private val instructions: String,
    private val call: (name: String, args: Map<String, Any?>) -> ToolResult,
) {
    class McpTool(val name: String, val description: String, val properties: Map<String, Any>, val required: List<String> = emptyList())

    private val json = ObjectMapper()
    private val nodes = JsonNodeFactory.instance

    /** The answer to one request body, or null when nothing is owed (only notifications). */
    fun handle(body: String): String? {
        val req = try { json.readTree(body) } catch (e: Exception) {
            return json.writeValueAsString(error(nodes.nullNode(), -32700, "parse error"))
        }
        if (req.isArray) {
            val out = nodes.arrayNode()
            req.forEach { one(it)?.let(out::add) }
            return if (out.isEmpty) null else json.writeValueAsString(out)
        }
        return one(req)?.let { json.writeValueAsString(it) }
    }

    private fun one(req: JsonNode): JsonNode? {
        val id = req.get("id")
        val method = req.path("method").asText("")
        if (id == null || id.isNull) return null // a notification (initialized, cancelled, ...) or a response
        return try {
            when (method) {
                "initialize" -> result(id, initialize(req.path("params")))
                "ping" -> result(id, nodes.objectNode())
                "tools/list" -> result(id, nodes.objectNode().set("tools", toolList()))
                "tools/call" -> result(id, toolCall(req.path("params")))
                else -> error(id, -32601, "method not found: $method")
            }
        } catch (e: IllegalArgumentException) {
            error(id, -32602, e.message ?: "invalid params")
        }
    }

    private fun initialize(params: JsonNode): JsonNode {
        val asked = params.path("protocolVersion").asText("")
        return nodes.objectNode().apply {
            put("protocolVersion", if (asked in VERSIONS) asked else VERSIONS.first())
            set<JsonNode>("capabilities", nodes.objectNode().set("tools", nodes.objectNode().put("listChanged", false)))
            set<JsonNode>("serverInfo", nodes.objectNode().put("name", "another-macro-phone").put("version", BuildConfig.VERSION_NAME))
            put("instructions", instructions)
        }
    }

    private fun toolList() = nodes.arrayNode().apply {
        for (t in tools) add(nodes.objectNode().apply {
            put("name", t.name)
            put("description", t.description)
            set<JsonNode>("inputSchema", nodes.objectNode().apply {
                put("type", "object")
                set<JsonNode>("properties", json.valueToTree(t.properties))
                if (t.required.isNotEmpty()) set<JsonNode>("required", json.valueToTree(t.required))
            })
        })
    }

    private fun toolCall(params: JsonNode): JsonNode {
        val name = params.path("name").asText("")
        if (tools.none { it.name == name }) throw IllegalArgumentException("unknown tool: $name")
        @Suppress("UNCHECKED_CAST")
        val args = (json.convertValue(params.path("arguments"), Map::class.java) as? Map<String, Any?>).orEmpty()
        val r = try { call(name, args) } catch (e: IllegalArgumentException) {
            ToolResult("$name failed: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult("$name failed: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
        return nodes.objectNode().apply {
            set<JsonNode>("content", nodes.arrayNode().apply {
                if (r.imageJpegB64 != null) {
                    add(nodes.objectNode().put("type", "image").put("data", r.imageJpegB64).put("mimeType", "image/jpeg"))
                    // a note about the image (e.g. "all black: the screen is off") goes next to it
                    if (r.text.isNotBlank()) add(nodes.objectNode().put("type", "text").put("text", r.text))
                } else add(nodes.objectNode().put("type", "text").put("text", r.text))
            })
            put("isError", r.isError)
        }
    }

    private fun result(id: JsonNode, r: JsonNode): ObjectNode = nodes.objectNode().put("jsonrpc", "2.0").apply { set<JsonNode>("id", id); set<JsonNode>("result", r) }

    private fun error(id: JsonNode, code: Int, message: String): ObjectNode = nodes.objectNode().put("jsonrpc", "2.0").apply {
        set<JsonNode>("id", id)
        set<JsonNode>("error", nodes.objectNode().put("code", code).put("message", message))
    }

    companion object {
        /** MCP protocol versions this server speaks; the first is the answer to any other. */
        val VERSIONS = listOf("2025-06-18", "2025-11-25", "2025-03-26")
    }
}
