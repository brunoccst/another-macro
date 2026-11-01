# Another Macro

Press **play** on your phone and let a macro play parts of **Another Eden** for you:
grind battles, and later run dungeons, do dailies and more. Everything runs **on the
Android phone itself**. There's no PC and no root: the macro uses Android's
Wireless debugging to take screenshots and tap, recognises screens with OpenCV
template matching, and follows a small per-scenario script.

> **Disclaimer.** This is a personal, educational project, and you use it at your own
> risk. It is not affiliated with Wright Flyer Studios, Gumi or Square Enix. It only
> reads the screen and sends taps, like a macro, and never touches game memory,
> files or network traffic. Automation may be against the game's terms of service.

## Quick start

1. Set up the phone: [docs/setup-android.md](docs/setup-android.md) (Termux, Wireless
   debugging, one pairing).
2. Capture the screen templates from **your** screen once. `another-macro doctor`
   lists what's missing, and [docs/writing-scenarios.md](docs/writing-scenarios.md)
   shows how to capture them.
3. Stand on a map with random encounters and tap the **Grind battles** widget, or
   run:

   ```bash
   another-macro play grind-battles --param battles=20
   ```

   Stop it with the **Stop macro** widget or `another-macro stop`.

## Scenarios

| Scenario | Start state | What it does | Params |
|---|---|---|---|
| `grind-battles` | On a field/dungeon map with random encounters | Walks left/right, fights each encounter, clears results, repeats. Default `skill` mode gives the first front-row character their last skill (e.g. Verdanta → Sprinting Raid) and presses Attack; `mode=auto` uses the Auto button. Resumes from the title screen; stops on defeat, when the party seems stuck, or when Game Booster's touch lock covers the game. | `battles` (10, 0 = no limit), `walk_ms` (1500), `mode` (skill/auto), `slot` (1-4), `skill_x` (0.8075 = last skill), `first_walk` (-1 = left), `stuck_walks` (8) |
| `cat-scratchbook` | On any field map (`mode=hunt`: on the map where the cat is) | `read` (default): opens Diary → Cat Scratchbook and reports where today's cats are. `hunt`: walks the street in short steps, taps the cat's feather bubble, clears the rewards. Travelling between maps is still manual. | `mode` (read/hunt), `first_walk`, `span`, `pulse_ms`, `max_walks` |
| `start-game` | Anything | Turns the game on (or brings it to the front), taps Continue on the title, stops on the field. | `package` |
| `stop-game` | Anything | Turns the game off the clean way: back → "Close the app?" → Yes; force-stops after `timeout_s`. | `package`, `timeout_s` (60) |

`another-macro list` prints them all. To add one, ask Claude to "automate …" in this
repo (it uses the [`new-scenario`](.claude/skills/new-scenario/SKILL.md) skill), or
follow [docs/writing-scenarios.md](docs/writing-scenarios.md).

## Commands

```
another-macro connect [--pair-port P --code C] [--port P]   connect the phone's ADB to itself
another-macro doctor                                        check OpenCV, device, missing templates
another-macro list                                          scenarios and params
another-macro play <scenario> [--param k=v] [--minutes N]   run one
another-macro stop                                          stop the running one
another-macro screenshot [-o file]                          save the screen
another-macro tap X Y | hold X Y MS | swipe X1 Y1 X2 Y2 [MS] | back
another-macro check [--from file]                           which templates/screen match
another-macro capture-template NAME --box L,T,W,H [--from file]
```

Coordinates from 0 to 1 are fractions of the screen; larger values are pixels. The
small commands also let an AI agent (Claude, via Remote Control in Termux) drive the
game step by step.

## Development

```bash
pip install -e '.[dev]'
python -m pytest -q
```

Tests run against synthetic screens and a simulated game, so no phone is needed.

## Roadmap

- Native Android app driver with a floating play/stop button (same scenarios and templates)
- More scenarios: daily cat scratchbook, Another Dungeons (e.g. Dogma Tower), …
- OCR anchors, "go to map X" navigation helpers, Claude as a fallback on unknown screens

## License

MIT, see [LICENSE](LICENSE).
