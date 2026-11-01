"""Where local run data goes. Screenshots of your account stay on the device."""
from __future__ import annotations

import os
from pathlib import Path


def home() -> Path:
    path = Path(os.environ.get("ANOTHER_MACRO_HOME", Path.home() / ".another-macro"))
    path.mkdir(parents=True, exist_ok=True)
    return path


def runs_dir() -> Path:
    path = home() / "runs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def stop_file() -> Path:
    """Touching this file asks a running scenario to stop (the "Stop" widget)."""
    return home() / "STOP"


def serial_file() -> Path:
    """Remembers the ADB serial `connect` set up (e.g. localhost:37215)."""
    return home() / "serial"


def saved_serial() -> str | None:
    f = serial_file()
    return f.read_text().strip() or None if f.is_file() else None


# Another Eden's Android package (global release)
GAME_PACKAGE = "games.wfs.anothereden"


def screen_timeout_file() -> Path:
    """The phone's own screen timeout, saved while a run keeps the screen awake."""
    return home() / "screen_timeout"
