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
