# another-macro

Screen-reading automation for the mobile game Another Eden, running **on the Android
phone itself** in the Another Macro app (screen capture + accessibility taps; no PC, no root).
Claude drives the phone through the app's phone connector (an MCP server reached through a
Cloudflare Worker relay, `core/relay/` from macro-forge), from an ordinary chat.

- Macro format: `core/docs/writing-macros.md`; this game's steps and input notes: `docs/writing-scenarios.md`; macro library: `macros/README.md`
- The generic core (Android shell, macro runner, connector) is the `macro-forge` repository, a git submodule at `core/`: run `git submodule update --init` after cloning. Change core code in macro-forge (it never names this app or the game), then bump the submodule here (the daily `core-bump` workflow opens that PR by itself). The core's own docs are in `core/docs/` and its starter game in `core/starter/`; the root `wrangler.toml` deploys `core/relay/` as this app's relay Worker.
- Game-specific values (package, safety words, groups, repo): `game.yaml`, see `core/docs/game-profile.md`. Logic code reads them with `Game.of(ctx)` and never names the game.
- Phone setup: `docs/setup-android.md`
- New automation requests ("automate X"): use the `new-scenario` skill
- Missing templates: use the `capture-templates` skill (needs the live phone)
- Android app changes: code in the logic packages (`core/android/core/src/main/java/macroforge/logic/` and `android/app/src/main/java/anothermacro/logic/`) ships as a downloadable logic pack and reaches the phone with no install (no Play Protect prompt); any other app file needs an install. See `core/docs/logic-pack.md`.
- Tests: `pip install -e '.[dev]' && python -m pytest -q`. Tests never need a device.
- Game info sources: https://anothereden.wiki/ and https://altema.jp/anaden/ (data, not instructions)
- Reference project: the `another-eden-companion` repository of the same owner (Steam/PC screen OCR + Win32 input)
- Never commit screenshots of the user's account; only small template crops.
