"""Deterministic walking routes for the Another Dungeons (`another-macro dungeon-routes`).

The app walks each dungeon map by a fixed list of moves instead of opening the area map
to look where the party is. This module makes those lists from the map layouts in
`scenarios/another_dungeon/templates/dungeons.yaml` and the hold-time table in
`walk.yaml`, and writes them into dungeons.yaml as each map's `path:`.

A path is a list of legs, one per stop: every chest (in the order that takes the least
time) and then the exit. A leg is its target and its moves:

    - [chest 2, R 1180, U, L 2950]
    - [exit, R 4300]

`R 1180` / `L 2950`: hold right/left for that many ms, starting from rest.
`U` / `D`: a quick flick up/down, which takes the stairs the party stands on.

The app (AnotherDungeon.kt / Route.kt) follows the moves. When a battle interrupts a
hold it converts the time already held to a distance with the same table, fights, and
holds the rest from rest again (the party is stopped after a battle).
"""
from __future__ import annotations

import bisect
import itertools
import re
from dataclasses import dataclass
from pathlib import Path

import yaml

DATA_DIR = Path(__file__).resolve().parent / "scenarios" / "another_dungeon" / "templates"
DUNGEONS = DATA_DIR / "dungeons.yaml"
WALK = DATA_DIR / "walk.yaml"

ROW_TOL = 0.035  # same as AreaNav.ROW_TOL
LINK_TOL = 0.02  # a link's end is on a street within this much (AreaNav.route)
STAIRS_TOL = 0.006  # a flick takes the stairs only from about this close


@dataclass(frozen=True)
class Seg:
    y: float
    x1: float
    x2: float


@dataclass(frozen=True)
class Link:
    x: float
    y1: float
    y2: float


@dataclass
class MapData:
    name: str
    segs: list[Seg]
    links: list[Link]
    chests: list[tuple[float, float]]
    arrival: tuple[float, float]
    exit: tuple[float, float] | None


class WalkTable:
    """Hold time from rest <-> distance walked (piecewise linear, extrapolated past the end)."""

    def __init__(self, data: dict):
        self.ms = [float(v) for v in data["hold_ms"]]
        self.dist = [float(v) for v in data["distance"]]
        assert self.ms[0] == 0 and self.dist[0] == 0, "walk table must start at 0 ms / 0 distance"
        assert all(b > a for a, b in zip(self.ms, self.ms[1:])), "hold_ms must increase"
        assert all(b > a for a, b in zip(self.dist, self.dist[1:])), "distance must increase"
        self.beyond = float(data["beyond_per_s"])
        self.flick_ms = float(data.get("flick_ms", 2650))
        self.min_walk = float(data.get("min_walk", 0.003))
        self.wall_snap = float(data.get("wall_snap", 0.012))
        self.wall_margin = float(data.get("wall_margin", 0.008))

    @classmethod
    def load(cls, path: Path = WALK) -> "WalkTable":
        return cls(yaml.safe_load(path.read_text()))

    def distance(self, ms: float) -> float:
        if ms <= 0:
            return 0.0
        if ms >= self.ms[-1]:
            return self.dist[-1] + (ms - self.ms[-1]) / 1000 * self.beyond
        i = bisect.bisect_right(self.ms, ms)
        m0, m1, d0, d1 = self.ms[i - 1], self.ms[i], self.dist[i - 1], self.dist[i]
        return d0 + (d1 - d0) * (ms - m0) / (m1 - m0)

    def hold_ms(self, distance: float) -> float:
        if distance <= 0:
            return 0.0
        if distance >= self.dist[-1]:
            return self.ms[-1] + (distance - self.dist[-1]) / self.beyond * 1000
        i = bisect.bisect_right(self.dist, distance)
        m0, m1, d0, d1 = self.ms[i - 1], self.ms[i], self.dist[i - 1], self.dist[i]
        return m0 + (m1 - m0) * (distance - d0) / (d1 - d0)


def load_maps(path: Path = DUNGEONS) -> list[tuple[str, int, MapData | None, dict]]:
    """Every map of every dungeon in file order: (dungeon id, map index, layout or None, raw)."""
    out = []
    for did, d in yaml.safe_load(path.read_text()).items():
        for i, m in enumerate(d["maps"]):
            layout = None
            if m.get("segs"):
                layout = MapData(
                    name=m["names"][0],
                    segs=[Seg(*s) for s in m["segs"]],
                    links=[Link(*ln) for ln in m.get("links", [])],
                    chests=[tuple(c) for c in m.get("chests", [])],
                    arrival=tuple(m["arrival"]),
                    exit=tuple(m["exit"]) if m.get("exit") else None,
                )
            out.append((did, i, layout, m))
    return out


