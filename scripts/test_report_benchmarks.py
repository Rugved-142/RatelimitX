"""Tests for the benchmark report generator.

Run with:  python3 -m pytest scripts/test_report_benchmarks.py

These cover the parts where a mistake would put a wrong number in the documentation: reading the
saved files, deciding which runs count, and the arithmetic behind decisions/s and allowed/s.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from report_benchmarks import (
    NOT_MEASURED,
    RunResult,
    fmt_bytes_as_gib,
    fmt_number,
    load_results,
    load_run,
    main,
    parse_gatling_stats,
    render,
    summarise,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def write_run(
    root: Path,
    name: str,
    *,
    exit_code: int = 0,
    allowed: int = 600,
    denied: int = 2400,
    unexpected: int = 0,
    total: int | None = None,
    first_ms: int = 1_700_000_000_000,
    last_ms: int = 1_700_000_060_000,
    include_stats: bool = True,
) -> Path:
    run_dir = root / name
    (run_dir / "gatling" / "js").mkdir(parents=True)

    (run_dir / "exit-code.txt").write_text(f"{exit_code}\n")
    (run_dir / "decisions.json").write_text(json.dumps({
        "allowed": allowed,
        "denied": denied,
        "unexpected": unexpected,
        "total": allowed + denied + unexpected,
    }))

    if include_stats:
        request_total = total if total is not None else allowed + denied + unexpected
        (run_dir / "gatling" / "js" / "stats.json").write_text(json.dumps({
            "stats": {
                "numberOfRequests": {"total": request_total, "ok": request_total, "ko": 0},
                "meanNumberOfRequestsPerSecond": {"total": 50.0},
                "percentiles1": {"total": 4.0},
                "percentiles2": {"total": 6.0},
                "percentiles3": {"total": 11.0},
                "percentiles4": {"total": 30.0},
                "minResponseTime": {"total": 1.0},
                "maxResponseTime": {"total": 72.0},
                "meanResponseTime": {"total": 5.0},
            }
        }))

    # A minimal simulation.log: the generator only needs the first and last 13-digit timestamp.
    (run_dir / "gatling" / "simulation.log").write_text(
        f"REQUEST\tscenario\tGET /api/data\t{first_ms}\t{first_ms + 5}\tOK\n"
        f"REQUEST\tscenario\tGET /api/data\t{last_ms}\t{last_ms + 5}\tOK\n"
    )
    return run_dir


def write_environment(root: Path, **overrides) -> None:
    environment = {
        "capturedAt": "2026-11-10T00:00:00Z",
        "host": {
            "os": "Test OS 1.0",
            "kernel": "Test 1.0",
            "arch": "arm64",
            "cpuModel": "Test CPU",
            "cpuCount": "10",
            "memoryBytes": 34359738368,
        },
        "loadGenerator": {"sharesHostWithService": True, "jvm": "Java version: 21"},
        "service": {
            "javaVersion": "openjdk 21",
            "entrypoint": ["java", "-jar", "app.jar"],
            "containerMemoryLimitBytes": 0,
            "containerNanoCpus": 0,
        },
        "docker": {"engine": "29.0.0", "compose": "v2.0.0"},
        "repository": {"commit": "abc123", "uncommittedChanges": False},
        "parameters": {
            "algorithm": "sliding-window",
            "tierMix": {"USER": 10, "PREMIUM": 5, "ADMIN": 1},
            "userCount": 16,
            "rpsStart": 50,
            "rpsStep": 50,
            "rpsSteps": 4,
            "stepSeconds": 30,
            "warmupSeconds": 10,
            "runs": 3,
        },
    }
    environment.update(overrides)
    (root / "environment.json").write_text(json.dumps(environment))


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def test_reads_the_decision_tally_and_gatling_stats(tmp_path: Path):
    run_dir = write_run(tmp_path, "run-1", allowed=600, denied=2400)

    run = load_run(run_dir)

    assert run.allowed == 600
    assert run.denied == 2400
    assert run.unexpected == 0
    assert run.total_requests == 3000
    assert run.ko_requests == 0
    assert run.percentiles["p95"] == 11.0
    assert run.duration_seconds == pytest.approx(60.0)


def test_finds_stats_json_at_either_depth(tmp_path: Path):
    nested = tmp_path / "run-1" / "gatling" / "deeper" / "js"
    nested.mkdir(parents=True)
    (nested / "stats.json").write_text(json.dumps(
        {"stats": {"numberOfRequests": {"total": 7, "ok": 7, "ko": 0}}}))
    (tmp_path / "run-1" / "exit-code.txt").write_text("0")

    run = load_run(tmp_path / "run-1")

    assert run.total_requests == 7


def test_parses_a_stats_file_without_a_wrapper_key(tmp_path: Path):
    path = tmp_path / "stats.json"
    path.write_text(json.dumps({"numberOfRequests": {"total": 5, "ok": 4, "ko": 1}}))

    parsed = parse_gatling_stats(path)

    assert parsed["total_requests"] == 5
    assert parsed["ko_requests"] == 1


def test_falls_back_to_the_tally_when_gatling_stats_are_missing(tmp_path: Path):
    run_dir = write_run(tmp_path, "run-1", allowed=10, denied=20, include_stats=False)

    run = load_run(run_dir)

    assert run.total_requests == 30, "a run must not be dropped just because stats.json is absent"


def test_a_run_with_no_files_at_all_is_not_counted(tmp_path: Path):
    (tmp_path / "run-1").mkdir()

    run = load_run(tmp_path / "run-1")

    assert run.exit_code is None
    assert not run.succeeded


# ---------------------------------------------------------------------------
# Which runs count
# ---------------------------------------------------------------------------


def test_a_nonzero_exit_code_disqualifies_a_run(tmp_path: Path):
    run = load_run(write_run(tmp_path, "run-1", exit_code=1))

    assert not run.succeeded


def test_an_unexpected_status_disqualifies_a_run(tmp_path: Path):
    run = load_run(write_run(tmp_path, "run-1", unexpected=3))

    assert not run.succeeded, "a 500 means the service stopped deciding; the run is not usable"


def test_a_clean_run_counts(tmp_path: Path):
    assert load_run(write_run(tmp_path, "run-1")).succeeded


# ---------------------------------------------------------------------------
# Arithmetic
# ---------------------------------------------------------------------------


def test_decisions_per_second_counts_allowed_and_denied_together():
    run = RunResult(name="run-1", exit_code=0, allowed=600, denied=2400, unexpected=0,
                    total_requests=3000, duration_seconds=60.0)

    assert run.decisions_per_second == pytest.approx(50.0)


def test_allowed_per_second_counts_only_admissions():
    run = RunResult(name="run-1", exit_code=0, allowed=600, denied=2400, unexpected=0,
                    total_requests=3000, duration_seconds=60.0)

    assert run.allowed_per_second == pytest.approx(10.0)
    assert run.allowed_per_second != run.decisions_per_second


def test_rates_are_not_measured_without_a_duration():
    run = RunResult(name="run-1", exit_code=0, allowed=1, denied=1, total_requests=2)

    assert run.decisions_per_second is None
    assert run.allowed_per_second is None


def test_denied_ratio():
    run = RunResult(name="run-1", allowed=250, denied=750, total_requests=1000)

    assert run.denied_ratio == pytest.approx(0.75)


def test_denied_ratio_is_none_with_no_requests():
    assert RunResult(name="run-1", total_requests=0).denied_ratio is None


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("value,expected", [
    (None, NOT_MEASURED),
    (0, "0"),
    (1234, "1,234"),
    (1234.0, "1,234"),
    (12.345, "12.3"),
])
def test_fmt_number(value, expected):
    assert fmt_number(value) == expected


def test_fmt_bytes_as_gib():
    assert fmt_bytes_as_gib(34359738368) == "32.0 GiB"
    assert fmt_bytes_as_gib(0) == NOT_MEASURED
    assert fmt_bytes_as_gib(None) == NOT_MEASURED


def test_summarise_reports_the_range_across_runs():
    assert summarise([10.0, 20.0, 30.0]) == "20 (min 10, max 30)"


def test_summarise_of_a_single_run_has_no_range():
    assert summarise([42.0]) == "42"


def test_summarise_omits_the_range_when_every_run_agrees():
    assert summarise([50.0, 50.0, 50.0]) == "50", "an identical range is noise, not information"


def test_summarise_of_nothing_is_not_measured():
    assert summarise([]) == NOT_MEASURED
    assert summarise([None, None]) == NOT_MEASURED


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def test_report_contains_the_measured_figures_and_no_invented_ones(tmp_path: Path):
    write_environment(tmp_path)
    for i in (1, 2, 3):
        write_run(tmp_path, f"run-{i}")

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "# Benchmarks" in markdown
    assert "3 of 3 runs usable" in markdown
    assert "Decisions per second" in markdown
    assert "Allowed per second" in markdown
    # 3000 decisions over 60 s, 600 of them allowed.
    assert "| Decisions per second | 50 |" in markdown
    assert "| Allowed per second | 10 |" in markdown
    assert "Test CPU" in markdown
    assert "32.0 GiB" in markdown
    assert "abc123" in markdown


def test_report_explains_why_denials_dominate(tmp_path: Path):
    write_environment(tmp_path)
    write_run(tmp_path, "run-1")

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "per-minute caps" in markdown or "per-minute cap" in markdown
    # 10 USER x 10 + 5 PREMIUM x 100 + 1 ADMIN x 1000 = 1600 per minute.
    assert "1,600" in markdown
    assert "not the service struggling" in markdown


def test_report_says_so_when_the_generator_shared_the_host(tmp_path: Path):
    write_environment(tmp_path)
    write_run(tmp_path, "run-1")

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "same host as the service" in markdown
    assert "floor, not a capacity figure" in markdown


def test_report_flags_unexpected_responses_as_failures(tmp_path: Path):
    write_environment(tmp_path)
    write_run(tmp_path, "run-1", unexpected=5)

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "FAILED" in markdown
    assert "stopped making decisions" in markdown
    assert "No run completed cleanly" in markdown


def test_report_excludes_failed_runs_from_the_summary(tmp_path: Path):
    write_environment(tmp_path)
    write_run(tmp_path, "run-1")
    write_run(tmp_path, "run-2", exit_code=1, allowed=1, denied=1)

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "1 of 2 runs usable" in markdown
    assert "| Decisions per second | 50 |" in markdown, \
        "the failed run's figures must not move the summary"


def test_report_with_no_runs_says_pending(tmp_path: Path):
    write_environment(tmp_path)

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "Status: pending" in markdown
    assert "Nothing has been measured yet" in markdown


def test_report_marks_an_unclean_working_tree(tmp_path: Path):
    write_environment(tmp_path, repository={"commit": "abc123", "uncommittedChanges": True})
    write_run(tmp_path, "run-1")

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert "uncommitted changes" in markdown


def test_missing_environment_file_does_not_crash_the_report(tmp_path: Path):
    write_run(tmp_path, "run-1")

    environment, runs = load_results(tmp_path)
    markdown = render(environment, runs, tmp_path)

    assert environment == {}
    assert NOT_MEASURED in markdown


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def test_main_writes_the_output_file(tmp_path: Path):
    results = tmp_path / "results"
    results.mkdir()
    write_environment(results)
    write_run(results, "run-1")
    output = tmp_path / "out" / "benchmarks.md"

    code = main(["--results", str(results), "--output", str(output)])

    assert code == 0
    assert output.is_file()
    assert "# Benchmarks" in output.read_text()


def test_main_reports_a_missing_results_directory(tmp_path: Path):
    code = main(["--results", str(tmp_path / "nope"), "--output", str(tmp_path / "out.md")])

    assert code == 1
