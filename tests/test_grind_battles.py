"""Run the grind-battles scenario against a simulated game."""
import pytest

from another_macro.device import FakeDevice
from another_macro.scenario import base
from another_macro.scenarios.grind_battles import GrindBattles

from helpers import render, write_library


class FakeGame:
    """Tiny state machine reacting to the macro's inputs like the real game would."""

    SCREENS = {
        "field": ["field_menu_button"],
        "battle_command": ["battle_hud", "battle_attack_button", "battle_auto_button"],
        "battle_command_set": ["battle_hud", "battle_attack_button", "battle_auto_button", "verdanta_last_skill_selected"],
        "skill_list": ["battle_hud", "skill_list_open"],
        "battle_anim": ["battle_hud"],
        "battle_result": ["result_banner"],
        "defeat": ["defeat_banner"],
        "loading": [],
    }

    def __init__(self, steps_to_encounter=2, lose_on_battle=None, size=(1600, 720)):
        self.state = "field"
        self.walks = 0
        self.battles = 0
        self.steps_to_encounter = steps_to_encounter
        self.lose_on_battle = lose_on_battle
        self.size = size
        self.anim_frames = 0
        self.seen = 0
        self.portrait_taps = 0
        self.attacks = []  # per Attack press: was the skill set?
        self.turns_per_battle = 1
        self._frames = {k: render(v, size=size) for k, v in self.SCREENS.items()}

    def screenshot(self, device):
        # consume inputs made since the last frame
        for action in device.actions[self.seen :]:
            self.on_input(action)
        self.seen = len(device.actions)
        if self.state == "battle_anim":
            self.anim_frames += 1
            if self.anim_frames >= 3 and len(self.attacks) % self.turns_per_battle:
                self.state = "battle_command_set" if self.attacks[-1] else "battle_command"
            elif self.anim_frames >= 3:
                self.battles += 1
                self.state = "defeat" if self.battles == self.lose_on_battle else "battle_result"
        return self._frames[self.state]

    def on_input(self, action):
        kind = action[0]
        if self.state == "field" and kind == "swipe":
            self.walks += 1
            if self.walks % self.steps_to_encounter == 0:
                self.state = "battle_command"
        elif self.state in ("battle_command", "battle_command_set") and kind == "tap":
            x, y = action[1] / self.size[0], action[2] / self.size[1]
            if y > 0.88 and x < 0.55:  # a front-row portrait opens its skill list
                self.portrait_taps += 1
                self.state = "skill_list"
            else:
                self.attacks.append(self.state == "battle_command_set")
                self.state, self.anim_frames = "battle_anim", 0
        elif self.state == "skill_list" and kind == "tap":
            # the chosen skill is remembered for the rest of the battle
            self.state = "battle_command_set"
        elif self.state == "battle_result" and kind == "tap":
            self.state = "field"


@pytest.fixture
def scenario(tmp_path, monkeypatch):
    lib = write_library(tmp_path / "lib")
    monkeypatch.setenv("ANOTHER_MACRO_HOME", str(tmp_path / "home"))
    screens = {
        "field": {"require": ["field_menu_button"]},
        "battle_command": {"require": ["battle_attack_button"]},
        "skill_list": {"require": ["skill_list_open"]},
        "battle": {"require": ["battle_hud"]},
        "battle_result": {"require": ["result_banner"]},
        "defeat": {"require": ["defeat_banner"]},
    }
    lib = write_library(tmp_path / "lib", screens)
    s = GrindBattles()
    s.library_files = lambda: [lib]
    s.tick_s = 0
    monkeypatch.setattr(base.Context, "wait", lambda self, seconds: None)
    return s


def test_grinds_until_battle_limit(scenario, tmp_path):
    game = FakeGame()
    device = FakeDevice(game.screenshot)
    result = base.run(scenario, device, {"battles": 3, "mode": "auto"}, run_dir=tmp_path / "run")
    assert result.reason == "3 battles done"
    assert result.stats["battles"] == 3
    assert game.battles == 3
    # walking alternates sides of the screen
    holds = [a for a in device.actions if a[0] == "swipe"]
    assert holds[0][1] > 800 > holds[1][1]
    # it pressed Auto, not Attack, when Auto was available
    w, h = game.size
    taps_in_battle = [a for a in device.actions if a[0] == "tap" and a[2] / h < 0.85 and a[2] / h > 0.7 and a[1] / w < 0.2]
    assert taps_in_battle


def test_skill_mode_sets_skill_once_per_battle_then_attacks(scenario, tmp_path):
    game = FakeGame()
    game.turns_per_battle = 2
    device = FakeDevice(game.screenshot)
    result = base.run(scenario, device, {"battles": 2}, run_dir=tmp_path / "run")
    assert result.reason == "2 battles done"
    # one portrait tap per battle; every Attack press had the skill set
    assert game.portrait_taps == 2
    assert game.attacks == [True] * 4
    # the skill picked is the last box of the skill row
    w, h = game.size
    taps = [a for a in device.actions if a[0] == "tap"]
    after_portrait = [taps[i + 1] for i, a in enumerate(taps[:-1]) if a[2] / h > 0.88 and a[1] / w < 0.55]
    assert len(after_portrait) == 2
    assert all(abs(a[1] / w - 0.8075) < 0.01 and abs(a[2] / h - 0.763) < 0.01 for a in after_portrait)


def test_stops_and_saves_screenshot_on_defeat(scenario, tmp_path):
    game = FakeGame(lose_on_battle=2)
    result = base.run(scenario, FakeDevice(game.screenshot), {"battles": 0}, run_dir=tmp_path / "run")
    assert result.reason == "party was defeated"
    assert result.stats["battles"] == 1
    assert list((tmp_path / "run").glob("*-defeat.png"))


def test_stops_when_stuck_on_unknown_screen(scenario, tmp_path):
    scenario.unknown_timeout_s = 0
    frames = [render([], size=(1600, 720))]
    result = base.run(scenario, FakeDevice(frames), run_dir=tmp_path / "run")
    assert result.reason.startswith("stuck on an unrecognised screen")
    assert list((tmp_path / "run").glob("*-unknown.png"))


def test_stop_file_ends_run(scenario, tmp_path):
    from another_macro import config

    def frame(device):
        if len(device.actions) >= 2:
            config.stop_file().touch()
        return render(["field_menu_button"], size=(1600, 720))

    result = base.run(scenario, FakeDevice(frame), run_dir=tmp_path / "run")
    assert result.reason == "stop requested"
