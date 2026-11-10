#!/usr/bin/env python3
"""Build docs/failure-test.md from the raw data saved by scripts/failure-test.sh.

Every number in the generated document is read from the recorded request log, the circuit
breaker poll log and the event timestamps. Nothing is estimated, and anything that cannot be read
from those files is reported as "not measured" rather than filled in.

What this deliberately does not do:

* It publishes no availability figure. The run lasts a couple of minutes under one synthetic load
  shape on one machine; that cannot support a claim about availability over any period, so none
  is made.
* It does not treat a 429 as a failure. The service under test is a rate limiter, so a denial is
  a correct answer. Failures here are 5xx responses and requests that got no response at all.

Usage:
    python3 scripts/analyze_failure_test.py --results docs/failure-tests/2026-11-10 \
        --output docs/failure-test.md
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

NOT_MEASURED = "not measured"

#: A recorded status of 0 means no HTTP response arrived at all.
NO_RESPONSE = 0


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


@dataclass
class Request:
    """One recorded request. Times are epoch milliseconds."""

    seq: int
    user: str
    sent_ms: int
    received_ms: int
    latency_ms: int
    status: int
    algorithm: str
    error: str

    @property
    def is_allowed(self) -> bool:
        return self.status == 200

    @property
    def is_denied(self) -> bool:
        return self.status == 429

    @property
    def is_server_error(self) -> bool:
        return 500 <= self.status < 600

    @property
    def is_no_response(self) -> bool:
        return self.status == NO_RESPONSE

    @property
    def is_unexpected(self) -> bool:
        """Anything that is neither a decision nor a known failure mode: a 4xx that is not 429."""
        return not (self.is_allowed or self.is_denied or self.is_server_error
                    or self.is_no_response)

    @property
    def is_failure(self) -> bool:
        """A failure is an answer the service should never have given, or no answer at all."""
        return self.is_server_error or self.is_no_response or self.is_unexpected


def parse_requests(path: Path) -> list[Request]:
    if not path.is_file():
        return []
    rows: list[Request] = []
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            rows.append(Request(
                seq=int(row["seq"]),
                user=row.get("user", ""),
                sent_ms=int(row["sent_ms"]),
                received_ms=int(row["received_ms"]),
                latency_ms=int(row["latency_ms"]),
                status=int(row["status"]),
                algorithm=row.get("algorithm", ""),
                error=row.get("error", ""),
            ))
    rows.sort(key=lambda request: request.sent_ms)
    return rows


def parse_circuit(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    samples: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            samples.append(json.loads(line))
        except json.JSONDecodeError:
            # A truncated final line is possible if the run was killed. Skip it rather than
            # failing the whole analysis.
            continue
    samples.sort(key=lambda sample: sample.get("t_ms", 0))
    return samples


def read_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


# ---------------------------------------------------------------------------
# Measurement
# ---------------------------------------------------------------------------


@dataclass
class Phase:
    """A slice of the run, bounded by the events the script recorded."""

    name: str
    description: str
    start_ms: int
    end_ms: int | None = None
    requests: list[Request] = field(default_factory=list)

    @property
    def duration_seconds(self) -> float | None:
        if self.end_ms is None:
            return None
        return (self.end_ms - self.start_ms) / 1000.0

    def tally(self, predicate) -> int:
        return sum(1 for request in self.requests if predicate(request))

    @property
    def allowed(self) -> int:
        return self.tally(lambda request: request.is_allowed)

    @property
    def denied(self) -> int:
        return self.tally(lambda request: request.is_denied)

    @property
    def server_errors(self) -> int:
        return self.tally(lambda request: request.is_server_error)

    @property
    def no_responses(self) -> int:
        return self.tally(lambda request: request.is_no_response)

    @property
    def unexpected(self) -> int:
        return self.tally(lambda request: request.is_unexpected)

    @property
    def failures(self) -> int:
        return self.tally(lambda request: request.is_failure)

    @property
    def allowed_per_second(self) -> float | None:
        """Admissions per second. The figure the fallback is expected to change."""
        duration = self.duration_seconds
        if not duration:
            return None
        return self.allowed / duration

    @property
    def fallback_responses(self) -> int:
        """Responses the service itself labelled as served by the local fallback."""
        return self.tally(lambda request: request.algorithm == "local-fallback")


def percentile(values: Sequence[float], fraction: float) -> float | None:
    """Nearest-rank percentile. Returns None for an empty sample rather than guessing."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


