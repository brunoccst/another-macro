"""The list of every Another Dungeon (dungeon_list.yaml) and the dungeons the macros know."""
from pathlib import Path

import yaml

DATA = Path(__file__).resolve().parents[1] / "src/another_macro/scenarios/another_dungeon/templates"
MACROS = Path(__file__).resolve().parents[1] / "macros/dungeon"


def test_every_mapped_dungeon_is_in_the_list():
    listed = yaml.safe_load((DATA / "dungeon_list.yaml").read_text(encoding="utf-8"))
    mapped = yaml.safe_load((DATA / "dungeons.yaml").read_text(encoding="utf-8"))
    assert len(listed) >= 80
    assert set(mapped) <= set(listed)
    for d in listed.values():
        assert d["era"] in {"Antiquity", "Present", "Future", "none"}
        assert "hard" in d or "very_hard" in d
        macro = d["macro"]
        assert macro == "todo" or (MACROS / f"{macro}.yaml").exists(), macro
