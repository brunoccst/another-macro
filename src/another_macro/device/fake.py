"""A scripted device for tests and offline dry runs.

It serves screenshots from a callable (or a fixed list, last one repeating) and
records every input, so a scenario can be exercised without a phone.
"""
from __future__ import annotations

from typing import Callable

import numpy as np

from .base import Device


class FakeDevice(Device):
    def __init__(self, frames: list[np.ndarray] | Callable[["FakeDevice"], np.ndarray]) -> None:
        self._frames = frames
        self._i = 0
        self.actions: list[tuple] = []

    def screenshot(self) -> np.ndarray:
        if callable(self._frames):
            frame = self._frames(self)
        else:
            frame = self._frames[min(self._i, len(self._frames) - 1)]
            self._i += 1
        self._last = frame
        return frame

    def size(self) -> tuple[int, int]:
        frame = getattr(self, "_last", None)
        if frame is None:
            frame = self.screenshot()
        return frame.shape[1], frame.shape[0]

    def tap_px(self, x: int, y: int) -> None:
        self.actions.append(("tap", x, y))

    def swipe_px(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None:
        self.actions.append(("swipe", x1, y1, x2, y2, duration_ms))

    def back(self) -> None:
        self.actions.append(("back",))

    # app lifecycle, for start/stop scenarios
    running: bool = True

    def start_app(self, package: str) -> None:
        self.actions.append(("start_app", package))
        self.running = True

    def stop_app(self, package: str) -> None:
        self.actions.append(("stop_app", package))
        self.running = False

    def app_running(self, package: str) -> bool:
        return self.running
