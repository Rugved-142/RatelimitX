#!/usr/bin/env bash
#
# Pauses Redis under steady load and records what the service did.
#
# The sequence is: drive steady load at /api/data, let it settle, freeze Redis, hold it frozen,
# let it go, and keep driving load long enough for the circuit breaker to close again. Freezing
# rather than stopping is the point: connections stay open and commands simply never get an
# answer, which is the failure mode the 200 ms Redis timeout and the circuit breaker are there
# for. On compose that is `docker compose pause`; on Kubernetes it is SIGSTOP to the server
# process. See plat_pause_redis.
#
# Nothing here prints a result. The raw request log, circuit breaker poll log and event
# timestamps are written under docs/failure-tests/<date>/, and docs/failure-test.md is generated
# from them by scripts/analyze_failure_test.py, so no number in the documentation can be typed by
# hand.
#
# Runs against either platform. --platform compose drives docker-compose.yml; --platform
# kubernetes drives the kind cluster scripts/kind-up.sh creates, where the same frozen-Redis
# failure is produced by stopping the redis process with SIGSTOP rather than by the Docker
# freezer. Everything platform-specific is behind the plat_* functions below.
#
# Usage:
#   scripts/failure-test.sh [--platform compose|kubernetes]
#                           [--baseline N] [--pause N] [--recovery N] [--rate N] [--users N]
#                           [--tier USER|PREMIUM|ADMIN] [--algorithm NAME] [--label TEXT]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PLATFORM="${RLX_PLATFORM:-compose}"
BASE_URL="${RLX_BASE_URL:-}"
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
AUTH_LIMIT_FOR_PROVISIONING=10000
# The production value, restored on exit. It matches application.properties, docker-compose.yml
# and k8s/10-configmap.yaml; a change in one of those belongs here too.
AUTH_LIMIT_PRODUCTION=5

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform) PLATFORM="$2"; shift 2 ;;
    --baseline) BASELINE_SECONDS="$2"; shift 2 ;;
    --pause) PAUSE_SECONDS="$2"; shift 2 ;;
    --recovery) RECOVERY_SECONDS="$2"; shift 2 ;;
    --rate) RATE="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --tier) TIER="$2"; shift 2 ;;
    --algorithm) ALGORITHM="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

case "$PLATFORM" in
  compose|kubernetes) ;;
  *) echo "unknown platform: $PLATFORM (expected compose or kubernetes)" >&2; exit 2 ;;
esac

# The two platforms publish the API on different ports, so the default follows the platform.
if [[ -z "$BASE_URL" ]]; then
  if [[ "$PLATFORM" == "kubernetes" ]]; then
    BASE_URL="http://127.0.0.1:18080"
  else
    BASE_URL="http://localhost:8080"
  fi
fi

DATE_DIR="$(date -u +%Y-%m-%d)"
OUT_ROOT="docs/failure-tests/$PLATFORM/$DATE_DIR"
mkdir -p "$OUT_ROOT"

