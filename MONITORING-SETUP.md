# Monitoring Setup: Prometheus and Grafana

## Overview

RateLimitX exposes application metrics through Spring Boot Actuator and Micrometer. A
Prometheus instance scrapes them and a Grafana instance, provisioned on startup, renders the
bundled dashboard. Both run as Docker Compose services alongside the application.

## Components

### Dependencies (pom.xml)

- `spring-boot-starter-actuator` — actuator endpoints, including `/actuator/prometheus`
- `micrometer-registry-prometheus` — Prometheus registry for Micrometer

### Application configuration (application.properties)

```properties
management.server.port=9091
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.show-details=always
management.endpoint.health.probes.enabled=true
management.metrics.tags.application=ratelimitx
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.prometheus.metrics.export.enabled=true
```

Actuator is served on port 9091, not on the application's port 8080. Docker Compose does not
publish 9091, so these endpoints are reachable only from inside the Compose network — which is
all Prometheus and the container healthcheck need. Nothing under `/actuator` is mapped on port
8080, so a request there returns 404.

The alternative was to keep actuator on 8080 and require the ADMIN role. That would force
Prometheus to authenticate, and one wrong request matcher would publish health details or
`/actuator/env`. A port that is never published cannot be reached from outside the network no
matter how the authorization rules are written. The trade-off is that health is no longer a
plain `curl` from the host; use `docker compose ps` or `docker exec`.

### Java classes

#### `config/MetricsConfig.java`
Configures the Micrometer registry and registers the custom counters, timers and gauges.

#### `service/PrometheusMetricsService.java`
Records metrics from the request path:

| Method | Records |
|--------|---------|
| `recordRequest(userId, allowed, algorithm)` | One rate limit decision |
| `recordDenied(userId)` | A denied request |
| `recordCheckDuration(duration, algorithm, allowed)` | Rate limit check timing |

The `recordRequest` and `recordDenied` signatures take the algorithm rather than a user id: see
[Metrics](#metrics) below.

The dead `setCircuitBreakerState` and `setActiveUsers` methods have been removed along with the
gauges they backed. Nothing ever called them, so `app_circuitbreaker_state` only ever reported
`CLOSED`. The gauge is reinstated, bound to the real breaker state, in the circuit breaker work;
until then the dashboard panel that reads it shows no data, which is accurate.

#### `controller/ApiController.java`
Calls `PrometheusMetricsService` for every rate limit decision with the user id, the
allow/deny outcome, the algorithm in use and the elapsed time.

#### `security/SecurityConfig.java`
`/actuator/**` is permitted without authentication so Prometheus can scrape it, but because
actuator is bound to the unpublished management port this grants nothing to a caller outside the
Compose network.

### Infrastructure

#### `prometheus/prometheus.yml`
- Global scrape interval: 15s; the `ratelimitx` job scrapes every 10s with a 5s timeout
- Target: `app:9091/actuator/prometheus` (the management port)
- Retention: 15 days (set by `--storage.tsdb.retention.time` in Compose)

#### `grafana/provisioning/datasources/datasource.yml`
Provisions Prometheus as the default datasource. No manual setup is needed.

#### `grafana/provisioning/dashboards/dashboard.yml`
Loads dashboards from `/var/lib/grafana/dashboards`, which is where
`grafana/dashboards/` is mounted.

#### `grafana/dashboards/ratelimitx-dashboard.json`
Panels:

**Overview** — allowed vs denied requests, success rate gauge
**Performance** — requests per second, p50/p95/p99 rate limit check latency
**Rate limiting** — denied requests by algorithm, algorithm usage, circuit breaker state (shows
no data until the gauge is wired to the breaker)
**JVM** — heap used vs max, process CPU usage, current vs peak threads

### Compose services

| Service | Image | Port |
|---------|-------|------|
| prometheus | `prom/prometheus:v2.47.0` | 9090 |
| grafana | `grafana/grafana:10.1.0` | 3000 |

Grafana's admin credentials come from `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` in
`.env`. There is no `admin`/`admin` fallback: Compose refuses to start if either is unset.

All four supporting services publish their host ports on `127.0.0.1` only.

Volumes: `prometheus-data` holds the time-series database, `grafana-data` holds Grafana's
own state.

## Running the stack

```bash
cp .env.example .env
```

```bash
docker compose up -d
```

Endpoints once the stack is up:

- Application: http://localhost:8080 (`/auth/**`, `/api/data`, `/api/status`)
- Prometheus: http://127.0.0.1:9090
- Grafana: http://127.0.0.1:3000 (dashboard is provisioned automatically)
- Actuator: port 9091 inside the container only

## Generating traffic

```bash
curl -X POST http://localhost:8080/auth/register -H "Content-Type: application/json" -d '{"username":"testuser","email":"test@example.com","password":"password123"}'
```

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d '{"username":"testuser","password":"password123"}' | jq -r '.token')
```

```bash
for i in $(seq 1 15); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/data -H "Authorization: Bearer $TOKEN"; done
```

```bash
docker exec ratelimitx-app curl -s http://localhost:9091/actuator/prometheus | grep ratelimit
```

## Metrics

### Rate limiting metrics

| Metric | Tags | Description |
|--------|------|-------------|
| `ratelimit.requests.total` | `allowed`, `algorithm` | Rate limit decisions |
| `ratelimit.requests.denied` | `algorithm` | Denied requests |
| `ratelimit.check.duration` | `algorithm`, `result` | Rate limit check duration |

Every tag has a small fixed set of values, so the number of time series is bounded regardless of
how many users the service has. The `user_id` tag these metrics used to carry created one series
per user and grew without bound; per-user figures come from `/metrics/user/{userId}` instead.

Each meter is registered once and reused. The previous code registered the same meter name both
with tags and without, which produced two separate series and made `sum()` over the metric count
every request twice.

### JVM and HTTP metrics (collected by Micrometer)

- `jvm.memory.used`, `jvm.memory.max`
- `jvm_threads_current`, `jvm_threads_peak`
- `process_cpu_usage`
- `http.server.requests`

## Example PromQL queries

```promql
# Decisions per second
rate(ratelimit_requests_total[1m])

