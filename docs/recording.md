# Recording a run (⏺) and turning it into a fixed macro

Instead of working a route out (a cat in the Cat Scratchbook, a dungeon map's walk),
play it once by hand while the app records it.

## One-time setup: pair the app with Wireless debugging

Recording reads the touchscreen through ADB. The app has its own ADB client (libadb-android,
the one App Manager uses) and pairs with Android's Wireless debugging like Shizuku or LADB do:
no Termux, no PC, no root.

1. Settings → About phone → tap **Build number** 7 times (unlocks Developer options).
2. Settings → Developer options → turn on **Wireless debugging** (needs Wi-Fi, not internet).
3. In the app: **Settings → Set up recording → Show pairing notification**.
4. In Wireless debugging, tap **Pair device with pairing code**. Pull the notification down (the
   code dialog stays open), tap **Enter code** and type the six digits. Or put the app and
   Settings in split screen and type the code (and port) in the setup screen.
5. **Test the connection** shows the port and the touchscreen it found. The app finds Wireless
   debugging's port by itself (last port, mDNS, then a scan of localhost). If it can't, type the port
   from **IP address & Port** (the number after the colon) into the port field and test again.

After a restart or a Wi-Fi change Android turns Wireless debugging off: turn it back on (step 2);
the pairing stays. ⏺ without a pairing opens the setup screen.

(Termux still works as a fallback when the app isn't paired: if Termux may run commands, ⏺ uses
`another-macro record-touches` there.)

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

- `sync` (off): before each tap, wait (up to 20 s) until the screen looks like it did when
  that tap was recorded. Walks never wait: after fights the party is never in quite the same spot.
- `pace` (1): how much of the recorded pause before each move to keep.

A tap that ends a walk (a chest's "!") is replayed where it was pressed. Then the replay
waits for the chest's "Obtained …" text (up to 15 s) and goes on as soon as it shows,
instead of keeping the recorded pause: each phone takes its own time to show it.

## How it works

Android doesn't let an app see touches meant for another app, and a gesture an app plays
is cancelled as soon as a real finger touches the screen, so the app can't pass touches on
live either. ADB's shell can read the touchscreen itself (`getevent`) without getting in
the way. So ⏺ connects the app's ADB to Wireless debugging and runs `getevent -lt`, while the
app keeps a frame of the screen every 0.4 s. ⏹ stops it; the app turns the log into moves
(`TouchLog.kt`, the Kotlin twin of `touch_recorder.py`), gives each move the frame before it
and the text under each tap, and writes `recording.json`. Moves on the floating buttons
(the ⏹ press) are left out.

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
- `--dungeon` splits the walks at the tap that ends each one (the chest's "!") into `path:`
  legs, `chest 1`, `chest 2`, …, then `exit`. Taps right after a chest (closing "Obtained …")
  are left out. Each chest leg notes the move and where it was tapped. The chest is tapped
  where Bruno pressed it; the next leg starts when "Obtained …" shows.
- `--macro` writes a plain macro (`tap_exact` / `drag` / `gesture` / `wait` steps,
  coordinates and timings only), safe to commit. Then fine-tune it: replace pauses with screen checks
  (`if: found('...')`) where loading times vary, using the frames to pick templates.

A recorded hold counts from the press, so its time matches walk.yaml's "from rest" table
when the party was standing still as the finger went down. In a swipe that turns without
lifting, only the first direction starts from rest; the later ones are already walking.
