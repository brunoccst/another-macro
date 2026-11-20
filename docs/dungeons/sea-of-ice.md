# Sea of Ice: mapping notes

What the project found on the phone (data: `sea_of_ice` in
`src/another_macro/scenarios/another_dungeon/templates/dungeons.yaml`; macro:
`macros/dungeon/sea-of-ice.yaml`). The wiki's facts:
[knowledge base](../knowledge-base/dungeons/sea-of-ice.md).

- **Reach**: top left of the Migleina continent on the Antiquity map: "Glacial Place" opens a
  box with "Sea of Ice". Very Hard only (red key). The game asks for Sheila in the party.
- **Area map**: a see-through overlay that scrolls up and down with the party (it draws ~0.035
  higher with the party two streets down), so each look lines the layout up with the streets
  it shows (`area_map_scrolls`). The streets are a see-through grey-blue over a dark sea.
- **Camera**: the scene is dark and the camera waits before following: a walk's first second
  looks still on screen (`camera_lags`; it was taken for a late start and holds overshot a
  stairs flick, 2026-11-15).
- **Layouts** measured on the phone 2026-11-15 (Very Hard), y as the area map shows it with the
  party on the arrival street.
- **The way on**: a round sign with an open door (template `door_sign_round`) over a ground
  arrow; map 3's is over a glowing portal. On map 1 the sign shows with the party a little
  right of the stairs down.
- **Pickups** (read off the area map 2026-11-17): sparkles = T. Black Pearls (as many show as
  the Pip count at the map's start; a bag icon on the area map); fish = the area map's fish
  icons (a black fish in a coloured glow, one harpoon throw each); jars = its jar/shrimp icons,
  bait spots the user sets (the macro only empties a full jar, never sets or buys bait).
  - Map 2: a third fish icon on a short stub above the second street at 0.476 never had a fish
    from that street in two runs (2026-11-17): left out. The bottom street is split in two; its
    left part is reached only from the middle street's stairs at 0.411.
  - Map 3: the second street is two parts joined by a slope (right part 0.390, left 0.419),
    taken as one street. The right part of the third street is reached only from below (stairs
    at 0.580). The stairs up to chest 1's short street were taken at x 0.414 and 0.415 in two
    runs (2026-11-18; 0.420 before). A fish icon at (0.483, 0.642) never had a fish in two runs.
- **The Abyss** (boss): no area map and no enemies. The chest's small "!" is ~3 s of walking left
  of the arrival, the boss's question ("A major trial awaits you beyond this point. Proceed?") a
  little further. Short steps with a look each took ~16 s to the chest (2026-11-15), so one 2 s
  walk goes first.
- **Boss**: skill 4 of party slot 1 beat the Abyssal Queen in 4 of 4 runs (2026-11-17), about
  14-15 min a run.
