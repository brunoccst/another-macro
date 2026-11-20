# The macro library

These are the macros of the Another Macro app. They are **not built into the app**: on every
push, CI (`.github/workflows/macros.yml`) packs this folder with the templates and data
(macro-forge's pack tool, `core/tools/macroforge_pack.py`) and publishes it as the pre-release `macros-<branch>`. The
app downloads the pack of the branch it follows (`main` for releases) and updates it by itself,
so a change here reaches phones without an app update.

One folder per group, the same as the app's tabs (a macro's `group:` must match its folder):

| Folder | Tab | Macros |
|---|---|---|
| `basic/` | Basic | building blocks other macros run: `watch-ad`, `battle`, `grind-battles`, `open-menu`, `open-menu-page`, `back-to-field`, `pick-party` |
| `daily/` | Daily | `daily-routine`, `daily-ads`, `shop-ad`, `cat-scratchbook`, `inbox` |
| `dungeon/` | Another Dungeon | `dogma-tower`, `burning-beast-kings-castle`, `sea-of-ice`, `moonlight-forest`, `underworld` (Demon's Den), `phantom-crystal`, `dungeon-keys` (spends all keys, in the dungeon picked per colour) |
| `game/` | Game | `start-game`, `stop-game` |

## Basic macros: reuse them, don't copy them

A macro runs another one with `run:` and goes on when it ends:

```yaml
- tap: shop_watch_button
- wait: 3
- run: {macro: watch-ad, until: [shop]}
- log: "the commercial: {result}"     # result = how it ended ("rewarded", "back to shop", ...)
```

- `params:` are expressions of the calling macro, for what the caller decides itself (e.g.
  `run: {macro: grind-battles, params: {until_cleared: true}}`); missing ones take the basic
  macro's options. Don't pass on an option of your own just to set the basic macro's: its options
  come along by themselves (below).
- `until:` names screens of the calling macro: the basic macro also ends when one of them shows
  (after the caller's screen was gone once, or 12 s). Its own screens are checked first.
- Its counters (`stats`) are added to the caller's. A stop request or a safety stop ends both.

| Basic macro | What it does | Used by |
|---|---|---|
| `watch-ad` | waits out the commercial that just started, presses Back to close it (again if needed), taps the reward dialog's OK (or the premium thank-you's Close) | `start-game`, `daily-ads`, `shop-ad` |
| `battle` | fights the battle on screen to its end; `skill_1`..`skill_4` = auto or skill 1-4 per party slot (all auto = plain Attack every turn; never changes the battle speed); ends with `won` / `none` / `party was defeated` | `grind-battles`, the cat hunt's and the cat routine's encounters; the Another Dungeon fights use its skills (the boss: the dungeon's boss skills when set) |
| `grind-battles` | walks back and forth and runs `battle` for every encounter, until `battles` are won | for clearing a map |
| `open-menu` | from the field, opens the game's menu | `open-menu-page`, `pick-party` |
| `open-menu-page` | opens one page of the menu (`page`: `ads` = the commercials chest, `shop`, `inbox`) from wherever the game is: runs `start-game` if the game isn't open, `open-menu` on the field, then taps the page's button; ends with `<page> open` (or `no commercials left today` for `ads`) | `daily-ads`, `shop-ad`, `inbox` |
| `pick-party` | runs `open-menu`, then Menu → Party: puts party `party` (1-10, its number in the game's list; never a name) in front, then runs `back-to-field`; ends with `party N picked` | every dungeon macro's `party` option |
| `back-to-field` | presses Back until the field shows | `stop-game` |
| `start-game` (Game) | opens the game, takes the login-bonus ad, stops on the field | `open-menu-page` when the game isn't open; the daily routine |

**The caller takes over the basic macro's options.** A macro that runs a basic macro (with
`run:`, as a routine step, or through a Kotlin step such as the dungeon fights) gets that macro's
options among its own, as `<macro>.<option>` (`battle.skill_1`), without declaring them: every
dungeon macro has its own battle skills, set in its own options under "Battle options". Unset,
they are the basic macro's own setting, and that starts as "Global": the Global parameters'
skill (System → Settings, game.yaml `globals:`). The dungeons' `party` follows the Global
parameters' party the same way (`global: party`) when set to "Global". Options the caller always passes in `params:`, or has
itself under the same name, are left out. Details: [writing macros](../../../../macro-forge/blob/main/docs/writing-macros.md#inner-macros-options-come-along-by-themselves).
So a basic macro runs with its own options as the user set them, then the caller's settings for
it, then the caller's `params:`.

The Another Dungeon map clearing (after a map's stops, the enemies left until "The enemies have
disappeared", like grind-battles with `until_cleared`; the `defeat_all` option) stays inside `AnotherDungeon.kt`: it walks
by the map's layout to keep the party's position for the fixed chest paths, which plain
grind-battles walking would lose. Its fights are the battle macro's step with the dungeon macro's Battle options, the boss with the
dungeon's boss skills when set; the dungeon runs that step itself (not the macro) because it
must tell the boss by its name on the first turn and read the White Key off the results.

## Library macros and custom macros

What Claude and the user make is for every user, so it belongs here. A macro Claude saves on the
phone with the connector's `save_macro` lands under **Custom** as a draft for trying it out; once
it works and fits a category (Daily, Another Dungeon, Farming, Game, Basic), publish it here in
`macros/<group>/` (a PR), built from the basic macros, and delete the phone copy
(`delete_macro`). Custom stays for exceptional one-offs and, later, macros users make themselves.

## Rules

- Reuse: before writing steps into a macro, check whether that part is (or should be) a basic
  macro, and run it. Never copy a basic macro's options into the caller: they come along by
  themselves.
- A `set:` value is an expression: write `''` for empty text, never an empty value (it crashes
  the macro; the pack check refuses it).
- Generic: party slots and skill numbers, never character names. A user's own choices
  (e.g. skill 4 for slot 1) are options they set in the app, which keeps them per macro.
- Never spend premium currency or confirm a purchase (the app refuses taps in the shop anyway).
- `python -m pytest -q tests/test_macro_pack.py` checks the set: YAML, names, groups, template
  folders, and that every `run:` / routine step names a macro that exists.