log() { printf '\n== %s\n' "$*"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# ---------------------------------------------------------------------------
# Platform
#
# Everything that differs between docker compose and Kubernetes lives in these functions and
# nowhere else, so the measurement itself is identical on both and the two runs are comparable.
# ---------------------------------------------------------------------------

K8S_NAMESPACE="${RLX_K8S_NAMESPACE:-ratelimitx}"
K8S_CONTEXT="${RLX_K8S_CONTEXT:-kind-ratelimitx}"
# The kind node is itself a Docker container. Freezing Redis has to be done from here; see
# plat_pause_redis for why it cannot be done from inside the pod.
K8S_NODE="${RLX_K8S_NODE:-ratelimitx-control-plane}"
kube() { kubectl --context "$K8S_CONTEXT" -n "$K8S_NAMESPACE" "$@"; }

if [[ "$PLATFORM" == "compose" ]]; then
  REQUIRED_TOOLS=(docker curl python3 openssl)
else
  REQUIRED_TOOLS=(docker kubectl curl python3 openssl)
fi
for tool in "${REQUIRED_TOOLS[@]}"; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done

plat_check_running() {
  if [[ "$PLATFORM" == "compose" ]]; then
    if [[ ! -f .env ]]; then
      echo ".env not found. Copy .env.example and set real values first." >&2
      exit 1
    fi
    if ! grep -qx app <<< "$(docker compose ps --status running --services 2>/dev/null)"; then
      echo "The app service is not running. Start it with: docker compose up -d --build" >&2
      exit 1
    fi
  else
    if ! kube get deployment ratelimitx >/dev/null 2>&1; then
      echo "No ratelimitx deployment in $K8S_CONTEXT/$K8S_NAMESPACE." >&2
      echo "Bring the cluster up with: scripts/kind-up.sh" >&2
      exit 1
    fi
  fi
}

plat_wait_healthy() {
  if [[ "$PLATFORM" == "compose" ]]; then
    local health=unknown
    for _ in $(seq 1 60); do
      health="$(docker inspect --format '{{.State.Health.Status}}' ratelimitx-app 2>/dev/null \
        || echo unknown)"
      [[ "$health" == "healthy" ]] && return 0
      sleep 2
    done
    echo "ratelimitx-app is not healthy (status: $health)" >&2
    return 1
  fi
  kube rollout status deployment/ratelimitx --timeout=300s >/dev/null || return 1
  # Both replicas have to be in the Service, not merely running, or the load is being sent to one
  # of them and the run would silently be a single-instance test.
  local want ready
  want="$(kube get deployment ratelimitx -o jsonpath='{.spec.replicas}')"
  for _ in $(seq 1 60); do
    ready="$(kube get endpointslice -l kubernetes.io/service-name=ratelimitx \
      -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' 2>/dev/null \
      | grep -c true || true)"
    [[ "${ready:-0}" -ge "$want" ]] && return 0
    sleep 2
  done
  echo "only ${ready:-0} of $want endpoints are ready" >&2
  return 1
}

# Raise or restore the per-IP auth throttle. Compose takes it from the environment; Kubernetes
# takes it from the ConfigMap, which needs a rollout for the pods to see it.
plat_set_auth_limit() {
  local value="$1"
  if [[ "$PLATFORM" == "compose" ]]; then
    if [[ "$value" == "default" ]]; then
      unset RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE
    else
      export RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE="$value"
      export RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE="$value"
    fi
    docker compose up -d app >/dev/null
  else
    local effective="$value"
    [[ "$value" == "default" ]] && effective="$AUTH_LIMIT_PRODUCTION"
    kube patch configmap ratelimitx-config --type merge -p \
      "{\"data\":{\"RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE\":\"$effective\",\
\"RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE\":\"$effective\"}}" >/dev/null
    kube rollout restart deployment/ratelimitx >/dev/null
  fi
  plat_wait_healthy
}

# Reads SQL on stdin rather than taking it as an argument: the Kubernetes form passes through
# two shells, and quoting a statement through both is a good way to produce a different statement.
plat_psql() {
  if [[ "$PLATFORM" == "compose" ]]; then
    docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD_VALUE" ratelimitx-postgres \
      psql -U "$POSTGRES_USER_VALUE" -d "$POSTGRES_DB_VALUE" -q
  else
    # The credentials come from the pod's own environment, so they are never on a command line.
    kube exec -i statefulset/postgres -- sh -c \
      'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q'
  fi
}

# The pod whose circuit breaker is observed. The breaker is per instance, so on Kubernetes a poll
# through the Service would land on an arbitrary replica and the resulting timeline would
# interleave two independent state machines. One pod is port-forwarded and polled directly, which
# gives a timeline with a single owner and keeps the figures comparable with the compose run.
OBSERVED_POD=""
CIRCUIT_BASE_URL="$BASE_URL"
PORT_FORWARD_PID=""

# A pod that is Ready and not already being deleted.
#
# `--field-selector status.phase=Running` is not enough: a pod that is terminating after a
# rollout is still Running, and port-forwarding to one of those fails a few seconds later. That
# is exactly what happened the first time this ran.
k8s_ready_app_pod() {
  kube get pods -l app.kubernetes.io/name=ratelimitx -o json | python3 -c '
import json, sys
for pod in json.load(sys.stdin)["items"]:
    if pod["metadata"].get("deletionTimestamp"):
        continue
    conditions = pod.get("status", {}).get("conditions", [])
    if any(c["type"] == "Ready" and c["status"] == "True" for c in conditions):
        print(pod["metadata"]["name"])
        break
'
}

plat_start_circuit_observer() {
  [[ "$PLATFORM" == "compose" ]] && return 0
  CIRCUIT_BASE_URL="http://127.0.0.1:18081"
  local attempt
  for attempt in 1 2 3; do
    OBSERVED_POD="$(k8s_ready_app_pod)"
    if [[ -z "$OBSERVED_POD" ]]; then
      sleep 3
      continue
    fi
    kube port-forward "pod/$OBSERVED_POD" 18081:8080 >/dev/null 2>&1 &
    PORT_FORWARD_PID=$!
    local _ status
    for _ in $(seq 1 15); do
      status="$(curl -s -o /dev/null -w '%{http_code}' "$CIRCUIT_BASE_URL/api/data" 2>/dev/null || true)"
      if [[ "$status" == "401" ]]; then
        echo "  observing $OBSERVED_POD directly on $CIRCUIT_BASE_URL"
        return 0
      fi
      sleep 1
    done
    echo "  port-forward to $OBSERVED_POD did not come up (attempt $attempt)" >&2
    plat_stop_circuit_observer
  done
  echo "could not port-forward to an application pod" >&2
  return 1
}

plat_stop_circuit_observer() {
  [[ -n "$PORT_FORWARD_PID" ]] && kill "$PORT_FORWARD_PID" 2>/dev/null || true
  PORT_FORWARD_PID=""
}

plat_redis_cli() {
  if [[ "$PLATFORM" == "compose" ]]; then
    docker exec ratelimitx-redis redis-cli "$@"
  else
    kube exec statefulset/redis -- redis-cli "$@"
  fi
}

# Freeze Redis: the process keeps its connections and simply stops answering.
#
# On compose that is `docker compose pause`, which uses the freezer cgroup.
#
# Kubernetes has no equivalent verb and kind's default CNI does not enforce NetworkPolicy, so the
# server process is sent SIGSTOP instead. It has to be sent from the kind node, not from inside
# the container: a signal with no handler sent to PID 1 of a PID namespace from inside that
# namespace is discarded by the kernel, so `kubectl exec ... kill -STOP 1` silently does nothing
# and Redis keeps answering - which is exactly what happened the first time this was written.
#
# Deleting the pod or scaling the StatefulSet to zero would refuse connections rather than hang
# them. That is a real failure too, and a different one; it is not what this test measures and it
# would not be comparable with the compose run.
REDIS_FROZEN_PID=""

k8s_redis_host_pid() {
  local cid
  cid="$(docker exec "$K8S_NODE" crictl ps --name '^redis$' -q 2>/dev/null | head -1)"
  [[ -n "$cid" ]] || { echo "no running redis container on $K8S_NODE" >&2; return 1; }
  docker exec "$K8S_NODE" crictl inspect "$cid" 2>/dev/null \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["info"]["pid"])'
}

