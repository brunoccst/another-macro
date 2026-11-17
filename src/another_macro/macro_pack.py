"""The macro pack the Android app downloads: macro-forge's pack tool, run on this repo.

The tool lives in the core (core/tools/macroforge_pack.py, docs in core/docs/writing-macros.md);
game.yaml says where this game's templates are (``pack:``) and which plugin steps describe
themselves in "How it works" (``plugin_steps:``). CI (.github/workflows/macros.yml) runs it on
every push that touches macros or templates and publishes ``macros-<branch>``.

This module gives the tool this repo as its default root, for the tests:

    python -m another_macro.macro_pack -o macros.zip [--commit SHA --branch NAME] [--check]
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_TOOL = ROOT / "core" / "tools" / "macroforge_pack.py"
if not _TOOL.is_file():
    raise ImportError(f"{_TOOL} is missing: run `git submodule update --init`")
_spec = importlib.util.spec_from_file_location("macroforge_pack", _TOOL)
tool = importlib.util.module_from_spec(_spec)
sys.modules.setdefault("macroforge_pack", tool)
_spec.loader.exec_module(tool)

_runs = tool._runs
_check_game = tool._check_game


def pack_files(root: Path = ROOT) -> dict[str, Path]:
    return tool.pack_files(root)


def macros(root: Path = ROOT) -> dict[str, tuple[Path, dict]]:
    return tool.macros(root)


def game(root: Path = ROOT) -> dict:
    return tool.game(root)


def check(root: Path = ROOT) -> list[str]:
    return tool.check(root)


def build(out: Path, commit: str = "", branch: str = "", root: Path = ROOT) -> int:
    return tool.build(out, root, commit, branch)


def main(argv: list[str] | None = None) -> None:
    tool.main(["--root", str(ROOT), *(sys.argv[1:] if argv is None else argv)])


if __name__ == "__main__":
    main()
