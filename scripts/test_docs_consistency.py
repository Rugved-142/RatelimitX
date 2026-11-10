"""Checks that docs/slo.md still agrees with the runs and the rules it quotes.

Run with:  python3 -m pytest scripts/test_docs_consistency.py

docs/benchmarks.md and docs/failure-test.md are generated, so their numbers cannot drift from the
saved runs. docs/slo.md is written by hand and quotes figures from both, which is exactly the
place a number goes stale: a later run changes the generated reports and the hand-written summary
keeps the old value. These tests fail when that happens, so the choice is to update slo.md rather
than to leave a wrong number in it.

They also check that the alert table in slo.md lists every rule that exists and no rule that does
not, because an alert nobody documented and a documented alert that was deleted are both ways for
the operational story to stop being true.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SLO = REPO_ROOT / "docs" / "slo.md"
BENCHMARKS = REPO_ROOT / "docs" / "benchmarks.md"
FAILURE_TEST = REPO_ROOT / "docs" / "failure-test.md"
FAILURE_TEST_K8S = REPO_ROOT / "docs" / "failure-test-kubernetes.md"
RULES = REPO_ROOT / "prometheus" / "rules" / "ratelimitx.rules.yml"
FAILURE_RUNS = REPO_ROOT / "docs" / "failure-tests"


def flatten(text: str) -> str:
    """Collapse runs of whitespace, so a phrase split across a line break still matches.

    These checks look for a figure in its sentence, and a hand-written document wraps where the
    line runs out. Requiring the prose to keep a phrase on one line would be the test dictating
    the formatting; the numbers and their order are what matter.
    """
    return re.sub(r"\s+", " ", text)


def slo_text() -> str:
    if not SLO.is_file():
        pytest.skip("docs/slo.md is not present")
    return flatten(SLO.read_text(encoding="utf-8"))


def latest_failure_summary() -> dict:
    # Runs are filed per platform, docs/failure-tests/<platform>/<date>/. slo.md quotes the
    # compose run, which is the single-instance baseline every figure in it is about.
    runs = sorted(FAILURE_RUNS.glob("compose/*/summary.json")) if FAILURE_RUNS.is_dir() else []
    if not runs:
        pytest.skip("no committed failure test run to check against")
    return json.loads(runs[-1].read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# The figures slo.md quotes from the failure test
# ---------------------------------------------------------------------------


def test_the_failure_count_matches_the_run():
    summary = latest_failure_summary()
    totals = summary["totals"]
    expected = f"{totals['failures']:,} of {totals['requests']:,} requests"
    assert expected in slo_text(), f"docs/slo.md should quote '{expected}'"


def test_the_time_to_open_matches_the_run():
    transitions = latest_failure_summary()["transitions"]
    expected = f"{transitions['pauseToObservedOpenMs']:,} ms"
    assert expected in slo_text(), f"docs/slo.md should quote '{expected}'"


def test_the_breaker_cycle_time_matches_the_run():
    transitions = latest_failure_summary()["transitions"]
    expected = f"{transitions['lastObservedOpenBeforeUnpauseToClosedMs']:,} ms"
    assert expected in slo_text(), f"docs/slo.md should quote '{expected}'"


def test_the_fallback_count_matches_the_run():
    expected = f"{latest_failure_summary()['totals']['servedByFallback']:,}"
    assert expected in slo_text(), f"docs/slo.md should quote '{expected}'"


def test_the_admission_rates_match_the_run():
    """The claim that the fallback enforced a more generous limit rests on these two numbers."""
    phases = {phase["name"]: phase for phase in latest_failure_summary()["phases"]}
    before = phases["baseline"]["allowedPerSecond"]
    during = phases["outage"]["allowedPerSecond"]
    expected = f"from {before:,.1f} to {during:,.1f} per second"
    assert expected in slo_text(), f"docs/slo.md should quote '{expected}'"


# ---------------------------------------------------------------------------
# The figures slo.md quotes from the benchmark
# ---------------------------------------------------------------------------


def benchmark_row(label: str) -> str:
    if not BENCHMARKS.is_file():
        pytest.skip("docs/benchmarks.md is not present")
    for line in BENCHMARKS.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"| {label} |"):
            return line.split("|")[2].strip()
    pytest.fail(f"docs/benchmarks.md has no '{label}' row")


@pytest.mark.parametrize("label,unit", [
    ("Response time p50 (ms)", "ms"),
    ("Response time p95 (ms)", "ms"),
    ("Response time p99 (ms)", "ms"),
])
def test_the_quoted_latency_percentiles_match_the_benchmark(label, unit):
    # The generated table may carry a range, e.g. "5 (min 4, max 6)"; slo.md quotes the median.
    median = benchmark_row(label).split(" ")[0]
    expected = f"| {median} {unit} |"
    assert expected in slo_text(), f"docs/slo.md should quote '{expected}' for {label}"


def test_the_quoted_denial_share_matches_the_benchmark():
    share = benchmark_row("Denied share of decisions (%)").split(" ")[0]
    assert f"{share}%" in slo_text(), f"docs/slo.md should quote {share}% as the denied share"


# ---------------------------------------------------------------------------
# The alert table
# ---------------------------------------------------------------------------


def rule_names() -> set[str]:
    if not RULES.is_file():
        pytest.skip("the alert rules file is not present")
    return set(re.findall(r"^\s*- alert:\s*(\S+)", RULES.read_text(encoding="utf-8"),
                          flags=re.MULTILINE))


def test_every_alert_rule_is_documented():
    undocumented = sorted(name for name in rule_names() if f"`{name}`" not in slo_text())
    assert not undocumented, f"alert rules missing from docs/slo.md: {undocumented}"


def test_no_alert_is_documented_that_does_not_exist():
    documented = set(re.findall(r"`(RateLimitX[A-Za-z]+)`", slo_text()))
    missing = sorted(documented - rule_names())
    assert not missing, f"docs/slo.md documents alerts that no longer exist: {missing}"


# ---------------------------------------------------------------------------
# The things these documents must not say
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", [SLO, BENCHMARKS, FAILURE_TEST, FAILURE_TEST_K8S])
def test_no_document_claims_an_availability_figure(path):
    if not path.is_file():
        pytest.skip(f"{path.name} is not present")
    text = path.read_text(encoding="utf-8").lower()
    for forbidden in ["99.9%", "99.99%", "99.999%", "five nines", "three nines"]:
        assert forbidden not in text, f"{path.name} contains '{forbidden}'"


def test_the_slo_document_says_plainly_that_no_objective_is_set():
    assert "no service level objective is set" in slo_text().lower()


# ---------------------------------------------------------------------------
# The run data the reports say is committed
# ---------------------------------------------------------------------------


def tracked_files() -> set[str]:
    import subprocess
    try:
        output = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT, capture_output=True,
                                text=True, check=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        pytest.skip("git is not available")
    return set(output.splitlines())


def run_readmes() -> list[Path]:
    return sorted(
        path
        for parent in ("benchmarks", "failure-tests")
        for path in (REPO_ROOT / "docs" / parent).glob("**/README.md")
        if (REPO_ROOT / "docs" / parent).is_dir()
    )


@pytest.mark.parametrize("readme", run_readmes(),
                         ids=lambda path: path.parent.parent.name + "/" + path.parent.name)
def test_the_files_a_run_readme_lists_are_actually_committed(readme):
    """A .gitignore rule once excluded simulation.log while the README still listed it.

    A report that points at evidence which was never committed is worse than one that points at
    nothing, because the reader has no way to tell. This walks the file table in each run's
    README and checks that something matching each name is tracked by git.
    """
    tracked = tracked_files()
    run_dir = readme.parent.relative_to(REPO_ROOT).as_posix()
    listed = re.findall(r"^\| `([^`]+)` \|", readme.read_text(encoding="utf-8"),
                        flags=re.MULTILINE)
    assert listed, f"{readme} lists no files"

    # run-N stands for each per-run subdirectory that actually exists, so a file committed for
    # run-2 cannot cover a file missing from run-1.
    run_dirs = sorted(path.name for path in readme.parent.glob("run-*") if path.is_dir())

    missing = []
    for entry in listed:
        for expanded in ([entry.replace("run-N", name) for name in run_dirs]
                         if "run-N" in entry else [entry]):
            pattern = re.escape(run_dir + "/" + expanded).replace(r"\*", "[^/]*")
            if not any(re.fullmatch(pattern, path) for path in tracked):
                missing.append(expanded)
    assert not missing, f"{run_dir}/README.md lists files git does not track: {missing}"


# ---------------------------------------------------------------------------
# docs/kubernetes.md
#
# Same problem as slo.md: it is written by hand and quotes figures from two generated reports,
# so it is where a number goes stale after a re-run.
# ---------------------------------------------------------------------------

KUBERNETES = REPO_ROOT / "docs" / "kubernetes.md"
POD_KILL = REPO_ROOT / "docs" / "pod-kill-test.md"
POD_KILL_RUNS = REPO_ROOT / "docs" / "pod-kill-tests"


def kubernetes_text() -> str:
    if not KUBERNETES.is_file():
        pytest.skip("docs/kubernetes.md is not present")
    return flatten(KUBERNETES.read_text(encoding="utf-8"))


def latest_summary(root: Path, pattern: str) -> dict:
    runs = sorted(root.glob(pattern)) if root.is_dir() else []
    if not runs:
        pytest.skip(f"no committed run under {root}")
    return json.loads(runs[-1].read_text(encoding="utf-8"))


def k8s_failure_summary() -> dict:
    return latest_summary(FAILURE_RUNS, "kubernetes/*/summary.json")


def pod_kill_summary() -> dict:
    return latest_summary(POD_KILL_RUNS, "*/summary.json")


def test_the_kubernetes_failure_figures_match_that_run():
    summary = k8s_failure_summary()
    totals = summary["totals"]
    transitions = summary["transitions"]
    text = kubernetes_text()
    for expected in (
        f"{totals['failures']:,} of {totals['requests']:,} requests",
        f"{transitions['pauseToObservedOpenMs']:,} ms",
        f"{transitions['lastObservedOpenBeforeUnpauseToClosedMs']:,} ms",
    ):
        assert expected in text, f"docs/kubernetes.md should quote '{expected}'"


def test_the_pod_kill_figures_match_that_run():
    summary = pod_kill_summary()
    totals = summary["totals"]
    text = kubernetes_text()
    expected = f"{totals['failures']:,} of {totals['requests']:,} requests"
    assert expected in text, f"docs/kubernetes.md should quote '{expected}'"
    restored = summary["intervals"]["deleteToFullEndpointsMs"]
    assert f"{restored:,} ms" in text, f"docs/kubernetes.md should quote '{restored:,} ms'"


def test_the_quoted_per_pod_split_matches_that_run():
    """The claim that the Service spread the load rests on exactly these two counts."""
    served = sorted(
        (count for count in pod_kill_summary()["requestsServedByPod"].values()
         if count is not None),
        reverse=True,
    )
    if len(served) < 2:
        pytest.skip("fewer than two surviving pods were counted")
    expected = f"{served[0]:,} and {served[1]:,}"
    assert expected in kubernetes_text(), f"docs/kubernetes.md should quote '{expected}'"


def test_the_quoted_latency_cost_of_a_pod_kill_matches_that_run():
    phases = {phase["name"]: phase for phase in pod_kill_summary()["phases"]}
    text = kubernetes_text()  # already whitespace-normalised
    worst = phases["disruption"]["latencyMs"]["max"]
    assert f"{worst:,.0f} ms" in text, f"docs/kubernetes.md should quote '{worst:,.0f} ms'"
    baseline_p95 = phases["before"]["latencyMs"]["p95"]
    assert f"{baseline_p95:,.0f} ms p95" in text, \
        f"docs/kubernetes.md should quote '{baseline_p95:,.0f} ms p95'"


def test_the_kubernetes_documents_claim_no_availability_figure():
    for path in (KUBERNETES, POD_KILL):
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8").lower()
        for forbidden in ["99.9%", "99.99%", "five nines", "zero downtime", "highly available"]:
            assert forbidden not in text, f"{path.name} contains '{forbidden}'"


def test_the_kubernetes_document_says_what_it_does_not_establish():
    assert "not high availability" in kubernetes_text().lower()


# ---------------------------------------------------------------------------
# README claims about the test suite
#
# The README said "65 unit tests and 89 integration tests" for two phases after the integration
# count became 98, and its Testing table never gained the class added in Phase 7. Test counts are
# figures like any other and are checked here for the same reason.
# ---------------------------------------------------------------------------

README = REPO_ROOT / "README.md"
SRC_TEST_JAVA = REPO_ROOT / "src" / "test" / "java"


def readme_text() -> str:
    if not README.is_file():
        pytest.skip("README.md is not present")
    return README.read_text(encoding="utf-8")


def java_test_class_names() -> set[str]:
    if not SRC_TEST_JAVA.is_dir():
        return set()
    return {path.stem for path in SRC_TEST_JAVA.rglob("*.java")}


def surefire_total(report_dir: Path) -> int | None:
    """Total tests from a Maven report directory, or None when it has not been produced.

    Reports whose class no longer exists in src/test/java are ignored. target/ is not cleaned
    between builds, so a report left behind by a class that has since been deleted otherwise
    counts as tests that ran - which is how this check first disagreed with the build by one, on
    the strength of a probe class removed two phases earlier.
    """
    if not report_dir.is_dir():
        return None
    classes = java_test_class_names()
    total = 0
    found = False
    for path in report_dir.glob("*.txt"):
        simple_name = path.stem.rsplit(".", 1)[-1]
        if classes and simple_name not in classes:
            continue
        match = re.search(r"Tests run: (\d+)", path.read_text(encoding="utf-8", errors="replace"))
        if match:
            total += int(match.group(1))
            found = True
    return total if found else None


@pytest.mark.parametrize("kind,report,pattern", [
    ("unit", "surefire-reports", r"(\d+) unit tests"),
    ("integration", "failsafe-reports", r"(\d+) integration tests"),
])
def test_the_readme_test_counts_match_the_last_build(kind, report, pattern):
    """Skips when the build has not run here; in CI the build job produces the reports."""
    actual = surefire_total(REPO_ROOT / "target" / report)
    if actual is None:
        pytest.skip(f"target/{report} is not present; run ./mvnw -B verify first")
    match = re.search(pattern, readme_text())
    assert match, f"README.md does not state a number of {kind} tests"
    assert int(match.group(1)) == actual, \
        f"README.md says {match.group(1)} {kind} tests, the last build ran {actual}"


def test_every_test_class_appears_in_the_readme_testing_table():
    """A suite the table does not mention is a suite nobody reading the README knows exists."""
    if not SRC_TEST_JAVA.is_dir():
        pytest.skip("src/test/java is not present")
    # Only classes that actually hold tests. A shared base class such as
    # AbstractIntegrationTest is infrastructure, not a suite, and has nothing to say in a table
    # that tells a reader what is covered.
    classes = sorted(
        path.stem for path in SRC_TEST_JAVA.rglob("*.java")
        if path.stem.endswith(("Test", "IT"))
        and "@Test" in path.read_text(encoding="utf-8", errors="replace")
    )
    if not classes:
        pytest.skip("no test classes found")
    text = readme_text()
    missing = [name for name in classes if f"`{name}`" not in text]
    assert not missing, f"test classes missing from the README Testing table: {missing}"


def test_the_readme_points_at_the_smoke_test_script():
    """The definition of done is a fresh clone running this script, so it has to be findable."""
    assert (REPO_ROOT / "scripts" / "smoke-test.sh").is_file(), "scripts/smoke-test.sh is missing"
    assert "scripts/smoke-test.sh" in readme_text(), \
        "README.md does not mention scripts/smoke-test.sh"


# ---------------------------------------------------------------------------
# docs/claims.md and the README's links
#
# claims.md maps every factual claim in the README to the test, script or committed data that
# backs it. Two things can rot: a path it names can move, and the README can grow a claim nobody
# mapped. These check the first directly and the second as far as it can be checked mechanically.
# ---------------------------------------------------------------------------

CLAIMS = REPO_ROOT / "docs" / "claims.md"
ADR_DIR = REPO_ROOT / "docs" / "adr"


def claims_text() -> str:
    if not CLAIMS.is_file():
        pytest.skip("docs/claims.md is not present")
    return CLAIMS.read_text(encoding="utf-8")


def backticked_paths(text: str) -> list[str]:
    """Paths named in backticks.

    A backticked token is a path when it names a source file or sits under a directory of this
    repository. Commands (`./mvnw -B verify`) and HTTP endpoints (`POST /admin/algorithm`) also
    appear in backticks and are not paths, so anything containing a space is skipped, as is
    anything starting with a URL path this service serves.
    """
    roots = ("src/", "docs/", "scripts/", "k8s/", "prometheus/", "grafana/", ".github/")
    out = []
    for token in re.findall(r"`([^`\n]+)`", text):
        token = token.strip()
        if " " in token:
            continue
        if token.endswith((".java", ".py", ".sh", ".yml", ".yaml", ".properties", ".scala")):
            out.append(token)
        elif token.startswith(roots):
            out.append(token)
    return out


def test_every_file_claims_md_names_exists():
    """claims.md says this is checked, so it had better be."""
    missing = []
    for path in backticked_paths(claims_text()):
        if "*" in path:
            # A glob such as docs/failure-tests/*/summary.json: at least one match is enough.
            if not list(REPO_ROOT.glob(path)):
                missing.append(path)
            continue
        if (REPO_ROOT / path).exists():
            continue
        # A bare file name, such as a test class, resolves against the source tree.
        if list((REPO_ROOT / "src").rglob(Path(path).name)):
            continue
        missing.append(path)
    assert not missing, f"docs/claims.md names files that do not exist: {missing}"


def local_links(text: str) -> list[str]:
    links = []
    for target in re.findall(r"\]\(([^)]+)\)", text):
        if target.startswith(("http://", "https://", "#", "mailto:")):
            continue
        links.append(target.split("#", 1)[0])
    return [link for link in links if link]


def test_every_local_link_in_the_readme_resolves():
    missing = [link for link in local_links(readme_text())
               if not (REPO_ROOT / link).exists()]
    assert not missing, f"README.md links to files that do not exist: {missing}"


def test_every_local_link_in_claims_md_resolves():
    missing = [link for link in local_links(claims_text())
               if not (CLAIMS.parent / link).exists()]
    assert not missing, f"docs/claims.md links to files that do not exist: {missing}"


def test_every_adr_is_linked_from_the_readme_and_the_index():
    if not ADR_DIR.is_dir():
        pytest.skip("docs/adr is not present")
    adrs = sorted(p.name for p in ADR_DIR.glob("0*.md"))
    assert adrs, "docs/adr contains no decision records"
    readme = readme_text()
    index = (ADR_DIR / "README.md").read_text(encoding="utf-8")
    unlinked = [name for name in adrs if name not in readme]
    assert not unlinked, f"ADRs not linked from README.md: {unlinked}"
    unindexed = [name for name in adrs if name not in index]
    assert not unindexed, f"ADRs missing from docs/adr/README.md: {unindexed}"


# A measured figure is one that came out of a run: a latency, a throughput, a percentile. The
# README deliberately quotes none of them and links to the generated reports instead, so that
# re-running a benchmark cannot leave a stale number in it. Configuration values - tier limits,
# ports, thread counts in test descriptions - are not measurements and are expected.
MEASURED_FIGURE_PATTERNS = [
    r"\d+(?:\.\d+)? ?ms\b",
    r"\d+(?:\.\d+)? ?(?:req|requests|decisions)/s\b",
    r"\d+(?:\.\d+)? ?(?:rps|qps)\b",
    r"p(?:50|75|95|99)\D{0,12}\d",
    r"\d+(?:\.\d+)? ?% ?(?:availability|uptime|of the time)",
    r"99\.9+ ?%",
]


@pytest.mark.parametrize("pattern", MEASURED_FIGURE_PATTERNS)
def test_the_readme_quotes_no_measured_figure(pattern):
    hits = re.findall(pattern, readme_text(), flags=re.IGNORECASE)
    assert not hits, (
        f"README.md quotes what looks like a measured figure {hits}; link to the generated "
        "report instead, so a re-run cannot leave it stale"
    )


def test_the_readme_says_where_its_claims_are_mapped():
    assert "docs/claims.md" in readme_text(), \
        "README.md does not point at docs/claims.md"