def latency_summary(requests: Iterable[Request]) -> dict[str, float | None]:
    # Only responses that actually arrived have a meaningful latency. A client timeout records
    # the timeout, not the service's response time, and averaging it in would understate the
    # harm by turning a non-answer into a slow answer.
    values = [float(request.latency_ms) for request in requests if not request.is_no_response]
    return {
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values) if values else None,
        "count": len(values),
    }


def split_into_phases(requests: list[Request], events: dict[str, Any]) -> list[Phase]:
    """Cuts the request log at the recorded pause and unpause timestamps.

    A request is assigned to the phase its send time falls in. A request sent just before the
    pause and answered just after it belongs to the baseline, which is the honest placement: the
    service accepted it while Redis was up.
    """
    start = events.get("loadStartedAtMs")
    pause = events.get("redisPausedAtMs")
    unpause = events.get("redisUnpausedAtMs")
    end = events.get("loadFinishedAtMs")
    if start is None or pause is None or unpause is None:
        return []

    phases = [
        Phase("baseline", "Redis up, before the pause", start, pause),
        Phase("outage", "Redis paused", pause, unpause),
        Phase("recovery", "Redis unpaused", unpause, end),
    ]
    for request in requests:
        for phase in phases:
            if request.sent_ms >= phase.start_ms and (
                    phase.end_ms is None or request.sent_ms < phase.end_ms):
                phase.requests.append(request)
                break
    return phases


def first_state_at_or_after(samples: list[dict[str, Any]], state: str,
                            t_ms: int) -> dict[str, Any] | None:
    """The first poll at or after t_ms that observed the given state."""
    for sample in samples:
        if sample.get("t_ms", 0) >= t_ms and sample.get("state") == state:
            return sample
    return None


def last_entry_into_state(samples: list[dict[str, Any]], state: str,
                          t_ms: int) -> int | None:
    """When the breaker last *entered* the given state at or before t_ms.

    Deliberately not "the last poll that saw this state": the poll log holds one sample every
    100 ms, so during a 30 second OPEN period the last OPEN sample before an event is simply the
    one just before it. What matters for timing is when the state was entered, which is the last
    change in the timeline.
    """
    entered: int | None = None
    for observed_state, observed_ms in state_timeline(samples):
        if observed_ms > t_ms:
            break
        if observed_state == state:
            entered = observed_ms
    return entered


def state_timeline(samples: list[dict[str, Any]]) -> list[tuple[str, int]]:
    """Each change of observed state, with the time it was first observed."""
    timeline: list[tuple[str, int]] = []
    previous: str | None = None
    for sample in samples:
        state = sample.get("state")
        if state is None:
            continue
        if state != previous:
            timeline.append((state, sample.get("t_ms", 0)))
            previous = state
    return timeline


def transition_ms(samples: list[dict[str, Any]], state: str,
                  from_ms: int | None) -> int | None:
    if from_ms is None:
        return None
    observed = first_state_at_or_after(samples, state, from_ms)
    if observed is None:
        return None
    return observed["t_ms"] - from_ms


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------


def fmt_int(value: int | None) -> str:
    return NOT_MEASURED if value is None else f"{value:,}"


def fmt_ms(value: float | int | None) -> str:
    return NOT_MEASURED if value is None else f"{value:,.0f} ms"


def fmt_seconds(value: float | None) -> str:
    return NOT_MEASURED if value is None else f"{value:,.1f} s"


def platform_name(environment: dict[str, Any]) -> str:
    """Which platform the run was driven against; "compose" when unrecorded, as older runs are."""
    return environment.get("platform", {}).get("name") or "compose"


def _replica_count(environment: dict[str, Any]) -> int:
    """How many application instances the run was driven against. Defaults to 1 when unrecorded.

    Every admission figure in this report is a total across however many instances were running,
    and the fallback's limits are per instance, so this number changes what the figures mean.
    """
    raw = environment.get("platform", {}).get("appReplicas")
    try:
        return max(1, int(raw))
    except (TypeError, ValueError):
        return 1


def fmt_rate(value: float | None) -> str:
    return NOT_MEASURED if value is None else f"{value:,.1f}"


def fmt_ratio(part: int | None, whole: int | None) -> str:
    if part is None or not whole:
        return NOT_MEASURED
    return f"{100.0 * part / whole:.1f}%"


