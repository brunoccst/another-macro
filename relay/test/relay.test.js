// Run with `npm test` (Node 18+, no dependencies): the Worker's routing and auth, and the
// relay pairing each MCP request with the phone's answer, with a fake phone socket.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { PhoneRelay } from "../src/index.js";
import { safeEqual, isNotification, OFFLINE } from "../src/relay.js";

const env = { PHONE_TOKEN: "p".repeat(43), CONNECTOR_TOKEN: "c".repeat(43) };

/** A fake Durable Object namespace whose stub records what reached it. */
function withStub(reply = () => new Response("from relay")) {
  const seen = [];
  return {
    seen,
    env: { ...env, RELAY: { idFromName: (n) => n, get: () => ({ fetch: async (r) => { seen.push(r); return reply(r); } }) } },
  };
}

const rpc = (id, method = "tools/list") => JSON.stringify({ jsonrpc: "2.0", id, method });

test("tokens are compared exactly", () => {
  assert.equal(safeEqual("abc", "abc"), true);
  assert.equal(safeEqual("abc", "abd"), false);
  assert.equal(safeEqual("abc", "abcd"), false);
  assert.equal(safeEqual("", ""), false);
  assert.equal(safeEqual(undefined, "x"), false);
});

test("notifications are told apart from requests", () => {
  assert.equal(isNotification(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })), true);
  assert.equal(isNotification(rpc(1)), false);
  assert.equal(isNotification("not json"), false);
});

test("without secrets the Worker says what to set", async () => {
  const r = await worker.fetch(new Request("https://x/mcp"), {});
  assert.equal(r.status, 500);
});

test("/mcp needs the connector token, in the path or as a Bearer header", async () => {
  const { env: e, seen } = withStub();
  assert.equal((await worker.fetch(new Request("https://x/mcp", { method: "POST", body: rpc(1) }), e)).status, 401);
  assert.equal((await worker.fetch(new Request("https://x/mcp/wrong", { method: "POST", body: rpc(1) }), e)).status, 401);
  assert.equal((await worker.fetch(new Request(`https://x/mcp/${env.PHONE_TOKEN}`, { method: "POST", body: rpc(1) }), e)).status, 401, "the phone token is not the connector token");
  assert.equal(seen.length, 0);
  const inPath = await worker.fetch(new Request(`https://x/mcp/${env.CONNECTOR_TOKEN}`, { method: "POST", body: rpc(1) }), e);
  assert.equal(await inPath.text(), "from relay");
  const header = await worker.fetch(new Request("https://x/mcp", { method: "POST", body: rpc(2), headers: { Authorization: `Bearer ${env.CONNECTOR_TOKEN}` } }), e);
  assert.equal(header.status, 200);
  assert.equal(seen.length, 2);
  assert.equal(await seen[1].text(), rpc(2));
  assert.ok(!seen[0].url.includes(env.CONNECTOR_TOKEN), "the token is not passed on");
});

test("/mcp answers 405 to GET (no stream offered)", async () => {
  const { env: e } = withStub();
  const r = await worker.fetch(new Request(`https://x/mcp/${env.CONNECTOR_TOKEN}`), e);
  assert.equal(r.status, 405);
});

test("/phone needs the phone token and a WebSocket upgrade", async () => {
  const { env: e, seen } = withStub();
  assert.equal((await worker.fetch(new Request("https://x/phone", { headers: { Upgrade: "websocket" } }), e)).status, 401);
  assert.equal((await worker.fetch(new Request("https://x/phone", { headers: { Upgrade: "websocket", Authorization: `Bearer ${env.CONNECTOR_TOKEN}` } }), e)).status, 401);
  assert.equal((await worker.fetch(new Request("https://x/phone", { headers: { Authorization: `Bearer ${env.PHONE_TOKEN}` } }), e)).status, 426);
  const ok = await worker.fetch(new Request("https://x/phone", { headers: { Upgrade: "websocket", Authorization: `Bearer ${env.PHONE_TOKEN}` } }), e);
  assert.equal(await ok.text(), "from relay");
  assert.equal(seen.length, 1);
});

