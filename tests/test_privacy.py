"""Guards for a public repo: only small UI template crops may be committed.

A full screenshot of the game (or anything big enough to show account
details) failing here is the point — crop it down to the button instead.
"""
import subprocess
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parent.parent
# a template is a button/label crop; a full phone screen is ~2400x1080
MAX_TEMPLATE_SIDE = 700
MAX_TEMPLATE_AREA = 300 * 300


def _tracked(pattern: str) -> list[Path]:
    out = subprocess.run(["git", "ls-files", pattern], cwd=ROOT, capture_output=True, text=True, check=True)
    return [ROOT / line for line in out.stdout.splitlines() if line]


def test_committed_images_are_small_crops():
    too_big = []
    for path in _tracked("*.png") + _tracked("*.jpg") + _tracked("*.jpeg"):
        if "tests/fixtures" in path.as_posix():
            continue
        img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
        assert img is not None, f"unreadable image {path}"
        h, w = img.shape[:2]
        if max(w, h) > MAX_TEMPLATE_SIDE or w * h > MAX_TEMPLATE_AREA:
            too_big.append(f"{path.relative_to(ROOT)} ({w}x{h})")
    assert not too_big, "images too large to be template crops (screenshots?): " + ", ".join(too_big)


def test_no_run_data_or_keys_committed():
    banned = [p for p in _tracked("*") if any(part in {"runs", ".another-macro", ".android"} for part in p.parts)]
    banned += [p for p in _tracked("*") if p.name in {"adbkey", "adbkey.pub", "serial", "log.txt"}]
    assert not banned, f"local run data / keys must not be committed: {banned}"
