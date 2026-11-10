#!/usr/bin/env bash
#
# End-to-end check that a freshly started stack actually works.
#
# This is the last step of the definition of done for this repository: from a fresh clone,
#   cp .env.example .env    (then put real values in it)
#   docker compose up --build
#   scripts/smoke-test.sh
# must pass. It replaces the manual sequence that used to be in the README, which nobody could
# run without copying eight commands out of a document and reading the output themselves.
#
# Every step asserts. A smoke test that prints output for a human to inspect is a demo, not a
# test: it passes whatever happens.
#
# Works against docker compose or a Kubernetes deployment - it only needs a base URL - but the
# actuator check needs a container to exec into, so it is skipped when neither is reachable.
#
# Usage:
#   scripts/smoke-test.sh [--base-url URL] [--container NAME | --no-container] [--keep-user]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${RLX_BASE_URL:-http://localhost:8080}"
KEEP_USER=false

# Some checks - the metrics scrape, the readiness group, removing the test user - need to get
# inside the service, which here means `docker exec` into the compose container. They are gated
# on this name rather than on "is there a container called ratelimitx-app", because when the
# compose stack and the kind cluster are both up on one machine that question answers yes while
# the traffic is going to the cluster: the checks would then inspect one deployment and report on
# another. scripts/kind-up.sh passes --no-container for exactly that reason.
CONTAINER="${RLX_CONTAINER:-ratelimitx-app}"
DB_CONTAINER="${RLX_DB_CONTAINER:-ratelimitx-postgres}"

# A unique user per run, so a second run is not defeated by the first one's registration and so
# the rate limit counters start empty. The password is only ever used against this local stack.
SMOKE_USER="smoke$(date +%s)$$"
SMOKE_PASSWORD="smoke-test-password-123"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --no-container) CONTAINER=""; DB_CONTAINER=""; shift ;;
    --keep-user) KEEP_USER=true; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# A note on `grep -q`, which bit this script once and is easy to reintroduce:
#
# `producer | grep -q pattern` under `set -o pipefail` fails on a MATCH when the producer is still
# writing. grep exits at the first hit, the producer dies of SIGPIPE, and pipefail takes the
# producer's status for the pipeline's. It only shows up when the output is big enough that the
# producer has not finished: searching a 170KB metrics dump for something near the top reported
# "missing", while the same search for something near the bottom worked. Every search below feeds
# grep from a herestring, so there is no pipe to break.

PASSED=0
FAILED=0

