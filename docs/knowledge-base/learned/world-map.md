# The World Map: what the project learned

How the World Map behaves on the phone, behind the basic macro `teleport` (`logic/Teleport.kt`),
which makes every trip of the other macros (the cat scratchbook's cats, the dungeon macros' way
to the Spacetime Rift). Places reached one by one: [cat-scratchbook.md](cat-scratchbook.md#reaching-places-on-the-world-map)
and [dungeons/README.md](dungeons/README.md).

## Eras and continents

- **An era tab can be greyed out** (the user, 2026-11-23): a continent need not exist in every
  era. Zerberiya is on the Antiquity tab only (as far as the user knows); with the view on
  Zerberiya in Antiquity, every other era's tab is greyed out and can't be picked. Then the
  continent comes first (Expand → a continent the other era has too), then the era. The reverse
  also happens: a continent only one era has is picked after that era's tab. The teleport takes
  a tab whose tap leaves the view unchanged as greyed out and switches to Migleina Continent
  first (an assumption, not yet seen on the phone: that Migleina is on every era's list).
- **Expand** (seen 2026-11-23) shows the era's whole world with one label per continent
  ("Zerberiya Continent", "Migleina Continent", "Garulea Continent" on Antiquity); tapping a label
  opens that continent's map. The era tabs are Antiq., Pres., Fut., "???" and a fifth, "Hollow".
  On the phone's account (2026-11-23): Antiquity has Zerberiya, Migleina and Garulea; Present
  and Future have Migleina and Garulea; "???" has Illusion World and Underworld.
- Every place label of the account was mapped on 2026-11-23 with the teleport's `map_places` mode
  (`known_places.yaml`, about 120 places on 9 maps). Only unlocked places show, so the list grows
  as the story goes on: run `map_places` again, or let trips learn them (they search the map and
  remember the continent). OCR reads the Expand button's label in many garbled ways ("Dxpand",
  "Ppand"); region names ("Barbera Untrodden Region") and quest markers ("The Riftbreaker") are
  labels too but not travel points, so they were left out of the list.
- The "???" tab's Expand list has Illusion World (the Spacetime Rift) and Underworld (Nazrik,
  Zero Point), and opens on the continent of its last trip ([dungeons/README.md](dungeons/README.md)).

## The map itself


- Baruoki is the game's first map, so every account has it (other places, and even the Expand
  button, only show once the story unlocks them). It sits by the Present map's west edge: the
  Present tab opens with it at about (0.185, 0.509) and a drag doesn't move the map further
  (2026-11-03).
- Each era tab opens where that era's last trip went (2026-11-03). The tabs' lower edge
  (y ~0.18) sits over the map: a place label under it takes the tap and opens "Moving to ..."
  (seen with Moonlight Forest), so the tabs are tapped on their top half. "???" (Nazrik, Zero
  Point: the Underworld) is the fourth tab, right of Future (2026-11-16).
- A drag that starts on a place label doesn't move the map: a quest marker's "The Riftbreaker"
  label sat where a sweep row's drags started, the row stood still and Zarbo was missed
  (2026-11-17).
- Continent maps are big at the zoom a trip leaves them at: three corner drags left the view
  below Migleina's top edge and missed Zarbo (top right, 2026-11-16); an island's label sat below
  a three-row sweep (2026-11-12); Migleina is wider than 4 pans (Zarbo at its east end,
  2026-11-15).
- Islands (Snake Bone Island, Astra Island, Remulia) are one label on a continent's map that
  opens a panel of their places (the island list: anothereden.wiki/w/Map). Konium, on Snake Bone
  Island, is on Migleina Continent's map (2026-11-03).
