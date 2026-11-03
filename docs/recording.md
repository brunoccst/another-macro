# Recording a run (⏺) and turning it into a fixed macro

Instead of working a route out (a cat in the Cat Scratchbook, a dungeon map's walk),
play it once by hand while the app records it.

## One-time setup

Recording reads the touchscreen through ADB, which Termux already has (the app can't):

1. In Termux, update another-macro (`termux/install.sh`) and check ADB: `another-macro connect`.
2. Let other apps run commands in Termux:
   `mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings`
3. The first ⏺ asks to allow Another Macro to "run commands in Termux environment": allow it,
   then ⏺ again.

## On the phone

1. Turn on screen capture in the app (the floating buttons appear over the game).
2. Get to the starting point. For a cat: **point zero**, the Present World Map with
   Baruoki on screen (or the Spacetime Rift). For a dungeon map: where the party arrives.
3. Tap **⏺**. Only **⏹** stays on the buttons (the handle turns red).
4. Play as usual: your touches go straight to the game. Long holds and turning mid-swipe
   without lifting (left, then up, then right) are recorded as one stroke.
5. Tap **⏹**, give it a name (`cat-<place>`, `<dungeon>-map-<n>`) and **Save**.

Saving keeps the recording on the phone and makes a macro with the same name in the
**Recorded** group: ▶ replays it right away, each move with the finger's own path and
timing. Its params:

- `sync` (on): before each move, wait (up to 20 s) until the screen looks like it did when
  that move was recorded, so slow loading doesn't make the replay run ahead.
- `pace` (1): how much of the recorded pause before each move to keep.

## How it works

Android doesn't let an app see touches meant for another app, and a gesture an app plays
is cancelled as soon as a real finger touches the screen, so the app can't pass touches on
live either. ADB's shell can read the touchscreen itself (`getevent`) without getting in
the way. So ⏺ asks Termux to run `another-macro record-touches start` (it reads
`adb shell getevent -lt` in the background), and the app keeps a frame of the screen every
0.4 s. ⏹ runs `another-macro record-touches stop`, which turns the log into moves and
`adb push`es them (`touches.json`) into the app's folder; the app gives each move the frame
before it and the text under each tap, and writes `recording.json`. Moves on the floating
buttons (the ⏹ press) are left out.

Without Termux the app falls back to catching each touch and passing it on when the
finger lifts: fine for taps and menus, not for walking.

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

## Recording from Termux (or the phone session) directly

The same reader works without the app:

```bash
another-macro record-touches start --out /sdcard/Download/dogma1
# ... play ...
another-macro record-touches stop --out /sdcard/Download/dogma1
another-macro recording ~/.another-macro/touchrec/touches.json --dungeon
```

(no frames or tap labels this way; the converter reads `touches.json` as well as `recording.json`).

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
- `--macro` writes a plain macro (`tap_exact` / `drag` / `gesture` / `wait` steps,
  coordinates and timings only), safe to commit. Then fine-tune it: replace pauses with screen checks
  (`if: found('...')`) where loading times vary, using the frames to pick templates.

A recorded hold counts from the press, so its time matches walk.yaml's "from rest" table
when the party was standing still as the finger went down. In a swipe that turns without
lifting, only the first direction starts from rest; the later ones are already walking.
