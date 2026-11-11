# 2. Take the clock from Redis inside the Lua scripts

Date: 2026-11-10

## Status

Accepted.

## Context

Every algorithm needs to know the time: which fixed window a request falls in, how many tokens
have refilled, how much of the previous sliding window still counts. The original code read
`System.currentTimeMillis()` in the application and passed it into Redis.

With one instance that works. With more than one it does not: two application instances whose
clocks differ by a second will disagree about which window a request belongs to, and about how
much a bucket has refilled. The disagreement is silent, and it gets worse as instances are added
- which is exactly when a *distributed* rate limiter is supposed to start being useful.

## Decision

The Lua scripts call `redis.call('TIME')` and use that. The application passes no timestamp.

## Consequences

Every instance reads the same clock, because there is only one, and it is the clock belonging to
the server that also holds the counters. Adding instances cannot introduce skew.

This is only possible because Redis 7 replicates script *effects* rather than the script itself.
Under the older whole-script replication a script that called `TIME` was non-deterministic and
Redis refused to let it write. On Redis 6 or earlier these scripts will not run. That is recorded
here rather than discovered later: the compose file and the Kubernetes manifests both pin
`redis:7-alpine`.

The other consequence is that the limiter's behaviour is tied to Redis being reachable. When it
is not, the fallback uses the application's own clock, with the per-instance consequences
described in ADR 3.
