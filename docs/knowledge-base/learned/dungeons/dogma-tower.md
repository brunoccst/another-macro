# Dogma Tower: mapping notes

What the project found on the phone while mapping Dogma Tower (data: `dogma_tower` in
`src/another_macro/scenarios/another_dungeon/templates/dungeons.yaml`; macro:
`macros/dungeon/dogma-tower.yaml`). The wiki's facts: [knowledge base](../../external/dungeons/dogma-tower.md).

- **Reach**: the Present tab of the Another Dungeon map, the "Dogma Tower" label.
- **Party**: the game's "please include: Poporo" box: Poporo must be in the party (the wiki
  doesn't list it). Kept as `requires: [Poporo]`.
- **End of a run**: an "Items Obtained ... Continue to skip? End / Skip" box (the dungeon has
  its own currency, Dark Residue). End finishes; Skip spends another key and ticket.
- **"The enemies have disappeared"**: shows on map 2 after about 5 fights.
- **Layouts**: map 1 measured on the phone; maps 2 and 3 from the wiki minimaps (1000x507),
  lined up on the phone. Offsets against the wiki: map 1 -0.005, map 2 -0.042, map 3 -0.029.
  - Map 1 (Basement Entrance): stairs bottom→middle taken at x 0.382-0.395, middle→top-left at
    0.353-0.360.
  - Map 2 (Gloomy / Secluded Corridor): arrival measured at 0.643; middle-row stairs up taken
    at 0.370-0.385, bottom→middle at 0.428-0.434, down at 0.597; the chest at 0.372.
  - Map 3 (Desolate / Remote Space): arrival measured at 0.436; the bottom street is split in two.
  - Map 4 (Basement Mausoleum, boss): no minimap; one chest; the run sweeps it.
- **Walking**: the hold-time table `walk.yaml` was measured on map 1 (250 ms → 0.0076,
  1 s → 0.0244, 4 s → 0.097 of the screen width, then ~0.026/s).
- A run takes about 10-17 minutes.
