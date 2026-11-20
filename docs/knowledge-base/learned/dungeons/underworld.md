# Underworld: mapping notes

What the project found on the phone (data and macro: `macros/dungeon/underworld.yaml`, its `dungeon:` block with the
`cathedral`, `tomb` and `ark` areas). No wiki page has been shared for it yet (see
[the knowledge base](../../external/README.md#missing)), so everything here comes from the phone.

## Reach and cost

- The "???" tab of the Another Dungeon map, its right-hand continent. The tab has no text OCR
  can read: it is tapped by its place (it glows with a White Key).
- Hard only; one run takes 2 green keys. Party 1 by the user's choice.
- Bosses: Demon's Den - Titan Ogre; Dimensional Cathedral - Haunted Queen Himika; Ark of the
  Underworld - Dimensional Destroyer Himika; Mark of God's Tomb - Phantom Eden.

## Demon's Den (mapped 2026-11-15, Hard)

A run: Nazrik (a safe hub, no enemies; every few runs a new merchant walks up and talks) → east
off the screen into Zero Point → the door sign at its lower-right end, "Go to Demon's Den? *You
cannot return..." Yes → Demon's Den (4 chests; the area map shows no chest icons) → the door
sign at the west end of its third street → the boss area (also titled "Demon's Den"): its chest
~1.5 s right of the arrival, the boss's question ~3 s left.

- **Nazrik**: the area map scrolls with the party; Zero Point is east off the bottom street.
- **Zero Point**: enemies come back without end ("The enemies have disappeared" never showed:
  15 fights in 10 min), so they are only fought as met. Horrors = the red star icon, which moves.
  Rows from the top: a short top street, A, B (the party comes in on its left part, ~0.427), C, D
  (the door sign to Demon's Den at its right end). Lined up on the phone; the wiki screenshot the
  user showed matched within ~0.005.
- **Sea-Drift sparkles** in Zero Point change spots every run (seen: (0.476, top), (0.563, A),
  (0.490, C), (0.613, D); on the wiki screenshot: (0.438, A), (0.621, B), (0.505, D), (0.614, D)),
  so they are read off the area map's bag icons. The macro never spends Sea-Drifts (the user does).
- **Demon's Den**: measured 2026-11-15; no barricades in the way. Its enemies didn't run out
  either (15 fights, ~9 min, no banner).

## Dimensional Cathedral (the user's route, measured 2026-11-18)

Nazrik's top-street ladder up → the upper floor's upper street to its left end → the ladder
down to Nazrik's west part → walk left into Zero Point (West) → its left end, up → the door
sign at the upper street's right end ("Go to The Dimensional Cathedral?" Yes). It needs the
ladder the Present Man installs (Embedded Ladder, a path item kept for good).

- Nazrik's upper floor (same title): the party comes up on the middle street (~0.465); the
  ladder down (a bars sign on the ground) is at the upper street's left end, next to a house
  door (its sign nearer the middle took the party indoors).
- Nazrik's west part (same title again): the party comes down a ladder; Zero Point (West) is off
  the screen's left edge (5 s of walking left got there).
- Zero Point (West): the lower street (the party comes in on its right part, ~0.52), up at its
  left end. Enemies come back without end.
- Dimensional Cathedral: endless enemies too (15 fights, ~11.5 min, no banner). Four streets in
  a brick pattern; no chest icons on the area map (chests show up close). Left out: the hidden
  room above row 2's left end (link ~0.332) and the stub at the top street's right end, each
  with a one-time chest (exclusive equipment). A Horror (red star) walks row 3's right part.
  Chest 3: walking right from chest 2, the party stops short at its chest (~0.470) and the "!"
  opens it. The stairs (door sign) to the boss are on the top street.
- Boss area (same title): the chest up right of the arrival, then up to the altar's "!" ("A
  major trial awaits you beyond this point. Proceed?" Yes).
- Run 2026-11-18: 5/5 chests in 6m15s.

## Mark of God's Tomb (mapped 2026-11-17, 2 runs; not yet run by the macro)

- The game's gate reads "Dark Lord's Tomb": the seal in Nazrik's basement takes 1 Mural
  Fragment a run ("... *Uses 1 Mural Fragment." Yes). The user buys them from the Lost Traveler
  for 30 Sea-Drifts; the macro never does. No Zero Point on this route.
- Nazrik: the arrival is the left part of the second street (~0.476, 0.499; walking here is
  slow, ~18 px of area map a second). The way down: the stairs at its right part (0.554, R 4700
  from the arrival) to the third street, its left end (wall) and 600 ms back right, the stairs
  down to a short fourth street, its right end (wall) and 700 ms back left to the manhole
  (basement stairs, up). Measured by hand 2026-11-18.
- Nazrik's basement (same title): a street with the seal's "!" behind an opening near its left end.
- The Tomb: three streets; no chest icons on the area map. A Horror (red star) at the middle
  street's left end. The way on: the door above the top street's middle.
- Boss area: its chest's "!" right by the arrival (a Jadeite once), the boss's question to the left.

## Ark of the Underworld (mapped by hand 2026-11-18, 1 run; not yet run by the macro)

- The portal at the right end of Nazrik's upper floor (Nazrik's top street, the ladder near its
  left end). No item needed: "Go to The Ark of the Underworld?" Yes. Fixed steps in Nazrik
  missed the stairs (holds walk different lengths), so Nazrik is walked by its area map; the
  portal is the ⇔ near the upper floor's middle-street right end. How its question is answered
  (a flick up opens it) is untested.
- Skip the keyhole "!" at the right end of the middle street: a one-time chest behind a Hooked
  Tail Cat Key.
- The Ark: the same endless enemies; five streets in a brick pattern; no chest icons on the area
  map, chests show only when close. A Horror (red star) at row 2's left end (beaten in 1 turn).
  The door (⇔) on row 2.
- Boss area "Ark of the Underworld - Depths": its chest right of the arrival, the boss's
  question left. Its title holds the dungeon's name, so the map is matched by "Depths".

## What the macro's checks are based on

- **Shared titles**: Demon's Den and its boss area share a title, as do Nazrik and its upper
  floor, west part and basement: the run expects the map an exit was just taken toward.
- **Talk mid-run**: a specter walks up and talks on the way to the Ark (2026-11-18), and a new
  merchant every few runs in Nazrik: the run taps through talk.
- **Questions**: "Go to Demon's Den? *You cannot return once you travel to the dungeon." (Yes);
  the seal "Go to Dark Lord's Tomb? ... *Uses 1 Mural Fragment." (Yes), and without one "Not
  enough Mural Fragments" | Cancel: Back doesn't close that box and the purchase guard refuses a
  tap on its Cancel, so the user closes it; the Ark's portal "Go to The Ark of the Underworld?
  ... No / Yes".
- **Sparkle bag icons** are drawn ~0.027 above their spot on the street (2026-11-15). Pickups:
  twice by 2026-11-15 a tap got no banner; once two taps at x 0.46-0.47, right by the party in
  the middle of the screen, got nothing (hence the step away before a retap). The blob finder
  took green plants (x 0.16, 0.74, 0.79) and the party for sparkles (2026-11-15), and the camera
  doesn't keep the party in the middle near a street's end. A bag icon stayed on the area map
  after "no sparkle by" its spot (2026-11-18). OCR reads "Obtained" as "Obtalned" at times.
- **Ladder-down sign** in Nazrik matches its template at 0.86 at scale 1.1, 0.44 at 1.0 (2026-11-18).
