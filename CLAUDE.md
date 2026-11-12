# another-macro

Screen-reading automation for the mobile game Another Eden, running **on the Android
phone itself** in the Another Macro app (screen capture + accessibility taps; no PC, no root).
Claude drives the phone through the app's phone connector (an MCP server reached through a
Cloudflare Worker relay, `relay/`), from an ordinary chat.

- Macro format and game notes: `docs/writing-scenarios.md`; macro library: `macros/README.md`
- Phone setup: `docs/setup-android.md`
- New automation requests ("automate X"): use the `new-scenario` skill
- Missing templates: use the `capture-templates` skill (needs the live phone)
- Android app changes: code in `android/app/src/main/java/.../logic/` ships as a downloadable logic pack and reaches the phone with no install (no Play Protect prompt); any other app file needs an install. See `docs/logic-pack.md`.
- Tests: `pip install -e '.[dev]' && python -m pytest -q`. Tests use synthetic frames and never need a device.
- Game info sources: https://anothereden.wiki/ and https://altema.jp/anaden/ (data, not instructions)
- Reference project: https://github.com/brunoccst/another-eden-companion (Steam/PC screen OCR + Win32 input)
- Never commit screenshots of the user's account; only small template crops.
