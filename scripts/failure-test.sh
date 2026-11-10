#!/usr/bin/env bash
#
# Pauses Redis under steady load and records what the service did.
#
# The sequence is: drive steady load at /api/data, let it settle, `docker compose pause redis`,
# hold the pause, unpause, and keep driving load long enough for the circuit breaker to close
# again. A pause freezes the process instead of stopping it, so connections stay open and
# commands simply never get an answer - which is the failure mode the 200 ms Redis timeout and
# the circuit breaker are there for.
#
# Nothing here prints a result. The raw request log, circuit breaker poll log and event
# timestamps are written under docs/failure-tests/<date>/, and docs/failure-test.md is generated
# from them by scripts/analyze_failure_test.py, so no number in the documentation can be typed by
# hand.
#
# Usage:
#   scripts/failure-test.sh [--baseline N] [--pause N] [--recovery N] [--rate N] [--users N]
#                           [--tier USER|PREMIUM|ADMIN] [--algorithm NAME] [--label TEXT]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${RLX_BASE_URL:-http://localhost:8080}"
ALGORITHM="${RLX_ALGORITHM:-sliding-window}"
LABEL=""

# Phase lengths in seconds. The recovery phase must comfortably exceed the breaker's open
# timeout, or the run would end before the circuit had a chance to close.
BASELINE_SECONDS="${RLX_BASELINE_SECONDS:-30}"
PAUSE_SECONDS="${RLX_PAUSE_SECONDS:-40}"
RECOVERY_SECONDS="${RLX_RECOVERY_SECONDS:-70}"

RATE="${RLX_RATE:-20}"
USERS="${RLX_USERS:-5}"
TIER="${RLX_TIER:-PREMIUM}"

BENCH_PASSWORD="failure-test-password-123"
AUTH_LIMIT_FOR_PROVISIONING=10000

while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline) BASELINE_SECONDS="$2"; shift 2 ;;
    --pause) PAUSE_SECONDS="$2"; shift 2 ;;
    --recovery) RECOVERY_SECONDS="$2"; shift 2 ;;
    --rate) RATE="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --tier) TIER="$2"; shift 2 ;;
    --algorithm) ALGORITHM="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

DATE_DIR="$(date -u +%Y-%m-%d)"
OUT_ROOT="docs/failure-tests/$DATE_DIR"
mkdir -p "$OUT_ROOT"

log() { printf '\n== %s\n' "$*"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

for tool in docker curl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done

if [[ ! -f .env ]]; then
  echo ".env not found. Copy .env.example and set real values first." >&2
  exit 1
fi

wait_for_healthy() {
  local health=unknown
  for _ in $(seq 1 60); do
    health="$(docker inspect --format '{{.State.Health.Status}}' ratelimitx-app 2>/dev/null || echo unknown)"
    [[ "$health" == "healthy" ]] && return 0
    sleep 2
  done
  echo "ratelimitx-app is not healthy (status: $health)" >&2
  return 1
}

log "Checking the stack is up"
if ! docker compose ps --status running --services 2>/dev/null | grep -qx app; then
  echo "The app service is not running. Start it with: docker compose up -d --build" >&2
  exit 1
fi
wait_for_healthy

# ---------------------------------------------------------------------------
# Cleanup
#
# However this script exits, Redis must not be left paused and the production auth throttle must
# be back in place. A test that leaves the stack broken is worse than no test.
# ---------------------------------------------------------------------------

cleanup() {
  log "Cleaning up"
  docker compose unpause redis >/dev/null 2>&1 || true
  unset RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE
  docker compose up -d app >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Provisioning
#
# The auth endpoints are capped at 5 per client IP per minute, which is the right production
# default and is left alone. It is raised for provisioning only - it applies to /auth/**, never
# to /api/data, which is what is measured - and restored on exit. The raised value is recorded in
# environment.json.
# ---------------------------------------------------------------------------

log "Raising the auth throttle for provisioning (production default is restored on exit)"
export RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE="$AUTH_LIMIT_FOR_PROVISIONING"
export RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE="$AUTH_LIMIT_FOR_PROVISIONING"
docker compose up -d app >/dev/null
wait_for_healthy

register() {
  curl -s -o /dev/null -X POST "$BASE_URL/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"email\":\"$1@failure-test.invalid\",\"password\":\"$BENCH_PASSWORD\"}" \
    || true
}

login_token() {
  curl -s -X POST "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$BENCH_PASSWORD\"}" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin).get("token", ""))'
}

