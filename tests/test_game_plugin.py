"""The generic macro steps never reach into the game: game code is behind GamePlugin."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOGIC = ROOT / "core/android/core/src/main/java/macroforge/logic"  # generic steps
GAME_LOGIC = ROOT / "android/app/src/main/java/anothermacro/logic"  # the game's plugin

# the logic's generic files: they must work for any game
GENERIC = sorted(f.name for f in LOGIC.glob("*.kt"))
# the game's own code (Another Eden): only the plugin and its own files use it
GAME_CODE = ["AnotherDungeon", "Battle", "CatDaily", "CatRoute", "Party", "PhantomCrystal", "FeatherFinder",
             "AreaNav", "MiniMap", "Route", "HowItWorks", "AnotherEdenPlugin"]


def _code(f: Path) -> str:
    return "\n".join(l for l in f.read_text(encoding="utf-8").splitlines() if not l.strip().startswith(("*", "/*", "//")))


def test_generic_logic_never_names_game_code():
    for name in GENERIC:
        code = _code(LOGIC / name)
        for g in GAME_CODE:
            assert not re.search(rf"\b{g}\b", code), f"{name} uses {g}"


def test_entry_takes_the_game_from_game_yaml():
    code = _code(LOGIC / "Entry.kt")
    assert re.search(r"val PLUGIN: GamePlugin = GameProfile\.current\.plugin", code)


def test_game_code_lives_in_the_app():
    for g in GAME_CODE:
        assert (GAME_LOGIC / f"{g}.kt").is_file(), g
