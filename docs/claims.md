# Claims and evidence

Every factual claim the [README](../README.md) makes about this service, and the thing that backs
it. A claim that could not be mapped to a test, a script or committed run data was removed from
the README rather than listed here as unverified.

How to read the Evidence column:

- a **test class** runs under `./mvnw -B verify`
- a **pytest file** runs under `python3 -m pytest scripts -q`
- a **script** can be run by hand; where it writes raw data, the data is committed
- **configuration** means the claim is a setting, checkable by reading the named file

`scripts/test_docs_consistency.py` checks that every path named in this document exists, so a
file that moves cannot leave a dangling reference here.

## What the service does

| Claim | Evidence |
|-------|----------|
| A caller authenticates with a JWT and gets a per-minute allowance from their tier | `ApiRateLimitIT` |
| USER 10, PREMIUM 100, ADMIN 1000 requests per minute by default | `Role`, where the tier defaults are declared; enforced limits asserted in `ApiRateLimitIT` and `AdminUserLimitIT` |
| A denied request gets 429 with `Retry-After` | `ApiRateLimitIT`; also asserted by `scripts/smoke-test.sh` |
| `Retry-After` is whole seconds, rounded up, never below 1 | `RateLimitResult.retryAfterSeconds()`; asserted in `ApiRateLimitIT` and `scripts/smoke-test.sh` |
| `X-RateLimit-Reset` is milliseconds for every algorithm | `RateLimitResult`; asserted per algorithm in `ApiRateLimitIT` |
| `X-RateLimit-Limit` reports the limit actually enforced | `ApiController` returns `result.getLimit()`; `AdminUserLimitIT` measures the enforced limit on `/api/data` |
| A 429 is counted as a correct answer, not a failure, everywhere in this repository | `scripts/analyze_failure_test.py` (`Request.is_failure`), covered by `scripts/test_analyze_failure_test.py` |

## Rate limiting

| Claim | Evidence |
|-------|----------|
| Three algorithms: fixed window, token bucket, sliding window | `RateLimitConfig.SUPPORTED_ALGORITHMS`; each exercised in `RateLimitAlgorithmIT` |
| Each decision is a single atomic Lua script | The `EVAL` in `RateLimiterService`, `TokenBucketService`, `SlidingWindowService` |
| Concurrent requests cannot push the count past the limit | `RateLimitAlgorithmIT` — 200 threads on one key, allowed count must equal the limit exactly |
| The clock comes from Redis, not the application | `redis.call('TIME')` in each script; [ADR 2](adr/0002-redis-time-as-the-clock.md) |
| Token bucket capacity is the caller's tier limit, refilling at limit/60 per second | `TokenBucketService`; tier behaviour asserted in `RateLimitAlgorithmIT` |
| Keys carry a per-user hash tag so a Redis Cluster co-locates them | `RateLimitKeys` |
| Windows roll over and buckets refill correctly | `WindowRolloverIT` — real waiting over 2-second windows |
| The algorithm can be switched at runtime | `POST /admin/algorithm`; `RateLimitConfigTest` covers validation and concurrent switching |
| An unknown algorithm name is rejected and changes nothing | `RateLimitConfig.setAlgorithmChecked`; `RateLimitConfigTest` |
| A script returning null throws rather than guessing allow or deny | `RateLimitScriptException`, thrown from each service |

## Security

| Claim | Evidence |
|-------|----------|
| The application refuses to start without a `JWT_SECRET` of at least 32 bytes | `JwtProperties.validate()`; `JwtPropertiesTest` |
| No secret has a default value | `application.properties` contains none; `.env.example` holds placeholders only; Compose uses `${VAR:?message}` |
| No Kubernetes Secret is committed | `scripts/kind-up.sh` generates it with `--from-literal`; `scripts/test_k8s_manifests.py` fails if a secret-looking file under `k8s/` is ever tracked |
| An unauthenticated request gets 401, not 403 | `RestAuthenticationEntryPoint`; `SecurityIT`; `scripts/smoke-test.sh` |
| Forged, expired and orphaned tokens are rejected | `SecurityIT` |
| A USER cannot reach `/admin` | `SecurityIT`; `AdminUserLimitIT`; `scripts/smoke-test.sh` |
| `/auth/login` and `/auth/register` are capped at 5 per minute per client IP | `AuthThrottleProperties`; `AuthThrottleIT` |
| `X-Forwarded-For` is trusted only when explicitly configured | `ClientIpResolver`; `AuthThrottleIT` |
| Errors are JSON; a bare exception never becomes a 500 with a stack trace | `GlobalExceptionHandler`; `SecurityIT` |
| Actuator is not reachable from the host | `management.server.port=9091`, published by nothing; asserted by `scripts/smoke-test.sh`; [ADR 1](adr/0001-actuator-on-a-separate-unpublished-port.md) |
| Every published port binds to 127.0.0.1 | `docker-compose.yml` |
| The container runs as a non-root user | `Dockerfile`; for Kubernetes, `scripts/test_k8s_manifests.py` |
| Metrics carry no per-user label | `PrometheusMetricsService` — every tag has a small fixed set of values |
| The image has no fixable CRITICAL or HIGH vulnerability | The Trivy gate in `.github/workflows/ci.yml`, which fails the build on a fixable CRITICAL |

## Resilience

