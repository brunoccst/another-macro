"""Daily Cat Scratchbook: see today's three cat riddles.

Field -> Diary opens the Cat Scratchbook: three riddles, each hinting at a map
where a cat is hiding, plus a weekly paw track with a gift at the end.

This first version only reads the book: it opens it, recognises the riddles it
already knows (one template per riddle text, in templates/library.yaml), saves
crops of the ones it doesn't know to the run folder so they can be added, closes
the book, and reports. Travelling to each cat comes next, riddle by riddle, as
the riddle -> location table (riddles.yaml) fills up.

Start state: on any field map (the Diary button is in the field's bottom bar).
"""
from __future__ import annotations

from pathlib import Path

import cv2
import yaml

from ...scenario.base import Context, Scenario
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


class CatScratchbook(Scenario):
    name = "cat-scratchbook"
    description = "Open the Diary's Cat Scratchbook and report today's three cat riddles."
    params: dict = {}
    screens = ["scratchbook", "field"]
    unknown_timeout_s = 20

    def setup(self, ctx: Context) -> None:
        self._opened = False
        self._read = False

    def on_field(self, ctx: Context) -> None:
        if self._read:
            ctx.stop(self.report)
        if not self._opened:
            self._opened = True
            ctx.tap(DIARY_BUTTON)
            ctx.wait(2.0)

    def on_scratchbook(self, ctx: Context) -> None:
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
