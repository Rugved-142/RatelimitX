#!/usr/bin/env bash
#
# Deletes one of the two application replicas under steady load and records what the caller saw.
#
# This is the question a two-replica deployment is supposed to answer: can a pod go away without
# anyone noticing? The readiness probe, the preStop pause and graceful shutdown in
# k8s/30-app.yaml all exist for this, and none of them is worth claiming until it has been
# measured.
#
# The pod is deleted normally, not with --force --grace-period=0. A normal delete is what a node
# drain, a rollout and an eviction all do, so it is the case worth testing; a forced delete skips
# every mechanism being tested here and would only measure how fast the kubelet can SIGKILL.
#
# Nothing here prints a result. The raw request log and the event timestamps are written under
# docs/pod-kill-tests/<date>/, and docs/pod-kill-test.md is generated from them by
# scripts/analyze_pod_kill.py.
#
# Usage:
#   scripts/pod-kill-test.sh [--baseline N] [--observe N] [--rate N] [--limit N] [--label TEXT]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${RLX_BASE_URL:-http://127.0.0.1:18080}"
K8S_NAMESPACE="${RLX_K8S_NAMESPACE:-ratelimitx}"
K8S_CONTEXT="${RLX_K8S_CONTEXT:-kind-ratelimitx}"

BASELINE_SECONDS="${RLX_BASELINE_SECONDS:-20}"
OBSERVE_SECONDS="${RLX_OBSERVE_SECONDS:-60}"
RATE="${RLX_RATE:-20}"
# A limit high enough that the run is not almost entirely denials, so a dropped request shows up
# against a background of admissions rather than against a background of 429s.
USER_LIMIT="${RLX_USER_LIMIT:-5000}"
LABEL=""

TEST_USER="podkill1"
TEST_PASSWORD="pod-kill-password-123"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline) BASELINE_SECONDS="$2"; shift 2 ;;
    --observe) OBSERVE_SECONDS="$2"; shift 2 ;;
    --rate) RATE="$2"; shift 2 ;;
    --limit) USER_LIMIT="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

log() { printf '\n== %s\n' "$*"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
kube() { kubectl --context "$K8S_CONTEXT" -n "$K8S_NAMESPACE" "$@"; }

DATE_DIR="$(date -u +%Y-%m-%d)"
OUT_ROOT="docs/pod-kill-tests/$DATE_DIR"
mkdir -p "$OUT_ROOT"

for tool in kubectl curl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

log "Checking the deployment"
kube get deployment ratelimitx >/dev/null 2>&1 || {
  echo "No ratelimitx deployment in $K8S_CONTEXT/$K8S_NAMESPACE. Run scripts/kind-up.sh" >&2
  exit 1
}
kube rollout status deployment/ratelimitx --timeout=300s >/dev/null

REPLICAS="$(kube get deployment ratelimitx -o jsonpath='{.spec.replicas}')"
if [[ "$REPLICAS" -lt 2 ]]; then
  echo "this test needs at least 2 replicas; the deployment has $REPLICAS" >&2
  exit 1
fi

ready_endpoints() {
  kube get endpointslice -l kubernetes.io/service-name=ratelimitx \
    -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' 2>/dev/null \
    | grep -c true || true
}

wait_for_ready_endpoints() {
  local want="$1" deadline="$2" seen
  for _ in $(seq 1 "$deadline"); do
    seen="$(ready_endpoints)"
    [[ "${seen:-0}" -ge "$want" ]] && return 0
    sleep 1
  done
  return 1
}

log "Waiting for all $REPLICAS endpoints to be ready before starting"
wait_for_ready_endpoints "$REPLICAS" 120 || { echo "endpoints never became ready" >&2; exit 1; }

# ---------------------------------------------------------------------------
# One load user
#
# Registration and a single login stay inside the production auth throttle, so unlike the failure
# test this needs no admin account and does not touch the throttle at all. The limit is raised
# directly in the database, which is the same row the limiter reads.
# ---------------------------------------------------------------------------

log "Preparing the load user"
curl -s -o /dev/null -X POST "$BASE_URL/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USER\",\"email\":\"$TEST_USER@pod-kill.invalid\",\"password\":\"$TEST_PASSWORD\"}" \
  || true

kube exec -i statefulset/postgres -- sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q' <<SQL
UPDATE users SET rate_limit=$USER_LIMIT WHERE username='$TEST_USER';
SQL

TOKEN="$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASSWORD\"}" \
  | python3 -c 'import sys, json; print(json.load(sys.stdin).get("token", ""))')"
