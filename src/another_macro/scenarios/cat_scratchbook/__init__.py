"""Daily Cat Scratchbook: see today's three cat riddles.

Field -> Diary opens the Cat Scratchbook: three riddles, each hinting at a map
where a cat is hiding, plus a weekly paw track with a gift at the end.

This first version only reads the book: it opens it, recognises the riddles it
already knows (one template per riddle text, in templates/library.yaml), saves
crops of the ones it doesn't know to the run folder so they can be added, closes
the book, and reports. Travelling to each cat comes next, riddle by riddle, as
the riddle -> location table (riddles.yaml) fills up.

Start state: on any field map (the Diary button is in the field's bottom bar).

Notes for the travel step (learned on the phone, 2026-10-31):
- World Map -> era -> area -> the free "Travel" dialog takes you to the town.
- The area map (tap the minimap) marks today's scratchbook cat with a yellow
  FEATHER icon. Plain cat-head icons are ordinary cats you can take along as a
  companion ("Take <name> with you?"): never confirm that dialog, it swaps the
  current companion cat.
- A cat that can be interacted with shows a paw bubble (`cat_paw_button`) when
  the party is next to it. Cats wander, so the tap must follow the detection
  within a fraction of a second; PNG screenshots (~2.4 s) are too slow, raw
  `screencap` (~0.7 s) works.
- The field's bottom buttons hide while walking, so `field` can't be used to
  tell "still on the map" during a walk.
"""
from __future__ import annotations

from pathlib import Path

import cv2
import yaml

import time

from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

DIARY_BUTTON = Point(0.329, 0.922)
CLOSE_BUTTON = Point(0.88, 0.03)
# the three riddle text rows (x, y, w, h) on a 2400x1080 screen, as fractions
RIDDLE_ROWS = [(0.3725, 0.2, 0.2425, 0.0778), (0.3725, 0.35, 0.2425, 0.0778), (0.3725, 0.5, 0.2425, 0.0778)]
RIDDLES_FILE = Path(__file__).resolve().parent / "riddles.yaml"


def load_riddles() -> dict:
    """riddle template name -> {text, location, ...} for every riddle seen so far."""
    if not RIDDLES_FILE.is_file():
        return {}
    return yaml.safe_load(RIDDLES_FILE.read_text(encoding="utf-8")) or {}


WALK_FROM_X, WALK_Y, WALK_SLIDE = 0.5, 0.555, 0.0625
# a tap reaches the game this long after the frame it was aimed from was taken
TAP_LATENCY_S = 0.55
FEATHER_FILE = Path(__file__).resolve().parent / "templates" / "cat_feather_bubble.png"
FEATHER_MIN = 0.78


class FeatherFinder:
    """Find the scratchbook cat's feather bubble.

    The bubble is semi-transparent (the scene shows through it) and its size
    changes with each map's camera zoom, so plain template matching fails off
    the map it was cut on. The white feather itself is constant: binarize the
    frame, match the feather shape at a few scales, at half resolution (~0.15 s).
    """

    def __init__(self, path: Path = FEATHER_FILE) -> None:
        g = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if g is None:
            raise FileNotFoundError(path)
        h, w = g.shape
        inner = ((g > 215) * 255).astype("uint8")[int(h * 0.18) : int(h * 0.82), int(w * 0.18) : int(w * 0.82)]
        self.templates = [cv2.resize(inner, None, fx=k / 2, fy=k / 2, interpolation=cv2.INTER_AREA) for k in (0.95, 1.1, 1.3)]

    def find(self, frame) -> tuple[float, Point]:
        h, w = frame.shape[:2]
        top, bottom = int(h * 0.09), int(h * 0.76)
        g = cv2.cvtColor(frame[top:bottom], cv2.COLOR_BGR2GRAY)
        g = cv2.resize(g, None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA)
        binary = ((g > 180) * 255).astype("uint8")
        best, at = 0.0, Point(0, 0)
        for t in self.templates:
            r = cv2.matchTemplate(binary, t, cv2.TM_CCOEFF_NORMED)
            _, score, _, loc = cv2.minMaxLoc(r)
            if score > best:
                cx, cy = 2 * (loc[0] + t.shape[1] / 2), top + 2 * (loc[1] + t.shape[0] / 2)
                best, at = float(score), Point(cx / w, cy / h)
        return best, at


