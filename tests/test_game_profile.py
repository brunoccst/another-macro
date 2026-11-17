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


def _strings(name: str) -> str:
    """The code of an app file without its comments."""
    return "\n".join(l for l in _kt(name).splitlines() if not l.strip().startswith(("*", "/*", "//")))


def test_app_never_names_the_game():
    names = [GAME["name"], GAME["app_name"], GAME["package"], GAME["activity"], GAME["repo"]]
    for f in sorted(APP.glob("*.kt")):
        code = _strings(f.name)
        for w in names:
            assert w not in code, f"{f.name} names {w!r}: read it from GameProfile.current"
        for w in ["white key", "dungeon run", "cat express"]:
            assert w not in code.lower(), f"{f.name} names the game's {w!r}: game.yaml reports: says how reports show"
        for w in GAME["safety"]["premium_words"]:
            assert w not in code.lower(), f"{f.name} names {w!r}: read it from GameProfile.current"


def test_app_is_built_with_the_profile():
    gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'buildConfigField("String", "GAME_YAML"' in gradle and "gameYaml" in gradle
    manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert "${gamePackage}" in manifest and GAME["package"] not in manifest


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
