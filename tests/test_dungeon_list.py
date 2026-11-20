"""The list of every Another Dungeon (the table in docs/macros.md) and the dungeons the macros know."""
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "src/another_macro/scenarios/another_dungeon/templates"
MACROS = ROOT / "macros/dungeon"
PLAN = ROOT / "docs/macros.md"
KINDS = {"standard", "otherlands", "mysterious_vortex", "kairos", "sealed_realm"}


def listed_dungeons():
    """The plan's "All dungeons" table: id -> (name, kind, macro)."""
    rows = {}
    for line in PLAN.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\| `([a-z0-9_]+)` \| (.+?) \| (\w+) \| (.+?) \|$", line)
        if m:
            rows[m.group(1)] = (m.group(2), m.group(3), m.group(4))
    return rows


def macro_name(cell):
    """A Macro cell: `todo`, or a link to the macro's file whose text is its name."""
    m = re.match(r"\[([a-z0-9-]+)\]\(\.\./macros/dungeon/([a-z0-9-]+)\.yaml\)$", cell)
    if m:
        assert m.group(1) == m.group(2), cell
        return m.group(1)
    assert cell == "todo", cell
    return cell


def test_every_macro_is_in_the_full_list():
    """The "All macros" table links every macro file in macros/, and only files that exist."""
    text = PLAN.read_text(encoding="utf-8")
    table = text[text.index("## All macros"):text.index("## Another Dungeons")]
    linked = re.findall(r"\| \[([a-z0-9-]+)\]\(\.\./macros/([a-z]+)/([a-z0-9-]+)\.yaml\) \|", table)
    assert all(name == stem for name, _, stem in linked), linked
    files = {(f.parent.name, f.stem) for f in (ROOT / "macros").glob("*/*.yaml")}
    assert {(group, stem) for _, group, stem in linked} == files


def test_every_mapped_dungeon_is_in_the_list():
    listed = listed_dungeons()
    mapped = yaml.safe_load((DATA / "dungeons.yaml").read_text(encoding="utf-8"))
    assert len(listed) >= 80
    # a hub dungeon's other chest areas ("<id>_<area>", picked by the macro's area option) say whose area they are
    assert {k for k, v in mapped.items() if "area_of" not in v} <= set(listed)
    assert all(v["area_of"] in listed and k.startswith(v["area_of"] + "_") for k, v in mapped.items() if "area_of" in v)
    for name, kind, macro in listed.values():
        assert kind in KINDS, name
        macro = macro_name(macro)
        assert macro == "todo" or (MACROS / f"{macro}.yaml").exists(), macro


def test_every_dungeon_macro_is_in_the_list():
    named = {macro_name(m) for _, _, m in listed_dungeons().values()}
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
