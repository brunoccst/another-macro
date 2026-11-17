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

## Game input notes (learned on the phone)

- **Walking is a virtual stick.** The party walks in the direction a held finger *slides*, not
  toward the side of the screen you press. Press mid-screen and slide a little left or right
  (`walk:` or `drag:`); a hold that drifts the wrong way walks the wrong way.
- **Battles start with an intro animation** with no HUD; it shows as `unknown` for a second or
  two before the command screen.
- **Closing the game:** Android back on the field opens "Close the app?"; Yes exits cleanly
  (`stop-game`). Starting: splash → title ("Game Id..." while connecting, then "Continue") → tap
  → field (`start-game`).
- **Ads:** the first `start-game` of the day always takes "Watch Video" on the login-bonus ad
  offer; that reward goes to the inbox. Each watch button (login, the 5 daily chests, the shop)
  can play 1-3 commercials before the reward. Some end with "Reward granted", others only with a
  progress bar; `watch-ad` closes them with Back (also a Google Play sheet a commercial opens)
  and takes the reward dialog. With the premium subscription a "Thank you for subscribing"
  dialog replaces the ad (Close).
- **Battles (`Battle.kt`):** each front-row character's skill row is 5 boxes: normal attack,
  then skills 1-4 (x 0.177 / 0.346 / 0.499 / 0.653 / 0.807, y 0.759 on a 20:9 screen). Not every
  character has 4 skills; a missing or unusable one leaves the list open, and the normal attack
  closes it. Tapping the portrait again does not close it.
- **Another Dungeons:** see `.claude/skills/map-dungeon/SKILL.md` (entry, Skip, the "Continue to
  skip?" box, the boss gate, the area map's quirks, what never to tap).
- **Cat Scratchbook routes:** `scenarios/cat_scratchbook/templates/cat_spots.yaml` holds, per
  clue location, the era, continent, place, sub-location, and on the area map the arrival spot,
  the streets, the cat's start and drift, and the map's `walk_scale`. Trips start at point zero
  (World Map on Present with Baruoki on screen), then the era tab, Expand → continent where
  needed, and the label by OCR. `another-macro cat-routes --write` turns the area data into each
  spot's fixed `path`; `--todo` lists what each spot still needs measured. The app
  (`CatRoute.kt`) falls back to the feather steering for any spot or step that doesn't match.
- **Lists:** the inbox macro opens `text_row_without('utc', 'opened', 0.15, 0.86)`, the first
  item not opened yet.
