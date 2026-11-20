# Another Macro

An Android app that plays parts of **Another Eden** for you: the daily routine (login ad,
ad chests, shop ad, cat scratchbook, inbox), Another Dungeon runs, the Phantom Crystal
Dimension and battle grinding. Everything runs **on the phone itself**, with no PC and no
root. The app reads the game screen (screen capture, OpenCV template matching and on-device
OCR), taps through an accessibility service, and follows small YAML macros.

You can also let **your own Claude chat** (claude.ai or the Claude apps) drive the game and
write new macros, through the app's phone connector.

> **Disclaimer.** This is a personal, educational project, and you use it at your own
> risk. It is not affiliated with Wright Flyer Studios, Gumi or Square Enix. It only
> reads the screen and sends taps, like a macro, and never touches game memory,
> files or network traffic. Automation may be against the game's terms of service.

## Quick start

1. **Install the app** and turn on taps and screen capture:
   [docs/setup-android.md](docs/setup-android.md).
2. **Pick a macro.** In the app, open **Macros**, tap a macro, set its options and tap
   **Select this macro**. The macros download by themselves.
3. **Play it** from the floating buttons over the game: ▶ start, ❚❚ pause, ■ stop, ↻ restart.
4. **Optional: Claude.** Set up the phone connector (System → Claude) to let Claude play or
   build macros from your chat: [the relay guide](../../../macro-forge/blob/main/relay/README.md) (in macro-forge).

## How it works

```
Claude chat ──(custom connector)──> relay (your Cloudflare Worker) <──WebSocket── Another Macro app
                                                                                     │
                                       screen capture + OCR + templates  ◄───────────┤
                                       accessibility taps / walking      ◄───────────┘
                                                                                  Another Eden
```

- **The app** (`android/`) holds the permissions, capture, vision, the macro runner, the
  floating buttons and the phone connector. Its UI has a sidebar with four pages: **Home**,
  **Macros** (tabs: In use, Daily, Another Dungeon, Farming, Game, Basic, Other, Custom),
  **Statistics** (every macro run, with totals and graphs) and
  **System** (Setup, Claude, Settings, Updates, Log).
- **The macros** (`macros/`) are YAML files. They are not built into the app: CI packs them
  with their templates and data and the app downloads the pack, so a macro fix reaches the
  phone without an app update. See [macros/README.md](macros/README.md).
- **The logic** (battles, Another Dungeons, routes, cat scratchbook, Phantom Crystal) ships as a
  downloadable logic pack, so most app changes also arrive without an install. See
  [the logic pack](../../../macro-forge/blob/main/docs/logic-pack.md) (macro-forge).
- **The phone connector** turns the app into an MCP server that you add in claude.ai as a
  custom connector. A small Cloudflare Worker on your own account relays it (macro-forge's `relay/`, at `core/relay/` here).

## Macros

The app shows its macros in groups, one tab each (Macros page). The full list, with a link to
each macro's file, is in [docs/macros.md](docs/macros.md).

- **Daily**: the things the game resets every day: the 5 menu ad chests, the shop ad, the cat
  scratchbook and the inbox, each on its own, or all of them in one go with `daily-routine`
  (which also starts and closes the game). The app's Daily schedule can run one by itself after
  the game's daily reset.
- **Another Dungeon**: one macro per dungeon. Each does one or more runs: it enters with the
  chosen key (or skips with a Cat Express Ticket), walks a fixed route to every chest (fighting
  on the way), optionally clears the enemies left, then the boss. It stops at 0 keys (never
  buys) and reports a White Key; the Phantom Crystal Dimension a White Key opens has a macro
  too. Which dungeons have one so far: [docs/macros.md](docs/macros.md#all-dungeons).
- **Farming**: repeated grinding of one thing (empty for now; `grind-battles` in Basic walks a
  map and fights every encounter).
- **Game**: turning the game on (with the daily login-bonus ad) and off the clean way.
- **Basic**: building blocks the other macros run (one battle, watching an ad, opening a menu
  page, picking a party...). Some, like `grind-battles`, also work on their own.
- **Other**: macros that fit no group above (none yet).
- **Custom**: drafts Claude saves on the phone while making a new macro; ones that work move to
  the library for everyone.

Battles use one skill choice per party slot (`skill_1`..`skill_4` = auto or skill 1-4), and the
dungeons a party by its number. Both are **Global parameters** (System → Settings), set once and
used by every macro that fights; each macro's own option starts as "Global" and can be set to its
own value instead. Macro options save as soon as you change them.

### Safety

- **Never spends premium currency or confirms a purchase.** Every tap goes through a purchase
  guard, and purchase screens are refused.
- Taps happen only while Another Eden is in front.
- Through the connector, anything that can't be undone (selling, discarding, exchanging...)
  asks you on the phone first.

## Writing macros

Ask Claude in a chat with the phone connector ("make a macro that ..."): it captures the
templates it needs, saves the macro as a **Custom** draft and tries it. Macros that work are
then added to `macros/<group>/` here, for everyone. The format is in
[writing macros](../../../macro-forge/blob/main/docs/writing-macros.md) (macro-forge) and this game's notes in [docs/writing-scenarios.md](docs/writing-scenarios.md).

## Docs

- [docs/setup-android.md](docs/setup-android.md): install and set up the app
- [Relay guide](../../../macro-forge/blob/main/relay/README.md): the Claude phone connector and its relay (macro-forge)
- [macros/README.md](macros/README.md): the macro library and basic macros
- [docs/writing-scenarios.md](docs/writing-scenarios.md): this game's macros, its Kotlin steps and input notes
- macro-forge: [writing macros](../../../macro-forge/blob/main/docs/writing-macros.md), [the Android app](../../../macro-forge/blob/main/docs/android-app.md), [the logic pack](../../../macro-forge/blob/main/docs/logic-pack.md), [the game profile](../../../macro-forge/blob/main/docs/game-profile.md)
- [docs/macros.md](docs/macros.md): every macro, every Another Dungeon and the plan for their macros
- [docs/knowledge-base/](docs/knowledge-base/README.md): everything known about the game, split into what outside sources say (`external/`: the wiki, Altema) and what the project learned itself while building and testing macros (`learned/`)
- [android/README.md](android/README.md): what this app adds to the core, build and releases

## Development

```bash
pip install -e '.[dev]'
python -m pytest -q                      # macro pack and routes (no phone)
gradle -p android testDebugUnitTest      # app unit tests
cd core/relay && npm test                # relay (macro-forge)
```

CI builds every pushed branch: the APK and logic pack go to the pre-release `dev-<branch>`,
the macro pack to `macros-<branch>`. Phones on a dev build follow their branch (normally
`main`) and update themselves.

The Python package (`src/another_macro`) holds the shared templates, dungeon and route data
and the macro packer, plus `another-macro dungeon-routes` / `cat-routes`, which plan the fixed
walking routes the macros follow.

## License

MIT, see [LICENSE](LICENSE).
