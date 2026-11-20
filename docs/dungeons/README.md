# Another Dungeons: what the project learned on the phone

Notes the project wrote while mapping and running dungeons with the app. Facts from the wiki are
in [the knowledge base](../knowledge-base/README.md) instead
([how Another Dungeons work](../knowledge-base/another-dungeon.md),
[every dungeon](../knowledge-base/another-dungeon-list.md)). The plan for one macro per dungeon:
[dungeon-macros-plan.md](../dungeon-macros-plan.md).

## Per dungeon

| Dungeon | Mapping notes | Wiki facts |
|---|---|---|
| Burning Beast King's Castle | [burning-beast-kings-castle.md](burning-beast-kings-castle.md) | [knowledge base](../knowledge-base/dungeons/burning-beast-kings-castle.md) |
| Dogma Tower | [dogma-tower.md](dogma-tower.md) | [knowledge base](../knowledge-base/dungeons/dogma-tower.md) |
| Moonlight Forest | [moonlight-forest.md](moonlight-forest.md) | [knowledge base](../knowledge-base/dungeons/moonlight-forest.md) |
| Sea of Ice | [sea-of-ice.md](sea-of-ice.md) | [knowledge base](../knowledge-base/dungeons/sea-of-ice.md) |
| Underworld | [underworld.md](underworld.md) | not shared yet |

## Every dungeon (seen on the phone)

- **Entry**: the "!" over the Another Dungeon door in the Spacetime Rift (not the World
  Map) → era tab → the dungeon's label → Hard (green key) / Very Hard (red key) → a box
  "Using Green Key×1 to move to … (Hard)" with Back / Skip / Move. One run always costs
  the dungeon's key(s); the count can't be chosen. The door's "!" is drawn ~10% larger
  than a chest's.
- **Never tap the "+"** beside the key and ticket counters at the bottom of the panel:
  they buy more (premium currency). The third counter ("17/50") is Cat Express Tickets.
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

## The Phantom Crystal Dimension (White Key, `phantom-crystal`)

Seen live on 2026-11-02: door "!" → "???" tab (glows) → "Phantom Crystal Dimension" → Hard
("Using White Key×1", Back / Skip / Move: never Skip) → Move. No enemies, no area map; the
bottom bar has only Menu and Rewards. World I (the first room): no chests; the king cat and the
notice board open the paw stamp menu (Cancel); drawer/window/bed are portals (no
confirmation, a white fade). Other worlds: the standard red-and-gold chests (their "!" shows
only when close), one cat with a "!" (a paw stamp), and portals (a "!" on scenery). The Cape:
4 chests (10,000 Git, Medium Class Scroll ×5, Broken Club, Cat Express Ticket ×1-5), a cat,
and a "!" on the stairs out → Congratulations → autosave → the Another Dungeon map. "!"
bubbles are drawn at different sizes per world (scales 0.8-1.35 of the template).

## 5. Test

Build (CI `android.yml`), install, re-allow screen capture, and run from next to the
Another Dungeon door with the live log on. Check the run report at the end (time,
chests n/total, items) and `adb logcat -s MacroForge:I` for "no chest found",
"gave up walking" or many looks at the same stairs: those mean a position in the data
is off. Fix it and run again.
