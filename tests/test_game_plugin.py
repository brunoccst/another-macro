"""The generic macro steps never reach into the game: game code is behind GamePlugin."""
import re
from pathlib import Path

LOGIC = Path(__file__).resolve().parents[1] / "android/app/src/main/java/io/github/brunoccst/anothermacro/logic"

# the logic's generic files: they must work for any game
GENERIC = ["FlowRun.kt", "GamePlugin.kt", "Game.kt", "AdEscape.kt"]
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


def test_entry_takes_the_game_from_one_place():
    code = _code(LOGIC / "Entry.kt")
    assert re.search(r"val PLUGIN: GamePlugin = \w+", code)
    assert [g for g in GAME_CODE if re.search(rf"\b{g}\b", code)] == ["AnotherEdenPlugin"]


def test_generic_steps_and_plugin_steps_are_listed():
    flow = (LOGIC / "FlowRun.kt").read_text(encoding="utf-8")
    plugin = (LOGIC / "AnotherEdenPlugin.kt").read_text(encoding="utf-8")
    steps = set(re.findall(r'"(\w+)"', re.search(r"val STEPS = setOf\((.*?)\)", flow, re.S).group(1)))
    actions = set(re.findall(r'"(\w+)"', re.search(r"override val actions = setOf\((.*?)\)", plugin, re.S).group(1)))
    assert not steps & actions
    # every generic step FlowRun lists is one it runs
    for s in steps:
        assert f'"{s}" in a' in flow, s
    # every plugin step is one the plugin runs
    for s in actions:
        assert f'"{s}" ->' in plugin, s
