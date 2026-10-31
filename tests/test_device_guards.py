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
