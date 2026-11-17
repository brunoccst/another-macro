"""The game profile (game.yaml): complete, packed, and equal to what the app has built in.

Until the app itself reads game.yaml, its own copies of these values (and the logic's defaults in
logic/Game.kt) must say the same, so a phone without the pack behaves the same as one with it.
"""
import re
from pathlib import Path

import yaml

from another_macro import macro_pack

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app/src/main/java/io/github/brunoccst/anothermacro"
GAME = yaml.safe_load((ROOT / "game.yaml").read_text(encoding="utf-8"))


def _kt(name: str) -> str:
    return (APP / name).read_text(encoding="utf-8")


def _list(text: str, name: str) -> list[str]:
    body = re.search(rf"{name}\s*=\s*listOf\((.*?)\)", text, re.S).group(1)
    return re.findall(r'"([^"]*)"', body)


def test_profile_is_complete():
    assert macro_pack._check_game(GAME) == []


def test_profile_is_in_the_pack():
    assert macro_pack.pack_files()["game.yaml"] == ROOT / "game.yaml"


def test_app_constants_match_the_profile():
    assert f'const val GAME = "{GAME["package"]}"' in _kt("ClaudeSafety.kt")
    assert f'const val GAME_ACTIVITY = "{GAME["activity"]}"' in _kt("Flow.kt")
    safety = _kt("ClaudeSafety.kt")
    assert _list(safety, "REFUSE_ANYWHERE") == GAME["safety"]["purchase_words"]
    assert _list(safety, "PREMIUM_ANYWHERE") == GAME["safety"]["premium_words"]
    for f in ("DevBuilds.kt", "MacroLibrary.kt", "Updater.kt"):
        assert f'REPO = "{GAME["repo"]}"' in _kt(f), f


def test_app_groups_match_the_profile():
    groups = re.findall(r'Group\("([^"]+)", "([^"]+)", R\.drawable\.ic_(\w+)\)', _kt("Ui.kt"))
    assert groups == [(g["id"], g["title"], g["icon"]) for g in GAME["groups"]]


def test_logic_defaults_match_the_profile():
    game = _kt("logic/Game.kt")
    default = game[game.index("val DEFAULT"):]
    assert f'name = "{GAME["name"]}"' in default
    assert f'packageName = "{GAME["package"]}"' in default
    assert f'activity = "{GAME["activity"]}"' in default
    assert _list(default, "purchaseWords") == GAME["safety"]["purchase_words"]
    assert _list(default, "premiumWords") == GAME["safety"]["premium_words"]


def test_logic_never_names_the_game():
    for f in sorted((APP / "logic").glob("*.kt")):
        if f.name == "Game.kt":
            continue
        code = "\n".join(l for l in f.read_text(encoding="utf-8").splitlines() if not l.strip().startswith(("*", "/*", "//")))
        assert GAME["package"] not in code and "ClaudeSafety.GAME" not in code and "GAME_ACTIVITY" not in code, f.name


def test_check_catches_unknown_group(tmp_path):
    (tmp_path / "macros" / "raid").mkdir(parents=True)
    (tmp_path / "macros" / "raid" / "x.yaml").write_text("name: x\ngroup: raid\nhow: [a]\n")
    (tmp_path / "game.yaml").write_text((ROOT / "game.yaml").read_text(encoding="utf-8"))
    assert any("not one of game.yaml's groups" in p for p in macro_pack.check(tmp_path))
