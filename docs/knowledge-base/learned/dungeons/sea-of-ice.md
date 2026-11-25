# Sea of Ice: mapping notes

What the project found on the phone (data and macro: `macros/dungeon/sea-of-ice.yaml`, its `dungeon:` block). The wiki's facts:
[knowledge base](../../external/dungeons/sea-of-ice.md).

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

## What the macro's checks are based on

Measurements and sightings behind thresholds in the code (`AnotherDungeon.kt`, `Route.kt`,
`AreaNav.kt`, `MiniMap.kt`).

- **Dark field**: brightness ~35, dimming to 29-30 in places with no battle (the castle's field
  is ~65), so the "dark screen" battle check is relative. With a 450 ms "stood still" limit,
  long holds ended early 4 times in one run (2026-11-15), with 900 ms twice (5150 and 5813 ms
  holds), hence 1.5 s here. A short hold can show no walking frame at all; a ~1 s hold onto a
  chest waited ~2.5 s for a battle about every other time until early stops were counted only
  near the end. A dark-scene walk can also stop short of a fish's spot (a false "stood still",
  2026-11-17).
- **Area map streets** read from S 20/V 135 grey to S 90/V 80 blue; the right layout fits
  0.89-0.95, another map's up to 0.65 (2026-11-15). The see-through minimap gave a wrong
  "clear" read after a flick that missed the stairs (2026-11-15), so only the area map is
  trusted here.
- **Sparkles** (T. Black Pearls): only as many show as the map's Pip count. The star finder
  (near-white green core in a teal glow) was measured on 15 screenshots of Sea of Ice and Zero
  Point (2026-11-15): every sparkle sat at y 0.53-0.58; a green thing at y 0.33 was no sparkle.
  After all 5 pickups of a run the map showed the party within ~0.01 of the data (2026-11-15).
  From 2026-11-17 on, runs found none at all: on 2026-11-24 every one of the 6 sparkle stops had
  none after its full look (3 s, then a step left and two right: ~9-12 s a stop, ~1 min a run).
  So after 2 empty stops in a row with none picked up, a stop gets a 1.5 s glance only.
- **Fish**: a tap from far off threw nothing (2026-11-17). A miss says "It nimbly dodged the
  harpoon!", a catch shows a card with the fish's name over "Size ... cm"; a "Double Get" shows a
  second card. The throw doesn't move the party (within ~0.009, 2026-11-17). The fish finder was
  measured on 6 screenshots with a fish and 16 without (2026-11-17): fish blobs had area 900-1530
  (1024-wide units), fill 0.53-0.57, ring saturation 157-197; dark scenery filled under 0.4.
  On 2026-11-24 the map 2 fish by (0.524, 0.586) was on screen (the user saw it) but turned down:
  area 820-1558, fill 0.58-0.62, ring saturation only 84-85 (a dimmer glow over that water). The
  finder now also takes a ring of 70+ when the blob fills 0.53+ of its box. The same day the user
  saw the map 2 fish by (0.430, 0.474) on screen while the macro reported none.
- **Proof level-up**: fishing in this dungeon levels up an item the game calls "the proof" (the
  user, 2026-11-23). After a catch the game can show a line of talk such as "Leveled the proof
  14 -> 15!" over the field, which waits for a tap. In the scheduled run of 2026-11-23 it came
  right after closing a catch card on map 1; the macro walked behind it for ~8 minutes (area map
  unreadable), skipped map 1's second chest and only tapped it away at the exit.
- **Bait jars**: the user sets bait at a shrimp icon and the jar fills in about a day. Standing
  by the jar shows a round bubble with a jar icon (2026-11-18); the party pulls it up (~2 s),
  then "You've caught a total of N fish." (maybe a catch card first).
- **Hold error** (2026-11-25 run): after a sparkle stop's look-around (a step left, two right) the party stood ~0.02 right of where the path
  followed it, so the next stairs' first flick missed and the chest after them was ~0.013 out of range of its "!". Holds also
  end 0.01-0.02 short or long of their target on their own (map 1 stairs 0.383, map 2 stairs 0.556 took 2 tries).
- **Misses seen**: a missed flick left the party a street above a chest (2026-11-15); the exit
  sign showed only a moment after the party stopped and was lost by the holds into the street's
  end (2026-11-15).
- **The Abyss**: drawn zoomed out, so its "!" is small (a smaller bubble template). Its chest step
  once met the boss's question first, and a "!" tapped then was a travel point outside
  (2026-11-16). After the boss, back outside, a "Travel to <place>? No / Yes" box came from a "!"
  next to the party (2026-11-16): the macro answers No.
