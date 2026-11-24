"""The Phantom Crystal Dimension's fixed world routes (`worlds:` in its macro, docs/dungeon-data.md)."""
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
MACRO = ROOT / "macros/dungeon/phantom-crystal.yaml"

HOLD = re.compile(r"^[LR] \d+$")
SPOT = re.compile(r"^(chest|cat|portal|stairs)( 0?\.\d+ 0?\.\d+)?$")
WAIT = re.compile(r"^wait \d+(\.\d+)?$")
WORDS = {"U", "D", "sensationalist", "moves_on"}


def worlds():
    return yaml.safe_load(MACRO.read_text(encoding="utf-8")).get("worlds")


def test_worlds_is_a_list_of_titled_routes():
    w = worlds()
    assert isinstance(w, list)
    titles = [e["title"] for e in w]
    assert all(isinstance(t, str) and t.strip() for t in titles)
    assert len(set(t.lower() for t in titles)) == len(titles), "two routes for one title"


def test_route_steps_are_known():
    for e in worlds():
        assert isinstance(e.get("route"), list) and e["route"], e["title"]
        for s in e["route"]:
            s = str(s).strip()
            assert HOLD.match(s) or SPOT.match(s) or WAIT.match(s) or s in WORDS, f"{e['title']}: unknown step '{s}'"


def test_app_knows_every_step_word():
    kt = (ROOT / "android/app/src/main/java/anothermacro/logic/PhantomCrystal.kt").read_text(encoding="utf-8")
    words = re.search(r"STEP_WORDS = setOf\(([^)]*)\)", kt).group(1)
    for w in ["l", "r", "u", "d", "wait", "chest", "cat", "portal", "stairs", "sensationalist", "moves_on"]:
        assert f'"{w}"' in words
