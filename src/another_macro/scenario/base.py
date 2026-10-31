"""Scenario plugin format and the loop that runs it.

A scenario is a class with:

* `name`, `description`
* `params`: user-tunable options (`another-macro play <name> --param battles=20`)
* `screens`: which screens to recognise, in priority order
* one `on_<screen>(ctx)` method per screen it cares about

The runner repeatedly takes a screenshot, works out which screen the game is on,
and calls the matching handler. Handlers do one small step (tap a button, walk a
bit) and return — the next screenshot decides what happens next. This keeps
scenarios robust: a missed tap or an unexpected popup just means the next tick
sees a different screen.
"""
from __future__ import annotations

import logging
import random
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import cv2
import numpy as np

from .. import config
from ..device.base import Device
from ..vision.geometry import Point, Region
from ..vision.templates import Library, Match, Vision

ASSETS = Path(__file__).resolve().parent.parent / "assets" / "templates"
COMMON_LIBRARY = ASSETS / "library.yaml"

log = logging.getLogger("another_macro")


@dataclass
class Param:
    type: Callable[[str], Any]
    default: Any
    help: str = ""


class StopScenario(Exception):
    """Raised (via ctx.stop) to end a run cleanly with a reason."""


class Context:
    """What a handler gets: the device, the current frame, and helpers."""

    def __init__(self, device: Device, vision: Vision, params: dict[str, Any], run_dir: Path, jitter: bool = True):
        self.device = device
        self.vision = vision
        self.params = params
        self.run_dir = run_dir
        self.jitter = jitter
        self.stats: Counter[str] = Counter()
        self.frame: np.ndarray | None = None
        self.screen: str | None = None
        self.started = time.monotonic()

    # --- seeing -----------------------------------------------------------
    def refresh(self) -> np.ndarray:
        self.frame = self.device.screenshot()
        return self.frame

    def find(self, template: str) -> Match | None:
        assert self.frame is not None
        return self.vision.find(self.frame, template)

    def save_frame(self, tag: str) -> Path:
        path = self.run_dir / f"{time.strftime('%H%M%S')}-{tag}.png"
        if self.frame is not None:
            cv2.imwrite(str(path), self.frame)
        return path

    # --- acting -----------------------------------------------------------
    def _point(self, target: Point | Match | Region | str) -> Point:
        if isinstance(target, str):
            match = self.find(target)
            if match is None:
                raise LookupError(f"'{target}' not visible")
            target = match
        if isinstance(target, Match):
            target = target.region
        if isinstance(target, Region):
            if not self.jitter:
                return target.center
            # land somewhere in the middle half of the button, like a finger would
            return Point(
                target.x + target.w * random.uniform(0.25, 0.75),
                target.y + target.h * random.uniform(0.25, 0.75),
            )
        return target

    def tap(self, target: Point | Match | Region | str) -> None:
        self.device.tap(self._point(target))

    def hold(self, target: Point | Match | Region | str, duration_ms: int, exact: bool = False) -> None:
        if self.jitter and not exact:
            duration_ms = int(duration_ms * random.uniform(0.9, 1.1))
        self.device.hold(self._point(target), duration_ms)

    def wait(self, seconds: float) -> None:
        if self.jitter:
            seconds *= random.uniform(0.9, 1.2)
        time.sleep(seconds)

    def stop(self, reason: str) -> None:
        raise StopScenario(reason)

    @property
    def elapsed_minutes(self) -> float:
        return (time.monotonic() - self.started) / 60


class Scenario:
    name: str = ""
    description: str = ""
    params: dict[str, Param] = {}
    # screens to recognise, in priority order (first match wins)
    screens: list[str] = []
    # seconds on unrecognised screens (animations, loading, popups) before giving up
    unknown_timeout_s: float = 45
    # pause between ticks
    tick_s: float = 0.4
    # Another Eden only runs in landscape: a portrait frame means another app is in front
    landscape: bool = True
    # how often to check for system overlays that block input (e.g. Game Booster's touch lock)
    overlay_check_s: float = 30

    def library_files(self) -> list[Path]:
        """Common templates plus the scenario's own templates/library.yaml, if any."""
        import inspect

        own = Path(inspect.getfile(type(self))).resolve().parent / "templates" / "library.yaml"
        return [COMMON_LIBRARY, own]

    def setup(self, ctx: Context) -> None:
        pass

    def on_unknown(self, ctx: Context) -> None:
        pass

    def summary(self, ctx: Context) -> str:
        return ", ".join(f"{k}={v}" for k, v in sorted(ctx.stats.items())) or "nothing counted"


