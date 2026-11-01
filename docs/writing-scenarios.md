# How the framework works, and how to write a scenario

## Layers

```
another-macro play <scenario>         CLI / home-screen widget
        │
scenario/base.py   run loop: screenshot → identify screen → call on_<screen>()
        │
vision/            OpenCV template matching, screens = sets of templates
        │
device/            Device interface: screenshot, tap, hold, swipe, back
  adb.py           ADB, either the phone controlling itself or a PC controlling the phone
  fake.py          scripted device for tests
```

Everything a scenario stores is **normalized**: positions are fractions of the
screen (0..1), so a scenario works on any resolution. Templates remember the
resolution they were cut from and are rescaled to the current screen by height
(Another Eden runs in landscape and its UI scales with the short side).

## The run loop

Each tick the runner:

1. stops if the stop file exists (`another-macro stop`) or `--minutes` ran out
2. takes a screenshot
3. finds the **first** screen in `Scenario.screens` whose `require` templates are all
   visible (and none of its `reject` ones)
4. calls `on_<screen>(ctx)`, or `on_unknown(ctx)` if nothing matched

Handlers do **one small step** and return. The next screenshot decides what happens
next, which makes scenarios tolerant of missed taps, lag and popups. If the screen
stays unrecognised for `unknown_timeout_s`, the run stops and saves the screenshot.
Add a screen for that state, or raise the timeout.

## Templates and screens

Templates and screens live in `library.yaml` files next to their PNGs:

- `src/another_macro/assets/templates/library.yaml`: shared ones (field, battle,
  results…)
- `src/another_macro/scenarios/<name>/templates/library.yaml`: a scenario's own,
  loaded after the shared ones

```yaml
templates:
  field_menu_button:
    notes: The "Menu" button on the field (bottom-left).
    file: field_menu_button.png
    ref: [2400, 1080]                  # size of the screenshot it was cut from
    region: [0.0, 0.75, 0.3, 0.25]     # where to search (x, y, w, h)
    threshold: 0.85                    # optional
screens:
  field:
    require: [field_menu_button]
    reject: []
```

An entry with no `ref` or PNG is **not captured yet**, and its screens can't match.
`another-macro doctor` lists what is missing.

### Capturing a template from your own screen

```bash
another-macro screenshot -o shot.png                  # save the current screen
another-macro capture-template field_menu_button \
    --from shot.png --box 60,920,190,110              # pixel box: left,top,width,height
another-macro check --from shot.png                   # which templates/screen match
```

Tips:

- Cut something that **doesn't change**: button text or an icon, not a character
  portrait or a number.
- Keep it small but distinctive. About 100–250 px wide is typical on a 1080p-tall
  screen.
- The search region is the box padded by 5% of the screen (`--pad`). Increase it
  for elements that move around.
- After capturing, run `check` on several different screenshots to make sure it
  matches where it should, and **only** there.

## A scenario

Create `src/another_macro/scenarios/<snake_name>/__init__.py`:

```python
from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point


class MyScenario(Scenario):
    name = "my-scenario"                  # what you type after `play`
    description = "One line shown by `another-macro list`."
    params = {"runs": Param(int, 1, "how many times")}
    screens = ["popup", "field"]          # priority order: specific screens first

    def setup(self, ctx: Context) -> None:
        ctx.stats["runs"] = 0

    def on_popup(self, ctx: Context) -> None:
        ctx.tap("popup_ok_button")        # tap a template where it was found

    def on_field(self, ctx: Context) -> None:
        ctx.drag(Point(0.5, 0.55), Point(0.56, 0.55), 1500)  # walk right for ~1.5s
        if ctx.stats["runs"] >= ctx.params["runs"]:
            ctx.stop("done")


SCENARIO = MyScenario
```

What `ctx` gives a handler:

| | |
|---|---|
| `ctx.find(name)` | `Match` (score, region, center) or `None` on the current frame |
| `ctx.tap(x)` | tap a template name, `Match`, `Region` or `Point` (lands randomly in the middle of a button) |
| `ctx.hold(x, ms)` | press and hold in place |
| `ctx.drag(a, b, ms)` | press at `a`, slide to `b`, hold until `ms` is up (walking) |
| `ctx.device.swipe(a, b, ms)`, `ctx.device.back()` | raw input |
| `ctx.wait(s)` | sleep (slightly randomised) |
| `ctx.stats` | counters printed at the end |
| `ctx.params` | the scenario's params |
| `ctx.save_frame(tag)` | save the current screenshot into the run folder |
| `ctx.stop(reason)` | end the run |

Test the scenario with a fake device and synthetic frames. See
`tests/test_grind_battles.py` for a small simulated game.

Then add a Termux widget button in `termux/shortcuts/<Name>.sh` (copy
`Grind battles.sh`).

## Where this goes next

- **More drivers** behind the same `Device` interface: a native Android app
  (Accessibility service + floating play/stop button), or the Steam version
  (port of another-eden-companion's Win32 input/capture).
- **OCR anchors** (text on screen) next to template anchors, e.g. for reading map
  names or item counts.
- **Navigation helpers**: "go to map X" built from menu screens, shared across
  scenarios.
- **Agent fallback**: on an unknown screen, ask Claude what to do instead of
  stopping.

## Game input notes (learned on the phone)

- **Walking is a virtual stick.** The party walks in the direction a held finger
  *slides*, not toward the side of the screen you press. Press mid-screen and slide a
  little left or right (`ctx.drag`); a hold that drifts the wrong way walks the wrong way.
- **Battles start with an intro animation** with no HUD; it shows as `unknown` for a
  second or two before the command screen.
- **Samsung Game Booster's touch lock** dims the game and swallows every tap while
  templates still match through the dim. The runner checks for it every 30 s and stops.
- **The game is landscape-only.** A portrait screenshot means another app (e.g.
  Termux) is in front; the runner pauses input until the game is back.
- **Every run keeps the screen awake.** The runner raises the phone's screen
  timeout for the run and puts the original back afterwards (saved to
  `~/.another-macro/screen_timeout`, so a killed run is restored by the next one).
  A locked phone stops the run with "the phone is locked".
- **Closing the game:** Android back on the field opens "Close the app?"; Yes exits
  cleanly (`stop-game`). Starting: splash → title ("Game Id..." while connecting,
  then "Continue") → tap → field (`start-game`).
