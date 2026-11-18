"""The list of every Another Dungeon (dungeon_list.yaml) and the dungeons the macros know."""
from pathlib import Path

import yaml

DATA = Path(__file__).resolve().parents[1] / "src/another_macro/scenarios/another_dungeon/templates"
MACROS = Path(__file__).resolve().parents[1] / "macros/dungeon"


def test_every_mapped_dungeon_is_in_the_list():
    listed = yaml.safe_load((DATA / "dungeon_list.yaml").read_text(encoding="utf-8"))
    mapped = yaml.safe_load((DATA / "dungeons.yaml").read_text(encoding="utf-8"))
    assert len(listed) >= 80
    # a hub dungeon's other chest areas ("<id>_<area>", picked by the macro's area option) say whose area they are
    assert {k for k, v in mapped.items() if "area_of" not in v} <= set(listed)
    assert all(v["area_of"] in listed and k.startswith(v["area_of"] + "_") for k, v in mapped.items() if "area_of" in v)
    for d in listed.values():
        assert d["era"] in {"Antiquity", "Present", "Future", "none"}
        assert "hard" in d or "very_hard" in d
        macro = d["macro"]
        assert macro == "todo" or (MACROS / f"{macro}.yaml").exists(), macro


def test_required_characters_are_names():
    mapped = yaml.safe_load((DATA / "dungeons.yaml").read_text(encoding="utf-8"))
    for k, v in mapped.items():
        req = v.get("requires", [])
        assert isinstance(req, list) and all(isinstance(n, str) and n.strip() for n in req), k
    assert mapped["dogma_tower"]["requires"] == ["Poporo"]
    assert mapped["burning_beast_kings_castle"]["requires"] == ["Deirdre"]
