# Another Macro relay

A tiny Cloudflare Worker that lets your Claude chat (claude.ai, the Claude apps) reach
the **phone connector** in the Another Macro app. You run it on your own free Cloudflare
account; nobody else's server is involved.

Claude's custom connectors are called from Anthropic's cloud, and a phone has no public
address. So the app keeps a WebSocket open to this Worker, and the Worker passes each
request from Claude to the phone and the answer back. It stores nothing and logs no
request contents (screenshots go straight through).

```
Claude (Anthropic cloud) --HTTPS /mcp/<connector token>--> this Worker <--WebSocket /phone-- Another Macro app
```

## Set it up (once, about 10 minutes, all from the phone's browser)

1. **Tokens.** In Another Macro open **System → Claude** and tap **Make new tokens**.
2. **Deploy.** Tap this button and sign in to Cloudflare (a free account is enough):

   [![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/brunoccst/another-macro/tree/main/relay)

   If it asks for `PHONE_TOKEN` and `CONNECTOR_TOKEN`, paste them from the app (**Copy phone
   token**, **Copy connector token**). If it doesn't, add them after the deploy: Cloudflare
   dashboard → **Workers & Pages** → **another-macro** → **Settings** →
   **Variables and Secrets** → **Add**, type **Secret**, one for each.

   If the build log shows `pip install .` or "Could not detect a directory containing static
   files", the build ran from the top of the repo: in the dashboard open the Worker →
   **Settings** → **Build**, set **Root directory** to `relay`, save, and retry the build.

   Builds of a branch other than the Worker's production branch run as previews
   (`wrangler preview`), which can't create the relay's Durable Object: build the
   production branch (Settings → Build → Branch control).

   Without the button: dashboard → **Workers & Pages** → **Create** → **Import a
   repository**, pick your fork of another-macro and set the root directory to `relay`.
3. **Relay address.** Copy the Worker's address (`https://another-macro.<you>.workers.dev`)
   into the app's **Relay address** field.
4. **Connector.** In the app tap **Copy connector address**. In claude.ai open **Settings →
   Connectors → Add custom connector**, name it e.g. "Another Macro" and paste the
   address. Open the connector there and set its tool permission to **Always approved**, so
   its tools load in every chat (project threads included) without a prompt per call. (If the form offers request headers, you can use `…/mcp` as the address and
   `Authorization: Bearer <connector token>` as a header instead.)
5. **Use it.** Keep taps and screen capture on, tap **Turn on** on System → Claude (or switch
   on **Stay on until I turn it off**), then ask Claude in a chat, e.g. "Take a
   screenshot of my phone and open the menu". Turn on the connector in the chat's tools
   menu if Claude doesn't see it.

## Safety

- Both tokens are 256-bit random secrets made on the phone. The Worker answers only
  requests that carry them (compared in constant time). Keep the connector address private:
  it contains the connector token.
- New tokens: tap **Make new tokens** in the app, then replace both secrets in the Worker and
  the address in claude.ai. The old ones stop working.
- The app's own rules still apply to every tap: only inside Another Eden, never on a purchase
  screen, Allow/Deny on the phone before risky taps. The connector turns itself off after
  30 minutes without use (unless "Stay on until I turn it off" is on), and the floating ■ stops
  what Claude is doing.

## Develop

```
npm test                 # routing, auth and request pairing, with a fake phone (no dependencies)
cp .dev.vars.example .dev.vars   # fill in two test tokens
npx wrangler dev         # local Worker; npx wrangler deploy to publish
```

Endpoints: `POST /mcp/<token>` or `POST /mcp` with `Authorization: Bearer <token>`
(MCP Streamable HTTP, JSON answers; `GET` gives 405 since there is no server stream) and
`GET /phone` (WebSocket, `Authorization: Bearer <phone token>`). Messages to the phone are
`{"id": "...", "body": "<JSON-RPC request>"}`; the phone answers `{"id": "...", "body": "<JSON-RPC response>" | null}`.
Durable Objects on the free plan need the SQLite storage backend, hence `new_sqlite_classes`.