[[ -n "$TOKEN" ]] || { echo "could not obtain a token for $TEST_USER" >&2; exit 1; }

TOKENS_FILE="$OUT_ROOT/tokens.json"
python3 -I - "$TOKENS_FILE" "$TEST_USER" "$TOKEN" <<'PYEOF'
import json, sys
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump([{"username": sys.argv[2], "token": sys.argv[3]}], handle)
PYEOF

# ---------------------------------------------------------------------------
# Per-pod request counters
#
# Proof that the Service actually spread the load, taken from each pod's own metrics rather than
# inferred. Without this the run could have gone entirely to one replica and looked identical.
# ---------------------------------------------------------------------------

# Pods that are Ready and not already being deleted.
#
# `--field-selector status.phase=Running` is not enough on its own: a pod left over from an
# earlier rollout is still Running while it terminates, and counting it puts a stranger in the
# per-pod table and picks a doomed pod as the victim.
ready_app_pods() {
  kube get pods -l app.kubernetes.io/name=ratelimitx -o json | python3 -c '
import json, sys
for pod in json.load(sys.stdin)["items"]:
    if pod["metadata"].get("deletionTimestamp"):
        continue
    conditions = pod.get("status", {}).get("conditions", [])
    if any(c["type"] == "Ready" and c["status"] == "True" for c in conditions):
        print(pod["metadata"]["name"])
'
}

pod_request_counts() {
  local pod total
  for pod in $(ready_app_pods); do
    total="$(kube exec "$pod" -c app -- sh -c \
      'wget -qO- http://127.0.0.1:9091/actuator/prometheus 2>/dev/null' 2>/dev/null \
      | awk -F' ' '/^http_server_requests_seconds_count.*uri="\/api\/data"/ {s+=$2} END {printf "%d", s+0}')"
    printf '%s %s\n' "$pod" "${total:-0}"
  done
}

log "Recording per-pod request counts before the run"
pod_request_counts | tee "$OUT_ROOT/pod-counts-before.txt"

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------

k8s_server="$(kubectl --context "$K8S_CONTEXT" version -o json 2>/dev/null \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["serverVersion"]["gitVersion"])' \
  2>/dev/null || echo unknown)"