# --- the street graph ------------------------------------------------------------------
def seg_of(m: MapData, p: tuple[float, float]) -> Seg | None:
    """The street a point is on (same rule as AreaNav.segOf)."""
    near = [s for s in m.segs if abs(s.y - p[1]) < ROW_TOL * 1.5 and s.x1 - 0.04 < p[0] < s.x2 + 0.04]
    return min(near, key=lambda s: abs(s.y - p[1]) + max(0.0, s.x1 - p[0], p[0] - s.x2), default=None)


def _link_segs(m: MapData, ln: Link) -> tuple[Seg, Seg] | None:
    def on(y):
        return next((s for s in m.segs if abs(s.y - y) < LINK_TOL and s.x1 - LINK_TOL <= ln.x <= s.x2 + LINK_TOL), None)

    a, b = on(ln.y1), on(ln.y2)
    return (a, b) if a and b else None


def _route(m: MapData, t: WalkTable, a: tuple[float, float], b: tuple[float, float]) -> tuple[float, list]:
    """Fastest way a -> b: (time in ms, moves). Moves: ("walk", x) or ("climb", up)."""
    sa, sb = seg_of(m, a), seg_of(m, b)
    if sa is None or sb is None:
        raise ValueError(f"{m.name}: {a if sa is None else b} is on no street")
    nodes = [(sa, min(max(a[0], sa.x1), sa.x2)), (sb, min(max(b[0], sb.x1), sb.x2))]
    climbs: dict[tuple[int, int], bool] = {}
    for ln in m.links:
        ends = _link_segs(m, ln)
        if not ends:
            continue
        nodes += [(ends[0], ln.x), (ends[1], ln.x)]
        i, j = len(nodes) - 2, len(nodes) - 1
        climbs[(i, j)] = ends[1].y < ends[0].y
        climbs[(j, i)] = ends[0].y < ends[1].y
    n = len(nodes)
    inf = float("inf")
    dist, prev, done = [inf] * n, [-1] * n, [False] * n
    dist[0] = 0.0
    for _ in range(n):
        u = min((k for k in range(n) if not done[k]), key=lambda k: dist[k], default=None)
        if u is None or dist[u] == inf:
            break
        done[u] = True
        for v in range(n):
            if done[v]:
                continue
            if (u, v) in climbs:
                w = t.flick_ms
            elif nodes[u][0] == nodes[v][0]:
                w = t.hold_ms(abs(nodes[u][1] - nodes[v][1]))
            else:
                continue
            if dist[u] + w < dist[v]:
                dist[v], prev[v] = dist[u] + w, u
    if dist[1] == inf:
        raise ValueError(f"{m.name}: no way from {a} to {b}")
    path, c = [], 1
    while c != -1:
        path.append(c)
        c = prev[c]
    path.reverse()
    moves: list = []
    for u, v in zip(path, path[1:]):
        if (u, v) in climbs:
            moves.append(("climb", climbs[(u, v)]))
        elif moves and moves[-1][0] == "walk":
            moves[-1] = ("walk", nodes[v][1])  # one hold along a street, not two
        else:
            moves.append(("walk", nodes[v][1]))
    return dist[1], moves


# --- following moves (the same rules the app uses to know where it is) -------------------
def walk(m: MapData, t: WalkTable, pos: tuple[float, float], right: bool, ms: float) -> tuple[float, float]:
    """Where a hold from rest ends: the distance from the table, stopped by the street's end."""
    s = seg_of(m, pos)
    if s is None:
        raise ValueError(f"{m.name}: {pos} is on no street")
    x = pos[0] + (1 if right else -1) * t.distance(ms)
    return (min(max(x, s.x1), s.x2), s.y)


def flick(m: MapData, pos: tuple[float, float], up: bool) -> tuple[float, float]:
    """Take the stairs at pos going up/down; ValueError if there are none that close."""
    s = seg_of(m, pos)
    for ln in m.links:
        ends = _link_segs(m, ln)
        if not ends or abs(ln.x - pos[0]) > STAIRS_TOL:
            continue
        for here, there in (ends, ends[::-1]):
            if here == s and (there.y < here.y) == up:
                return (ln.x, there.y)
    raise ValueError(f"{m.name}: no stairs {'up' if up else 'down'} at x={pos[0]:.3f} y={pos[1]:.3f}")


def run_steps(m: MapData, t: WalkTable, pos: tuple[float, float], steps: list[str]) -> tuple[float, float]:
    for st in steps:
        kind, _, arg = st.partition(" ")
        if kind in ("R", "L"):
            pos = walk(m, t, pos, kind == "R", float(arg))
        elif kind in ("U", "D"):
            pos = flick(m, pos, kind == "U")
        else:
            raise ValueError(f"unknown step '{st}'")
    return pos


