# Recording a run (⏺) and turning it into a fixed macro

Instead of working a route out (a cat in the Cat Scratchbook, a dungeon map's walk),
play it once by hand while the app records it.

## On the phone

1. Turn on screen capture in the app (the floating buttons appear over the game).
2. Get to the starting point. For a cat: **point zero**, the Present World Map with
   Baruoki on screen (or the Spacetime Rift). For a dungeon map: where the party arrives.
3. Tap **⏺** on the floating buttons. A thin red frame shows that the screen is being
   recorded, and only **⏹** stays on the buttons.
4. Play as usual. Each move reaches the game **when your finger lifts**: a tap a moment
   late, a hold (walking) right after you let go, with the same path and timing. Wait
   for a move to finish before the next one: a touch while a move is still playing
   reaches the game unrecorded and cuts that move short (the save screen warns about it).
   Use the game's own buttons, not Android's Back.
5. Tap **⏹**, give it a name (`cat-<place>`, `<dungeon>-map-<n>`) and **Save**.

Saving keeps the recording on the phone and makes a macro with the same name in the
**Recorded** group: ▶ replays it right away. Its params:

- `sync` (on): before each move, wait (up to 20 s) until the screen looks like it did when
  that move was recorded, so slow loading doesn't make the replay run ahead.
- `pace` (1): how much of the recorded pause before each move to keep.

## Why it works this way

Android doesn't let an app see touches meant for another app. An accessibility service
can only get them by taking them over, and an overlay that lets touches through gets no
coordinates. So the recorder takes each touch itself and plays it on to the game with
the same accessibility gestures the macros use. What the game got is exactly what was
recorded, so a replay does the same thing. (Reading the touchscreen with `adb shell
getevent` would let moves through without the delay, but needs ADB from Termux, which the
app doesn't use.)

## Where recordings are, and what not to do with them

```
/sdcard/Android/data/io.github.brunoccst.anothermacro/files/recordings/<name>/
    recording.json     the moves (format in src/another_macro/recordings.py)
    frames/NNN.jpg     the screen when each move started
```

The frames are screenshots of the account: **never commit a recording**. Copy one off
the phone with ADB (Termux, or the phone session):

```bash
adb pull /sdcard/Android/data/io.github.brunoccst.anothermacro/files/recordings/cat-acteul /tmp/cat-acteul
```

## Turning it into something to commit

```bash
another-macro recording /tmp/cat-acteul              # every move: tap/walk/flick/swipe, labels, pauses
another-macro recording /tmp/cat-acteul --cat        # cat_spots.yaml fields + the walk as route moves
another-macro recording /tmp/dogma-map-1 --dungeon   # the walks as dungeons.yaml path legs
another-macro recording /tmp/cat-acteul --macro cat-acteul -o macros/cat-acteul.yaml
```

- `--cat` reads the era, continent (the entry after "Expand"), place and sub-location from
  the labels under the taps (read by OCR on the phone: check them), the walk from the
  arrival to the cat as `"L 800", "U", "R 1600"` moves (walk.yaml notation), and where the
  cat bubble was tapped.
- `--dungeon` splits the walks at each tap (a chest, a door) into `path:` legs. Rename each
  leg's first item to `chest N` / `exit`.
- `--macro` writes a plain macro (`tap_exact` / `drag` / `wait` steps, coordinates and
  timings only), safe to commit. Then fine-tune it: replace pauses with screen checks
  (`if: found('...')`) where loading times vary, using the frames to pick templates.

A recorded hold counts from the press, so its time matches walk.yaml's "from rest" table
when the party was standing still as the finger went down.
