"""The macro pack the app downloads: every macro loads and finds what it needs."""
import zipfile

import yaml

from another_macro import macro_pack
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


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


def test_check_catches_dotted_option_and_unknown_uses(tmp_path):
    (tmp_path / "macros" / "daily").mkdir(parents=True)
    (tmp_path / "macros" / "daily" / "x.yaml").write_text(yaml.safe_dump({
        "name": "x", "group": "daily", "screens": [], "uses": ["nope"],
        "params": {"battle.skill_1": {"default": "auto"}},
    }))
    problems = macro_pack.check(tmp_path)
    assert any("unknown macro 'nope'" in p for p in problems)
    assert any("'battle.skill_1'" in p for p in problems)


def test_dungeons_take_battle_options_from_the_battle_macro():
    # the dungeon macros don't declare the battle skills: the app adds battle.skill_1..4 to their
    # options from the basic macro battle (InnerParams); only the boss has its own
    for name, (_, spec) in macro_pack.macros().items():
        if spec.get("group") == "dungeon":
            assert not any(str(k).startswith("skill_") for k in spec.get("params") or {}), name


def test_party_is_picked_by_number_only():
    # every user names and fills their parties differently: the party option is a number
    all_macros = macro_pack.macros()
    _, spec = all_macros["pick-party"]
    assert spec["group"] == "basic"
    assert [str(c) for c in spec["params"]["party"]["choices"]] == [str(i) for i in range(1, 11)]
    for name, (f, spec) in all_macros.items():
        party = (spec.get("params") or {}).get("party")
        if party is None or name == "pick-party":
            continue
        choices = [str(c) for c in party["choices"]]
        assert choices == ["current"] + [str(i) for i in range(1, 11)], name
        assert str(party["default"]) in choices, name
        assert "with " not in party["help"].lower(), name


def test_check_catches_empty_set_expression(tmp_path):
    (tmp_path / "macros" / "daily").mkdir(parents=True)
    (tmp_path / "macros" / "daily" / "x.yaml").write_text(yaml.safe_dump({
        "name": "x", "group": "daily", "screens": [], "setup": [{"set": {"picked": ""}}],
    }))
    assert any("empty expression" in p for p in macro_pack.check(tmp_path))


def test_watch_ad_leaves_commercials_that_wont_close():
    # a playable commercial can have no close X and ignore Back: watch-ad leaves it with the
    # leave_ad step (Home, then the game's own screen) instead of tapping it; the step is known
    # to the logic pack, and callers never tap while a commercial is still in front
    entry = (ROOT / "android/app/src/main/java/io/github/brunoccst/anothermacro/logic/Entry.kt").read_text()
    assert '"leave_ad"' in entry and '"home"' in entry and '"force_close"' in entry
    all_macros = macro_pack.macros()
    _, spec = all_macros["watch-ad"]
    acts = list(_flat(spec["unknown"]))
    leave = [a for a in acts if "tries >= leave_after" in str(a.get("if", ""))]
    assert leave and any(a.get("leave_ad") for a in _flat(leave[0]["then"]))
    # left once and still on: the game is closed from recent apps
    assert any(a.get("force_close") for a in _flat(leave[0]["then"]))
    assert not any("tap" in a or "tap_exact" in a for a in _flat(leave[0]["then"]))
    for name in ("daily-ads", "start-game"):
        _, caller = all_macros[name]
        guards = [a for a in _flat(caller["unknown"]) if a.get("if") == "ad_in_front()"]
        assert guards, name
        assert any(a.get("leave_ad") for a in _flat(guards[0]["then"])), name
        assert not any("tap" in a for a in _flat(guards[0]["then"])), name


def test_check_wants_how_steps(tmp_path):
    (tmp_path / "macros" / "daily").mkdir(parents=True)
    f = tmp_path / "macros" / "daily" / "x.yaml"
    f.write_text(yaml.safe_dump({"name": "x", "group": "daily", "screens": [], "handlers": {"field": [{"tap": [0.5, 0.5]}]}}))
    assert any("no how:" in p for p in macro_pack.check(tmp_path))
    f.write_text(yaml.safe_dump({"name": "x", "group": "daily", "params": {"n": 3}, "screens": [], "handlers": {},
                                 "how": ["Tap {n} times", "Then {m}", {"run": "nope"}, {"if": "n > 1", "then": ["{n}"]}]}))
    problems = macro_pack.check(tmp_path)
    assert any("{m} is not an option" in p for p in problems)
    assert any("how: runs unknown macro 'nope'" in p for p in problems)
    assert not any("{n}" in p for p in problems)
    # a step the app describes itself needs no how:
    f.write_text(yaml.safe_dump({"name": "x", "group": "daily", "screens": [], "handlers": {"field": [{"another_dungeon": "dogma_tower"}]}}))
    assert not any("how" in p for p in macro_pack.check(tmp_path))


def test_watch_ad_stops_when_no_commercial_played():
    # no commercial (the game shows its chest straight away): watch-ad stops so the caller opens
    # the chest, instead of pressing Back on it after ad_wait_s
    flow = (ROOT / "android/app/src/main/java/io/github/brunoccst/anothermacro/logic/FlowRun.kt").read_text()
    assert '"game_screen"' in flow
    _, spec = macro_pack.macros()["watch-ad"]
    acts = list(_flat(spec["unknown"]))
    no_ad = [a for a in acts if "not ad_seen and game_screen()" in str(a.get("if", ""))]
    assert no_ad and any("no commercial" in str(a.get("stop", "")) for a in no_ad[0]["then"])