pass() { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$1"; }
fail() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$1" >&2; }
step() { printf '\n== %s\n' "$*"; }

# Asserts an exact value, and prints both sides when it does not match - a bare "FAIL" tells the
# reader nothing about what went wrong.
expect() {
  local what="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    pass "$what"
  else
    fail "$what: expected $expected, got $actual"
  fi
}

# curl already prints 000 when it cannot connect, so the failure branch must not print another
# one: `|| echo 000` produced "000000", which no comparison would ever match and which read as a
# nonsense status in the output.
status_of() {
  curl -s -o /dev/null -w '%{http_code}' "$@" || true
}

for tool in curl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done

# ---------------------------------------------------------------------------
step "Service is reachable at $BASE_URL"
# ---------------------------------------------------------------------------

reachable=false
for _ in $(seq 1 60); do
  code="$(status_of "$BASE_URL/api/data")"
  # 401 is the right answer to an unauthenticated request and proves the whole chain is up:
  # the container is listening, Spring Security is wired, and the entry point is the REST one.
  if [[ "$code" == "401" ]]; then
    reachable=true
    break
  fi
  sleep 2
done
if [[ "$reachable" != true ]]; then
  echo "  FAIL  $BASE_URL/api/data never returned 401 (last status: ${code:-none})" >&2
  echo >&2
  echo "The stack does not look ready. Start it with:  docker compose up -d --build" >&2
  exit 1
fi
pass "an unauthenticated GET /api/data returns 401, not 403 or a connection error"

# A port that answers is not the same as an application that has finished starting. Run against a
# container that came up seconds ago and the datasource is still warming and the meters are not
# all registered yet, which produces failures that say nothing about the service. Wait for the
# readiness group where there is a container to ask.
if [[ -n "$CONTAINER" ]] && command -v docker >/dev/null 2>&1 \
    && grep -qx "$CONTAINER" <<< "$(docker ps --format '{{.Names}}' 2>/dev/null)"; then
  for _ in $(seq 1 60); do
    readiness="$(docker exec "$CONTAINER" curl -fsS \
      http://localhost:9091/actuator/health/readiness 2>/dev/null \
      | python3 -c 'import sys, json; print(json.load(sys.stdin)["status"])' 2>/dev/null || true)"
    [[ "$readiness" == "UP" ]] && break
    sleep 2
  done
  expect "the application reports itself ready" UP "${readiness:-none}"
fi

expect "a request with a forged token is rejected" 401 \
  "$(status_of "$BASE_URL/api/data" -H 'Authorization: Bearer not.a.real.token')"

expect "an unknown path is not enumerable by an anonymous caller" 401 \
  "$(status_of "$BASE_URL/definitely-not-a-real-path")"

# ---------------------------------------------------------------------------
step "Actuator is not published to the host"
# ---------------------------------------------------------------------------

# The management port is deliberately unpublished: Prometheus reaches it over the internal
# network. If this ever starts answering from the host, the hardening has been undone.
management_host="$(printf '%s' "$BASE_URL" | sed -E 's#(https?://[^:/]+).*#\1#')"
code="$(status_of "$management_host:9091/actuator/health")"
if [[ "$code" == "000" ]]; then
  pass "port 9091 is not reachable from the host"
else
  fail "the management port answered from the host with $code; it must not be published"
fi

# ---------------------------------------------------------------------------
step "Registration, login and the rate limit"
# ---------------------------------------------------------------------------

register_code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SMOKE_USER\",\"email\":\"$SMOKE_USER@smoke.invalid\",\"password\":\"$SMOKE_PASSWORD\"}")"
case "$register_code" in
  200|201) pass "registered $SMOKE_USER" ;;
  429) fail "registration was throttled (429). The auth throttle allows 5 per minute per IP; wait a minute and retry" ;;
  *) fail "registration returned $register_code" ;;
esac

TOKEN="$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASSWORD\"}" \
  | python3 -c 'import sys, json; print(json.load(sys.stdin).get("token", ""))' 2>/dev/null || true)"
if [[ -n "$TOKEN" ]]; then
  pass "logged in and received a token"
else
  fail "login returned no token; the remaining checks cannot run"
  printf '\n%s passed, %s failed\n' "$PASSED" "$FAILED"
  exit 1
fi

# Status and body together: a bare status tells the reader nothing about why the service
# disagreed, and "expected 401, got 400" with no body sent me looking in the wrong place once.
wrong_password_response="$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SMOKE_USER\",\"password\":\"not-the-password\"}" || true)"
wrong_password_code="$(printf '%s' "$wrong_password_response" | tail -1)"
if [[ "$wrong_password_code" == "401" ]]; then
  pass "the wrong password is rejected"
else
  fail "the wrong password is rejected: expected 401, got $wrong_password_code; body: $(printf '%s' "$wrong_password_response" | sed '$d' | tr -d '\n')"
fi

# A new USER-tier account has a limit of 10 per minute. 15 requests must therefore produce
# exactly 10 admissions and 5 denials - not "roughly", because the limiter is exact and a test
# that accepts a range would not notice it drifting.
step "Enforcing the USER tier limit of 10 per minute"
allowed=0
denied=0
other=0
retry_after=""
for _ in $(seq 1 15); do
  response="$(curl -s -o /dev/null -D - -w '%{http_code}' "$BASE_URL/api/data" \
    -H "Authorization: Bearer $TOKEN")"
  code="$(printf '%s' "$response" | tail -1)"
  case "$code" in
    200) allowed=$((allowed + 1)) ;;
    429)
      denied=$((denied + 1))
      [[ -z "$retry_after" ]] && retry_after="$(printf '%s' "$response" \
        | awk 'BEGIN{IGNORECASE=1} /^Retry-After:/ {gsub(/\r/,""); print $2; exit}')"
      ;;
    *) other=$((other + 1)) ;;
  esac
done

