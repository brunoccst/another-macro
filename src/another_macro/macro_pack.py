"""The macro pack: what the Android app downloads instead of having macros built in.

CI (.github/workflows/macros.yml) runs this on every push that touches macros or templates
and publishes the zip as the pre-release ``macros-<branch>``; the app installs the newest pack
of the branch it follows (MacroLibrary.kt).

Layout of the zip (the same paths the app reads):

    macros/<group>/<name>.yaml        the repo's macros/ folder as it is
    templates/common/...              src/another_macro/assets/templates
    templates/<folder>/...            src/another_macro/scenarios/<folder>/templates and its
                                      riddles.yaml / other data files next to them

``check`` finds what would break a macro on the phone: bad YAML, a duplicate name, a missing
template folder, a ``run:`` or routine step naming a macro that isn't there, a macro filed under
the wrong group folder.

    python -m another_macro.macro_pack -o macros.zip [--commit SHA --branch NAME]
"""
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = Path(__file__).resolve().parent / "scenarios"
COMMON = Path(__file__).resolve().parent / "assets" / "templates"

# data files a scenario keeps next to its templates folder (not .py code)
DATA_SUFFIXES = {".yaml", ".yml"}


def pack_files(root: Path = ROOT) -> dict[str, Path]:
    """Every file of the pack: its path inside the zip -> the file in the repo."""
    files: dict[str, Path] = {}
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


def check(root: Path = ROOT) -> list[str]:
    """Problems that would break a macro on the phone (empty = fine)."""
    problems: list[str] = []
    all_macros = macros(root)
    folders = {p.split("/")[1] for p in pack_files(root) if p.startswith("templates/")}
    for name, (f, spec) in all_macros.items():
        rel = f.relative_to(root / "macros")
        group = spec.get("group", "other")
        if len(rel.parts) != 2 or rel.parts[0] != group:
            problems.append(f"{rel}: group {group!r} belongs in macros/{group}/")
        for t in spec.get("templates") or []:
            if t not in folders:
                problems.append(f"{rel}: template folder {t!r} not in the pack")
        used = _runs(spec.get("setup")) + _runs(spec.get("unknown"))
        for acts in (spec.get("handlers") or {}).values():
            used += _runs(acts)
        used += [str(s) for s in spec.get("steps") or []]
        for u in used:
            if u not in all_macros:
                problems.append(f"{rel}: runs unknown macro {u!r}")
            elif u == name:
                problems.append(f"{rel}: runs itself")
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
