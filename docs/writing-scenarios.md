# This game's macros

The macro format, "How it works", inner macros' options and templates are the same for every game
and are documented in macro-forge: **[writing macros](../../../../macro-forge/blob/main/docs/writing-macros.md)**
(`core/docs/writing-macros.md` in a checkout). This page holds what is particular to Another Eden.
The list of macros and the basic macros to reuse are in [macros/README.md](../macros/README.md).

## Where they live here

- Library macros: `macros/<group>/<name>.yaml`, groups `daily`, `dungeon`, `farm`, `game`,
  `basic`, `other` (game.yaml `groups:`). `.github/workflows/macros.yml` packs them
  (macro-forge's `core/tools/macroforge_pack.py`, with game.yaml `pack:` and `plugin_steps:`) as
  `macros-<branch>`.
- Templates: `src/another_macro/assets/templates/library.yaml` (the `common` folder) and
  `src/another_macro/scenarios/<folder>/templates/library.yaml` (a folder a macro lists in
  `templates:`).
- `python -m pytest -q tests/test_macro_pack.py` checks every library macro: YAML, names, groups,
  template folders, that every `run:` names a macro that exists, and each macro's "How it works".
- `macros/basic/grind-battles.yaml` is a complete example.

## The game's own steps

The game plugin (`android/app/src/main/java/anothermacro/logic/AnotherEdenPlugin.kt`, game.yaml
`plugin:`) adds the steps written in Kotlin:

| Step | |
|---|---|
| `fight: skill_` | fight the battle on screen with `skill_1`..`skill_4` |
| `another_dungeon: <id>` | run that Another Dungeon (`AnotherDungeon.kt`) |
| `phantom_crystal: true` | the Phantom Crystal Dimension with a White Key (`PhantomCrystal.kt`) |
| `find_feather`, `read_cat_clues`, `cat_daily` | the cat scratchbook (`CatDaily.kt`, `CatRoute.kt`) |

They describe themselves under "How it works" (`HowItWorks.kt`: a dungeon's maps, chests,
sparkles, fish, jars, its route, the boss, and what keys, runs, party, clear first, cat ticket,
White Key and the collect switches change). Change what such a step does, change its words there
too. `another_dungeon` and `cat_daily` fight with the basic macro `battle`, so macros using them
take over `battle`'s options (`stepUses`).

## Macro notes for this game

How the game behaves on the phone (walking, battles, ads, closing the game) is in the knowledge
base: [learned/playing-on-the-phone.md](knowledge-base/learned/playing-on-the-phone.md).

- **Another Dungeons:** see `.claude/skills/map-dungeon/SKILL.md` and
  [learned/dungeons/](knowledge-base/learned/dungeons/README.md) (entry, Skip, the "Continue to
  skip?" box, the boss gate, the area map's quirks, what never to tap).
- **Cat Scratchbook routes:** `scenarios/cat_scratchbook/templates/cat_spots.yaml` holds, per
  clue location, the era, continent, place, sub-location, and on the area map the arrival spot,
  the streets, the cat's start and drift, and the map's `walk_scale`. Trips start at point zero
  (World Map on Present with Baruoki on screen), then the era tab, Expand → continent where
  needed, and the label by OCR. `another-macro cat-routes --write` turns the area data into each
  spot's fixed `path`; `--todo` lists what each spot still needs measured. The app
  (`CatRoute.kt`) falls back to the feather steering for any spot or step that doesn't match.
  A spot with no route yet is measured by the app itself the first time its cat shows up
  (`CatSurvey.kt`, before the hunt): streets and stairs read from the area map, the arrival,
  the feather, a 3000 ms hold for `walk_scale` and a few feather reads for the drift. It logs one
  `MEASURED cat spot: {...}` line (`macro_status` with `find: "MEASURED cat spot"`, `lines: 600`)
  and the run's outcome names the spot. Save those lines to a file and run
  `another-macro cat-routes --import <file>`: it adds the areas (hand-measured ones are kept
  unless `--force`) and writes their paths. Check the imported streets against a look at the
  area map; the street reading is approximate.
- **Lists:** the inbox macro opens `text_row_without('utc', 'opened', 0.15, 0.86)`, the first
  item not opened yet.
