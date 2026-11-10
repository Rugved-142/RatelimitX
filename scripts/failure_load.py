#!/usr/bin/env python3
"""Steady open-model load against /api/data, plus a poller of the circuit breaker's state.

This is the measuring instrument for scripts/failure-test.sh. It records what happened and
computes nothing: every figure in docs/failure-test.md is derived from these files by
scripts/analyze_failure_test.py.

Two properties are deliberate:

* **Open model.** Departures are scheduled from a fixed clock, not from when the previous
  response arrived, so a slow or failing service shows up as a queue and as latency rather than
  as reduced load. A closed loop would quietly stop sending while Redis was down, which is the
  one thing this test must not do.
* **Nothing is retried, and no response is treated as a failure here.** A 429 is a correct
  answer from a rate limiter, a 5xx is not, and deciding which is which is the analyser's job.
  This script records the status code, the latency and the X-Algorithm header and moves on.

Written with the standard library only: it has to run from a bare checkout with no extra install.
"""

from __future__ import annotations

import argparse
import csv
import json
import socket
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# A request that has not come back within this many seconds is recorded as a client timeout. It
# is far longer than the service's own 200ms Redis timeout, so anything hitting it is a stall in
# the service rather than a slow Redis call.
REQUEST_TIMEOUT_SECONDS = 5.0

# Status recorded when no HTTP response arrived at all.
NO_RESPONSE = 0


def now_ms() -> int:
    return int(time.time() * 1000)


class Recorder:
    """Collects request records from the worker threads."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.rows: list[dict] = []

    def add(self, row: dict) -> None:
        with self._lock:
            self.rows.append(row)


def send_one(base_url: str, token: str, user: str, seq: int, scheduled_ms: int,
             recorder: Recorder) -> None:
    request = urllib.request.Request(
        base_url + "/api/data",
        headers={"Authorization": "Bearer " + token},
        method="GET",
    )
    sent = now_ms()
    status = NO_RESPONSE
    algorithm = ""
    error = ""
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            status = response.status
            algorithm = response.headers.get("X-Algorithm", "")
            response.read()
    except urllib.error.HTTPError as exc:
        # 429 arrives here. So does 5xx. Record the code; classification is the analyser's job.
        status = exc.code
        algorithm = exc.headers.get("X-Algorithm", "") if exc.headers else ""
        try:
            exc.read()
        except Exception:  # noqa: BLE001 - the body is not needed and may be gone
            pass
    except (socket.timeout, TimeoutError):
        error = "timeout"
    except urllib.error.URLError as exc:
        error = "urlerror: " + str(exc.reason)
    except Exception as exc:  # noqa: BLE001 - anything else is still a datum, not a crash
        error = type(exc).__name__ + ": " + str(exc)
    received = now_ms()

    recorder.add({
        "seq": seq,
        "user": user,
        "scheduled_ms": scheduled_ms,
        "sent_ms": sent,
        "received_ms": received,
        "latency_ms": received - sent,
        "status": status,
        "algorithm": algorithm,
        "error": error,
    })


def poll_circuit(base_url: str, admin_token: str, interval_ms: int, stop: threading.Event,
                 out_path: Path) -> None:
    """Samples /admin/circuit until stopped, one JSON object per line.

    The endpoint reports in-memory state and touches neither Redis nor the fallback, so it keeps
    answering while Redis is paused. That is the whole reason it can be used to time the
    transitions.
    """
    with out_path.open("w", encoding="utf-8") as handle:
        while not stop.is_set():
            sample: dict = {"t_ms": now_ms()}
            request = urllib.request.Request(
                base_url + "/admin/circuit",
                headers={"Authorization": "Bearer " + admin_token},
                method="GET",
            )
            try:
                with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                    sample["http_status"] = response.status
                    body = json.loads(response.read().decode("utf-8"))
                    sample["state"] = body.get("state")
                    sample["failureCount"] = body.get("failureCount")
                    sample["currentMode"] = body.get("currentMode")
                    sample["localFallbackActiveUsers"] = body.get("localFallbackActiveUsers")
                    sample["probeInFlight"] = body.get("probeInFlight")
            except Exception as exc:  # noqa: BLE001 - a failed poll is itself data
                sample["http_status"] = NO_RESPONSE
                sample["error"] = type(exc).__name__ + ": " + str(exc)
            handle.write(json.dumps(sample) + "\n")
            handle.flush()
            stop.wait(interval_ms / 1000.0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--circuit-base-url", default=None,
                        help="where to poll /admin/circuit, if not --base-url. With more than "
                             "one replica the breaker is per instance, so polling through a "
                             "load-balanced Service would interleave two state machines; this "
                             "points the poller at one pod.")
    parser.add_argument("--tokens", required=True,
                        help="JSON file: a list of {username, token} for the load users")
    parser.add_argument("--admin-token", default=None,
                        help="enables the /admin/circuit poller. Omitted when the run is not "
                             "about the circuit breaker, in which case no polling happens and no "
                             "circuit.jsonl is written.")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--duration-seconds", type=float, required=True)
    parser.add_argument("--rate", type=float, required=True,
                        help="requests per second, imposed regardless of response time")
    parser.add_argument("--poll-interval-ms", type=int, default=100)
    parser.add_argument("--max-in-flight", type=int, default=256)
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    users = json.loads(Path(args.tokens).read_text(encoding="utf-8"))
    if not users:
        raise SystemExit("no load users were supplied; see the setup output")

    recorder = Recorder()
    stop = threading.Event()
    poller = None
    if args.admin_token:
        poller = threading.Thread(
            target=poll_circuit,
            args=(args.circuit_base_url or args.base_url, args.admin_token,
                  args.poll_interval_ms, stop, out_dir / "circuit.jsonl"),
            daemon=True,
        )
        poller.start()

    interval = 1.0 / args.rate
    started_ms = now_ms()
    start = time.monotonic()
    deadline = start + args.duration_seconds
    submitted = 0
    not_submitted = 0

    with ThreadPoolExecutor(max_workers=args.max_in_flight) as pool:
        seq = 0
        while True:
            target = start + seq * interval
            if target >= deadline:
                break
            sleep_for = target - time.monotonic()
            if sleep_for > 0:
                time.sleep(sleep_for)
            user = users[seq % len(users)]
            try:
                pool.submit(send_one, args.base_url, user["token"], user["username"], seq,
                            now_ms(), recorder)
                submitted += 1
            except RuntimeError:
                # The pool is shutting down. Counted rather than ignored.
                not_submitted += 1
            seq += 1

    stop.set()
    if poller is not None:
        poller.join(timeout=5)
    finished_ms = now_ms()

    rows = sorted(recorder.rows, key=lambda row: row["seq"])
    fields = ["seq", "user", "scheduled_ms", "sent_ms", "received_ms", "latency_ms", "status",
              "algorithm", "error"]
    with (out_dir / "requests.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    summary = {
        "startedAtMs": started_ms,
        "finishedAtMs": finished_ms,
        "requestedRatePerSecond": args.rate,
        "durationSeconds": args.duration_seconds,
        "pollIntervalMs": args.poll_interval_ms,
        "circuitBaseUrl": (args.circuit_base_url or args.base_url) if args.admin_token else None,
        "users": [user["username"] for user in users],
        "submitted": submitted,
        "notSubmitted": not_submitted,
        "recorded": len(rows),
    }
    (out_dir / "load-summary.json").write_text(json.dumps(summary, indent=2) + "\n",
                                               encoding="utf-8")
    print("  wrote " + str(out_dir / "requests.csv") + " (" + str(len(rows)) + " records)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