class CatScratchbook(Scenario):
    name = "cat-scratchbook"
    description = "Report today's Cat Scratchbook clues, or (mode=hunt) find the scratchbook cat on the current map."
    params = {
        "mode": Param(str, "read", "read = report today's clues; hunt = find the cat on the map you're standing on"),
        "first_walk": Param(int, -1, "hunt: direction of the first walk, -1 = left, 1 = right"),
        "pulse_ms": Param(int, 300, "hunt: length of each short walk; the party stands still between walks"),
        "max_walks": Param(int, 120, "hunt: give up after this many walks"),
        "span": Param(int, 8, "hunt: walks to either side of the start before turning (keeps the party inside the town)"),
    }
    screens = ["scratchbook", "battle_result", "skill_list", "battle_command", "battle", "field"]
    unknown_timeout_s = 20

    def setup(self, ctx: Context) -> None:
        self._opened = False
        self._read = False
        self._dir = ctx.params["first_walk"]
        self._walks = 0
        self._still = 0
        self._last_view = None
        self._found = 0
        self._pos = 0  # walks right minus walks left since the start
        self._feather = FeatherFinder() if ctx.params["mode"] == "hunt" else None

    # --- hunt ---------------------------------------------------------------
    def _hunt_field(self, ctx: Context) -> None:
        if self._found:
            ctx.stop("found the scratchbook cat")
        assert self._feather is not None and ctx.frame is not None
        score, p1 = self._feather.find(ctx.frame)
        if score >= FEATHER_MIN:
            # the bubble rides on the cat, which keeps walking: measure its speed
            # from a second frame and tap where it will be when the tap lands
            t1 = time.monotonic()
            score2, p2 = self._feather.find(ctx.refresh())
            if score2 >= FEATHER_MIN:
                vx = (p2.x - p1.x) / max(time.monotonic() - t1, 0.05)
                p1 = Point(min(max(p2.x + vx * TAP_LATENCY_S, 0.02), 0.98), p2.y)
            ctx.device.tap(p1)
            ctx.stats["bubble_taps"] += 1
            ctx.wait(2.0)
            return
        if self._walks >= ctx.params["max_walks"]:
            ctx.save_frame("no-cat")
            ctx.stop(f"no scratchbook cat found after {self._walks} walks")
        if not self._moved(ctx):
            self._still += 1
            if self._still >= 2:  # hit a wall: turn around
                self._dir, self._still = -self._dir, 0
        else:
            self._still = 0
        span = ctx.params["span"]
        if span and abs(self._pos + self._dir) > span:
            self._dir = -self._dir  # sweep back and forth around the start
        x0 = WALK_FROM_X
        ctx.drag(Point(x0, WALK_Y), Point(x0 + WALK_SLIDE * self._dir, WALK_Y), ctx.params["pulse_ms"], exact=True)
        self._walks += 1
        self._pos += self._dir
        ctx.wait(0.4)

    def _moved(self, ctx: Context) -> bool:
        assert ctx.frame is not None
        h, w = ctx.frame.shape[:2]
        view = cv2.cvtColor(cv2.resize(ctx.frame[int(h * 0.25) : int(h * 0.8), : int(w * 0.8)], (160, 60)), cv2.COLOR_BGR2GRAY)
        prev, self._last_view = self._last_view, view.astype("int16")
        return prev is None or float(abs(self._last_view - prev).mean()) > 4.0

    # --- battles: a random encounter can start while hunting outside a town ----
    def on_battle_command(self, ctx: Context) -> None:
        self._last_view = None
        ctx.tap("battle_auto_button" if ctx.find("battle_auto_button") else "battle_attack_button")
        ctx.wait(1.0)

    def on_skill_list(self, ctx: Context) -> None:
        ctx.tap("battle_attack_button") if ctx.find("battle_attack_button") else ctx.device.back()
        ctx.wait(0.8)

    def on_battle_result(self, ctx: Context) -> None:
        ctx.tap(Point(0.5, 0.75))
        ctx.wait(0.8)

    # --- read ---------------------------------------------------------------
    def on_field(self, ctx: Context) -> None:
        if ctx.params["mode"] == "hunt":
            return self._hunt_field(ctx)
        if self._read:
            ctx.stop(self.report)
        if not self._opened:
            self._opened = True
            ctx.tap(DIARY_BUTTON)
            ctx.wait(2.0)

    def on_scratchbook(self, ctx: Context) -> None:
        if ctx.params["mode"] == "hunt":
            # finding the cat opens the book with reward banner(s) ("Obtained X",
            # "50000 Git obtained.", the weekly gift): clear them, then close it
            if ctx.stats["bubble_taps"] and not self._found:
                self._found = 1
                ctx.stats["cats_found"] += 1
                ctx.save_frame("found")
            for _ in range(3):
                ctx.tap(Point(0.5, 0.65))
                ctx.wait(1.2)
            ctx.tap(CLOSE_BUTTON)
            ctx.wait(1.5)
            return
        if not self._read:
            self._read = True
            self.report = self._read_riddles(ctx)
        ctx.tap(CLOSE_BUTTON)
        ctx.wait(1.5)

    def _read_riddles(self, ctx: Context) -> str:
        riddles = load_riddles()
        lib = ctx.vision.library.templates
        known = [n for n in riddles if n in lib and lib[n].available and ctx.find(n)]
        lines = []
        for name in known:
            r = riddles[name]
            where = r.get("location") or "location unknown"
            if r.get("era"):
                where += f" ({r['era']})"
            lines.append(f"{where}: {r.get('text', name)}")
        ctx.stats["known_riddles"] = len(known)
        unknown = 3 - len(known)
        if unknown > 0:
            # keep crops of every row so new riddles can be cut into templates
            assert ctx.frame is not None
            h, w = ctx.frame.shape[:2]
            for i, (x, y, rw, rh) in enumerate(RIDDLE_ROWS, 1):
                crop = ctx.frame[int(y * h) : int((y + rh) * h), int(x * w) : int((x + rw) * w)]
                cv2.imwrite(str(ctx.run_dir / f"riddle-{i}.png"), crop)
            lines.append(f"{unknown} riddle(s) not in riddles.yaml yet; row crops saved in {ctx.run_dir}")
        ctx.stats["unknown_riddles"] = max(unknown, 0)
        return "; ".join(lines)


SCENARIO = CatScratchbook
