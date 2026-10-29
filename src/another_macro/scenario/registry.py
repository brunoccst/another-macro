"""Find scenarios: every package under another_macro.scenarios exporting SCENARIO."""
from __future__ import annotations

import importlib
import pkgutil

from .. import scenarios as scenarios_pkg
from .base import Scenario


def all_scenarios() -> dict[str, type[Scenario]]:
    found: dict[str, type[Scenario]] = {}
    for mod in pkgutil.iter_modules(scenarios_pkg.__path__):
        module = importlib.import_module(f"{scenarios_pkg.__name__}.{mod.name}")
        cls = getattr(module, "SCENARIO", None)
        if cls is not None:
            found[cls.name] = cls
    return dict(sorted(found.items()))


def get(name: str) -> Scenario:
    scenarios = all_scenarios()
    if name not in scenarios:
        raise KeyError(f"unknown scenario '{name}'. Available: {', '.join(scenarios) or 'none'}")
    return scenarios[name]()
