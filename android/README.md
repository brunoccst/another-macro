# Another Macro — Android app

The app is macro-forge's Android core (the `core/` submodule, Gradle module `:core`) with this
game's parts in `android/app`. How the app works (screens, phone connector and its safety layer,
updates, the macro library, templates) is documented in macro-forge:
**[the Android app](../../../../macro-forge/blob/main/docs/android-app.md)**, [the logic pack](../../../../macro-forge/blob/main/docs/logic-pack.md) and
[the game profile](../../../../macro-forge/blob/main/docs/game-profile.md) (`core/docs/` in a checkout).

## What this app adds

- `game.yaml` (repo root): Another Eden's package and activity, safety words (Chronos Stones,
  Tsubura, paid stones), macro groups (Daily, Another Dungeon, Farming, Game, Basic, Other,
  Custom), Claude notes, the dungeon run report fields, the app id `anothermacro.app`.
- `android/app/src/main/java/anothermacro/logic/`: the game plugin `AnotherEdenPlugin` and its
  steps (battles, Another Dungeons, routes, area navigation, the cat scratchbook, the Phantom
  Crystal). It ships in the logic pack with the core's logic, so most changes reach the phone
  without an install.
- `android/app/src/main/res/`: the app's name, launcher icon and floating-button art.
- `android/app/src/test/`: the unit tests (`gradle -p android testDebugUnitTest`, no device).

## Build

`git submodule update --init`, then `gradle -p android assembleDebug logicPack`. CI
(`.github/workflows/android.yml`) builds the APK and the logic pack and publishes each pushed
branch as the pre-release `dev-<branch>`. It is signed with the permanent key from the
`ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` secrets, so updates install over an
existing copy. Keep a backup of that key: without it installed apps can't be updated.

Releases: run `.github/workflows/release.yml` by hand on main with the version (it creates the
tag). It publishes the signed APK as `another-macro.apk`, with `docs/releases/<tag>.md` above
the generated notes.

## Install / first run

See [docs/setup-android.md](../docs/setup-android.md).

## Testing from a computer

`adb shell am start -n anothermacro.app/macroforge.MainActivity --es run grind-battles --es params "battles=3"`
and `adb logcat -s MacroForge` for the log.
