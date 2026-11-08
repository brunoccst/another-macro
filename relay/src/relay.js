// Helpers for the relay Worker (src/index.js). Kept apart because a Worker module may
// only export handlers and Durable Object classes.

/** How long Claude's request waits for the phone (Claude's own limit is 240 s). */
export const PHONE_TIMEOUT_MS = 235_000;

export const OFFLINE = "The phone is offline: open Another Macro on the phone, allow screen capture and turn on the connector (Claude screen).";

/** Constant-time string compare, so the tokens can't be guessed one character at a time. */
export function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length === 0 || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** JSON-RPC error for a request's id (MCP clients show it to the user). */
export function rpcError(body, message, code = -32000) {
  let id = null;
  try { id = JSON.parse(body).id ?? null; } catch { /* not JSON */ }
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** True for a JSON-RPC body that owes no answer (notifications and responses only). */
export function isNotification(body) {
  try {
    const m = JSON.parse(body);
    const all = Array.isArray(m) ? m : [m];
    return all.every((x) => x && (x.id === undefined || x.id === null));
  } catch {
    return false;
  }
}

export function bearer(request) {
  const h = request.headers.get("Authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7).trim() : "";
}

export function text(status, body) {
  return new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

/** The method of a single JSON-RPC request, or "" for anything else. */
export function rpcMethod(body) {
  try {
    const m = JSON.parse(body);
    return m && !Array.isArray(m) && typeof m.method === "string" ? m.method : "";
  } catch {
    return "";
  }
}

/** MCP protocol versions the phone speaks (McpServer.VERSIONS); the first answers any other. */
export const VERSIONS = ["2025-06-18", "2025-11-25", "2025-03-26"];

/**
 * The relay's own answer while the phone is offline, so claude.ai can add, check and list the
 * connector: initialize, ping and tools/list (the phone's last tool list, if the relay has seen
 * one). Null for anything else, which gets OFFLINE.
 */
export function offlineAnswer(body, tools) {
  let m;
  try { m = JSON.parse(body); } catch { return null; }
  if (!m || Array.isArray(m) || m.id === undefined || m.id === null) return null;
  let result;
  if (m.method === "initialize") {
    const asked = m.params?.protocolVersion;
    result = {
      protocolVersion: VERSIONS.includes(asked) ? asked : VERSIONS[0],
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "another-macro-phone", version: "relay" },
      instructions: OFFLINE,
    };
  } else if (m.method === "ping") {
    result = {};
  } else if (m.method === "tools/list" && Array.isArray(tools)) {
    result = { tools };
  } else {
    return null;
  }
  return JSON.stringify({ jsonrpc: "2.0", id: m.id, result });
}