expect "exactly 10 requests were admitted" 10 "$allowed"
expect "exactly 5 requests were denied" 5 "$denied"
expect "nothing returned an unexpected status" 0 "$other"

if [[ -n "$retry_after" ]] && [[ "$retry_after" =~ ^[0-9]+$ ]] && [[ "$retry_after" -ge 1 ]]; then
  pass "a denial carries a Retry-After header ($retry_after seconds)"
else
  fail "a denial did not carry a usable Retry-After header (got '${retry_after:-none}')"
fi

headers="$(curl -s -D - -o /dev/null "$BASE_URL/api/data" -H "Authorization: Bearer $TOKEN")"
for header in X-RateLimit-Limit X-RateLimit-Remaining X-RateLimit-Reset X-Algorithm; do
  if grep -qi "^$header:" <<< "$headers"; then
    pass "the response carries $header"
  else
    fail "the response is missing $header"
  fi
done

# ---------------------------------------------------------------------------
step "Privilege separation"
# ---------------------------------------------------------------------------

expect "a USER-tier caller cannot reach /admin" 403 \
  "$(status_of "$BASE_URL/admin/stats" -H "Authorization: Bearer $TOKEN")"

# ---------------------------------------------------------------------------
step "Metrics"
# ---------------------------------------------------------------------------

# Only possible where there is a container to exec into; the point is that the metrics the
# Grafana dashboards and the alert rules are built on actually exist.
if command -v docker >/dev/null 2>&1 \
    && grep -qx "$CONTAINER" <<< "$(docker ps --format '{{.Names}}' 2>/dev/null)"; then
  # Micrometer publishes a timer only once something has been recorded into it, so these appear
  # a moment after the traffic above rather than at startup. Retry briefly instead of reporting a
  # missing metric that is about to exist.
  # Scraped once into a variable, then searched. See the note on grep -q at the top.
  metrics=""
  for _ in $(seq 1 15); do
    metrics="$(docker exec "$CONTAINER" curl -fsS \
      http://localhost:9091/actuator/prometheus 2>/dev/null || true)"
    # The HTTP timer only exists once a request has been recorded into it, so this can need a
    # moment after the traffic above.
    if grep -q '^http_server_requests_seconds_count' <<< "$metrics"; then
      break
    fi
    sleep 2
  done
  for metric in ratelimit_requests_total app_circuitbreaker_state http_server_requests_seconds_count; do
    if grep -q "^$metric" <<< "$metrics"; then
      pass "$metric is exported"
    else
      fail "$metric is missing from /actuator/prometheus"
    fi
  done
  health="$(docker exec "$CONTAINER" curl -fsS http://localhost:9091/actuator/health 2>/dev/null \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["status"])' 2>/dev/null || echo "?")"
  expect "the container reports itself healthy" UP "$health"
else
  printf '  skip  metrics checks (no container to exec into; this run drove %s)\n' "$BASE_URL"
fi

# ---------------------------------------------------------------------------
step "Cleaning up"
# ---------------------------------------------------------------------------

if [[ "$KEEP_USER" == true ]]; then
  printf '  kept  %s\n' "$SMOKE_USER"
elif [[ -n "$DB_CONTAINER" ]] && command -v docker >/dev/null 2>&1 \
    && grep -qx "$DB_CONTAINER" <<< "$(docker ps --format '{{.Names}}' 2>/dev/null)"; then
  # Leaving a user behind per run would slowly fill the table on a long-lived stack.
  if docker exec -i "$DB_CONTAINER" sh -c \
      'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q' \
      <<< "DELETE FROM users WHERE username='$SMOKE_USER';" >/dev/null 2>&1; then
    printf '  ok    removed the smoke test user\n'
  else
    printf '  note  could not remove %s; delete it by hand if it matters\n' "$SMOKE_USER"
  fi
else
  printf '  note  left %s behind; no reachable database container to remove it from\n' "$SMOKE_USER"
fi

# ---------------------------------------------------------------------------
printf '\n'
if [[ "$FAILED" -eq 0 ]]; then
  printf '%s checks passed.\n' "$PASSED"
  exit 0
fi
printf '%s passed, %s FAILED.\n' "$PASSED" "$FAILED" >&2
exit 1
