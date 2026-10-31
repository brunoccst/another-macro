"""Grind battles on the current field.

Stand on any field/dungeon map with random encounters, press play, and the macro
walks left and right until a battle starts, fights, clears the results screen,
and repeats.

Two ways to fight (`--param mode=...`):

* `skill` (default): give one front-row character a fixed skill, then press
  Attack; everyone else uses whatever is already selected. By default that is
  the first character's last skill (Bruno's Verdanta -> Sprinting Raid). The
  game remembers a chosen skill between turns, so the macro only re-selects it
  when the `verdanta_last_skill_selected` label is not showing.
* `auto`: press the battle's Auto (>>) button, falling back to Attack.

The skill row and portraits sit at fixed spots on a 20:9 landscape screen,
measured on a 2400x1080 phone; adjust `slot` / `skill_x` for other parties.
"""
from __future__ import annotations

from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

# where to hold to walk; Another Eden moves the party toward the side you hold
WALK_RIGHT = Point(0.85, 0.5)
WALK_LEFT = Point(0.15, 0.5)

SELECTED_LABEL = "verdanta_last_skill_selected"


def _captured(ctx: Context, name: str) -> bool:
    tpl = ctx.vision.library.templates.get(name)
    return bool(tpl and tpl.available)


def _seen(ctx: Context, name: str) -> bool:
    return _captured(ctx, name) and ctx.find(name) is not None


# battle command layout (normalized, landscape)
PORTRAIT_X = [0.161, 0.267, 0.373, 0.479]  # the four front-row portraits
PORTRAIT_Y = 0.917
SKILL_ROW_Y = 0.763  # the skill list that opens above the portraits
LAST_SKILL_X = 0.8075  # fifth (last) box of the skill row


class GrindBattles(Scenario):
    name = "grind-battles"
    description = "Walk back and forth on the current map, auto-battle every encounter, repeat."
    params = {
        "battles": Param(int, 10, "stop after this many battles (0 = no limit)"),
        "walk_ms": Param(int, 1500, "how long each walk in one direction lasts"),
        "mode": Param(str, "skill", "skill = set one character's skill then Attack; auto = Auto button"),
        "slot": Param(int, 1, "skill mode: front-row position (1-4) of the character whose skill is set"),
        "skill_x": Param(float, LAST_SKILL_X, "skill mode: x (0..1) of the skill to pick in that character's skill row"),
    }
    screens = ["defeat", "battle_result", "skill_list", "battle_command", "battle", "field"]

    def setup(self, ctx: Context) -> None:
        ctx.stats["battles"] = 0
        self._direction = 1
        self._in_battle = False
        self._skill_set_this_battle = False

    def on_field(self, ctx: Context) -> None:
        if self._in_battle:
            # back on the field after a battle: count it
            self._in_battle = False
            self._skill_set_this_battle = False
            ctx.stats["battles"] += 1
            limit = ctx.params["battles"]
            if limit and ctx.stats["battles"] >= limit:
                ctx.stop(f"{limit} battles done")
        ctx.hold(WALK_RIGHT if self._direction > 0 else WALK_LEFT, ctx.params["walk_ms"])
        self._direction = -self._direction

    def on_battle_command(self, ctx: Context) -> None:
        self._in_battle = True
        if ctx.params["mode"] == "auto":
            # Auto keeps attacking every turn; if it was already on we never get here
            if ctx.find("battle_auto_button"):
                ctx.tap("battle_auto_button")
            else:
                ctx.tap("battle_attack_button")
            ctx.wait(1.0)
            return
        # the game keeps last turn's choice, so only open the skill list when needed
        if self._skill_set_this_battle or _seen(ctx, SELECTED_LABEL):
            ctx.stats["turns"] += 1
            ctx.tap("battle_attack_button")
            ctx.wait(1.0)
        else:
            slot = min(max(ctx.params["slot"], 1), len(PORTRAIT_X)) - 1
            ctx.tap(Point(PORTRAIT_X[slot], PORTRAIT_Y))
            ctx.wait(0.8)

    def on_skill_list(self, ctx: Context) -> None:
        self._in_battle = True
        ctx.tap(Point(ctx.params["skill_x"], SKILL_ROW_Y))
        # without the label template we can't see the choice, so trust the tap for this battle
        self._skill_set_this_battle = not _captured(ctx, SELECTED_LABEL)
        ctx.wait(0.8)

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
