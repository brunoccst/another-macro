"""Every YAML file of the repo must parse.

The app loads each file of the macro pack on its own and skips a broken one (it fails alone,
the rest keeps working), but the broken file still loses its macro or templates on the phone:
one must never merge. The macro pack tool (core/tools/macroforge_pack.py) also refuses to build
a pack with a broken file.
"""
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
# not this repo's files: the core submodule checks its own
SKIP = {".git", "core", "node_modules", ".venv", "venv", "build", ".gradle"}
FILES = sorted(
    p for p in ROOT.rglob("*")
    if p.suffix in (".yaml", ".yml") and p.is_file() and not SKIP & set(p.relative_to(ROOT).parts[:-1])
)


def test_finds_the_app_files():
    names = {p.relative_to(ROOT).as_posix() for p in FILES}
    assert "game.yaml" in names
    assert "src/another_macro/assets/templates/library.yaml" in names
    assert any(n.startswith("macros/") for n in names)


@pytest.mark.parametrize("path", FILES, ids=lambda p: str(p.relative_to(ROOT)))
def test_yaml_parses(path):
    yaml.safe_load(path.read_text(encoding="utf-8"))
