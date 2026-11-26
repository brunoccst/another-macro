"""Fixed routes to the Cat Scratchbook cats (cat_routes.py, cat_spots.yaml)."""
import pytest
import yaml

from another_macro import cat_routes as cr
from another_macro import dungeon_routes as dr

# a made-up two-street town (the shape of a measured spot; not a real map)
SPOT = {
    "era": "Present",
    "continent": "Somewhere Continent",
    "place": "Testville",
    "sub": None,
    "area": {
        "segs": [[0.4, 0.3, 0.7], [0.6, 0.3, 0.7]],
        "links": [[0.5, 0.6, 0.4]],
        "arrival": [0.35, 0.6],
        "cat": [0.65, 0.4],
        "drift": [0.02, 0.1],
        "walk_scale": 1.0,
    },
}


@pytest.fixture(scope="module")
def table():
    return dr.WalkTable.load()


def test_every_riddle_location_has_a_spot():
    riddles = yaml.safe_load(cr.RIDDLES.read_text(encoding="utf-8"))
    spots = cr.load_spots()
    assert {r["location"] for r in riddles.values()} <= set(spots)
    for name, s in spots.items():
        assert s["era"] and s["place"], name


def test_route_reaches_the_cat(table):
    steps = cr.plan("Testville", SPOT, table)
    assert steps and "U" in steps
    end = cr.follow("Testville", SPOT, table, steps)
    assert end[1] == pytest.approx(0.4)
    assert abs(end[0] - 0.65) <= table.min_walk + 1e-9


def test_walk_scale_changes_hold_times_not_the_end(table):
    big = {**SPOT, "area": {**SPOT["area"], "walk_scale": 2.0}}
    a, b = cr.plan("T", SPOT, table), cr.plan("T", big, table)
    holds = lambda s: [int(x.split()[1]) for x in s if x[0] in "RL"]  # noqa: E731
    assert sum(holds(b)) < sum(holds(a))  # a map drawn twice as large: the same street takes less holding
    end = cr.follow("T", big, table, b)
    assert abs(end[0] - 0.65) <= table.min_walk + 1e-9


def test_drift_stays_on_the_street():
    lo, hi = cr.drift_range("Testville", SPOT)
    assert lo == pytest.approx(0.63) and hi == pytest.approx(0.7)  # the street ends at 0.7


def test_unmeasured_spots_have_no_route(table):
    assert cr.plan("x", {"era": "Present", "place": "x", "area": None}, table) is None
    assert cr.plan("x", {**SPOT, "walk_to": "Next Map", "area": {**SPOT["area"], "cat": None}}, table) is None
    assert "area.cat" in cr.missing({"area": {"segs": [[0.5, 0.1, 0.9]]}})


def test_walk_to_spot_routes_on_the_map_walked_into(table):
    # the area is measured on the walk_to map; the arrival is where walking across leaves the party
    assert cr.plan("x", {**SPOT, "walk_to": "Next Map"}, table) == cr.plan("x", SPOT, table)


def test_split_location():
    assert cr.split_location("Moonlight Forest (Forest Edge)") == ("Moonlight Forest", "Forest Edge")
    assert cr.split_location("Nadara Volcano - Caldera Edge") == ("Nadara Volcano", "Caldera Edge")
    assert cr.split_location("Route 99 (Enter from Industrial Ruins)") == ("Route 99", None)
    assert cr.split_location("Unigan") == ("Unigan", None)


def test_paths_in_cat_spots_yaml_are_up_to_date(table):
    assert cr.render(cr.SPOTS, table) == cr.SPOTS.read_text(encoding="utf-8"), "run `another-macro cat-routes --write`"


def test_render_writes_and_replaces_a_path(tmp_path, table):
    p = tmp_path / "spots.yaml"
    p.write_text("# head\n" + yaml.safe_dump({"Testville": SPOT}, sort_keys=False) + "Other:\n  era: Future\n  place: Other\n  area: null\n")
    once = cr.render(p, table)
    assert once.count("  path: [") == 1
    p.write_text(once)
    assert cr.render(p, table) == once
    assert yaml.safe_load(once)["Testville"]["path"] == cr.plan("Testville", SPOT, table)


def test_measured_spots_reach_their_cat(table):
    spots = cr.load_spots()
    measured = {n: s for n, s in spots.items() if cr.area_map(n, s) is not None}
    assert {"Ratle", "Acteul"} <= set(measured)
    for name, s in measured.items():
        steps = s["path"]
        end = cr.follow(name, s, table, steps)
        cat = s["area"]["cat"]
        assert end[1] == pytest.approx(cat[1]), name
        # a cat by a street's end is reached by holding into the wall (within wall_snap of it)
        assert abs(end[0] - cat[0]) <= table.wall_snap, f"{name}: path ends at x={end[0]:.3f}, cat {cat[0]:.3f}"


