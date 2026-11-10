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

Every figure in this table is checked against the run that produced it by
`scripts/test_docs_consistency.py`. Nothing outside this table quotes a measured figure, so a
re-run cannot leave a stale number in the prose below.

| Indicator | Measured | Conditions | Source |
|-----------|----------|------------|--------|
| Decision latency, p50 | 1 ms | 16 users, four steps from 50 to 200 req/s, sliding window | [benchmarks](benchmarks.md) |
| Decision latency, p95 | 2 ms | as above | [benchmarks](benchmarks.md) |
| Decision latency, p99 | 3 ms | as above | [benchmarks](benchmarks.md) |
| Decisions per second | 118.8 decisions/s | **not a capacity figure** - see below | [benchmarks](benchmarks.md) |
| Denied share of decisions | 85.3% | by design: the per-minute caps are far below the offered rate | [benchmarks](benchmarks.md) |
| Failures during a 40 s Redis outage | 0 of 2,800 requests | the whole run, of which 803 requests were sent while Redis was frozen | [failure test](failure-test.md) |
| Redis failure to breaker OPEN | 396 ms | upper bound; polled every 100 ms | [failure test](failure-test.md) |
| Breaker OPEN to CLOSED once Redis returned | 30,055 ms | paced by the breaker's own open timeout, not by Redis | [failure test](failure-test.md) |
| Requests served by the local fallback | 1,206 | during and after the 40 s pause | [failure test](failure-test.md) |
| Failures while a replica was deleted under load | 0 of 1,600 requests | two replicas, one deleted mid-run | [pod kill test](pod-kill-test.md) |
| Response time p99 while a replica was missing | 1,858 ms | same run, disruption phase | [pod kill test](pod-kill-test.md) |
| Response time p99 after the replacement joined | 1,009 ms | same run, once the Service was back to full strength | [pod kill test](pod-kill-test.md) |

### Reading those figures

**Decisions per second is not a throughput limit.** Every request offered during the benchmark
was answered, so the figure is simply the average rate at which load was offered, divided by the
run. Raising the offered rate would raise it. It establishes a floor the service clears, not a
ceiling it reaches, and the only way to find the ceiling is to drive it until something gives,
which has not been done. The benchmark document derives the offered rate from the load profile
and says the same thing.

**The latency figures come from a heavily denied run.** A denial is cheaper to serve than an
admission - it does less work and returns a shorter body - so a run where most requests are
denied has a lower latency profile than a lightly loaded one. These are not comparable with a
service answering mostly 200s.

**No failures is not the same as no impact.** The pod kill run recorded zero failed requests and,
in the same run, a p99 of roughly two seconds while the replica was missing and roughly one
second after the replacement joined, against a p99 in the low tens of milliseconds before the
deletion. A JVM that has just started is slow for its first requests, and a readiness probe
establishes that a pod answers, not that it is warm. A caller saw no errors and did wait.

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
| `RateLimitXNearTotalDenial` | behaviour | over 95% denied for 15 m | set near total, because the measured denial share above is normal operation |
| `RateLimitXHighLatency` | latency | p95 on `/api/data` over 250 ms for 10 m | a round number well above every p95 and every maximum in any committed run; not an objective |

Two of those thresholds are judgement rather than measurement, and say so in the rule file. The
latency threshold was chosen to sit far enough above everything observed that the rule does not
fire in normal operation; it is not derived from a target and it should be re-derived on real
deployment hardware. The denial threshold is set near total because a high denial ratio is a rate
limiter working, not a rate limiter failing.

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
  caller a fresh window regardless of how much of their Redis window they had spent.

  Whether that shows up as extra admissions depends on where in the minute-aligned window the
  outage falls, and the two runs disagree - which says more than either would alone. On
  Kubernetes, admissions went from 16.6 to 19.9 per second while Redis was frozen: the entire
  offered rate, against an unchanged configured limit. On compose the same test went from 16.6 to
  12.5 per second, because those callers had less of their window left to be handed back. The
  generated reports state whichever happened instead of asserting the rule, so this is recorded
  as conditional, which is what it is.
- **Recovery is paced by the breaker's open timeout, not by Redis.** With the configured 30
  seconds, a Redis outage that ends at the wrong moment leaves the service on the fallback for up
  to another 30 seconds.
