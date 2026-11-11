#!/usr/bin/env bash
#
# Runs the Gatling benchmark three times and saves the raw results, together with the environment
# they were produced on, under docs/benchmarks/<date>/.
#
# Nothing here prints a performance number. The figures in docs/benchmarks.md are generated from
# the files this script writes, by scripts/report_benchmarks.py, so that no number in the
# documentation can be typed by hand.
#
# Works on Linux and macOS. It deliberately does not open a browser: the report is a file.
#
# Usage:
#   scripts/benchmark.sh [--runs N] [--algorithm NAME] [--label TEXT]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RUNS=3
ALGORITHM="${RLX_ALGORITHM:-sliding-window}"
LABEL=""
BASE_URL="${RLX_BASE_URL:-http://localhost:8080}"

# Load shape. Overridable from the environment so a run can be widened without editing the script.
USERS_USER="${RLX_USERS_USER:-10}"
USERS_PREMIUM="${RLX_USERS_PREMIUM:-5}"
USERS_ADMIN="${RLX_USERS_ADMIN:-1}"
RPS_START="${RLX_RPS_START:-50}"
RPS_STEP="${RLX_RPS_STEP:-50}"
RPS_STEPS="${RLX_RPS_STEPS:-4}"
STEP_SECONDS="${RLX_STEP_SECONDS:-30}"
WARMUP_SECONDS="${RLX_WARMUP_SECONDS:-10}"

# Generated per run, not a literal.
#
# These accounts live for one run on a local stack, so the value hardly matters - but a password
# sitting in a tracked file is a password, and grep does not care what it was for. Generated
# fresh each time instead.
#
# The accounts are reused by name across runs, so the rows are dropped before registering:
# otherwise the second run would register nothing and then try this run's password against last
# run's stored hash.
BENCH_PASSWORD="$(openssl rand -hex 24)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs) RUNS="$2"; shift 2 ;;
    --algorithm) ALGORITHM="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

DATE_DIR="$(date -u +%Y-%m-%d)"
OUT_ROOT="docs/benchmarks/$DATE_DIR"
mkdir -p "$OUT_ROOT"

log() { printf '\n== %s\n' "$*"; }

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

for tool in docker curl python3 openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done

if [[ ! -f .env ]]; then
  echo ".env not found. Copy .env.example and set real values first." >&2
  exit 1
fi

log "Checking the stack is up"
if ! grep -qx app <<< "$(docker compose ps --status running --services 2>/dev/null)"; then
  echo "The app service is not running. Start it with: docker compose up -d --build" >&2
  exit 1
fi

# Wait for the application's own healthcheck rather than guessing with a sleep.
for _ in $(seq 1 60); do
  health="$(docker inspect --format '{{.State.Health.Status}}' ratelimitx-app 2>/dev/null || echo unknown)"
  [[ "$health" == "healthy" ]] && break
  sleep 2
done
if [[ "$health" != "healthy" ]]; then
  echo "ratelimitx-app is not healthy (status: $health)" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Admin account
#
# Registration always creates a USER. Promoting anyone to another tier needs an ADMIN token, and
# the first ADMIN has to be made directly in the database because no endpoint can create one.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Provisioning allowance
#
# /auth/register and /auth/login are capped at 5 per client IP per minute. That is the right
# production default and it is deliberately left alone, but it also means provisioning this run's
# users would take minutes. The cap is raised for provisioning only; it applies to the auth
# endpoints, never to /api/data, which is what is being measured. The raised value is recorded in
# environment.json, and the original is restored when the script exits however it exits.
# ---------------------------------------------------------------------------

AUTH_LIMIT_FOR_PROVISIONING=10000
restore_auth_limits() {
  log "Restoring the production auth throttle limits"
  unset RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE
  docker compose up -d app >/dev/null 2>&1 || true
}
trap restore_auth_limits EXIT

log "Raising the auth throttle for provisioning (production default is restored on exit)"
export RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE="$AUTH_LIMIT_FOR_PROVISIONING"
export RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE="$AUTH_LIMIT_FOR_PROVISIONING"
docker compose up -d app >/dev/null
for _ in $(seq 1 60); do
  health="$(docker inspect --format '{{.State.Health.Status}}' ratelimitx-app 2>/dev/null || echo unknown)"
  [[ "$health" == "healthy" ]] && break
  sleep 2
done
if [[ "$health" != "healthy" ]]; then
  echo "ratelimitx-app did not come back healthy after raising the auth limits" >&2
  exit 1
fi

ADMIN_USER="benchadmin"

POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2-)"
POSTGRES_PASSWORD_VALUE="$(grep -E '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)"

bench_psql() {
  docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD_VALUE" ratelimitx-postgres \
    psql -U "$POSTGRES_USER_VALUE" -d "$POSTGRES_DB_VALUE" -q
}

