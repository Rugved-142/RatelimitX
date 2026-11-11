# 3. Fall back to a per-instance in-memory limiter, and accept over-admission

Date: 2026-11-10

## Status

Accepted.

## Context

When Redis is unreachable the service has to do something. Three options:

1. **Fail the request** - return 503 and let the caller retry.
2. **Allow everything** - stop limiting until Redis returns.
3. **Limit locally** - each instance keeps its own in-memory counters.

A rate limiter that returns 503 turns a dependency outage into an outage of the thing it was
protecting. A rate limiter that allows everything removes the protection at the exact moment the
system is already unhealthy.

## Decision

Option 3. A circuit breaker opens after three consecutive Redis failures and every decision is
then made by `LocalRateLimiter`, an in-memory fixed-window limiter that applies the caller's own
tier limit against counters held in that instance's heap.

## Consequences

**Limits stop being global, and the published number stops being the enforced number.** Each
instance enforces the configured limit on its own counters, so N instances in the fallback admit
up to N times the configured rate between them. With the two replicas in `k8s/`, that is twice.

**A failover hands every caller a fresh window.** The fallback cannot read Redis's counters -
Redis is the thing that is down - so a caller who had spent their window is given a new one.
This was measured, not reasoned about: in `docs/failure-test.md`, admissions rose from 16.6 to
19.9 per second, the entire offered rate, while the configured limit never changed.

Neither effect is a bug to fix, because there is nothing to fix them with while Redis is
unreachable. They are the price of continuing to answer, and the alternative prices are worse.
What was required instead is that they are never hidden:

- Every response served this way carries `X-Algorithm: local-fallback`, so a caller can tell.
- The `RateLimitXFallbackInUse` alert fires on the counter rather than on the breaker's state,
  so a short outage cannot pass unnoticed.
- `docs/slo.md` lists the multiplier among the limits any future objective must account for.
- `LocalRateLimiter`'s class javadoc says it in the code, where someone changing the replica
  count will be reading.

Sizing follows from this: the failure threshold and the replica count together decide how far
the enforced limit can drift from the configured one during an outage.
