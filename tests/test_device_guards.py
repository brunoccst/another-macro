"""Screen-off and input-blocking-overlay guards."""
import numpy as np

from another_macro.device.adb import find_blocking_overlay
from another_macro.scenario.base import is_blank

LOCKED = """  Window #10 Window{b10d87c u0 GameBooster Lock Screen}:
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    isOnScreen=true
    isVisible=true
  Window #11 Window{c9d85cf u0 com.android.vending}:
    isVisible=true
"""

UNLOCKED = """  Window #10 Window{b10d87c u0 GameBooster Lock Screen}:
    mViewVisibility=0x8 mHaveFrame=true mObscured=false
    isOnScreen=false
    isVisible=false
  Window #11 Window{c9d85cf u0 com.android.vending}:
    isVisible=true
"""


def test_finds_visible_game_booster_lock():
    assert find_blocking_overlay(LOCKED) == "GameBooster Lock Screen"


def test_ignores_hidden_or_missing_lock():
    assert find_blocking_overlay(UNLOCKED) is None
    assert find_blocking_overlay("  Window #1 Window{1 u0 StatusBar}:\n    isVisible=true\n") is None


def test_black_frame_means_screen_off():
    assert is_blank(np.zeros((1080, 2400, 3), np.uint8))
    assert not is_blank(np.full((1080, 2400, 3), 40, np.uint8))


def test_no_input_while_another_app_is_in_front(tmp_path, monkeypatch):
    from another_macro import config
    from another_macro.device import FakeDevice
    from another_macro.scenario import base
    from another_macro.scenarios.grind_battles import GrindBattles

    monkeypatch.setenv("ANOTHER_MACRO_HOME", str(tmp_path / "home"))
    portrait = np.full((2400, 1080, 3), 60, np.uint8)
    calls = {"n": 0}

    def frame(device):
        calls["n"] += 1
        if calls["n"] >= 5:
            config.stop_file().touch()
        return portrait

    device = FakeDevice(frame)
    s = GrindBattles()
    s.unknown_timeout_s = 0
    result = base.run(s, device, run_dir=tmp_path / "run", sleep=lambda _: None)
    assert result.reason == "stop requested"
    assert not [a for a in device.actions if a[0] in ("tap", "swipe")]


def test_adb_device_implements_guards():
    from another_macro.device.adb import AdbDevice

    assert "wake" in AdbDevice.__dict__
    assert "blocking_overlay" in AdbDevice.__dict__
