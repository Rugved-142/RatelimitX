"""Checks the Kubernetes manifests for the properties this deployment is claimed to have.

Run with:  python3 -m pytest scripts/test_k8s_manifests.py

These are static checks on the YAML, so they run without a cluster and catch a regression on
every push rather than only when someone brings kind up. They are deliberately about claims the
documentation makes - two replicas, probes that do not take pods out of service during a Redis
outage, resource limits, non-root, no committed Secret - because each of those is a sentence in
docs/kubernetes.md that would otherwise quietly stop being true.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

yaml = pytest.importorskip("yaml", reason="pyyaml is needed to parse the manifests")

REPO_ROOT = Path(__file__).resolve().parent.parent
K8S = REPO_ROOT / "k8s"
APP_PROPERTIES = REPO_ROOT / "src" / "main" / "resources" / "application.properties"
COMPOSE = REPO_ROOT / "docker-compose.yml"


def load_all() -> list[dict]:
    if not K8S.is_dir():
        pytest.skip("k8s/ is not present")
    documents: list[dict] = []
    for path in sorted(K8S.glob("*.yaml")):
        for document in yaml.safe_load_all(path.read_text(encoding="utf-8")):
            if document:
                document["__file__"] = path.name
                documents.append(document)
    return documents


def by_kind(kind: str) -> list[dict]:
    return [document for document in load_all() if document.get("kind") == kind]


def one(kind: str, name: str) -> dict:
    matches = [d for d in by_kind(kind) if d.get("metadata", {}).get("name") == name]
    assert len(matches) == 1, f"expected exactly one {kind}/{name}, found {len(matches)}"
    return matches[0]


def app_container(deployment: dict) -> dict:
    containers = deployment["spec"]["template"]["spec"]["containers"]
    return next(c for c in containers if c["name"] == "app")


def every_container(document: dict):
    spec = document["spec"]["template"]["spec"]
    yield from spec.get("initContainers", [])
    yield from spec["containers"]


# ---------------------------------------------------------------------------
# Shape
# ---------------------------------------------------------------------------


def test_the_application_runs_two_replicas():
    """Two is what makes the pod-kill test and the per-instance fallback meaningful."""
    assert one("Deployment", "ratelimitx")["spec"]["replicas"] == 2


def test_redis_and_postgres_are_single_replica_statefulsets():
    names = {s["metadata"]["name"]: s for s in by_kind("StatefulSet")}
    assert set(names) == {"postgres", "redis"}
    for name, statefulset in names.items():
        assert statefulset["spec"]["replicas"] == 1, f"{name} should have one replica"
        assert statefulset["spec"]["volumeClaimTemplates"], f"{name} needs a volume"


def test_a_rollout_never_drops_below_the_replica_count():
    strategy = one("Deployment", "ratelimitx")["spec"]["strategy"]
    assert strategy["type"] == "RollingUpdate"
    assert strategy["rollingUpdate"]["maxUnavailable"] == 0


def test_a_disruption_budget_keeps_one_replica():
    assert one("PodDisruptionBudget", "ratelimitx")["spec"]["minAvailable"] == 1


# ---------------------------------------------------------------------------
# Probes
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("probe", ["startupProbe", "readinessProbe", "livenessProbe"])
def test_the_application_has_each_probe_on_the_management_port(probe):
    container = app_container(one("Deployment", "ratelimitx"))
    assert probe in container, f"the app container has no {probe}"
    assert container[probe]["httpGet"]["port"] == "management"


def test_the_probes_use_the_health_groups_not_the_full_health_endpoint():
    """/actuator/health includes the Redis indicator.

    Using it would take every pod out of the Service the moment Redis went down, turning a
    degradation the circuit breaker absorbs into a total outage. The liveness and readiness
    groups contain only the application's own state.
    """
    container = app_container(one("Deployment", "ratelimitx"))
    for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
        path = container[probe]["httpGet"]["path"]
        assert path in ("/actuator/health/liveness", "/actuator/health/readiness"), \
            f"{probe} uses {path}, which is not one of the health groups"


def test_redis_is_not_restarted_before_the_breaker_can_recover():
    """A frozen Redis is absorbed by the circuit breaker, so killing it quickly buys nothing.

    Measured on kind: a 15s period with a threshold of 4 killed a frozen Redis about 45 seconds
    in, which is inside the breaker's own 30s open timeout plus a probe cycle. The budget has to
    be comfortably longer than that for the recovery in docs/failure-test-kubernetes.md to be the
    application's own doing.
    """
    redis = one("StatefulSet", "redis")
    probe = next(c for c in redis["spec"]["template"]["spec"]["containers"]
                 if c["name"] == "redis")["livenessProbe"]
    budget = probe["periodSeconds"] * probe["failureThreshold"]
    assert budget >= 90, f"the liveness budget is only {budget}s"


def test_the_redis_service_keeps_resolving_while_unready():
    """Otherwise a frozen Redis becomes a name resolution error instead of a timeout."""
    service = one("Service", "redis")
    assert service["spec"].get("publishNotReadyAddresses") is True


# ---------------------------------------------------------------------------
# Resources and security
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("kind,name", [
    ("Deployment", "ratelimitx"), ("StatefulSet", "postgres"), ("StatefulSet", "redis"),
])
def test_every_container_declares_requests_and_limits(kind, name):
    for container in every_container(one(kind, name)):
        resources = container.get("resources", {})
        assert resources.get("requests"), f"{name}/{container['name']} has no requests"
        assert resources.get("limits"), f"{name}/{container['name']} has no limits"
        for field in ("cpu", "memory"):
            assert field in resources["requests"], f"{name}/{container['name']} requests no {field}"
            assert field in resources["limits"], f"{name}/{container['name']} limits no {field}"


@pytest.mark.parametrize("kind,name", [
    ("Deployment", "ratelimitx"), ("StatefulSet", "postgres"), ("StatefulSet", "redis"),
])
def test_nothing_runs_as_root(kind, name):
    pod_security = one(kind, name)["spec"]["template"]["spec"].get("securityContext", {})
    assert pod_security.get("runAsNonRoot") is True, f"{name} does not require a non-root user"
    assert pod_security.get("runAsUser"), f"{name} does not pin a uid"


@pytest.mark.parametrize("kind,name", [
    ("Deployment", "ratelimitx"), ("StatefulSet", "postgres"), ("StatefulSet", "redis"),
])
def test_no_container_may_escalate_privileges(kind, name):
    for container in every_container(one(kind, name)):
        security = container.get("securityContext", {})
        assert security.get("allowPrivilegeEscalation") is False, \
            f"{name}/{container['name']} may escalate privileges"
        assert security.get("capabilities", {}).get("drop") == ["ALL"], \
            f"{name}/{container['name']} does not drop all capabilities"


def test_the_application_root_filesystem_is_read_only():
    container = app_container(one("Deployment", "ratelimitx"))
    assert container["securityContext"]["readOnlyRootFilesystem"] is True
    # It can only be read-only because the JVM gets a writable /tmp from somewhere else.
    assert any(mount["mountPath"] == "/tmp" for mount in container["volumeMounts"])


# ---------------------------------------------------------------------------
# Secrets
# ---------------------------------------------------------------------------


def test_no_secret_manifest_is_committed():
    """The Secret is generated into the cluster by scripts/kind-up.sh and never written down.

    A committed Secret with placeholder values is a default secret waiting to reach production,
    which is the thing this repository removed in an earlier phase.
    """
    assert not by_kind("Secret"), "k8s/ contains a Secret manifest"
    try:
        tracked = subprocess.run(["git", "ls-files", "k8s"], cwd=REPO_ROOT, capture_output=True,
                                 text=True, check=True, timeout=60).stdout.split()
    except (OSError, subprocess.SubprocessError):
        pytest.skip("git is not available")
    offenders = [path for path in tracked if "secret" in path.lower()]
    assert not offenders, f"secret-looking files are tracked: {offenders}"


def test_the_secret_keys_the_manifests_reference_are_the_ones_the_script_creates():
    """A typo here leaves pods stuck with a missing environment variable and no clear reason."""
    referenced = set(re.findall(r"secretKeyRef:\s*\n\s*name:\s*ratelimitx-secrets\s*\n\s*key:\s*(\S+)",
                                "\n".join(path.read_text(encoding="utf-8")
                                          for path in sorted(K8S.glob("*.yaml")))))
    created = set(re.findall(r"--from-literal=(\w+)=",
                             (REPO_ROOT / "scripts" / "kind-up.sh").read_text(encoding="utf-8")))
    assert referenced, "no Secret keys are referenced by the manifests"
    assert referenced <= created, f"referenced but never created: {sorted(referenced - created)}"


# ---------------------------------------------------------------------------
# Cross-file consistency
# ---------------------------------------------------------------------------


def test_graceful_shutdown_fits_inside_the_termination_grace_period():
    """If it does not, the kubelet SIGKILLs part way through and the graceful phase is pointless."""
    properties = APP_PROPERTIES.read_text(encoding="utf-8")
    match = re.search(r"^spring\.lifecycle\.timeout-per-shutdown-phase=(\d+)s", properties,
                      flags=re.MULTILINE)
    assert match, "application.properties does not set a shutdown phase timeout"
    shutdown = int(match.group(1))
    assert re.search(r"^server\.shutdown=graceful", properties, flags=re.MULTILINE), \
        "graceful shutdown is not enabled"

    pod_spec = one("Deployment", "ratelimitx")["spec"]["template"]["spec"]
    grace = pod_spec["terminationGracePeriodSeconds"]
    prestop = app_container(one("Deployment", "ratelimitx"))["lifecycle"]["preStop"]["exec"]["command"]
    pause = int(prestop[-1])
    assert shutdown + pause < grace, \
        f"shutdown {shutdown}s + preStop {pause}s does not fit in {grace}s"


def test_the_auth_throttle_default_is_the_same_everywhere():
    """Three files carry this number. A test raises it and puts it back; they must agree."""
    properties = APP_PROPERTIES.read_text(encoding="utf-8")
    expected = re.search(r"^ratelimit\.auth\.login-limit-per-minute=(\d+)", properties,
                         flags=re.MULTILINE)
    assert expected, "application.properties does not set the login throttle"
    value = expected.group(1)

    config = one("ConfigMap", "ratelimitx-config")["data"]
    assert config["RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE"] == value
    assert config["RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE"] == value

    if COMPOSE.is_file():
        compose = COMPOSE.read_text(encoding="utf-8")
        assert f"RATELIMIT_AUTH_LOGIN_LIMIT_PER_MINUTE:-{value}" in compose
        assert f"RATELIMIT_AUTH_REGISTER_LIMIT_PER_MINUTE:-{value}" in compose
