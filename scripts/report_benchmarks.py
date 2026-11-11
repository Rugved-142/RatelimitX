#!/usr/bin/env python3
"""Build docs/benchmarks.md from saved Gatling results.

Every number in the generated document comes from a file under docs/benchmarks/<date>/, written
by scripts/benchmark.sh. Nothing is typed by hand, and a figure that cannot be read from the
saved results is reported as "not measured" rather than estimated.

Usage:
    python3 scripts/report_benchmarks.py --results docs/benchmarks/2026-11-10 --output docs/benchmarks.md
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

NOT_MEASURED = "not measured"


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


@dataclass
class RunResult:
    """One benchmark run: the decision tally plus Gatling's own request statistics."""

    name: str
    exit_code: int | None = None
    allowed: int | None = None
    denied: int | None = None
    unexpected: int | None = None
    total_requests: int | None = None
    ok_requests: int | None = None
    ko_requests: int | None = None
    mean_rps: float | None = None
    duration_seconds: float | None = None
    percentiles: dict[str, float] = field(default_factory=dict)

    @property
    def succeeded(self) -> bool:
        """A run counts only if the process exited cleanly and nothing unexpected came back."""
        return self.exit_code == 0 and (self.unexpected or 0) == 0

    @property
    def decisions_per_second(self) -> float | None:
        """Rate limit decisions per second, allowed and denied together."""
        if self.total_requests is None or not self.duration_seconds:
            return None
        return self.total_requests / self.duration_seconds

    @property
    def allowed_per_second(self) -> float | None:
        """Requests actually admitted per second, which the per-minute caps bound."""
        if self.allowed is None or not self.duration_seconds:
            return None
        return self.allowed / self.duration_seconds

    @property
    def denied_ratio(self) -> float | None:
        if not self.total_requests:
            return None
        return (self.denied or 0) / self.total_requests


def _find_stats_json(run_dir: Path) -> Path | None:
    """Locate Gatling's stats.json, whose depth has varied between Gatling versions."""
    candidates = list(run_dir.rglob("js/stats.json")) + list(run_dir.rglob("stats.json"))
    return candidates[0] if candidates else None


