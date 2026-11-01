"""Turn the game off.

The game's own way out: Android back on the field opens "Close the app?",
and Yes exits the game cleanly. Elsewhere (battles, menus) back steps out of
the current screen first, so this keeps pressing back until the dialog shows.
If the game doesn't close that way within `timeout_s`, it is force-stopped.

Start state: anything. Avoid stopping in the middle of a battle you care about.
"""
from __future__ import annotations

import time

from ... import config
from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

YES = Point(0.588, 0.568)


class StopGame(Scenario):
    name = "stop-game"
    description = "Turn Another Eden off (back -> 'Close the app?' -> Yes; force-stop as a fallback)."
    params = {
        "package": Param(str, config.GAME_PACKAGE, "Android package of the game"),
        "timeout_s": Param(int, 60, "force-stop the game if it hasn't closed by itself after this long"),
    }
    screens = ["close_app", "field"]
    unknown_timeout_s = 90
    landscape = False  # the home screen shows (portrait) once the game is gone

    def setup(self, ctx: Context) -> None:
        self._t0 = time.monotonic()
        self._check_gone(ctx)

    def _check_gone(self, ctx: Context) -> None:
        pkg = ctx.params["package"]
        if not ctx.device.app_running(pkg):
            ctx.stop("the game is off")
        if time.monotonic() - self._t0 > ctx.params["timeout_s"]:
            ctx.device.stop_app(pkg)
            ctx.stats["force_stops"] += 1
            ctx.wait(1.0)
            if not ctx.device.app_running(pkg):
                ctx.stop("the game is off (force-stopped)")
            ctx.stop("could not turn the game off")

    def on_close_app(self, ctx: Context) -> None:
        ctx.stats["confirms"] += 1
        ctx.tap(YES)
        ctx.wait(3.0)
        self._check_gone(ctx)

    def on_field(self, ctx: Context) -> None:
        ctx.device.back()
        ctx.wait(1.5)

    def on_unknown(self, ctx: Context) -> None:
        # menus, results, the home screen...: step back towards the field
        self._check_gone(ctx)
        if ctx.frame is not None and ctx.frame.shape[0] > ctx.frame.shape[1]:
            return  # game not in front; nothing to press
        ctx.device.back()
        ctx.wait(1.5)


SCENARIO = StopGame