log "Clearing this run's accounts from a previous run"
bench_psql <<SQL
DELETE FROM users WHERE username = '$ADMIN_USER'
  OR username LIKE 'user%' OR username LIKE 'premium%' OR username LIKE 'admin%';
SQL

log "Preparing the admin account used to set user tiers"
set +e
curl -s -o /dev/null -X POST "$BASE_URL/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"email\":\"$ADMIN_USER@benchmark.invalid\",\"password\":\"$BENCH_PASSWORD\"}"
set -e

bench_psql <<SQL
UPDATE users SET role='ADMIN', rate_limit=100000 WHERE username='$ADMIN_USER';
SQL

ADMIN_TOKEN="$(curl -s -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$BENCH_PASSWORD\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"

if [[ -z "$ADMIN_TOKEN" ]]; then
  echo "could not obtain an admin token" >&2
  exit 1
fi

log "Selecting the $ALGORITHM algorithm"
curl -s -o /dev/null -X POST "$BASE_URL/admin/algorithm" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"algorithm\":\"$ALGORITHM\"}"

ACTIVE_ALGORITHM="$(curl -s "$BASE_URL/admin/algorithm" -H "Authorization: Bearer $ADMIN_TOKEN" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["algorithm"])')"

if [[ "$ACTIVE_ALGORITHM" != "$ALGORITHM" ]]; then
  echo "algorithm is $ACTIVE_ALGORITHM, expected $ALGORITHM" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Environment capture
#
# A throughput number is meaningless without the machine it was produced on, so this is recorded
# before the first run and saved beside the results.
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
    if [[ -r /etc/os-release ]]; then
      os_version="$(. /etc/os-release && echo "$PRETTY_NAME")"
    else
      os_version="$(uname -sr)"
    fi
    ;;
  *)
    cpu_model="unknown"; cpu_count="unknown"; mem_bytes=0; os_version="$(uname -sr)"
    ;;
esac

jvm_version="$(./mvnw -B -q --version 2>/dev/null | grep -i '^Java version' || true)"
[[ -z "$jvm_version" ]] && jvm_version="$(java -version 2>&1 | head -1 || echo unknown)"

app_java_version="$(docker exec ratelimitx-app java -version 2>&1 | head -1 || echo unknown)"
app_jvm_flags="$(docker inspect --format '{{json .Config.Entrypoint}}' ratelimitx-app 2>/dev/null || echo '[]')"
app_memory_limit="$(docker inspect --format '{{.HostConfig.Memory}}' ratelimitx-app 2>/dev/null || echo 0)"
app_cpu_limit="$(docker inspect --format '{{.HostConfig.NanoCpus}}' ratelimitx-app 2>/dev/null || echo 0)"

docker_version="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
compose_version="$(docker compose version --short 2>/dev/null || echo unknown)"
git_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
# Whether the code that produced this run was committed.
#
# Generated measurement output is excluded, all of it, not just this script's own. A run's
# report is rewritten by the run itself, and a batch of runs would otherwise mark every run
# after the first as unreproducible on the strength of the previous one's output. None of
# these paths is source: they are what the source produced.
GENERATED=(
  ':!docs/benchmarks' ':!docs/benchmarks.md'
  ':!docs/failure-tests' ':!docs/failure-test.md' ':!docs/failure-test-kubernetes.md'
  ':!docs/pod-kill-tests' ':!docs/pod-kill-test.md'
)
git_dirty="$(test -n "$(git status --porcelain -- . "${GENERATED[@]}" 2>/dev/null)" && echo true || echo false)"

python3 - "$OUT_ROOT/environment.json" <<PYEOF
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
    "loadGenerator": {
        # Gatling runs on this machine, against containers on the same machine. The generator and
        # the service therefore compete for the same CPUs, which caps the achievable rate and adds
        # latency that a separate generator would not.
        "sharesHostWithService": True,
        "jvm": """$jvm_version""".strip(),
    },
    "service": {
        "javaVersion": """$app_java_version""".strip(),
        "entrypoint": json.loads("""$app_jvm_flags"""),
        "containerMemoryLimitBytes": int("$app_memory_limit" or 0),
        "containerNanoCpus": int("$app_cpu_limit" or 0),
    },
    "docker": {"engine": "$docker_version", "compose": "$compose_version"},
    "repository": {"commit": "$git_commit", "uncommittedChanges": "$git_dirty" == "true"},
    "parameters": {
        "algorithm": "$ACTIVE_ALGORITHM",
        # Raised for provisioning only; it does not affect /api/data, the endpoint measured here.
        "authThrottlePerMinuteDuringRun": int("$AUTH_LIMIT_FOR_PROVISIONING"),
        "baseUrl": "$BASE_URL",
        "tierMix": {"USER": int("$USERS_USER"), "PREMIUM": int("$USERS_PREMIUM"), "ADMIN": int("$USERS_ADMIN")},
        "userCount": int("$USERS_USER") + int("$USERS_PREMIUM") + int("$USERS_ADMIN"),
        "rpsStart": int("$RPS_START"),
        "rpsStep": int("$RPS_STEP"),
        "rpsSteps": int("$RPS_STEPS"),
        "stepSeconds": int("$STEP_SECONDS"),
        "warmupSeconds": int("$WARMUP_SECONDS"),
        "runs": int("$RUNS"),
    },
}
with open(sys.argv[1], "w") as handle:
    json.dump(env, handle, indent=2)
    handle.write("\n")
