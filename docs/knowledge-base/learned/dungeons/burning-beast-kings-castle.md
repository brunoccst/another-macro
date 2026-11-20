# Burning Beast King's Castle: mapping notes

What the project found on the phone (data and macro: `macros/dungeon/burning-beast-kings-castle.yaml`, its `dungeon:` block). The wiki's facts:
[knowledge base](../../external/dungeons/burning-beast-kings-castle.md).

- **Reach**: the Present tab, "Parallel Time Layer" opens a smaller map with the castle; its
  label wraps onto two lines ("Burning Beast King's").
- **Party**: the game's "please include" box asks for Deirdre (seen 2026-11-18).
- **Layouts**: the wiki minimaps (2160x1080) line up with the phone's area map with no offset:
  x = (x_wiki + 120) / 2400, y = y_wiki / 1080. Checked 2026-11-08: map 1 right wall 0.610,
  stairs 0.540 and 0.476; map 2 arrival 0.579, stairs 0.532 / 0.628 / 0.405 / 0.372, chest taken
  at 0.382 (left wall); map 3 arrival 0.388.
- **Way to the next map**: a grand staircase in the background with a round door sign
  (template `door_sign`): tap it. The party arrives in front of the next map's foreground door.
- **Throne Room** (map 4): no enemies. The chest's "!" shows at the arrival; up the middle, a
  pillar of light whose "!" leads to Deliverance Sword (seen 2026-11-08). Its "!" can show only
  ~5 s after arriving.
- **Deliverance Sword** (the boss area): no chest and no enemies. Walk left from the arrival to
  "A major trial awaits you beyond this point. Proceed?" (Yes). The "!" on the right only leads
  back to the Throne Room.

## What the macro's checks are based on

- **Map titles**: the castle's maps carry their own titles ("Beast King's Castle Front Guard
  Tower"), not "<dungeon> - <map>", and share most of their words, so only a whole-title match
  counts. As a map opens its title shows over the field ("HARD" over "Beast King's Castle
  Watchtower").
- **Encounters** (three traces, 2026-11-12): the walking view (change 10-24 a frame) stops dead,
  then a white flash (brightness ~65 → 139) about 2 s after the party stops, then a still screen
  (change 0-6, single shimmer frames of 7-11) for seconds before the battle HUD. An encounter
  started just as a hold ended about every other flick. Waiting for a battle whenever the view
  stood still cost ~3 s at every junction. The party once did nothing for ~1 s right after a
  chest's popup. The followed position stayed within ~0.005 of the area map's, but once was 0.05
  off after three battles in a row (all 2026-11-12).
- **Minimap**: draws the area map's layout at 0.49 of its scale (both axes), streets light grey
  on dark red (castle screenshots 2026-11-12).
- **Throne Room**: the light pillar and fires behind the "!" need the bubble's inside-only
  template; the chest's "!" was missed once in 3 tries over ~5 s (2026-11-12), so the run
  watches up to 8 s. A place's name over a door read twice was taken for a chest banner before
  (2026-11-12).
- **The end**: the boss's result screens took ~25 s (2026-11-12); a 2.0 s wait between reads made
  them take ~1 min.
