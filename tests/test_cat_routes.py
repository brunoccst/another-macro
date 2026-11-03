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
    "world_map": {"pans": [[-0.4, 0.0]], "tap": [0.5, 0.5]},
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
    assert cr.plan("x", {**SPOT, "walk_to": "Next Map"}, table) is None
    assert "area.cat" in cr.missing({"area": {"segs": [[0.5, 0.1, 0.9]]}})


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
