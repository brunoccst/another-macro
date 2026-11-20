# Cat Scratchbook spots: notes from the phone

What the project found while routing the daily cat macro (data: `cat_spots.yaml` and
`known_places.yaml` under `src/another_macro/scenarios/cat_scratchbook/`). The wiki's facts (the
clue table, levels, rewards): [knowledge base](knowledge-base/cat-scratchbook.md).

## Reaching places on the world map

- Trips start at "point zero": the World Map on the Present era with Baruoki's label on screen
  (the first map; every account has it). Each era tab opens where that era's last trip went, so
  labels have no fixed screen spot and are found by OCR.
- **Hidden Village Itoise** (2026-11-08): Present > Expand > Garulea Continent, pan down once,
  then the label; no panel, straight to "Moving to Hidden Village Itoise" and Yes.
- **Satellite Stadium** (2026-11-03): Future > Expand > Garulea Continent (north of the map), pan
  down 0.4, the label at about (0.466, 0.440); no panel, straight to "Moving to Satellite
  Stadium" with Yes at y ~0.59. From the spawn point up to the middle street, left to the next
  junction and up: the cat is on that street.
- **Nazrik, City of Shadows** (2026-11-16): the ??? tab, then Expand -> Underworld, then the
  "Nazrik, City of Shadows" label. Found by hand: from the arrival (right street) walk left to
  the stairs ramp, flick up to the short top street, walk left to the stairs by the feather and
  flick down: the cat is on the feather's street, left of those stairs. The area map scrolls;
  not measured yet.
- **Inanari Plateau** (2026-11-16): not a World Map travel point. Travel to Cat Shrine, walk to
  the lower street's exit (about x 0.508, y 0.616 on Cat Shrine's map) and flick down into the
  plateau. The party then stands on the top street (about 0.532, 0.380); the cat is on the
  bottom street (about x 0.405, y 0.733). The plateau has battles. Needs a `walk_to` that
  flicks; not routed yet.
- **Miglance Castle 2F**: the trip lands on 1F at the foot of the grand stairs; a flick up there
  is 2F (`enter: [U]`).

## Not in the data yet

- The wiki (revision 343355) lists a clue the macro doesn't know: "What a strange place. Is that
  building in the distance a chapel?" → Zero Point - West (Enter from Dimensional Cathedral),
  ??? era. Its first words are the same as the Zero Point - East clue's ("What a strange place.
  Is ..."), so `riddles.yaml`'s key and its screen template for that clue need a longer prefix
  before it can be added. Left for a phone thread.
