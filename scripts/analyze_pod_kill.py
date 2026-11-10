#!/usr/bin/env python3
"""Build docs/pod-kill-test.md from the raw data saved by scripts/pod-kill-test.sh.

Every number in the generated document is read from the recorded request log, the per-pod request
counters and the event timestamps. Anything that cannot be read from those files is reported as
"not measured".

The request classification, the percentile arithmetic and the Phase type are imported from
scripts/analyze_failure_test.py rather than written again: the two reports have to agree about
what a failure is, and two copies of that decision would eventually disagree.

Usage:
    python3 scripts/analyze_pod_kill.py --results docs/pod-kill-tests/2026-11-10 \
        --output docs/pod-kill-test.md
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

# This module imports the shared classification from analyze_failure_test.py, which sits beside
# it. The scripts are run with `python3 -I`, which deliberately drops the script's own directory
# from sys.path - good hygiene, since it stops a file in the working directory shadowing a
# standard library module, but it also hides the sibling. Put just that one directory back.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from analyze_failure_test import (  # noqa: E402 - must follow the sys.path line above
    NOT_MEASURED,
    Phase,
    Request,
    fmt_int,
    fmt_ms,
    fmt_rate,
    fmt_ratio,
    fmt_seconds,
    latency_summary,
    parse_requests,
    read_json,
)


def split_into_phases(requests: list[Request], events: dict[str, Any]) -> list[Phase]:
    """Cuts the request log at the delete and at the return to a full set of endpoints.

    A request belongs to the phase it was sent in. The disruption phase runs from the delete to
    the moment the Service had its full complement of ready endpoints again - that is the window
    in which fewer replicas were carrying the traffic, which is the thing being measured.
    """
    start = events.get("loadStartedAtMs")
    deleted = events.get("podDeletedAtMs")
    restored = events.get("endpointsRestoredAtMs")
    end = events.get("loadFinishedAtMs")
    if start is None or deleted is None:
        return []

    phases = [Phase("before", "all replicas ready", start, deleted)]
    if restored is None:
        # The Service never got back to full strength inside the run. Say so by leaving the
        # disruption phase open to the end rather than inventing a boundary.
        phases.append(Phase("disruption", "a replica missing, never restored", deleted, end))
    else:
        phases.append(Phase("disruption", "a replica missing", deleted, restored))
        phases.append(Phase("after", "all replicas ready again", restored, end))

    for request in requests:
        for phase in phases:
            if request.sent_ms >= phase.start_ms and (
                    phase.end_ms is None or request.sent_ms < phase.end_ms):
                phase.requests.append(request)
                break
    return phases


def parse_pod_counts(path: Path) -> dict[str, int]:
    """Reads 'pod-name count' lines written from each pod's own metrics endpoint."""
    if not path.is_file():
        return {}
    counts: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        try:
            counts[parts[0]] = int(parts[1])
        except ValueError:
            continue
    return counts


def pod_deltas(before: dict[str, int], after: dict[str, int]) -> dict[str, int | None]:
    """How many /api/data requests each pod served during the run.

    A pod present only in `after` is the replacement: its counter started at zero, so its count
    is its delta. A pod present only in `before` is the one that was deleted, and its final count
    went with it - that is recorded as unknown rather than guessed at.
    """
    deltas: dict[str, int | None] = {}
    for pod, count in after.items():
        deltas[pod] = count - before.get(pod, 0)
    for pod in before:
        if pod not in after:
            deltas[pod] = None
    return deltas


def interval_ms(events: dict[str, Any], from_key: str, to_key: str) -> int | None:
    start, end = events.get(from_key), events.get(to_key)
    if start is None or end is None:
        return None
    return end - start