print("  wrote " + sys.argv[1])
PYEOF

# ---------------------------------------------------------------------------
# Runs
# ---------------------------------------------------------------------------

for run in $(seq 1 "$RUNS"); do
  run_dir="$OUT_ROOT/run-$run"
  mkdir -p "$run_dir"
  log "Run $run of $RUNS"

  # Each run starts from an empty limiter state so run 2 does not inherit run 1's counters.
  docker exec ratelimitx-redis redis-cli FLUSHDB >/dev/null

  decisions_file="$REPO_ROOT/$run_dir/decisions.json"

  set +e
  ./mvnw -B gatling:test \
    -Dgatling.simulationClass=loadtest.RateLimitXSimulation \
    -Drlx.baseUrl="$BASE_URL" \
    -Drlx.admin.token="$ADMIN_TOKEN" \
    -Drlx.users.user="$USERS_USER" \
    -Drlx.users.premium="$USERS_PREMIUM" \
    -Drlx.users.admin="$USERS_ADMIN" \
    -Drlx.rps.start="$RPS_START" \
    -Drlx.rps.step="$RPS_STEP" \
    -Drlx.rps.steps="$RPS_STEPS" \
    -Drlx.step.seconds="$STEP_SECONDS" \
    -Drlx.warmup.seconds="$WARMUP_SECONDS" \
    -Drlx.decisions.file="$decisions_file" \
    2>&1 | tee "$run_dir/gatling-output.log"
  status=${PIPESTATUS[0]}
  set -e

  echo "$status" > "$run_dir/exit-code.txt"

  # Copy the raw Gatling results rather than linking them: target/ is disposable.
  latest="$(ls -td target/gatling/*/ 2>/dev/null | head -1 || true)"
  if [[ -n "$latest" && -d "$latest" ]]; then
    mkdir -p "$run_dir/gatling"
    cp -R "$latest." "$run_dir/gatling/" 2>/dev/null || true

    # Keep the data, drop the viewer. simulation.log is the raw record and js/*.json holds the
    # computed statistics; the rest of the HTML report is a vendored copy of a charting library
    # and its assets, which is a few hundred kilobytes of third-party code per run and is not
    # evidence of anything. The HTML can be regenerated from simulation.log at any time.
    find "$run_dir/gatling" -type f ! -name 'simulation.log' ! -name '*.json' -delete 2>/dev/null || true
    find "$run_dir/gatling" -type d -empty -delete 2>/dev/null || true

    echo "  raw results copied from $latest"
  else
    echo "  WARNING: no Gatling output directory found for run $run" >&2
  fi

  if [[ "$status" -ne 0 ]]; then
    echo "  run $run exited with $status; the report will record it as failed" >&2
  fi
done

# A dated results directory should explain itself without reference to the script that made it.
cat > "$OUT_ROOT/README.md" <<'READMEEOF'
# Benchmark run data

Raw output from `scripts/benchmark.sh`. `docs/benchmarks.md` is generated from these files by
`scripts/report_benchmarks.py`; that file is generated and should not be edited by hand.

| File | What it is |
|------|------------|
| `environment.json` | Machine, container, Docker and load parameters captured before the first run |
| `run-N/decisions.json` | Allowed, denied and unexpected response tallies, written by the simulation |
| `run-N/exit-code.txt` | Exit status of the Gatling process for that run |
| `run-N/gatling-output.log` | Console output of the run |
| `run-N/gatling/simulation.log` | Gatling's raw per-request record |
| `run-N/gatling/js/*.json` | Gatling's computed statistics |

The HTML report's own assets, a vendored charting library of a few hundred kilobytes per run, are
not kept: they are third-party code rather than measurement data. Gatling can rebuild the HTML
from `simulation.log`.
READMEEOF

log "Generating the report"
python3 -I scripts/report_benchmarks.py --results "$OUT_ROOT" --output docs/benchmarks.md

log "Done"
echo "  raw results: $OUT_ROOT"
echo "  report:      docs/benchmarks.md"
