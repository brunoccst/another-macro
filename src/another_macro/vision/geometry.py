"""Resolution-independent geometry.

Phones have different resolutions and aspect ratios, so everything a scenario
stores is *normalized*: x and y are fractions (0..1) of the screen width and
height. Pixel coordinates only exist at the moment we talk to a device.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Point:
    """A normalized point (0..1 on each axis)."""

    x: float
    y: float

    def to_px(self, width: int, height: int) -> tuple[int, int]:
        return round(self.x * width), round(self.y * height)

    @classmethod
    def from_px(cls, x: float, y: float, width: int, height: int) -> "Point":
        return cls(x / width, y / height)


@dataclass(frozen=True)
class Region:
    """A normalized rectangle: top-left (x, y) plus width and height, all 0..1."""

    x: float
    y: float
    w: float
    h: float

    FULL: "Region" = None  # type: ignore[assignment]  # set below

    @classmethod
    def parse(cls, value) -> "Region":
        if value is None:
            return Region.FULL
        if isinstance(value, Region):
            return value
        x, y, w, h = (float(v) for v in value)
        return cls(x, y, w, h)

    def to_px(self, width: int, height: int) -> tuple[int, int, int, int]:
        """Pixel box (left, top, right, bottom), clamped to the image."""
        left = max(0, min(width, round(self.x * width)))
        top = max(0, min(height, round(self.y * height)))
        right = max(left, min(width, round((self.x + self.w) * width)))
        bottom = max(top, min(height, round((self.y + self.h) * height)))
        return left, top, right, bottom

    @property
    def center(self) -> Point:
        return Point(self.x + self.w / 2, self.y + self.h / 2)

    def padded(self, pad: float) -> "Region":
        """Grow by `pad` on every side (normalized), clamped to the screen."""
        x0, y0 = max(0.0, self.x - pad), max(0.0, self.y - pad)
        x1, y1 = min(1.0, self.x + self.w + pad), min(1.0, self.y + self.h + pad)
        return Region(x0, y0, x1 - x0, y1 - y0)

    def as_list(self, digits: int = 4) -> list[float]:
        return [round(v, digits) for v in (self.x, self.y, self.w, self.h)]


Region.FULL = Region(0.0, 0.0, 1.0, 1.0)
