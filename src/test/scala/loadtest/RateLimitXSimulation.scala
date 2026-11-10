package loadtest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import io.gatling.core.Predef._
import io.gatling.http.Predef._

/**
 * Load test for the JWT-protected rate limiting API.
 *
 * The previous version of this file sent an `X-API-Key` header and no `Authorization` header at
 * all, so every request it made was rejected with 401 and none of it ever exercised the rate
 * limiter. It also asserted on a success rate while treating 429 as a valid response, which are
 * contradictory positions: either a denial is a successful rate limit decision or it is a failed
 * request, and the assertions cannot assume both.
 *
 * This simulation takes the first position. A 200 and a 429 are both correct answers from a rate
 * limiter, so both count as successful requests. Anything else - a 401, a 500, a timeout - is a
 * failure, because it means the service stopped making decisions.
 *
 * Everything is configured by system property so a run can be reproduced from the recorded
 * parameters:
 *
 *   -Drlx.baseUrl=http://localhost:8080
 *   -Drlx.users.user / .premium / .admin   how many users of each tier to create
 *   -Drlx.rps.start / .step / .steps       stepped arrival rate, requests per second
 *   -Drlx.step.seconds                     how long to hold each step
 *   -Drlx.warmup.seconds                   discarded warm-up before the steps
 *   -Drlx.decisions.file                   where to write the allowed/denied tally
 *   -Drlx.admin.token                      token for an ADMIN account, used to set tiers
 */
class RateLimitXSimulation extends Simulation {

  // ==================== configuration ====================

  private def prop(name: String, default: String): String =
    sys.props.getOrElse(name, default)

  private def intProp(name: String, default: Int): Int =
    prop(name, default.toString).trim.toInt

  private val baseUrl = prop("rlx.baseUrl", "http://localhost:8080")
  private val password = prop("rlx.password", "benchmark-password-123")

  private val tierCounts: Seq[(String, Int, Int)] = Seq(
    // (role, number of users, requests per minute for that tier)
    ("USER", intProp("rlx.users.user", 10), 10),
    ("PREMIUM", intProp("rlx.users.premium", 5), 100),
    ("ADMIN", intProp("rlx.users.admin", 1), 1000)
  ).filter(_._2 > 0)

  private val startRps = intProp("rlx.rps.start", 50)
  private val stepRps = intProp("rlx.rps.step", 50)
  private val stepCount = intProp("rlx.rps.steps", 4)
  private val stepSeconds = intProp("rlx.step.seconds", 30)
  private val warmupSeconds = intProp("rlx.warmup.seconds", 10)

  private val decisionsFile = prop("rlx.decisions.file", "target/gatling-decisions.json")
  private val adminToken = prop("rlx.admin.token", "")

  // ==================== decision tally ====================

  // Gatling's own statistics count requests, not outcomes, and a request name cannot be derived
  // from the response. Allowed and denied are therefore tallied here and written out for the
  // report generator, which needs them separately: a per-minute cap turns most of a sustained
  // load into denials, so a single throughput figure would say almost nothing.
  private val allowed = new AtomicLong()
  private val denied = new AtomicLong()
  private val unexpected = new AtomicLong()

  // ==================== setup ====================