def _admission_note(phases: list[Phase]) -> list[str]:
    """States, from the measured admission rates, what the failover did to the limit.

    The fallback keeps its own counters and cannot see Redis's, so when the breaker opens every
    caller starts a fresh window on this instance regardless of how much of their Redis window
    they had already spent. Whether that shows up as extra admissions depends on how much of the
    window the callers had used, so this reports what happened in this run rather than asserting
    a general rule.
    """
    by_name = {phase.name: phase for phase in phases}
    baseline = by_name.get("baseline")
    outage = by_name.get("outage")
    if baseline is None or outage is None:
        return []
    before = baseline.allowed_per_second
    during = outage.allowed_per_second
    if before is None or during is None:
        return []

    lines = ["#### What the failover did to the limit", ""]
    lines.append("The fallback keeps its own counters and cannot read Redis's, so when the")
    lines.append("breaker opens every caller starts a fresh window on this instance however much")
    lines.append("of their Redis window they had already spent.")
    lines.append("")
    if during > before:
        lines.append(f"That is visible here: admissions went from {before:,.1f} per second while")
        lines.append(f"Redis was up to {during:,.1f} per second while it was frozen, against a")
        lines.append("configured limit that did not change. The service kept answering, which is")
        lines.append("the point of the fallback, but it was enforcing a more generous limit than")
        lines.append("the one it is configured with, and it says so in the `X-Algorithm` header")
        lines.append("on every one of those responses.")
    else:
        lines.append(f"In this run it did not raise the admission rate: {before:,.1f} per second")
        lines.append(f"while Redis was up against {during:,.1f} per second while it was frozen.")
        lines.append("That depends on how much of their window the callers had already spent when")
        lines.append("the breaker opened, so it is not a property to rely on.")
    lines.append("")
    return lines


