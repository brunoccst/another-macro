"""Turn the game on, collect the daily ad reward, and get to the field.

Launches Another Eden if it isn't running (or brings it to the front), waits
through the splash and the title screen's "Game Id..." connection, taps
"Continue", and handles what the first login of the day shows:

1. the cat delivering the daily login bonus (an animation; tapped through),
2. "Get 20 Chronos Stones ... for watching a commercial" -> always **Watch Video**,
3. the ad itself, or, with the premium subscription, a "Thank you for
   subscribing" dialog instead -> Close,
4. "Thank you for watching. Your reward was delivered to your inbox." -> OK,

then stops once the field (Menu button) shows. Other days it goes straight from
Continue to the field.

Seen live with the premium subscription (no ad). Without it the ad runs in the
game or in its own window; the macro waits for it to finish (`ad_wait_s`) and
then presses Android back to close it, which is how rewarded ads are dismissed.

Start state: anything (game closed, in the background, or already running).
"""
from __future__ import annotations

import time

from ... import config
from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

CONTINUE = Point(0.5, 0.75)


class StartGame(Scenario):
    name = "start-game"
    description = "Turn Another Eden on, always watch the daily ad for the login bonus, stop on the field."
    params = {
        "package": Param(str, config.GAME_PACKAGE, "Android package of the game"),
        "ad_wait_s": Param(int, 45, "how long to let an ad play before pressing back to close it"),
    }
    screens = ["ad_reward", "premium_thanks", "ad_offer", "field", "title"]
    # splash, server login and the ad can take a while before anything recognisable shows
    unknown_timeout_s = 180
    landscape = False  # ads may play in portrait; handled in on_unknown

    def setup(self, ctx: Context) -> None:
        self._watching_since: float | None = None
        # (re)launching also brings an already-running game to the front
        ctx.device.start_app(ctx.params["package"])
        ctx.stats["launches"] += 1
        ctx.wait(3.0)

    def on_title(self, ctx: Context) -> None:
        # "Continue" only shows once the game has reached its server
        if ctx.find("title_continue"):
            ctx.stats["continue_taps"] += 1
            ctx.tap(CONTINUE)
            ctx.wait(8.0)  # the title stays up while the game logs in
        else:
            ctx.wait(2.0)

    def on_ad_offer(self, ctx: Context) -> None:
        # always take the ad: it's the daily Chronos Stone bonus
        ctx.stats["ads_accepted"] += 1
        ctx.tap("watch_video_button")
        self._watching_since = time.monotonic()
        ctx.wait(3.0)

    def on_premium_thanks(self, ctx: Context) -> None:
        # subscribers get this instead of an ad; same reward afterwards
        ctx.stats["premium_skips"] += 1
        ctx.tap("dialog_close_button")
        self._watching_since = None
        ctx.wait(2.0)

    def on_ad_reward(self, ctx: Context) -> None:
        ctx.stats["ad_rewards"] += 1
        ctx.tap("dialog_ok_button")
        self._watching_since = None
        ctx.wait(2.0)

    def on_field(self, ctx: Context) -> None:
        ctx.stop("the game is on and on the field")

    def on_unknown(self, ctx: Context) -> None:
        if self._watching_since is not None:
            # an ad is playing (in the game or its own window): let it finish,
            # then back closes it; never tap inside it (that opens the store)
            if time.monotonic() - self._watching_since > ctx.params["ad_wait_s"]:
                ctx.device.back()
                ctx.stats["ad_backs"] += 1
                self._watching_since = time.monotonic() - ctx.params["ad_wait_s"] + 10
            ctx.wait(2.0)
            return
        if ctx.frame is not None and ctx.frame.shape[0] > ctx.frame.shape[1]:
            # game not in front and no ad expected: bring it back
            ctx.device.start_app(ctx.params["package"])
            ctx.wait(3.0)
            return
        # loading screens and the cat delivering the login bonus: a tap in the
        # empty upper middle advances the animation without hitting any button
        ctx.tap(Point(0.5, 0.3))
        ctx.wait(1.5)


SCENARIO = StartGame