test("other paths are not found and the root says nothing more", async () => {
  const { env: e } = withStub();
  assert.equal((await worker.fetch(new Request("https://x/admin"), e)).status, 404);
  assert.equal(await (await worker.fetch(new Request("https://x/"), e)).text(), "Another Macro relay");
});

/** A relay instance with a fake phone that answers each message with [answer]. */
function relayWithPhone(answer) {
  const sockets = [];
  const kept = new Map();
  const storage = { get: async (k) => kept.get(k), put: async (k, v) => { kept.set(k, v); } };
  const relay = new PhoneRelay({ getWebSockets: () => sockets, acceptWebSocket: () => {}, storage }, env);
  if (answer) {
    sockets.push({
      send(msg) {
        const m = JSON.parse(msg);
        queueMicrotask(() => relay.webSocketMessage(this, JSON.stringify({ id: m.id, body: answer(m.body) })));
      },
    });
  }
  return { relay, sockets };
}

test("requests reach the phone and its answer comes back", async () => {
  const { relay } = relayWithPhone((body) => JSON.stringify({ jsonrpc: "2.0", id: JSON.parse(body).id, result: { ok: true } }));
  const r = await relay.mcp(rpc(7));
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("Content-Type"), "application/json");
  assert.deepEqual(await r.json(), { jsonrpc: "2.0", id: 7, result: { ok: true } });
  assert.equal(relay.pending.size, 0);
});

test("a notification gets 202 with no body", async () => {
  const { relay } = relayWithPhone(() => null);
  const r = await relay.mcp(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }));
  assert.equal(r.status, 202);
});

test("with no phone connected Claude gets a JSON-RPC error that says so", async () => {
  const { relay } = relayWithPhone(null);
  const r = await relay.mcp(rpc("abc"));
  const j = await r.json();
  assert.equal(j.id, "abc");
  assert.equal(j.error.message, OFFLINE);
});

test("a phone that drops mid-request ends the request with the offline error", async () => {
  const { relay, sockets } = relayWithPhone(null);
  sockets.push({ send() { queueMicrotask(() => relay.webSocketClose()); } });
  const j = await (await relay.mcp(rpc(9))).json();
  assert.equal(j.id, 9);
  assert.equal(j.error.message, OFFLINE);
  assert.equal(relay.pending.size, 0);
});

test("with the phone off the relay still answers initialize and ping, so the connector can be added", async () => {
  const { relay } = relayWithPhone(null);
  const init = await (await relay.mcp(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-11-25" } }))).json();
  assert.equal(init.id, 1);
  assert.equal(init.result.protocolVersion, "2025-11-25");
  assert.deepEqual(init.result.capabilities, { tools: { listChanged: false } });
  assert.equal(init.result.instructions, OFFLINE);
  const odd = await (await relay.mcp(JSON.stringify({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: "1999-01-01" } }))).json();
  assert.equal(odd.result.protocolVersion, "2025-06-18");
  assert.deepEqual((await (await relay.mcp(rpc(3, "ping"))).json()).result, {});
  const call = await (await relay.mcp(rpc(4, "tools/call"))).json();
  assert.equal(call.error.message, OFFLINE);
});

test("the phone's tool list is kept and served while the phone is off", async () => {
  const tools = [{ name: "screenshot", description: "d", inputSchema: { type: "object", properties: {} } }];
  const { relay, sockets } = relayWithPhone((body) => JSON.stringify({ jsonrpc: "2.0", id: JSON.parse(body).id, result: { tools } }));
  await relay.mcp(rpc(5, "tools/list"));
  sockets.length = 0; // the phone goes offline
  const j = await (await relay.mcp(rpc(6, "tools/list"))).json();
  assert.equal(j.id, 6);
  assert.deepEqual(j.result.tools, tools);
});
