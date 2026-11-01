# Another Macro — Android app (proof of concept)

Runs the macros on the phone **by itself**: no Termux, no ADB, no root.

- **Screen capture:** MediaProjection (Android asks "Start recording?" once per session).
- **Input:** an accessibility service dispatches taps, slide-and-hold walking and Back.
- **Vision:** OpenCV template matching on the same `library.yaml` + PNG templates as the
  Python tool (copied into the APK at build time from `src/another_macro/.../templates`).
- **Macros:** `grind-battles`, `start-game` (with the daily ad, `watch_ad`), `stop-game`,
  `cat-scratchbook` (`mode=read` reports today's clues and where the cats are; `mode=hunt`
  finds the cat on the current map). Pick one on the main screen; its params are remembered.
- **Floating controls** over the game (start/pause/stop/restart the last macro), shown
  while screen capture is on.

## Install / first run
1. Download `another-macro.apk` from the latest GitHub Release and install it.
2. In the app: **1. Turn on taps** → enable *Another Macro* in Accessibility. On Android 13+,
   if it's greyed out ("restricted setting"): Settings → Apps → Another Macro → ⋮ →
   *Allow restricted settings*, then enable it.
3. **2. Allow screen capture** → *Start now*.
4. Set the params and **Run**; afterwards the floating buttons repeat it.

## Build
CI (`.github/workflows/android.yml`) builds the APK. It is signed with the permanent key
from the `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` secrets, so updates install
over an existing copy. Keep a backup of that key: without it installed apps can't be updated.

## Notes
- Whether the game is in front is read from the capture: a portrait app (home screen, Termux)
  shows pillar-boxed in the landscape capture.
- Dev builds from CI (`0.0.N-dev`) don't check for updates (a release would replace them).
- `uiautomator dump` suspends accessibility services while it runs; don't use it while testing.

## Testing from a computer/Termux
`adb shell am start -n io.github.brunoccst.anothermacro/.MainActivity --es run grind-battles --es params "battles=3"`
and `adb logcat -s AnotherMacro` for the log.

## Releases and auto-update
- Push a tag `vX.Y.Z` → `.github/workflows/release.yml` builds the signed APK and publishes
  it as a GitHub Release asset `another-macro.apk`.
- Users install from
  https://github.com/brunoccst/another-macro/releases/latest/download/another-macro.apk
  (the browser asks to allow installing unknown apps once).
- The app checks GitHub for a newer release when it opens (at most every 6 hours, or with
  **Check for updates**), downloads it and hands it to Android's installer. Android asks to
  confirm; the first time it also asks to allow Another Macro to install apps.
- versionCode is derived from the version (1.2.3 → 10203), so releases always upgrade.

## Macros as data
Every macro is a `.yaml` file: the built-in ones live in the repo's `macros/` folder (shipped in
the APK), and users can **Import macro** their own. The format (screens, handlers, actions,
expressions) is documented at the top of `Flow.kt`; `macros/grind-battles.yaml` is a full example.

## Capturing templates in the app
With screen capture on, tap 📷 on the floating buttons: the game screen is grabbed (without the
buttons), drag a box around a stable element (a label or icon, no numbers or moving parts), name
it, **Save template**. It is matched on the same screen right away. Saved templates live in the
app's storage and are usable in macros by name: `found('my_button')`, `tap: my_button`.
