# The macro library

These are the macros of the Another Macro app. They are **not built into the app**: on every
push, CI (`.github/workflows/macros.yml`) packs this folder with the templates and data
(`src/another_macro/macro_pack.py`) and publishes it as the pre-release `macros-<branch>`. The
app downloads the pack of the branch it follows (`main` for releases) and updates it by itself,
so a change here reaches phones without an app update.

One folder per group, the same as the app's tabs (a macro's `group:` must match its folder):

| Folder | Tab | Macros |
|---|---|---|
| `basic/` | Basic | building blocks other macros run: `watch-ad`, `battle`, `grind-battles`, `open-menu`, `back-to-field` |
| `daily/` | Daily | `daily-routine`, `daily-ads`, `shop-ad`, `cat-scratchbook` |
| `dungeon/` | Another Dungeon | `dogma-tower`, `burning-beast-kings-castle`, `phantom-crystal` |
| `game/` | Game | `start-game`, `stop-game` |

## Basic macros: reuse them, don't copy them

A macro runs another one with `run:` and goes on when it ends:

```yaml
- tap: shop_watch_button
- wait: 3
- run: {macro: watch-ad, params: {ad_wait_s: ad_wait_s}, until: [shop]}
- log: "the commercial: {result}"     # result = how it ended ("rewarded", "back to shop", ...)
```

- `params:` are expressions of the calling macro; missing ones take the basic macro's defaults.
- `until:` names screens of the calling macro: the basic macro also ends when one of them shows
  (after the caller's screen was gone once, or 12 s). Its own screens are checked first.
- Its counters (`stats`) are added to the caller's. A stop request or a safety stop ends both.

| Basic macro | What it does | Used by |
|---|---|---|
| `watch-ad` | waits out the commercial that just started, presses Back to close it (again if needed), taps the reward dialog's OK (or the premium thank-you's Close) | `start-game`, `daily-ads`, `shop-ad` |
| `battle` | fights the battle on screen to its end; `skill_1`..`skill_4` = auto or skill 1-4 per party slot (all auto = the Auto button); ends with `won` / `none` / `party was defeated` | `grind-battles`, the cat hunt's and the cat routine's encounters; the Another Dungeon fights use its skills (the boss: the dungeon's boss skills when set) |
| `grind-battles` | walks back and forth and runs `battle` for every encounter, until `battles` are won | for clearing a map |
| `open-menu` | from the field, opens the game's menu | `daily-ads`, `shop-ad` |
| `back-to-field` | presses Back until the field shows | `stop-game` |
| `start-game` (Game) | opens the game, takes the login-bonus ad, stops on the field | `daily-ads`, `shop-ad` when the game isn't open; the daily routine |

A basic macro runs with the options the user set for it in the app (then the caller's
`params:`), so e.g. the battle macro's skills are set once and apply to every battle.

The Another Dungeon map clearing (fights until "The enemies have disappeared", like grind-battles
with `until_cleared`) stays inside `AnotherDungeon.kt`: it walks
by the map's layout to keep the party's position for the fixed chest paths, which plain
grind-battles walking would lose. Its fights are the battle macro's step with the battle macro's skills, the boss with the
dungeon's boss skills when set; the dungeon runs that step itself (not the macro) because it
must tell the boss by its name on the first turn and read the White Key off the results.

## Library macros and custom macros

What Claude and Bruno make is for every user, so it belongs here. A macro Claude saves on the
phone with the connector's `save_macro` lands under **Custom** as a draft for trying it out; once
it works and fits a category (Daily, Another Dungeon, Farming, Game, Basic), publish it here in
`macros/<group>/` (a PR), built from the basic macros, and delete the phone copy
(`delete_macro`). Custom stays for exceptional one-offs and, later, macros users make themselves.

## Rules

- Generic: party slots and skill numbers, never character names. A user's own choices
  (e.g. skill 4 for slot 1) are options they set in the app, which keeps them per macro.
- Never spend premium currency or confirm a purchase (the app refuses taps in the shop anyway).
- `python -m pytest -q tests/test_macro_pack.py` checks the set: YAML, names, groups, template
  folders, and that every `run:` / routine step names a macro that exists.
