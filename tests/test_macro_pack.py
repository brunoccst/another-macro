"""The macro pack the app downloads: every macro loads and finds what it needs."""
import zipfile

import yaml

from another_macro import macro_pack


def test_macros_have_no_problems():
    assert macro_pack.check() == []


def test_pack_layout(tmp_path):
    out = tmp_path / "macros.zip"
    macro_pack.build(out, commit="abc1234", branch="main")
    names = set(zipfile.ZipFile(out).namelist())
    assert "macros/basic/watch-ad.yaml" in names
    assert "macros/basic/grind-battles.yaml" in names
    assert "templates/common/library.yaml" in names
    # the data the app's built-in steps read
    assert "templates/another_dungeon/dungeons.yaml" in names
    assert "templates/another_dungeon/walk.yaml" in names
    assert "templates/cat_scratchbook/riddles.yaml" in names
    # never Python code
    assert not any(n.endswith(".py") for n in names)


def test_ads_go_through_watch_ad():
    all_macros = macro_pack.macros()
    for name in ["start-game", "daily-ads", "shop-ad"]:
        _, spec = all_macros[name]
        runs = []
        for acts in spec["handlers"].values():
            runs += macro_pack._runs(acts)
        assert "watch-ad" in runs, name


def test_no_character_names():
    # macros stay generic: party slots and skill numbers, never who is in the party
    for f, _ in macro_pack.macros().values():
        text = f.read_text(encoding="utf-8").lower()
        assert "verdanta" not in text and "poporo" not in text, f


def test_check_catches_unknown_macro(tmp_path):
    (tmp_path / "macros" / "daily").mkdir(parents=True)
    (tmp_path / "macros" / "daily" / "x.yaml").write_text(yaml.safe_dump({
        "name": "x", "group": "daily", "screens": [], "handlers": {"field": [{"run": "nope"}]},
    }))
    assert any("unknown macro 'nope'" in p for p in macro_pack.check(tmp_path))
