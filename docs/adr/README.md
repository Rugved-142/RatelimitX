# Architecture decision records

One file per decision that had a real trade-off: what the situation was, what was chosen, and
what that costs. They are records, not proposals — each one describes a decision already in the
code, and the consequences section is the part worth reading.

| # | Decision |
|---|----------|
| [1](0001-actuator-on-a-separate-unpublished-port.md) | Serve actuator on a separate port that is not published |
| [2](0002-redis-time-as-the-clock.md) | Take the clock from Redis inside the Lua scripts |
| [3](0003-per-instance-fallback.md) | Fall back to a per-instance in-memory limiter, and accept over-admission |
| [4](0004-readiness-excludes-redis.md) | Keep Redis out of the readiness probe |
| [5](0005-analytics-off-the-decision-path.md) | Publish analytics asynchronously, and never from the request thread |

Decisions that were not hard, or that have no interesting alternative, are not here. The
reasoning for those lives in a comment next to the code, which is where someone changing it will
be looking.
