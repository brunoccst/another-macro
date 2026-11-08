"""The Another Dungeons' fixed walking routes (dungeon_routes.py, dungeons.yaml `path:`)."""
import pytest

from another_macro import dungeon_routes as dr


@pytest.fixture(scope="module")
def table():
    return dr.WalkTable.load()


def mapped():
    return [(did, idx, m, raw) for did, idx, m, raw in dr.load_maps() if m is not None]


def test_walk_table_round_trips(table):
    for ms in (100, 250, 600, 1000, 2200, 4000, 6500):
        assert table.hold_ms(table.distance(ms)) == pytest.approx(ms, abs=1e-6)
    # measured points
    assert table.distance(1000) == pytest.approx(0.0244)
    assert table.distance(5000) == pytest.approx(0.0970 + 0.026)


@pytest.mark.parametrize("did,idx,m,raw", mapped(), ids=lambda v: str(v) if isinstance(v, (str, int)) else "")
def test_path_takes_every_chest_then_the_exit(did, idx, m, raw, table):
    legs = raw["path"]
    targets = [leg[0] for leg in legs]
    assert sorted(targets[:-1]) == sorted(f"chest {i + 1}" for i in range(len(m.chests)))
    assert targets[-1] == "exit"
    pos = m.arrival
    for leg in legs:
        pos = dr.run_steps(m, table, pos, leg[1:])  # raises if a flick isn't at stairs
        goal = m.exit if leg[0] == "exit" else m.chests[int(leg[0].split()[1]) - 1]
        assert dr.seg_of(m, pos) == dr.seg_of(m, goal), f"{leg[0]}: ended on the wrong street at {pos}"
        assert abs(pos[0] - goal[0]) <= table.wall_snap, f"{leg[0]}: ended at x={pos[0]:.3f}, goal {goal[0]:.3f}"


def test_paths_in_dungeons_yaml_are_up_to_date(table):
    assert dr.render(dr.DUNGEONS, table) == dr.DUNGEONS.read_text(), "run `another-macro dungeon-routes --write`"


def test_render_is_stable(tmp_path, table):
    p = tmp_path / "d.yaml"
    p.write_text(dr.DUNGEONS.read_text())
    once = dr.render(p, table)
    p.write_text(once)
    assert dr.render(p, table) == once


def test_stairs_need_the_party_on_them(table):
    m = mapped()[0][2]
    with pytest.raises(ValueError):
        dr.flick(m, m.arrival, up=True)  # map 1's arrival isn't at stairs


def test_a_walk_stops_at_the_wall(table):
    m = mapped()[0][2]
    end = dr.walk(m, table, m.arrival, right=False, ms=20000)
    assert end[0] == pytest.approx(0.374)  # map 1's bottom street starts at x 0.374 (measured on the phone)