# Denial ratio
rate(ratelimit_requests_denied[5m]) / rate(ratelimit_requests_total[5m])

# p95 rate limit check latency
histogram_quantile(0.95, rate(ratelimit_check_duration_bucket[5m]))

# Decisions by algorithm
sum by (algorithm) (rate(ratelimit_requests_total[5m]))

# Decisions by outcome
sum by (allowed) (rate(ratelimit_requests_total[5m]))
```

## Verifying the stack

```bash
curl http://localhost:9090/api/v1/targets
```

```bash
curl http://localhost:3000/api/health
```

```bash
docker exec ratelimitx-app curl -fsS http://localhost:9091/actuator/prometheus | head
```

## Troubleshooting

### Metrics missing in Grafana
1. Check the Prometheus target list at http://127.0.0.1:9090/targets; the `ratelimitx` job should
   be scraping `http://app:9091/actuator/prometheus`
2. Confirm the application responds: `docker exec ratelimitx-app curl -fsS http://localhost:9091/actuator/health`
3. Read the Prometheus logs: `docker logs ratelimitx-prometheus`

### Dashboard not loading
1. Check the datasource under Grafana → Connections → Data sources
2. Confirm Grafana can reach Prometheus over the Compose network
3. Read the Grafana logs: `docker logs ratelimitx-grafana`

### Grafana unreachable
1. Grafana is bound to `127.0.0.1:3000`; it is not reachable from another machine by design
2. Check that port 3000 is free: `lsof -i :3000`
3. Read the Grafana logs: `docker logs ratelimitx-grafana`

### Grafana rejects the password
The credentials come from `.env`, not `admin`/`admin`. Changing `GRAFANA_ADMIN_PASSWORD` after
the first start has no effect, because Grafana stores the admin user in `grafana-data`; reset it
with `docker exec ratelimitx-grafana grafana-cli admin reset-admin-password <new>`.

## Configuration files

| Purpose | Path |
|---------|------|
| Application config | `src/main/resources/application.properties` |
| Prometheus config | `prometheus/prometheus.yml` |
| Grafana datasource | `grafana/provisioning/datasources/datasource.yml` |
| Grafana dashboard provisioning | `grafana/provisioning/dashboards/dashboard.yml` |
| Dashboard definition | `grafana/dashboards/ratelimitx-dashboard.json` |
| Compose stack | `docker-compose.yml` |

## Known gaps

- The circuit breaker gauge was removed with the dead method that backed it, so its dashboard
  panel shows no data until the gauge is bound to the real breaker state.
- No Prometheus alert rules are defined.
- `management.endpoint.health.show-details=always` is safe only because the management port is
  unpublished. Publishing port 9091 would expose full health details without authentication.

## References

- Prometheus: https://prometheus.io/docs/
- Grafana: https://grafana.com/docs/
- Spring Boot Actuator: https://docs.spring.io/spring-boot/reference/actuator/index.html
- Micrometer: https://micrometer.io/
