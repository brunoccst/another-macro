"""Grind battles on the current field.

Stand on any field/dungeon map with random encounters, press play, and the macro
walks left and right until a battle starts, lets the party auto-battle, clears
the results screen, and repeats.
"""
from __future__ import annotations

from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

# where to hold to walk; Another Eden moves the party toward the side you hold
WALK_RIGHT = Point(0.85, 0.5)
WALK_LEFT = Point(0.15, 0.5)


class GrindBattles(Scenario):
    name = "grind-battles"
    description = "Walk back and forth on the current map, auto-battle every encounter, repeat."
    params = {
        "battles": Param(int, 10, "stop after this many battles (0 = no limit)"),
        "walk_ms": Param(int, 1500, "how long each walk in one direction lasts"),
    }
    screens = ["defeat", "battle_result", "battle_command", "battle", "field"]

    def setup(self, ctx: Context) -> None:
        ctx.stats["battles"] = 0
        self._direction = 1
        self._in_battle = False

    def on_field(self, ctx: Context) -> None:
        if self._in_battle:
            # back on the field after a battle: count it
            self._in_battle = False
            ctx.stats["battles"] += 1
            limit = ctx.params["battles"]
            if limit and ctx.stats["battles"] >= limit:
                ctx.stop(f"{limit} battles done")
        ctx.hold(WALK_RIGHT if self._direction > 0 else WALK_LEFT, ctx.params["walk_ms"])
        self._direction = -self._direction

    def on_battle_command(self, ctx: Context) -> None:
        self._in_battle = True
        # Auto keeps attacking every turn; if it was already on we never get here
        if ctx.find("battle_auto_button"):
            ctx.tap("battle_auto_button")
        else:
            ctx.tap("battle_attack_button")
        ctx.wait(1.0)

    def on_battle(self, ctx: Context) -> None:
        self._in_battle = True  # animations playing; nothing to do

    def on_battle_result(self, ctx: Context) -> None:
        self._in_battle = True
        # results (and level-up pages) advance with a tap anywhere
        ctx.tap(Point(0.5, 0.75))
        ctx.wait(0.8)

    def on_defeat(self, ctx: Context) -> None:
        ctx.save_frame("defeat")
        ctx.stop("party was defeated")


SCENARIO = GrindBattles
