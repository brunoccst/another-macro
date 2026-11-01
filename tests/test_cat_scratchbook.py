"""cat-scratchbook against a simulated field + Diary."""
import pytest

from another_macro.device import FakeDevice
from another_macro.scenario import base
from another_macro.scenarios import cat_scratchbook
from another_macro.scenarios.cat_scratchbook import CatScratchbook

from helpers import ELEMENTS, render, write_library

EXTRA = {
    "scratchbook_header": (0.42, 0.0, 0.16, 0.05),
    "riddle_round_door": (0.37, 0.2, 0.22, 0.04),
    "riddle_bench_town_air": (0.37, 0.35, 0.23, 0.04),
}


@pytest.fixture
def scenario(tmp_path, monkeypatch):
    for k, v in EXTRA.items():
        monkeypatch.setitem(ELEMENTS, k, v)
    monkeypatch.setenv("ANOTHER_MACRO_HOME", str(tmp_path / "home"))
    monkeypatch.setattr(base.Context, "wait", lambda self, seconds: None)
    lib = write_library(tmp_path / "lib", {
        "scratchbook": {"require": ["scratchbook_header"]},
        "field": {"require": ["field_menu_button"]},
    })
    s = CatScratchbook()
    s.library_files = lambda: [lib]
    s.tick_s = 0
    return s


def test_opens_reads_and_closes_the_scratchbook(scenario, tmp_path):
    state = {"screen": "field"}
    frames = {
        "field": render(["field_menu_button"]),
        # two clues we know + one row we have no template for
        "book": render(["scratchbook_header", "riddle_round_door", "riddle_bench_town_air"]),
    }

    def frame(device):
        for a in device.actions[state.get("seen", 0):]:
            if a[0] == "tap":
                y = a[2] / 1080
                state["screen"] = "book" if state["screen"] == "field" and y > 0.85 else "field"
        state["seen"] = len(device.actions)
        return frames[state["screen"]]

    result = base.run(scenario, FakeDevice(frame), run_dir=tmp_path / "run")
    assert result.stats == {"known_riddles": 2, "unknown_riddles": 1}
    assert "Sarupa (Antiquity)" in result.reason and "Sky Fortress Eeza (Future)" in result.reason
    assert "1 riddle(s) not in riddles.yaml" in result.reason
    assert len(list((tmp_path / "run").glob("riddle-*.png"))) == 3


def test_every_clue_has_a_location():
    riddles = cat_scratchbook.load_riddles()
    assert len(riddles) > 60
    assert all(r["location"] and r["text"] for r in riddles.values())


def test_feather_finder_matches_its_own_template_and_not_a_blank_frame():
    import cv2
    import numpy as np

    from another_macro.scenarios.cat_scratchbook import FEATHER_FILE, FEATHER_MIN, FeatherFinder

    finder = FeatherFinder()
    bubble = cv2.imread(str(FEATHER_FILE))
    frame = np.full((1080, 2400, 3), 60, np.uint8)
    h, w = bubble.shape[:2]
    frame[400 : 400 + h, 1000 : 1000 + w] = bubble
    score, at = finder.find(frame)
    assert score >= FEATHER_MIN
    assert abs(at.x * 2400 - (1000 + w / 2)) < 12 and abs(at.y * 1080 - (400 + h / 2)) < 12
    score, _ = finder.find(np.full((1080, 2400, 3), 60, np.uint8))
    assert score < FEATHER_MIN
