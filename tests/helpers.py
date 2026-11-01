"""Synthetic "game screens" so the framework can be tested without a phone.

Each UI element is a random textured patch; a screen is a noisy background with
some patches pasted at fixed normalized positions. Templates are cut at the
reference resolution, frames can be rendered at any other resolution.
"""
from __future__ import annotations

import zlib
from pathlib import Path

import cv2
import numpy as np
import yaml

REF = (2400, 1080)

# element -> normalized (x, y, w, h)
ELEMENTS = {
    "field_menu_button": (0.03, 0.85, 0.08, 0.1),
    "battle_attack_button": (0.85, 0.75, 0.1, 0.15),
    "battle_auto_button": (0.05, 0.7, 0.08, 0.1),
    "battle_hud": (0.45, 0.03, 0.1, 0.08),
    "result_banner": (0.35, 0.1, 0.3, 0.1),
    "defeat_banner": (0.35, 0.3, 0.3, 0.12),
    "skill_list_open": (0.2, 0.02, 0.1, 0.06),
    "verdanta_last_skill_selected": (0.1, 0.62, 0.1, 0.05),
    "title_screen": (0.66, 0.18, 0.2, 0.05),
    "title_continue": (0.66, 0.26, 0.1, 0.05),
    "close_app_dialog": (0.66, 0.34, 0.14, 0.05),
    "ad_offer_header": (0.66, 0.42, 0.14, 0.05),
    "watch_video_button": (0.66, 0.5, 0.1, 0.05),
    "premium_thanks": (0.66, 0.58, 0.14, 0.05),
    "dialog_close_button": (0.82, 0.58, 0.08, 0.05),
    "reward_inbox_thanks": (0.66, 0.66, 0.14, 0.05),
    "dialog_ok_button": (0.82, 0.66, 0.08, 0.05),
}


def _patch(name: str, w: int, h: int) -> np.ndarray:
    rng = np.random.default_rng(zlib.crc32(name.encode()))
    small = rng.integers(0, 255, (8, 8, 3), dtype=np.uint8)
    return cv2.resize(small, (w, h), interpolation=cv2.INTER_NEAREST)


def render(elements: list[str], size: tuple[int, int] = REF, seed: int = 0) -> np.ndarray:
    """A frame at `size` showing `elements`."""
    w, h = REF
    rng = np.random.default_rng(seed)
    img = np.full((h, w, 3), 90, np.uint8)
    img = cv2.add(img, rng.integers(0, 30, (h, w, 3), dtype=np.uint8))
    for name in elements:
        x, y, ew, eh = ELEMENTS[name]
        px, py, pw, ph = int(x * w), int(y * h), int(ew * w), int(eh * h)
        img[py : py + ph, px : px + pw] = _patch(name, pw, ph)
    if size != REF:
        img = cv2.resize(img, size, interpolation=cv2.INTER_AREA)
    return img


def write_library(folder: Path, screens: dict | None = None) -> Path:
    """Cut every element as a template at REF resolution and write library.yaml."""
    folder.mkdir(parents=True, exist_ok=True)
    full = render(list(ELEMENTS))
    w, h = REF
    templates = {}
    for name, (x, y, ew, eh) in ELEMENTS.items():
        px, py, pw, ph = int(x * w), int(y * h), int(ew * w), int(eh * h)
        cv2.imwrite(str(folder / f"{name}.png"), full[py : py + ph, px : px + pw])
        templates[name] = {
            "file": f"{name}.png",
            "ref": list(REF),
            "region": [max(0, x - 0.05), max(0, y - 0.05), min(1, ew + 0.1), min(1, eh + 0.1)],
        }
    screens = screens or {
        "field": {"require": ["field_menu_button"]},
        "battle_command": {"require": ["battle_attack_button"]},
        "battle": {"require": ["battle_hud"]},
        "battle_result": {"require": ["result_banner"]},
        "defeat": {"require": ["defeat_banner"]},
    }
    path = folder / "library.yaml"
    path.write_text(yaml.safe_dump({"templates": templates, "screens": screens}, sort_keys=False))
    return path
