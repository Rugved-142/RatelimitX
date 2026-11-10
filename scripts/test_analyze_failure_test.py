"""Tests for the failure test analysis.

Run with:  python3 -m pytest scripts/test_analyze_failure_test.py

These cover the places where a mistake would put a wrong number in docs/failure-test.md: which
responses count as failures, which phase a request lands in, how the transition times are
measured, and what happens when a file is missing or truncated.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from analyze_failure_test import (
    NOT_MEASURED,
    Phase,
    Request,
    build_summary,
    first_state_at_or_after,
    fmt_ms,
    fmt_ratio,
    latency_summary,
    main,
    parse_circuit,
    parse_requests,
    percentile,
    render,
    split_into_phases,
    state_timeline,
    transition_ms,
)


def make_request(seq: int = 0, status: int = 200, sent_ms: int = 1_000,
                 latency_ms: int = 3, algorithm: str = "sliding-window",
                 error: str = "") -> Request:
    return Request(
        seq=seq,
        user="u",
        sent_ms=sent_ms,
        received_ms=sent_ms + latency_ms,
        latency_ms=latency_ms,
        status=status,
        algorithm=algorithm,
        error=error,
    )


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------


def test_200_is_allowed_and_not_a_failure():
    request = make_request(status=200)
    assert request.is_allowed
    assert not request.is_failure


def test_429_is_a_decision_not_a_failure():
    """The service under test is a rate limiter: a denial is a correct answer."""
    request = make_request(status=429)
    assert request.is_denied
    assert not request.is_failure
    assert not request.is_server_error


@pytest.mark.parametrize("status", [500, 502, 503, 599])
def test_5xx_is_a_failure(status):
    request = make_request(status=status)
    assert request.is_server_error
    assert request.is_failure


def test_no_response_is_a_failure():
    request = make_request(status=0, error="timeout")
    assert request.is_no_response
    assert request.is_failure


@pytest.mark.parametrize("status", [400, 401, 403, 404])
def test_an_unexpected_4xx_is_a_failure(status):
    """A 401 here would mean the harness lost its token, which must not pass silently."""
    request = make_request(status=status)
    assert request.is_unexpected
    assert request.is_failure


def test_401_is_not_counted_as_a_denial():
    request = make_request(status=401)
    assert not request.is_denied


# ---------------------------------------------------------------------------
# Percentiles and latency
# ---------------------------------------------------------------------------


def test_percentile_of_an_empty_sample_is_none():
    assert percentile([], 0.95) is None


def test_percentile_uses_nearest_rank():
    values = list(range(1, 101))
    assert percentile(values, 0.50) == 50
    assert percentile(values, 0.95) == 95
    assert percentile(values, 0.99) == 99


def test_percentile_of_one_value():
    assert percentile([7], 0.99) == 7


def test_latency_excludes_requests_that_got_no_response():
    """A client timeout is a non-answer, not a slow answer; averaging it in would flatter us."""
    requests = [
        make_request(seq=0, status=200, latency_ms=3),
        make_request(seq=1, status=200, latency_ms=5),
        make_request(seq=2, status=0, latency_ms=5000, error="timeout"),
    ]
    summary = latency_summary(requests)
    assert summary["count"] == 2
    assert summary["max"] == 5


def test_latency_of_no_responses_only_is_none():
    summary = latency_summary([make_request(status=0, latency_ms=5000)])
    assert summary["count"] == 0
    assert summary["p50"] is None
    assert summary["max"] is None


# ---------------------------------------------------------------------------
# Phases
# ---------------------------------------------------------------------------


EVENTS = {
    "loadStartedAtMs": 1_000,
    "redisPausedAtMs": 2_000,
    "redisUnpausedAtMs": 3_000,
    "loadFinishedAtMs": 4_000,
}


def test_requests_are_assigned_by_send_time():
    requests = [
        make_request(seq=0, sent_ms=1_500),
        make_request(seq=1, sent_ms=2_500),
        make_request(seq=2, sent_ms=3_500),
    ]
    phases = split_into_phases(requests, EVENTS)
    assert [phase.name for phase in phases] == ["baseline", "outage", "recovery"]
    assert [len(phase.requests) for phase in phases] == [1, 1, 1]


def test_a_request_sent_before_the_pause_stays_in_the_baseline():
    """It was accepted while Redis was up, even if the answer arrived after the pause."""
    request = Request(seq=0, user="u", sent_ms=1_999, received_ms=2_300, latency_ms=301,
                      status=200, algorithm="sliding-window", error="")
    phases = split_into_phases([request], EVENTS)
    assert len(phases[0].requests) == 1
    assert len(phases[1].requests) == 0


def test_the_pause_instant_belongs_to_the_outage():
    request = make_request(sent_ms=2_000)
    phases = split_into_phases([request], EVENTS)
    assert len(phases[1].requests) == 1


def test_no_phases_without_a_recorded_pause():
    assert split_into_phases([make_request()], {"loadStartedAtMs": 1}) == []


def test_phase_tallies():
    phase = Phase("outage", "", 0, 100, [
        make_request(seq=0, status=200, algorithm="local-fallback"),
        make_request(seq=1, status=429, algorithm="local-fallback"),
        make_request(seq=2, status=503),
        make_request(seq=3, status=0),
    ])
    assert phase.allowed == 1
    assert phase.denied == 1
    assert phase.server_errors == 1
    assert phase.no_responses == 1
    assert phase.failures == 2
    assert phase.fallback_responses == 2


def test_fallback_is_counted_from_the_header_the_service_sent():
    phase = Phase("outage", "", 0, 100, [
        make_request(seq=0, algorithm="local-fallback"),
        make_request(seq=1, algorithm="sliding-window"),
    ])
    assert phase.fallback_responses == 1


def test_phase_duration():
    assert Phase("p", "", 1_000, 3_500).duration_seconds == 2.5


def test_phase_duration_is_none_without_an_end():
    assert Phase("p", "", 1_000, None).duration_seconds is None


# ---------------------------------------------------------------------------
# Circuit breaker timing
# ---------------------------------------------------------------------------


SAMPLES = [
    {"t_ms": 1_000, "state": "CLOSED"},
    {"t_ms": 1_100, "state": "CLOSED"},
    {"t_ms": 2_100, "state": "CLOSED"},
    {"t_ms": 2_400, "state": "OPEN"},
    {"t_ms": 3_100, "state": "HALF_OPEN"},
    {"t_ms": 3_300, "state": "CLOSED"},
]


def test_first_state_at_or_after_ignores_earlier_samples():
    found = first_state_at_or_after(SAMPLES, "CLOSED", 3_000)
    assert found["t_ms"] == 3_300


def test_transition_time_is_measured_from_the_event():
    assert transition_ms(SAMPLES, "OPEN", 2_000) == 400
    assert transition_ms(SAMPLES, "CLOSED", 3_000) == 300


def test_transition_time_is_none_when_the_state_was_never_observed():
    assert transition_ms(SAMPLES, "OPEN", 3_200) is None


def test_transition_time_is_none_without_an_event_timestamp():
    assert transition_ms(SAMPLES, "OPEN", None) is None


def test_state_timeline_records_only_changes():
    assert state_timeline(SAMPLES) == [
        ("CLOSED", 1_000),
        ("OPEN", 2_400),
        ("HALF_OPEN", 3_100),
        ("CLOSED", 3_300),
    ]


def test_state_timeline_skips_polls_that_failed():
    samples = [
        {"t_ms": 1, "state": "CLOSED"},
        {"t_ms": 2, "http_status": 0, "error": "timeout"},
        {"t_ms": 3, "state": "OPEN"},
    ]
    assert state_timeline(samples) == [("CLOSED", 1), ("OPEN", 3)]


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def write_requests(path: Path, rows: list[dict]) -> None:
    fields = ["seq", "user", "scheduled_ms", "sent_ms", "received_ms", "latency_ms", "status",
              "algorithm", "error"]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field, "") for field in fields})


def test_parse_requests_of_a_missing_file_is_empty():
    assert parse_requests(Path("/nonexistent/requests.csv")) == []


def test_parse_requests_sorts_by_send_time(tmp_path):
    path = tmp_path / "requests.csv"
    write_requests(path, [
        {"seq": 1, "user": "u", "scheduled_ms": 0, "sent_ms": 200, "received_ms": 201,
         "latency_ms": 1, "status": 200, "algorithm": "a"},
        {"seq": 0, "user": "u", "scheduled_ms": 0, "sent_ms": 100, "received_ms": 101,
         "latency_ms": 1, "status": 429, "algorithm": "a"},
    ])
    parsed = parse_requests(path)
    assert [request.sent_ms for request in parsed] == [100, 200]


def test_parse_circuit_skips_a_truncated_final_line(tmp_path):
    """A killed run can leave half a line; that must not fail the whole analysis."""
    path = tmp_path / "circuit.jsonl"
    path.write_text('{"t_ms": 1, "state": "CLOSED"}\n{"t_ms": 2, "sta\n', encoding="utf-8")
    assert parse_circuit(path) == [{"t_ms": 1, "state": "CLOSED"}]


def test_parse_circuit_of_a_missing_file_is_empty():
    assert parse_circuit(Path("/nonexistent/circuit.jsonl")) == []


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------


def test_missing_values_are_reported_as_not_measured():
    assert fmt_ms(None) == NOT_MEASURED
    assert fmt_ratio(None, 10) == NOT_MEASURED
    assert fmt_ratio(1, 0) == NOT_MEASURED
    assert fmt_ratio(1, None) == NOT_MEASURED


def test_ratio_is_a_percentage_of_the_whole():
    assert fmt_ratio(1, 200) == "0.5%"
    assert fmt_ratio(0, 200) == "0.0%"


# ---------------------------------------------------------------------------
# Rendering and the summary file
# ---------------------------------------------------------------------------


def build_phases() -> list[Phase]:
    return split_into_phases([
        make_request(seq=0, status=200, sent_ms=1_500),
        make_request(seq=1, status=429, sent_ms=1_600),
        make_request(seq=2, status=200, sent_ms=2_500, algorithm="local-fallback"),
        make_request(seq=3, status=503, sent_ms=2_600),
        make_request(seq=4, status=200, sent_ms=3_500),
    ], EVENTS)


def test_summary_totals_match_the_phase_tallies():
    summary = build_summary(build_phases(), SAMPLES, EVENTS)
    assert summary["totals"]["requests"] == 5
    assert summary["totals"]["allowed"] == 3
    assert summary["totals"]["denied"] == 1
    assert summary["totals"]["serverErrors"] == 1
    assert summary["totals"]["failures"] == 1
    assert summary["totals"]["servedByFallback"] == 1


def test_summary_records_both_transition_times():
    summary = build_summary(build_phases(), SAMPLES, EVENTS)
    assert summary["transitions"]["pauseToObservedOpenMs"] == 400
    assert summary["transitions"]["unpauseToObservedClosedMs"] == 300


def test_render_contains_the_measured_transition_times(tmp_path):
    text = render(tmp_path, {}, EVENTS, build_phases(), SAMPLES, {"pollIntervalMs": 100})
    assert "400 ms" in text
    assert "300 ms" in text


def test_render_makes_no_availability_claim(tmp_path):
    text = render(tmp_path, {}, EVENTS, build_phases(), SAMPLES, {"pollIntervalMs": 100})
    lowered = text.lower()
    for forbidden in ["99.9", "99.99", "uptime of", "availability of", "five nines"]:
        assert forbidden not in lowered
    assert "no availability figure" in lowered


def test_render_says_not_measured_when_the_breaker_was_never_polled(tmp_path):
    text = render(tmp_path, {}, EVENTS, build_phases(), [], {})
    assert NOT_MEASURED in text


def test_render_flags_an_uncommitted_source_tree(tmp_path):
    environment = {"repository": {"commit": "abc", "uncommittedChanges": True}}
    text = render(tmp_path, environment, EVENTS, build_phases(), SAMPLES, {})
    assert "uncommitted changes present" in text


def test_main_writes_the_report_and_the_summary(tmp_path):
    results = tmp_path / "run"
    results.mkdir()
    write_requests(results / "requests.csv", [
        {"seq": 0, "user": "u", "scheduled_ms": 0, "sent_ms": 1_500, "received_ms": 1_503,
         "latency_ms": 3, "status": 200, "algorithm": "sliding-window"},
        {"seq": 1, "user": "u", "scheduled_ms": 0, "sent_ms": 2_500, "received_ms": 2_505,
         "latency_ms": 5, "status": 429, "algorithm": "local-fallback"},
    ])
    (results / "circuit.jsonl").write_text(
        "\n".join(json.dumps(sample) for sample in SAMPLES) + "\n", encoding="utf-8")
    (results / "events.json").write_text(json.dumps(EVENTS), encoding="utf-8")

    output = tmp_path / "failure-test.md"
    assert main(["--results", str(results), "--output", str(output)]) == 0
    assert output.is_file()

    summary = json.loads((results / "summary.json").read_text(encoding="utf-8"))
    assert summary["totals"]["requests"] == 2
    assert summary["totals"]["failures"] == 0


def test_main_refuses_a_missing_results_directory(tmp_path):
    assert main(["--results", str(tmp_path / "nope"), "--output", str(tmp_path / "o.md")]) == 1


def test_main_refuses_a_run_with_no_recorded_pause(tmp_path):
    results = tmp_path / "run"
    results.mkdir()
    (results / "events.json").write_text(json.dumps({"loadStartedAtMs": 1}), encoding="utf-8")
    assert main(["--results", str(results), "--output", str(tmp_path / "o.md")]) == 1


# ---------------------------------------------------------------------------
# Recovery is paced by the breaker's own timer, not by Redis
# ---------------------------------------------------------------------------


def test_last_entry_into_state_is_the_change_not_the_latest_sample():
    """The poll log repeats a state every 100 ms; only the moment it was entered is a timing."""
    from analyze_failure_test import last_entry_into_state
    # CLOSED is sampled at 1000, 1100 and 2100, but it was entered once, at 1000.
    assert last_entry_into_state(SAMPLES, "CLOSED", 2_200) == 1_000


def test_last_entry_into_state_takes_the_most_recent_entry():
    from analyze_failure_test import last_entry_into_state
    assert last_entry_into_state(SAMPLES, "CLOSED", 9_999) == 3_300


def test_last_entry_into_state_is_none_when_never_entered():
    from analyze_failure_test import last_entry_into_state
    assert last_entry_into_state(SAMPLES, "HALF_OPEN", 2_000) is None


def test_time_from_the_last_open_is_measured_not_the_unpause():
    """A breaker that re-opened during the outage closes on its own timer, not on Redis's."""
    from analyze_failure_test import _from_last_open
    samples = [
        {"t_ms": 1_000, "state": "CLOSED"},
        {"t_ms": 2_000, "state": "OPEN"},
        {"t_ms": 32_000, "state": "HALF_OPEN"},
        {"t_ms": 32_200, "state": "OPEN"},     # the probe failed: Redis was still frozen
        {"t_ms": 40_000, "state": "OPEN"},     # still open, repeated by the poller
        {"t_ms": 62_200, "state": "CLOSED"},
    ]
    unpause = 42_000
    # Measured from the unpause it looks like 20.2s, but the breaker's own timer explains it.
    assert transition_ms(samples, "CLOSED", unpause) == 20_200
    assert _from_last_open(samples, unpause) == 30_000


