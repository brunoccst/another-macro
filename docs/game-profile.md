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

The macro pack carries `game.yaml` (`macro_pack.py`), so a change to it needs no app install.
The logic pack reads it through `logic/Game.kt`. If the pack has no profile, or the file is
broken, the logic uses `Game.DEFAULT`, which holds the same values.

## Who reads it today

- **The logic pack** (`logic/Game.kt`): the game's package and activity (`ad_in_front()`,
  `game_screen()`, `leave_ad`, `force_close`) and the purchase words the Another Dungeon run
  stops on.
- **The pack check**: every macro's `group:` must be one of `groups`, and every key must be there.
- **Not yet the app itself.** Its own copies (`ClaudeSafety.GAME`, `FlowScenario.GAME_ACTIVITY`,
  `ClaudeSafety`'s word lists, `Groups.ALL`, `REPO` in the updater, the build picker and the
  macro library) move to the profile with the next app install. Until then,
  `tests/test_game_profile.py` checks that they say the same as `game.yaml`. If you change one,
  change both.

## Rules for code

- Logic code never names the game: use `Game.of(ctx)`. `tests/test_game_profile.py` fails on the
  package name, `ClaudeSafety.GAME` or `GAME_ACTIVITY` in the logic.
- A new game-specific value goes in `game.yaml` and in `Game.Profile`, with a default equal to it.
