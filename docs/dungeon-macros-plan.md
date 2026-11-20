# One macro per Another Dungeon: the plan

The goal is a macro for every Another Dungeon. The full list (80 dungeons) is
[All dungeons](#all-dungeons) below: each row's Macro says which macro runs it (`todo` until one
does). What the wiki says about each one is in
[the knowledge base](knowledge-base/external/another-dungeon-list.md); what the project learned on the phone
is in [learned/dungeons/](knowledge-base/learned/dungeons/README.md).

## What every dungeon macro reuses

The Burning Beast King's Castle and Dogma Tower macros are the same YAML with a different
id: the whole run is the `another_dungeon` action (`AnotherDungeon.kt`), which already does
the parts every dungeon shares:

- **Entry**: Spacetime Rift door "!" → era tab → (optional `area:` sub-map such as
  "Parallel Time Layer") → the dungeon's label (`label:` when it wraps) → Hard/Very Hard
  (`key: green/red`) → Move, or Skip with a Cat Express Ticket.
- **Stops first**: the map's fixed `path:` (from `another-macro dungeon-routes --write`) to
  every chest and pickup at once, fighting the battles met on the way with the basic `battle`
  macro's skills; after a battle the walk reads the party's spot again and goes on from there.
  A chest not found on the way gets one more try, walked to by the area map, before the exit.
- **Enemies left, then the exit**: on a map whose enemies run out (the banner can show), with
  `defeat_all` on and the banner not seen yet, walk back and forth fighting until
  "The enemies have disappeared" (`clear_max_battles` is only a safety cap); then the exit
  (ground bars sign, or `door_sign` for grand staircases). The option is only offered by
  dungeons with such a map.
- **Small maps by steps** (`steps:`), maps without enemies (`enemies: false`), and the
  **boss map** (sweep, "!" taps, "A major trial awaits... Proceed?" → Yes, `boss_setup` skills).
- **End of run**: rewards, light/shadow animation, "Continue to skip?" → End, White Key
  (`white_key: phantom/stop/keep_going`), never buying keys.
- **Before the first run**: `party` picks one of the game's saved parties by its number in the
  game's party list (1 = the first; read from the dots on Menu → Party), through the basic macro
  `pick-party` (`logic/Party.kt`); `current` keeps the party in front. Never by a party's name or
  members, and the log only says "party N": every user names and fills their parties differently.
- **Keys and runs**: one switch per key colour the dungeon takes (`green_key` Hard, `red_key`
  Very Hard; a dungeon with one colour only has just that switch), `runs` (any number, a
  `type: number` box) and beside it the switch `all_keys` (`beside: runs`: until the keys run
  out). With both colours on, `runs` runs are done with each colour: red keys first, then
  green (a colour that runs out ends its runs). No colour on = only the party is picked (a safe test). Keys are never bought. A
  dungeon whose run takes more than one key sets `key_cost` (Underworld: 2) in dungeons.yaml.