def _as_float(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number


def parse_gatling_stats(stats_path: Path) -> dict[str, Any]:
    """Pull the global figures out of Gatling's stats.json.

    Only the ``stats`` block is read. Per-request breakdowns under ``contents`` are ignored
    because this simulation issues exactly one kind of request.
    """
    with stats_path.open() as handle:
        document = json.load(handle)

    stats = document.get("stats", document)
    requests = stats.get("numberOfRequests", {})

    percentiles: dict[str, float] = {}
    for key, label in (
        ("percentiles1", "p50"),
        ("percentiles2", "p75"),
        ("percentiles3", "p95"),
        ("percentiles4", "p99"),
        ("minResponseTime", "min"),
        ("maxResponseTime", "max"),
        ("meanResponseTime", "mean"),
    ):
        value = _as_float((stats.get(key) or {}).get("total"))
        if value is not None:
            percentiles[label] = value

    return {
        "total_requests": requests.get("total"),
        "ok_requests": requests.get("ok"),
        "ko_requests": requests.get("ko"),
        "mean_rps": _as_float((stats.get("meanNumberOfRequestsPerSecond") or {}).get("total")),
        "percentiles": percentiles,
    }


def parse_decisions(decisions_path: Path) -> dict[str, Any]:
    with decisions_path.open() as handle:
        return json.load(handle)


def _duration_from_simulation_log(run_dir: Path) -> float | None:
    """Wall-clock duration of the run, taken from the first and last log record.

    Gatling's stats.json reports a mean rate but not the window it covers, and the injection
    profile here is stepped, so the duration has to come from the raw log.
    """
    logs = list(run_dir.rglob("simulation.log"))
    if not logs:
        return None

    first: int | None = None
    last: int | None = None
    try:
        with logs[0].open(errors="replace") as handle:
            for line in handle:
                for field_value in line.rstrip("\n").split("\t"):
                    if len(field_value) == 13 and field_value.isdigit():
                        timestamp = int(field_value)
                        first = timestamp if first is None else min(first, timestamp)
                        last = timestamp if last is None else max(last, timestamp)
                        break
    except OSError:
        return None

    if first is None or last is None or last <= first:
        return None
    return (last - first) / 1000.0


def load_run(run_dir: Path) -> RunResult:
    result = RunResult(name=run_dir.name)

    exit_code_file = run_dir / "exit-code.txt"
    if exit_code_file.is_file():
        text = exit_code_file.read_text().strip()
        if text.lstrip("-").isdigit():
            result.exit_code = int(text)

    decisions_file = run_dir / "decisions.json"
    if decisions_file.is_file():
        decisions = parse_decisions(decisions_file)
        result.allowed = decisions.get("allowed")
        result.denied = decisions.get("denied")
        result.unexpected = decisions.get("unexpected")

    stats_path = _find_stats_json(run_dir)
    if stats_path is not None:
        stats = parse_gatling_stats(stats_path)
        result.total_requests = stats["total_requests"]
        result.ok_requests = stats["ok_requests"]
        result.ko_requests = stats["ko_requests"]
        result.mean_rps = stats["mean_rps"]
        result.percentiles = stats["percentiles"]

    result.duration_seconds = _duration_from_simulation_log(run_dir)

    # Fall back to the tally when Gatling's stats are missing, so a run is not silently dropped.
    if result.total_requests is None and result.allowed is not None:
        result.total_requests = (result.allowed or 0) + (result.denied or 0) + (result.unexpected or 0)

    return result


def load_results(results_dir: Path) -> tuple[dict[str, Any], list[RunResult]]:
    environment_file = results_dir / "environment.json"
    environment = json.loads(environment_file.read_text()) if environment_file.is_file() else {}

    runs = [load_run(path) for path in sorted(results_dir.glob("run-*")) if path.is_dir()]
    return environment, runs


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------


def fmt_number(value: float | int | None, digits: int = 1) -> str:
    if value is None:
        return NOT_MEASURED
    if isinstance(value, int) or float(value).is_integer():
        return f"{int(value):,}"
    return f"{value:,.{digits}f}"


def fmt_percent(value: float | None) -> str:
    return NOT_MEASURED if value is None else f"{value * 100:.1f}%"


def fmt_bytes_as_gib(value: int | None) -> str:
    if not value:
        return NOT_MEASURED
    return f"{value / (1024 ** 3):.1f} GiB"


def summarise(values: Iterable[float | None]) -> str:
    """Median across the usable runs, with the spread when the runs actually differ.

    The range is omitted when every run produced the same figure, since "50 (min 50, max 50)"
    only adds noise.
    """
    usable = [v for v in values if v is not None]
    if not usable:
        return NOT_MEASURED
    low, high = min(usable), max(usable)
    if fmt_number(low) == fmt_number(high):
        return fmt_number(low)
    return f"{fmt_number(statistics.median(usable))} (min {fmt_number(low)}, max {fmt_number(high)})"


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def offered_load(parameters: dict[str, Any]) -> tuple[int, float, float] | None:
    """Requests the injection profile offers, how long it runs, and the average rate.

    Open-model injection imposes an arrival rate, so the offered total is arithmetic from the
    profile rather than something to be measured: the warm-up plus each step. It matters because
    when the service answers all of it, the measured decisions per second is that arrival rate
    and nothing more - see the note this feeds in render().
    """
    try:
        warmup = float(parameters["warmupSeconds"])
        start = float(parameters["rpsStart"])
        step = float(parameters["rpsStep"])
        steps = int(parameters["rpsSteps"])
        step_seconds = float(parameters["stepSeconds"])
    except (KeyError, TypeError, ValueError):
        return None
    if steps < 1:
        return None
    total = warmup * start
    for index in range(steps):
        total += step_seconds * (start + index * step)
    duration = warmup + steps * step_seconds
    if duration <= 0:
        return None
    return int(round(total)), duration, total / duration


def render(environment: dict[str, Any], runs: list[RunResult], results_dir: Path) -> str:
    successful = [run for run in runs if run.succeeded]
    parameters = environment.get("parameters", {})
    host = environment.get("host", {})
    service = environment.get("service", {})
    docker = environment.get("docker", {})
    repository = environment.get("repository", {})
    load_generator = environment.get("loadGenerator", {})

    lines: list[str] = []
    add = lines.append

    add("# Benchmarks")
    add("")
    add("<!-- Generated by scripts/report_benchmarks.py. Do not edit by hand: regenerate it with")
    add("     scripts/benchmark.sh, which rewrites this file from the saved run data. -->")
    add("")

    if not runs:
        add("**Status: pending. Nothing has been measured yet.**")
        add("")
        add("No benchmark runs are committed under `docs/benchmarks/`, so this file has no data to")
        add("report. Run `scripts/benchmark.sh` to produce some.")
        add("")
        return "\n".join(lines)

    add(f"Generated from `{results_dir.as_posix()}`, "
        f"{len(successful)} of {len(runs)} runs usable.")
    add("")

    # --- how to read it -----------------------------------------------------
    add("## How to read these numbers")
    add("")
    add("The service is a rate limiter, so a 429 is a correct answer and not a failure. That makes")
    add("a single throughput figure misleading, and two separate ones are reported instead:")
    add("")
    add("- **Decisions per second** is how many requests the service made a rate limit decision")
    add("  about, allowed and denied together. This is the throughput of the service itself.")
    add("- **Allowed per second** is how many requests were actually admitted. This is bounded by")
    add("  the configured per-minute caps and by the number of users, not by the service's")
    add("  capacity.")
    add("")
    user_count = parameters.get("userCount")
    tier_mix = parameters.get("tierMix", {})
    if tier_mix and user_count:
        ceiling = sum(
            count * {"USER": 10, "PREMIUM": 100, "ADMIN": 1000}.get(tier, 0)
            for tier, count in tier_mix.items()
        )
        add(f"With this run's mix of {user_count} users, the combined cap is {fmt_number(ceiling)}")
        add(f"requests per minute, about {fmt_number(ceiling / 60)} per second. Any offered load")
        add("above that is denied by design, so a high denial ratio here is the limiter working,")
        add("not the service struggling.")
        add("")
    add("Latency percentiles cover every response, allowed and denied alike. A denial is cheaper")
    add("to produce than an admission, so a heavily denied run has a lower latency profile than a")
    add("lightly loaded one; the two are not comparable.")
    add("")

    # The figure readers are most likely to misuse. Say what it is before showing it.
    offered = offered_load(parameters)
    answered = successful and all(
        (run.ko_requests or 0) == 0 and (run.unexpected or 0) == 0 for run in successful)
    add("### Decisions per second is not a capacity figure")
    add("")
    if offered is None:
        add("The load profile for this run was not recorded in full, so the offered rate cannot be")
        add(f"derived from it: {NOT_MEASURED}. Treat the throughput figure below as a floor.")
    else:
        total_offered, duration, rate = offered
        add("Injection is open-model, so the arrival rate is imposed rather than observed. This")
        add(f"profile offers {fmt_number(total_offered, 0)} requests over "
            f"{fmt_number(duration, 0)} seconds, an average of")
        add(f"**{fmt_number(rate)} per second**.")
        add("")
        if answered:
            add("Every one of them was answered - no failures and no unexpected statuses in any")
            add("usable run - so the decisions per second below is that offered rate and nothing")
            add("more. It says the service kept up with the load it was given. It does not say")
            add("what the service's limit is, because the load never found one: raising the")
            add("offered rate would raise the figure. Finding a ceiling means driving the service")
            add("until something gives, which this benchmark does not do.")
        else:
            add("Not every offered request was answered in every run, so the decisions per second")
            add("below is lower than the offered rate. Check the per-run table for which runs")
            add("were affected before reading the summary.")
    add("")

    # --- results ------------------------------------------------------------
    add("## Results")
    add("")
    if successful:
        add("Median across the usable runs, with the range in brackets.")
        add("")
        add("| Measure | Value |")
        add("|---------|-------|")
        add(f"| Decisions per second | {summarise(r.decisions_per_second for r in successful)} |")
        add(f"| Allowed per second | {summarise(r.allowed_per_second for r in successful)} |")
        add(f"| Denied share of decisions (%) | "
            f"{summarise((r.denied_ratio or 0) * 100 for r in successful)} |")
        for label in ("p50", "p75", "p95", "p99", "max"):
            values = [r.percentiles.get(label) for r in successful]
            if any(v is not None for v in values):
                add(f"| Response time {label} (ms) | {summarise(values)} |")
        add(f"| Failed requests | {summarise(float(r.ko_requests or 0) for r in successful)} |")
        add("")
    else:
        add("No run completed cleanly, so there is nothing to report. See the per-run table below.")
        add("")

    add("### Per run")
    add("")
    add("| Run | Status | Decisions | Allowed | Denied | Unexpected | Duration (s) | Decisions/s | Allowed/s |")
    add("|-----|--------|-----------|---------|--------|------------|--------------|-------------|-----------|")
    for run in runs:
        status = "ok" if run.succeeded else f"FAILED (exit {run.exit_code})"
        add(
            f"| {run.name} | {status} | {fmt_number(run.total_requests)} | "
            f"{fmt_number(run.allowed)} | {fmt_number(run.denied)} | "
            f"{fmt_number(run.unexpected)} | {fmt_number(run.duration_seconds)} | "
            f"{fmt_number(run.decisions_per_second)} | {fmt_number(run.allowed_per_second)} |"
        )
    add("")

    unexpected_total = sum(r.unexpected or 0 for r in runs)
    if unexpected_total:
        add(f"**{fmt_number(unexpected_total)} responses were neither 200 nor 429.** Those are")
        add("failures: the service stopped making decisions. Any figures above are reported from")
        add("the clean runs only, and the run that produced them is marked FAILED.")
        add("")

    # --- load shape ---------------------------------------------------------
    add("## Load profile")
    add("")
    add("Open-model injection: the arrival rate is imposed regardless of how fast the service")
    add("responds, so a slowdown shows up as a queue rather than as reduced load.")
    add("")
    add("| Parameter | Value |")
    add("|-----------|-------|")
    add(f"| Algorithm | {parameters.get('algorithm', NOT_MEASURED)} |")
    add(f"| Users | {fmt_number(user_count)} |")
    if tier_mix:
        mix = ", ".join(f"{count} x {tier}" for tier, count in sorted(tier_mix.items()))
        add(f"| Tier mix | {mix} |")
    add(f"| Warm-up | {fmt_number(parameters.get('warmupSeconds'))} s at "
        f"{fmt_number(parameters.get('rpsStart'))} req/s |")
    steps = parameters.get("rpsSteps")
    if steps:
        start = parameters.get("rpsStart", 0)
        step = parameters.get("rpsStep", 0)
        targets = ", ".join(str(start + i * step) for i in range(steps))
        add(f"| Steps | {steps} x {fmt_number(parameters.get('stepSeconds'))} s at {targets} req/s |")
    add(f"| Runs | {len(runs)} |")
    add("")

    # --- environment --------------------------------------------------------
    add("## Environment")
    add("")
    add("| Item | Value |")
    add("|------|-------|")
    add(f"| Captured | {environment.get('capturedAt', NOT_MEASURED)} |")
    add(f"| OS | {host.get('os', NOT_MEASURED)} |")
    add(f"| Kernel | {host.get('kernel', NOT_MEASURED)} |")
    add(f"| Architecture | {host.get('arch', NOT_MEASURED)} |")
    add(f"| CPU | {host.get('cpuModel', NOT_MEASURED)} ({host.get('cpuCount', '?')} logical) |")
    add(f"| Memory | {fmt_bytes_as_gib(host.get('memoryBytes'))} |")
    add(f"| Service JVM | {service.get('javaVersion', NOT_MEASURED)} |")
    entrypoint = service.get("entrypoint")
    add(f"| Service entrypoint | `{' '.join(entrypoint)}` |" if entrypoint
        else f"| Service entrypoint | {NOT_MEASURED} |")
    memory_limit = service.get("containerMemoryLimitBytes")
    add(f"| Container memory limit | "
        f"{'unlimited (host default)' if not memory_limit else fmt_bytes_as_gib(memory_limit)} |")
    cpu_limit = service.get("containerNanoCpus")
    add(f"| Container CPU limit | "
        f"{'unlimited (host default)' if not cpu_limit else fmt_number(cpu_limit / 1e9)} |")
    add(f"| Docker engine | {docker.get('engine', NOT_MEASURED)} |")
    add(f"| Docker Compose | {docker.get('compose', NOT_MEASURED)} |")
    add(f"| Load generator JVM | {load_generator.get('jvm', NOT_MEASURED)} |")
    shares_host = load_generator.get("sharesHostWithService")
    add(f"| Load generator location | "
        f"{'same host as the service' if shares_host else 'separate host'} |")
    add(f"| Commit | `{repository.get('commit', NOT_MEASURED)}` |")
    if repository.get("uncommittedChanges"):
        add("| Source at run time | **had uncommitted changes**, so this run is not reproducible "
            "from the commit alone |")
    else:
        add("| Source at run time | committed, excluding this run's own output |")
    add("")

    if shares_host:
        add("The load generator ran on the same machine as the service, so Gatling and the")
        add("application competed for the same CPUs. That caps the achievable rate and inflates")
        add("latency compared with driving the service from a separate host. These numbers are a")
        add("floor, not a capacity figure.")
        add("")

    # --- reproduction -------------------------------------------------------
    add("## Reproducing this")
    add("")
    add("```bash")
    add("docker compose up -d --build")
    add("```")
    add("")
    add("```bash")
    add(f"scripts/benchmark.sh --runs {len(runs)} --algorithm {parameters.get('algorithm', 'sliding-window')}")
    add("```")
    add("")
    add(f"Raw Gatling output, the decision tallies and the captured environment are committed under")
    add(f"`{results_dir.as_posix()}/`. This file is regenerated from them and should not be edited")
    add("directly.")
    add("")

    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path,
                        help="directory holding environment.json and run-* subdirectories")
    parser.add_argument("--output", required=True, type=Path,
                        help="markdown file to write")
    args = parser.parse_args(argv)

    if not args.results.is_dir():
        print(f"no such results directory: {args.results}", file=sys.stderr)
        return 1

    environment, runs = load_results(args.results)
    markdown = render(environment, runs, args.results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(markdown)

    usable = sum(1 for run in runs if run.succeeded)
    print(f"  wrote {args.output} from {len(runs)} runs ({usable} usable)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
