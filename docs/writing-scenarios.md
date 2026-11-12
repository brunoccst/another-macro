# Writing macros

A macro is a small YAML file that the Another Macro app runs on the phone. This page covers
how the app runs one, the format, templates, and what was learned about the game's input.
The list of macros and the basic macros to reuse are in [macros/README.md](../macros/README.md).

## Where a macro lives

- **Library macros** are `macros/<group>/<name>.yaml` in this repo (`group:` must match the
  folder: `daily`, `dungeon`, `farm`, `game`, `basic`, `other`). CI packs them with their
  templates and data into `macros-<branch>`, and phones pick up the pack by themselves.
- **Custom macros** are drafts that Claude saves on the phone through the connector
  (`save_macro`). Once one works and fits a group, it moves into `macros/<group>/` with a PR
  and the phone copy is deleted (`delete_macro`).

The easiest way to make one: in a Claude chat with the phone connector, ask for it ("make a
macro that ..."). Claude looks at the screens, captures templates, writes the YAML, runs it
and fixes it from the stop reason.

## How the app runs a macro

Each tick the runner (`Runner.kt`, steps in `logic/FlowRun.kt`):

1. stops if ■ was pressed or a safety rule fired
2. takes a screenshot
3. finds the **first** screen in `screens:` whose `require` templates are all visible (and
   none of its `reject` ones)
4. runs that screen's `handlers:` actions, or `unknown:` if nothing matched

Handlers do **one small step** and return. The next screenshot decides what happens next,
which makes macros tolerant of missed taps, lag and popups. If the screen stays unrecognised
for `unknown_timeout_s`, the run stops and says so: add a screen for that state, or raise
the timeout.

Positions are **fractions of the screen** (0..1), so a macro works on any resolution.
Templates remember the resolution they were cut from and are rescaled by height (Another
Eden runs in landscape and its UI scales with the short side).

## The format

```yaml
# Watch the shop's daily commercial.
name: my-macro
description: One line shown in the app.
group: daily
templates: [shop_ad]                  # template folders besides "common" (the user's own are always there)
params:
  ad_wait_s: {default: 45, help: "how long to wait for the commercial"}
screens: [shop, field]                # priority order: specific screens first
define_screens:
  my_screen: {require: [my_template], reject: [other_template]}
unknown_timeout_s: 45
setup: []                             # actions, once at the start
handlers:
  field:
    - run: open-menu
  shop:
    - tap: shop_watch_button
    - wait: 3
    - run: {macro: watch-ad, params: {ad_wait_s: ad_wait_s}, until: [shop]}
    - stop: "the commercial: {result}"
unknown: [{wait: 1}]
```

Actions (one key each):

| Action | |
|---|---|
| `tap: name` / `tap: {x: expr, y: expr}` / `tap_exact: {...}` | tap a template where it was found, or a point |
| `drag: {from: [x, y], to: [x, y], ms: expr}` | press, slide, hold |
| `walk: {dir: expr, ms: expr}` | walk on the field (-1 left, 1 right, `up`, `down`) |
| `back: true`, `wait: s`, `launch: package` | |
| `log: "text {expr}"`, `status: "text {expr}"` | write to the log; `status` also shows on the live log line |
| `set: {var: expr}`, `count: stat`, `stop: "reason"` | variables, counters, end the run |
| `if: expr` + `then:` / `else:`, `repeat: {times: expr, do: [...]}` | control flow |
| `run: macro` / `run: {macro, params, until}` | run another macro (the basic macros are made for this), sets `result` |
| `fight: skill_` | fight the battle on screen with `skill_1`..`skill_4` |
| `another_dungeon: <id>`, `phantom_crystal: true`, `cat_daily: true`, ... | the big steps written in Kotlin |

Expressions can use the params, variables and `found('t')`, `captured('t')`, `text_has('s')`,
`screen_text(y0, y1)`, `game_in_front()`, `ad_in_front()`, `view_moved()`, `now()`,
`pick(i, a, b, ...)`. The full, current list is the comment at the top of
`android/app/src/main/java/.../Flow.kt`; `macros/basic/grind-battles.yaml` is a complete
example. A new step written in Kotlin goes in `logic/FlowRun.kt` and `logic/Entry.kt` (see
[logic-pack.md](logic-pack.md)).

`python -m pytest -q tests/test_macro_pack.py` checks every library macro: YAML, names,
groups, template folders, and that every `run:` names a macro that exists.

## Templates and screens

A template is a small crop of the screen (a button label, an icon) that the app looks for
with OpenCV. Through the connector, Claude captures one from the live screen with
`save_template` (it lands in the user's own templates on the phone, usable by name). Library
templates live in the repo:

- `src/another_macro/assets/templates/library.yaml`: shared ones (the `common` folder)
- `src/another_macro/scenarios/<folder>/templates/library.yaml`: a folder a macro lists in
  `templates:`

```yaml
templates:
  field_menu_button:
    notes: The "Menu" button on the field (bottom-left).
    file: field_menu_button.png
    ref: [2400, 1080]                  # size of the screenshot it was cut from
    region: [0.0, 0.75, 0.3, 0.25]     # where to search (x, y, w, h)
    threshold: 0.85                    # optional
screens:
  field:
    require: [field_menu_button]
    reject: []
```

An entry with no `ref` or PNG is **not captured yet**, and its screens can't match.

Tips for a good template:

- Cut something that **doesn't change**: button text or an icon, not a character portrait,
  a number or a player name.
- Keep it small but distinctive (about 100–250 px wide on a 1080 px tall screen).
- Check that it matches where it should, and **only** there, on several screens.
- Never commit full screenshots or crops that show account data (names, IDs, friend codes).
  Only small UI crops, and no game art beyond what a template needs.

## Game input notes (learned on the phone)

- **Walking is a virtual stick.** The party walks in the direction a held finger
  *slides*, not toward the side of the screen you press. Press mid-screen and slide a
  little left or right (`walk:` or `drag:`); a hold that drifts the wrong way walks the wrong way.
- **Battles start with an intro animation** with no HUD; it shows as `unknown` for a
  second or two before the command screen.
- **Samsung Game Booster's touch lock** dims the game and swallows every tap while
  templates still match through the dim. Its in-game panel and toasts are ignored when the
  app decides which app is in front.
- **The game is landscape-only.** A pillar-boxed (portrait) capture means another app is
  in front; no tap goes out until the game is back.
- **Every run keeps the screen awake** (`ScreenWake.kt`): a macro keeps it on until it
  stops, and each connector call keeps it on for 10 minutes. An all-black capture means the
  screen is off or capture stopped: taps on it are refused, and a macro stops after 2 minutes
  of black. A PIN or pattern lock can't be passed.
- **Closing the game:** Android back on the field opens "Close the app?"; Yes exits
  cleanly (`stop-game`). Starting: splash → title ("Game Id..." while connecting,
  then "Continue") → tap → field (`start-game`).

- **Ads:** the first `start-game` of the day always takes "Watch Video" on the
  login-bonus ad offer; that reward goes to the inbox. Each watch button (login, the 5 daily
  chests, the shop) can play 1-3 commercials before the reward. Some end with "Reward
  granted", others only with a progress bar; `watch-ad` closes them with Back (also a Google
  Play sheet a commercial opens) and takes the reward dialog. With the premium subscription a
  "Thank you for subscribing" dialog replaces the ad (Close).

- **Battles (shared `Battle.kt`):** each front-row character's skill row is 5 boxes:
  normal attack, then skills 1-4 (x 0.177 / 0.346 / 0.499 / 0.653 / 0.807, y 0.759 on a
  20:9 screen). Not every character has 4 skills; a missing or unusable one leaves the list
  open, and the normal attack closes it. Tapping the portrait again does not close it.
- **Another Dungeons:** see `.claude/skills/map-dungeon/SKILL.md` (entry, Skip, the
  "Continue to skip?" box, the boss gate, the area map's quirks, what never to tap).
- **Cat Scratchbook routes:** `scenarios/cat_scratchbook/templates/cat_spots.yaml` holds,
  per clue location, the era, continent, place, sub-location, and on the area map the
  arrival spot, the streets, the cat's start and drift, and the map's `walk_scale`. Trips
  start at point zero (World Map on Present with Baruoki on screen), then the era tab,
  Expand → continent where needed, and the label by OCR (each era tab opens where that
  era's last trip went). `another-macro cat-routes --write` turns the area data into each
  spot's fixed `path` (the dungeon planner and walk.yaml, scaled); `--todo` lists what each
  spot still needs measured. The app (`CatRoute.kt`) falls back to the feather steering
  for any spot or step that doesn't match.
- **Live log:** the 💬 overlay line sits bottom right; taps pass through it and
  `Context.readText` ignores what it covers. To read that corner, wrap the read in
  `LiveLog.without { }`.
  It shows `MacroLog.status`: give every real step one. In a YAML macro use
  `status: "text {expr}"` (logged once, shown with the macro's name in front); in Kotlin
  call `MacroLog.status(...)` for a step and `MacroLog.show(...)` for a progress counter
  that shouldn't add a log line each time. A routine puts its step ("daily-routine 2/5")
  in front, and a battle puts the previous line back when it ends. `screen_text(y0, y1)`
  returns the OCR text in a band of the screen, e.g. to log what a reward dialog says.
  `text_row_without(anchor, mark, y0, y1)` finds a list row still to do: the height of the
  top-most text line containing `anchor` with no `mark` text on the same line, or -1 (the inbox
  macro opens `text_row_without('utc', 'opened', 0.15, 0.86)`, the first item not opened yet).
  Never put account data (player name, IDs, friend codes) in a log line.
