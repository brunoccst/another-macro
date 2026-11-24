# Cat Scratchbook: what the project learned

What the project found while routing the daily cat macro (data: `cat_spots.yaml` and
`known_places.yaml` under `src/another_macro/scenarios/cat_scratchbook/`). The wiki's facts (the
clue table, levels, rewards): [knowledge base](../external/cat-scratchbook.md).

## Reaching places on the world map

- Every trip is the basic macro teleport ([world-map.md](world-map.md)). Until 2026-11-23 the fixed
  routes started at "point zero" (the Present tab with Baruoki's label on screen); each era tab
  opens where that era's last trip went, so labels have no fixed screen spot and are found by OCR.
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

## The world map

How the World Map behaves (era tabs, Expand, panning, islands): [world-map.md](world-map.md),
behind the basic macro teleport that makes every cat trip.

- Karek Swampland (Enter from Baruoki) is reached from Baruoki's east end; the west end is a dead
  end (2026-11-12).
- A cat's spot is fixed (the user): the data wins over the area map's icons.

## The feather bubble (behind `FeatherFinder.kt` and `CatSurvey.kt`)

- Some maps (e.g. tree-top villages) are zoomed further in or out than the map the template was
  cut on, so it is matched at five scales.
- The minimap shows the cat's feather icon too: five taps went to it at Zarbo (2026-11-15).
- Over Snake Skull Mescita's sunset a fixed brightness threshold turned the whole sky white
  (2026-11-12); on that map's half-size frames bubbles scored 0.81-0.84 and scenery at most 0.72.
- In Ratle a big grey quill-in-a-circle icon on the area map matches the feather template nearly
  as well (2026-11-03), and the cat's bubble shows right at the arrival. A hold too soon after
  the arrival there walked nothing ("hold R 539 ... view change 0.3", 2026-11-03).

## Not in the data yet

- The wiki (revision 343355) lists a clue the macro doesn't know: "What a strange place. Is that
  building in the distance a chapel?" → Zero Point - West (Enter from Dimensional Cathedral),
  ??? era. Its first words are the same as the Zero Point - East clue's ("What a strange place.
  Is ..."), so `riddles.yaml`'s key and its screen template for that clue need a longer prefix
  before it can be added. Left for a phone thread.

- Four spots the app's survey measured but couldn't route (read back with `cat-scratchbook`
  `mode: measured`, 2026-11-24). The phone keeps these measurements and won't measure them
  again, so each needs measuring by hand while its clue is up:
  - Cat Shrine (survey 2026-11-24): neither the party marker nor the feather was on a street
    the survey read (another floor?).
  - Hot Springs (2026-11-17) and Snake Skull Mescita (2026-11-23): no feather on the area map
    (a quest marker may hide it), and the party marker on no street.
  - Izana (2026-11-19): the party marker was read at the area map's top-left corner, so the
    arrival is wrong; the cat was read at (0.653, 0.449).

## Measurements per spot

What was noted while each spot's data in `cat_spots.yaml` was measured (by hand or by the
app's survey); the data itself stays in the YAML.

### Baruoki

- measured by hand on the phone 2026-11-20: four streets (a short diagonal path up to the fish
  icon at the top left is left out); the feather sits on the long middle street, left of the arrival
- `arrival`: the trip of 2026-11-20 landed on the middle street at x 0.508; the survey's trip of
  2026-11-19 read the party on the lower street at (0.306, 0.588). The trip doesn't always land on
  the same spot, so the route walks the layout from wherever the party is (`CatRoute.walk`)
- `cat`: feather drawn at (0.450, 0.435); the survey read x 0.435-0.448
- `walk_scale`: R 3000 from rest walked 0.0618
- 2026-11-20 run missed this cat: the feather wasn't read on the area map (cause not found; the
  icon matches the feather template at 0.89 on a screenshot of the same map), so the steering
  walked to a quest marker instead. With the fixed route the steering is only the fallback.

### Keruri Highroad

- not a world-map travel point. The user (2026-11-22): travel to Tower of Time and leave by the
  door just below the spawn point (flick down); the party comes out at the north-west of Keruri
  Highroad. Then right, down, right, down, left: the cat is at the end of that road.
  `cat_spots.yaml`: `place: Tower of Time`, `enter: [D]`
- 2026-11-22 on the phone: the trip and the door worked; the party came out at (0.245, 0.322) on a
  short street at the top left that the survey didn't read (added by hand, with its stairs down at
  its right end, x 0.293). The macro's map steering didn't reach the cat; by hand: right to the
  stairs, down, right to x 0.339, down, then left. The cat was by the feather at (0.255, 0.559) and
  was found. `walk_scale` 0.85 is an estimate (not measured)
