# Failure test run data

Raw output from `scripts/failure-test.sh --platform compose`. `docs/failure-test.md` is generated from
these files by `scripts/analyze_failure_test.py`; that file is generated and should not be
edited by hand.

> **Provenance note.** The harness's account provisioning changed after this run, in commit
> `c047b25`, which replaced the literal test passwords with per-run random values and made the
> scripts clear their own accounts before registering; nothing that this run measured changed,
> so the data here was not regenerated.

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
