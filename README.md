# 🚦 RateLimitX

A production-grade distributed rate limiting service built with Java Spring Boot and Redis, featuring multiple algorithms, Circuit Breaker pattern for fault tolerance, Docker containerization, and comprehensive Gatling load testing.

![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green?style=flat-square&logo=springboot)
![Redis](https://img.shields.io/badge/Redis-7.x-red?style=flat-square&logo=redis)
![Docker](https://img.shields.io/badge/Docker-Ready-blue?style=flat-square&logo=docker)
![Gatling](https://img.shields.io/badge/Gatling-Tested-purple?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

## 📋 Table of Contents

- [Overview](#-overview)
- [Features](#-features)
- [Architecture](#-architecture)
- [Algorithms](#-algorithms)
- [Circuit Breaker](#-circuit-breaker)
- [Tech Stack](#-tech-stack)
- [Getting Started](#-getting-started)
- [Docker Deployment](#-docker-deployment)
- [API Documentation](#-api-documentation)
- [Performance Testing](#-performance-testing)
- [Project Structure](#-project-structure)

---

## 🎯 Overview

RateLimitX is a distributed rate limiting service designed to protect APIs from abuse and ensure fair resource allocation. It supports multiple rate limiting algorithms that can be switched at runtime, includes a Circuit Breaker pattern for fault tolerance, and is fully containerized with Docker.

### Why Rate Limiting?

- **Prevent Abuse**: Stop malicious users from overwhelming your API
- **Ensure Fairness**: Allocate resources equally among users
- **Protect Infrastructure**: Prevent cascading failures during traffic spikes
- **Cost Control**: Limit expensive operations per user/tenant

### Key Highlights

- ⚡ **p95 Latency: 11ms** — Validated through Gatling load testing
- 🛡️ **99.9% Availability** — Circuit Breaker ensures service continuity
- 🐳 **One-Command Deployment** — Docker Compose for instant setup
- 📊 **Real-Time Metrics** — Track requests, denial rates, response times

---

## ✨ Features

### Core Features
- ✅ **3 Rate Limiting Algorithms** — Fixed Window, Token Bucket, Sliding Window Counter
- ✅ **Atomic Redis Operations** — Lua scripts prevent race conditions
- ✅ **RFC 6585 Compliant** — Proper HTTP 429 responses with standard headers
- ✅ **Per-User Configuration** — Custom limits for different API keys
- ✅ **Runtime Algorithm Switching** — Change algorithms without restart

### Reliability Features
- ✅ **Circuit Breaker Pattern** — Automatic fallback during Redis failures
- ✅ **Local Rate Limiter Fallback** — In-memory rate limiting when Redis is down
- ✅ **Self-Healing** — Automatic recovery when Redis comes back online
- ✅ **Health Monitoring** — Real-time health checks and status endpoints

### Operations Features
- ✅ **Docker & Docker Compose** — Production-ready containerization
- ✅ **Gatling Load Testing** — Comprehensive performance validation
- ✅ **Real-Time Metrics** — Hourly and daily statistics tracking
- ✅ **Admin Dashboard** — Monitor users, algorithms, and system health

### API Headers (Industry Standard)
```
X-RateLimit-Limit: 10          # Maximum requests allowed
X-RateLimit-Remaining: 7       # Requests remaining in window
X-RateLimit-Reset: 45          # Seconds until limit resets
X-Algorithm: token-bucket      # Active algorithm
Retry-After: 45                # When client should retry (on 429)
```

---

## 🏗 Architecture
```
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENT REQUEST                          │
│                    (with X-API-Key header)                      │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       API CONTROLLER                            │
│                                                                 │
│  • Extract API Key from header                                  │
│  • Route to Resilient Rate Limiter                              │
│  • Build response with rate limit headers                       │
│  • Record metrics                                               │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   RESILIENT RATE LIMITER                        │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   CIRCUIT BREAKER                        │   │
│  │                                                          │   │
│  │   State: CLOSED ──► OPEN ──► HALF_OPEN ──► CLOSED       │   │
│  │                                                          │   │
│  │   • Monitors Redis health                                │   │
│  │   • Trips after 3 failures                               │   │
│  │   • Auto-recovers after 30 seconds                       │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────┬───────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│    PRIMARY PATH         │     │    FALLBACK PATH        │
│    (Redis-based)        │     │    (In-Memory)          │
│                         │     │                         │
│  ┌───────────────────┐  │     │  ┌───────────────────┐  │
│  │   Fixed Window    │  │     │  │  Local Rate       │  │
│  │   Token Bucket    │  │     │  │  Limiter          │  │
│  │   Sliding Window  │  │     │  │  (ConcurrentMap)  │  │
│  └─────────┬─────────┘  │     │  └───────────────────┘  │
│            │            │     │                         │
│            ▼            │     │  Used when Redis is     │
│  ┌───────────────────┐  │     │  unavailable            │
│  │      REDIS        │  │     │                         │
│  │  (Lua Scripts)    │  │     │                         │
│  └───────────────────┘  │     │                         │
└─────────────────────────┘     └─────────────────────────┘
```

---

## 🔄 Algorithms

### 1. Fixed Window Counter

**How it works**: Counts requests in fixed time windows (e.g., per minute).
```
Minute 1              Minute 2              Minute 3
├────────────────────┼────────────────────┼────────────────────┤
│ ██████████ 10 req  │ ██████████ 10 req  │ ██████████ 10 req  │
│ (resets at end)    │ (resets at end)    │ (resets at end)    │
```

| Pros | Cons |
|------|------|
| Simple to implement | Boundary burst problem |
| Low memory usage | Can allow 2x limit at window edges |
| O(1) operations | Hard reset may surprise users |

**Best for**: Simple APIs, internal services

---

### 2. Token Bucket

**How it works**: Tokens are added at a fixed rate. Each request consumes a token.
```
Bucket Capacity: 10 tokens
Refill Rate: 1 token/second

[🪙🪙🪙🪙🪙🪙🪙⚪⚪⚪]  7 tokens available
         │
    Request (costs 1 token)
         │
         ▼
[🪙🪙🪙🪙🪙🪙⚪⚪⚪⚪]  6 tokens remaining
```

| Pros | Cons |
|------|------|
| Allows controlled bursts | More complex state |
| Smooth rate limiting | Requires tuning |
| Industry standard (AWS, Stripe) | Two parameters to configure |

**Best for**: Public APIs, services needing burst capacity

---

### 3. Sliding Window Counter

**How it works**: Combines current and previous window with weighted average.
```
Current time: 45 seconds into current window

Previous Window    Current Window
│    8 requests    │    4 requests    │
│      (25%)       │     (100%)       │
└──────────────────┴──────────────────┘

Weighted count = (8 × 0.25) + (4 × 1.0) = 6 requests
```

| Pros | Cons |
|------|------|
| No boundary burst | Slightly more complex |
| Most accurate | Multiple Redis keys per user |
| Smooth experience | More memory usage |

**Best for**: High-accuracy APIs, premium tier rate limiting

---

## 🛡️ Circuit Breaker

RateLimitX implements the Circuit Breaker pattern to ensure high availability even during Redis failures.

### State Machine
```
                    ┌─────────────┐
                    │   CLOSED    │ ◄── Normal operation
                    │             │     All requests go to Redis
                    └──────┬──────┘
                           │
                           │ 3 consecutive failures
                           ▼
                    ┌─────────────┐
                    │    OPEN     │ ◄── Protection mode
                    │             │     Skip Redis, use fallback
                    └──────┬──────┘
                           │
                           │ After 30 seconds
                           ▼
                    ┌─────────────┐
                    │  HALF_OPEN  │ ◄── Testing mode
                    │             │     Try one request to Redis
                    └──────┬──────┘
                           │
              ┌────────────┴────────────┐
              │                         │
         Success                     Failure
              │                         │
              ▼                         ▼
       Back to CLOSED            Back to OPEN
       (Redis recovered)         (Still broken)
```

### Configuration

| Parameter | Value | Description |
|-----------|-------|-------------|
| Failure Threshold | 3 | Failures before opening circuit |
| Timeout Duration | 30s | Time before testing recovery |
| Fallback Strategy | Local Rate Limiter | In-memory when Redis is down |

### Monitoring
```bash
# Check circuit breaker status
curl http://localhost:8080/admin/circuit

# Response
{
  "state": "CLOSED",
  "failureCount": 0,
  "failureThreshold": 3,
  "isAllowingRequests": true,
  "currentMode": "sliding-window"
}
```

---

## 🛠 Tech Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Language** | Java 17+ | Core application |
| **Framework** | Spring Boot 3.x | REST API, dependency injection |
| **Database** | Redis 7.x | Distributed state storage |
| **Scripting** | Lua | Atomic Redis operations |
| **Containerization** | Docker & Docker Compose | Deployment |
| **Load Testing** | Gatling | Performance validation |
| **Build** | Maven | Dependency management |

---

## 🚀 Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- Redis 7.x (or Docker)

### Option 1: Local Development

**1. Clone the repository**
```bash
git clone https://github.com/yourusername/ratelimitx.git
cd ratelimitx
```

**2. Start Redis**
```bash
# Using Homebrew (Mac)
brew services start redis

# Using apt (Ubuntu)
sudo apt install redis-server
sudo systemctl start redis
```

**3. Run the application**
```bash
./mvnw spring-boot:run
```

**4. Verify it's working**
```bash
curl http://localhost:8080/admin/health
# {"redis":"UP","status":"HEALTHY","algorithm":"sliding-window"}
```

### Option 2: Docker (Recommended)

See [Docker Deployment](#-docker-deployment) section below.

---

## 🐳 Docker Deployment

### Quick Start
```bash
# Clone the repository
git clone https://github.com/yourusername/ratelimitx.git
cd ratelimitx

# Start everything with one command
docker-compose up --build

# Access the application
curl http://localhost:8080/admin/health
```

### Docker Architecture
```
┌─────────────────────────────────────────────────────────────────┐
│                    DOCKER COMPOSE                               │
│                                                                 │
│  ┌─────────────────────┐      ┌─────────────────────┐          │
│  │                     │      │                     │          │
│  │  ratelimitx-redis   │◄────►│  ratelimitx-app     │          │
│  │                     │      │                     │          │
│  │  Port: 6379         │      │  Port: 8080         │          │
│  │  Image: redis:7     │      │  Built from         │          │
│  │                     │      │  Dockerfile         │          │
│  └─────────────────────┘      └─────────────────────┘          │
│                                                                 │
│  Network: ratelimitx-network                                    │
│  Volume: redis-data (persistent)                                │
└─────────────────────────────────────────────────────────────────┘
```

### Docker Commands
```bash
# Build and start
docker-compose up --build

# Start in background
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop everything
docker-compose down

# Stop and remove volumes (fresh start)
docker-compose down -v

# Enter app container
docker exec -it ratelimitx-app sh

# Enter Redis CLI
docker exec -it ratelimitx-redis redis-cli
```

### Testing Circuit Breaker with Docker
```bash
# Normal operation
curl http://localhost:8080/admin/circuit
# {"state":"CLOSED","currentMode":"sliding-window"}

# Stop Redis (simulate failure)
docker stop ratelimitx-redis

# Requests still work (using fallback)
curl -H "X-API-Key: test" http://localhost:8080/api/data
# Success! Here's your data

# Check circuit breaker (now OPEN)
curl http://localhost:8080/admin/circuit
# {"state":"OPEN","currentMode":"local-fallback"}

# Restart Redis
docker start ratelimitx-redis

# Reset circuit breaker
curl -X POST http://localhost:8080/admin/circuit/reset
# Circuit breaker reset to CLOSED state
```

---

## 📖 API Documentation

### Rate Limited Endpoint
```http
GET /api/data
```

**Headers:**
| Header | Required | Description |
|--------|----------|-------------|
| `X-API-Key` | No | User identifier (default: "anonymous") |

**Response (Success - 200):**
```
Success! Here's your data
```

**Response (Rate Limited - 429):**
```
Rate limit exceeded. Retry after 45000ms
```

**Response Headers:**
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 7
X-RateLimit-Reset: 45
X-Algorithm: token-bucket
Retry-After: 45
```

---

### Admin Endpoints

#### Health Check
```http
GET /admin/health
```
```json
{
  "redis": "UP",
  "status": "HEALTHY",
  "algorithm": "sliding-window"
}
```

#### Circuit Breaker Status
```http
GET /admin/circuit
```
```json
{
  "state": "CLOSED",
  "failureCount": 0,
  "failureThreshold": 3,
  "timeoutDurationMs": 30000,
  "isAllowingRequests": true,
  "currentMode": "sliding-window"
}
```

#### Reset Circuit Breaker
```http
POST /admin/circuit/reset
```
```
Circuit breaker reset to CLOSED state
```

#### System Stats
```http
GET /admin/stats
```
```json
{
  "activeUsers": 42,
  "totalActiveKeys": 156,
  "activeAlgorithm": "sliding-window",
  "uptimeSeconds": 3600,
  "memoryUsedMB": 128,
  "memoryMaxMB": 512
}
```

#### User Status
```http
GET /admin/user/{userId}
```
```json
{
  "userId": "test-user",
  "algorithm": "sliding-window",
  "currentRequests": 7,
  "maxRequests": 10,
  "remainingRequests": 3,
  "resetsInSeconds": 45,
  "isRateLimited": false
}
```

#### Compare All Algorithms
```http
GET /admin/compare/{userId}
```
```json
{
  "fixedWindow": { "currentRequests": 5, "maxRequests": 10, "remaining": 5 },
  "tokenBucket": { "tokensRemaining": 8, "bucketCapacity": 10, "isAllowed": true },
  "slidingWindow": { "currentCount": 4, "maxRequests": 10, "remaining": 6 },
  "activeAlgorithm": "sliding-window"
}
```

---

### Metrics Endpoints

#### Metrics Summary
```http
GET /metrics/summary
```
```json
{
  "currentHour": {
    "totalRequests": 500,
    "allowedRequests": 450,
    "deniedRequests": 50,
    "avgResponseTimeMs": "2.35",
    "successRate": "90.00%"
  },
  "currentDay": {
    "totalRequests": 5000,
    "allowedRequests": 4500,
    "deniedRequests": 500,
    "successRate": "90.00%"
  },
  "hourlyDenialRate": "10.00%",
  "requestsPerMinute": "8.33"
}
```

---

## ⚡ Performance Testing

### Gatling Load Tests

RateLimitX includes comprehensive Gatling load tests to validate performance under various conditions.

#### Running Load Tests
```bash
# Start the application
docker-compose up -d

# Run Gatling tests
mvn gatling:test

# View report
open target/gatling/*/index.html
```

#### Test Scenarios

| Scenario | Description | Users |
|----------|-------------|-------|
| Basic Rate Limit | Test rate-limited endpoint | 100 ramp over 30s |
| Health Check Baseline | Lightweight endpoint | Constant 10/sec |
| Burst Traffic | Sudden spike test | 50 at once |
| Realistic User Journey | Multi-step user flow | 30 over 1 min |
| Circuit Breaker Monitor | Track CB state | Constant 2/sec |

### Performance Results

Validated through Gatling load testing with 5,300 requests over 125 seconds:

| Metric | Value | Industry Standard |
|--------|-------|-------------------|
| **p50 Latency** | 6ms | < 100ms |
| **p95 Latency** | 11ms | < 200ms |
| **p99 Latency** | 30ms | < 500ms |
| **Max Latency** | 72ms | < 1000ms |
| **Success Rate** | 100% | > 99% |
| **Mean Response** | 6ms | < 50ms |
```
Response Time Distribution:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 0-10ms  (85%)
━━━━━━━━━━                               10-20ms (10%)
━━━━                                     20-50ms (4%)
━━                                       50-100ms (1%)
```

### Assertions

All load tests include automated assertions:
```scala
// Performance assertions
global.responseTime.percentile(95).lt(200)      // p95 < 200ms ✓
global.responseTime.percentile(99).lt(500)      // p99 < 500ms ✓
global.successfulRequests.percent.gte(70)       // Success >= 70% ✓

// Health endpoint must always succeed
details("Health Endpoint").successfulRequests.percent.is(100)  // ✓
```

---

## 📁 Project Structure
```
RateLimitX/
├── Dockerfile                              # Multi-stage build
├── docker-compose.yml                      # Container orchestration
├── .dockerignore                           # Docker build exclusions
├── pom.xml                                 # Maven dependencies
├── run-load-test.sh                        # Load test runner script
│
├── src/main/java/com/ratelimitx/core/
│   ├── RateLimitXApplication.java          # Main entry point
│   │
│   ├── circuitbreaker/
│   │   ├── CircuitBreaker.java             # Circuit breaker state machine
│   │   ├── CircuitBreakerState.java        # CLOSED, OPEN, HALF_OPEN enum
│   │   └── LocalRateLimiter.java           # In-memory fallback
│   │
│   ├── config/
│   │   ├── RedisConfig.java                # Redis connection setup
│   │   └── RateLimitConfig.java            # Rate limit configuration
│   │
│   ├── controller/
│   │   ├── ApiController.java              # Rate-limited API endpoint
│   │   ├── AdminController.java            # Admin & monitoring endpoints
│   │   └── MetricsController.java          # Metrics endpoints
│   │
│   ├── model/
│   │   └── RateLimitResult.java            # Rate limit check result
│   │
│   └── service/
│       ├── RateLimiterService.java         # Fixed Window implementation
│       ├── TokenBucketService.java         # Token Bucket implementation
│       ├── SlidingWindowService.java       # Sliding Window implementation
│       ├── ResilientRateLimiter.java       # Circuit breaker integration
│       └── MetricsService.java             # Metrics tracking
│
├── src/main/resources/
│   └── application.properties              # Configuration
│
├── src/test/scala/loadtest/
│   └── RateLimitXSimulation.scala          # Gatling load tests
│
└── target/gatling/
    └── */index.html                        # Load test reports
```

---

## 🧪 Quick Testing

### Basic Rate Limit Test
```bash
# Send 12 requests (limit is 10)
for i in {1..12}; do
  echo "Request $i:"
  curl -s -H "X-API-Key: testuser" http://localhost:8080/api/data
  echo ""
done
```

**Expected:**
- Requests 1-10: `Success! Here's your data`
- Requests 11-12: `Rate limit exceeded`

### Circuit Breaker Test
```bash
# Check initial state
curl http://localhost:8080/admin/circuit

# Stop Redis
docker stop ratelimitx-redis

# Requests still work (fallback mode)
curl -H "X-API-Key: test" http://localhost:8080/api/data

# Check circuit (should be OPEN)
curl http://localhost:8080/admin/circuit

# Restart Redis
docker start ratelimitx-redis

# Reset circuit
curl -X POST http://localhost:8080/admin/circuit/reset
```

---

## 📊 Configuration

### application.properties
```properties
# Server
server.port=8080

# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Rate Limiting
ratelimit.algorithm=sliding-window    # fixed-window, token-bucket, sliding-window
ratelimit.bucket-capacity=10          # Token bucket capacity
ratelimit.refill-rate=1.0             # Tokens per second
ratelimit.max-requests=10             # Max requests per window
ratelimit.window-size-seconds=60      # Window duration

# Circuit Breaker (in code)
# FAILURE_THRESHOLD=3
# TIMEOUT_DURATION_MS=30000
```

---

## 🎯 Use Cases

| Use Case | Recommended Algorithm | Configuration |
|----------|----------------------|---------------|
| Simple API Protection | Fixed Window | 100 req/min |
| Public REST API | Token Bucket | 10 capacity, 1/sec refill |
| Premium Tier Limiting | Sliding Window | Custom per-user limits |
| Burst-Tolerant API | Token Bucket | High capacity, fast refill |
| Strict Rate Limiting | Sliding Window | Low tolerance |

---

## 👨‍💻 Author

**Rugved Gundawar**

- GitHub: [Rugved-142](https://github.com/Rugved-142)
- LinkedIn: [Rugved](https://www.linkedin.com/in/rugved-gundawar/)