grace="$(kube get deployment ratelimitx -o jsonpath='{.spec.template.spec.terminationGracePeriodSeconds}')"
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
        "os": """$(uname -sr)""".strip(),
        "arch": "$(uname -m)",
    },
    "platform": {
        "name": "kubernetes",
        "kubernetesServer": "$k8s_server",
        "appReplicas": int("$REPLICAS"),
        "terminationGracePeriodSeconds": int("$grace"),
    },
    "repository": {"commit": "$git_commit", "uncommittedChanges": "$git_dirty" == "true"},
    "parameters": {
        "baseUrl": "$BASE_URL",
        "ratePerSecond": float("$RATE"),
        "userLimit": int("$USER_LIMIT"),
        "baselineSeconds": float("$BASELINE_SECONDS"),
        "observeSeconds": float("$OBSERVE_SECONDS"),
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

TOTAL_SECONDS=$(( BASELINE_SECONDS + OBSERVE_SECONDS ))

log "Starting load: $RATE req/s for ${TOTAL_SECONDS}s"
LOAD_STARTED_MS="$(now_ms)"
# No --admin-token: this run is not about the circuit breaker, so nothing polls it.
python3 -I scripts/failure_load.py \
  --base-url "$BASE_URL" \
  --tokens "$TOKENS_FILE" \
  --out-dir "$OUT_ROOT" \
  --duration-seconds "$TOTAL_SECONDS" \
  --rate "$RATE" \
  > "$OUT_ROOT/load-output.log" 2>&1 &
LOAD_PID=$!

sleep "$BASELINE_SECONDS"

VICTIM="$(ready_app_pods | head -1)"
[[ -n "$VICTIM" ]] || { echo "no ready pod to delete" >&2; exit 1; }
log "Deleting pod $VICTIM"
DELETE_SENT_MS="$(now_ms)"
kube delete pod "$VICTIM" --wait=false >/dev/null
DELETE_RETURNED_MS="$(now_ms)"

# How long the Service ran with fewer endpoints than it wants. With maxUnavailable 0 on the
# Deployment and a readiness probe on the replacement, this is the window the remaining replica
# carried everything.
if wait_for_ready_endpoints "$REPLICAS" "$OBSERVE_SECONDS"; then
  ENDPOINTS_RESTORED_MS="$(now_ms)"
else
  ENDPOINTS_RESTORED_MS=""
  echo "  WARNING: the Service did not get back to $REPLICAS ready endpoints in time" >&2
fi

GONE_MS=""
for _ in $(seq 1 "$OBSERVE_SECONDS"); do
  if ! kube get pod "$VICTIM" >/dev/null 2>&1; then
    GONE_MS="$(now_ms)"
    break
  fi
  sleep 1
done

wait "$LOAD_PID" || echo "  the load driver exited non-zero; see $OUT_ROOT/load-output.log" >&2
LOAD_FINISHED_MS="$(now_ms)"
cat "$OUT_ROOT/load-output.log"

log "Recording per-pod request counts after the run"
pod_request_counts | tee "$OUT_ROOT/pod-counts-after.txt"

python3 -I - "$OUT_ROOT/events.json" <<PYEOF
import json, sys
def maybe(value):
    return int(value) if value else None
events = {
    "loadStartedAtMs": int("$LOAD_STARTED_MS"),
    "podDeletedAtMs": int("$DELETE_SENT_MS"),
    "deleteCommandReturnedAtMs": int("$DELETE_RETURNED_MS"),
    "podGoneAtMs": maybe("$GONE_MS"),
    "endpointsRestoredAtMs": maybe("$ENDPOINTS_RESTORED_MS"),
    "loadFinishedAtMs": int("$LOAD_FINISHED_MS"),
    "deletedPod": "$VICTIM",
    "replicas": int("$REPLICAS"),
    "note": (
        "podDeletedAtMs is when the delete was issued, not when it completed. Every interval "
        "measured from it is therefore an upper bound."
    ),
}
with open(sys.argv[1], "w") as handle:
    json.dump(events, handle, indent=2)
    handle.write("\n")
print("  wrote " + sys.argv[1])
PYEOF

rm -f "$TOKENS_FILE"

cat > "$OUT_ROOT/README.md" <<'READMEEOF'
# Pod kill test run data

Raw output from `scripts/pod-kill-test.sh`. `docs/pod-kill-test.md` is generated from these files
by `scripts/analyze_pod_kill.py`; that file is generated and should not be edited by hand.

| File | What it is |
|------|------------|
| `environment.json` | Cluster, replica count, grace period and load parameters |
| `events.json` | When load started, when the delete was issued and returned, when the pod was gone, when the Service was back to full endpoints, when load stopped |
| `requests.csv` | One row per request: send and receive time, latency, status, `X-Algorithm` header, error |
| `load-summary.json` | What the load driver was asked to do and what it recorded |
| `load-output.log` | Console output of the load driver |
| `pod-counts-before.txt` | Per-pod `/api/data` request counts, read from each pod's own metrics before the run |
| `pod-counts-after.txt` | The same counts after the run, which is how the load is shown to have reached both replicas |
| `summary.json` | The measurements the analysis derived, in machine-readable form |

The load user's JWT is not kept: it is a credential for a throwaway account on a local cluster.
READMEEOF

log "Generating the report"
python3 -I scripts/analyze_pod_kill.py --results "$OUT_ROOT" --output docs/pod-kill-test.md

log "Done"
echo "  raw results: $OUT_ROOT"
echo "  report:      docs/pod-kill-test.md"
