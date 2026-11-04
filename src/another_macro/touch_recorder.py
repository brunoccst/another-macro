"""Live touch recording for the app's ⏺ button: the touchscreen read through ADB.

Android gives an app no way to watch the touches another app gets, but ADB's shell
can read the touchscreen itself (``getevent``), with no effect on the game. So the
app's ⏺ asks Termux (RUN_COMMAND) to run::

    another-macro record-touches start --out <the app's pending recording folder> --rotation 1

which reads ``adb shell getevent -lt`` in the background into ``~/.another-macro/touchrec``,
and ⏹ runs ``another-macro record-touches stop --out <same folder>``, which turns the
log into ``touches.json`` (the gestures, below) and pushes it into the app's folder
with ``adb push`` (Termux can't write there itself; ADB's shell can). The app then
adds a frame per move and writes recording.json (see recordings.py).

touches.json::

    {"clock": "monotonic" | "realtime", "rotation": 1, "screen": [maxX+1, maxY+1],
     "gestures": [{"down_s": 1234.5678, "dur_ms": 820, "multi_touch": false,
                   "points": [[0, x, y], [16, x, y], ...]}]}       # x, y: 0..1 of the screen as shown
    or {"error": "..."}

``down_s`` is the kernel's timestamp of the press (seconds; CLOCK_MONOTONIC, which is
the app's SystemClock.uptimeMillis, or wall time if getevent didn't switch clocks).
"""
from __future__ import annotations

import json
import os
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import config

# `getevent -lt` lines; reading a single device (`getevent -lt /dev/input/event2`) leaves the path out
LINE = re.compile(r"^\[\s*(\d+\.\d+)\]\s+(?:(/dev/input/\S+):\s+)?(\S+)\s+(\S+)\s+(\S+)")
NAME = re.compile(r'name:\s+"([^"]*)"')
DEVICE = re.compile(r"add device \d+:\s+(/dev/input/\S+)")
AXIS = re.compile(r"(ABS_MT_POSITION_[XY])\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)")
UP = 0xFFFFFFFF


def work_dir() -> Path:
    d = config.home() / "touchrec"
    d.mkdir(parents=True, exist_ok=True)
    return d


# --- parsing --------------------------------------------------------------------------

@dataclass
class Touchscreen:
    path: str
    min_x: int
    max_x: int
    min_y: int
    max_y: int
    name: str = ""


def parse_devices(text: str, log: str = "") -> Touchscreen | None:
    """The touchscreen from `getevent -pl`: of the devices with multi-touch positions (a phone can
    also have a touchpad one, e.g. Samsung's "sec_touchpad"), the one named *touchscreen*, else
    the one with the most events in [log], else the first."""
    found: list[Touchscreen] = []
    for b in re.split(r"(?=add device \d+:)", text):
        m = DEVICE.search(b)
        axes = {a: (int(lo), int(hi)) for a, lo, hi in AXIS.findall(b)}
        if m and "ABS_MT_POSITION_X" in axes and "ABS_MT_POSITION_Y" in axes:
            (x0, x1), (y0, y1) = axes["ABS_MT_POSITION_X"], axes["ABS_MT_POSITION_Y"]
            n = NAME.search(b)
            found.append(Touchscreen(m.group(1), x0, x1, y0, y1, n.group(1) if n else ""))
    if not found:
        return None
    named = [t for t in found if "touchscreen" in t.name.lower()]
    if named:
        return named[0]
    counts = {t.path: log.count(t.path + ":") for t in found}
    return max(found, key=lambda t: counts[t.path]) if any(counts.values()) else found[0]


def to_screen(rx: float, ry: float, rotation: int) -> tuple[float, float]:
    """Natural-orientation fractions (0..1) to fractions of the screen as shown.

    rotation is Android's Display rotation: 1 = ROTATION_90 (turned left, the usual
    landscape), 3 = ROTATION_270 (turned right).
    """
    if rotation == 1:
        return ry, 1 - rx
    if rotation == 2:
        return 1 - rx, 1 - ry
    if rotation == 3:
        return 1 - ry, rx
    return rx, ry


@dataclass
class _Slot:
    tracking: int = -1
    x: int | None = None
    y: int | None = None


@dataclass
class _Gesture:
    down_s: float
    points: list = field(default_factory=list)   # [s, x, y] in screen fractions
    multi: bool = False


def parse_events(log: str, ts: Touchscreen, rotation: int) -> list[dict]:
    """Gestures of the first finger down (others mark multi_touch), from `getevent -lt` lines."""
    slots: dict[int, _Slot] = {}
    slot = 0
    primary: int | None = None        # slot of the finger being followed
    cur: _Gesture | None = None
    out: list[_Gesture] = []
    span_x = max(1, ts.max_x - ts.min_x)
    span_y = max(1, ts.max_y - ts.min_y)
    for raw in log.splitlines():
        m = LINE.match(raw.strip())
        if not m or (m.group(2) is not None and m.group(2) != ts.path):
            continue
        t = float(m.group(1))
        kind, code, value = m.group(3), m.group(4), m.group(5)
        if kind == "EV_ABS":
            try:
                v = int(value, 16)
            except ValueError:
                continue
            if code == "ABS_MT_SLOT":
                slot = v
            elif code == "ABS_MT_TRACKING_ID":
                s = slots.setdefault(slot, _Slot())
                if v == UP:
                    s.tracking = -1
                else:
                    s.tracking = v
                    if primary is None:
                        primary = slot
                        cur = _Gesture(down_s=t)
                    elif cur is not None and slot != primary:
                        cur.multi = True
            elif code == "ABS_MT_POSITION_X":
                slots.setdefault(slot, _Slot()).x = v
            elif code == "ABS_MT_POSITION_Y":
                slots.setdefault(slot, _Slot()).y = v
        elif kind == "EV_SYN" and code == "SYN_REPORT" and primary is not None and cur is not None:
            s = slots.get(primary)
            if s is None:
                continue
            if s.x is not None and s.y is not None and (s.tracking >= 0 or not cur.points):
                fx = (s.x - ts.min_x) / span_x
                fy = (s.y - ts.min_y) / span_y
                x, y = to_screen(min(max(fx, 0.0), 1.0), min(max(fy, 0.0), 1.0), rotation)
                cur.points.append([t, x, y])
            if s.tracking < 0:
                if cur.points:
                    cur.points.append([t, cur.points[-1][1], cur.points[-1][2]])
                    out.append(cur)
                primary, cur = None, None
    return [_gesture_json(g) for g in out]


