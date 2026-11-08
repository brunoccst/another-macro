# One macro per Another Dungeon: the plan

Bruno (2026-11-08) wants a macro for every Another Dungeon. The full list (80 dungeons, from
the wiki's Another Dungeon page) is
[`dungeon_list.yaml`](../src/another_macro/scenarios/another_dungeon/templates/dungeon_list.yaml),
next to `dungeons.yaml`. Each entry's `macro:` says which macro runs it (`todo` until one does).

## What every dungeon macro reuses

The Burning Beast King's Castle and Dogma Tower macros are the same YAML with a different
id: the whole run is the `another_dungeon` action (`AnotherDungeon.kt`), which already does
the parts every dungeon shares:

- **Entry**: Spacetime Rift door "!" → era tab → (optional `area:` sub-map such as
  "Parallel Time Layer") → the dungeon's label (`label:` when it wraps) → Hard/Very Hard
  (`key: green/red`) → Move, or Skip with a Cat Express Ticket.
- **Every map, until cleared**: fight with the basic `battle` macro's skills until
  "The enemies have disappeared" (`clear_max_battles` is only a safety cap), so the fixed
  path is never cut by a battle.
- **Chests and exit**: the map's fixed `path:` (from `another-macro dungeon-routes --write`),
  then the exit (ground bars sign, or `door_sign` for grand staircases).
- **Small maps by steps** (`steps:`), maps without enemies (`enemies: false`), and the
  **boss map** (sweep, "!" taps, "A major trial awaits... Proceed?" → Yes, `boss_setup` skills).
- **End of run**: rewards, light/shadow animation, "Continue to skip?" → End, White Key
  (`white_key: phantom/stop/keep_going`), `runs` 1-10/all, never buying keys.

So a new dungeon needs **data, not code**: one `dungeons.yaml` entry (era, area/label, map
names, each map's layout, chests, arrival, exit, boss map) and a 40-line macro YAML copied
from `macros/dungeon/burning-beast-kings-castle.yaml` with the new id. Code changes are
only needed for a family with its own rules (below).

## Per dungeon (skill `map-dungeon`)

1. **Interview Bruno** (step 0 of the skill): the saved wiki page (the wiki refuses automated
   fetches), how to reach it (era, continent, location, sub-location, label), key cost,
   required characters, own currency or special rules.
2. **Read the wiki page**: map names (regular/rare), chests per map, minimaps → layouts.
3. **Line it up on the phone** (Bruno's OK first; only one thread drives the phone): arrival
   offsets, stairs, exits. Or Bruno records the path by hand and Claude turns it into the route.
4. **Write** the `dungeons.yaml` entry, `dungeon-routes --write`, tests, the macro YAML,
   set `macro:` in `dungeon_list.yaml`. Open a PR; merge once phone-tested.

## Order

1. **Standard dungeons with an era tab** (47 left): same entry as the castle, so only
   data. Start with the ones that drop tomes or that Bruno farms; easy low-level ones
   (Moonlight Forest, Industrial Ruins, Nadara Volcano, Man-eating Marsh) are good first
   checks that the generic flow holds on a new map style.
2. **Standard dungeons without an era icon** (Edax Unigan, Entrana, Jasmond, Omegapolis,
   Underworld, Home's Lost Atelier): ask Bruno where they are on the Another Dungeon map;
   may need a new `tab:` field instead of `era:`.
3. **Families** (one code change each, then data per dungeon):
   - **Otherlands** (Baruoki / Elzion / Ratle, areas 1-4: 12 dungeons): regions with
     areas; likely a region pick then an area pick.
   - **Mysterious Vortex** (7, Very Hard only): chapters 1-3 / 4-6 grouping.
   - **Temporal Surveillance Ship Kairos** (4 sites, Very Hard).
   - **Sealed Realm** (Transitory / Ephemeral Time Space).
4. **Dungeons with a 2x key or a different fight limit** (all 2x green key dungeons, Home's
   Lost Atelier, Toto Theater World): the until-cleared mode already copes with any limit;
   check the key-cost box text.

Done: Dogma Tower, Burning Beast King's Castle (and the White Key's Phantom Crystal Dimension).

## Idea: an explore mode before a dungeon is mapped

The run already sweeps a map that has no layout (the boss map). Letting a dungeon entry
with only `era` + `maps: [{names}]` sweep every map (fight until cleared, walk each way,
tap every "!", take the exit sign) would give a slow but working macro for any dungeon
on day one, and its logs (where chests and exits were found) would seed the fixed routes.

## All dungeons

| Dungeon | Kind | Era | Hard lvl | Very Hard lvl | Light/Shadow | Tomes | Macro |
|---|---|---|---|---|---|---|---|
| Moonlight Forest | standard | Present | 21 | 58 | shadow | yes | todo |
| Industrial Ruins | standard | Future | 24 | 60 | light | yes | todo |
| Transitory Time Space | sealed realm | none | 25 | 60 | light, shadow |  | todo |
| Nadara Volcano | standard | Antiquity | 28 | 62 | light | yes | todo |
| Man-eating Marsh | standard | Antiquity | 33 | 64 | shadow | yes | todo |
| Miglance Labyrinth | standard | Present | 33 | 40 | light, shadow |  | todo |
| Saki's Dream World | standard | Future | 35 | 42 | light, shadow |  | todo |
| Tower of Time | standard | Antiquity | 36 | - | shadow | yes | todo |
| Dragon Palace | standard | Antiquity | 40 | 50 | light, shadow |  | todo |
| Miglance Castle | standard | Present | 40 | 66 | light | yes | todo |
| Xeno-Domain | standard | Future | 44 | 68 | light | yes | todo |
| Mementos | standard | Present | 45 | 65 | light, shadow |  | todo |
| Corridor of Time Layers | standard | Future | 48 | - | shadow | yes | todo |
| Darkrealm - Wyrmking's Domain | standard | Antiquity | 50 | 70 | light, shadow |  | todo |
| Dream Flower | standard | Antiquity | 50 | 70 | light, shadow |  | todo |
| Dreamslip Tower of Time | standard | Present | 50 | 70 | light, shadow |  | todo |
| Edge of Contradiction | standard | Present | 50 | 70 | light, shadow |  | todo |
| Evil's Nest Arena | standard | Future | 50 | 70 | light, shadow |  | todo |
| Forgotten Tale: Unborn Visions | standard | Antiquity | 50 | 70 | light, shadow |  | todo |
| Path of Avarice | standard | Antiquity | 50 | 70 | light, shadow |  | todo |
| Riftbreaker Ark | standard | Future | 50 | 70 | light, shadow |  | todo |
| Wyrmrest Isle - Dragon's Depths | standard | Antiquity | 50 | 70 | light, shadow |  | todo |
| Wyrmrest Isle - Quadra Wastes | standard | Antiquity | 50 | 70 | light, shadow |  | todo |
| Otherlands: Baruoki Region. Area 1 | otherlands | Present | 50-70 | - | light, shadow |  | todo |
| Otherlands: Elzion Region. Area 1 | otherlands | Future | 50-70 | - | light, shadow |  | todo |
| Otherlands: Ratle Region. Area 1 | otherlands | Antiquity | 50-70 | - | light, shadow |  | todo |
| Mayu's Dream World | standard | Future | 52 | 62 | light |  | todo |
| The Riftbreaker | standard | Future | 52 | 70 | light | yes | todo |
| Tower of Stars | standard | Antiquity | 55 | - | shadow | yes | todo |
| Otherlands: Baruoki Region. Area 2 | otherlands | Present | 55-75 | - | light, shadow |  | todo |
| Otherlands: Elzion Region. Area 2 | otherlands | Future | 55-75 | - | light, shadow |  | todo |
| Otherlands: Ratle Region. Area 2 | otherlands | Antiquity | 55-75 | - | light, shadow |  | todo |
| Dogma Tower | standard | Present | 60 | 65 | light, shadow |  | dogma-tower |
| Otherlands: Baruoki Region. Area 3 | otherlands | Present | 60-80 | - | light, shadow |  | todo |
| Otherlands: Baruoki Region. Area 4 | otherlands | Present | 60-80 | - | shadow |  | todo |
| Otherlands: Elzion Region. Area 3 | otherlands | Future | 60-80 | - | light, shadow |  | todo |
| Otherlands: Elzion Region. Area 4 | otherlands | Future | 60-80 | - | light |  | todo |
| Otherlands: Ratle Region. Area 3 | otherlands | Antiquity | 60-80 | - | light, shadow |  | todo |
| Otherlands: Ratle Region. Area 4 | otherlands | Antiquity | 60-80 | - | light |  | todo |
| Beast King's Castle | standard | Present | 64 | 72 | shadow | yes | todo |
| Burning Beast King's Castle | standard | Present | 65 | 70 | light, shadow | yes | burning-beast-kings-castle |
| Former KMS HQ | standard | Future | 65 | 70 | light, shadow |  | todo |
| Ruins of Rucyana | standard | Present | 66 | - | light, shadow |  | todo |
| Snake Liver Damaku | standard | Present | 66 | 72 | shadow | yes | todo |
| Proto Eden | standard | Future | 70 | 90 | light, shadow |  | todo |
| Toto Dreamland | standard | Future | 70 | 76 | light | yes | todo |
| Antiquity Garulea Continent | standard | Antiquity | 80 | - | light, shadow |  | todo |
| Edax Unigan | standard | none | 80 | - | light, shadow |  | todo |
| Entrana | standard | none | 80 | - | light, shadow |  | todo |
| Future Garulea Continent | standard | Future | 80 | - | light, shadow |  | todo |
| Jasmond | standard | none | 80 | - | light, shadow |  | todo |
| Omegapolis | standard | none | 80 | - | light, shadow |  | todo |
| Present Garulea Continent | standard | Present | 80 | - | light, shadow |  | todo |
| Underworld | standard | none | 80 | - | light, shadow | yes | todo |
| Mysterious Vortex - Warped Cochlea | mysterious vortex | none | - | ? | light, shadow |  | todo |
| Mysterious Vortex - Dust City | mysterious vortex | none | - | 50-70 | light, shadow |  | todo |
| Mysterious Vortex - Isle of Gospel | mysterious vortex | none | - | 50-70 | light, shadow |  | todo |
| Mysterious Vortex - Land of Ro | mysterious vortex | none | - | 50-70 | light, shadow |  | todo |
| Mysterious Vortex - The Tower Argentauri | mysterious vortex | none | - | 50-70 | light, shadow |  | todo |
| Mysterious Vortex - Thundering Fortress | mysterious vortex | none | - | 50-70 | light, shadow |  | todo |
| Mysterious Vortex - Tower of Selection | mysterious vortex | none | - | 50-70 | light, shadow |  | todo |
| Forest of Forgetfulness | standard | Present | - | 65 | light | yes | todo |
| Hanging Garden | standard | Future | - | 67 | light | yes | todo |
| Astral Ruins | standard | Present | - | 70 | light, shadow |  | todo |
| Burning Kurosagi Castle | standard | Present | - | 70 | light, shadow |  | todo |
| Meigoku | standard | Present | - | 70 | light, shadow |  | todo |
| What the Planet Dreamt | standard | Present | - | 70 | light, shadow |  | todo |
| Home's Lost Atelier | standard | none | - | 72 | light, shadow |  | todo |
| Ephemeral Time Space | sealed realm | none | - | 75 | light, shadow |  | todo |
| Antiquity Zerberiya Continent: Crystal | standard | Antiquity | - | 80 | light, shadow | yes | todo |
| Antiquity Zerberiya Continent: Thunder | standard | Antiquity | - | 80 | light, shadow | yes | todo |
| City of Lost Paradise | standard | Antiquity | - | 80 | light, shadow | yes | todo |
| Iscariot | standard | Future | - | 80 | light, shadow | yes | todo |
| Sapient Complex: Midnight | standard | Future | - | 80 | light, shadow | yes | todo |
| Sea of Ice | standard | Antiquity | - | 80 | light, shadow | yes | todo |
| Temporal Surveillance Ship Kairos - Blood Ritual Site | kairos | Present | - | 80 | light, shadow |  | todo |
| Temporal Surveillance Ship Kairos - Holy Meadow | kairos | Present | - | 80 | light, shadow |  | todo |
| Temporal Surveillance Ship Kairos - Institute of Despair | kairos | Present | - | 80 | light, shadow |  | todo |
| Temporal Surveillance Ship Kairos - Ruins of Glory | kairos | Present | - | 80 | light, shadow |  | todo |
| Toto Theater World | standard | Future | - | 80 | light, shadow |  | todo |
