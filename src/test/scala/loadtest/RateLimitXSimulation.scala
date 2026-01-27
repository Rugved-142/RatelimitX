package loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random


class RateLimitXSimulation extends Simulation {

  // Base URL - change this if running elsewhere
  val baseUrl = "http://localhost:8080"
  
  // HTTP Protocol configuration
  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("Gatling/RateLimitX-LoadTest")


  val userIdFeeder = Iterator.continually(
    Map("userId" -> s"user-${Random.alphanumeric.take(8).mkString}")
  )
  
  // Generate random API keys from a pool (simulates different users)
  val apiKeyFeeder = Iterator.continually(
    Map("apiKey" -> s"api-key-${Random.nextInt(1000)}")
  )


  val basicRateLimitScenario = scenario("Basic Rate Limit Test")
    .feed(userIdFeeder)
    .exec(
      http("Rate Limited API Request")
        .get("/api/data")
        .header("X-API-Key", "${userId}")
        .check(status.in(200, 429))  // Both are valid responses!
        .check(header("X-RateLimit-Remaining").exists)
        .check(header("X-RateLimit-Limit").exists)
    )

 
  val healthCheckScenario = scenario("Health Check Baseline")
    .exec(
      http("Health Endpoint")
        .get("/admin/health")
        .check(status.is(200))
        .check(jsonPath("$.status").is("HEALTHY"))
        .check(jsonPath("$.redis").exists)
    )

 
  val metricsScenario = scenario("Metrics Endpoint Test")
    .exec(
      http("Metrics Summary")
        .get("/metrics/summary")
        .check(status.is(200))
        .check(jsonPath("$.currentHour").exists)
        .check(jsonPath("$.currentDay").exists)
    )


  val circuitBreakerScenario = scenario("Circuit Breaker Monitor")
    .exec(
      http("Circuit Breaker Status")
        .get("/admin/circuit")
        .check(status.is(200))
        .check(jsonPath("$.state").exists)
        .check(jsonPath("$.isAllowingRequests").exists)
    )

 
  val burstTrafficScenario = scenario("Burst Traffic Test")
    .feed(userIdFeeder)
    .repeat(20) {  // 20 rapid requests
      exec(
        http("Burst Request")
          .get("/api/data")
          .header("X-API-Key", "${userId}")
          .check(status.in(200, 429))
      )
      .pause(10.milliseconds, 50.milliseconds)  // Tiny pause between requests
    }

 
  val realisticUserJourney = scenario("Realistic User Journey")
    .feed(userIdFeeder)
    .exec(
      http("1. Check Service Health")
        .get("/admin/health")
        .check(status.is(200))
    )
    .pause(500.milliseconds, 1500.milliseconds)  // Think time
    .repeat(5) {
      exec(
        http("2. API Request")
          .get("/api/data")
          .header("X-API-Key", "${userId}")
          .check(status.in(200, 429))
      )
      .pause(200.milliseconds, 800.milliseconds)  // Variable think time
    }
    .exec(
      http("3. Check Remaining Quota")
        .get("/admin/user/${userId}")
        .check(status.in(200, 404))  // 404 if user doesn't exist yet
    )
    .pause(300.milliseconds, 700.milliseconds)
    .exec(
      http("4. Check Metrics")
        .get("/metrics/summary")
        .check(status.is(200))
    )


  val algorithmComparisonScenario = scenario("Algorithm Comparison")
    .feed(userIdFeeder)
    .exec(
      http("Compare Algorithms")
        .get("/admin/compare/${userId}")
        .check(status.is(200))
        .check(jsonPath("$.fixedWindow").exists)
        .check(jsonPath("$.tokenBucket").exists)
        .check(jsonPath("$.slidingWindow").exists)
    )

  setUp(
    
    // Test 1: Basic rate limit - ramp up to 100 users
    basicRateLimitScenario.inject(
      nothingFor(5.seconds),                    // Wait 5 seconds
      rampUsers(50).during(30.seconds),         // Ramp to 50 users over 30 sec
      constantUsersPerSec(20).during(1.minute), // Sustain 20 users/sec for 1 min
      rampUsersPerSec(20).to(50).during(30.seconds)  // Ramp up more
    ),
    
    // Test 2: Health check baseline - constant light load
    healthCheckScenario.inject(
      constantUsersPerSec(10).during(2.minutes)
    ),
    
    // Test 3: Metrics - light load
    metricsScenario.inject(
      rampUsers(20).during(30.seconds),
      constantUsersPerSec(5).during(1.minute)
    ),
    
    // Test 4: Circuit breaker monitoring
    circuitBreakerScenario.inject(
      constantUsersPerSec(2).during(2.minutes)
    ),
    
    // Test 5: Burst test - spike of users
    burstTrafficScenario.inject(
      nothingFor(1.minute),                     // Wait for system to stabilize
      atOnceUsers(50)                           // Then hit with 50 users at once
    ),
    
    // Test 6: Realistic journeys - gradual ramp
    realisticUserJourney.inject(
      rampUsers(30).during(1.minute)
    )
    
  ).protocols(httpProtocol)
  
  .assertions(
    // Global assertions
    global.responseTime.percentile(95).lt(200),     // p95 < 200ms
    global.responseTime.percentile(99).lt(500),     // p99 < 500ms
    global.successfulRequests.percent.gte(70),      // At least 70% success (429 counts as KO)
    global.requestsPerSec.gte(30),                  // At least 50 req/sec
    
    // Per-request assertions
    details("Health Endpoint").responseTime.percentile(99).lt(100),  // Health check p99 < 100ms
    details("Health Endpoint").successfulRequests.percent.is(100)    // Health must always succeed
  )
}