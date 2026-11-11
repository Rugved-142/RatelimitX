# 4. Keep Redis out of the readiness probe

Date: 2026-11-10

## Status

Accepted.

## Context

Spring Boot's `/actuator/health` aggregates every health indicator, Redis included. The obvious
thing to point a Kubernetes readiness probe at is `/actuator/health`.

Doing that would mean: Redis becomes unreachable, the Redis health indicator goes DOWN,
`/actuator/health` goes DOWN, every pod fails its readiness probe, Kubernetes removes every pod
from the Service, and the Service has no endpoints. Callers get connection failures.

The service at that moment is working. The circuit breaker has opened, the local fallback is
answering, and `docs/failure-test-kubernetes.md` records 2,800 requests through a 40-second Redis
freeze with zero failures. A readiness probe on `/actuator/health` would have converted that into
a total outage.

## Decision

All three probes use the health **groups** that Boot publishes when
`management.endpoint.health.probes.enabled=true`:

- `startupProbe` and `livenessProbe` → `/actuator/health/liveness`
- `readinessProbe` → `/actuator/health/readiness`

Those groups contain the application's own liveness and readiness state and nothing else. A pod
serving correct answers from the fallback is ready, which is the intended behaviour.

## Consequences

A Redis outage degrades the enforced limit (ADR 3) rather than taking the service out of the load
balancer. The two failure modes are kept separate: liveness means "this process is broken, restart
it", readiness means "do not send this pod traffic", and neither means "a dependency is unwell".

The dependency's state is not lost - it is still in `/actuator/health`, still scraped into
Prometheus, and still alerted on. It simply does not control traffic routing.

The risk is the inverse case: a pod that cannot reach Redis *and* cannot fall back would stay in
the Service while failing. That is covered by `RateLimitXServerErrors`, which fires on any 5xx,
and by the failure test asserting there are none.

`scripts/test_k8s_manifests.py` fails if a probe is ever pointed at `/actuator/health`, because
the reasoning above is not visible from the manifest alone.
