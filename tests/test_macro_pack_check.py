"""The macro pack the phones download passes the core pack tool's checks on every pull request
(not only when it is published from main): bad YAML, broken template entries, unknown macros."""
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "core" / "tools"


@pytest.mark.skipif(not (TOOL / "macroforge_pack.py").is_file(), reason="core submodule not checked out")
def test_macro_pack_is_clean():
    sys.path.insert(0, str(TOOL))
    try:
        import macroforge_pack as mp
    finally:
        sys.path.remove(str(TOOL))
    assert mp.check(ROOT) == []