def is_blank(frame: np.ndarray) -> bool:
    """True for an all-black frame, which is what screencap returns while the screen is off."""
    return float(frame.max()) < 8


@dataclass
class RunResult:
    reason: str
    stats: dict[str, int]
    run_dir: Path


def run(
    scenario: Scenario,
    device: Device,
    params: dict[str, Any] | None = None,
    *,
    minutes: float | None = None,
    jitter: bool = True,
    run_dir: Path | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> RunResult:
    """Run `scenario` until it stops itself, times out, or a stop is requested."""
    values = {k: p.default for k, p in scenario.params.items()}
    values.update(params or {})
    run_dir = run_dir or config.runs_dir() / f"{time.strftime('%Y%m%d-%H%M%S')}-{scenario.name}"
    run_dir.mkdir(parents=True, exist_ok=True)

    library = Library.load(*scenario.library_files())
    missing = sorted(
        {t for s in scenario.screens if s in library.screens for t in library.screens[s].require} & set(library.missing())
    )
    if missing:
        log.warning("templates not captured yet (those screens can't be recognised): %s", ", ".join(missing))

    ctx = Context(device, Vision(library), values, run_dir, jitter=jitter)
    stop_file = config.stop_file()
    stop_file.unlink(missing_ok=True)
    unknown_since: float | None = None
    last_screen: str | None = None
    reason = "finished"
    last_overlay_check = float("-inf")

    # every run keeps its own log next to any screenshots it saved
    file_log = logging.FileHandler(run_dir / "log.txt", encoding="utf-8")
    file_log.setFormatter(logging.Formatter("%(asctime)s %(message)s", "%H:%M:%S"))
    log.addHandler(file_log)
    log.info("running %s with %s (run folder: %s)", scenario.name, values, run_dir)
    try:
        device.wake()
        scenario.setup(ctx)
        while True:
            if stop_file.exists():
                stop_file.unlink(missing_ok=True)
                raise StopScenario("stop requested")
            if minutes and ctx.elapsed_minutes >= minutes:
                raise StopScenario(f"time limit ({minutes} min) reached")

            now = time.monotonic()
            if now - last_overlay_check >= scenario.overlay_check_s:
                last_overlay_check = now
                overlay = device.blocking_overlay()
                if overlay:
                    raise StopScenario(f"'{overlay}' is covering the game and blocks input; turn it off and run again")

            frame = ctx.refresh()
            if is_blank(frame):
                # screen off (screencap returns a black frame): wake it instead of counting as unknown
                if last_screen != "screen off":
                    log.info("screen: off, waking it")
                    last_screen = "screen off"
                device.wake()
                sleep(1.0)
                continue
            if scenario.landscape and frame.shape[0] > frame.shape[1]:
                # never tap into whatever app is in front; wait for the game to come back
                if last_screen != "game not in front":
                    log.info("screen: game not in front (portrait frame), pausing input")
                    last_screen = "game not in front"
                unknown_since = None
                sleep(2.0)
                continue
            ctx.screen = ctx.vision.identify(frame, scenario.screens)
            if ctx.screen != last_screen:
                log.info("screen: %s", ctx.screen or "unknown")
                last_screen = ctx.screen

            if ctx.screen is None:
                now = time.monotonic()
                unknown_since = unknown_since or now
                if now - unknown_since > scenario.unknown_timeout_s:
                    shot = ctx.save_frame("unknown")
                    raise StopScenario(f"stuck on an unrecognised screen for {scenario.unknown_timeout_s:.0f}s (saved {shot})")
                scenario.on_unknown(ctx)
            else:
                unknown_since = None
                handler = getattr(scenario, f"on_{ctx.screen}", None)
                if handler:
                    handler(ctx)
            sleep(scenario.tick_s)
    except StopScenario as stop:
        reason = str(stop)
    except KeyboardInterrupt:
        reason = "interrupted"
    log.info("stopped: %s — %s", reason, scenario.summary(ctx))
    log.removeHandler(file_log)
    file_log.close()
    return RunResult(reason, dict(ctx.stats), run_dir)
