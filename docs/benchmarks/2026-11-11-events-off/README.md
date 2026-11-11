# Benchmark run data

Raw output from `scripts/benchmark.sh`. `docs/benchmarks.md` is generated from these files by
`scripts/report_benchmarks.py`; that file is generated and should not be edited by hand.

| File | What it is |
|------|------------|
| `environment.json` | Machine, container, Docker and load parameters captured before the first run |
| `run-N/decisions.json` | Allowed, denied and unexpected response tallies, written by the simulation |
| `run-N/exit-code.txt` | Exit status of the Gatling process for that run |
| `run-N/gatling-output.log` | Console output of the run |
| `run-N/gatling/simulation.log` | Gatling's raw per-request record |
| `run-N/gatling/js/*.json` | Gatling's computed statistics |

The HTML report's own assets, a vendored charting library of a few hundred kilobytes per run, are
not kept: they are third-party code rather than measurement data. Gatling can rebuild the HTML
from `simulation.log`.

## What this run is

One half of an A/B pair measuring the analytics feed, with the feed **off**. The other half is
`docs/benchmarks/2026-11-11-events-on`, and the comparison is
[docs/analytics-overhead.md](../../analytics-overhead.md).

This pair is not the project's baseline benchmark: `docs/benchmarks.md` is still generated from
`docs/benchmarks/2026-11-10`, the Spring Boot 4.0.9 baseline, and the figures `docs/slo.md`
quotes come from there. These two sets exist to be compared with each other, on one machine
within a few minutes, which is the only thing that makes an A/B worth reading.
