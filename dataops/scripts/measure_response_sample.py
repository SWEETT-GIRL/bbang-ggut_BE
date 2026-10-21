"""One-shot collection measurement using the existing worker; never approves proposals.

Writes a fresh run's timing, raw spool and diagnostic input. A running, write-disabled
DataOps server with an operational ServingReader is required. No LLM calls.
"""
import argparse
import json
import os
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import sync_worker as worker
from capture_spool import CaptureSpool


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--server", default="http://127.0.0.1:8090")
    parser.add_argument("--max", type=int, default=100, dest="maximum")
    args = parser.parse_args()
    if not 1 <= args.maximum <= 100:
        parser.error("initial measurement allows 1..100 stores")
    os.umask(0o077)
    args.run_dir.mkdir(parents=True, exist_ok=True)
    worker.safe_server(args.server)
    spool = CaptureSpool(args.run_dir / "captures")
    if list(spool.root.glob("*.json")):
        parser.error("use a fresh measurement directory; reconcile earlier pending captures separately")
    original_fetch = worker.fetch_place
    original_submit = worker.submit_snapshot
    started = time.monotonic()
    summary = {"started_at": datetime.now(timezone.utc).isoformat(), "maximum": args.maximum,
               "max_minutes": 30, "mode": "one-shot; proposal-only; zero LLM calls"}
    # Exclusive creation prevents accidental re-use from hiding an earlier measurement.
    with (args.run_dir / "timings.jsonl").open("x") as timings:
        def timed_fetch(external_id):
            begin = time.monotonic()
            event = {"phase": "fetch", "external_id": external_id}
            try:
                result = original_fetch(external_id)
                event.update(kind=result.kind, http_status=result.http_status)
                return result
            except Exception as error:
                event["error_type"] = type(error).__name__
                raise
            finally:
                event["seconds"] = time.monotonic() - begin
                timings.write(json.dumps(event) + "\n")
                timings.flush()

        def timed_submit(*positional, **keywords):
            begin = time.monotonic()
            event = {"phase": "submit", "observation_id": keywords["observation_id"]}
            try:
                result = original_submit(*positional, **keywords)
                event.update(assessment=result.get("assessment"), changed=result.get("changed"),
                             snapshot_id=result.get("snapshotId"), reasons=result.get("reasons"))
                return result
            except Exception as error:
                event["error_type"] = type(error).__name__
                raise
            finally:
                event["seconds"] = time.monotonic() - begin
                timings.write(json.dumps(event) + "\n")
                timings.flush()

        worker.fetch_place, worker.submit_snapshot = timed_fetch, timed_submit
        try:
            with spool.exclusive():
                code = worker.run(args.server, 10, args.maximum, 45, 0, 0.5, spool)
            summary["worker_exit_code"] = code
        except Exception as error:
            code = 1
            summary.update(worker_exit_code=code, error_type=type(error).__name__)
        finally:
            worker.fetch_place, worker.submit_snapshot = original_fetch, original_submit
            summary["elapsed_seconds"] = time.monotonic() - started
            summary["finished_at"] = datetime.now(timezone.utc).isoformat()
            records = [spool.read(p) for p in sorted(spool.root.glob("*.json"))]
            summary["capture_states"] = dict(Counter(r["state"] for r in records))
            with (args.run_dir / "diagnostic-input.jsonl").open("x") as output:
                for record in records:
                    if record["request"].get("payload") is not None:
                        output.write(json.dumps(record["request"], ensure_ascii=False) + "\n")
            (args.run_dir / "collection-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