# --- importing the app's survey ("MEASURED cat spot:" log lines, CatSurvey.kt) ---------------
LOG = (
    "12:00:01 cat-scratchbook: Testplace: no fixed route yet\n"
    '12:00:40 MEASURED cat spot: {"location":"Testplace","map":"Testplace","segs":[[0.4,0.3,0.7],[0.6,0.3,0.7]],'
    '"links":[[0.5,0.4,0.6]],"arrival":[0.35,0.6],"cat":[0.65,0.4],"drift":[0.02,0.03],'
    '"feather_reads":[0.65,0.64,0.68],"walk_scale":0.9,"notes":["a note"],"date":"2026-11-17"}\n'
)


def _spots_file(tmp_path, area="null"):
    p = tmp_path / "cat_spots.yaml"
    p.write_text(
        "# header\n"
        f"Testplace:\n  era: Present\n  continent: null\n  place: Testplace\n  sub: null\n  area: {area}\n"
        "Other:\n  era: Future\n  continent: null\n  place: Other\n  sub: null\n  area: null\n",
        encoding="utf-8",
    )
    return p


def test_import_measured_spot(tmp_path, table):
    p = _spots_file(tmp_path)
    text, imported, skipped = cr.import_measured(LOG + LOG.replace("Testplace", "Nowhere"), p)
    assert imported == ["Testplace"] and skipped == ["Nowhere (no such spot in cat_spots.yaml)"]
    p.write_text(text, encoding="utf-8")
    spots = cr.load_spots(p)
    a = spots["Testplace"]["area"]
    assert a["walk_scale"] == 0.9 and a["cat"] == [0.65, 0.4] and a["links"] == [[0.5, 0.4, 0.6]]
    assert spots["Other"]["area"] is None and "# survey note: a note" in text
    steps = cr.plan("Testplace", spots["Testplace"], table)
    assert steps and "U" in steps
    # and the paths get written like any measured spot's
    out = cr.render(p, table)
    assert "  path: [" in out and cr.load_spots(p)["Testplace"]["era"] == "Present"


def test_import_keeps_hand_measured_areas(tmp_path):
    p = _spots_file(tmp_path, area="\n    segs: [[0.5, 0.1, 0.9]]\n    arrival: [0.2, 0.5]\n    cat: [0.8, 0.5]")
    _, imported, skipped = cr.import_measured(LOG, p)
    assert imported == [] and skipped[0].startswith("Testplace (already has an area")
    text, imported, _ = cr.import_measured(LOG, p, force=True)
    assert imported == ["Testplace"] and "0.1, 0.9" not in text


def test_a_layout_that_does_not_connect_has_no_route(table):
    bad = {**SPOT, "area": {**SPOT["area"], "links": []}}
    assert cr.safe_plan("T", bad, table) is None


def test_import_bridges_the_icon_gaps(tmp_path, table):
    # the party marker and the feather sit on their streets and split the survey's read
    log = LOG.replace('"segs":[[0.4,0.3,0.7],[0.6,0.3,0.7]]', '"segs":[[0.4,0.3,0.62],[0.4,0.68,0.7],[0.6,0.38,0.7]]')
    p = _spots_file(tmp_path)
    text, imported, _ = cr.import_measured(log, p)
    p.write_text(text, encoding="utf-8")
    a = cr.load_spots(p)["Testplace"]["area"]
    assert [0.4, 0.3, 0.7] in a["segs"]          # joined across the feather at x 0.65
    assert [0.6, 0.34, 0.7] in a["segs"]         # stretched to the arrival at x 0.35
    assert "import: cat: joined" in text
    assert cr.plan("Testplace", cr.load_spots(p)["Testplace"], table)


def test_approach_steps_are_holds_and_flicks():
    # the app's Route.parseLeg reads these: "R 2000" / "L 1000" (ms) and "U" / "D"
    for name, s in cr.load_spots().items():
        for step in s.get("approach") or []:
            assert step in ("U", "D") or (step.split()[0] in ("R", "L") and step.split()[1].isdigit()), (name, step)


def test_approach_leaves_the_area_and_path_alone(table):
    assert cr.plan("x", {**SPOT, "approach": ["R 2000", "D"]}, table) == cr.plan("x", SPOT, table)


def test_valley_of_the_ancients_travels_to_pador_then_approaches():
    s = cr.load_spots()["Valley of the Ancients"]
    assert (s["era"], s["place"]) == ("Antiquity", "Pador")
    assert s["approach"] and s["area"] is None
