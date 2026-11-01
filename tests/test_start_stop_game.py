"""start-game and stop-game against a simulated app lifecycle."""
import pytest

from another_macro.device import FakeDevice
from another_macro.scenario import base
from another_macro.scenarios.start_game import StartGame
from another_macro.scenarios.stop_game import StopGame

from helpers import render, write_library

SCREENS = {
    "field": {"require": ["field_menu_button"]},
    "title": {"require": ["title_screen"]},
    "close_app": {"require": ["close_app_dialog"]},
}


@pytest.fixture
def lib(tmp_path, monkeypatch):
    monkeypatch.setenv("ANOTHER_MACRO_HOME", str(tmp_path / "home"))
    monkeypatch.setattr(base.Context, "wait", lambda self, seconds: None)
    return write_library(tmp_path / "lib", SCREENS)


def _scenario(cls, lib):
    s = cls()
    s.library_files = lambda: [lib]
    s.tick_s = 0
    return s


def test_start_game_taps_continue_once_it_shows(lib, tmp_path):
    frames = {
        "connecting": render(["title_screen"]),
        "continue": render(["title_screen", "title_continue"]),
        "field": render(["field_menu_button"]),
    }
    state = {"n": 0}

    def frame(device):
        state["n"] += 1
        taps = [a for a in device.actions if a[0] == "tap"]
        if taps:
            return frames["field"]
        return frames["connecting"] if state["n"] < 3 else frames["continue"]

    device = FakeDevice(frame)
    device.running = False
    result = base.run(_scenario(StartGame, lib), device, run_dir=tmp_path / "run")
    assert result.reason == "the game is on and on the field"
    assert device.actions[0] == ("start_app", "games.wfs.anothereden")
    # no tap while the title was still connecting ("Game Id...")
    assert len([a for a in device.actions if a[0] == "tap"]) == 1


def test_stop_game_presses_back_and_confirms(lib, tmp_path):
    frames = {"field": render(["field_menu_button"]), "dialog": render(["close_app_dialog"])}

    def frame(device):
        if any(a[0] == "tap" for a in device.actions):
            device.running = False  # Yes closes the app: the launcher shows
            return render([])
        return frames["dialog"] if ("back",) in device.actions else frames["field"]

    device = FakeDevice(frame)
    result = base.run(_scenario(StopGame, lib), device, run_dir=tmp_path / "run")
    assert result.reason == "the game is off"
    assert device.actions[0] == ("back",)
    assert result.stats == {"confirms": 1}


def test_stop_game_when_already_off(lib, tmp_path):
    device = FakeDevice([render([])])
    device.running = False
    result = base.run(_scenario(StopGame, lib), device, run_dir=tmp_path / "run")
    assert result.reason == "the game is off"
    assert device.actions == []
