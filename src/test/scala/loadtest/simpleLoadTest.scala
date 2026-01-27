package loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Simple Load Test - Start with this!
 */
class SimpleLoadTest extends Simulation {

  // Configuration
  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")

  // Simple scenario: Just hit the API endpoint
  val simpleTest = scenario("Simple API Test")
    .exec(
      http("API Request")
        .get("/api/data")
        .header("X-API-Key", "gatling-test-user")
        .check(status.in(200, 429))
    )

  // Run: 50 users over 30 seconds
  setUp(
    simpleTest.inject(
      rampUsers(50).during(30.seconds)
    )
  ).protocols(httpProtocol)
}