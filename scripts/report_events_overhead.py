#!/usr/bin/env python3
"""Compares two committed benchmark result sets: the analytics feed off, and on.

Writes docs/analytics-overhead.md. Every figure in that document is read from the committed raw
data; nothing here is typed by hand, and a figure that cannot be read is reported as "not
measured" rather than guessed at.

The comparison is only meaningful if both sets were produced with the same load shape, so the
load parameters are compared first and a mismatch is refused outright - a difference between two
different shapes is not an overhead measurement.

Usage:
  python3 -I scripts/report_events_overhead.py \\
      --off docs/benchmarks/<date>-events-off \\
      --on  docs/benchmarks/<date>-events-on \\
      --output docs/analytics-overhead.md
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

# Load parameters that must match between the two sets for the comparison to mean anything.
# eventsEnabled and analyticsEnabled are deliberately excluded: they are what differs.
SHAPE_KEYS = [
    "algorithm",
    "tierMix",
    "userCount",
    "rpsStart",
    "rpsStep",
    "rpsSteps",
    "stepSeconds",
    "warmupSeconds",
    "runs",
]

# Gatling's percentile keys, in the order its own reports use them.
PERCENTILES = {
    "percentiles1": "p50",
    "percentiles2": "p75",
    "percentiles3": "p95",
    "percentiles4": "p99",
}

NOT_MEASURED = "not measured"


class ResultSet:
    """One benchmark directory: its environment and every run inside it."""

    def __init__(self, directory: Path):
        self.directory = directory
        self.environment = self._read_json(directory / "environment.json")
        self.runs = []
        for run_dir in sorted(p for p in directory.glob("run-*") if p.is_dir()):
            stats = self._read_json(run_dir / "gatling" / "js" / "global_stats.json")
            decisions = self._read_json(run_dir / "decisions.json")
            if stats is not None:
                self.runs.append({"name": run_dir.name, "stats": stats, "decisions": decisions})

    @staticmethod
    def _read_json(path: Path):
        if not path.is_file():
            return None
        with path.open() as handle:
            return json.load(handle)

    @property
    def label(self) -> str:
        if not self.environment:
            return NOT_MEASURED
        return self.environment.get("label") or NOT_MEASURED

    @property
    def parameters(self) -> dict:
        return (self.environment or {}).get("parameters", {})

    @property
    def commit(self) -> str:
        return (self.environment or {}).get("repository", {}).get("commit", NOT_MEASURED)

    @property
    def dirty(self):
        return (self.environment or {}).get("repository", {}).get("uncommittedChanges")

    def feed_state(self, key: str):
        """Whether the feed was on, as recorded from the running container."""
        return self.parameters.get(key)

    def metric(self, key: str) -> list:
        """One Gatling figure per run, in run order."""
        values = []
        for run in self.runs:
            node = run["stats"].get(key)
            if isinstance(node, dict) and "total" in node:
                values.append(node["total"])
        return values

    def requests(self) -> list:
        return [run["stats"]["numberOfRequests"]["total"] for run in self.runs
                if "numberOfRequests" in run["stats"]]

    def failures(self) -> list:
        return [run["stats"]["numberOfRequests"]["ko"] for run in self.runs
                if "numberOfRequests" in run["stats"]]

    def unexpected(self) -> list:
        return [run["decisions"]["unexpected"] for run in self.runs
                if run["decisions"] and "unexpected" in run["decisions"]]


def fmt(value) -> str:
    if value is None:
        return NOT_MEASURED
    if isinstance(value, float):
        return f"{value:.1f}"
    return f"{value:,}"


def fmt_mean(value) -> str:
    """A mean across runs, always to one decimal: a column that mixes "2" and "1.7" reads as
    though the two were measured to different precision."""
    if value is None:
        return NOT_MEASURED
    return f"{float(value):.1f}"


def mean_or_none(values):
    return statistics.mean(values) if values else None


def signed(value) -> str:
    """A difference, with its sign kept: the direction is the point."""
    if value is None:
        return NOT_MEASURED
    if abs(value) < 0.05:
        return "0"
    return f"{value:+.1f}"


def describe_tier_mix(mix) -> str:
    """The tier mix as prose. It is recorded as an object by the benchmark script and as a string
    by older runs, and a raw dict printed into a document reads as a bug."""
    if mix is None:
        return NOT_MEASURED
    if isinstance(mix, dict):
        return ", ".join(f"{count} {tier}" for tier, count in mix.items())
    return str(mix)


def compare_shapes(off: ResultSet, on: ResultSet) -> list:
    """@return the keys whose load parameters differ."""
    return [key for key in SHAPE_KEYS
            if off.parameters.get(key) != on.parameters.get(key)]


def build_report(off: ResultSet, on: ResultSet) -> str:
    lines = []
    add = lines.append

    add("# Analytics feed: measured overhead")
    add("")
    add("Generated by `scripts/report_events_overhead.py` from the committed raw data under")
    add(f"`{off.directory}` and `{on.directory}`. No figure here is typed by hand.")
    add("")
    add("## What was run")
    add("")
    add("| | Feed off | Feed on |")
    add("|---|---|---|")
    add(f"| Result data | `{off.directory}` | `{on.directory}` |")
    add(f"| Label | {off.label} | {on.label} |")
    add(f"| `ratelimit.events.enabled` | {off.feed_state('eventsEnabled')} | "
        f"{on.feed_state('eventsEnabled')} |")
    add(f"| `ratelimit.analytics.enabled` | {off.feed_state('analyticsEnabled')} | "
        f"{on.feed_state('analyticsEnabled')} |")
    add(f"| Commit | `{off.commit[:12]}` | `{on.commit[:12]}` |")
    add(f"| Uncommitted changes | {off.dirty} | {on.dirty} |")
    add(f"| Runs | {len(off.runs)} | {len(on.runs)} |")
    add("")

    mismatches = compare_shapes(off, on)
    if mismatches:
        add("## The two sets are not comparable")
        add("")
        add("The load shape differs between them, so the difference below would not be an")
        add("overhead measurement. Differing parameters:")
        add("")
        for key in mismatches:
            add(f"- `{key}`: off = `{off.parameters.get(key)}`, on = `{on.parameters.get(key)}`")
        add("")
        return "\n".join(lines) + "\n"

    shape = off.parameters
    add("Both sets used the same load shape: "
        f"{shape.get('userCount', NOT_MEASURED)} users as "
        f"{describe_tier_mix(shape.get('tierMix'))}, the `{shape.get('algorithm', NOT_MEASURED)}` "
        f"algorithm, {shape.get('warmupSeconds', NOT_MEASURED)}s warmup then "
        f"{shape.get('rpsSteps', NOT_MEASURED)} steps of "
        f"{shape.get('stepSeconds', NOT_MEASURED)}s starting at "
        f"{shape.get('rpsStart', NOT_MEASURED)} req/s and rising by "
        f"{shape.get('rpsStep', NOT_MEASURED)} req/s.")
    add("")

    add("## Response time, in milliseconds")
    add("")
    add("Each figure is the mean across runs of that run's own Gatling statistic. The spread row")
    add("is the range across runs within one set, which is what tells you whether a difference")
    add("between the two sets is larger than this machine's own variation.")
    add("")
    add("| Statistic | Feed off | Feed on | Difference |")
    add("|---|---|---|---|")

    rows = [("mean", "meanResponseTime"), ("max", "maxResponseTime")]
    rows += [(name, key) for key, name in PERCENTILES.items()]

    for name, key in rows:
        off_values = off.metric(key)
        on_values = on.metric(key)
        off_mean = mean_or_none(off_values)
        on_mean = mean_or_none(on_values)
        difference = None if off_mean is None or on_mean is None else on_mean - off_mean
        add(f"| {name} | {fmt_mean(off_mean)} | {fmt_mean(on_mean)} | {signed(difference)} |")

    for name, key in rows:
        off_values = off.metric(key)
        on_values = on.metric(key)
        if off_values and on_values:
            add(f"| {name} spread across runs | {min(off_values)}-{max(off_values)} | "
                f"{min(on_values)}-{max(on_values)} | |")

    add("")
    add("## Requests and failures")
    add("")
    add("| | Feed off | Feed on |")
    add("|---|---|---|")
    add(f"| Requests, total across runs | {fmt(sum(off.requests()))} | "
        f"{fmt(sum(on.requests()))} |")
    add(f"| Gatling failures (ko) | {fmt(sum(off.failures()))} | {fmt(sum(on.failures()))} |")
    add(f"| Unexpected responses | {fmt(sum(off.unexpected()))} | {fmt(sum(on.unexpected()))} |")
    add("")

    add("## What this does and does not say")
    add("")
    add("- The load generator shared a machine with the service, so the two competed for CPU.")
    add("  Turning the feed on adds a thread to the same contended machine, so any difference")
    add("  here includes that contention and is not a measurement of the feed alone.")
    add("- A difference smaller than the spread across runs within either set is not a signal.")
    add("- This is a few minutes of synthetic load on one developer machine. It supports a")
    add("  statement about these runs and nothing wider: no capacity figure, no per-request cost,")
    add("  and nothing about behaviour over time.")
    add("- The offered rate never saturated the service, so this compares two unsaturated runs.")
    add("  It says nothing about the feed's cost at a rate high enough to queue.")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--off", required=True, type=Path)
    parser.add_argument("--on", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    for directory in (args.off, args.on):
        if not directory.is_dir():
            print(f"no such results directory: {directory}", file=sys.stderr)
            return 1

    off = ResultSet(args.off)
    on = ResultSet(args.on)
    if not off.runs or not on.runs:
        print("both result sets need at least one run with Gatling statistics", file=sys.stderr)
        return 1

    args.output.write_text(build_report(off, on))
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
