"""Turn the game on and get to the field.

Launches Another Eden if it isn't running (or brings it to the front), waits
through the splash and the title screen's "Game Id..." connection, taps
"Continue" and stops once the field (Menu button) shows. Booting takes about
15-60 s; the title screen can sit on "Game Id..." for a while on a slow network.

Start state: anything (game closed, in the background, or already running).
"""
from __future__ import annotations

from ... import config
from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

CONTINUE = Point(0.5, 0.75)


class StartGame(Scenario):
    name = "start-game"
    description = "Turn Another Eden on and wait until the field shows (taps Continue on the title)."
    params = {
        "package": Param(str, config.GAME_PACKAGE, "Android package of the game"),
    }
    screens = ["field", "title"]
    # splash + server login can take a while before anything recognisable shows
    unknown_timeout_s = 180

    def setup(self, ctx: Context) -> None:
        self._launched_at = None
        # (re)launching also brings an already-running game to the front
        ctx.device.start_app(ctx.params["package"])
        ctx.stats["launches"] += 1
        ctx.wait(3.0)

    def on_title(self, ctx: Context) -> None:
        # "Continue" only shows once the game has reached its server
        if ctx.find("title_continue"):
            ctx.stats["continue_taps"] += 1
            ctx.tap(CONTINUE)
            ctx.wait(4.0)
        else:
            ctx.wait(2.0)

    def on_field(self, ctx: Context) -> None:
        ctx.stop("the game is on and on the field")


SCENARIO = StartGame
