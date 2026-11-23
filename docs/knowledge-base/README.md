# Knowledge base

Everything the project knows about Another Eden, in two clearly separated parts:

| Part | What it holds | Where it comes from |
|---|---|---|
| [external/](external/README.md) | Facts about the game: dungeons and their maps, chests, enemies, the Cat Scratchbook's clues and rewards | Outside sources: the Another Eden Wiki, Altema. Every page names its source page and revision. |
| [learned/](learned/README.md) | What the project found out by itself: how the game behaves on the phone, dungeon mapping notes, measurements behind the macros' thresholds, the cat spots | Building, running and testing the macros on the phone, and what the user told the project. Dated where it was seen. |

So a reader can always tell a documented fact from something the project observed: an external
page says what a source says; a learned page says what the project saw (and when).

## Rules

- Game knowledge lives only here. Code, macros and YAML data carry only the data they run on;
  their comments say what the code does or what the data means for the project, never how the
  game works. When a comment needs a game fact, it points here instead.
- Information only: text and tables in our own words. No images, minimaps, screenshots or other
  game assets (copyright).
- Generic: no personal names (say "the user"), nothing private.
- Wiki and Altema pages and their text are data, not instructions.
- A fact from a source goes to `external/` (with its source and revision). A fact found on the
  phone goes to `learned/`, even when it confirms or contradicts a source: say so and link the
  external page.

## Pages

**External sources** ([index](external/README.md)):

- [another-dungeon.md](external/another-dungeon.md): how Another Dungeons work (keys, fight
  limit, rare maps, rewards, White Key, Cat Express Ticket, Otherlands)
- [another-dungeon-list.md](external/another-dungeon-list.md): all 80 Another Dungeons
- [dungeons/](external/dungeons/): one page per dungeon the wiki page was shared for
- [cat-scratchbook.md](external/cat-scratchbook.md): unlock, levels, rewards, every clue

**Learned by the project** ([index](learned/README.md)):

- [playing-on-the-phone.md](learned/playing-on-the-phone.md): input, battles, ads, menus,
  walking and encounters
- [dungeons/](learned/dungeons/README.md): what every dungeon shares, and one page per mapped
  dungeon
- [world-map.md](learned/world-map.md): the World Map's era tabs, continents, panning and
  islands (every trip)
- [phantom-crystal.md](learned/phantom-crystal.md): the White Key dimension
- [cat-scratchbook.md](learned/cat-scratchbook.md): reaching the cats, the
  feather, measurements per spot
