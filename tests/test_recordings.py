"""The recording converter: synthetic recordings (no phone, no images)."""
import json

import yaml

from another_macro import cli, recordings


def _tap(i, x, y, label=None, gap=800):
    g = {"i": i, "gap_ms": gap, "dur_ms": 70, "points": [[0, x, y], [70, x, y]]}
    if label:
        g["label"] = label
    return g


def _hold(i, x0, y0, x1, y1, ms, gap=600):
    # press, slide in the first 150 ms, then stay
    return {"i": i, "gap_ms": gap, "dur_ms": ms, "points": [[0, x0, y0], [150, x1, y1], [ms, x1, y1]]}


def _recording(tmp_path, gestures, name="cat-acteul"):
    folder = tmp_path / name
    folder.mkdir()
    (folder / "recording.json").write_text(json.dumps(
        {"version": 1, "name": name, "screen": [2400, 1080], "end_ms": 60000, "gestures": gestures}
    ))
    return folder


def _cat_trip():
    gs = [
        _tap(0, 0.95, 0.9, "Map"),
        _tap(1, 0.20, 0.06, "Antiquity"),
        _tap(2, 0.90, 0.10, "Expand"),
        _tap(3, 0.50, 0.40, "Migleina Continent"),
        _tap(4, 0.52, 0.48, "Acteul"),
        _tap(5, 0.60, 0.70, "Travel"),
        _hold(6, 0.5, 0.65, 0.4, 0.65, 800, gap=4000),     # walk left
        {"i": 7, "gap_ms": 500, "dur_ms": 180, "points": [[0, 0.5, 0.65], [180, 0.5, 0.5]]},  # flick up
        _hold(8, 0.5, 0.65, 0.6, 0.65, 163),                # too short for a walk: a swipe
        _hold(9, 0.5, 0.65, 0.6, 0.65, 1604),               # walk right
        _tap(10, 0.41, 0.40),                               # the cat bubble
    ]
    for k, g in enumerate(gs):
        g["i"] = k
    return gs


def test_classify_moves(tmp_path):
    rec = recordings.load(_recording(tmp_path, _cat_trip()))
    kinds = [m.kind for m in recordings.moves(rec)]
    assert kinds == ["tap"] * 6 + ["walk", "flick", "swipe", "walk", "tap"]
    ms = recordings.moves(rec)
    assert ms[6].token == "L 800"
    assert ms[7].token == "U"
    assert ms[9].token == "R 1600"
    assert ms[4].label == "Acteul"


def test_cat_spot_from_a_recording(tmp_path):
    rec = recordings.load(_recording(tmp_path, _cat_trip()))
    spot = recordings.cat_spot(rec)
    assert spot["era"] == "Antiquity"
    assert spot["continent"] == "Migleina Continent"
    assert spot["place"] == "Acteul"
    assert spot["sub"] is None
    assert spot["path"] == ["L 800", "U", "R 1600"]
    assert spot["cat_tap"] == [0.41, 0.4]
    assert any("swipe" in d for d in spot["unrouted"])
    # the YAML snippet parses
    data = yaml.safe_load(recordings.cat_spot_yaml(rec))
    assert data["Acteul"]["recorded_path"] == ["L 800", "U", "R 1600"]


def test_sub_location_is_the_label_after_the_place(tmp_path):
    gs = [_tap(0, 0.2, 0.06, "Antiquity"), _tap(1, 0.5, 0.5, "Nadara Volcano"), _tap(2, 0.5, 0.6, "Caldera Edge"),
          _hold(3, 0.5, 0.65, 0.6, 0.65, 1000), _tap(4, 0.6, 0.4)]
    spot = recordings.cat_spot(recordings.load(_recording(tmp_path, gs)))
    assert (spot["era"], spot["continent"], spot["place"], spot["sub"]) == ("Antiquity", None, "Nadara Volcano", "Caldera Edge")
    assert spot["path"] == ["R 1000"]


def test_dungeon_legs_split_at_taps(tmp_path):
    gs = [_hold(0, 0.5, 0.65, 0.4, 0.65, 5250),
          {"i": 1, "gap_ms": 500, "dur_ms": 200, "points": [[0, 0.5, 0.65], [200, 0.5, 0.5]]},
          _tap(2, 0.48, 0.36, "!"),
          _hold(3, 0.5, 0.65, 0.6, 0.65, 6690)]
    legs = recordings.dungeon_legs(recordings.load(_recording(tmp_path, gs, "dogma-map-1")))
    assert legs == [["chest 1", "L 5250", "U"], ["exit", "R 6690"]]
    assert yaml.safe_load(recordings.dungeon_yaml(recordings.load(tmp_path / "dogma-map-1")))["path"][1] == ["exit", "R 6690"]