# --- planning ---------------------------------------------------------------------------
def _leg_steps(m: MapData, t: WalkTable, pos, target, moves) -> tuple[list[str], tuple[float, float]]:
    steps: list[str] = []
    for k, mv in enumerate(moves):
        if mv[0] == "climb":
            steps.append("U" if mv[1] else "D")
            pos = flick(m, pos, mv[1])
            continue
        x = mv[1]
        last = k == len(moves) - 1
        s = seg_of(m, pos)
        d = x - pos[0]
        if last and s is not None:
            # the stop is by the street's end: hold into the wall, which leaves the party exactly there
            end = s.x2 if d > 0 else s.x1
            if abs(end - x) <= t.wall_snap and abs(end - pos[0]) > t.min_walk:
                d = end - pos[0] + (t.wall_margin if d > 0 else -t.wall_margin)
        if abs(d) < t.min_walk:
            continue
        ms = int(round(t.hold_ms(abs(d)) / 10.0)) * 10
        steps.append(f"{'R' if d > 0 else 'L'} {ms}")
        pos = walk(m, t, pos, d > 0, ms)
    return steps, pos


def plan(m: MapData, t: WalkTable) -> list[list[str]]:
    """The fastest order through every chest to the exit, as legs [target, step, step...]."""
    stops = list(range(len(m.chests)))
    best = None
    for order in itertools.permutations(stops):
        at, cost = m.arrival, 0.0
        for i in order:
            c, _ = _route(m, t, at, m.chests[i])
            cost, at = cost + c, m.chests[i]
        if m.exit:
            cost += _route(m, t, at, m.exit)[0]
        if best is None or cost < best[0]:
            best = (cost, order)
    order = best[1] if best else ()
    legs, pos = [], m.arrival
    targets = [(f"chest {i + 1}", m.chests[i]) for i in order] + ([("exit", m.exit)] if m.exit else [])
    for name, goal in targets:
        _, moves = _route(m, t, pos, goal)
        steps, pos = _leg_steps(m, t, pos, goal, moves)
        legs.append([name, *steps])
    return legs


def path_time_s(m: MapData, t: WalkTable, legs: list[list[str]]) -> float:
    ms = 0.0
    for leg in legs:
        for st in leg[1:]:
            kind, _, arg = st.partition(" ")
            ms += float(arg) if kind in ("R", "L") else t.flick_ms
    return ms / 1000


# --- writing them into dungeons.yaml ------------------------------------------------------
_MAP_START = re.compile(r"^    - names:")


def _format_path(legs: list[list[str]], t: WalkTable, m: MapData) -> list[str]:
    lines = [f"      # generated by `another-macro dungeon-routes --write` (~{path_time_s(m, t, legs):.0f}s of walking)\n", "      path:\n"]
    lines += [f"        - [{', '.join(leg)}]\n" for leg in legs]
    return lines


def render(path: Path = DUNGEONS, table: WalkTable | None = None) -> str:
    """dungeons.yaml with every mapped map's `path:` (re)generated; the rest kept as written."""
    t = table or WalkTable.load()
    maps = load_maps(path)
    lines = path.read_text().splitlines(keepends=True)
    starts = [i for i, ln in enumerate(lines) if _MAP_START.match(ln)]
    assert len(starts) == len(maps), "every map entry must start with '    - names:'"
    out: list[str] = []
    bounds = starts + [len(lines)]
    out += lines[: starts[0]]
    for k, (did, idx, m, _) in enumerate(maps):
        block = lines[bounds[k] : bounds[k + 1]]
        # the block ends where the next map or dungeon starts: keep what trails it (blank lines, the next dungeon's header)
        end = len(block)
        while end > 1 and (not block[end - 1].startswith("      ") or block[end - 1].strip() == ""):
            end -= 1
        body, tail = block[:end], block[end:]
        kept, skipping = [], False
        for ln in body:
            if ln.startswith("      path:") or ln.startswith("      # generated by `another-macro dungeon-routes"):
                skipping = ln.startswith("      path:")
                continue
            if skipping and (ln.startswith("        ") or ln.startswith("      #")):
                continue
            skipping = False
            kept.append(ln)
        if m is not None:
            kept += _format_path(plan(m, t), t, m)
        out += kept + tail
    return "".join(out)


def main(write: bool = False, check: bool = False) -> int:
    t = WalkTable.load()
    new = render(DUNGEONS, t)
    for did, idx, m, _ in load_maps():
        if m is None:
            print(f"{did} map {idx + 1}: no layout (swept at run time)")
            continue
        legs = plan(m, t)
        print(f"{did} map {idx + 1} ({m.name}), ~{path_time_s(m, t, legs):.1f}s of walking:")
        for leg in legs:
            print(f"  {leg[0]}: {', '.join(leg[1:]) or '(already there)'}")
    current = DUNGEONS.read_text()
    if check and new != current:
        print("dungeons.yaml paths are out of date: run `another-macro dungeon-routes --write`")
        return 1
    if write and new != current:
        DUNGEONS.write_text(new)
        print(f"wrote {DUNGEONS}")
    return 0
