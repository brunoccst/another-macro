"""Every YAML file the app loads must parse: one bad file in the macro pack breaks every connector call."""
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
FILES = sorted(
    p for d in ("macros", "src") for p in (ROOT / d).rglob("*.yaml")
) + [ROOT / "game.yaml"]


@pytest.mark.parametrize("path", FILES, ids=lambda p: str(p.relative_to(ROOT)))
def test_yaml_parses(path):
    yaml.safe_load(path.read_text(encoding="utf-8"))
