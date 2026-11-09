# Benchmarks

**Status: pending. Nothing has been measured yet.**

No performance, latency, throughput or availability figures are published for RateLimitX
because no reproducible measurement run is committed to this repository.

Earlier revisions of the README listed p50/p95/p99 latencies, a throughput number and a
"99.9% availability" claim. Those numbers could not be traced to any committed run and were
removed rather than carried forward.

## Why there is no data yet

The Gatling simulations under `src/test/scala/loadtest/` send no `Authorization` header, so
they cannot exercise the JWT-protected `/api/**` endpoints. They are being replaced with a
simulation that registers users, logs in for tokens and injects load at a configurable target
rate.

## What a published number will require

Any figure that appears in this file must come from a committed, reproducible run:

- a run script in `scripts/` that anyone can re-execute
- raw Gatling statistics saved under `docs/benchmarks/<date>/`
- the recorded environment (CPU, RAM, OS, JVM version and flags, Docker version, algorithm,
  tier mix, user count, and whether the load generator shared the host)
- a report generated from the saved statistics, never hand-typed

Until then, every performance characteristic of this service is **not measured**.