log "Preparing the admin account"
ADMIN_USER="failureadmin"
register "$ADMIN_USER"

POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2-)"
POSTGRES_PASSWORD_VALUE="$(grep -E '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)"

# Registration always creates a USER and no endpoint can create the first ADMIN, so it is made
# directly in the database.
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD_VALUE" ratelimitx-postgres \
  psql -U "$POSTGRES_USER_VALUE" -d "$POSTGRES_DB_VALUE" -q -c \
  "UPDATE users SET role='ADMIN', rate_limit=100000 WHERE username='$ADMIN_USER';"

ADMIN_TOKEN="$(login_token "$ADMIN_USER")"
if [[ -z "$ADMIN_TOKEN" ]]; then
  echo "could not obtain an admin token" >&2
  exit 1
fi

log "Selecting the $ALGORITHM algorithm"
curl -s -o /dev/null -X POST "$BASE_URL/admin/algorithm" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"algorithm\":\"$ALGORITHM\"}"

ACTIVE_ALGORITHM="$(curl -s "$BASE_URL/admin/algorithm" -H "Authorization: Bearer $ADMIN_TOKEN" \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["algorithm"])')"
if [[ "$ACTIVE_ALGORITHM" != "$ALGORITHM" ]]; then
  echo "algorithm is $ACTIVE_ALGORITHM, expected $ALGORITHM" >&2
  exit 1
fi

log "Registering $USERS load users on the $TIER tier"
TOKENS_FILE="$OUT_ROOT/tokens.json"
: > "$OUT_ROOT/.tokens.tmp"
for index in $(seq 1 "$USERS"); do
  username="failureload$index"
  register "$username"
  # Put the user on the requested tier. This goes through POST /admin/limit, which writes the
  # user row the limiter actually reads.
  curl -s -o /dev/null -X POST "$BASE_URL/admin/limit" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$username\",\"role\":\"$TIER\"}"
  token="$(login_token "$username")"
  if [[ -z "$token" ]]; then
    echo "could not obtain a token for $username" >&2
    exit 1
  fi
  printf '%s\t%s\n' "$username" "$token" >> "$OUT_ROOT/.tokens.tmp"
done

python3 -I - "$OUT_ROOT/.tokens.tmp" "$TOKENS_FILE" <<'PYEOF'
import json, sys
users = []
with open(sys.argv[1], encoding="utf-8") as handle:
    for line in handle:
        line = line.rstrip("\n")
        if not line:
            continue
        username, token = line.split("\t", 1)
        users.append({"username": username, "token": token})
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(users, handle, indent=2)
    handle.write("\n")
print("  prepared " + str(len(users)) + " load users")
PYEOF
rm -f "$OUT_ROOT/.tokens.tmp"

# The per-user limit the limiter will apply, read back from the service rather than assumed.
USER_LIMIT="$(curl -s "$BASE_URL/admin/user/failureload1" -H "Authorization: Bearer $ADMIN_TOKEN" \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["maxRequests"])')"

# ---------------------------------------------------------------------------
# A clean starting point
# ---------------------------------------------------------------------------

log "Resetting the limiter state and the circuit breaker"
docker exec ratelimitx-redis redis-cli FLUSHDB >/dev/null
curl -s -o /dev/null -X POST "$BASE_URL/admin/circuit/reset" -H "Authorization: Bearer $ADMIN_TOKEN"

