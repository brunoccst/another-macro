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
   build macros from your chat: [relay/README.md](relay/README.md).

## How it works

```
Claude chat ──(custom connector)──> relay (your Cloudflare Worker) <──WebSocket── Another Macro app
                                                                                     │
                                       screen capture + OCR + templates  ◄───────────┤
                                       accessibility taps / walking      ◄───────────┘
                                                                                  Another Eden
```

- **The app** (`android/`) holds the permissions, capture, vision, the macro runner, the
  floating buttons and the phone connector. Its UI has a sidebar with three pages: **Home**,
  **Macros** (tabs: In use, Daily, Another Dungeon, Farming, Game, Basic, Other, Custom) and
  **System** (Setup, Claude, Settings, Updates, Log).
- **The macros** (`macros/`) are YAML files. They are not built into the app: CI packs them
  with their templates and data and the app downloads the pack, so a macro fix reaches the
  phone without an app update. See [macros/README.md](macros/README.md).
- **The logic** (battles, Another Dungeons, routes, cat scratchbook, Phantom Crystal) ships as a
  downloadable logic pack, so most app changes also arrive without an install. See
  [docs/logic-pack.md](docs/logic-pack.md).
- **The phone connector** turns the app into an MCP server that you add in claude.ai as a
  custom connector. A small Cloudflare Worker on your own account relays it (`relay/`).

## Macros

| Group | Macro | What it does |
|---|---|---|
| Daily | `daily-routine` | Starts the game, watches the 5 menu ad chests and the shop ad, does the cat scratchbook, claims the inbox, then turns the game off. |
| Daily | `daily-ads` | The 5 daily commercial chests (Menu → video icon, 1-3 ads each). |
| Daily | `shop-ad` | The shop's daily commercial for Chronos Stones. |
| Daily | `cat-scratchbook` | Reads today's cat clues, travels to each cat by a fixed route and finds it (`mode`: daily / read / hunt). |
| Daily | `inbox` | Claims every inbox item not opened yet. |
| Another Dungeon | `dogma-tower`, `burning-beast-kings-castle` | One or more runs: enters with the chosen key (or skips with a Cat Express Ticket), fights each map until "The enemies have disappeared", walks fixed routes to every chest and the exit, then the boss. Stops at 0 keys (never buys), reports a White Key. |
| Another Dungeon | `phantom-crystal` | The Phantom Crystal Dimension after a White Key: every chest and paw stamp, world by world. Stops so you pick a free Treatise/Codex/Opus yourself. |
| Game | `start-game` | Opens the game, takes the daily login-bonus ad, stops on the field. |
| Game | `stop-game` | Closes the game the clean way (Back → "Close the app?" → Yes). |
| Basic | `battle`, `grind-battles`, `watch-ad`, `open-menu`, `back-to-field` | Building blocks the other macros run. `grind-battles` also works on its own: walk a map and fight every encounter. |

Battles use one skill choice per party slot (`skill_1`..`skill_4` = auto or skill 1-4), set
once on the `battle` macro and used by every macro that fights.

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
[docs/writing-scenarios.md](docs/writing-scenarios.md) and at the top of `Flow.kt`.

## Docs

- [docs/setup-android.md](docs/setup-android.md): install and set up the app
- [relay/README.md](relay/README.md): the Claude phone connector and its relay
- [macros/README.md](macros/README.md): the macro library and basic macros
- [docs/writing-scenarios.md](docs/writing-scenarios.md): the macro format, templates, game notes
- [docs/logic-pack.md](docs/logic-pack.md): how app logic updates reach the phone without an install
- [docs/dungeon-macros-plan.md](docs/dungeon-macros-plan.md): every Another Dungeon and the plan for their macros
- [android/README.md](android/README.md): the app's code, build and releases

## Development

```bash
pip install -e '.[dev]'
python -m pytest -q                      # macro pack, routes, vision (synthetic frames, no phone)
gradle -p android testDebugUnitTest      # app unit tests
cd relay && npm test                     # relay
```

CI builds every pushed branch: the APK and logic pack go to the pre-release `dev-<branch>`,
the macro pack to `macros-<branch>`. Phones on a dev build follow their branch (normally
`main`) and update themselves.

The Python package (`src/another_macro`) holds the shared templates, dungeon and route data
and the macro packer. It also still has the original Termux command-line tool, now legacy (see
the end of [docs/setup-android.md](docs/setup-android.md)).

## License

MIT, see [LICENSE](LICENSE).
