# Service level

**No service level objective is set for RateLimitX.** An objective is a promise about behaviour
over a period, and nothing in this repository measures behaviour over a period. What exists is a
load benchmark of a few minutes and a single two-minute failure test, both on one developer
machine. Those support statements about what was observed; they cannot support a target, and
inventing one from them would be the kind of number this repository exists not to publish.

What this document does instead: name the indicators worth measuring, record what has actually
been measured against them, and say what each alert rule watches.

## Indicators

Two, chosen because they are the two ways this service can fail its callers.

**Decision availability** — the share of requests that received a rate limit decision, allowed or
denied. A 429 counts as a success: the service under test is a rate limiter, and a denial is the
correct answer. Failures are 5xx responses, statuses the service should never return, and
requests that got no response at all.

**Decision latency** — the time to answer a request on `/api/data`, as p95. A rate limit check is
on the caller's request path, so its latency is added to every request the caller makes.

A third property matters as much as either and is not an availability figure: **the limit the
service enforces is the limit it is configured with**. While the circuit breaker is open that
stops being true, which is why the fallback is treated below as a correctness event rather than a
degraded-but-fine one.

## What has been measured

Each figure links to the run that produced it. Nothing here is a target.

| Indicator | Measured | Conditions | Source |
|-----------|----------|------------|--------|
| Decision latency, p50 | 3 ms | 16 users, stepped 50-150 req/s, sliding window | [benchmarks](benchmarks.md) |
| Decision latency, p95 | 5 ms | as above | [benchmarks](benchmarks.md) |
| Decision latency, p99 | 6 ms | as above | [benchmarks](benchmarks.md) |
| Decision throughput | 92.2 decisions/s | a floor: the offered rate never exceeded 150/s and the load generator shared the machine | [benchmarks](benchmarks.md) |
| Failures during a 40 s Redis outage | 0 of 2,800 requests | 5 users at 20 req/s, Redis frozen with `docker compose pause` | [failure test](failure-test.md) |
| Redis failure to breaker OPEN | 425 ms | upper bound; polled every 100 ms | [failure test](failure-test.md) |
| Breaker OPEN to CLOSED once Redis returned | 30,129 ms | paced by the configured 30 s open timeout, not by Redis | [failure test](failure-test.md) |
| Requests served by the local fallback | 1,205 | during and after the 40 s pause | [failure test](failure-test.md) |

The two latency figures were produced under a load shape that denied 82.9% of requests by design.
A denial is cheaper to serve than an admission, so these are not comparable with a lightly loaded
service's latency. The benchmark document says so at more length.

## What the alerts watch

Rules live in [`prometheus/rules/ratelimitx.rules.yml`](../prometheus/rules/ratelimitx.rules.yml)
and each one records in a comment what its threshold came from. There is no Alertmanager in
`docker-compose.yml`, so they fire into Prometheus' own alert list at `/alerts` and reach no
human; routing them is a deployment concern this repository does not pretend to have solved.

| Alert | Indicator | Condition | Threshold came from |
|-------|-----------|-----------|---------------------|
| `RateLimitXDown` | availability | scrape fails for 1 m | nothing to derive: either it answers or it does not |
| `RateLimitXServerErrors` | availability | any 5xx for 2 m | the failure test recorded no 5xx at all, so any is a defect |
| `RateLimitXCircuitBreakerOpen` | enforced limit | breaker OPEN for 1 m | longer than the 30 s open timeout, so a recovered blip does not page |
| `RateLimitXCircuitBreakerNotRecovering` | enforced limit | breaker out of CLOSED for 15 m | it closed within seconds of Redis returning in the failure test |
| `RateLimitXFallbackInUse` | enforced limit | any fallback decision in 5 m | catches outages too short to trip the rule above |
| `RateLimitXNearTotalDenial` | behaviour | over 95% denied for 15 m | the benchmark denied 82.9% by design, so the threshold sits near total |
| `RateLimitXHighLatency` | latency | p95 on `/api/data` over 250 ms for 10 m | a round number with headroom over the measured 5 ms p95; not an objective |

Two of those thresholds are explicitly judgement rather than measurement. 250 ms is roughly fifty
times the measured p95 and above every maximum in any committed run; it was chosen so the rule
does not fire in normal operation, and it should be re-derived on real deployment hardware. The
95% denial threshold is set near total because a high denial ratio is a rate limiter working.

### A measured gap in the alerting

During the failure test, `RateLimitXCircuitBreakerOpen` reached `pending` and returned to normal
within a second or two of its one-minute mark without ever firing: a 40-second Redis outage keeps
the breaker out of CLOSED for about 60 seconds, which sits right on the boundary. An outage
shorter than roughly a minute and a half will not fire that rule. `RateLimitXFallbackInUse`
exists because of this: it watches a counter rather than a state, so the event survives the
breaker closing again.

## What setting an objective would need

- Production traffic over weeks, not a synthetic run over minutes, and from more than one
  machine.
- The load generator off the machine under test, so the latency figures are not inflated by
  contention with the service.
- More than one failure mode. Only a frozen Redis has been tested; a Redis that refuses
  connections, returns errors, or loses data has not.
- More than one replica. Everything above is a single instance.
- An error budget policy, which is a decision about what to do when the budget is spent, not a
  number.

## Structural limits any objective would have to account for

- **Redis is a single point of failure.** One instance, no replication. Its outage does not stop
  the service, but it does change what the service enforces.
- **The fallback's limits are per instance, and its counters start empty.** N replicas running
  the fallback admit up to N times the configured rate between them, and a failover hands every
  caller a fresh window regardless of how much of their Redis window they had spent. The failure
  test measured this directly: admissions rose from 16.7 to 20.0 per second - the full offered
  rate - while Redis was frozen, with no change to the configured limit.
- **Recovery is paced by the breaker's open timeout, not by Redis.** With the configured 30
  seconds, a Redis outage that ends at the wrong moment leaves the service on the fallback for up
  to another 30 seconds.
