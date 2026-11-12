# Another Macro — Android app

Runs the macros on the phone **by itself**: no Termux, no ADB, no root.

- **Screen capture:** MediaProjection (Android asks "Start recording?" once per session).
- **Input:** an accessibility service dispatches taps, slide-and-hold walking and Back.
- **Vision:** OpenCV template matching on the same `library.yaml` + PNG templates as the
  Python tool.
- **Macros are downloaded, not built in:** the app installs the macro pack (macros, templates,
  dungeon data) CI publishes from the repo, and updates it by itself (see *The macro library*).
- **Basic macros** other macros run: `watch-ad` (watch a commercial, close it, take the reward),
  `battle` (one battle, skill per party slot: every battle uses its skills), `grind-battles`
  (walk and run `battle` for every encounter),
  `open-menu`, `back-to-field`, and `start-game` when the game isn't open. See `macros/README.md`.
- **Claude:** let your own Claude chat play or write macros through the phone connector (see below).
- **Floating controls** over the game: the selected macro (tap it to pick another, in tabs like
  the Macros page, in a list that scrolls), start/pause/stop/restart, shown while screen capture is on.

## Screens
A sidebar (≡ at the top left) switches between three pages, all in `MainActivity.kt`:
- **Home** (`HomePage.kt`): what the app is, what's still missing (taps, capture), the selected
  macro, how to use the app, and links (GitHub, docs, relay guide, releases).
- **Macros** (`MacrosPage.kt`): a search box, tabs "In use" plus one per group (Daily, Another
  Dungeon, Farming, Game, Basic, Other, Custom). Each macro has a switch to choose whether you use
  it: the macros in use make up the "In use" tab (`MacroChoice` in `Ui.kt`). Tap a macro for what
  it does (a diagram of its YAML, `MacroDiagram.kt`; the macros it runs open from there), its
  options and **Select this macro**: the floating buttons start, pause and stop it (nothing runs
  from the app itself). Options are kept per macro (`MacroParams`) and used by every run of it.
  The macro library card shows the installed pack and checks for a newer one.
- **System** (`SystemPage.kt`), in tabs: Setup (taps, screen capture), Claude (the phone
  connector and what Claude may do), Settings (live log), Updates and Log.

From adb: `am start -n io.github.brunoccst.anothermacro/.MainActivity --es page macros`.

## Claude in the app: the phone connector
The **System** page's **Claude phone connector** section turns the app into a remote MCP
server that the user adds in claude.ai as a custom connector, so their own Claude chat (any
plan, any Claude app) can see and control the game. A phone has no public address, so the app
keeps a WebSocket open to the user's relay, a small Cloudflare Worker on their own account
(`relay/`, with a Deploy button and setup steps in `relay/README.md`).
- `PhoneConnector.kt`: the socket (reconnects with backoff), turns itself off after 30 minutes
  without tool calls, and maps the connector's tools (screenshot, zoom, tap, swipe, back, wait,
  walk, read_text, find_template, list_templates, list_macros, read_macro, start_macro,
  macro_status, stop_macro, save_template, save_macro, launch_game, floating_controls,
  wake_screen, update_app, update_macros, delete_macro) onto `PhoneTools`. tap and swipe fold the floating buttons first when their window
  covers the point.
- `ScreenWake.kt`: turns the screen on and keeps it on while Claude or a macro drives the game
  (a bright-screen wake lock that wakes the screen; if that doesn't, a see-through activity that
  turns the screen on and closes itself). Each connector tool call wakes it and keeps it on for
  10 minutes; a macro keeps it on until it stops. An all-black capture is never taken for "the
  game isn't in front": screenshots say so next to the image, `macro_status` reports the screen
  and capture state, taps on a black frame are refused, and a macro stops after 2 minutes of black.
- `PhoneTools.kt`: the tools, behind the safety layer (`ClaudeSafety.kt`, enforced in code,
  not just the prompt): taps only while Another Eden is in front; every tap goes through
  `Context.tap`'s purchase guard; screens offering a purchase are refused (a second purchase
  attempt turns the connector off); taps on sell/discard/release/exchange/... or on screens
  mentioning Chronos Stones ask the user on the phone (`AskOverlay.kt`, denied after 2
  minutes because connector tool calls time out at 240 s). Macros run in the background
  (`start_macro` / `macro_status`) for the same reason; macros Claude writes are ordinary
  `.yaml` files in the app's storage, and built-in macro names can't be replaced.