def _gesture_json(g: _Gesture) -> dict:
    t0 = g.points[0][0]
    pts: list[list] = []
    for t, x, y in g.points:
        ms = int(round((t - t0) * 1000))
        p = [ms, round(x, 4), round(y, 4)]
        if pts and ms - pts[-1][0] < 16 and len(pts) > 1:
            pts[-1] = p           # at most one point per frame, always the last one
        elif not pts or p != pts[-1]:
            pts.append(p)
    if len(pts) == 1:
        pts.append([int(round((g.points[-1][0] - t0) * 1000)), pts[0][1], pts[0][2]])
    return {"down_s": round(g.down_s, 4), "dur_ms": pts[-1][0], "multi_touch": g.multi, "points": pts}


def clock_kind(down_s: float) -> str:
    """getevent's timestamps: seconds since boot, or since 1970 if it kept the wall clock."""
    return "realtime" if down_s > 1e8 else "monotonic"


def convert(device_txt: str, log: str, rotation: int) -> dict:
    ts = parse_devices(device_txt, log)
    if ts is None:
        return {"error": "no touchscreen found in `getevent -pl`"}
    gestures = parse_events(log, ts, rotation)
    return {
        "clock": clock_kind(gestures[0]["down_s"]) if gestures else "monotonic",
        "rotation": rotation,
        "device": ts.path,
        "gestures": gestures,
    }


# --- running (Termux) -------------------------------------------------------------------

def _serial() -> str:
    from .device.adb import AdbError, connect_local, pick_serial

    try:
        return pick_serial(config.saved_serial())
    except AdbError:
        serial = connect_local()
        config.serial_file().write_text(serial)
        return serial


def start(out: str, rotation: int) -> None:
    """Start reading the touchscreen in the background (returns at once)."""
    stop(out=None, quiet=True)
    d = work_dir()
    for f in ("touches.log", "device.txt", "touches.json"):
        (d / f).unlink(missing_ok=True)
    (d / "session.json").write_text(json.dumps({"out": out, "rotation": rotation, "started": time.time()}))
    log = open(d / "loop.log", "w")
    proc = subprocess.Popen(
        [sys.executable, "-m", "another_macro.touch_recorder", "loop"],
        stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True,
    )
    (d / "loop.pid").write_text(str(proc.pid))
    print(f"recording touches (pid {proc.pid})")


def loop() -> None:
    """The background reader: `getevent -lt` through a pty (so it isn't buffered) into touches.log."""
    from .device.adb import adb_path, run_adb

    d = work_dir()
    serial = _serial()
    (d / "device.txt").write_text(run_adb("shell", "getevent -pl", serial=serial, timeout=15))
    proc = subprocess.Popen([adb_path(), "-s", serial, "shell", "-tt", "getevent", "-lt"],
                            stdout=subprocess.PIPE, stdin=subprocess.DEVNULL)

    def finish(*_):
        proc.terminate()
        try:
            run_adb("shell", "pkill -x getevent", serial=serial, timeout=5)
        except Exception:  # noqa: BLE001 - it may already be gone
            pass
        sys.exit(0)

    signal.signal(signal.SIGTERM, finish)
    signal.signal(signal.SIGINT, finish)
    with open(d / "touches.log", "wb") as f:
        assert proc.stdout is not None
        for line in proc.stdout:
            f.write(line.replace(b"\r", b""))
            f.flush()


def stop(out: str | None, quiet: bool = False) -> None:
    """Stop the reader, convert the log and push touches.json into [out] on the phone."""
    d = work_dir()
    pid_file = d / "loop.pid"
    if pid_file.is_file():
        try:
            os.kill(int(pid_file.read_text()), signal.SIGTERM)
            time.sleep(0.8)
        except (ProcessLookupError, ValueError):
            pass
        pid_file.unlink(missing_ok=True)
    if out is None:
        return
    session = json.loads((d / "session.json").read_text()) if (d / "session.json").is_file() else {}
    rotation = int(session.get("rotation", 1))
    device_txt = (d / "device.txt").read_text() if (d / "device.txt").is_file() else ""
    log = (d / "touches.log").read_text(errors="replace") if (d / "touches.log").is_file() else ""
    if not device_txt:
        loop_log = (d / "loop.log").read_text(errors="replace")[-400:] if (d / "loop.log").is_file() else ""
        result = {"error": "the touch reader never started (is ADB connected? `another-macro connect`) " + loop_log.strip()}
    else:
        result = convert(device_txt, log, rotation)
    (d / "touches.json").write_text(json.dumps(result))
    from .device.adb import run_adb

    run_adb("push", str(d / "touches.json"), f"{out.rstrip('/')}/touches.json", serial=_serial(), timeout=30)
    if not quiet:
        print(f"{len(result.get('gestures', []))} moves -> {out}/touches.json" if "error" not in result else result["error"])


if __name__ == "__main__":
    if sys.argv[1:] == ["loop"]:
        loop()