| Claim | Evidence |
|-------|----------|
| A circuit breaker opens after three consecutive Redis failures | `CircuitBreakerProperties`; `CircuitBreakerTest` |
| Only Redis connectivity and timeout failures count toward opening it | `RedisFailureClassifier`; `CircuitBreakerTest` |
| Exactly one probe runs in HALF_OPEN; everything else uses the fallback | `CircuitBreaker.executeHalfOpen`; `CircuitBreakerTest` under concurrency |
| The service keeps answering while Redis is unreachable | `RedisOutageIT`; measured in [failure-test.md](failure-test.md) and [failure-test-kubernetes.md](failure-test-kubernetes.md) |
| No 5xx and no dropped connections during a Redis outage | Measured: `docs/failure-tests/*/*/summary.json`, reported in the two failure test documents |
| The fallback's limits are per instance, and a failover grants a fresh window | Measured: the admission rate per phase in `docs/failure-tests/compose/*/summary.json`; [ADR 3](adr/0003-per-instance-fallback.md) |
| Responses served by the fallback say so in `X-Algorithm` | `ResilientRateLimiter.getCurrentMode()`; counted in the failure test reports |
| The breaker returns to CLOSED on its own once Redis answers | `CircuitBreakerTest`; measured in both failure test documents |
| Time to close is paced by the breaker's open timeout, not by Redis | Measured: `lastObservedOpenBeforeUnpauseToClosedMs` in the failure test summaries |
| The local fallback admits atomically and evicts stale counters | `LocalRateLimiterTest` — 200 threads, plus eviction |
| Redis timeouts are 200ms so an outage trips the breaker quickly | `application.properties` (`spring.data.redis.timeout`) |

## Kubernetes

| Claim | Evidence |
|-------|----------|
| Two application replicas behind a Service | `k8s/30-app.yaml`; `scripts/test_k8s_manifests.py` |
| Single-replica Redis and PostgreSQL StatefulSets with volumes | `k8s/20-postgres.yaml`, `k8s/21-redis.yaml`; `scripts/test_k8s_manifests.py` |
| Probes use the health groups, not `/actuator/health` | `k8s/30-app.yaml`; `scripts/test_k8s_manifests.py`; [ADR 4](adr/0004-readiness-excludes-redis.md) |
| Every container declares resource requests and limits | `scripts/test_k8s_manifests.py` |
| Nothing runs as root and no container may escalate privileges | `scripts/test_k8s_manifests.py` |
| Graceful shutdown fits inside the termination grace period | `application.properties` and `k8s/30-app.yaml`; `scripts/test_k8s_manifests.py` checks the arithmetic |
| A rollout never drops below two replicas | `maxUnavailable: 0`; `scripts/test_k8s_manifests.py` |
| Deleting a replica under load costs no requests | Measured: [pod-kill-test.md](pod-kill-test.md) and `docs/pod-kill-tests/*/summary.json` |
| Load reaches both replicas rather than one | Measured: the per-pod counters in the pod kill run data |
| The manifests deploy and serve traffic in CI | The `kubernetes` job in `.github/workflows/ci.yml` |

## Monitoring

| Claim | Evidence |
|-------|----------|
| The listed metrics are exported | `scripts/smoke-test.sh` asserts three of them against a running service |
| `app_circuitbreaker_state` reflects the breaker's real state | `CircuitBreakerMetrics` binds the gauge to `getState()`; `CircuitBreakerTest` covers the state machine |
| Seven alert rules exist, each recording where its threshold came from | `prometheus/rules/ratelimitx.rules.yml`; `scripts/test_docs_consistency.py` checks the rule list against `slo.md` |
| The alert rules parse and load | `promtool` in the `scripts` CI job |
| There is no Alertmanager | `docker-compose.yml` |
| Grafana's dashboard is provisioned on startup | `grafana/provisioning` |

## Process

| Claim | Evidence |
|-------|----------|
| No measured figure is quoted in the README | `scripts/test_docs_consistency.py` |
| Every number in the generated documents comes from committed raw data | `scripts/report_benchmarks.py`, `scripts/analyze_failure_test.py`, `scripts/analyze_pod_kill.py`, each with its own pytest file |
| A figure that cannot be read from the data is reported as "not measured" | Asserted in `scripts/test_report_benchmarks.py` and `scripts/test_analyze_failure_test.py` |
| No availability figure is claimed anywhere | `scripts/test_docs_consistency.py` fails if one appears in any of the five documents |
| The README's test counts match the build | `scripts/test_docs_consistency.py`, against the surefire and failsafe reports |
| Every test class appears in the README's Testing table | `scripts/test_docs_consistency.py` |
| Each run records whether its source tree was committed | The `uncommittedChanges` field in every `environment.json` |
| The run data each results README lists is actually committed | `scripts/test_docs_consistency.py` |

## Claims deliberately not made

Things a reader might expect, which are absent because nothing here establishes them:

- Any availability or uptime percentage.
- A capacity or peak-throughput figure. The benchmark is a floor; the load generator shared the
  machine and the offered rate never saturated the service.
- High availability. Redis and PostgreSQL are one replica each.
- Behaviour under node failure, a Redis that refuses connections, or a Redis that loses data.
- Any comparison with another rate limiter.
- Production readiness. There is no Ingress, TLS, NetworkPolicy, autoscaling or Alertmanager, and
  the resource limits were sized for a laptop.
