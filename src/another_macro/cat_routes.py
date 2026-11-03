"""Deterministic routes to the Cat Scratchbook cats (`another-macro cat-routes`).

Each scratchbook clue points at a fixed place, and the cat always starts at the same spot on
that place's map (it wanders a little from there, always along the same street). So, like the
Another Dungeon paths (dungeon_routes.py), the whole trip can be a fixed list of actions:

1. **Point zero**: open the World Map, tap the clue's era tab, open Expand and tap the
   continent, then drag the map into its top-left corner (the drags stop at the edges, so the
   view is the same every time, wherever the map was before).
2. **World map**: from point zero, the fixed pans (`world_map.pans`) and the tap on the place's
   label (`world_map.tap`), then the panel entry for the sub-location (`sub`) and Yes.
3. **Area map**: the party arrives at a fixed spot (`area.arrival`); the fixed moves (`path`,
   generated here from the street layout, the walk table and the map's `walk_scale`) take it to
   the cat's start (`area.cat`).
4. **Drift**: the cat wanders along its street up to `area.drift` = [left, right] from its start;
   the app looks for the feather bubble at the start, then at each end of that range.

The data is `scenarios/cat_scratchbook/templates/cat_spots.yaml`, keyed by the clue's
`location` in riddles.yaml. Positions are measured on the phone (the `map-dungeon` way: the
opened area map, fractions of a 2400x1080 screen). A spot whose measurements are missing has no
`path` and the app finds the cat the old way (searching the world map by OCR, steering by the
area map's feather icon).
"""
from __future__ import annotations

import re
from pathlib import Path

import yaml

from . import dungeon_routes as dr

SPOTS = Path(__file__).resolve().parent / "scenarios" / "cat_scratchbook" / "templates" / "cat_spots.yaml"
RIDDLES = Path(__file__).resolve().parent / "scenarios" / "cat_scratchbook" / "riddles.yaml"

AREA_KEYS = ("segs", "arrival", "cat")


def load_spots(path: Path = SPOTS) -> dict[str, dict]:
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def scaled(t: dr.WalkTable, scale: float) -> dr.WalkTable:
    """The walk table for a map whose area map is drawn [scale] times as large as Dogma Tower's.

    The same hold walks the same distance in the game, but each area map has its own zoom, so
    the distance in area-map units differs by map (measured once per map: hold R 3000 from rest
    and compare with walk.yaml's 0.0709).
    """
    if scale == 1.0:
        return t
    data = {
        "hold_ms": t.ms,
        "distance": [d * scale for d in t.dist],
        "beyond_per_s": t.beyond * scale,
        "flick_ms": t.flick_ms,
        "min_walk": t.min_walk,
        "wall_snap": t.wall_snap,
        "wall_margin": t.wall_margin,
    }
    return dr.WalkTable(data)


def area_map(name: str, spot: dict) -> dr.MapData | None:
    """The spot's map as a layout the dungeon planner understands, the cat as its only stop."""
    a = spot.get("area") or {}
    if spot.get("walk_to") or any(a.get(k) is None for k in AREA_KEYS):
        return None  # unmeasured, or on a map past the travel point (not routed yet)
    return dr.MapData(
        name=name,
        segs=[dr.Seg(*s) for s in a["segs"]],
        links=[dr.Link(*ln) for ln in a.get("links") or []],
        chests=[],
        arrival=tuple(a["arrival"]),
        exit=tuple(a["cat"]),
    )


def drift_range(name: str, spot: dict) -> tuple[float, float]:
    """Where on its street the cat can be: (x from, x to), inside the street."""
    m = area_map(name, spot)
    assert m is not None
    left, right = (spot["area"].get("drift") or [0.0, 0.0])
    cat = m.exit
    s = dr.seg_of(m, cat)
    if s is None:
        raise ValueError(f"{name}: the cat {cat} is on no street")
    return max(s.x1, cat[0] - left), min(s.x2, cat[0] + right)


def plan(name: str, spot: dict, t: dr.WalkTable) -> list[str] | None:
    """The fixed moves from the arrival to the cat's start, or None while unmeasured."""
    m = area_map(name, spot)
    if m is None:
        return None
    tt = scaled(t, float(spot["area"].get("walk_scale") or 1.0))
    _, moves = dr._route(m, tt, m.arrival, m.exit)
    steps, _ = dr._leg_steps(m, tt, m.arrival, m.exit, moves)
    return steps


def follow(name: str, spot: dict, t: dr.WalkTable, steps: list[str]) -> tuple[float, float]:
    """Where the moves leave the party (the rules the app follows too)."""
    m = area_map(name, spot)
    assert m is not None
    return dr.run_steps(m, scaled(t, float(spot["area"].get("walk_scale") or 1.0)), m.arrival, steps)