def test_to_macro_is_a_valid_flow_macro(tmp_path):
    rec = recordings.load(_recording(tmp_path, _cat_trip()))
    text = recordings.to_macro(rec, "cat-acteul-fixed")
    data = yaml.safe_load(text)
    assert data["name"] == "cat-acteul-fixed"
    assert data["screens"] == []
    steps = data["setup"]
    assert steps[0] == {"status": "replaying cat-acteul-fixed"}
    assert steps[-1] == {"stop": "replayed cat-acteul-fixed"}
    assert {"tap_exact": {"x": 0.52, "y": 0.48}} in steps
    assert {"gesture": [[0, 0.5, 0.65], [150, 0.4, 0.65], [800, 0.4, 0.65]]} in steps
    assert any("drag" in s for s in steps)  # the flick
    assert {"wait": "4.00 * pace"} in steps
    # every step is one action the app knows
    known = {"status", "wait", "tap_exact", "drag", "gesture", "stop"}
    assert all(len(s) == 1 and next(iter(s)) in known for s in steps)


def test_cli_recording(tmp_path, capsys):
    folder = _recording(tmp_path, _cat_trip())
    assert cli.main(["recording", str(folder)]) == 0
    out = capsys.readouterr().out
    assert "11 moves" in out and "'Acteul'" in out and "walk L 800" in out
    assert cli.main(["recording", str(folder), "--cat"]) == 0
    assert "recorded_path: [L 800, U, R 1600]" in capsys.readouterr().out
    target = tmp_path / "out.yaml"
    assert cli.main(["recording", str(folder / "recording.json"), "--macro", "cat-acteul", "-o", str(target)]) == 0
    assert yaml.safe_load(target.read_text())["name"] == "cat-acteul"


def test_a_walk_that_turns_without_lifting(tmp_path):
    # left 1.2 s, slide up past the press point and hold 0.4 s (stairs), then left 0.8 s
    pts = [[0, 0.5, 0.65], [100, 0.46, 0.65], [1200, 0.46, 0.65], [1260, 0.5, 0.55], [1600, 0.5, 0.55],
           [1680, 0.45, 0.65], [2400, 0.45, 0.65]]
    gs = [{"i": 0, "gap_ms": 0, "dur_ms": 2400, "points": pts}, _tap(1, 0.6, 0.4)]
    rec = recordings.load(_recording(tmp_path, gs, "dogma-map-1"))
    m = recordings.moves(rec)[0]
    assert m.kind == "walk"
    assert m.tokens == ["L 1260", "+U 420", "+L 720"]
    assert recordings.dungeon_legs(rec) == [["chest 1", "L 1260", "+U 420", "+L 720"]]


def test_a_long_hold_down_is_a_walk_not_a_swipe(tmp_path):
    # the exit leg: a 5.2 s hold whose finger ends far below the press (stairs down)
    gs = [_tap(0, 0.6, 0.4), _hold(1, 0.70, 0.59, 0.70, 0.92, 5220)]
    rec = recordings.load(_recording(tmp_path, gs, "dogma-map-1"))
    m = recordings.moves(rec)[1]
    assert m.kind == "walk" and m.tokens == ["D 5220"]
    assert recordings.dungeon_legs(rec) == [["exit", "D 5220"]]


def test_a_hold_that_ends_with_a_flick_down(tmp_path):
    # dogma1's exit: right for 4.7 s, then the finger drops below the press just before lifting
    pts = [[0, 0.699, 0.592], [270, 0.821, 0.587], [4696, 0.820, 0.590], [5119, 0.728, 0.917], [5219, 0.728, 0.917]]
    gs = [_tap(0, 0.6, 0.4), {"i": 1, "gap_ms": 600, "dur_ms": 5219, "points": pts}]
    rec = recordings.load(_recording(tmp_path, gs, "dogma-map-1"))
    m = recordings.moves(rec)[1]
    assert m.kind == "walk" and m.tokens == ["R 5120", "+D"]
    assert recordings.dungeon_legs(rec) == [["exit", "R 5120", "+D"]]


def test_chest_stops_carry_the_recorded_frame_and_crop(tmp_path):
    import cv2
    import numpy as np

    gs = [_hold(0, 0.5, 0.65, 0.4, 0.65, 3000), _tap(1, 0.48, 0.36, gap=400), _tap(2, 0.6, 0.6, gap=900),
          _hold(3, 0.5, 0.65, 0.6, 0.65, 2000)]
    gs[1]["frame"] = "frames/t1.jpg"
    folder = _recording(tmp_path, gs, "dogma-map-1")
    (folder / "frames").mkdir()
    cv2.imwrite(str(folder / "frames" / "t1.jpg"), np.full((540, 1200, 3), 90, np.uint8))
    rec = recordings.load(folder)
    stops = recordings.dungeon_stops(rec)
    # the tap closing "Obtained" (move 3) is not a stop
    assert [s["leg"] for s in stops] == [["chest 1", "L 3000"], ["exit", "R 2000"]]
    assert stops[0]["move"] == 2 and stops[0]["frame"] == "frames/t1.jpg"
    assert "frame frames/t1.jpg" in recordings.dungeon_yaml(rec)
    crops = recordings.chest_crops(rec, tmp_path / "crops")
    assert [c.name for c in crops] == ["dogma-map-1-chest1.png"]
    h, w = cv2.imread(str(crops[0])).shape[:2]
    assert 80 <= w <= 86 and 120 <= h <= 128