- tried first (2026-11-22) from Palsifal Palace: the trip lands inside on Palsifal Palace 1F; its
  main door (exit icon below the middle of the bottom street, flick down) leads to Derismo
  Highroad, whose top-left exit leads to Corinda Plains, not to Keruri Highroad

### Nazrik, City of Shadows

- measured by hand on the phone 2026-11-22 (positions on the area map as it opens at the arrival)
- the world map writes the place's label on two lines ("Nazrik, City of" / "Shadows"); the trip
  matches the two lines joined (missed 2026-11-21 before that)
- the trip lands on a lower floor at (0.689, 0.395); the cat's street is drawn at the same height
  just to the left (x 0.52-0.584) but is another floor, reached over the upper street:
  - walk left to the alley at x 0.627 (the lower street slopes down a ramp on the way) and flick up
    at the ground arrow
  - on the upper street (y about 0.29, partly a ramp) walk left to x 0.569 and flick down at the
    ground arrow
  - the cat starts at x 0.548 on that street (survey reads 0.543-0.556)
- from the arrival floor the feather is drawn faded, and the feather template misses it after the
  first look, so the map steering lost the cat on 2026-11-22 (found by walking the floors by hand)
- the area map scrolls up and down with the party (up to about 0.03), not sideways
- `walk_scale`: L 2000 walked 0.040 and L 3000 on the upper street 0.058 (0.84 and 0.82)
- a flick right after closing the area map can be ignored; the second one took

### Snake Neck Igoma (Enter from Konium)

- measured by hand on the phone 2026-11-23 (positions on the area map as it opens on the arrival
  street): travel to Konium, walk left off its middle street's west end, through a short unnamed
  field map, into Snake Neck Igoma. The party comes in on the long street at y 0.61 (right part)
