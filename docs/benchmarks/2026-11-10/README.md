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
