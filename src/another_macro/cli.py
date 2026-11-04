"""Command line: `another-macro <command>`.

Run `another-macro -h` for the list. The small primitives (screenshot, tap, hold,
swipe, back, check) also let an AI agent drive the game step by step.
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from . import config


def _device(args):
    from .device.adb import AdbDevice

    return AdbDevice(args.serial or config.saved_serial())


def _point(dev, x: float, y: float):
    from .vision.geometry import Point

    # values <= 1 are normalized fractions, anything bigger is pixels
    if x <= 1 and y <= 1:
        return Point(x, y)
    w, h = dev.size()
    return Point(x / w, y / h)


def cmd_connect(args) -> None:
    from .device.adb import connect_local

    pairing = (args.pair_port, args.code) if args.pair_port else None
    serial = connect_local(args.port, pairing)
    config.serial_file().write_text(serial)
    print(f"connected to {serial}")


def cmd_devices(args) -> None:
    from .device.adb import AdbDevice, list_devices

    for serial, state in list_devices():
        print(f"{serial}\t{state}")
    dev = _device(args)
    print("Another Eden packages:", ", ".join(dev.find_packages()) or "none found")


def cmd_list(args) -> None:
    from .scenario.registry import all_scenarios

    for name, cls in all_scenarios().items():
        print(f"{name}\n    {cls.description}")
        for pname, p in cls.params.items():
            print(f"    --param {pname}={p.default}  {p.help}")


def cmd_play(args) -> int:
    from .scenario import base, registry

    scenario = registry.get(args.scenario)
    params = {}
    for item in args.param or []:
        key, _, value = item.partition("=")
        if key not in scenario.params:
            raise SystemExit(f"unknown param '{key}' for {scenario.name} (has: {', '.join(scenario.params)})")
        params[key] = scenario.params[key].type(value)
    dev = _device(args)
    result = base.run(scenario, dev, params, minutes=args.minutes, jitter=not args.no_jitter)
    print(f"stopped: {result.reason}")
    print(f"stats: {result.stats}")
    return 0 if "stuck" not in result.reason else 2


def cmd_stop(args) -> None:
    config.stop_file().touch()
    print("stop requested; the running scenario stops at its next step")


def cmd_screenshot(args) -> None:
    import cv2

    dev = _device(args)
    out = Path(args.output or config.home() / "screenshot.png")
    cv2.imwrite(str(out), dev.screenshot())
    w, h = dev.size()
    print(f"{out} ({w}x{h})")


def cmd_tap(args) -> None:
    dev = _device(args)
    dev.tap(_point(dev, args.x, args.y))


def cmd_hold(args) -> None:
    dev = _device(args)
    dev.hold(_point(dev, args.x, args.y), args.ms)


def cmd_swipe(args) -> None:
    dev = _device(args)
    dev.swipe(_point(dev, args.x1, args.y1), _point(dev, args.x2, args.y2), args.ms)


def cmd_back(args) -> None:
    _device(args).back()


def _load_frame(args):
    import cv2

    if args.source:
        frame = cv2.imread(args.source, cv2.IMREAD_COLOR)
        if frame is None:
            raise SystemExit(f"can't read image {args.source}")
        return frame
    return _device(args).screenshot()


def _library_paths(scenario_name: str | None) -> list[Path]:
    from .scenario import base, registry

    if scenario_name:
        return registry.get(scenario_name).library_files()
    return [base.COMMON_LIBRARY]


def cmd_check(args) -> None:
    from .vision.templates import Library, Vision

    frame = _load_frame(args)
    lib = Library.load(*_library_paths(args.scenario))
    vision = Vision(lib)
    print(f"frame {frame.shape[1]}x{frame.shape[0]}")
    for name, tpl in lib.templates.items():
        if not tpl.available:
            print(f"  {name:28s} not captured")
            continue
        m = vision.find(frame, name)
        print(f"  {name:28s} " + (f"FOUND score={m.score:.3f} at {m.region.as_list(3)}" if m else "-"))
    print("screen:", vision.identify(frame) or "unknown")


def cmd_capture_template(args) -> None:
    import cv2
    import yaml

    from .vision.geometry import Region

    frame = _load_frame(args)
    fh, fw = frame.shape[:2]
    left, top, width, height = (int(v) for v in args.box.split(","))
    crop = frame[top : top + height, left : left + width]
    if crop.size == 0:
        raise SystemExit("box is outside the screenshot")

    library = Path(args.library) if args.library else _library_paths(args.scenario)[-1]
    library.parent.mkdir(parents=True, exist_ok=True)
    png = library.parent / f"{args.name}.png"
    cv2.imwrite(str(png), crop)

    text = library.read_text(encoding="utf-8") if library.is_file() else ""
    header = "".join(line + "\n" for line in text.splitlines() if line.startswith("#")) if text else ""
    data = yaml.safe_load(text) or {}
    templates = data.setdefault("templates", {})
    spec = templates.get(args.name) or {}
    region = Region(left / fw, top / fh, width / fw, height / fh).padded(args.pad)
    spec.update({"file": png.name, "ref": [fw, fh], "region": region.as_list()})
    if args.threshold:
        spec["threshold"] = args.threshold
    templates[args.name] = spec
    body = yaml.safe_dump(data, sort_keys=False, allow_unicode=True)
    library.write_text(header + ("\n" if header else "") + body, encoding="utf-8")
    print(f"saved {png} and updated {library}")


def cmd_doctor(args) -> None:
    from .scenario import base, registry
    from .vision.templates import Library

    try:
        import cv2

        print("opencv", cv2.__version__)
    except ImportError:
        print("opencv MISSING (on the phone: pkg install x11-repo opencv-python)")
    try:
        dev = _device(args)
        w, h = dev.size()
        print(f"device {dev.serial}: {w}x{h}")
    except Exception as e:  # noqa: BLE001 - doctor reports, never crashes
        print(f"device: {e}")
    for name, cls in registry.all_scenarios().items():
        missing = Library.load(*cls().library_files()).missing()
        print(f"{name}: " + (f"templates to capture: {', '.join(missing)}" if missing else "ready"))


def cmd_recording(args) -> None:
    from . import recordings

    rec = recordings.load(args.path)
    if args.macro:
        text = recordings.to_macro(rec, args.macro)
    elif args.cat:
        text = recordings.cat_spot_yaml(rec)
    elif args.dungeon:
        text = recordings.dungeon_yaml(rec)
        if args.crops:
            for c in recordings.chest_crops(rec, Path(args.crops)):
                print(f"chest crop: {c}  (a game screenshot: don't commit it)")
    else:
        text = recordings.summary(rec) + "\n"
    if args.output:
        Path(args.output).write_text(text)
        print(f"wrote {args.output}")
    else:
        print(text, end="")


def cmd_record_touches(args) -> None:
    from . import touch_recorder

    if args.action == "start":
        touch_recorder.start(args.out, args.rotation)
    else:
        touch_recorder.stop(args.out)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="another-macro", description="Another Eden macro framework")
    p.add_argument("--serial", help="ADB serial (default: the one saved by `connect`, or the only device)")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("connect", help="connect the phone's ADB to itself (Wireless debugging)")
    s.add_argument("--port", type=int, help="connect port shown under Wireless debugging (auto-discovered if omitted)")
    s.add_argument("--pair-port", type=int, help="pairing port from 'Pair device with pairing code' (first time only)")
    s.add_argument("--code", help="six-digit pairing code (first time only)")
    s.set_defaults(fn=cmd_connect)

    sub.add_parser("devices", help="list ADB devices and installed Another Eden packages").set_defaults(fn=cmd_devices)
    sub.add_parser("list", help="list scenarios and their params").set_defaults(fn=cmd_list)
    sub.add_parser("doctor", help="check OpenCV, the device, and which templates still need capturing").set_defaults(fn=cmd_doctor)

    s = sub.add_parser("play", help="run a scenario")
    s.add_argument("scenario")
    s.add_argument("--param", action="append", metavar="KEY=VALUE")
    s.add_argument("--minutes", type=float, help="stop after this many minutes")
    s.add_argument("--no-jitter", action="store_true", help="tap exact centers with exact timings")
    s.set_defaults(fn=cmd_play)

    sub.add_parser("stop", help="ask a running scenario to stop").set_defaults(fn=cmd_stop)

    s = sub.add_parser("screenshot", help="save the current screen")
    s.add_argument("-o", "--output")
    s.set_defaults(fn=cmd_screenshot)

    s = sub.add_parser("tap", help="tap at X Y (fractions 0..1, or pixels)")
    s.add_argument("x", type=float)
    s.add_argument("y", type=float)
    s.set_defaults(fn=cmd_tap)

    s = sub.add_parser("hold", help="press and hold at X Y for MS milliseconds")
    s.add_argument("x", type=float)
    s.add_argument("y", type=float)
    s.add_argument("ms", type=int)
    s.set_defaults(fn=cmd_hold)

    s = sub.add_parser("swipe", help="swipe from X1 Y1 to X2 Y2")
    for a in ("x1", "y1", "x2", "y2"):
        s.add_argument(a, type=float)
    s.add_argument("ms", type=int, nargs="?", default=300)
    s.set_defaults(fn=cmd_swipe)

    sub.add_parser("back", help="press Android back").set_defaults(fn=cmd_back)

    s = sub.add_parser("check", help="show which templates/screen match a screenshot")
    s.add_argument("--from", dest="source", help="image file (default: live screenshot)")
    s.add_argument("--scenario", help="include this scenario's own templates")
    s.set_defaults(fn=cmd_check)

    s = sub.add_parser("capture-template", help="cut a template from a screenshot and register it")
    s.add_argument("name")
    s.add_argument("--box", required=True, help="pixel box LEFT,TOP,WIDTH,HEIGHT in the screenshot")
    s.add_argument("--from", dest="source", help="image file (default: live screenshot)")
    s.add_argument("--scenario", help="save into this scenario's library instead of the common one")
    s.add_argument("--library", help="explicit library.yaml path")
    s.add_argument("--pad", type=float, default=0.05, help="search-region padding around the box (fraction of screen)")
    s.add_argument("--threshold", type=float)
    s.set_defaults(fn=cmd_capture_template)

    s = sub.add_parser("recording", help="turn a recording made with the app's record button into steps/routes")
    s.add_argument("path", help="the recording's folder (adb pull'ed from the phone) or its recording.json")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--macro", metavar="NAME", help="write a macro .yaml (tap_exact/drag/wait steps) named NAME")
    g.add_argument("--cat", action="store_true", help="cat_spots.yaml fields + the walk to the cat")
    g.add_argument("--dungeon", action="store_true", help="the walks as dungeons.yaml path legs")
    s.add_argument("-o", "--output", help="write to this file instead of printing")
    s.add_argument("--crops", metavar="DIR", help="with --dungeon: cut each chest out of its recorded frame into DIR")
    s.set_defaults(fn=cmd_recording)

    s = sub.add_parser("record-touches", help="read the touchscreen through ADB (the app's record button runs this in Termux)")
    s.add_argument("action", choices=["start", "stop"])
    s.add_argument("--out", help="the app's recording folder on the phone; stop pushes touches.json there")
    s.add_argument("--rotation", type=int, default=1, help="Display rotation while recording (1 = landscape, turned left)")
    s.set_defaults(fn=cmd_record_touches)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(message)s",
        datefmt="%H:%M:%S",
    )
    try:
        return args.fn(args) or 0
    except KeyboardInterrupt:
        return 130
    except Exception as e:  # noqa: BLE001 - one-line errors for a phone terminal
        if args.verbose:
            raise
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