- two junctions go up from that street to the left of the arrival. The first one (x 0.61) is
  stairs (a ground arrow; flick up) to the feather's street (y 0.497, x 0.437-0.62); on the area
  map it is drawn a little greyer, as another floor. The second one (x 0.548) climbs past that
  street, on another level, to the top street (y 0.383) and does not reach the cat. The survey's
  street mask didn't read the greyer street at all
  (11-23 survey notes: "the feather is on no street read (another floor?)"), so the map steering
  took the second junction and got stuck (the user's report, 2026-11-23)
- the 11-23 survey read the party at (0.640, 0.379), on no street: not the marker. The walk across
  ends wherever its long hold ends, so the arrival isn't fixed; the route reads the party on the
  layout first
- `cat`: the feather at (0.473, 0.497); the cat was found there by hand 2026-11-23 (hunt mode)
- `walk_scale`: L 3000 walked 0.066 (0.93)

### Sarupa

- on the Antiquity tab, Migleina Continent, at the map's top right: north of Vulga Mountain Road,
  next to Vega Forest and Charol Plains (phone, 2026-11-20). The map scrolls further up than the
  view a trip leaves it at, so the label shows only after the sweep reaches the top edge
- 2026-11-20 run missed it: the sweep's drags to the top-left corner started on place labels
  (Migleina's west is dense with them) and didn't move the map, so the top rows were never seen

### Last Island

- measured by hand on the phone 2026-11-18: the streets are pale lines over white sand, which the survey's street mask can't tell apart (it read none under the party or the feather)
- `arrival`: the survey's party read, on the long middle street
- `cat`: same street; feather drawn at y 0.413, x 0.592-0.610 over four reads
- `walk_scale`: R 1500 from rest walked 0.0313 (3000 holds kept meeting battles)

### Acteul

- `continent`: the user: Antiquity > Migleina Continent > Acteul (middle of the map)
- measured on the phone 2026-11-03 (grey stone floor: the street mask is noisy, so approximate)
- `arrival`: the same both trips
- `cat`: the user: left to the next junction, up that street; feather drawn at y 0.407
- `drift`: x 0.386-0.401 over ~2 min
- `walk_scale`: R 3000 from rest walked 0.0713

### Unigan

- `continent`: learned on the phone (known_places.yaml)

### Zarbo

- `continent`: phone 2026-11-16: the map's far top-right corner, next to Rucyana Sands
- measured on the phone 2026-11-16: four streets; the stairs down from the arrival street take a flick (a down hold does nothing)
- `arrival`: top street, by the door
- `cat`: second street; feather drawn at y 0.406
- `drift`: not timed yet: the hunt found it at once
- `walk_scale`: R 3000 from rest walked 0.0657

### Rucyana Sands

- `continent`: next to Zarbo (phone 2026-11-16)

### Miglance Castle 2F

- `continent`: on the Present map with Baruoki (2026-11-08)
- the trip lands on 1F at the foot of the grand stairs: a flick up there is 2F
- measured on the phone 2026-11-08 (2F, a small map: two streets joined by two stairs)
- `links`: the right one taken at 0.530
- `arrival`: where 1F's stairs put the party
- `cat`: feather 0.589 / 0.599 / 0.595 over ~3 min, right end of the top street
- `walk_scale`: L 2000 from rest walked 0.045 (0.0480 in Dogma Tower)

### Ratle

- `continent`: the user: Antiquity > Migleina Continent > Ratle
- measured on the phone 2026-11-03 (icons cover parts of the map, so approximate). A big grey quill-in-a-circle icon at (0.59-0.61, 0.325) matches the feather template nearly as well: the app takes the feather nearest the cat's start
- `arrival`: 2nd trip 0.4753
- `cat`: just right of the arrival (the user: its bubble usually shows at once); feather drawn at y 0.532
- `drift`: x 0.481-0.496 over ~1.5 min
- `walk_scale`: R 3000 from rest walked 0.063 (0.0709 in Dogma Tower)

### Nilva

- `continent`: on the map the Future tab opens, left side (phone 2026-11-16)
- `sub`: its label opens a panel: Nilva / Macminal Museum / Museum - Library
- measured on the phone 2026-11-16: four streets under the museum
- `arrival`: third street
- `cat`: second street, its right end; feather drawn at y 0.416
- `drift`: not timed yet
- `walk_scale`: R 3000 from rest walked 0.0621

### Laula Dome

- `continent`: phone 2026-11-16: bottom right of the Future map, under Expand
- measured on the phone 2026-11-16: six streets in steps under the dome
- `arrival`: fourth street
- `cat`: same street, its right end (the next screen, by the wheat field)
- `drift`: not timed yet
- `walk_scale`: R 3000 from rest walked 0.0653

### Konium

- `continent`: learned on the phone (known_places.yaml)
- `place`: on Snake Bone Island: its label on the continent map opens a panel with Konium (2026-11-03)

### Purgatory

- `continent`: on the map the Antiquity tab opens (phone 2026-11-16)
- measured on the phone 2026-11-16: the cat starts right by the arrival (its bubble shows at once)
- `arrival`: middle street
- `cat`: same street; feather drawn at y 0.456
- `drift`: seen just right of the arrival
- `walk_scale`: R 3000 from rest walked 0.0623

### Hidden Village Itoise

- `continent`: learned on the phone (known_places.yaml)
- measured on the phone 2026-11-08: four streets; down the stairs by flicks (a down hold does nothing here) middle->lower went down at 0.4607, lower->bottom at 0.5065 in the live walk
- `arrival`: on the middle street, by the inn
- `cat`: bottom street; feather reads 0.492, 0.483, 0.480, 0.486, 0.495
- `walk_scale`: holds of 1500/2200/3000 ms walked 0.86/0.92/0.94 of walk.yaml

### Ikarga

- measured by the app's survey 2026-11-17 (map 'Ikarga'): check it, then test it live
- feather x reads: [0.519]

### Sky Fortress Eeza

- measured by the app's survey 2026-11-18 (map 'Sky Fortress Eeza'): check it, then test it live
- survey note: import: arrival: joined the street at y 0.389 across the icon (0.35-0.651)
- survey note: import: cat: joined the street at y 0.5 across the icon (0.349-0.482)
- feather x reads: [0.41, 0.4, 0.41, 0.398, 0.408]

### Zami

- measured by the app's survey 2026-11-18 (map 'Zami'): check it, then test it live
- survey note: screen-edge streets dropped by hand: [[0.482, 0, 0.032], [0.482, 0.879, 0.93], [0.499, 0, 0.069]] and the stairs between them
- survey note: import: arrival: stretched the street at y 0.559 to 0.5
- feather x reads: [0.575, 0.579, 0.571, 0.578, 0.58]

### Gadaro

- `continent`: on the map the Antiquity tab opens (found without Expand, 2026-11-16)
- measured on the phone 2026-11-16 (two screens of the same street: the cat is on the left one)
- `arrival`: main street, under the inn sign
- `cat`: same street, by the well; feather drawn at y 0.514
- `drift`: it wanders both ways around the well
- `walk_scale`: R 3000 from rest walked 0.0625

### Satellite Stadium

- `continent`: the user: Future > Garulea Continent > Satellite Stadium, north of the map
- measured on the phone 2026-11-03 stairs where the flicks took them in the live test (bottom->middle at 0.509 after missing at 0.503/0.497, middle->top at 0.447 after missing at 0.453)
- `arrival`: 3 reads 0.4997-0.5000
- `cat`: feather 0.452-0.458 at y 0.348, above its street
- `drift`: x 0.442-0.456 over ~2 min
- `walk_scale`: L 2000 walked 0.048, L 1500 0.035 (table 0.0480 / 0.0362)

### Elzion (Gamma District)

- `continent`: learned on the phone (known_places.yaml)