plat_pause_redis() {
  if [[ "$PLATFORM" == "compose" ]]; then
    docker compose pause redis >/dev/null
    return
  fi
  REDIS_FROZEN_PID="$(k8s_redis_host_pid)"
  docker exec "$K8S_NODE" kill -STOP "$REDIS_FROZEN_PID"
  # Confirm it is actually stopped rather than trusting the signal: the whole measurement is
  # worthless if Redis kept answering.
  local state
  state="$(docker exec "$K8S_NODE" sh -c "awk '/^State:/{print \$2}' /proc/$REDIS_FROZEN_PID/status" 2>/dev/null || echo "?")"
  [[ "$state" == "T" ]] || { echo "redis pid $REDIS_FROZEN_PID is in state '$state', not T (stopped)" >&2; return 1; }
}

plat_unpause_redis() {
  if [[ "$PLATFORM" == "compose" ]]; then
    docker compose unpause redis >/dev/null
    return
  fi
  # If the kubelet restarted the container while it was frozen the pid is gone, which is itself
  # recorded in events.json; resume whatever is running now in that case.
  local pid="${REDIS_FROZEN_PID:-}"
  [[ -n "$pid" ]] || pid="$(k8s_redis_host_pid || true)"
  [[ -n "$pid" ]] && docker exec "$K8S_NODE" kill -CONT "$pid" 2>/dev/null || true
  REDIS_FROZEN_PID=""
}

plat_redis_restarts() {
  if [[ "$PLATFORM" == "compose" ]]; then
    docker inspect --format '{{.RestartCount}}' ratelimitx-redis 2>/dev/null || echo unknown
  else
    kube get pod redis-0 -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null \
      || echo unknown
  fi
}

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

log "Checking the stack is up ($PLATFORM)"
plat_check_running
plat_wait_healthy

