"""Tests for the pod kill analysis.

Run with:  python3 -m pytest scripts/test_analyze_pod_kill.py

These cover the parts a mistake would misreport: which phase a request lands in when the Service
never recovers, how the per-pod counters are turned into "requests served", and the intervals
measured from the delete.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from analyze_failure_test import NOT_MEASURED, Request
from analyze_pod_kill import (
    build_summary,
    interval_ms,
    main,
    parse_pod_counts,
    pod_deltas,
    render,
    split_into_phases,
)


EVENTS = {
    "loadStartedAtMs": 1_000,
    "podDeletedAtMs": 2_000,
    "endpointsRestoredAtMs": 3_000,
    "podGoneAtMs": 2_400,
    "loadFinishedAtMs": 4_000,
    "deletedPod": "ratelimitx-aaa",
    "replicas": 2,
}


def make_request(seq: int = 0, status: int = 200, sent_ms: int = 1_500,
                 latency_ms: int = 4) -> Request:
    return Request(seq=seq, user="u", sent_ms=sent_ms, received_ms=sent_ms + latency_ms,
                   latency_ms=latency_ms, status=status, algorithm="sliding-window", error="")


# ---------------------------------------------------------------------------
# Phases
# ---------------------------------------------------------------------------


def test_three_phases_when_the_service_recovers():
    phases = split_into_phases([
        make_request(seq=0, sent_ms=1_500),
        make_request(seq=1, sent_ms=2_500),
        make_request(seq=2, sent_ms=3_500),
    ], EVENTS)
    assert [phase.name for phase in phases] == ["before", "disruption", "after"]
    assert [len(phase.requests) for phase in phases] == [1, 1, 1]


def test_no_after_phase_when_the_service_never_recovers():
    """Rather than invent a boundary, the disruption phase stays open to the end of the run."""
    events = dict(EVENTS, endpointsRestoredAtMs=None)
    phases = split_into_phases([make_request(seq=0, sent_ms=3_500)], events)
    assert [phase.name for phase in phases] == ["before", "disruption"]
    assert phases[1].description == "a replica missing, never restored"
    assert len(phases[1].requests) == 1


def test_the_delete_instant_belongs_to_the_disruption():
    phases = split_into_phases([make_request(sent_ms=2_000)], EVENTS)
    assert len(phases[1].requests) == 1


def test_no_phases_without_a_recorded_delete():
    assert split_into_phases([make_request()], {"loadStartedAtMs": 1}) == []


# ---------------------------------------------------------------------------
# Intervals
# ---------------------------------------------------------------------------


def test_intervals_are_measured_from_the_delete():
    assert interval_ms(EVENTS, "podDeletedAtMs", "podGoneAtMs") == 400
    assert interval_ms(EVENTS, "podDeletedAtMs", "endpointsRestoredAtMs") == 1_000


def test_an_interval_with_a_missing_end_is_none():
    assert interval_ms(dict(EVENTS, podGoneAtMs=None), "podDeletedAtMs", "podGoneAtMs") is None


# ---------------------------------------------------------------------------
# Per-pod counters
# ---------------------------------------------------------------------------


def test_pod_counts_are_parsed_and_bad_lines_skipped(tmp_path):
    path = tmp_path / "counts.txt"
    path.write_text("pod-a 10\npod-b 20\nrubbish\npod-c notanumber\n", encoding="utf-8")
    assert parse_pod_counts(path) == {"pod-a": 10, "pod-b": 20}


def test_pod_counts_of_a_missing_file_is_empty():
    assert parse_pod_counts(Path("/nonexistent/counts.txt")) == {}


def test_a_surviving_pod_reports_the_difference():
    assert pod_deltas({"pod-a": 100}, {"pod-a": 180})["pod-a"] == 80


def test_a_replacement_pod_counts_from_zero():
    """It did not exist before the run, so its whole counter is what it served."""
    assert pod_deltas({"pod-a": 100}, {"pod-a": 180, "pod-b": 45})["pod-b"] == 45


def test_the_deleted_pod_is_unknown_rather_than_guessed():
    deltas = pod_deltas({"pod-a": 100, "pod-gone": 90}, {"pod-a": 180})
    assert deltas["pod-gone"] is None


def test_render_says_the_deleted_pod_count_is_unknown(tmp_path):
    deltas = pod_deltas({"pod-a": 1, "pod-gone": 2}, {"pod-a": 5})
    text = render(tmp_path, {}, EVENTS, split_into_phases([make_request()], EVENTS), deltas, {})
    assert "deleted during the run" in text
    assert NOT_MEASURED in text


def test_render_says_not_measured_without_per_pod_counters(tmp_path):
    text = render(tmp_path, {}, EVENTS, split_into_phases([make_request()], EVENTS), {}, {})
    assert "no per-pod counters were recorded" in text


def test_render_does_not_claim_a_node_failure_was_tested(tmp_path):
    text = render(tmp_path, {}, EVENTS, split_into_phases([make_request()], EVENTS), {}, {})
    assert "Not a node failure" in text
    for forbidden in ["99.9%", "high availability", "zero downtime"]:
        assert forbidden not in text.lower()


# ---------------------------------------------------------------------------
# Summary and main
# ---------------------------------------------------------------------------


def test_summary_counts_failures_but_not_denials():
    phases = split_into_phases([
        make_request(seq=0, status=200, sent_ms=1_500),
        make_request(seq=1, status=429, sent_ms=1_600),
        make_request(seq=2, status=0, sent_ms=2_500),
        make_request(seq=3, status=503, sent_ms=2_600),
    ], EVENTS)
    summary = build_summary(phases, EVENTS, {})
    assert summary["totals"]["requests"] == 4
    assert summary["totals"]["denied"] == 1
    assert summary["totals"]["failures"] == 2
    assert summary["intervals"]["deleteToFullEndpointsMs"] == 1_000


def test_main_writes_the_report_and_the_summary(tmp_path):
    results = tmp_path / "run"
    results.mkdir()
    fields = ["seq", "user", "scheduled_ms", "sent_ms", "received_ms", "latency_ms", "status",
              "algorithm", "error"]
    with (results / "requests.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerow({"seq": 0, "user": "u", "scheduled_ms": 0, "sent_ms": 1_500,
                         "received_ms": 1_504, "latency_ms": 4, "status": 200,
                         "algorithm": "sliding-window", "error": ""})
    (results / "events.json").write_text(json.dumps(EVENTS), encoding="utf-8")
    (results / "pod-counts-before.txt").write_text("pod-a 0\n", encoding="utf-8")
    (results / "pod-counts-after.txt").write_text("pod-a 1\n", encoding="utf-8")

    output = tmp_path / "pod-kill-test.md"
    assert main(["--results", str(results), "--output", str(output)]) == 0
    summary = json.loads((results / "summary.json").read_text(encoding="utf-8"))
    assert summary["totals"]["requests"] == 1
    assert summary["requestsServedByPod"] == {"pod-a": 1}


def test_main_refuses_a_missing_results_directory(tmp_path):
    assert main(["--results", str(tmp_path / "nope"), "--output", str(tmp_path / "o.md")]) == 1


def test_main_refuses_a_run_with_no_recorded_delete(tmp_path):
    results = tmp_path / "run"
    results.mkdir()
    (results / "events.json").write_text(json.dumps({"loadStartedAtMs": 1}), encoding="utf-8")
    assert main(["--results", str(results), "--output", str(tmp_path / "o.md")]) == 1