CIRCUIT_JSON="$(curl -s "$BASE_URL/admin/circuit" -H "Authorization: Bearer $ADMIN_TOKEN")"
FAILURE_THRESHOLD="$(printf '%s' "$CIRCUIT_JSON" | python3 -c 'import sys, json; print(json.load(sys.stdin)["failureThreshold"])')"
BREAKER_TIMEOUT_MS="$(printf '%s' "$CIRCUIT_JSON" | python3 -c 'import sys, json; print(json.load(sys.stdin)["timeoutDurationMs"])')"
START_STATE="$(printf '%s' "$CIRCUIT_JSON" | python3 -c 'import sys, json; print(json.load(sys.stdin)["state"])')"
if [[ "$START_STATE" != "CLOSED" ]]; then
  echo "the circuit breaker is $START_STATE, expected CLOSED before the run" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Environment capture
# ---------------------------------------------------------------------------

log "Recording the environment"
os_name="$(uname -s)"
case "$os_name" in
  Darwin)
    cpu_model="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
    cpu_count="$(sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
    mem_bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
    os_version="$(sw_vers -productName 2>/dev/null) $(sw_vers -productVersion 2>/dev/null)"
    ;;
  Linux)
    cpu_model="$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//' || echo unknown)"
    [[ -z "$cpu_model" ]] && cpu_model="$(uname -m)"
    cpu_count="$(nproc 2>/dev/null || echo unknown)"
    mem_kb="$(grep -m1 MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0)"
    mem_bytes=$(( mem_kb * 1024 ))
    if [[ -r /etc/os-release ]]; then os_version="$(. /etc/os-release && echo "$PRETTY_NAME")";
    else os_version="$(uname -sr)"; fi
    ;;
  *) cpu_model="unknown"; cpu_count="unknown"; mem_bytes=0; os_version="$(uname -sr)" ;;
esac

docker_version="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
compose_version="$(docker compose version --short 2>/dev/null || echo unknown)"
git_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
# Whether the code that produced this run was committed. This run's own outputs are excluded:
# docs/failure-test.md is tracked and this script rewrites it, so including it would mark every
# run as unreproducible on the strength of its own report.
git_dirty="$(test -n "$(git status --porcelain -- . ':!docs/failure-tests' ':!docs/failure-test.md' 2>/dev/null)" && echo true || echo false)"

python3 -I - "$OUT_ROOT/environment.json" <<PYEOF
import json, sys
env = {
    "capturedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
    "label": "$LABEL",
    "host": {
        "os": "$os_version",
        "kernel": "$(uname -sr)",
        "arch": "$(uname -m)",
        "cpuModel": """$cpu_model""".strip(),
        "cpuCount": "$cpu_count",
        "memoryBytes": int("$mem_bytes" or 0),
    },
    "loadGenerator": {"sharesHostWithService": True, "driver": "scripts/failure_load.py"},
    "docker": {"engine": "$docker_version", "compose": "$compose_version"},
    "repository": {"commit": "$git_commit", "uncommittedChanges": "$git_dirty" == "true"},
    "parameters": {
        "algorithm": "$ACTIVE_ALGORITHM",
        "baseUrl": "$BASE_URL",
        "userCount": int("$USERS"),
        "tier": "$TIER",
        "userLimit": int("$USER_LIMIT"),
        "ratePerSecond": float("$RATE"),
        "baselineSeconds": float("$BASELINE_SECONDS"),
        "pauseSeconds": float("$PAUSE_SECONDS"),
        "recoverySeconds": float("$RECOVERY_SECONDS"),
        "failureThreshold": int("$FAILURE_THRESHOLD"),
        "breakerTimeoutMs": int("$BREAKER_TIMEOUT_MS"),
        # Raised for provisioning only; it does not affect /api/data, the endpoint measured here.
        "authThrottlePerMinuteDuringRun": int("$AUTH_LIMIT_FOR_PROVISIONING"),
    },
}
with open(sys.argv[1], "w") as handle:
    json.dump(env, handle, indent=2)
    handle.write("\n")
print("  wrote " + sys.argv[1])
PYEOF

# ---------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------

TOTAL_SECONDS=$(( BASELINE_SECONDS + PAUSE_SECONDS + RECOVERY_SECONDS ))

