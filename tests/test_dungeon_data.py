"""Each dungeon's data lives in its own macro file (`dungeon:` block, docs/dungeon-data.md)."""
from pathlib import Path

import pytest
import yaml

from another_macro import dungeon_routes as dr

ROOT = Path(__file__).resolve().parents[1]
MACROS = ROOT / "macros/dungeon"
KEYS = {"name", "era", "area", "label", "requires", "boss", "maps", "area_map_scrolls", "camera_lags", "key_cost", "areas"}
MAP_KEYS = {"names", "segs", "links", "chests", "arrival", "exit", "path", "boss", "enemies", "steps", "chests_count",
            "sparkles", "fish", "jars", "sparkle_icons", "exit_walk", "exit_sign", "area_map_scrolls", "walk_scale", "portal"}


def specs():
    return {f.stem: yaml.safe_load(f.read_text(encoding="utf-8")) for f in sorted(MACROS.glob("*.yaml"))}


def steps(spec):
    """Every another_dungeon step's argument in a macro (handlers and unknown)."""
    out = []

    def walk(v):
        if isinstance(v, dict):
            if "another_dungeon" in v:
                out.append(v["another_dungeon"])
            for x in v.values():
                walk(x)
        elif isinstance(v, list):
            for x in v:
                walk(x)
    walk(spec.get("handlers"))
    walk(spec.get("unknown"))
    return out


def test_every_dungeon_macro_runs_its_own_data():
    """A macro with an another_dungeon step names itself, and carries the dungeon it runs."""
    found = 0
    for name, spec in specs().items():
        args = steps(spec)
        if not args:
            assert "dungeon" not in spec, name
            continue
        found += 1
        assert set(args) == {name}, f"{name}: another_dungeon must name the macro itself, got {args}"
        assert isinstance(spec.get("dungeon"), dict), f"{name}: no dungeon: block"
    assert found >= 5


@pytest.mark.parametrize("name", [n for n, s in specs().items() if "dungeon" in s])
def test_dungeon_block_keys(name):
    d = specs()[name]["dungeon"]
    for did, data in dr.dungeons(MACROS / f"{name}.yaml"):
        assert set(data) - {"areas"} <= KEYS, (did, set(data) - KEYS)
        assert data["name"] and data["era"] and data["maps"], did
        for m in data["maps"]:
            assert set(m) <= MAP_KEYS, (did, set(m) - MAP_KEYS)
            assert m["names"], did
        assert sum(1 for m in data["maps"] if m.get("boss")) == 1, f"{did}: one boss map"
    for a, area in (d.get("areas") or {}).items():
        assert set(area) <= {"boss", "maps"}, (name, a)


def test_known_dungeons_load_as_before():
    """The routes each macro runs (spot checks of the data moved out of the old shared file)."""
    loaded = {did: d for f in dr.dungeon_files() for did, d in dr.dungeons(f)}
    assert set(loaded) == {"dogma-tower", "burning-beast-kings-castle", "sea-of-ice", "moonlight-forest", "underworld",
                           "underworld/cathedral", "underworld/tomb", "underworld/ark"}
    assert {did: len(d["maps"]) for did, d in loaded.items()} == {
        "dogma-tower": 4, "burning-beast-kings-castle": 5, "sea-of-ice": 4, "moonlight-forest": 4,
        "underworld": 4, "underworld/cathedral": 6, "underworld/tomb": 4, "underworld/ark": 4}
    assert loaded["dogma-tower"]["requires"] == ["Poporo"]
    assert loaded["burning-beast-kings-castle"]["requires"] == ["Deirdre"]
    assert loaded["dogma-tower"]["maps"][0]["path"][0] == ["chest 2", "L 5250", "U", "L 1320", "U", "R 1970"]
    # an area keeps the dungeon's own keys and sets its boss and maps
    cathedral = loaded["underworld/cathedral"]
    assert (cathedral["name"], cathedral["era"], cathedral["key_cost"]) == ("Underworld", "???", 2)
    assert cathedral["boss"] == "Haunted Queen Himika"
    assert cathedral["maps"][4]["path"][-1] == ["exit", "R 1320", "U", "L 2010"]
    assert loaded["underworld"]["boss"] == "Titan Ogre"
