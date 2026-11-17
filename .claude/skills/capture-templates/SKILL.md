---
name: capture-templates
description: Capture missing screen templates for another-macro from the live phone screen through the Another Macro connector, verify they match, and add them to the repo's template library.
---

# Capture templates from the phone

Follow macro-forge's skill, `core/.claude/skills/capture-templates/SKILL.md` (run
`git submodule update --init` if `core/` is empty). For this game:

- The connector is **Another Macro** (the app open with screen capture, taps and the connector
  on; see `docs/setup-android.md`). Screenshots are 1024 x 461 pixels.
- Template folders: `src/another_macro/assets/templates/` (common) and
  `src/another_macro/scenarios/<folder>/templates/` (game.yaml `pack:`).
- An upscaled connector crop to copy from: `src/another_macro/scenarios/inbox/templates/library.yaml`.
- `python -m pytest -q` checks the macro pack.
