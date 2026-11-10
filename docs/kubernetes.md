# Running on Kubernetes

The manifests in [`k8s/`](../k8s) deploy RateLimitX to a Kubernetes cluster: two application
replicas behind a Service, with single-replica Redis and PostgreSQL StatefulSets. They are
exercised on a local [kind](https://kind.sigs.k8s.io) cluster by `scripts/kind-up.sh`, and the CI
pipeline deploys them and kills a pod under load on every push.

What has actually been measured on the cluster is in
[failure-test-kubernetes.md](failure-test-kubernetes.md) and
[pod-kill-test.md](pod-kill-test.md); both are generated from committed run data, produced on a
local kind cluster.

## Getting it up

```bash
scripts/kind-up.sh
```

That creates the cluster, builds the image, side-loads it, generates the Secret, applies the
manifests, waits for both replicas to be ready endpoints, and fails unless an unauthenticated
`GET /api/data` comes back 401. The API is then on `http://127.0.0.1:18080`.

```bash
kind delete cluster --name ratelimitx
```

Re-running `scripts/kind-up.sh` on an existing cluster rebuilds the image and rolls the
deployment onto it, keeping the Secret that is already there.

## What gets deployed

| Object | File | Notes |
|--------|------|-------|
| Namespace `ratelimitx` | `00-namespace.yaml` | |
| ConfigMap `ratelimitx-config` | `10-configmap.yaml` | Non-secret configuration only |
| StatefulSet + Service `postgres` | `20-postgres.yaml` | One replica, 1Gi volume |
| StatefulSet + Service `redis` | `21-redis.yaml` | One replica, 1Gi volume |
| Deployment `ratelimitx` | `30-app.yaml` | **Two replicas** |
| Service `ratelimitx` | `30-app.yaml` | ClusterIP, ports 8080 and 9091 |
| PodDisruptionBudget `ratelimitx` | `30-app.yaml` | `minAvailable: 1` |
| Service `ratelimitx-nodeport` | `31-app-nodeport.yaml` | Local access only, port 8080 |
| Secret `ratelimitx-secrets` | **none** | Generated into the cluster, never committed |

## Decisions worth explaining

**The probes use the health *groups*, not `/actuator/health`.** The full health endpoint includes
the Redis indicator. A readiness probe on it would take every pod out of the Service the moment
Redis went down - turning a degradation the circuit breaker is built to absorb into a total
outage, which is the opposite of what the breaker is for. The liveness and readiness groups carry
only the application's own state, so a pod serving correct answers from the local fallback stays
ready. That is deliberate, and it is checked by a test.

**Redis is given about two minutes before its liveness probe restarts it.** This was measured,
not guessed: with the usual 15-second period and a threshold of 4, the kubelet killed a frozen
Redis about 45 seconds in - inside the circuit breaker's own 30-second open timeout, so the
restart, not the application, was what ended the outage. A hung Redis is already absorbed, so
restarting it quickly buys nothing and destroys the state anyone would need to find out why it
hung. 10 seconds times 12 leaves room for the breaker to open, time out, probe and close on its
own, and still recovers a genuinely wedged server without a human.

**The Redis Service publishes not-ready addresses.** With one replica there is nothing to
load-balance, so removing the only address turns an unresponsive Redis into NXDOMAIN - a name
resolution failure rather than the connection timeout the breaker is written around. Keeping the
address makes the deployment behave the way the failure test measured.

**A rollout never drops below two replicas.** `maxUnavailable: 0` with `maxSurge: 1` brings the
new pod up and waits for its readiness probe before taking an old one away. A five-second preStop
pause covers the race between removal from the Service's endpoints and SIGTERM arriving, and
`server.shutdown=graceful` with a 20-second phase timeout lets in-flight requests finish. The 45
second `terminationGracePeriodSeconds` is sized to contain both; a test fails if those three
numbers stop adding up.

**The NodePort Service is for local tests only.** `kubectl port-forward` forwards to a single
pod, which would quietly turn a two-replica test into a one-replica test. Driving a NodePort
through kube-proxy actually spreads the load, which the per-pod counters in the pod kill report
confirm. A real deployment would put an Ingress or a LoadBalancer with TLS in front of the
ClusterIP Service instead; actuator is on port 9091 and is not published outside the cluster by
either Service.

**There is no committed Secret, not even a template.** `scripts/kind-up.sh` generates the JWT
signing key and the database password straight into the cluster with `kubectl create secret
--from-literal`, so neither value is ever written to disk. A committed Secret with placeholder
values is a default secret waiting to reach production, which earlier work here removed. A test
fails if any secret-looking file under `k8s/` is ever tracked, and if the keys the manifests
reference are not the ones the script creates.

**Everything runs as a non-root user with no capabilities**, privilege escalation disabled and a
seccomp profile. The application container additionally has a read-only root filesystem, which it
can afford because the JVM gets a writable `/tmp` from an `emptyDir`.

**Two init containers wait for Postgres and Redis**, mirroring `depends_on: service_healthy` in
`docker-compose.yml`. Without them the application starts, fails to reach Postgres and exits,
spending its first minute in CrashLoopBackOff before succeeding - which works, but makes every
real startup problem look exactly like a dependency that was merely slow. Both use images the
cluster already needs, so neither adds a pull.

## What was measured on it

| Measure | Value | Source |
|---------|-------|--------|
| Failures during a 40 s Redis freeze, 2 replicas | 0 of 2,800 requests | [failure test](failure-test-kubernetes.md) |
| Redis frozen to breaker OPEN | 687 ms | [failure test](failure-test-kubernetes.md) |
| Breaker's own OPEN-to-CLOSED cycle | 30,118 ms | [failure test](failure-test-kubernetes.md) |
| Failures while a replica was deleted under load | 0 of 1,600 requests | [pod kill test](pod-kill-test.md) |
| Pod delete to a full set of ready endpoints | 15,980 ms | [pod kill test](pod-kill-test.md) |
| Requests served by each surviving replica | 963 and 441 | [pod kill test](pod-kill-test.md) |

Deleting a replica under load cost no requests at all - no 5xx, no dropped connections - but it
was not free: the slowest response during the disruption was 1,885 ms, against an 11 ms p95 before
it. A JVM that has just started is slow for its first requests, and the readiness probe only
establishes that it is answering, not that it is warm.

## What this does not establish

- **Not high availability.** Redis and PostgreSQL are one replica each with no replication.
  Redis going away is survivable - that is what the failure test measures - but during it the
  limits are enforced per replica rather than globally, which with two replicas means up to twice
  the configured rate. PostgreSQL going away stops registration and login outright.
- **Not a node failure.** The kind cluster has one node. A pod was deleted; nothing here says
  what happens when the machine under it goes away.
- **Not a capacity statement.** One replica carried the whole offered rate during the disruption,
  but that rate was low by design.
- **Not production manifests.** There is no Ingress, no TLS, no NetworkPolicy (kind's default CNI
  would not enforce one), no HorizontalPodAutoscaler and no in-cluster Prometheus. The resource
  requests and limits were chosen to fit a laptop, not derived from production load.

## In CI

The `kubernetes` job in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) installs kind
from a checksum-verified release binary, runs `scripts/kind-up.sh`, runs the pod kill test under
load, and fails if a single request came back 5xx or got no response - or if fewer than a hundred
requests were recorded, so a run that quietly measured nothing cannot pass either. The job is
green: it creates the cluster, deploys the manifests and kills a pod under load on a GitHub-hosted
runner in a little under five minutes, with the run data uploaded as an artifact.

What the job establishes is that the manifests deploy and the Service keeps answering while a
replica is deleted. It does not re-measure the figures above; those come from the local runs,
whose raw data is committed. The manifests are also
checked statically by `scripts/test_k8s_manifests.py` in the `scripts` job, which runs without a
cluster, so a change that breaks one of the decisions above fails fast rather than waiting for
the cluster to come up.

The Redis failure test is run by hand rather than in CI: it takes well over two minutes of
wall-clock time per run and the pod kill test already proves the deployment serves traffic.
