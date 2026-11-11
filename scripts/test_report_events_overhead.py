"""Tests for the analytics overhead comparison.

Run with:  python3 -m pytest scripts/test_report_events_overhead.py

These cover the parts where a mistake would put a wrong number, or a meaningless comparison, into
the documentation: refusing two sets that were not run the same way, keeping the sign of a
difference, and saying "not measured" instead of inventing a figure.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from report_events_overhead import (
    NOT_MEASURED,
    ResultSet,
    build_report,
    compare_shapes,
    main,
    signed,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


SHAPE = {
    "algorithm": "sliding-window",
    "tierMix": "10 x USER@10/min",
    "userCount": 16,
    "rpsStart": 50,
    "rpsStep": 50,
    "rpsSteps": 4,
    "stepSeconds": 30,
    "warmupSeconds": 10,
    "runs": 2,
}


def write_set(
    root: Path,
    name: str,
    *,
    events: bool,
    means: list,
    p99s: list = None,
    requests: int = 1000,
    ko: int = 0,
    unexpected: int = 0,
    shape: dict = None,
    omit_stats: bool = False,
) -> Path:
    directory = root / name
    directory.mkdir(parents=True)
    parameters = dict(shape if shape is not None else SHAPE)
    parameters["eventsEnabled"] = events
    parameters["analyticsEnabled"] = events
    (directory / "environment.json").write_text(json.dumps({
        "label": f"feed {'on' if events else 'off'}",
        "repository": {"commit": "a" * 40, "uncommittedChanges": False},
        "parameters": parameters,
    }))

    for index, mean in enumerate(means, start=1):
        run_dir = directory / f"run-{index}"
        (run_dir / "gatling" / "js").mkdir(parents=True)
        (run_dir / "decisions.json").write_text(json.dumps({"unexpected": unexpected}))
        if omit_stats:
            continue
        p99 = (p99s or means)[index - 1]
        (run_dir / "gatling" / "js" / "global_stats.json").write_text(json.dumps({
            "numberOfRequests": {"total": requests, "ok": requests - ko, "ko": ko},
            "meanResponseTime": {"total": mean},
            "maxResponseTime": {"total": mean * 10},
            "percentiles1": {"total": mean},
            "percentiles2": {"total": mean},
            "percentiles3": {"total": mean * 2},
            "percentiles4": {"total": p99},
        }))
    return directory


# ---------------------------------------------------------------------------
# Reading the data
# ---------------------------------------------------------------------------


def test_runs_are_read_in_order(tmp_path):
    directory = write_set(tmp_path, "off", events=False, means=[1, 2])
    results = ResultSet(directory)

    assert [run["name"] for run in results.runs] == ["run-1", "run-2"]
    assert results.metric("meanResponseTime") == [1, 2]


def test_the_feed_state_comes_from_the_recorded_parameters(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[1]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[1]))

    assert off.feed_state("eventsEnabled") is False
    assert on.feed_state("eventsEnabled") is True


def test_a_run_without_statistics_is_not_counted(tmp_path):
    directory = write_set(tmp_path, "off", events=False, means=[1, 2], omit_stats=True)

    assert ResultSet(directory).runs == []


def test_a_missing_environment_file_does_not_invent_a_label(tmp_path):
    directory = tmp_path / "empty"
    directory.mkdir()

    assert ResultSet(directory).label == NOT_MEASURED


# ---------------------------------------------------------------------------
# Refusing a comparison that would not mean anything
# ---------------------------------------------------------------------------


def test_matching_shapes_compare_clean(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[1]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[1]))

    assert compare_shapes(off, on) == []


def test_the_feed_state_itself_is_not_treated_as_a_shape_difference(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[1]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[1]))

    # The whole point is that this differs; it must not disqualify the comparison.
    assert "eventsEnabled" not in compare_shapes(off, on)


def test_a_different_load_shape_is_reported_instead_of_a_difference(tmp_path):
    faster = dict(SHAPE, rpsStart=200)
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[10]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[90], shape=faster))

    report = build_report(off, on)

    assert "not comparable" in report
    assert "`rpsStart`" in report
    # No difference may be stated from two different shapes.
    assert "Difference" not in report
    assert "+80" not in report


# ---------------------------------------------------------------------------
# The arithmetic
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("value,expected", [
    (2.0, "+2.0"),
    (-2.0, "-2.0"),
    (0.0, "0"),
    (0.01, "0"),
    (None, NOT_MEASURED),
])
def test_a_difference_keeps_its_sign(value, expected):
    assert signed(value) == expected


def test_the_difference_is_on_minus_off(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[10, 10]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[13, 13]))

    report = build_report(off, on)

    assert "| mean | 10.0 | 13.0 | +3.0 |" in report


def test_a_feed_that_made_things_faster_is_reported_as_faster(tmp_path):
    # The report must not assume the feed can only cost: on a contended machine the difference
    # can land either way, and reporting only the expected direction would be dishonest.
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[12, 12]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[10, 10]))

    assert "| mean | 12.0 | 10.0 | -2.0 |" in build_report(off, on)


def test_the_spread_across_runs_is_reported(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[8, 14]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[9, 15]))

    report = build_report(off, on)

    assert "| mean spread across runs | 8-14 | 9-15 |" in report


def test_requests_and_failures_are_totalled(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[1, 1], requests=500))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[1, 1], requests=500, ko=3))

    report = build_report(off, on)

    assert "| Requests, total across runs | 1,000 | 1,000 |" in report
    assert "| Gatling failures (ko) | 0 | 6 |" in report


# ---------------------------------------------------------------------------
# The caveats are part of the report
# ---------------------------------------------------------------------------


def test_the_report_states_what_it_cannot_support(tmp_path):
    off = ResultSet(write_set(tmp_path, "off", events=False, means=[1]))
    on = ResultSet(write_set(tmp_path, "on", events=True, means=[1]))

    report = build_report(off, on)

    assert "shared a machine" in report
    assert "smaller than the spread" in report
    assert "no capacity figure" in report


def test_the_report_names_the_data_it_came_from(tmp_path):
    off_dir = write_set(tmp_path, "off", events=False, means=[1])
    on_dir = write_set(tmp_path, "on", events=True, means=[1])

    report = build_report(ResultSet(off_dir), ResultSet(on_dir))

    assert str(off_dir) in report
    assert str(on_dir) in report


# ---------------------------------------------------------------------------
# The command line
# ---------------------------------------------------------------------------


def test_main_writes_the_output_file(tmp_path, capsys):
    off_dir = write_set(tmp_path, "off", events=False, means=[1])
    on_dir = write_set(tmp_path, "on", events=True, means=[2])
    output = tmp_path / "overhead.md"

    code = main_with(off_dir, on_dir, output)

    assert code == 0
    assert output.is_file()
    assert "Analytics feed: measured overhead" in output.read_text()


def test_main_refuses_a_missing_directory(tmp_path):
    off_dir = write_set(tmp_path, "off", events=False, means=[1])

    assert main_with(off_dir, tmp_path / "nope", tmp_path / "out.md") == 1


def test_main_refuses_a_set_with_no_runs(tmp_path):
    off_dir = write_set(tmp_path, "off", events=False, means=[1])
    empty = tmp_path / "empty"
    empty.mkdir()

    assert main_with(off_dir, empty, tmp_path / "out.md") == 1


def main_with(off_dir: Path, on_dir: Path, output: Path) -> int:
    import sys

    argv = sys.argv
    sys.argv = ["report_events_overhead.py", "--off", str(off_dir), "--on", str(on_dir),
                "--output", str(output)]
    try:
        return main()
    finally:
        sys.argv = argv
