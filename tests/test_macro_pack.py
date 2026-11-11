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


def _flat(acts):
    for a in acts or []:
        yield a
        for k in ("then", "else", "do"):
            yield from _flat(a.get(k) if k != "do" else (a.get("repeat") or {}).get("do"))


def test_watch_ad_closes_google_play_sheet():
    # a commercial can open a Google Play app sheet by itself: watch-ad must not take that for the
    # end of the commercials, close it with Back only (never a tap while Play is in front); only
    # functions every app build has (the pack reaches older apps too)
    _, spec = macro_pack.macros()["watch-ad"]
    play = [a for a in spec["unknown"] if "install" in str(a.get("if", ""))]
    assert play, "watch-ad has no Google Play step"
    in_play = list(_flat(play[0]["then"]))
    assert any(a.get("back") for a in in_play)
    assert not any("tap" in a or "tap_exact" in a for a in in_play)
    assert "play_in_front" not in str(spec)
    after = list(_flat(play[0]["else"]))
    assert not any("tap" in a or "tap_exact" in a for a in after)
    assert any("commercials are over" in str(a.get("stop", "")) for a in after)