So a new dungeon needs **data, not code**: one `dungeons.yaml` entry (era, area/label, map
names, each map's layout, chests, arrival, exit, boss map) and a 40-line macro YAML copied
from `macros/dungeon/burning-beast-kings-castle.yaml` with the new id. Code changes are
only needed for a family with its own rules (below).

## Per dungeon (skill `map-dungeon`)

1. **Interview the user** (step 0 of the skill): the saved wiki page (the wiki refuses automated
   fetches), how to reach it (era, continent, location, sub-location, label), key cost,
   required characters, own currency or special rules.
2. **Read the wiki page**: map names (regular/rare), chests per map, minimaps → layouts.
3. **Line it up on the phone** (the user's OK first; only one thread drives the phone): arrival
   offsets, stairs, exits. Or the user records the path by hand and Claude turns it into the route.
4. **Write** the `dungeons.yaml` entry, `dungeon-routes --write`, tests, the macro YAML,
   set its Macro in [All dungeons](#all-dungeons), the wiki's facts in
   `docs/knowledge-base/external/dungeons/<dungeon>.md` and the mapping notes in
   `docs/knowledge-base/learned/dungeons/<dungeon>.md`.
   Open a PR; merge once phone-tested.

## Order

1. **Standard dungeons with an era tab** (46 left): same entry as the castle, so only
   data. Start with the ones that drop tomes or that the user farms; easy low-level ones
   (Moonlight Forest, Industrial Ruins, Nadara Volcano, Man-eating Marsh) are good first
   checks that the generic flow holds on a new map style.
2. **Standard dungeons without an era icon** (Edax Unigan, Entrana, Jasmond, Omegapolis,
   Underworld, Home's Lost Atelier): ask the user where they are on the Another Dungeon map;
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

Done: Dogma Tower, Burning Beast King's Castle, Sea of Ice, Moonlight Forest, Underworld (Demon's Den and
the Dimensional Cathedral), and the White Key's Phantom Crystal Dimension.

## Idea: an explore mode before a dungeon is mapped

The run already sweeps a map that has no layout (the boss map). Letting a dungeon entry
with only `era` + `maps: [{names}]` sweep every map (fight until cleared, walk each way,
tap every "!", take the exit sign) would give a slow but working macro for any dungeon
on day one, and its logs (where chests and exits were found) would seed the fixed routes.

## All dungeons

Every dungeon in the game with the id the data and macros use (`dungeons.yaml` keys), its kind
(standard = Spacetime Rift door → era tab → the dungeon's label; otherlands / mysterious_vortex /
kairos / sealed_realm = families with their own entry and rules) and the macro that runs it
(`todo` until one does). A test checks this table against `dungeons.yaml` and `macros/dungeon/`.
The wiki's facts for each (era, levels, elements, tomes, wiki page) are in
[the knowledge base](knowledge-base/external/another-dungeon-list.md).

| Id | Dungeon | Kind | Macro |
|---|---|---|---|
| `moonlight_forest` | Moonlight Forest | standard | moonlight-forest |
| `industrial_ruins` | Industrial Ruins | standard | todo |
| `transitory_time_space` | Transitory Time Space | sealed_realm | todo |
| `nadara_volcano` | Nadara Volcano | standard | todo |
| `man_eating_marsh` | Man-eating Marsh | standard | todo |
| `miglance_labyrinth` | Miglance Labyrinth | standard | todo |
| `sakis_dream_world` | Saki's Dream World | standard | todo |
| `tower_of_time` | Tower of Time | standard | todo |
| `dragon_palace` | Dragon Palace | standard | todo |
| `miglance_castle` | Miglance Castle | standard | todo |
| `xeno_domain` | Xeno-Domain | standard | todo |
| `mementos` | Mementos | standard | todo |
| `corridor_of_time_layers` | Corridor of Time Layers | standard | todo |
| `darkrealm_wyrmkings_domain` | Darkrealm - Wyrmking's Domain | standard | todo |
| `dream_flower` | Dream Flower | standard | todo |
| `dreamslip_tower_of_time` | Dreamslip Tower of Time | standard | todo |
| `edge_of_contradiction` | Edge of Contradiction | standard | todo |
| `evils_nest_arena` | Evil's Nest Arena | standard | todo |
| `forgotten_tale_unborn_visions` | Forgotten Tale: Unborn Visions | standard | todo |
| `path_of_avarice` | Path of Avarice | standard | todo |
| `riftbreaker_ark` | Riftbreaker Ark | standard | todo |
| `wyrmrest_isle_dragons_depths` | Wyrmrest Isle - Dragon's Depths | standard | todo |
| `wyrmrest_isle_quadra_wastes` | Wyrmrest Isle - Quadra Wastes | standard | todo |
| `otherlands_baruoki_area_1` | Otherlands: Baruoki Region. Area 1 | otherlands | todo |
| `otherlands_elzion_area_1` | Otherlands: Elzion Region. Area 1 | otherlands | todo |
| `otherlands_ratle_area_1` | Otherlands: Ratle Region. Area 1 | otherlands | todo |
| `mayus_dream_world` | Mayu's Dream World | standard | todo |
| `the_riftbreaker` | The Riftbreaker | standard | todo |
| `tower_of_stars` | Tower of Stars | standard | todo |
| `otherlands_baruoki_area_2` | Otherlands: Baruoki Region. Area 2 | otherlands | todo |
| `otherlands_elzion_area_2` | Otherlands: Elzion Region. Area 2 | otherlands | todo |
| `otherlands_ratle_area_2` | Otherlands: Ratle Region. Area 2 | otherlands | todo |
| `dogma_tower` | Dogma Tower | standard | dogma-tower |
| `otherlands_baruoki_area_3` | Otherlands: Baruoki Region. Area 3 | otherlands | todo |
| `otherlands_baruoki_area_4` | Otherlands: Baruoki Region. Area 4 | otherlands | todo |
| `otherlands_elzion_area_3` | Otherlands: Elzion Region. Area 3 | otherlands | todo |
| `otherlands_elzion_area_4` | Otherlands: Elzion Region. Area 4 | otherlands | todo |
| `otherlands_ratle_area_3` | Otherlands: Ratle Region. Area 3 | otherlands | todo |
| `otherlands_ratle_area_4` | Otherlands: Ratle Region. Area 4 | otherlands | todo |
| `beast_kings_castle` | Beast King's Castle | standard | todo |
| `burning_beast_kings_castle` | Burning Beast King's Castle | standard | burning-beast-kings-castle |
| `former_kms_hq` | Former KMS HQ | standard | todo |
| `ruins_of_rucyana` | Ruins of Rucyana | standard | todo |
| `snake_liver_damaku` | Snake Liver Damaku | standard | todo |
| `proto_eden` | Proto Eden | standard | todo |
| `toto_dreamland` | Toto Dreamland | standard | todo |
| `antiquity_garulea_continent` | Antiquity Garulea Continent | standard | todo |
| `edax_unigan` | Edax Unigan | standard | todo |
| `entrana` | Entrana | standard | todo |
| `future_garulea_continent` | Future Garulea Continent | standard | todo |
| `jasmond` | Jasmond | standard | todo |
| `omegapolis` | Omegapolis | standard | todo |
| `present_garulea_continent` | Present Garulea Continent | standard | todo |
| `underworld` | Underworld | standard | underworld |
| `mysterious_vortex_warped_cochlea` | Mysterious Vortex - Warped Cochlea | mysterious_vortex | todo |
| `mysterious_vortex_dust_city` | Mysterious Vortex - Dust City | mysterious_vortex | todo |
| `mysterious_vortex_isle_of_gospel` | Mysterious Vortex - Isle of Gospel | mysterious_vortex | todo |
| `mysterious_vortex_land_of_ro` | Mysterious Vortex - Land of Ro | mysterious_vortex | todo |
| `mysterious_vortex_the_tower_argentauri` | Mysterious Vortex - The Tower Argentauri | mysterious_vortex | todo |
| `mysterious_vortex_thundering_fortress` | Mysterious Vortex - Thundering Fortress | mysterious_vortex | todo |
| `mysterious_vortex_tower_of_selection` | Mysterious Vortex - Tower of Selection | mysterious_vortex | todo |
| `forest_of_forgetfulness` | Forest of Forgetfulness | standard | todo |
| `hanging_garden` | Hanging Garden | standard | todo |
| `astral_ruins` | Astral Ruins | standard | todo |
| `burning_kurosagi_castle` | Burning Kurosagi Castle | standard | todo |
| `meigoku` | Meigoku | standard | todo |
| `what_the_planet_dreamt` | What the Planet Dreamt | standard | todo |
| `homes_lost_atelier` | Home's Lost Atelier | standard | todo |
| `ephemeral_time_space` | Ephemeral Time Space | sealed_realm | todo |
| `antiquity_zerberiya_continent_crystal` | Antiquity Zerberiya Continent: Crystal | standard | todo |
| `antiquity_zerberiya_continent_thunder` | Antiquity Zerberiya Continent: Thunder | standard | todo |
| `city_of_lost_paradise` | City of Lost Paradise | standard | todo |
| `iscariot` | Iscariot | standard | todo |
| `sapient_complex_midnight` | Sapient Complex: Midnight | standard | todo |
| `sea_of_ice` | Sea of Ice | standard | sea-of-ice |
| `temporal_surveillance_ship_kairos_blood_ritual_site` | Temporal Surveillance Ship Kairos - Blood Ritual Site | kairos | todo |
| `temporal_surveillance_ship_kairos_holy_meadow` | Temporal Surveillance Ship Kairos - Holy Meadow | kairos | todo |
| `temporal_surveillance_ship_kairos_institute_of_despair` | Temporal Surveillance Ship Kairos - Institute of Despair | kairos | todo |
| `temporal_surveillance_ship_kairos_ruins_of_glory` | Temporal Surveillance Ship Kairos - Ruins of Glory | kairos | todo |
| `toto_theater_world` | Toto Theater World | standard | todo |
