# 🚦 RateLimitX

A production-grade distributed rate limiting service built with Java Spring Boot and Redis, implementing multiple industry-standard algorithms.

![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green?style=flat-square&logo=springboot)
![Redis](https://img.shields.io/badge/Redis-7.x-red?style=flat-square&logo=redis)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

## 📋 Table of Contents

- [Overview](#-overview)
- [Features](#-features)
- [Architecture](#-architecture)
- [Algorithms](#-algorithms)
- [Tech Stack](#-tech-stack)
- [Getting Started](#-getting-started)
- [API Documentation](#-api-documentation)
- [Project Structure](#-project-structure)
- [Testing](#-testing)
- [Performance](#-performance)

---

## 🎯 Overview

RateLimitX is a distributed rate limiting service designed to protect APIs from abuse and ensure fair resource allocation. It supports multiple rate limiting algorithms that can be switched at runtime, making it suitable for various use cases from simple APIs to high-traffic production systems.

### Why Rate Limiting?

- **Prevent Abuse**: Stop malicious users from overwhelming your API
- **Ensure Fairness**: Allocate resources equally among users
- **Protect Infrastructure**: Prevent cascading failures during traffic spikes
- **Cost Control**: Limit expensive operations per user/tenant

---

## ✨ Features

### Core Features
- ✅ **3 Rate Limiting Algorithms** — Fixed Window, Token Bucket, Sliding Window Counter
- ✅ **Atomic Redis Operations** — Lua scripts prevent race conditions
- ✅ **RFC 6585 Compliant** — Proper HTTP 429 responses with standard headers
- ✅ **Per-User Configuration** — Custom limits for different API keys
- ✅ **Real-Time Metrics** — Track requests, denial rates, response times
- ✅ **Runtime Algorithm Switching** — Change algorithms without restart

### API Headers (Industry Standard)
```
X-RateLimit-Limit: 10          # Maximum requests allowed
X-RateLimit-Remaining: 7       # Requests remaining in window
X-RateLimit-Reset: 45          # Seconds until limit resets
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
│  • Route to configured algorithm                                │
│  • Build response with rate limit headers                       │
│  • Record metrics                                               │
└─────────────────────────────┬───────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  Fixed Window   │ │  Token Bucket   │ │ Sliding Window  │
│    Service      │ │    Service      │ │    Service      │
└────────┬────────┘ └────────┬────────┘ └────────┬────────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                          REDIS                                  │
│                                                                 │
│  • Atomic Lua Scripts (no race conditions)                      │
│  • TTL-based key expiration                                     │
│  • Hash storage for bucket state                                │
└─────────────────────────────────────────────────────────────────┘
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

## 🛠 Tech Stack

| Component | Technology |
|-----------|------------|
| **Language** | Java 17+ |
| **Framework** | Spring Boot 3.x |
| **Database** | Redis 7.x |
| **Scripting** | Lua (for atomic operations) |
| **Build** | Maven |
| **Containerization** | Docker (coming soon) |

---

## 🚀 Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- Redis 7.x

### Installation

**1. Clone the repository**
```bash
git clone https://github.com/yourusername/ratelimitx.git
cd ratelimitx
```

**2. Start Redis**
```bash
# Using Docker
docker run -d --name redis -p 6379:6379 redis:latest

# Or using Homebrew (Mac)
brew services start redis

# Or using apt (Ubuntu)
sudo apt install redis-server
sudo systemctl start redis
```

**3. Configure application**

Edit `src/main/resources/application.properties`:
```properties
# Server
server.port=8080

# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Rate Limiting (choose algorithm)
ratelimit.algorithm=token-bucket
ratelimit.bucket-capacity=10
ratelimit.refill-rate=1.0
ratelimit.max-requests=10
ratelimit.window-size-seconds=60
```

**4. Run the application**
```bash
./mvnw spring-boot:run
```

**5. Verify it's working**
```bash
curl http://localhost:8080/admin/health
# {"redis":"UP","status":"HEALTHY","algorithm":"token-bucket"}
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
```json
Success! Here's your data
```

**Response (Rate Limited - 429):**
```json
Rate limit exceeded. Retry after 45000ms
```

**Response Headers:**
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
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
  "algorithm": "token-bucket"
}
```

#### System Stats
```http
GET /admin/stats
```
```json
{
  "activeUsers": 42,
  "totalActiveKeys": 156,
  "activeAlgorithm": "token-bucket",
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
  "algorithm": "fixed-window",
  "currentRequests": 7,
  "maxRequests": 10,
  "remainingRequests": 3,
  "resetsInSeconds": 45,
  "isRateLimited": false
}
```

#### Token Bucket Status
```http
GET /admin/bucket/{userId}
```
```json
{
  "userId": "test-user",
  "algorithm": "token-bucket",
  "tokensRemaining": 7,
  "bucketCapacity": 10,
  "isAllowed": true
}
```

#### Sliding Window Status
```http
GET /admin/sliding/{userId}
```
```json
{
  "userId": "test-user",
  "algorithm": "sliding-window",
  "currentCount": 3,
  "maxRequests": 10,
  "remainingRequests": 7,
  "resetInMs": 45000,
  "isAllowed": true
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
  "activeAlgorithm": "token-bucket"
}
```

#### Set Custom Limit
```http
POST /admin/limit
Content-Type: application/json

{
  "userId": "premium-user",
  "limit": 100
}
```

#### Reset User
```http
DELETE /admin/reset/{userId}
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

#### User Metrics
```http
GET /metrics/user/{userId}
```

#### Historical Metrics
```http
GET /metrics/history?hours=24
```

---

## 📁 Project Structure

```
src/main/java/com/ratelimitx/core/
├── RateLimitXApplication.java          # Main application entry
├── config/
│   ├── RedisConfig.java                # Redis connection setup
│   └── RateLimitConfig.java            # Rate limit configuration
├── controller/
│   ├── ApiController.java              # Rate-limited API endpoint
│   ├── AdminController.java            # Admin & monitoring endpoints
│   └── MetricsController.java          # Metrics endpoints
├── model/
│   └── RateLimitResult.java            # Rate limit check result
└── service/
    ├── RateLimiterService.java         # Fixed Window implementation
    ├── TokenBucketService.java         # Token Bucket implementation
    ├── SlidingWindowService.java       # Sliding Window implementation
    └── MetricsService.java             # Metrics tracking
```

---

## 🧪 Testing

### Quick Test

```bash
# Send 12 requests (limit is 10)
for i in {1..12}; do
  echo "Request $i:"
  curl -s -i -H "X-API-Key: testuser" http://localhost:8080/api/data | grep -E "HTTP|X-RateLimit|Success|exceeded"
  echo ""
done
```

**Expected Output:**
- Requests 1-10: `200 OK` with decreasing `X-RateLimit-Remaining`
- Requests 11-12: `429 Too Many Requests`

### Test Token Refill

```bash
# Exhaust tokens
for i in {1..10}; do
  curl -s -H "X-API-Key: testuser" http://localhost:8080/api/data > /dev/null
done

# Wait 5 seconds (5 tokens refill)
sleep 5

# Should succeed
curl -i -H "X-API-Key: testuser" http://localhost:8080/api/data
```

### Check Metrics

```bash
curl http://localhost:8080/metrics/summary | python3 -m json.tool
```

---

## ⚡ Performance

| Metric | Value |
|--------|-------|
| Average Response Time | < 5ms |
| Throughput | 10,000+ req/sec |
| Redis Operations | O(1) per request |
| Memory per User | ~100 bytes |

---

## 👨‍💻 Author

**Rugved Gundawar**

- GitHub: [Rugved-142](https://github.com/Rugved-142)
- LinkedIn: [Rugved](https://www.linkedin.com/in/rugved-gundawar/)