def missing(spot: dict) -> list[str]:
    """What still has to be measured on the phone for a fully fixed trip."""
    out = ["walking across from the travel point (walk_to: not routed yet)"] if spot.get("walk_to") else []
    if not spot.get("continent"):
        out.append("continent")
    if not (spot.get("world_map") or {}).get("tap"):
        out.append("world_map")
    a = spot.get("area") or {}
    out += [f"area.{k}" for k in AREA_KEYS if a.get(k) is None]
    if a.get("walk_scale") is None:
        out.append("area.walk_scale")
    if a.get("drift") is None:
        out.append("area.drift")
    return out


# --- writing the paths into cat_spots.yaml ---------------------------------------------
_TOP = re.compile(r"^[^\s#].*:\s*$")
_GEN = "  # generated by `another-macro cat-routes --write`"


def render(path: Path = SPOTS, table: dr.WalkTable | None = None) -> str:
    """cat_spots.yaml with every measured spot's `path:` (re)generated; the rest kept as written."""
    t = table or dr.WalkTable.load()
    spots = load_spots(path)
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    starts = [i for i, ln in enumerate(lines) if _TOP.match(ln)]
    assert len(starts) == len(spots), "every spot must start with an unindented 'Name:' line"
    out = lines[: starts[0]] if starts else lines
    bounds = starts + [len(lines)]
    for k, name in enumerate(spots):
        block = lines[bounds[k] : bounds[k + 1]]
        end = len(block)
        while end > 1 and (not block[end - 1].startswith("  ") or block[end - 1].strip() == ""):
            end -= 1
        body = [ln for ln in block[:end] if not ln.startswith("  path:") and not ln.startswith(_GEN)]
        steps = plan(name, spots[name], t)
        if steps is not None:
            body += [f"{_GEN}\n", f"  path: [{', '.join(steps)}]\n"]
        out += body + block[end:]
    return "".join(out)


def skeleton(riddles: Path = RIDDLES, spots: Path = SPOTS) -> str:
    """Entries for riddles.yaml locations that cat_spots.yaml doesn't have yet (to append)."""
    have = load_spots(spots) if spots.is_file() else {}
    seen, out = set(), []
    for r in (yaml.safe_load(riddles.read_text(encoding="utf-8")) or {}).values():
        loc = r.get("location")
        if not loc or loc in have or loc in seen:
            continue
        seen.add(loc)
        place, sub = split_location(loc)
        via = re.search(r"\(Enter from ([^)]*)\)", loc)
        if via:  # the cat is on the map next to the travel point: travel there, walk across
            place, sub = via.group(1), None
        out.append(
            f"{_scalar(loc)}:\n"
            f"  era: {r.get('era') or 'null'}\n"
            f"  continent: null\n"
            f"  place: {_scalar(place)}\n"
            f"  sub: {_scalar(sub) if sub else 'null'}\n"
            f"  world_map: null\n"
            + (f"  walk_to: {_scalar(split_location(loc)[0])}\n" if via else "")
            + "  area: null\n"
        )
    return "".join(out)


def _scalar(v: str) -> str:
    """v as a one-line YAML scalar (quoted when it needs to be)."""
    return yaml.safe_dump({"k": v}, allow_unicode=True, width=10_000).strip()[3:]


def split_location(loc: str) -> tuple[str, str | None]:
    """'Moonlight Forest (Forest Edge)' -> ('Moonlight Forest', 'Forest Edge'), the CatDaily rule."""
    m = re.match(r"^(.*?)\s*\(([^)]*)\)\s*$", loc)
    if m and not m.group(2).startswith("Enter from") and "quick destination" not in m.group(2):
        return m.group(1), m.group(2)
    if m:
        return m.group(1), None
    m = re.match(r"^(.*?)\s+[–-]\s+(.+)$", loc)
    if m:
        return m.group(1), m.group(2)
    return loc, None


def main(write: bool = False, check: bool = False, todo: bool = False) -> int:
    t = dr.WalkTable.load()
    spots = load_spots()
    ready = 0
    for name, spot in spots.items():
        steps = plan(name, spot, t)
        if steps is not None:
            ready += 1
            lo, hi = drift_range(name, spot)
            print(f"{name} ({spot.get('era')}): {', '.join(steps) or '(arrives at the cat)'}; cat drifts x {lo:.3f}-{hi:.3f}")
        elif todo:
            print(f"{name} ({spot.get('era')}): to measure: {', '.join(missing(spot))}")
    print(f"{ready}/{len(spots)} spots have a fixed route")
    new = render(SPOTS, t)
    current = SPOTS.read_text(encoding="utf-8")
    if check and new != current:
        print("cat_spots.yaml paths are out of date: run `another-macro cat-routes --write`")
        return 1
    if write and new != current:
        SPOTS.write_text(new, encoding="utf-8")
        print(f"wrote {SPOTS}")
    return 0
