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
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.metrics.tags.application=ratelimitx
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.prometheus.metrics.export.enabled=true
```

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
| `setCircuitBreakerState(state)` | Circuit breaker state gauge |
| `setActiveUsers(count)` | Active user count gauge |

Note: `setCircuitBreakerState` is defined but never called from the request path, so the
circuit breaker gauge does not currently track the real breaker state. The Grafana panel that
reads `app_circuitbreaker_state` is therefore not reliable yet.

#### `controller/ApiController.java`
Calls `PrometheusMetricsService` for every rate limit decision with the user id, the
allow/deny outcome, the algorithm in use and the elapsed time.

#### `security/SecurityConfig.java`
All `/actuator/**` paths are currently reachable without authentication, which is what lets
Prometheus scrape `/actuator/prometheus` over the Compose network. This also exposes
`/actuator/health` with full details to anyone who can reach the port.

### Infrastructure

#### `prometheus/prometheus.yml`
- Global scrape interval: 15s; the `ratelimitx` job scrapes every 10s with a 5s timeout
- Target: `app:8080/actuator/prometheus`
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
**Rate limiting** — denied requests by user, algorithm usage, circuit breaker state
**JVM** — heap used vs max, process CPU usage, current vs peak threads

### Compose services

| Service | Image | Port |
|---------|-------|------|
| prometheus | `prom/prometheus:v2.47.0` | 9090 |
| grafana | `grafana/grafana:10.1.0` | 3000 |

Grafana's admin credentials are set in `docker-compose.yml` and currently default to
`admin` / `admin`. Change them before exposing the stack anywhere but a local machine.

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
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (dashboard is provisioned automatically)

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
curl -s http://localhost:8080/actuator/prometheus | grep ratelimit
```

## Metrics

### Rate limiting metrics

| Metric | Tags | Description |
|--------|------|-------------|
| `ratelimit.requests.total` | `user_id`, `allowed`, `algorithm` | Rate limit decisions |
| `ratelimit.requests.denied` | `user_id` | Denied requests |
| `ratelimit.check.duration` | `algorithm`, `result` | Rate limit check duration |
| `app.active.users` | - | Active user count gauge |
| `app.circuitbreaker.state` | - | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |

The `user_id` tag has unbounded cardinality: every distinct user creates a new Prometheus
time series. This is a known problem for any deployment with more than a handful of users.

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

# Circuit breaker state
app_circuitbreaker_state
```

## Verifying the stack

```bash
curl http://localhost:9090/api/v1/targets
```

```bash
curl http://localhost:3000/api/health
```

```bash
curl http://localhost:8080/actuator/prometheus
```

## Troubleshooting

### Metrics missing in Grafana
1. Check the Prometheus target list at http://localhost:9090/targets
2. Confirm the application responds: `curl http://localhost:8080/actuator/health`
3. Read the Prometheus logs: `docker logs ratelimitx-prometheus`

### Dashboard not loading
1. Check the datasource under Grafana → Connections → Data sources
2. Confirm Grafana can reach Prometheus over the Compose network
3. Read the Grafana logs: `docker logs ratelimitx-grafana`

### Grafana unreachable
1. Check that port 3000 is free: `lsof -i :3000`
2. Read the Grafana logs: `docker logs ratelimitx-grafana`

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

- `/actuator/**` is unauthenticated, including health details.
- Per-user metric labels give the Prometheus series unbounded cardinality.
- The circuit breaker gauge is never updated, so its dashboard panel is inaccurate.
- No Prometheus alert rules are defined.

## References

- Prometheus: https://prometheus.io/docs/
- Grafana: https://grafana.com/docs/
- Spring Boot Actuator: https://docs.spring.io/spring-boot/reference/actuator/index.html
- Micrometer: https://micrometer.io/
