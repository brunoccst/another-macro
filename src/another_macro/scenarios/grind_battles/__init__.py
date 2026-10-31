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

import cv2
import numpy as np

from ...scenario.base import Context, Param, Scenario
from ...vision.geometry import Point

# where to hold to walk; Another Eden moves the party toward the side you hold
WALK_RIGHT = Point(0.85, 0.5)
WALK_LEFT = Point(0.15, 0.5)

SELECTED_LABEL = "verdanta_last_skill_selected"
# mean grey-level change (0-255) of the downscaled field view below which a walk "didn't move"
STUCK_DIFF = 4.0


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
        "first_walk": Param(int, -1, "direction of the first walk: -1 = left, 1 = right"),
        "stuck_walks": Param(int, 8, "stop if this many walks in a row leave the field view unchanged (0 = never)"),
        "mode": Param(str, "skill", "skill = set one character's skill then Attack; auto = Auto button"),
        "slot": Param(int, 1, "skill mode: front-row position (1-4) of the character whose skill is set"),
        "skill_x": Param(float, LAST_SKILL_X, "skill mode: x (0..1) of the skill to pick in that character's skill row"),
    }
    screens = ["title", "defeat", "battle_result", "skill_list", "battle_command", "battle", "field"]

    def setup(self, ctx: Context) -> None:
        ctx.stats["battles"] = 0
        self._direction = ctx.params["first_walk"]
        self._last_view = None
        self._walked = False
        self._still_walks = 0
        self._in_battle = False
        self._skill_set_this_battle = False

    def _field_moved(self, ctx: Context) -> bool:
        """Did the last walk change the view? (party/camera moved, not just idle animation)"""
        assert ctx.frame is not None
        h, w = ctx.frame.shape[:2]
        # the walkable band, minus the bottom button row and the minimap corner
        small = cv2.resize(ctx.frame[int(h * 0.25) : int(h * 0.8), : int(w * 0.8)], (160, 60), interpolation=cv2.INTER_AREA)
        small = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY).astype(np.int16)
        prev, self._last_view = self._last_view, small
        if prev is None:
            return True
        return float(np.abs(small - prev).mean()) > STUCK_DIFF

    def on_field(self, ctx: Context) -> None:
        if self._in_battle:
            # back on the field after a battle: count it
            self._in_battle = False
            self._skill_set_this_battle = False
            self._last_view, self._walked, self._still_walks = None, False, 0
            ctx.stats["battles"] += 1
            limit = ctx.params["battles"]
            if limit and ctx.stats["battles"] >= limit:
                ctx.stop(f"{limit} battles done")
        if self._walked and not self._field_moved(ctx):
            self._still_walks += 1
            limit = ctx.params["stuck_walks"]
            if limit and self._still_walks >= limit:
                ctx.save_frame("stuck")
                ctx.stop(f"party seems stuck: {self._still_walks} walks without the view changing; move it somewhere open and run again")
        else:
            self._still_walks = 0
        self._walked = True
        # equal, unjittered walks each way so the party paces around where it started
        # instead of drifting into the next area (or a dead-end ledge)
        ctx.hold(WALK_RIGHT if self._direction > 0 else WALK_LEFT, ctx.params["walk_ms"], exact=True)
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

    def on_title(self, ctx: Context) -> None:
        # the game restarted (crash, Game Booster, update); a tap resumes where it was
        ctx.stats["title_resumes"] += 1
        ctx.tap(Point(0.5, 0.75))
        ctx.wait(5.0)

    def on_defeat(self, ctx: Context) -> None:
        ctx.save_frame("defeat")
        ctx.stop("party was defeated")


SCENARIO = GrindBattles
