# Another Dungeons: what the project learned on the phone

Notes the project wrote while mapping and running dungeons with the app. Facts from the wiki are
in [the knowledge base](../../README.md) instead
([how Another Dungeons work](../../external/another-dungeon.md),
[every dungeon](../../external/another-dungeon-list.md)). The plan for one macro per dungeon:
[macros.md](../../../macros.md#another-dungeons-one-macro-per-dungeon). The White Key dimension:
[phantom-crystal.md](../phantom-crystal.md).

## Per dungeon

| Dungeon | Mapping notes | Wiki facts |
|---|---|---|
| Burning Beast King's Castle | [burning-beast-kings-castle.md](burning-beast-kings-castle.md) | [knowledge base](../../external/dungeons/burning-beast-kings-castle.md) |
| Dogma Tower | [dogma-tower.md](dogma-tower.md) | [knowledge base](../../external/dungeons/dogma-tower.md) |
| Moonlight Forest | [moonlight-forest.md](moonlight-forest.md) | [knowledge base](../../external/dungeons/moonlight-forest.md) |
| Sea of Ice | [sea-of-ice.md](sea-of-ice.md) | [knowledge base](../../external/dungeons/sea-of-ice.md) |
| Underworld | [underworld.md](underworld.md) | not shared yet |

## Every dungeon (seen on the phone)

- **Entry**: the "!" over the Another Dungeon door in the Spacetime Rift (not the World
  Map) → era tab → the dungeon's label → Hard (green key) / Very Hard (red key) → a box
  "Using Green Key×1 to move to … (Hard)" with Back / Skip / Move. One run always costs
  the dungeon's key(s); the count can't be chosen. The door's "!" is drawn ~10% larger
  than a chest's.
- **Never tap the "+"** beside the key and ticket counters at the bottom of the panel:
  they buy more (premium currency). The third counter ("17/50") is Cat Express Tickets.
- **Key cap and ad rewards** (the user, 2026-11-20): at the cap (10/10 green, 5/5 red on the
  user's account) the player can't receive more key cards, so a key card won from a daily ad
  chest is lost. So the daily routine spends the keys before the ads (`daily-routine`) and
  again after them, for the keys the ads gave.
- **Skip** (Cat Express Ticket + the key): no confirmation, straight to "Congratulations"
  (rewards, Light/Shadow points) and the chest/enemy loot. Greyed out with no tickets.
- **End of a run in a dungeon with its own currency** (Dogma Tower: Dark Residue): an
  "Items Obtained … Continue to skip? End / Skip" box. Always **End**; Skip spends
  another key and ticket (that's where a repeat-runs loop would go). Older dungeons
  (e.g. Miglance Castle) don't show it.
- **Light/Shadow**: a character who earns Light/Shadow points there (e.g. in Dogma
  Tower) adds an animation after the rewards; it closes with a tap like the rest.
- **White Key** (10% per run, also with Skip): a notification at the end; the Another
  Dungeon door then shines and the "???" tab glows. The run stops and says so.
- **On arrival** the daily ad offer can pop up ("Watch Video" is free).
- **Chests** have different art per dungeon but always show the "!" bubble when the party
  is close; the banner reads "Obtained <item> x<n>."
- **"The enemies have disappeared"** flashes for a moment at the top after a map's last
  enemy (Dogma Tower map 2: after ~5 fights): read the top of the screen right after each
  won fight.
- **The boss map** is usually one street going left from where the party arrives, with the
  chest between the arrival and the spot that asks "A major trial awaits you beyond this
  point. Proceed?" (Yes). Not a rule: check each dungeon.
- **The area map** hides unexplored parts and draws each map a little left or right; the
  party marker pulses (smaller/bigger, fading); chest icons on walked parts are gold too.
  Never tap to open or close it while a battle is up (the close tap at the bottom middle
  lands on the 4th character's portrait).
- **Enemy names** show while a skill list is open (and in the battle intro): that's how the
  boss is told apart.
- **Walking**: the party accelerates from rest, so distance isn't time × speed. The hold-time
  table is `walk.yaml` (measured in Dogma Tower map 1: 250 ms → 0.0076, 1 s → 0.0244,
  4 s → 0.097 of the screen width, then ~0.026/s). After a battle the party stands still, so
  the rest of a walk is held again from rest (the app turns the time held before the battle
  into a distance with the same table). Stairs are taken from within ~0.005 with a quick
  flick. A stop by a street's end is walked into the wall, which puts the party exactly there.
  The run opens the area map only to re-read the party's spot: once after a battle cut a
  walk short (before the next stairs), after looking around for a chest, and when a flick
  seems to have done nothing (if the party is still at the stairs it flicks again). A chest
  that isn't found logs where the map shows the party: use it to fix the chest's x.
- **Re-measuring the walk**: hold right from rest for 0.25-4 s and read how far the party
  marker moved on the area map; write the rows into `walk.yaml` and run
  `another-macro dungeon-routes --write`. `battle_lag_ms` (time held counted up to the last
  frame with the field minus this) and `flick_change` (how much the screen must change after
  a flick before it's taken as a street change) are tuned live from the log lines
  "walk ... stopped after" and "flick U: screen change N".

## Test

Build (CI `android.yml`), install, re-allow screen capture, and run from next to the
Another Dungeon door with the live log on. Check the run report at the end (time,
chests n/total, items) and `adb logcat -s MacroForge:I` for "no chest found",
"gave up walking" or many looks at the same stairs: those mean a position in the data
is off. Fix it and run again.

## What the macro's checks are based on

- **The panel's counters**: green keys "/10" or "/9", red keys "/5", Cat Express Tickets "/50";
  OCR reads 0 as O at times ("16/5O") and can join two counters ("5/515/50"). A counter OCR
  misses shifts the rest, so each is told by its denominator.
- **Labels on the Another Dungeon map**: the map keeps its last pan, so a label can sit half
  hidden under the era icons ("Dogma TovSealed"), cut by the left edge ("lel Time Layer") or under
  the app's floating buttons ("Dogma Towe" at x 0.93). A drag that starts on the islands in the
  middle (0.49, 0.65) doesn't pan the Present map (2026-11-11); the Present map can't pan the
  Parallel Time Layer label further left than x 0.78 (2026-11-18).
- **The "???" tab** opens on the continent of its last trip (Underworld after an Underworld run);
  the Spacetime Rift is on Illusion World. Its Expand lists two continents: Illusion World and
  Underworld (2026-11-22). From a Present place (Kurosagi Castle) the map opens on that era's tab;
  a run's "???" tap there once didn't switch the tab (2026-11-21). The Phantom Crystal Dimension's
  label sits on Illusion World too: after an Underworld run its entry missed it until it switched
  continents (2026-11-23). The user's rule (2026-11-23): every dungeon macro gets to the door by
  itself from anywhere on the field (option `go_to_door`, on by default). A "!" over a tree NPC near the door sits higher than
  the door's.
- **"please include" box**: it sits over the party list, so the next OCR line is a party member or
  the screen's title "Dungeon" (2026-11-18). A place's box ("Parallel Time Layer", Sea of Ice's
  "Glacial Place") can stay open over the panel, so the close missed it (2026-11-18).
- **Safety words**: talk in dungeons uses "buy" without any sale, so "buy " is not one of the
  words the dungeon run stops on.
- **Walk table** (`walk.yaml`): right holds measured in Dogma Tower map 1 on 2026-11-02.
