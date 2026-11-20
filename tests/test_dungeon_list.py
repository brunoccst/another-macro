"""The list of every Another Dungeon (the table in docs/dungeon-macros-plan.md) and the dungeons the macros know."""
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "src/another_macro/scenarios/another_dungeon/templates"
MACROS = ROOT / "macros/dungeon"
PLAN = ROOT / "docs/dungeon-macros-plan.md"
KINDS = {"standard", "otherlands", "mysterious_vortex", "kairos", "sealed_realm"}


def listed_dungeons():
    """The plan's "All dungeons" table: id -> (name, kind, macro)."""
    rows = {}
    for line in PLAN.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\| `([a-z0-9_]+)` \| (.+?) \| (\w+) \| (.+?) \|$", line)
        if m:
            rows[m.group(1)] = (m.group(2), m.group(3), m.group(4))
    return rows


def test_every_mapped_dungeon_is_in_the_list():
    listed = listed_dungeons()
    mapped = yaml.safe_load((DATA / "dungeons.yaml").read_text(encoding="utf-8"))
    assert len(listed) >= 80
    # a hub dungeon's other chest areas ("<id>_<area>", picked by the macro's area option) say whose area they are
    assert {k for k, v in mapped.items() if "area_of" not in v} <= set(listed)
    assert all(v["area_of"] in listed and k.startswith(v["area_of"] + "_") for k, v in mapped.items() if "area_of" in v)
    for name, kind, macro in listed.values():
        assert kind in KINDS, name
        macro = macro.split(" ")[0]
        assert macro == "todo" or (MACROS / f"{macro}.yaml").exists(), macro


def test_every_dungeon_macro_is_in_the_list():
    named = {m.split(" ")[0] for _, _, m in listed_dungeons().values()}
    for f in MACROS.glob("*.yaml"):
        if f.stem != "phantom-crystal":
            assert f.stem in named, f.stem


def test_required_characters_are_names():
    mapped = yaml.safe_load((DATA / "dungeons.yaml").read_text(encoding="utf-8"))
    for k, v in mapped.items():
        req = v.get("requires", [])
        assert isinstance(req, list) and all(isinstance(n, str) and n.strip() for n in req), k
    assert mapped["dogma_tower"]["requires"] == ["Poporo"]
    assert mapped["burning_beast_kings_castle"]["requires"] == ["Deirdre"]
