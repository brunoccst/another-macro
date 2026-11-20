# Phantom Crystal Dimension: what the project learned

Notes the project wrote while building and running the `phantom-crystal` macro
(`PhantomCrystal.kt`). The wiki page (Phantom Crystal Dimension) has not been shared yet (see
[the external sources](../external/README.md#missing)), so everything here comes from the phone
and from the user.

## Seen on the phone

Seen live on 2026-11-02: door "!" → "???" tab (glows) → "Phantom Crystal Dimension" → Hard
("Using White Key×1", Back / Skip / Move: never Skip) → Move. No enemies, no area map; the
bottom bar has only Menu and Rewards. World I (the first room): no chests; the king cat and the
notice board open the paw stamp menu (Cancel); drawer/window/bed are portals (no
confirmation, a white fade). Other worlds: the standard red-and-gold chests (their "!" shows
only when close), one cat with a "!" (a paw stamp), and portals (a "!" on scenery). The Cape:
4 chests (10,000 Git, Medium Class Scroll ×5, Broken Club, Cat Express Ticket ×1-5), a cat,
and a "!" on the stairs out → Congratulations → autosave → the Another Dungeon map. "!"
bubbles are drawn at different sizes per world (scales 0.8-1.35 of the template).

## How a run goes

- The White Key can drop from a finished Another Dungeon run (10%, also with Skip).
- Every world: chests show a "!" when the party is close; each cat gives a paw stamp; a portal
  is any other "!". Which portal is taken doesn't matter: the outcome was decided when the key
  dropped. World I has no chests; its king cat and notice board open the paw stamp menu
  (claiming is left to the user).
- Bonus worlds (the Ratle shop, Nuaru Uplands, Izana) move on by themselves once emptied. The
  last straw dummy of a bonus room once moved the party on with no fade the run saw (an autosave
  on arrival), and the run went on in the jungle as "world 2" until it stopped with "no way out"
  (2026-11-16): the run checks the world's title too.
- The Cape or the final world ends it: its chests, then the "!" on the stairs out,
  Congratulations. Skip on the entry panel would spend a Cat Express Ticket.
- **The Sensationalist** offers a free Treatise/Codex/Opus of the user's choice. Always in the
  Last World; only sometimes in the World's Cape (the user, 2026-11-12; on 2026-11-18 the run
  stopped for them when they weren't there). In the Cape they stand just right of the cat. "!"
  heights there: the stairs out ~0.234 (measured 2026-11-18; the Cape's stairs have no up arrow
  under their "!"), the Sensationalist ~0.26 (from the user's picture, scaled by the cat's "!"),
  the cat ~0.29-0.30. The Cape's cat sits still, so the walking-cat check never took it (the
  user got its stamp by hand, 2026-11-16).

## What the macro's checks are based on

- A world's title or an item banner hides the field's buttons for a few seconds: twice the
  "door" tapped was a chest inside (2026-11-12). "MenuRewards" was read as one word (world V,
  2026-11-12).
- Chest banners can take ~3 s (read at 2 s one was blank, and the run then waited for a new world
  with the banner open, 2026-11-12). With the app's own "AM" badge counted as text, most chests'
  banners were only caught 2-5 s later (2026-11-16). OCR split "Obtained Cat Express Ticket" |
  "x3." (2026-11-16).
- A tap low on the screen left an item banner open in the World's Cape for minutes (2026-11-12);
  a tap on the banner closes it.
- A door's "!" shows only when the party stands close (the water palace, 2026-11-12: walks of
  2.5 s went past it).
- The teal pipe world uses blue-grey metal chests that never matched the red chest template, so
  its chests were walked past (2026-11-12): a second template.
- Full-size template search took a world step ~20 s (2026-11-12): searched at half size.
