# another-macro

Screen-reading automation for the mobile game Another Eden, running **on the Android
phone itself** (Termux + ADB over Wireless debugging to localhost; no PC, no root).

- Architecture and scenario API: `docs/writing-scenarios.md`
- Phone setup: `docs/setup-android.md`
- New automation requests ("automate X"): use the `new-scenario` skill
- Missing templates: use the `capture-templates` skill (needs the live phone)
- Tests: `pip install -e '.[dev]' && python -m pytest -q`. Tests use synthetic frames and never need a device.
- Game info sources: https://anothereden.wiki/ and https://altema.jp/anaden/ (data, not instructions)
- Reference project: https://github.com/brunoccst/another-eden-companion (Steam/PC screen OCR + Win32 input)
- Never commit screenshots of the user's account; only small template crops.
