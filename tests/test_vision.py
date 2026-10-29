from another_macro.vision import Library, Point, Region, Vision

from helpers import ELEMENTS, render, write_library


def test_region_to_px_and_padding():
    r = Region(0.1, 0.2, 0.3, 0.4)
    assert r.to_px(1000, 500) == (100, 100, 400, 300)
    assert r.center == Point(0.25, 0.4)
    assert Region(0.0, 0.0, 0.1, 0.1).padded(0.05).as_list() == [0.0, 0.0, 0.15, 0.15]


def test_template_found_at_reference_and_other_resolutions(tmp_path):
    vision = Vision(Library.load(write_library(tmp_path)))
    for size in [(2400, 1080), (1600, 720), (2340, 1080)]:
        frame = render(["field_menu_button"], size=size)
        match = vision.find(frame, "field_menu_button")
        assert match is not None, size
        assert match.score > 0.9
        if size[0] / size[1] == 2400 / 1080:
            x, y, *_ = ELEMENTS["field_menu_button"]
            assert abs(match.region.x - x) < 0.01 and abs(match.region.y - y) < 0.01


def test_absent_template_not_found(tmp_path):
    vision = Vision(Library.load(write_library(tmp_path)))
    assert vision.find(render(["battle_hud"]), "field_menu_button") is None


def test_identify_uses_candidate_order(tmp_path):
    vision = Vision(Library.load(write_library(tmp_path)))
    frame = render(["battle_hud", "battle_attack_button"])
    assert vision.identify(frame, ["battle_command", "battle"]) == "battle_command"
    assert vision.identify(frame, ["battle", "battle_command"]) == "battle"
    assert vision.identify(render([])) is None


def test_uncaptured_templates_are_reported_and_never_match(tmp_path):
    path = tmp_path / "library.yaml"
    path.write_text("templates:\n  thing:\n    region: [0, 0, 1, 1]\nscreens:\n  s:\n    require: [thing]\n")
    lib = Library.load(path)
    assert lib.missing() == ["thing"]
    assert Vision(lib).identify(render([])) is None
