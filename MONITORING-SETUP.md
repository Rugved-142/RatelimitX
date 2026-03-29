# RateLimitX Prometheus + Grafana Monitoring Setup

## Overview
Complete Prometheus + Grafana monitoring stack has been integrated into your RateLimitX Spring Boot 3.x application.

## What Was Added

### 1. **Dependencies (pom.xml)**
- `spring-boot-starter-actuator` - Spring Boot actuator for metrics endpoints
- `micrometer-registry-prometheus` - Prometheus metrics registry

### 2. **Application Configuration (application.properties)**
```properties
# Actuator endpoints exposure
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.metrics.tags.application=ratelimitx
management.prometheus.metrics.export.enabled=true
```

### 3. **Java Configuration Classes**

#### MetricsConfig.java
- Configures Micrometer/Prometheus metrics
- Registers custom counters and timers
- Enables @Timed annotation support

#### PrometheusMetricsService.java
- Service layer for recording metrics
- Methods:
  - `recordRequest(userId, allowed, algorithm)` - Record API requests
  - `recordDenied(userId)` - Record denied requests
  - `recordCheckDuration(duration, algorithm, allowed)` - Record check timing
  - `setCircuitBreakerState(state)` - Update circuit breaker status
  - `setActiveUsers(count)` - Update active user count

### 4. **Updated Controllers & Security**

#### ApiController.java
- Injected `PrometheusMetricsService`
- Records every request with:
  - User ID
  - Success/failure status
  - Algorithm used
  - Response time

#### SecurityConfig.java
- Allows unauthenticated access to:
  - `/actuator/health`
  - `/actuator/prometheus`
  - `/actuator/info`
  - `/actuator/**`

### 5. **Monitoring Infrastructure**

#### prometheus/prometheus.yml
- Scrape interval: 10-15 seconds
- Target: `http://app:8080/actuator/prometheus`
- Storage retention: 15 days

#### grafana/provisioning/datasources/datasource.yml
- Auto-provisions Prometheus as default datasource
- No manual configuration needed

#### grafana/provisioning/dashboards/dashboard.yml
- Auto-loads `ratelimitx-dashboard.json`
- Refreshes every 30 seconds

#### grafana/dashboards/ratelimitx-dashboard.json
Comprehensive dashboard with panels:

**Row 1: Overview**
- Requests (Allowed vs Denied pie chart)
- Success Rate % (gauge)

**Row 2: Performance**
- Requests Per Second (time series)
- Request Latency p50/p95/p99 (time series)

**Row 3: Rate Limiting**
- Denied Requests by User (bar chart)
- Algorithm Usage (pie chart)
- Circuit Breaker State (stat)

**Row 4: JVM Metrics**
- JVM Memory Usage (heap used vs max)
- JVM CPU Usage (percentage)
- Active Threads (current vs peak)

### 6. **Docker Compose Updates**

Added three new services:

#### Prometheus Service
- Image: `prom/prometheus:v2.47.0`
- Port: `9090`
- Volumes: configuration and persistent data storage

#### Grafana Service
- Image: `grafana/grafana:10.1.0`
- Port: `3000`
- Admin credentials: `admin/admin`
- Auto-provisions datasources and dashboards

#### Volumes
- `prometheus-data` - Prometheus time-series database
- `grafana-data` - Grafana configuration and plugins

## Quick Start

### 1. Build the Application
```bash
mvn clean package
```

### 2. Start All Services
```bash
docker-compose up -d
```

### 3. Access Services

- **RateLimitX API**: http://localhost:8080
  - Endpoints: `/auth/**`, `/api/data`, `/api/status`
  
- **Prometheus**: http://localhost:9090
  - Query interface for Prometheus metrics
  - Status page: http://localhost:9090/graph
  
- **Grafana**: http://localhost:3000
  - Login: `admin` / `admin`
  - RateLimitX Dashboard automatically loaded
  - Update admin password on first login

### 4. Generate Test Traffic
```bash
# Use the load test script to generate metrics
./run-load-test.sh
```

Or manually:
```bash
# Register a user
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'

# Login
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'

# Use the token to make requests
TOKEN="<jwt_token_from_login>"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/data

# View metrics endpoint
curl http://localhost:8080/actuator/prometheus
```

## Available Metrics

### Custom Rate Limiting Metrics
- `ratelimit.requests.total` - Total requests (tagged: user_id, allowed, algorithm)
- `ratelimit.requests.denied` - Denied requests (tagged: user_id)
- `ratelimit.check.duration` - Check duration in milliseconds (tagged: algorithm, result)
- `app.active.users` - Gauge for active user count
- `app.circuitbreaker.state` - Circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN)

### JVM Metrics (Auto-collected)
- `jvm.memory.used` - Heap and non-heap memory usage
- `jvm.memory.max` - Maximum heap size
- `jvm_threads_current` - Currently active threads
- `jvm_threads_peak` - Peak thread count
- `process_cpu_usage` - CPU usage percentage
- `http.server.requests` - HTTP request metrics (timing, count, status)

## Prometheus Queries

Common useful queries for the dashboard:

```promql
# Requests per second
rate(ratelimit_requests_total[1m])

# Error rate
rate(ratelimit_requests_denied[5m]) / rate(ratelimit_requests_total[5m])

# 95th percentile latency
histogram_quantile(0.95, rate(ratelimit_check_duration_bucket[5m]))

# Request distribution by algorithm
sum by (algorithm) (rate(ratelimit_requests_total[5m]))

# Circuit breaker state
app_circuitbreaker_state
```

## Health Checks

Verify the monitoring stack:

```bash
# Check Prometheus is scraping metrics
curl http://localhost:9090/api/v1/targets

# Check Grafana is running
curl http://localhost:3000/api/health

# Check application metrics endpoint
curl http://localhost:8080/actuator/prometheus
```

## Troubleshooting

### Metrics Not Appearing in Grafana
1. Check Prometheus targets: http://localhost:9090/graph
2. Verify app is running: `curl http://localhost:8080/actuator/health`
3. Check Prometheus logs: `docker logs ratelimitx-prometheus`

### Grafana Dashboard Not Loading
1. Verify datasource connection: Grafana → Configuration → Data Sources
2. Check Prometheus is accessible from Grafana container
3. Reload dashboard: Press F5 or click the refresh icon

### Can't Access Grafana
1. Default login: `admin/admin`
2. Check port 3000 is not in use: `lsof -i :3000`
3. View logs: `docker logs ratelimitx-grafana`

## Configuration Files

- **Application Config**: `src/main/resources/application.properties`
- **Prometheus Config**: `prometheus/prometheus.yml`
- **Grafana Datasource**: `grafana/provisioning/datasources/datasource.yml`
- **Dashboard Config**: `grafana/provisioning/dashboards/dashboard.yml`
- **Dashboard JSON**: `grafana/dashboards/ratelimitx-dashboard.json`
- **Docker Compose**: `docker-compose.yml`

## Next Steps

1. **Fine-tune Grafana dashboard** - Customize panels based on your needs
2. **Add alerting rules** - Set up Prometheus alert rules for critical metrics
3. **Configure Grafana notifications** - Send alerts to Slack, PagerDuty, etc.
4. **Add Redis exporter** - Monitor Redis operations (optional)
5. **Add PostgreSQL exporter** - Monitor database performance (optional)
6. **Backup Prometheus data** - Set up automated backups of TSDB

## Support

For issues or improvements, check:
- Prometheus documentation: https://prometheus.io/docs/
- Grafana documentation: https://grafana.com/docs/
- Spring Boot Actuator: https://spring.io/guides/gs/actuator-service/
- Micrometer: https://micrometer.io/