def render(results_dir: Path, environment: dict[str, Any], events: dict[str, Any],
           phases: list[Phase], deltas: dict[str, int | None],
           load_summary: dict[str, Any]) -> str:
    requests = [request for phase in phases for request in phase.requests]
    total = len(requests)
    failures = sum(phase.failures for phase in phases)
    parameters = environment.get("parameters", {})
    platform = environment.get("platform", {})
    repository = environment.get("repository", {})

    lines: list[str] = []
    add = lines.append

    add("# Pod kill test")
    add("")
    add("<!-- Generated by scripts/analyze_pod_kill.py. Do not edit by hand: regenerate it with")
    add("     scripts/pod-kill-test.sh, which rewrites this file from the saved run data. -->")
    add("")
    add(f"Generated from `{results_dir.as_posix()}`.")
    add("")

    add("## What was done")
    add("")
    add("Steady load was driven against `/api/data` through the Service for the whole run. Part")
    add("way through, one of the application pods was deleted. The run then continued long enough")
    add("for the replacement to start, pass its readiness probe and rejoin the Service.")
    add("")
    add("The delete was an ordinary one, not `--force --grace-period=0`. An ordinary delete is")
    add("what a node drain, a rollout and an eviction all do, so it is the case worth testing,")
    add("and it is the one that exercises the readiness probe, the preStop pause and graceful")
    add("shutdown. A forced delete would skip all three.")
    add("")
    add("| Parameter | Value |")
    add("|-----------|-------|")
    add(f"| Replicas | {fmt_int(platform.get('appReplicas'))} |")
    add(f"| Deleted pod | `{events.get('deletedPod') or NOT_MEASURED}` |")
    add(f"| Offered rate | {load_summary.get('requestedRatePerSecond', NOT_MEASURED)} req/s |")
    add(f"| Per-user limit (requests/min) | {fmt_int(parameters.get('userLimit'))} |")
    add(f"| Load before the delete | {fmt_seconds(parameters.get('baselineSeconds'))} |")
    add(f"| Load after the delete | {fmt_seconds(parameters.get('observeSeconds'))} |")
    add(f"| terminationGracePeriodSeconds | "
        f"{fmt_int(platform.get('terminationGracePeriodSeconds'))} |")
    add("")

    add("## What was measured")
    add("")
    add("A 429 is a correct answer from a rate limiter and is not counted as a failure. Failures")
    add("are 5xx responses, statuses the service should never return, and requests that received")
    add("no response at all - which is what a dropped connection during a shutdown looks like.")
    add("")
    add("| Measure | Value |")
    add("|---------|-------|")
    add(f"| Requests recorded | {fmt_int(total)} |")
    add(f"| 5xx responses | {fmt_int(sum(p.server_errors for p in phases))} |")
    add(f"| Requests with no response | {fmt_int(sum(p.no_responses for p in phases))} |")
    add(f"| Other unexpected statuses | {fmt_int(sum(p.unexpected for p in phases))} |")
    add(f"| Total failures | {fmt_int(failures)} ({fmt_ratio(failures, total)}) |")
    add(f"| Delete issued to pod gone | "
        f"{fmt_ms(interval_ms(events, 'podDeletedAtMs', 'podGoneAtMs'))} |")
    add(f"| Delete issued to a full set of ready endpoints | "
        f"{fmt_ms(interval_ms(events, 'podDeletedAtMs', 'endpointsRestoredAtMs'))} |")
    add("")
    add("Both intervals are measured from when the delete was issued, not from when it took")
    add("effect, so both are upper bounds. The endpoint figure is also polled once a second, so")
    add("it carries up to a second of polling error on top of that.")
    add("")

    add("## By phase")
    add("")
    add("A request belongs to the phase it was *sent* in.")
    add("")
    add("| Phase | What was true | Duration | Requests | 200 | 429 | 5xx | No response | Allowed/s |")
    add("|-------|---------------|----------|----------|-----|-----|-----|-------------|-----------|")
    for phase in phases:
        add(f"| {phase.name} | {phase.description} | {fmt_seconds(phase.duration_seconds)} | "
            f"{fmt_int(len(phase.requests))} | {fmt_int(phase.allowed)} | "
            f"{fmt_int(phase.denied)} | {fmt_int(phase.server_errors)} | "
            f"{fmt_int(phase.no_responses)} | {fmt_rate(phase.allowed_per_second)} |")
    add("")

    add("### Response time by phase")
    add("")
    add("Only responses that arrived are included; a request that got none has no service")
    add("response time.")
    add("")
    add("| Phase | Responses | p50 | p95 | p99 | max |")
    add("|-------|-----------|-----|-----|-----|-----|")
    for phase in phases:
        latency = latency_summary(phase.requests)
        add(f"| {phase.name} | {fmt_int(latency['count'])} | {fmt_ms(latency['p50'])} | "
            f"{fmt_ms(latency['p95'])} | {fmt_ms(latency['p99'])} | {fmt_ms(latency['max'])} |")
    add("")

    add("## Where the requests went")
    add("")
    add("Taken from each pod's own metrics, before and after the run. This is what shows the")
    add("Service spread the load instead of sending all of it to one replica - a run that went")
    add("entirely to one pod would look identical in every table above.")
    add("")
    if not deltas:
        add(NOT_MEASURED + ": no per-pod counters were recorded.")
    else:
        add("| Pod | `/api/data` requests served |")
        add("|-----|-----------------------------|")
        for pod, delta in sorted(deltas.items()):
            if delta is None:
                add(f"| `{pod}` | {NOT_MEASURED}: deleted during the run, its counter went with it |")
            else:
                add(f"| `{pod}` | {fmt_int(delta)} |")
    add("")

    add("## What this does not show")
    add("")
    add("- **Not a node failure.** The cluster has one node. A pod was deleted; the machine under")
    add("  it never went away, and nothing here says what would happen if it did.")
    add("- **Not a capacity test.** The remaining replica carried the whole offered rate for the")
    add("  duration of the disruption, but that rate was low by design. This says nothing about")
    add("  whether one replica could carry production load.")
    add("- **One deletion, one run.** The intervals above are a single sample, not a distribution.")
    add("- **The load generator shared the machine with the cluster**, so the response times")
    add("  include contention a separate generator would not cause.")
    add("")

    add("## Environment")
    add("")
    add("| Item | Value |")
    add("|------|-------|")
    add(f"| Captured | {environment.get('capturedAt', NOT_MEASURED)} |")
    add(f"| Host | {environment.get('host', {}).get('os', NOT_MEASURED)} "
        f"({environment.get('host', {}).get('arch', NOT_MEASURED)}) |")
    add(f"| Kubernetes server | {platform.get('kubernetesServer', NOT_MEASURED)} |")
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
    add("```bash")
    add("scripts/kind-up.sh")
    add("```")
    add("")
    add("```bash")
    add("scripts/pod-kill-test.sh")
    add("```")
    add("")
    add("The request log, the per-pod counters, the event timestamps and the captured environment")
    add(f"are committed under `{results_dir.as_posix()}/`. This file is regenerated from them and")
    add("should not be edited directly.")
    add("")

    return "\n".join(lines)