def test_time_from_the_last_open_is_none_without_an_unpause():
    from analyze_failure_test import _from_last_open
    assert _from_last_open(SAMPLES, None) is None


def test_render_explains_that_recovery_is_paced_by_the_open_timeout(tmp_path):
    environment = {"parameters": {"breakerTimeoutMs": 30_000}}
    text = render(tmp_path, environment, EVENTS, build_phases(), SAMPLES,
                  {"pollIntervalMs": 100})
    assert "not the time Redis took to come back" in text


# ---------------------------------------------------------------------------
# What the failover did to the enforced limit
# ---------------------------------------------------------------------------


def test_allowed_per_second_is_none_without_a_duration():
    assert Phase("p", "", 0, None, [make_request(status=200)]).allowed_per_second is None


def test_allowed_per_second_counts_only_admissions():
    phase = Phase("p", "", 0, 10_000, [
        make_request(seq=0, status=200),
        make_request(seq=1, status=200),
        make_request(seq=2, status=429),
    ])
    assert phase.allowed_per_second == pytest.approx(0.2)


def _phases_with_rates(baseline_allowed: int, outage_allowed: int) -> list[Phase]:
    return [
        Phase("baseline", "", 0, 10_000,
              [make_request(seq=i, status=200) for i in range(baseline_allowed)]),
        Phase("outage", "", 10_000, 20_000,
              [make_request(seq=100 + i, status=200) for i in range(outage_allowed)]),
        Phase("recovery", "", 20_000, 30_000, []),
    ]


def test_a_more_generous_limit_during_the_outage_is_stated_plainly():
    from analyze_failure_test import _admission_note
    text = "\n".join(_admission_note(_phases_with_rates(10, 40)))
    assert "more generous limit" in text
    assert "1.0" in text and "4.0" in text


def test_an_unchanged_admission_rate_is_not_dressed_up():
    from analyze_failure_test import _admission_note
    text = "\n".join(_admission_note(_phases_with_rates(40, 10)))
    assert "did not raise the admission rate" in text
    assert "more generous limit" not in text


def test_the_admission_note_is_omitted_when_the_phases_are_missing():
    from analyze_failure_test import _admission_note
    assert _admission_note([Phase("baseline", "", 0, 10)]) == []
