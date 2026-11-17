# The game profile (`game.yaml`)

`game.yaml` at the top of the repo says which game this app plays. It's the first step towards a
generic macro tool: the generic code asks the profile instead of naming the game, so the same
code can serve another game with another profile.

| Key | What it is |
|---|---|
| `name` | The game's name, also the name of its card in recent apps (force close) |
| `package` | The game's Android package |
| `activity` | The game's own activity. The same package with another activity in front is a commercial (`ad_in_front()`, `leave_ad`) |
| `repo` | The GitHub repo that hosts the app's builds and packs |
| `safety.purchase_words` | Words that mean an offer to pay: never tapped |
| `safety.premium_words` | Words that mean premium currency: taps there need the user's OK |
| `groups` | The macro groups in tab order: `id` (a macro's `group:` and its folder in `macros/`), `title`, `icon` (`res/drawable/ic_<icon>.xml`) |

## How it reaches the phone

The build bakes `game.yaml` into the app (`BuildConfig.GAME_YAML`, read by `GameProfile.kt`)
and puts its `package` in the manifest (`${gamePackage}`, so the app may see and open the game).
The app's own code asks `GameProfile.current`. The file is part of the app's fingerprint
(`SHELL_ID`), so changing it means a new app build.

The macro pack carries a copy too (`macro_pack.py`). The logic pack reads it through
`logic/Game.kt` (`Game.of(ctx)`), so a fix to, say, the safety words reaches the logic with the
next macro pack. Without a pack copy the logic uses the app's own.

## Other keys

| Key | What it is |
|---|---|
| `app_name`, `tagline`, `about` | The app's name, the line under it and the Home page's text |
| `safety.shop_screen`, `shop_close`, `shop_safe` | The purchase guard: while the shop template shows, macros may only tap `shop_safe` templates |
| `claude` | What Claude is told about the game: extra how-to lines, premium-currency examples, the example macro, a reward text example, extra macro-format lines |

## Still in the app

The run reports of the dungeon macros (dungeon runs, keys, White Keys) are still read by name in
`RunStats.kt`, `RunSummary.kt` and `StatsPage.kt`; they move behind the game plugin next.
`tests/test_game_profile.py` fails if any other app file names the game.

## Rules for code

- App code never names the game: use `GameProfile.current`. Logic code uses `Game.of(ctx)`. `tests/test_game_profile.py` fails on the
  package name, `ClaudeSafety.GAME` or `GAME_ACTIVITY` in the logic.
- A new game-specific value goes in `game.yaml` and in `GameProfile` (and in `GAME_KEYS` of `macro_pack.py` when it is required).

## The game plugin

The profile holds values; the game's own *code* sits behind `logic/GamePlugin.kt`. The generic
steps (`FlowRun.STEPS`: tap, walk, run, leave_ad, ...) know nothing about the game, and
`FlowRun` hands every other step to the plugin that `Entry.PLUGIN` names. Another Eden's plugin is
`logic/AnotherEdenPlugin.kt`: fights, parties, Another Dungeons, the Phantom Crystal Dimension and
the cat scratchbook. A plugin gives its steps (`actions`), the basic macros they run in code
(`stepUses`, so outer macros still take over those macros' options), their "How it works" words
(`explain`) and a fresh `Run` per macro run for its state. A game with only YAML macros uses
`GamePlugin.None`.