- `McpServer.kt`: the MCP JSON-RPC (initialize, ping, tools/list, tools/call), unit tested.
- Tokens: a phone token (app → relay) and a connector token (Claude → relay), 256-bit random,
  stored encrypted with an Android Keystore AES key (`ClaudeSettings.kt`), never logged.
- Unit tests (`gradle -p android testDebugUnitTest`, no device): the MCP answers and the
  safety word checks.

## Install / first run
See [docs/setup-android.md](../docs/setup-android.md). In short: install `another-macro.apk`
from the `dev-main` pre-release, turn on taps (Accessibility; on Android 13+ first *Allow
restricted settings* in App info → ⋮) and allow screen capture on System → Setup, then pick
a macro on **Macros** and start it with ▶ on the floating buttons over the game.

## Build
CI (`.github/workflows/android.yml`) builds the APK. It is signed with the permanent key
from the `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` secrets, so updates install
over an existing copy. Keep a backup of that key: without it installed apps can't be updated.

## Notes
- Whether the game is in front is read from the capture: a portrait app (home screen, Termux)
  shows pillar-boxed in the landscape capture.
- `uiautomator dump` suspends accessibility services while it runs; don't use it while testing.

## Testing from a computer/Termux
`adb shell am start -n io.github.brunoccst.anothermacro/.MainActivity --es run grind-battles --es params "battles=3"`
and `adb logcat -s AnotherMacro` for the log.

## Releases and auto-update
- **Dev builds** (`0.0.N-dev`): CI (`android.yml`) publishes every pushed branch's APK and
  logic pack as the pre-release `dev-<branch>`. A dev build follows its branch (`BuildConfig.BRANCH`,
  normally `main`): it checks every 3 minutes while the connector is on and idle, on the
  connector's `update_app`, and on System → Updates → *Install the newest build*. When only
  `logic/` changed it loads the logic pack with no install ([docs/logic-pack.md](../docs/logic-pack.md));
  otherwise it installs the APK. If Play Protect blocks an unattended install, the app taps
  *Got it* (never *Install anyway*) and doesn't retry that version; the user installs it from
  System → Updates. After any install, screen capture and the connector stay off until the
  user turns them on.
- **Releases:** push a tag `vX.Y.Z`, or run `.github/workflows/release.yml` by hand on main
  with the version (it creates the tag). It publishes the signed APK as the GitHub Release
  asset `another-macro.apk`, with `docs/releases/<tag>.md` above the generated notes. Release
  builds check GitHub for a newer release when they open (at most every 6 hours, or with
  *Check for updates*). They don't load logic packs, so stay on dev builds to follow `main`.
- versionCode is derived from the version (1.2.3 → 10203), so releases always upgrade.

## The macro library
Every macro is a `.yaml` file in the repo's `macros/<group>/` folders. They are **not** in the
APK: `.github/workflows/macros.yml` packs them with the templates and data
(`src/another_macro/macro_pack.py`) on every push and publishes `macros.zip` as the pre-release
`macros-<branch>`. The app (`MacroLibrary.kt`) follows the same branch as its own updates (a
release build: `main`; no pack for that branch: `main`'s), checks at most every 15 minutes (on
opening the app, turning on capture, and while the connector is idle) and swaps a newer pack in
between runs. So a macro fix reaches phones with a push, without an app release. The format
(screens, handlers, actions, expressions, `run:` for running another macro) is documented at the
top of `Flow.kt`; `macros/basic/grind-battles.yaml` is a full example.

Users don't add macros themselves. Claude can save **custom** macros through the phone connector
(`save_macro`, listed under Custom) as drafts; one that fits a library category is then published
to `macros/<group>/` for everyone and removed from the phone (`delete_macro`). The connector's
`update_macros` installs the newest pack now.

## Templates captured with Claude
The connector's `save_template` saves a box of the current screen as a template in the app's
storage, usable in macros by name: `found('my_button')`, `tap: my_button`.
