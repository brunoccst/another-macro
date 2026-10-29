"""Template matching and screen identification.

A *template* is a small PNG cut from a real screenshot (a button, a label, an
icon). It remembers the resolution of the screenshot it came from (`ref`), so it
can be rescaled to whatever phone it is matched on, and a normalized search
`region` so we only look where it can appear.

A *screen* is a named game state ("field", "battle", ...) defined by templates
that must be visible (`require`) and templates that must not be (`reject`).

Both live in a YAML library file next to the PNGs:

    templates:
      field_menu_button:
        file: field_menu_button.png
        ref: [2400, 1080]           # size of the screenshot it was cut from
                                    # (no ref/PNG yet = "not captured", see capture-template)
        region: [0.0, 0.75, 0.25, 0.25]
        threshold: 0.85             # optional, default 0.85
    screens:
      field:
        require: [field_menu_button]
        reject: []
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np
import yaml

from .geometry import Point, Region

DEFAULT_THRESHOLD = 0.85


@dataclass(frozen=True)
class Match:
    name: str
    score: float
    region: Region  # where it was found, normalized

    @property
    def center(self) -> Point:
        return self.region.center


@dataclass
class Template:
    name: str
    path: Path
    ref: tuple[int, int] | None  # (width, height) of the source screenshot
    region: Region = Region.FULL
    threshold: float = DEFAULT_THRESHOLD
    # extra scale factors tried when the straight rescale misses (UI that does
    # not scale exactly with screen height, e.g. on unusual aspect ratios)
    scales: tuple[float, ...] = (1.0,)
    _gray: np.ndarray | None = field(default=None, repr=False)

    @property
    def available(self) -> bool:
        return self.ref is not None and self.path.is_file()

    def image(self) -> np.ndarray:
        if self._gray is None:
            img = cv2.imread(str(self.path), cv2.IMREAD_GRAYSCALE)
            if img is None:
                raise FileNotFoundError(f"template '{self.name}' image missing: {self.path}")
            self._gray = img
        return self._gray

    def match(self, frame_gray: np.ndarray) -> Match | None:
        """Best match inside the search region, or None if below threshold."""
        fh, fw = frame_gray.shape[:2]
        left, top, right, bottom = self.region.to_px(fw, fh)
        area = frame_gray[top:bottom, left:right]
        # the game UI scales with the screen's short side (landscape: height)
        assert self.ref is not None
        base = fh / self.ref[1]
        best: tuple[float, int, int, int, int] | None = None
        for extra in self.scales:
            tpl = _scaled(self.image(), base * extra)
            th, tw = tpl.shape[:2]
            if th < 4 or tw < 4 or th > area.shape[0] or tw > area.shape[1]:
                continue
            res = cv2.matchTemplate(area, tpl, cv2.TM_CCOEFF_NORMED)
            _, score, _, loc = cv2.minMaxLoc(res)
            if best is None or score > best[0]:
                best = (score, loc[0], loc[1], tw, th)
        if best is None or best[0] < self.threshold:
            return None
        score, x, y, tw, th = best
        return Match(
            self.name,
            float(score),
            Region((left + x) / fw, (top + y) / fh, tw / fw, th / fh),
        )


def _scaled(img: np.ndarray, factor: float) -> np.ndarray:
    if abs(factor - 1.0) < 1e-3:
        return img
    h, w = img.shape[:2]
    interp = cv2.INTER_AREA if factor < 1 else cv2.INTER_LINEAR
    return cv2.resize(img, (max(1, round(w * factor)), max(1, round(h * factor))), interpolation=interp)


@dataclass
class ScreenDef:
    name: str
    require: list[str]
    reject: list[str] = field(default_factory=list)


class Library:
    """Templates + screens merged from one or more YAML files."""

    def __init__(self) -> None:
        self.templates: dict[str, Template] = {}
        self.screens: dict[str, ScreenDef] = {}

    @classmethod
    def load(cls, *paths: Path | str) -> "Library":
        lib = cls()
        for p in paths:
            lib.add_file(Path(p))
        return lib

    def add_file(self, path: Path) -> None:
        if not path.is_file():
            return
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        base = path.parent
        for name, spec in (data.get("templates") or {}).items():
            self.templates[name] = Template(
                name=name,
                path=base / spec.get("file", f"{name}.png"),
                ref=tuple(spec["ref"]) if spec.get("ref") else None,
                region=Region.parse(spec.get("region")),
                threshold=float(spec.get("threshold", DEFAULT_THRESHOLD)),
                scales=tuple(spec.get("scales", (1.0,))),
            )
        for name, spec in (data.get("screens") or {}).items():
            spec = spec or {}
            self.screens[name] = ScreenDef(name, list(spec.get("require") or []), list(spec.get("reject") or []))

    def missing(self) -> list[str]:
        """Templates referenced by the library whose PNG has not been captured yet."""
        return sorted(n for n, t in self.templates.items() if not t.available)


class Vision:
    """Matches a library against frames. Results are cached per frame."""

    def __init__(self, library: Library) -> None:
        self.library = library
        self._frame_id: int | None = None
        self._gray: np.ndarray | None = None
        self._cache: dict[str, Match | None] = {}

    def _prepare(self, frame: np.ndarray) -> np.ndarray:
        if self._frame_id != id(frame):
            self._frame_id = id(frame)
            self._gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
            self._cache = {}
        return self._gray  # type: ignore[return-value]

    def find(self, frame: np.ndarray, name: str) -> Match | None:
        gray = self._prepare(frame)
        if name not in self._cache:
            tpl = self.library.templates.get(name)
            if tpl is None:
                raise KeyError(f"unknown template '{name}'")
            self._cache[name] = tpl.match(gray) if tpl.available else None
        return self._cache[name]

    def is_screen(self, frame: np.ndarray, screen: str) -> bool:
        sd = self.library.screens[screen]
        if not sd.require:
            return False
        return all(self.find(frame, t) for t in sd.require) and not any(self.find(frame, t) for t in sd.reject)

    def identify(self, frame: np.ndarray, candidates: list[str] | None = None) -> str | None:
        """First screen (in library order, or `candidates` order) the frame satisfies."""
        for name in candidates or list(self.library.screens):
            if name in self.library.screens and self.is_screen(frame, name):
                return name
        return None
