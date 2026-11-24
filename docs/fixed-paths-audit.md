# Fixed paths audit (2026-11-24)

The rule: every macro follows a fixed, pre-mapped way (dungeons: a fixed path to every chest,
sparkle, fish, jar and cat). Discovery (looking around, walking by the area map, sweeps,
self-measuring) is only a fallback when the screen doesn't match the data, and whenever a run
falls back the user is told, in the log **and** in the run's outcome.

This page checks every macro in `macros/` against that rule, as the code stood on 2026-11-24.
Words used below:

- **fixed**: data-driven steps (holds in ms, flicks, taps at known spots), no searching.
- **position read**: one look at the area map (or minimap) to correct where the party is after
  a battle or before stairs. It doesn't choose the way, so it isn't discovery.
- **discovery**: the way itself is found on the phone (walking by the area map, sweeping a map
  in short steps, OCR searching the World Map, measuring a spot).
- **told**: the fallback shows in the run's outcome (stop reason / Statistics), not only the log.

## Summary

| Macro | Fixed path? | Discovery left (per run) | Told when it falls back? |
|---|---|---|---|
| [dogma-tower](../macros/dungeon/dogma-tower.yaml) | Maps 1-3: yes (`path`). Boss map: **no** (no layout, no `steps`) | Boss map swept **every run** (short holds both ways, flicks, until the boss's question / "!") | Yes: "off the fixed path ... map 4: looked around (no layout, boss map)" (**new in this PR**; boss maps were left out before) |
| [burning-beast-kings-castle](../macros/dungeon/burning-beast-kings-castle.yaml) | Yes: maps 1-3 `path`, maps 4-5 `steps` | None planned. Fallbacks only | Yes (see "Fallbacks shared by every dungeon") |
| [moonlight-forest](../macros/dungeon/moonlight-forest.yaml) | Yes: maps 1-3 `path`, boss map `steps` | None planned | Yes |
| [sea-of-ice](../macros/dungeon/sea-of-ice.yaml) | Yes: maps 1-3 `path` (chests, sparkles, fish, jars), boss map `steps` | None planned. A fish not in view is walked to by the area map once | Yes ("<fish> (none in view)") |
| [underworld](../macros/dungeon/underworld.yaml), area `den` | Nazrik + Demon's Den: `path`; boss map `steps` | Zero Point: **one area-map read per run** on arrival (sparkles move every run, Cat Dogu door shows only some runs), then planned steps (`Route.plan`) | Read itself: by design (logged). Off-path walks: yes |
| underworld, area `cathedral` | Cathedral map: `path`; Nazrik step map `steps`; boss `steps` | **Every run**: Nazrik (2 maps) and Zero Point West walked to their exits by the area map (layouts, no `arrival`/`path`) | Yes, every run: "exit (no fixed path)" |
| underworld, area `ark` | Ark map: `path`; boss `steps` | **Every run**: both Nazrik maps walked to their exits by the area map | Yes, every run |
| underworld, area `tomb` | Yes: Nazrik maps by `steps`, Tomb `path`, boss `steps` | None planned | Yes |
| [phantom-crystal](../macros/dungeon/phantom-crystal.yaml) | Per-world `worlds:` routes exist in code, **list is still empty** | **Every world swept** until the routes are mapped (another thread owns this) | Yes: "LOOKED AROUND (no fixed path) in: ..." (PR #157). Route-step nudges (4 short steps when a "!" is late) are logged only |
| [cat-scratchbook](../macros/daily/cat-scratchbook.yaml) | 74/74 spots have a fixed trip (era, place); **18/74** have a fixed walk to the cat | Unrouted spots (56): measured once (CatSurvey), then steered by the area map's feather + hunt. Routed spots: hunt along the cat's drift range (the cat moves) | Measuring: yes. Steering: **new in this PR**: "LOOKED AROUND (no fixed path) for: <place> (no fixed route / left the fixed route / no cat along the fixed route)"; before, only the log and a counter |
| [teleport](../macros/basic/teleport.yaml) | Era tab → remembered continent (Expand) → place label (OCR + panning, since the map opens wherever the last trip left it) | Label search on the right map every trip (by design). Searching the other maps only when the place isn't where it was remembered | **New in this PR**: the stop reason ends "(searched the World Map: not on X; found on Y, remembered)", shown live, and the cat scratchbook's outcome repeats it |
| Way to the dungeon door (`go_to_door`, RiftDoor) | Teleport to Spacetime Rift (continent fixed), then walk left in short steps until the door's "!" shows (≤16 steps) | None (one direction, stops on the "!") | Failure stops the run with the reason |
| [daily-routine](../macros/daily/daily-routine.yaml) | Fixed list of steps | Only what its steps do | Each step's outcome |
| [start-game](../macros/game/start-game.yaml), [stop-game](../macros/game/stop-game.yaml), [daily-ads](../macros/daily/daily-ads.yaml), [shop-ad](../macros/daily/shop-ad.yaml), [inbox](../macros/daily/inbox.yaml), [watch-ad](../macros/basic/watch-ad.yaml), [open-menu](../macros/basic/open-menu.yaml), [open-menu-page](../macros/basic/open-menu-page.yaml), [back-to-field](../macros/basic/back-to-field.yaml), [pick-party](../macros/basic/pick-party.yaml), [battle](../macros/basic/battle.yaml) | Yes: no walking; each recognised screen has fixed taps | Unrecognised screen: wait, Back, or restart the game, then stop on `unknown_timeout_s` | Yes: the stop reason, plus a stuck screenshot |
| [grind-battles](../macros/basic/grind-battles.yaml) | Back and forth by design (its job is to meet battles) | n/a | n/a |

## Fallbacks shared by every dungeon (AnotherDungeon.kt)

Each one adds a line to the run's report, "off the fixed path (walked by the area map): ...",
which is the run's outcome, the live status and `runs.log` (`searched`).

| Fallback | When | Told before this PR | Now |
|---|---|---|---|
| Leg walked by the area map (`offPath`) | Party not on the street the path expects, stairs didn't take, the stop before was switched off | Yes | Yes |
| Chest looked for around its spot (position read, small holds both ways) | Its "!" didn't show within 3 s at the path's spot | **No** (only if it ended in a second try or a miss) | Yes: "looked for the chest at (x, y) around its spot" |
| Chest second try by the area map | A chest missed on the way | Yes | Yes |
| Exit sign looked for nearby (holds, flicks) | The exit's ground arrow didn't show (or didn't move the party on) where the path ends | **No** | Yes: "looked for the exit's sign around (x, y)" |
| `chest L` step walks back | The step reached the boss's question without seeing the chest | **No** | Yes: "step 'chest L' passed the chest" |
| Map without layout swept | Boss map with no `steps` (Dogma Tower), any map with neither | Non-boss maps only | Boss maps too |
| Fish not in view | Walked to by the area map once | Yes | Yes |
| Sparkle / portal leg with no known spot | Party not seen on the area map | Yes | Yes |
| Unknown screen in a dungeon (macro YAML `unknown:`) | 8 unrecognised ticks: a tap near the bottom middle to clear it | No (log only) | Unchanged (it clears talk, not the way; see follow-ups) |

Position reads (`resync`, `readSpot` after battles, minimap before stairs, `area_map_scrolls`
alignment) stay as they are: they correct the followed spot, they don't pick the way.

## Follow-ups for the user to prioritise

Bigger gaps this PR does not start. Each needs the phone.

1. **Phantom Crystal worlds**: map each world's route on the next White Keys (thread "Give
   Phantom Crystal worlds fixed routes" owns it).
2. **Underworld cathedral and ark areas**: give Nazrik (both maps) and Zero Point West an
   `arrival` and a generated `path` (`another-macro dungeon-routes --write`), like the den area.
   Today every cathedral/ark run walks 2-3 maps by the area map.
3. **Dogma Tower boss map**: replace the sweep with `steps` (e.g. `chest L` / `boss L`, like
   Moonlight Forest's boss map), measured on the phone.
4. **Cat scratchbook**: 56 of 74 spots still have no fixed walk. Each run measures the new ones
   (CatSurvey "MEASURED cat spot" lines); import them with `another-macro cat-routes --import`
   after the daily run so the next visit is fixed.
5. **Dungeon `unknown:` handler**: its blind tap after 8 unknown ticks could add a line to the
   run's report (needs a step the YAML can call, or moving it into AnotherDungeon.kt).
6. **Phantom Crystal route-step nudges**: the 4 short nudges when a route's "!" is late are only
   logged; they could join the outcome like the sweeps (same thread as item 1).