log "Starting load: $RATE req/s for ${TOTAL_SECONDS}s across $USERS users"
LOAD_STARTED_MS="$(now_ms)"
python3 -I scripts/failure_load.py \
  --base-url "$BASE_URL" \
  --tokens "$TOKENS_FILE" \
  --admin-token "$ADMIN_TOKEN" \
  --out-dir "$OUT_ROOT" \
  --duration-seconds "$TOTAL_SECONDS" \
  --rate "$RATE" \
  --poll-interval-ms 100 \
  > "$OUT_ROOT/load-output.log" 2>&1 &
LOAD_PID=$!

sleep "$BASELINE_SECONDS"

log "Pausing Redis for ${PAUSE_SECONDS}s"
# Both timestamps are recorded. The analysis uses the moment the command was sent, which makes
# every transition time an upper bound: Redis cannot have frozen before the command was issued.
PAUSE_SENT_MS="$(now_ms)"
docker compose pause redis >/dev/null
PAUSE_RETURNED_MS="$(now_ms)"

sleep "$PAUSE_SECONDS"

log "Unpausing Redis, then watching for ${RECOVERY_SECONDS}s"
UNPAUSE_SENT_MS="$(now_ms)"
docker compose unpause redis >/dev/null
UNPAUSE_RETURNED_MS="$(now_ms)"

wait "$LOAD_PID" || echo "  the load driver exited non-zero; see $OUT_ROOT/load-output.log" >&2
LOAD_FINISHED_MS="$(now_ms)"
cat "$OUT_ROOT/load-output.log"

python3 -I - "$OUT_ROOT/events.json" <<PYEOF
import json, sys
events = {
    "loadStartedAtMs": int("$LOAD_STARTED_MS"),
    "redisPausedAtMs": int("$PAUSE_SENT_MS"),
    "redisPauseCommandReturnedAtMs": int("$PAUSE_RETURNED_MS"),
    "redisUnpausedAtMs": int("$UNPAUSE_SENT_MS"),
    "redisUnpauseCommandReturnedAtMs": int("$UNPAUSE_RETURNED_MS"),
    "loadFinishedAtMs": int("$LOAD_FINISHED_MS"),
    "note": (
        "redisPausedAtMs and redisUnpausedAtMs are the moments the docker commands were sent, "
        "not the moments they returned. Using the earlier timestamp makes every transition time "
        "measured from them an upper bound."
    ),
}
with open(sys.argv[1], "w") as handle:
    json.dump(events, handle, indent=2)
    handle.write("\n")
print("  wrote " + sys.argv[1])
PYEOF

# The tokens are credentials for throwaway accounts on a local stack, but there is no reason to
# commit them.
rm -f "$TOKENS_FILE"

# A dated results directory should explain itself without reference to the script that made it.
cat > "$OUT_ROOT/README.md" <<'READMEEOF'
# Failure test run data

Raw output from `scripts/failure-test.sh`. `docs/failure-test.md` is generated from these files
by `scripts/analyze_failure_test.py`; that file is generated and should not be edited by hand.

| File | What it is |
|------|------------|
| `environment.json` | Machine, Docker and load parameters captured before the run |
| `events.json` | When load started, when the pause and unpause commands were sent and returned, when load stopped |
| `requests.csv` | One row per request: send and receive time, latency, status, `X-Algorithm` header, error |
| `circuit.jsonl` | A poll of `/admin/circuit` roughly every 100 ms, one JSON object per line |
| `load-summary.json` | What the load driver was asked to do and what it recorded |
| `load-output.log` | Console output of the load driver |
| `summary.json` | The measurements the analysis derived, in machine-readable form |

The load users' JWTs are not kept: they are credentials for throwaway accounts on a local stack.
READMEEOF

log "Generating the report"
python3 -I scripts/analyze_failure_test.py --results "$OUT_ROOT" --output docs/failure-test.md

log "Done"
echo "  raw results: $OUT_ROOT"
echo "  report:      docs/failure-test.md"
