"""Command line: `another-macro <command>`.

Offline tools for the repo's data (they never touch a phone): planning the fixed walking
routes that the app's dungeon and Cat Scratchbook macros follow. Run `another-macro -h`.
"""
from __future__ import annotations

import argparse
import sys


def cmd_cat_routes(args) -> int:
    from . import cat_routes

    return cat_routes.main(write=args.write, check=args.check, todo=args.todo)


def cmd_dungeon_routes(args) -> int:
    from . import dungeon_routes

    return dungeon_routes.main(write=args.write, check=args.check)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="another-macro", description="Another Macro data tools")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("dungeon-routes", help="plan the fixed walking routes of the Another Dungeons (chests, exit) from their layouts")
    s.add_argument("--write", action="store_true", help="write them into dungeons.yaml as each map's path")
    s.add_argument("--check", action="store_true", help="fail if the paths in dungeons.yaml are out of date")
    s.set_defaults(fn=cmd_dungeon_routes)

    s = sub.add_parser("cat-routes", help="plan the fixed walking routes to the Cat Scratchbook cats from cat_spots.yaml")
    s.add_argument("--write", action="store_true", help="write them into cat_spots.yaml as each spot's path")
    s.add_argument("--check", action="store_true", help="fail if the paths in cat_spots.yaml are out of date")
    s.add_argument("--todo", action="store_true", help="list what each spot still needs measured on the phone")
    s.set_defaults(fn=cmd_cat_routes)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.fn(args) or 0


if __name__ == "__main__":
    sys.exit(main())