  private val http2Client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private def postJson(path: String, body: String, bearer: Option[String] = None): (Int, String) = {
    val builder = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(20))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    bearer.foreach(token => builder.header("Authorization", "Bearer " + token))
    val response = http2Client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())
  }

  /** Minimal extraction of one string field; avoids pulling in a JSON library for two fields. */
  private def jsonString(body: String, field: String): Option[String] = {
    val marker = "\"" + field + "\""
    val at = body.indexOf(marker)
    if (at < 0) return None
    val colon = body.indexOf(':', at + marker.length)
    if (colon < 0) return None
    val open = body.indexOf('"', colon)
    if (open < 0) return None
    val close = body.indexOf('"', open + 1)
    if (close < 0) return None
    Some(body.substring(open + 1, close))
  }

  /** username -> bearer token, built before any load is applied. */
  private val tokens = scala.collection.mutable.ArrayBuffer.empty[Map[String, String]]

  private def runSuffix = java.lang.Long.toString(System.currentTimeMillis(), 36)

  before {
    val suffix = runSuffix
    println(s"[setup] creating users against $baseUrl")

    tierCounts.foreach { case (role, count, tierLimit) =>
      (1 to count).foreach { i =>
        val username = s"bench${role.toLowerCase}$i$suffix"
        val email = s"$username@benchmark.invalid"

        val (registerStatus, registerBody) = postJson("/auth/register",
          s"""{"username":"$username","email":"$email","password":"$password"}""")
        if (registerStatus != 201) {
          throw new IllegalStateException(
            s"[setup] register $username failed with $registerStatus: $registerBody")
        }

        // Registration always creates a USER. Promoting the other tiers needs an admin token;
        // without one the run would silently measure a single tier while claiming a mix.
        if (role != "USER") {
          if (adminToken.isEmpty) {
            throw new IllegalStateException(
              s"[setup] rlx.admin.token is required to create $role users")
          }
          val (limitStatus, limitBody) = postJson("/admin/limit",
            s"""{"username":"$username","role":"$role","rateLimit":$tierLimit}""",
            Some(adminToken))
          if (limitStatus != 200) {
            throw new IllegalStateException(
              s"[setup] setting tier $role for $username failed with $limitStatus: $limitBody")
          }
        }

        val (loginStatus, loginBody) = postJson("/auth/login",
          s"""{"username":"$username","password":"$password"}""")
        if (loginStatus != 200) {
          throw new IllegalStateException(
            s"[setup] login $username failed with $loginStatus: $loginBody")
        }

        val token = jsonString(loginBody, "token").getOrElse(
          throw new IllegalStateException(s"[setup] no token in login response for $username"))

        tokens += Map("token" -> token, "benchUser" -> username, "benchRole" -> role)
      }
    }

    println(s"[setup] ${tokens.size} users ready: " +
      tierCounts.map { case (role, count, limit) => s"$count x $role@$limit/min" }.mkString(", "))
  }

  after {
    val total = allowed.get() + denied.get() + unexpected.get()
    val json =
      s"""{
         |  "allowed": ${allowed.get()},
         |  "denied": ${denied.get()},
         |  "unexpected": ${unexpected.get()},
         |  "total": $total,
         |  "users": ${tokens.size},
         |  "tierMix": "${tierCounts.map { case (r, c, l) => s"$c x $r@$l/min" }.mkString("; ")}",
         |  "startRps": $startRps,
         |  "stepRps": $stepRps,
         |  "steps": $stepCount,
         |  "stepSeconds": $stepSeconds,
         |  "warmupSeconds": $warmupSeconds
         |}
         |""".stripMargin

    val path = Paths.get(decisionsFile)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, json.getBytes(StandardCharsets.UTF_8))
    println(s"[teardown] allowed=${allowed.get()} denied=${denied.get()} " +
      s"unexpected=${unexpected.get()} -> $decisionsFile")
  }

  // ==================== scenario ====================

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .userAgentHeader("Gatling/RateLimitX-Benchmark")
    .shareConnections

  // Round-robin over the tokens so the tier mix is exercised in proportion.
  //
  // Deliberately a lazy iterator rather than tokens.circular: the simulation body, including
  // setUp, is evaluated when the class is constructed, which happens before the before block has
  // registered anyone. Converting the buffer to a feeder here would capture it while still empty.
  private val cursor = new java.util.concurrent.atomic.AtomicInteger(0)
  private val tokenFeeder: Iterator[Map[String, String]] = Iterator.continually {
    val snapshot = tokens
    if (snapshot.isEmpty) {
      throw new IllegalStateException("no benchmark users were created; see the setup output")
    }
    snapshot(Math.floorMod(cursor.getAndIncrement(), snapshot.size))
  }

  private val rateLimitedRequest = scenario("Rate limited API")
    .feed(tokenFeeder)
    .exec(
      http("GET /api/data")
        .get("/api/data")
        .header("Authorization", "Bearer #{token}")
        // Tally first: this check never fails, it only observes.
        .check(status.transform { code =>
          if (code == 200) allowed.incrementAndGet()
          else if (code == 429) denied.incrementAndGet()
          else unexpected.incrementAndGet()
          code
        })
        // A denial is a correct answer from a rate limiter, so 200 and 429 are both successes.
        // Anything else means the service stopped deciding, and is a failure.
        .check(status.in(200, 429))
        .check(header("X-RateLimit-Limit").exists)
        .check(header("X-Algorithm").exists)
    )

  // ==================== injection ====================

  // Open model: arrival rate is imposed regardless of how fast the service responds, so a
  // slowing service shows up as a growing queue rather than as reduced load. A closed model
  // would quietly throttle the offered load to match the service and measure nothing useful.
  private val steps = (0 until stepCount).map { i =>
    constantUsersPerSec((startRps + i * stepRps).toDouble).during(stepSeconds.seconds)
  }

  private val injection =
    if (warmupSeconds > 0) {
      constantUsersPerSec(startRps.toDouble).during(warmupSeconds.seconds) +: steps
    } else {
      steps
    }

  setUp(
    // head/tail rather than `injection: _*`: inject's first parameter is separate from its
    // repeated tail, so a bare sequence does not match.
    rateLimitedRequest.inject(injection.head, injection.tail: _*)
  ).protocols(httpProtocol)
    .assertions(
      // The only assertion is that the service never stopped making decisions. No latency or
      // throughput threshold is asserted, because no measured baseline exists yet to justify a
      // number; see docs/benchmarks.md.
      global.failedRequests.count.is(0)
    )
}