def build_summary(phases: list[Phase], events: dict[str, Any],
                  deltas: dict[str, int | None]) -> dict[str, Any]:
    return {
        "totals": {
            "requests": sum(len(phase.requests) for phase in phases),
            "allowed": sum(phase.allowed for phase in phases),
            "denied": sum(phase.denied for phase in phases),
            "serverErrors": sum(phase.server_errors for phase in phases),
            "noResponse": sum(phase.no_responses for phase in phases),
            "unexpected": sum(phase.unexpected for phase in phases),
            "failures": sum(phase.failures for phase in phases),
        },
        "intervals": {
            "deleteToPodGoneMs": interval_ms(events, "podDeletedAtMs", "podGoneAtMs"),
            "deleteToFullEndpointsMs": interval_ms(events, "podDeletedAtMs",
                                                   "endpointsRestoredAtMs"),
        },
        "requestsServedByPod": deltas,
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
                "latencyMs": latency_summary(phase.requests),
            }
            for phase in phases
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    results_dir = Path(args.results)
    if not results_dir.is_dir():
        print("no such results directory: " + str(results_dir))
        return 1

    requests = parse_requests(results_dir / "requests.csv")
    events = read_json(results_dir / "events.json")
    environment = read_json(results_dir / "environment.json")
    load_summary = read_json(results_dir / "load-summary.json")
    deltas = pod_deltas(parse_pod_counts(results_dir / "pod-counts-before.txt"),
                        parse_pod_counts(results_dir / "pod-counts-after.txt"))

    phases = split_into_phases(requests, events)
    if not phases:
        print("the event log does not record a pod deletion; nothing to analyse")
        return 1

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(results_dir, environment, events, phases, deltas, load_summary),
                      encoding="utf-8")

    summary_path = results_dir / "summary.json"
    summary_path.write_text(json.dumps(build_summary(phases, events, deltas), indent=2) + "\n",
                            encoding="utf-8")

    print("  wrote " + str(output))
    print("  wrote " + str(summary_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
