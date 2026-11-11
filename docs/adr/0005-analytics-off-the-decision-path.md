# 5. Analytics is asynchronous and never on the decision path

## Status

Accepted.

## Context

The service now publishes one event per rate limit decision so the decisions can be aggregated and
looked at later. The obvious implementation is to write the event where the decision is made, on
the request thread, before the response is returned.

That would make every `/api/data` response depend on Kafka. A broker that is down, mid-election,
or merely slow would then be able to add latency to - or fail - a request whose rate limit decision
had already been made correctly. The rate limiter exists to protect the service; an analytics feed
that can take the service down inverts that.

The same argument was already made once in this codebase, about Redis: the limiter's own Redis
calls sit behind a circuit breaker with a 200ms timeout and a local fallback precisely so that a
dependency cannot turn into an outage. Analytics has an even weaker claim on the request path than
the limiter does, because nothing about the response depends on it.

## Decision

The request thread does one non-blocking `offer` onto a bounded in-memory queue and returns. A
single background thread drains the queue and sends to Kafka.

The feed is best-effort and says so:

- If the queue is full, the event is dropped and `ratelimit.events.dropped` is incremented.
- If the send fails or times out, the event is dropped and `ratelimit.events.failed` is
  incremented.
- Nothing is retried indefinitely, buffered to disk, or allowed to block a request.
- The producer's `max.block.ms` is 100ms, against a 60s default that would otherwise park the
  sending thread for a minute per event while the broker is unreachable.

The whole feature is off unless `ratelimit.events.enabled=true`.

## Consequences

- A Kafka outage costs analytics data and nothing else. This is tested: with the broker paused,
  `/api/data` keeps answering 200 and the loss is counted
  (`EventsSurviveAKafkaOutageIT`, `EventsQueueFullIT`).
- The aggregates are not a billing or audit record, and must never be used as one. An absent event
  does not mean the request did not happen - only that the feed dropped it.
- Delivery is at-least-once, so the consumer deduplicates on the event id in the same transaction
  as the aggregate it updates. Counting is therefore at-most-once per event id even though
  delivery is not.
- Events can be stale. They are sent from a queue, so during a stall the aggregate lags real time
  by however long the queue takes to drain.
- There is no ordering guarantee between the response a caller received and the event reaching
  Kafka. Nothing reads the feed back on the request path, so nothing depends on that ordering.

## Alternatives considered

**Send synchronously with a short timeout.** Still puts a network call on the request path, and a
short timeout only bounds the damage rather than removing it. Rejected: the queue gives the same
bound without the call.

**Kafka transactions, or exactly-once delivery.** Would remove the need for the
`processed_events` table, at the cost of a transactional producer on the request path and a
consumer that reads committed only. Rejected as much heavier than the problem: a primary key and
one transaction already make duplicate counting impossible.

**Write the aggregates straight to Postgres from the request thread.** No Kafka at all, but it
puts a write on the request path - the thing this ADR exists to avoid - and serialises every
request behind one row per hour, role and algorithm.