def render(results_dir: Path, environment: dict[str, Any], events: dict[str, Any],
           phases: list[Phase], samples: list[dict[str, Any]],
           load_summary: dict[str, Any]) -> str:
    requests = [request for phase in phases for request in phase.requests]
    total = len(requests)
    failures = sum(phase.failures for phase in phases)

    pause_ms = events.get("redisPausedAtMs")
    unpause_ms = events.get("redisUnpausedAtMs")
    to_open = transition_ms(samples, "OPEN", pause_ms)
    to_closed = transition_ms(samples, "CLOSED", unpause_ms)
    from_last_open_to_closed = _from_last_open(samples, unpause_ms)
    poll_interval = load_summary.get("pollIntervalMs")

    parameters = environment.get("parameters", {})
    host = environment.get("host", {})
    repository = environment.get("repository", {})

    lines: list[str] = []
    add = lines.append

    add("# Failure test: Redis outage")
    add("")
    add("<!-- Generated by scripts/analyze_failure_test.py. Do not edit by hand: regenerate it")
    add("     with scripts/failure-test.sh, which rewrites this file from the saved run data. -->")
    add("")
    add(f"Generated from `{results_dir.as_posix()}`.")
    add("")

    # -- what was done -----------------------------------------------------
    add("## What was done")
    add("")
    add("Steady load was driven against `/api/data` for the whole run. Part way through, Redis")
    add("was frozen, left frozen, and then let go. Freezing rather than stopping is the point:")
    add("connections stay open and commands simply never get an answer, which is the failure")
    add("mode the 200 ms Redis timeout and the circuit breaker exist for. Stopping the container")
    add("would have refused connections instead, which is a different failure.")
    add("")
    if platform_name(environment) == "kubernetes":
        add("On Kubernetes the freeze is SIGSTOP to the `redis-server` process, sent from the")
        add("kind node rather than from inside the pod. Kubernetes has no equivalent of")
        add("`docker compose pause`, and kind's default CNI does not enforce NetworkPolicy, so")
        add("SIGSTOP is what reproduces the failure the compose run used. It has to come from")
        add("outside the pod's PID namespace: the kernel discards an unhandled signal sent to")
        add("PID 1 from inside its own namespace, so `kubectl exec ... kill -STOP 1` does nothing")
        add("at all and Redis keeps answering.")
    else:
        add("The freeze is `docker compose pause redis`, which uses the freezer cgroup.")
    add("")
    add("Throughout, `/admin/circuit` was polled so the breaker's state could be timed. That")
    add("endpoint reads in-memory state and touches neither Redis nor the fallback, which is why")
    add("it keeps answering while Redis is frozen.")
    add("")
    add("| Parameter | Value |")
    add("|-----------|-------|")
    add(f"| Algorithm | {parameters.get('algorithm', NOT_MEASURED)} |")
    add(f"| Load users | {fmt_int(parameters.get('userCount'))} |")
    add(f"| Per-user limit (requests/min) | {fmt_int(parameters.get('userLimit'))} |")
    add(f"| Offered rate | {load_summary.get('requestedRatePerSecond', NOT_MEASURED)} req/s |")
    add(f"| Baseline before the pause | {fmt_seconds(parameters.get('baselineSeconds'))} |")
    add(f"| Pause duration | {fmt_seconds(parameters.get('pauseSeconds'))} |")
    add(f"| Observation after unpause | {fmt_seconds(parameters.get('recoverySeconds'))} |")
    add(f"| Circuit poll interval | {fmt_ms(poll_interval)} |")
    add(f"| Breaker failure threshold | {fmt_int(parameters.get('failureThreshold'))} "
        "consecutive Redis failures |")
    add(f"| Breaker open timeout | {fmt_ms(parameters.get('breakerTimeoutMs'))} |")
    add("")

    # -- headline ----------------------------------------------------------
    add("## What was measured")
    add("")
    add("A 429 is a correct answer from a rate limiter, so it is not counted as a failure here.")
    add("Failures are 5xx responses, responses with a status the service should never return,")
    add("and requests that received no response at all.")
    add("")
    add("| Measure | Value |")
    add("|---------|-------|")
    add(f"| Requests recorded | {fmt_int(total)} |")
    add(f"| 5xx responses | {fmt_int(sum(phase.server_errors for phase in phases))} |")
    add(f"| Requests with no response (client timeouts and connection errors) | "
        f"{fmt_int(sum(phase.no_responses for phase in phases))} |")
    add(f"| Other unexpected statuses | {fmt_int(sum(phase.unexpected for phase in phases))} |")
    add(f"| Total failures | {fmt_int(failures)} ({fmt_ratio(failures, total)}) |")
    add(f"| Pause to breaker observed OPEN | {fmt_ms(to_open)} |")
    add(f"| Unpause to breaker observed CLOSED | {fmt_ms(to_closed)} |")
    add(f"| Breaker's last entry into OPEN, to observed CLOSED | "
        f"{fmt_ms(from_last_open_to_closed)} |")
    add("")
    breaker_timeout = parameters.get("breakerTimeoutMs")
    if to_closed is not None and breaker_timeout:
        add("The time to close is not the time Redis took to come back. The breaker only lets a")
        add("probe through once its open timeout has elapsed, so recovery is paced by that timer")
        add("and depends on where in that cycle the recovery happens to land. The last row above")
        add("measures the breaker's own cycle instead, from the moment it last entered OPEN to")
        add(f"the moment it was seen CLOSED; it should sit close to the configured open timeout")
        add(f"of {fmt_ms(breaker_timeout)}. So the time between Redis becoming reachable and the")
        add("breaker closing is at worst one full open timeout, and at best immediate - it is a")
        add("property of this configuration, not a measurement of how fast Redis recovered.")
        add("")
    if poll_interval:
        add(f"Both transition times are the gap between an event this script's own log timed and")
        add(f"the first poll that saw the new state, so each carries up to {poll_interval} ms of")
        add("polling error and is an upper bound on the true transition time, not an exact value.")
        add("")

    # -- per phase ---------------------------------------------------------
    add("## By phase")
    add("")
    add("A request belongs to the phase it was *sent* in.")
    add("")
    add("| Phase | Duration | Requests | 200 | 429 | 5xx | No response | Allowed/s | "
        "Served by fallback |")
    add("|-------|----------|----------|-----|-----|-----|-------------|-----------|"
        "--------------------|")
    for phase in phases:
        add(f"| {phase.name} | {fmt_seconds(phase.duration_seconds)} | "
            f"{fmt_int(len(phase.requests))} | {fmt_int(phase.allowed)} | "
            f"{fmt_int(phase.denied)} | {fmt_int(phase.server_errors)} | "
            f"{fmt_int(phase.no_responses)} | {fmt_rate(phase.allowed_per_second)} | "
            f"{fmt_int(phase.fallback_responses)} |")
    add("")
    add("`Served by fallback` counts responses whose `X-Algorithm` header was `local-fallback`,")
    add("which is the service reporting on itself rather than an inference from the timing.")
    add("")
    lines.extend(_admission_note(phases))

    add("### Response time by phase")
    add("")
    add("Only responses that arrived are included: a request that timed out has no service")
    add("response time, and averaging the client timeout in would turn a non-answer into a slow")
    add("answer.")
    add("")
    add("| Phase | Responses | p50 | p95 | p99 | max |")
    add("|-------|-----------|-----|-----|-----|-----|")
    for phase in phases:
        latency = latency_summary(phase.requests)
        add(f"| {phase.name} | {fmt_int(latency['count'])} | {fmt_ms(latency['p50'])} | "
            f"{fmt_ms(latency['p95'])} | {fmt_ms(latency['p99'])} | {fmt_ms(latency['max'])} |")
    add("")

    # -- timeline ----------------------------------------------------------
    add("## Circuit breaker timeline")
    add("")
    timeline = state_timeline(samples)
    if not timeline or pause_ms is None:
        add(NOT_MEASURED + ": no circuit breaker samples were recorded.")
    else:
        add("Times are relative to the moment Redis was paused. A negative value is before it.")
        add("")
        add("| Observed state | Relative to pause |")
        add("|----------------|-------------------|")
        for state, t_ms in timeline:
            offset = (t_ms - pause_ms) / 1000.0
            add(f"| {state} | {offset:+.1f} s |")
        add("")
        failed_polls = sum(1 for sample in samples if sample.get("http_status") == NO_RESPONSE)
        add(f"Polls recorded: {len(samples):,}, of which {failed_polls:,} got no answer.")
    add("")

    # -- what this does not show -------------------------------------------
    add("## What this does not show")
    add("")
    add("- **No availability figure.** This is a single run of about two minutes, under one")
    add("  synthetic load shape, on one machine. It cannot support a claim about availability")
    add("  over any period, and none is made here or anywhere else in this repository.")
    replicas = _replica_count(environment)
    if replicas == 1:
        add("- **One instance.** The local fallback's limits are per instance, so the admission")
        add("  numbers above apply to a single replica. With N replicas in the fallback, between")
        add("  them they would admit up to N times as many requests per window.")
    else:
        add(f"- **{replicas} instances, and the fallback's limits are per instance.** While the")
        add("  breaker was open each replica enforced the configured limit against its own")
        add(f"  counters, so between them the {replicas} of them could admit up to {replicas}")
        add("  times the configured rate. The admission figures above are the total across all")
        add("  of them, not one replica's.")
    if replicas != 1:
        add("- **The breaker is per instance too.** The state timeline above is one pod's, polled")
        add("  directly rather than through the Service, because a load-balanced poll would")
        add("  interleave independent state machines. The other replicas ran their own breakers")
        add("  over the same outage; nothing here times those.")
    add("- **One failure mode.** A frozen Redis is not the same as a Redis that refuses")
    add("  connections, returns errors, or loses data. Only the frozen case was tested.")
    add("- **The load generator shared the machine with the service**, so the response times")
    add("  above include contention that a separate generator would not cause.")
    add("")

    # -- environment -------------------------------------------------------
    add("## Environment")
    add("")
    add("| Item | Value |")
    add("|------|-------|")
    add(f"| Captured | {environment.get('capturedAt', NOT_MEASURED)} |")
    add(f"| OS | {host.get('os', NOT_MEASURED)} |")
    add(f"| CPU | {host.get('cpuModel', NOT_MEASURED)} ({host.get('cpuCount', NOT_MEASURED)} "
        "logical) |")
    platform = environment.get("platform", {})
    add(f"| Platform | {platform.get('name', NOT_MEASURED)} |")
    add(f"| Application replicas | {platform.get('appReplicas', NOT_MEASURED)} |")
    if platform.get("name") == "kubernetes":
        add(f"| Kubernetes server | {platform.get('kubernetesServer', NOT_MEASURED)} |")
        add(f"| Node image | {platform.get('nodeOsImage', NOT_MEASURED)} |")
        add(f"| Pod whose breaker was timed | {events.get('observedPod') or NOT_MEASURED} |")
    restarts_before = events.get("redisRestartsBefore")
    restarts_after = events.get("redisRestartsAfter")
    if restarts_before is not None and restarts_after is not None:
        same = str(restarts_before) == str(restarts_after)
        add(f"| Redis container restarts during the run | "
            f"{'none' if same else f'{restarts_before} -> {restarts_after}'} |")
    add(f"| Docker engine | {environment.get('docker', {}).get('engine', NOT_MEASURED)} |")
    add(f"| Commit | `{repository.get('commit', NOT_MEASURED)}` |")
    dirty = repository.get("uncommittedChanges")
    if dirty is None:
        source = NOT_MEASURED
    elif dirty:
        source = "**uncommitted changes present**"
    else:
        source = "committed, excluding this run's own output"
    add(f"| Source at run time | {source} |")
    add("")

    add("## Reproducing this")
    add("")
    if platform_name(environment) == "kubernetes":
        add("```bash")
        add("scripts/kind-up.sh")
        add("```")
        add("")
        add("```bash")
        add("scripts/failure-test.sh --platform kubernetes")
        add("```")
    else:
        add("```bash")
        add("docker compose up -d --build")
        add("```")
        add("")
        add("```bash")
        add("scripts/failure-test.sh")
        add("```")
    add("")
    add("The request log, the circuit breaker poll log, the event timestamps and the captured")
    add(f"environment are committed under `{results_dir.as_posix()}/`. This file is regenerated")
    add("from them and should not be edited directly.")
    add("")

    return "\n".join(lines)


