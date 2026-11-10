# 1. Serve actuator on a separate port that is not published

Date: 2026-11-10

## Status

Accepted.

## Context

Actuator exposes `/actuator/health`, `/actuator/prometheus` and, if enabled, a good deal more.
Prometheus has to scrape it. Nobody on the internet has to reach it. The original configuration
served it on the application port with no restriction, so anything that could reach the API could
read the metrics, including per-endpoint request counts and JVM internals.

Two ways to fix it:

1. **Keep actuator on the application port and require ADMIN** for everything except
   `/actuator/health`, which stays public without details.
2. **Move the management server to its own port** and do not publish that port, so actuator is
   reachable only from inside the container network.

## Decision

Option 2. `management.server.port=9091`, and neither `docker-compose.yml` nor either Kubernetes
Service publishes it. Prometheus scrapes `app:9091` over the internal network.

## Consequences

The attack surface is removed rather than guarded: there is no request an outside client can
send to reach actuator, so there is no authorisation rule to get wrong, and no risk that adding
an endpoint later quietly exposes it because it did not match a path pattern.

The cost is that operators cannot curl the health endpoint from the host. They have to go
through the container (`docker exec ratelimitx-app curl localhost:9091/actuator/health`) or
through the cluster. The container `HEALTHCHECK` and the Kubernetes probes both run inside the
container, so neither is affected.

Option 1 was rejected because it is one misconfigured matcher away from being wrong, and because
a public `/actuator/health` still tells an unauthenticated caller whether the database is up.
`scripts/smoke-test.sh` asserts that port 9091 does **not** answer from the host, so if this ever
regresses a test says so.

The management port is still published inside the cluster on the ClusterIP Service, because an
in-cluster Prometheus needs it. It is deliberately absent from the NodePort Service.
