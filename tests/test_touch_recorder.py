"""Parsing `adb shell getevent` output into gestures (synthetic logs, no phone)."""
from another_macro import touch_recorder as tr

DEVICES = """add device 1: /dev/input/event4
  name:     "gpio_keys"
  events:
    KEY (0001): KEY_VOLUMEDOWN
add device 2: /dev/input/event2
  name:     "sec_touchscreen"
  events:
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
"""


def ev(t, code, value, kind="EV_ABS", dev="/dev/input/event2"):
    return f"[{t:14.6f}] {dev}: {kind:<12} {code:<20} {value}"


def syn(t):
    return ev(t, "SYN_REPORT", "00000000", kind="EV_SYN")


def test_touchscreen_found():
    ts = tr.parse_devices(DEVICES)
    assert (ts.path, ts.max_x, ts.max_y) == ("/dev/input/event2", 1079, 2399)


def test_rotation_maps_to_the_landscape_screen():
    # ROTATION_90: the natural top-left corner ends at the bottom-left
    assert tr.to_screen(0.0, 0.0, 1) == (0.0, 1.0)
    assert tr.to_screen(1.0, 1.0, 1) == (1.0, 0.0)
    assert tr.to_screen(0.0, 0.0, 3) == (1.0, 0.0)


def test_a_hold_and_a_tap():
    lines = [
        ev(100.0, "ABS_MT_TRACKING_ID", "0000002a"),
        ev(100.0, "ABS_MT_POSITION_X", f"{540:08x}"),
        ev(100.0, "ABS_MT_POSITION_Y", f"{1200:08x}"),
        syn(100.0),
        ev(100.1, "ABS_MT_POSITION_Y", f"{1000:08x}"),   # natural y down = landscape x left
        syn(100.1),
        ev(100.9, "ABS_MT_POSITION_X", f"{541:08x}"),
        syn(100.9),
        ev(101.2, "ABS_MT_TRACKING_ID", "ffffffff"),
        syn(101.2),
        ev(102.0, "SYN_REPORT", "00000000", kind="EV_SYN", dev="/dev/input/event4"),  # another device
        ev(103.0, "ABS_MT_TRACKING_ID", "0000002b"),
        ev(103.0, "ABS_MT_POSITION_X", f"{200:08x}"),
        ev(103.0, "ABS_MT_POSITION_Y", f"{2000:08x}"),
        syn(103.0),
        ev(103.06, "ABS_MT_TRACKING_ID", "ffffffff"),
        syn(103.06),
    ]
    out = tr.convert(DEVICES, "\n".join(lines), rotation=1)
    assert out["clock"] == "monotonic"
    hold, tap = out["gestures"]
    assert hold["down_s"] == 100.0 and hold["dur_ms"] == 1200
    assert hold["points"][0] == [0, round(1200 / 2399, 4), round(1 - 540 / 1079, 4)]
    assert hold["points"][1][0] == 100 and hold["points"][1][1] < hold["points"][0][1]  # moved left
    assert tap["dur_ms"] == 60 and not tap["multi_touch"]


def test_second_finger_marks_multi_touch():
    lines = [
        ev(5.0, "ABS_MT_TRACKING_ID", "00000001"), ev(5.0, "ABS_MT_POSITION_X", "00000010"),
        ev(5.0, "ABS_MT_POSITION_Y", "00000010"), syn(5.0),
        ev(5.1, "ABS_MT_SLOT", "00000001"), ev(5.1, "ABS_MT_TRACKING_ID", "00000002"),
        ev(5.1, "ABS_MT_POSITION_X", "00000100"), ev(5.1, "ABS_MT_POSITION_Y", "00000100"), syn(5.1),
        ev(5.2, "ABS_MT_TRACKING_ID", "ffffffff"), syn(5.2),
        ev(5.3, "ABS_MT_SLOT", "00000000"), ev(5.3, "ABS_MT_TRACKING_ID", "ffffffff"), syn(5.3),
    ]
    (g,) = tr.convert(DEVICES, "\n".join(lines), rotation=0)["gestures"]
    assert g["multi_touch"] and g["dur_ms"] == 300
    assert {tuple(p[1:]) for p in g["points"]} == {(round(16 / 1079, 4), round(16 / 2399, 4))}


def test_no_touchscreen_is_an_error():
    assert "error" in tr.convert("add device 1: /dev/input/event0\n", "", 1)
