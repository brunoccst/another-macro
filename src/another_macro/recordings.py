"""Turn a recording made with the app's ⏺ button into fixed macro steps and routes.

The app saves each recording on the phone (never in this repo: its frames are
screenshots of the account) as::

    /sdcard/Android/data/io.github.brunoccst.anothermacro/files/recordings/<name>/
        recording.json      the moves (format below)
        frames/NNN.jpg      the screen when move NNN started

Pull it with ``adb pull <that folder> /tmp/<name>`` and run
``another-macro recording /tmp/<name>`` (see ``--help``).

recording.json (version 1)::

    {"version": 1, "name": "cat-acteul", "screen": [2400, 1080], "end_ms": 61000,
     "gestures": [
       {"i": 0, "down_ms": 1830, "gap_ms": 1830, "dur_ms": 70, "echo_ms": 1960, "echo_ok": true,
        "frame": "frames/000.jpg", "label": "Present",
        "points": [[0, 0.4121, 0.0611], [70, 0.4121, 0.0611]]},
       ...]}

``points`` are ``[ms since the press, x, y]`` with x, y as fractions of the screen.
``gap_ms`` is the pause before the move (from the end of the previous one as the game
got it), ``label`` the text under a tap (OCR on the phone), if any.

What this module makes of it:

- :func:`moves`: each gesture classified as tap / long press / walk / flick / swipe.
- :func:`to_macro`: a macro .yaml (tap_exact / drag / wait steps) that can be committed:
  coordinates and timings only, no images.
- :func:`cat_spot`: the cat_spots.yaml fields (era, continent, place, sub) read from the
  tapped labels, the walk from the arrival to the cat as route moves ("R 1180", "U", ...)
  and where the cat was tapped.
- :func:`dungeon_legs`: the walks between taps (chests, doors) as dungeons.yaml ``path:``
  legs.

Route moves use the notation of walk.yaml / dungeons.yaml: "R 1180" / "L 2950" = hold
right/left that many ms, "U" / "D" = flick up/down at stairs. A swipe that turns without
lifting adds "+U 400" / "+L 720": keep the finger down and turn that way for that long
(already walking, so no ramp-up, and an up/down there is a turn onto stairs, not a flick). A "+D" / "+U"
with no ms ends such a hold: a quick slide down/up just before lifting (onto stairs). A long
hold up/down alone is "D 5220". A recorded hold is
counted from the press, so it matches the table when the party was standing still
when the finger went down (it is after a tap, a flick or a pause).
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

# a tap stays within this distance (fraction of the screen width) and is short
TAP_MAX_MOVE = 0.012
TAP_MAX_MS = 500
# a flick is quick; a hold this long or longer is a walk
FLICK_MAX_MS = 450
FLICK_MIN_MOVE = 0.015
# routes round hold times to this many ms
ROUND_MS = 10
# the virtual stick: how far from the press the finger must be to count as a direction,
# and how long a direction must be held to count (shorter is the finger passing through a turn)
STICK_DEAD = 0.012
TURN_MIN_MS = 150

ERAS = ("antiquity", "present", "future")
# labels on buttons that aren't places (map and travel dialogs)
GENERIC = {
    "ok", "yes", "no", "cancel", "close", "back", "travel", "go", "move", "depart", "confirm",
    "map", "world map", "expand", "menu", "skip", "next", "end",
}


@dataclass
class Move:
    i: int
    kind: str                      # tap, long_press, walk, flick, swipe
    start: tuple[float, float]
    end: tuple[float, float]
    dur_ms: int
    gap_ms: int
    label: str | None = None
    direction: str | None = None   # R/L for walks, U/D for flicks, R/L/U/D for swipes
    frame: str | None = None       # the screen just before the move (frames/....jpg in the recording)
    echo_ok: bool = True
    points: list[list[float]] = field(default_factory=list)
    # a walk that turns without lifting the finger: one route move per direction
    turns: list[str] = field(default_factory=list)

    @property
    def tokens(self) -> list[str]:
        """The route moves ("R 1180", "U"); empty for taps and swipes."""
        if self.kind == "walk":
            return self.turns or [f"{self.direction} {_ms(self.dur_ms)}"]
        if self.kind == "flick":
            return [self.direction or ""]
        return []

    @property
    def token(self) -> str | None:
        return " ".join(f"{t}," for t in self.tokens).rstrip(",") or None

    def describe(self) -> str:
        x0, y0 = self.start
        x1, y1 = self.end
        where = f"({x0:.4f}, {y0:.4f})"
        text = {
            "tap": f"tap {where}",
            "long_press": f"long press {where} {self.dur_ms} ms",
            "walk": f"walk {self.token}  {where} -> ({x1:.4f}, {y1:.4f})",
            "flick": f"flick {self.direction}  {where} -> ({x1:.4f}, {y1:.4f})",
            "swipe": f"swipe {self.direction} {self.dur_ms} ms  {where} -> ({x1:.4f}, {y1:.4f})",
        }[self.kind]
        if self.label:
            text += f"  '{self.label}'"
        if not self.echo_ok:
            text += "  [cut short while recording]"
        return text


@dataclass
class Recording:
    name: str
    screen: tuple[int, int]
    gestures: list[dict]
    end_ms: int = 0
    folder: Path | None = None

    @property
    def aspect(self) -> float:
        """Height over width: turns a y distance into width units."""
        w, h = self.screen
        return h / w if w else 0.45


def load(path: str | Path) -> Recording:
    """Load a recording from its folder or its recording.json."""
    p = Path(path)
    if p.is_dir():
        p = p / "recording.json"
    data = json.loads(p.read_text())
    if "error" in data:
        raise ValueError(f"{p}: {data['error']}")
    if "version" not in data and "clock" in data:
        data = _from_touches(data, p)
    if data.get("version", 1) != 1:
        raise ValueError(f"{p}: unsupported recording version {data.get('version')}")
    screen = data.get("screen") or [2400, 1080]
    return Recording(
        name=data.get("name") or p.parent.name,
        screen=(int(screen[0]), int(screen[1])),
        gestures=list(data.get("gestures", [])),
        end_ms=int(data.get("end_ms", 0)),
        folder=p.parent,
    )


def _ms(ms: float) -> int:
    return int(round(ms / ROUND_MS) * ROUND_MS)


def stick_runs(pts: list, aspect: float) -> list[tuple[str, int, int]]:
    """Walking is a virtual stick: the party goes where the finger is from the press point.

    Returns the directions held, as (R/L/U/D, from ms, to ms), the first from the press.
    Moments shorter than TURN_MIN_MS (the finger passing through on a turn) are dropped.
    """
    x0, y0 = pts[0][1], pts[0][2]
    runs: list[list] = []
    for t, x, y in pts:
        dx, dy = x - x0, (y - y0) * aspect
        if math.hypot(dx, dy) < STICK_DEAD:
            continue
        d = ("R" if dx > 0 else "L") if abs(dx) >= abs(dy) else ("D" if dy > 0 else "U")
        if runs and runs[-1][0] == d:
            continue
        runs.append([d, int(t)])
    end = int(pts[-1][0])
    out = [(d, start, runs[k + 1][1] if k + 1 < len(runs) else end) for k, (d, start) in enumerate(runs)]
    out = [r for r in out if r[2] - r[1] >= TURN_MIN_MS]
    merged: list[tuple[str, int, int]] = []
    for d, a, b in out:
        if merged and merged[-1][0] == d:
            merged[-1] = (d, merged[-1][1], b)
        else:
            merged.append((d, a, b))
    if merged:
        merged[0] = (merged[0][0], 0, merged[0][2])   # a hold counts from the press
    return merged


def _from_touches(data: dict, p: Path) -> dict:
    """A touches.json straight from `another-macro record-touches stop` (no frames, no labels)."""
    gestures = []
    prev_end = None
    t0 = data["gestures"][0]["down_s"] if data.get("gestures") else 0.0
    for i, g in enumerate(data.get("gestures", [])):
        down = g["down_s"]
        gap = 0 if prev_end is None else max(0, int(round((down - prev_end) * 1000)))
        gestures.append({"i": i, "down_ms": int(round((down - t0) * 1000)), "gap_ms": gap,
                         "dur_ms": g["dur_ms"], "points": g["points"], "multi_touch": g.get("multi_touch", False)})
        prev_end = down + g["dur_ms"] / 1000
    end = gestures[-1]["down_ms"] + gestures[-1]["dur_ms"] if gestures else 0
    return {"version": 1, "name": p.parent.name, "screen": data.get("screen") or [2400, 1080], "end_ms": end, "gestures": gestures}


def _end_flick(pts: list, aspect: float, runs: list) -> str | None:
    """U/D when the finger ends up/down of the press after the last held direction, too briefly to be a hold."""
    if not runs or runs[-1][0] in ("U", "D"):
        return None
    x0, y0 = pts[0][1], pts[0][2]
    dx, dy = pts[-1][1] - x0, (pts[-1][2] - y0) * aspect
    if math.hypot(dx, dy) < STICK_DEAD or abs(dy) <= abs(dx):
        return None
    return "D" if dy > 0 else "U"


def classify(g: dict, aspect: float = 0.45) -> Move:
    pts = g["points"]
    t_end = int(pts[-1][0])
    x0, y0 = pts[0][1], pts[0][2]
    x1, y1 = pts[-1][1], pts[-1][2]
    dx = x1 - x0
    dy = (y1 - y0) * aspect
    far = max(math.hypot(p[1] - x0, (p[2] - y0) * aspect) for p in pts)
    dur = int(g.get("dur_ms", t_end))
    turns: list[str] = []
    runs = stick_runs(pts, aspect) if dur >= FLICK_MAX_MS and far >= TAP_MAX_MOVE else []
    end_flick = _end_flick(pts, aspect, runs)
    if far < TAP_MAX_MOVE:
        kind = "tap" if dur < TAP_MAX_MS else "long_press"
        direction = None
    elif dur < FLICK_MAX_MS and abs(dy) > abs(dx) and abs(dy) >= FLICK_MIN_MOVE:
        kind, direction = "flick", ("U" if dy < 0 else "D")
    elif runs and end_flick:
        # held a direction, then a quick up/down at the very end (onto stairs) before lifting
        kind, direction = "walk", runs[0][0]
        turns = [("+" if k else "") + f"{d} {_ms(b - a)}" for k, (d, a, b) in enumerate(runs)] + [f"+{end_flick}"]
    elif len(runs) > 1:
        # turned without lifting the finger: one move per direction held, all with their ms.
        # Only the first starts from rest; "+" marks a turn made while already walking
        # (no flick, no ramp-up: the party keeps its speed)
        kind, direction = "walk", runs[0][0]
        turns = [("+" if k else "") + f"{d} {_ms(b - a)}" for k, (d, a, b) in enumerate(runs)]
    elif runs:
        # one direction held: a walk (an up/down hold is the party going along stairs)
        kind, direction = "walk", runs[0][0]
    elif dur >= FLICK_MAX_MS and abs(dx) >= abs(dy):
        kind, direction = "walk", ("R" if dx > 0 else "L")
    else:
        kind = "swipe"
        direction = ("R" if dx > 0 else "L") if abs(dx) >= abs(dy) else ("U" if dy < 0 else "D")
    return Move(
        i=int(g.get("i", 0)),
        kind=kind,
        start=(x0, y0),
        end=(x1, y1),
        dur_ms=dur,
        gap_ms=int(g.get("gap_ms", 0)),
        label=(g.get("label") or None),
        direction=direction,
        frame=(g.get("frame") or None),
        echo_ok=bool(g.get("echo_ok", True)),
        points=pts,
        turns=turns,
    )


def moves(rec: Recording) -> list[Move]:
    return [classify(g, rec.aspect) for g in rec.gestures]


def summary(rec: Recording) -> str:
    lines = [f"{rec.name}: {len(rec.gestures)} moves over {rec.end_ms / 1000:.0f}s ({rec.screen[0]}x{rec.screen[1]})"]
    for m in moves(rec):
        lines.append(f"  {m.i + 1:3d}. +{m.gap_ms / 1000:5.1f}s  {m.describe()}")
    return "\n".join(lines)


def _norm(s: str | None) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def _r(v: float) -> float:
    return round(v, 4)


def to_macro(rec: Recording, name: str | None = None, description: str | None = None) -> str:
    """A macro .yaml that plays the recording with plain steps (no images, safe to commit).

    Taps become ``tap_exact`` at the recorded spot, flicks a ``drag``, and holds/slides a
    ``gesture`` with the finger's path and timing (points every 50 ms); otherwise a ``drag`` from the
    first to the last point over the recorded time (how the macros walk), with the recorded
    pauses as ``wait``. Tune it afterwards: replace pauses with screen checks where loading
    times vary.
    """
    name = name or rec.name
    lines = [
        f"# Generated by `another-macro recording` from the recording '{rec.name}'",
        "# (recorded with the app's ⏺ button). Coordinates are fractions of the screen.",
        f"name: {json.dumps(name)}",
        f"description: {json.dumps(description or f'Fixed replay of {rec.name} ({len(rec.gestures)} moves)')}",
        "group: recorded",
        "params:",
        '  pace: {default: 1.0, help: "how much of the recorded pause to keep between moves"}',
        "screens: []",
        "setup:",
        f"  - status: {json.dumps('replaying ' + name)}",
    ]
    for m in moves(rec):
        if m.gap_ms >= 50:
            lines.append(f"  - wait: {m.gap_ms / 1000:.2f} * pace")
        note = f"   # {m.i + 1}: {m.kind}" + (f" '{m.label}'" if m.label else "") + (f" {m.token}" if m.token else "")
        if m.kind == "tap":
            lines.append(f"  - tap_exact: {{x: {_r(m.start[0])}, y: {_r(m.start[1])}}}{note}")
        elif len(m.points) <= 2 or m.kind in ("flick", "long_press"):
            lines.append(
                f"  - drag: {{from: [{_r(m.start[0])}, {_r(m.start[1])}], to: [{_r(m.end[0])}, {_r(m.end[1])}], ms: {m.dur_ms}}}{note}"
            )
        else:
            # the finger's whole path with its timing (turns, holds): played as one continued stroke
            pts = ", ".join(f"[{int(t)}, {_r(x)}, {_r(y)}]" for t, x, y in _thin(m.points))
            lines.append(f"  - gesture: [{pts}]{note}")
    lines.append(f"  - stop: {json.dumps('replayed ' + name)}")
    return "\n".join(lines) + "\n"


def _thin(pts: list, step_ms: int = 50) -> list:
    """Keep a point every step_ms (and the corners' neighbours: every point that turns), plus the last."""
    out = [pts[0]]
    for p in pts[1:-1]:
        if p[0] - out[-1][0] >= step_ms:
            out.append(p)
    if pts[-1] is not out[-1]:
        out.append(pts[-1])
    return out


def _walk_start(ms: list[Move]) -> int:
    """Index where the walk to the target starts: after the last tap before the last walk/flick."""
    walking = [k for k, m in enumerate(ms) if m.kind in ("walk", "flick")]
    if not walking:
        return len(ms)
    taps = [k for k in range(walking[-1]) if ms[k].kind == "tap"]
    return taps[-1] + 1 if taps else 0


def cat_spot(rec: Recording) -> dict:
    """The cat_spots.yaml fields a recording from point zero to a cat shows.

    Point zero is the Present World Map with Baruoki on screen. Reads the era from a tapped
    era tab, the continent from the entry tapped after "Expand", the place from the last
    place-like label tapped before the walk, and the sub-location from a label tapped after
    it. The walk from the arrival to the cat becomes ``path`` moves, and the last tap after
    it is where the cat was (``cat_tap``).
    """
    ms = moves(rec)
    w0 = _walk_start(ms)
    trip, rest = ms[:w0], ms[w0:]
    era = continent = place = sub = None
    after_expand = False
    labels: list[str] = []
    for m in trip:
        if m.kind != "tap" or not m.label:
            continue
        n = _norm(m.label)
        if n in ERAS:
            era = m.label.strip().title()
            continue
        if n == "expand":
            after_expand = True
            continue
        if after_expand and n not in GENERIC:
            continent = m.label.strip()
            after_expand = False
            continue
        if n not in GENERIC:
            labels.append(m.label.strip())
    if labels:
        place = labels[0] if len(labels) == 1 else labels[-2]
        sub = labels[-1] if len(labels) > 1 else None
    path = [t for m in rest for t in m.tokens]
    taps = [m for m in rest if m.kind == "tap"]
    out: dict = {
        "era": era,
        "continent": continent,
        "place": place,
        "sub": sub,
        "path": path,
    }
    if taps:
        out["cat_tap"] = [_r(taps[-1].start[0]), _r(taps[-1].start[1])]
    other = [m for m in rest if m.kind in ("swipe", "long_press")]
    if other:
        out["unrouted"] = [m.describe() for m in other]
    return out


def dungeon_stops(rec: Recording) -> list[dict]:
    """The walks of a dungeon map, one per stop, split at the tap that ends each walk.

    Each stop is ``{"leg": [what, moves...], "move": n, "tap": (x, y), "frame": path}``.
    The first tap after a walk is the chest's "!" ("chest N"); taps right after it close the
    "Obtained …" box and are left out. The last walk is the "exit".
    """
    stops: list[dict] = []
    cur: list[str] = []
    chests = 0
    for m in moves(rec):
        if m.tokens:
            cur.extend(m.tokens)
        elif m.kind == "tap" and cur:
            chests += 1
            stops.append({"leg": [f"chest {chests}", *cur], "move": m.i + 1,
                          "tap": (_r(m.start[0]), _r(m.start[1])), "frame": m.frame, "label": m.label})
            cur = []
    if cur:
        stops.append({"leg": ["exit", *cur], "move": None, "tap": None, "frame": None, "label": None})
    return stops


def dungeon_legs(rec: Recording) -> list[list[str]]:
    """The ``path:`` legs of :func:`dungeon_stops` (dungeons.yaml form)."""
    return [s["leg"] for s in dungeon_stops(rec)]


# a chest crop around the tapped "!": the bubble and the chest under it (fractions of the screen)
CHEST_CROP_W = 0.07
CHEST_CROP_UP = 0.07
CHEST_CROP_DOWN = 0.16


def chest_crops(rec: Recording, out: Path) -> list[Path]:
    """Cut the chest under each chest tap out of the frame recorded just before it.

    The crops are screenshots of the game: keep them on the phone or in a local folder
    (never commit them unless Bruno says game crops are fine).
    """
    import cv2  # noqa: PLC0415  (the cv extra)

    out.mkdir(parents=True, exist_ok=True)
    written = []
    for s in dungeon_stops(rec):
        if not s["frame"] or rec.folder is None:
            continue
        img = cv2.imread(str(rec.folder / s["frame"]))
        if img is None:
            continue
        h, w = img.shape[:2]
        x, y = s["tap"]
        x0, x1 = max(0, int((x - CHEST_CROP_W / 2) * w)), min(w, int((x + CHEST_CROP_W / 2) * w))
        y0, y1 = max(0, int((y - CHEST_CROP_UP) * h)), min(h, int((y + CHEST_CROP_DOWN) * h))
        if x1 - x0 < 4 or y1 - y0 < 4:
            continue
        p = out / f"{rec.name}-{s['leg'][0].replace(' ', '')}.png"
        cv2.imwrite(str(p), img[y0:y1, x0:x1])
        written.append(p)
    return written


def _yaml_list(items: list[str]) -> str:
    return "[" + ", ".join(items) + "]"


def cat_spot_yaml(rec: Recording, key: str | None = None) -> str:
    spot = cat_spot(rec)
    key = key or spot.get("place") or rec.name
    lines = [f"# from the recording '{rec.name}' (check the labels: they were read by OCR)", f"{key}:"]
    for k in ("era", "continent", "place", "sub"):
        v = spot.get(k)
        lines.append(f"  {k}: {json.dumps(v) if v is not None else 'null'}")
    lines.append(f"  recorded_path: {_yaml_list(spot['path'])}   # arrival -> cat, from the recording")
    if "cat_tap" in spot:
        lines.append(f"  cat_tap: {spot['cat_tap']}   # where the cat bubble was tapped")
    for d in spot.get("unrouted", []):
        lines.append(f"  # not a route move: {d}")
    return "\n".join(lines) + "\n"


def dungeon_yaml(rec: Recording) -> str:
    lines = [
        f"# path legs from the recording '{rec.name}'",
        "# each chest leg ends at the chest's \"!\": find it by its image (not the recorded spot), tap it,",
        "# and wait for \"Obtained ...\" before the next leg; the exit leg ends at the stairs down",
        "path:",
    ]
    for s in dungeon_stops(rec):
        note = ""
        if s["move"]:
            note = f"   # move {s['move']}: tapped at {s['tap']}" + (f", frame {s['frame']}" if s["frame"] else "")
        lines.append(f"  - {_yaml_list(s['leg'])}{note}")
    return "\n".join(lines) + "\n"
