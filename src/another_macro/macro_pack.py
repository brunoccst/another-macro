"""The macro pack: what the Android app downloads instead of having macros built in.

CI (.github/workflows/macros.yml) runs this on every push that touches macros or templates
and publishes the zip as the pre-release ``macros-<branch>``; the app installs the newest pack
of the branch it follows (MacroLibrary.kt).

Layout of the zip (the same paths the app reads):

    game.yaml                         the game profile (the repo's game.yaml): the game's package,
                                      safety words, macro groups (logic/Game.kt)
    macros/<group>/<name>.yaml        the repo's macros/ folder as it is
    templates/common/...              src/another_macro/assets/templates
    templates/<folder>/...            src/another_macro/scenarios/<folder>/templates and its
                                      riddles.yaml / other data files next to them

``check`` finds what would break a macro on the phone: bad YAML, a duplicate name, a missing
template folder, a ``run:``, routine step or ``uses:`` naming a macro that isn't there, a macro
filed under the wrong group folder or a group game.yaml doesn't list, a game.yaml missing a key, an option name with a dot (reserved for the options a macro
takes over from the macros it runs), a macro with no steps for the app's "How it works" (``how:``).

    python -m another_macro.macro_pack -o macros.zip [--commit SHA --branch NAME]
"""
from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = Path(__file__).resolve().parent / "scenarios"
COMMON = Path(__file__).resolve().parent / "assets" / "templates"

# the game profile at the repo's top, packed as it is
GAME = "game.yaml"
GAME_KEYS = ("name", "package", "activity", "repo", "safety", "groups")

# data files a scenario keeps next to its templates folder (not .py code)
DATA_SUFFIXES = {".yaml", ".yml"}


def pack_files(root: Path = ROOT) -> dict[str, Path]:
    """Every file of the pack: its path inside the zip -> the file in the repo."""
    files: dict[str, Path] = {}
    if (root / GAME).is_file():
        files[GAME] = root / GAME
    for f in sorted((root / "macros").rglob("*.yaml")):
        files[f"macros/{f.relative_to(root / 'macros').as_posix()}"] = f
    for f in sorted(COMMON.rglob("*")):
        if f.is_file():
            files[f"templates/common/{f.relative_to(COMMON).as_posix()}"] = f
    for scen in sorted(p for p in SCENARIOS.iterdir() if p.is_dir()):
        tpl = scen / "templates"
        if tpl.is_dir():
            for f in sorted(tpl.rglob("*")):
                if f.is_file():
                    files[f"templates/{scen.name}/{f.relative_to(tpl).as_posix()}"] = f
        for f in sorted(scen.iterdir()):
            if f.is_file() and f.suffix in DATA_SUFFIXES:
                files[f"templates/{scen.name}/{f.name}"] = f
    return files


def macros(root: Path = ROOT) -> dict[str, tuple[Path, dict]]:
    """Every macro by name: its file and its parsed YAML."""
    out: dict[str, tuple[Path, dict]] = {}
    for f in sorted((root / "macros").rglob("*.yaml")):
        spec = yaml.safe_load(f.read_text(encoding="utf-8"))
        if not isinstance(spec, dict) or "name" not in spec:
            raise ValueError(f"{f}: not a macro (no name)")
        if spec["name"] in out:
            raise ValueError(f"{f}: macro name {spec['name']!r} used twice")
        out[spec["name"]] = (f, spec)
    return out


def _runs(actions) -> list[str]:
    """Macro names a list of actions runs with `run:` (also inside if / repeat)."""
    names: list[str] = []
    for a in actions or []:
        if not isinstance(a, dict):
            continue
        if "run" in a:
            r = a["run"]
            names.append(str(r.get("macro") if isinstance(r, dict) else r))
        names += _runs(a.get("then")) + _runs(a.get("else"))
        if isinstance(a.get("repeat"), dict):
            names += _runs(a["repeat"].get("do"))
    return names


def _sets(actions) -> list[tuple[str, object]]:
    """(variable, expression) of every `set:` in a list of actions (also inside if / repeat)."""
    out: list[tuple[str, object]] = []
    for a in actions or []:
        if not isinstance(a, dict):
            continue
        if isinstance(a.get("set"), dict):
            out += list(a["set"].items())
        out += _sets(a.get("then")) + _sets(a.get("else"))
        if isinstance(a.get("repeat"), dict):
            out += _sets(a["repeat"].get("do"))
    return out


# steps the app's code runs and describes itself in "How it works" (logic/HowItWorks.kt)
EXPLAINED_STEPS = {"another_dungeon", "phantom_crystal", "cat_daily", "fight", "pick_party", "find_feather", "read_cat_clues"}


def _logic_steps(actions) -> set[str]:
    """The EXPLAINED_STEPS a list of actions uses (also inside if / repeat)."""
    out: set[str] = set()
    for a in actions or []:
        if not isinstance(a, dict):
            continue
        out |= EXPLAINED_STEPS & set(a)
        out |= _logic_steps(a.get("then")) | _logic_steps(a.get("else"))
        if isinstance(a.get("repeat"), dict):
            out |= _logic_steps(a["repeat"].get("do"))
    return out