def _from_last_open(samples: list[dict[str, Any]], unpause_ms: int | None) -> int | None:
    """How long after the breaker was last seen OPEN it was seen CLOSED.

    This is the figure that should track the configured open timeout, because the breaker will
    not probe Redis before that timer expires however quickly Redis recovers.
    """
    if unpause_ms is None:
        return None
    entered_open = last_entry_into_state(samples, "OPEN", unpause_ms)
    if entered_open is None:
        return None
    return transition_ms(samples, "CLOSED", entered_open)


def build_summary(phases: list[Phase], samples: list[dict[str, Any]],
                  events: dict[str, Any]) -> dict[str, Any]:
    """The same measurements as a machine-readable file, so a reader need not parse Markdown."""
    pause_ms = events.get("redisPausedAtMs")
    unpause_ms = events.get("redisUnpausedAtMs")
    return {
        "totals": {
            "requests": sum(len(phase.requests) for phase in phases),
            "allowed": sum(phase.allowed for phase in phases),
            "denied": sum(phase.denied for phase in phases),
            "serverErrors": sum(phase.server_errors for phase in phases),
            "noResponse": sum(phase.no_responses for phase in phases),
            "unexpected": sum(phase.unexpected for phase in phases),
            "failures": sum(phase.failures for phase in phases),
            "servedByFallback": sum(phase.fallback_responses for phase in phases),
        },
        "transitions": {
            "pauseToObservedOpenMs": transition_ms(samples, "OPEN", pause_ms),
            "unpauseToObservedClosedMs": transition_ms(samples, "CLOSED", unpause_ms),
            "lastObservedOpenBeforeUnpauseToClosedMs": _from_last_open(samples, unpause_ms),
            "observedStates": [{"state": state, "atMs": t_ms} for state, t_ms
                               in state_timeline(samples)],
        },
        "phases": [
            {
                "name": phase.name,
                "durationSeconds": phase.duration_seconds,
                "requests": len(phase.requests),
                "allowed": phase.allowed,
                "denied": phase.denied,
                "serverErrors": phase.server_errors,
                "noResponse": phase.no_responses,
                "unexpected": phase.unexpected,
                "allowedPerSecond": phase.allowed_per_second,
                "servedByFallback": phase.fallback_responses,
                "latencyMs": latency_summary(phase.requests),
            }
            for phase in phases
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, help="directory written by failure-test.sh")
    parser.add_argument("--output", required=True, help="Markdown file to write")
    args = parser.parse_args(argv)

    results_dir = Path(args.results)
    if not results_dir.is_dir():
        print("no such results directory: " + str(results_dir))
        return 1

    requests = parse_requests(results_dir / "requests.csv")
    samples = parse_circuit(results_dir / "circuit.jsonl")
    events = read_json(results_dir / "events.json")
    environment = read_json(results_dir / "environment.json")
    load_summary = read_json(results_dir / "load-summary.json")

    phases = split_into_phases(requests, events)
    if not phases:
        print("the event log does not record a pause and an unpause; nothing to analyse")
        return 1

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(results_dir, environment, events, phases, samples, load_summary),
                      encoding="utf-8")

    summary_path = results_dir / "summary.json"
    summary_path.write_text(
        json.dumps(build_summary(phases, samples, events), indent=2) + "\n", encoding="utf-8")

    print("  wrote " + str(output))
    print("  wrote " + str(summary_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
