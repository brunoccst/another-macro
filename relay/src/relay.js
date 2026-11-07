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