def _check_how(rel, spec: dict, all_macros: dict) -> list[str]:
    """
    Every macro tells the app's "How it works" its steps: a routine's steps, a `how:` list, or a
    step the app's code describes itself (e.g. another_dungeon). In `how:`, a run: names a macro
    of the library and {name} an option of the macro.
    """
    if spec.get("steps"):
        return []
    how = spec.get("how")
    if how is None:
        used = _logic_steps(spec.get("setup")) | _logic_steps(spec.get("unknown"))
        for acts in (spec.get("handlers") or {}).values():
            used |= _logic_steps(acts)
        return [] if used else [f"{rel}: no how: list (the steps the app shows under How it works)"]
    problems: list[str] = []
    params = set(spec.get("params") or {})

    def walk(items):
        for e in items if isinstance(items, list) else [items]:
            if isinstance(e, str):
                for name in re.findall(r"\{([a-z0-9_]+)\}", e):
                    if name not in params:
                        problems.append(f"{rel}: how: {{{name}}} is not an option")
            elif isinstance(e, dict):
                if "run" in e:
                    target = e["run"]["macro"] if isinstance(e["run"], dict) else str(e["run"])
                    if target not in all_macros:
                        problems.append(f"{rel}: how: runs unknown macro {target!r}")
                for k in ("then", "else", "sub"):
                    if k in e:
                        walk(e[k])
                if "text" in e:
                    walk(e["text"])
    walk(how)
    return problems


def game(root: Path = ROOT) -> dict:
    """The game profile (game.yaml), parsed; empty when there is none."""
    f = root / GAME
    return (yaml.safe_load(f.read_text(encoding="utf-8")) or {}) if f.is_file() else {}


def _check_game(profile: dict) -> list[str]:
    """game.yaml has every key, safety words and groups."""
    if not profile:
        return [f"{GAME}: missing (the game profile)"]
    problems = [f"{GAME}: no {k}" for k in GAME_KEYS if not profile.get(k)]
    safety = profile.get("safety") or {}
    for k in ("purchase_words", "premium_words"):
        if not isinstance(safety.get(k), list) or not safety.get(k):
            problems.append(f"{GAME}: safety.{k} must be a list of words")
    for g in profile.get("groups") or []:
        if not isinstance(g, dict) or not g.get("id") or not g.get("title"):
            problems.append(f"{GAME}: group {g!r} needs an id and a title")
    return problems


def check(root: Path = ROOT) -> list[str]:
    """Problems that would break a macro on the phone (empty = fine)."""
    profile = game(root)
    problems: list[str] = _check_game(profile)
    groups = {g.get("id") for g in profile.get("groups") or [] if isinstance(g, dict)}
    all_macros = macros(root)
    folders = {p.split("/")[1] for p in pack_files(root) if p.startswith("templates/")}
    for name, (f, spec) in all_macros.items():
        rel = f.relative_to(root / "macros")
        group = spec.get("group", "other")
        if len(rel.parts) != 2 or rel.parts[0] != group:
            problems.append(f"{rel}: group {group!r} belongs in macros/{group}/")
        if groups and group not in groups:
            problems.append(f"{rel}: group {group!r} is not one of game.yaml's groups")
        for t in spec.get("templates") or []:
            if t not in folders:
                problems.append(f"{rel}: template folder {t!r} not in the pack")
        used = _runs(spec.get("setup")) + _runs(spec.get("unknown"))
        for acts in (spec.get("handlers") or {}).values():
            used += _runs(acts)
        used += [str(s) for s in spec.get("steps") or []]
        used += [str(s) for s in spec.get("uses") or []]
        # a set: value is an expression: an empty one crashes the macro on the phone ('' is the empty text)
        for acts in [spec.get("setup"), spec.get("unknown"), *(spec.get("handlers") or {}).values()]:
            for k, v in _sets(acts):
                if v is None or (isinstance(v, str) and not v.strip()):
                    problems.append(f"{rel}: set {k}: an empty expression (write '' for empty text)")
        # "<macro>.<option>" keys are the options a macro takes over from the macros it runs
        # (the app's InnerParams): a macro's own option names never have a dot
        for k in spec.get("params") or {}:
            if "." in str(k):
                problems.append(f"{rel}: option {k!r}: a dot is for the options taken over from inner macros")
        # show_if / beside must name another option; beside goes on a switch (type: bool)
        params = spec.get("params") or {}
        for k, p in params.items():
            if not isinstance(p, dict):
                continue
            for ref in ("show_if", "beside"):
                if ref in p and p[ref] not in params:
                    problems.append(f"{rel}: option {k!r} {ref} unknown option {p[ref]!r}")
            if "beside" in p and p.get("type") != "bool" and not isinstance(p.get("default"), bool):
                problems.append(f"{rel}: option {k!r} has beside but is not a switch")
        for u in used:
            if u not in all_macros:
                problems.append(f"{rel}: runs unknown macro {u!r}")
            elif u == name:
                problems.append(f"{rel}: runs itself")
        problems += _check_how(rel, spec, all_macros)
    return problems


def build(out: Path, commit: str = "", branch: str = "", root: Path = ROOT) -> int:
    """Write the pack to [out]; the number of files in it."""
    problems = check(root)
    if problems:
        raise SystemExit("macro pack problems:\n  " + "\n  ".join(problems))
    files = pack_files(root)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for arc, f in files.items():
            z.write(f, arc)
        z.writestr("pack.json", json.dumps({"commit": commit, "branch": branch, "macros": sorted(macros(root))}, indent=1))
    return len(files)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description="Build the macro pack the Android app downloads.")
    ap.add_argument("-o", "--out", type=Path, default=Path("macros.zip"))
    ap.add_argument("--commit", default="")
    ap.add_argument("--branch", default="")
    ap.add_argument("--check", action="store_true", help="only check the macros, write nothing")
    a = ap.parse_args(argv)
    if a.check:
        problems = check()
        print("\n".join(problems) or "ok")
        raise SystemExit(1 if problems else 0)
    n = build(a.out, a.commit, a.branch)
    print(f"{a.out}: {n} files, {len(macros())} macros")


if __name__ == "__main__":
    main()
