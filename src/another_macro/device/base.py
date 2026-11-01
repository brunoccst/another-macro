"""The device interface every driver implements.

Scenarios never talk to ADB (or anything else) directly — they get a Device and
use normalized coordinates. That keeps scenarios portable across drivers: the
ADB driver (phone controlling itself via Wireless debugging, or a PC controlling
the phone), a fake driver for tests, and later a native-app or Steam driver.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from contextlib import contextmanager
from typing import Iterator

import numpy as np

from ..vision.geometry import Point


class Device(ABC):
    """Screen capture + touch input. Coordinates are normalized `Point`s."""

    @abstractmethod
    def screenshot(self) -> np.ndarray:
        """Current screen as a BGR image (height x width x 3)."""

    @abstractmethod
    def size(self) -> tuple[int, int]:
        """(width, height) of the screen in pixels, in its current orientation."""

    @abstractmethod
    def tap_px(self, x: int, y: int) -> None: ...

    @abstractmethod
    def swipe_px(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None: ...

    @abstractmethod
    def back(self) -> None:
        """Android back button."""

    def start_app(self, package: str) -> None:  # optional
        raise NotImplementedError

    def stop_app(self, package: str) -> None:  # optional
        raise NotImplementedError

    def app_running(self, package: str) -> bool:  # optional
        return True

    def is_locked(self) -> bool:  # optional
        """True while the lock screen is showing (nothing can be done until it's unlocked)."""
        return False

    def unlock(self) -> None:  # optional
        """Dismiss a lock screen that has no PIN/password (swipe or none)."""

    @contextmanager
    def keep_awake(self) -> Iterator[None]:  # optional
        """Keep the screen from timing out for the duration of a run."""
        yield

    def wake(self) -> None:  # optional
        """Turn the screen on if it is off."""

    def blocking_overlay(self) -> str | None:  # optional
        """Name of a system window covering the game and eating input, if any."""
        return None

    # --- normalized helpers used by scenarios -------------------------------
    def tap(self, p: Point) -> None:
        self.tap_px(*p.to_px(*self.size()))

    def hold(self, p: Point, duration_ms: int) -> None:
        """Press and hold in place (e.g. walking: hold on one side of the field)."""
        x, y = p.to_px(*self.size())
        # a zero-length swipe is not a held touch for Another Eden (the party doesn't
        # move); drifting one pixel makes it one
        w, _ = self.size()
        self.swipe_px(x, y, x + 1 if x + 1 < w else x - 1, y, duration_ms)

    def swipe(self, a: Point, b: Point, duration_ms: int = 300) -> None:
        w, h = self.size()
        self.swipe_px(*a.to_px(w, h), *b.to_px(w, h), duration_ms)
