"""The list of every Another Dungeon (the table in docs/macros.md) and the dungeon macros."""
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
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


def test_every_listed_macro_exists():
    listed = listed_dungeons()
    assert len(listed) >= 80
    for name, kind, macro in listed.values():
        assert kind in KINDS, name
        macro = macro_name(macro)
        assert macro == "todo" or (MACROS / f"{macro}.yaml").exists(), macro


def test_every_dungeon_macro_is_in_the_list():
    named = {macro_name(m) for _, _, m in listed_dungeons().values()}
    for f in MACROS.glob("*.yaml"):
        if "dungeon" in yaml.safe_load(f.read_text(encoding="utf-8")):
            assert f.stem in named, f.stem


def test_dungeon_keys_offers_every_dungeon_of_each_colour():
    """dungeon-keys lists, per key colour, every dungeon macro with that colour's key switch."""
    params = yaml.safe_load((MACROS / "dungeon-keys.yaml").read_text(encoding="utf-8"))["params"]
    for colour in ("red", "green"):
        takes = {f.stem for f in MACROS.glob("*.yaml")
                 if f"{colour}_key" in (yaml.safe_load(f.read_text(encoding="utf-8")).get("params") or {})}
        choices = params[f"{colour}_dungeon"]["choices"]
        assert choices[0] == "off" and set(choices[1:]) == takes, (colour, sorted(takes))


def test_required_characters_are_names():
    for f in MACROS.glob("*.yaml"):
        d = yaml.safe_load(f.read_text(encoding="utf-8")).get("dungeon")
        if d:
            req = d.get("requires", [])
            assert isinstance(req, list) and all(isinstance(n, str) and n.strip() for n in req), f.stem