# ---------------------------------------------------------------------------
# Cleanup
#
# However this script exits, Redis must not be left frozen and the production auth throttle must
# be back in place. A test that leaves the stack broken is worse than no test.
# ---------------------------------------------------------------------------

cleanup() {
  # Keep the status the script was exiting with. Without this the last command in here becomes
  # the script's exit status, so a run that failed half way through reports success - which it
  # did, once, and that is worse than the failure it was hiding.
  local status=$?
  log "Cleaning up"
  plat_stop_circuit_observer
  plat_unpause_redis >/dev/null 2>&1 || true
  plat_set_auth_limit default >/dev/null 2>&1 || true
  exit "$status"
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
plat_set_auth_limit "$AUTH_LIMIT_FOR_PROVISIONING"

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

ADMIN_USER="failureadmin"

if [[ "$PLATFORM" == "compose" ]]; then
  POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
  POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2-)"
  POSTGRES_PASSWORD_VALUE="$(grep -E '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)"
fi

log "Clearing this run's accounts from a previous run"
plat_psql <<SQL
DELETE FROM users WHERE username = '$ADMIN_USER' OR username LIKE 'failureload%';
SQL

log "Preparing the admin account"
register "$ADMIN_USER"

# Registration always creates a USER and no endpoint can create the first ADMIN, so it is made
# directly in the database.
plat_psql <<SQL
UPDATE users SET role='ADMIN', rate_limit=100000 WHERE username='$ADMIN_USER';
SQL

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
plat_redis_cli FLUSHDB >/dev/null
plat_start_circuit_observer
# Reset and read the breaker on the pod that will be observed, not through the Service: with more
# than one replica those are different machines.
curl -s -o /dev/null -X POST "$CIRCUIT_BASE_URL/admin/circuit/reset" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

CIRCUIT_JSON="$(curl -s "$CIRCUIT_BASE_URL/admin/circuit" -H "Authorization: Bearer $ADMIN_TOKEN")"
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

if [[ "$PLATFORM" == "kubernetes" ]]; then
  k8s_server="$(kubectl --context "$K8S_CONTEXT" version -o json 2>/dev/null \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["serverVersion"]["gitVersion"])' \
    2>/dev/null || echo unknown)"
  k8s_node_image="$(kubectl --context "$K8S_CONTEXT" get nodes \
    -o jsonpath='{.items[0].status.nodeInfo.osImage}' 2>/dev/null || echo unknown)"
  app_replicas="$(kube get deployment ratelimitx -o jsonpath='{.spec.replicas}' 2>/dev/null \
    || echo unknown)"
else
  k8s_server="n/a"
  k8s_node_image="n/a"
  app_replicas=1
fi
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
    "platform": {
        "name": "$PLATFORM",
        "kubernetesServer": "$k8s_server",
        "nodeOsImage": """$k8s_node_image""".strip(),
        # The number of application instances. It matters for every figure below: the local
        # fallback's counters are per instance, so N replicas in the fallback admit up to N times
        # the configured rate between them.
        "appReplicas": "$app_replicas",
    },
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
REDIS_RESTARTS_BEFORE="$(plat_redis_restarts)"

python3 -I scripts/failure_load.py \
  --base-url "$BASE_URL" \
  --circuit-base-url "$CIRCUIT_BASE_URL" \
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
plat_pause_redis
PAUSE_RETURNED_MS="$(now_ms)"

sleep "$PAUSE_SECONDS"

log "Unpausing Redis, then watching for ${RECOVERY_SECONDS}s"
UNPAUSE_SENT_MS="$(now_ms)"
plat_unpause_redis
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
    "platform": "$PLATFORM",
    "observedPod": "$OBSERVED_POD",
    # A frozen Redis fails its liveness probe. If the probe escalated to a restart part way
    # through, the pause was not one continuous outage and the figures mean something different,
    # so the counts are recorded either side rather than assumed equal.
    "redisRestartsBefore": "$REDIS_RESTARTS_BEFORE",
    "redisRestartsAfter": "$(plat_redis_restarts)",
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
# One report per platform: the two runs measure the same thing on different deployments and
# overwriting one with the other would lose half the evidence.
if [[ "$PLATFORM" == "kubernetes" ]]; then
  REPORT=docs/failure-test-kubernetes.md
else
  REPORT=docs/failure-test.md
fi
python3 -I scripts/analyze_failure_test.py --results "$OUT_ROOT" --output "$REPORT"

log "Done"
echo "  raw results: $OUT_ROOT"
echo "  report:      $REPORT"
