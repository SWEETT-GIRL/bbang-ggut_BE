#!/usr/bin/env python3
"""Bounded synthetic spool benchmark in temporary directories only.

Compare the former per-save glob/stat quota calculation with the locked usage
cache. Both timed paths retain atomic replace and file/directory fsync. No real
capture directory, service, database, network, or retention policy is touched.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
import time
from uuid import UUID

from capture_spool import CaptureSpool, encoded


class LegacySpool(CaptureSpool):
    """The previous save algorithm, kept only as the measured baseline."""
    scans = 0

    @contextmanager
    def exclusive(self):
        import fcntl
        with (self.root / ".worker.lock").open("a") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)

    def save(self, record):
        path = self.path(record["capture_id"])
        payload = encoded(record)
        self.scans += 1
        size = sum(item.stat().st_size for item in self.root.glob("*.json"))
        previous_size = path.stat().st_size if path.exists() else 0
        if size - previous_size + len(payload) > self.max_bytes:
            raise OSError("capture spool quota reached")
        fd, temporary = tempfile.mkstemp(prefix=".capture-", dir=self.root)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
            directory = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


class MeasuredSpool(CaptureSpool):
    scans = 0

    def _scan_usage(self):
        self.scans += 1
        return super()._scan_usage()


def seed_record(index):
    identity = str(UUID(int=index + 1))
    request = {"observationId": identity, "payload": {"sample": index, "filler": "x" * 32}}
    return {"capture_id": identity, "request": request,
            "request_digest": hashlib.sha256(encoded(request)).hexdigest(),
            "fetch": {"kind": "SYNTHETIC"}, "state": "PENDING"}


def measured_phase(spool, records):
    started = time.perf_counter()
    with spool.exclusive():
        for record in records:
            spool.acknowledge(record, {"assessment": "READY", "captureId": record["capture_id"]})
    elapsed = time.perf_counter() - started
    return {"seconds": round(elapsed, 6), "writes": len(records), "full_directory_scans": spool.scans,
            "seconds_per_write": round(elapsed / len(records), 8)}


def benchmark(count, writes):
    with tempfile.TemporaryDirectory(prefix="dataops-spool-scale-") as temporary:
        root = Path(temporary)
        started = time.perf_counter()
        selected = []
        seed_bytes = 0
        for index in range(count):
            record = seed_record(index)
            payload = encoded(record)
            (root / (record["capture_id"] + ".json")).write_bytes(payload)
            seed_bytes += len(payload)
            if index < writes:
                selected.append(record)
        seed_seconds = time.perf_counter() - started
        legacy = measured_phase(LegacySpool(root), selected)
        # Restore exactly the selected synthetic records outside the timed paths.
        for record in selected:
            (root / (record["capture_id"] + ".json")).write_bytes(encoded(record))
        current = MeasuredSpool(root)
        cached = measured_phase(current, selected)
        retained = list(root.glob("*.json"))
        for record in selected:
            saved = current.read(current.path(record["capture_id"]))
            if saved["state"] != "ACK" or saved["request"] != record["request"]:
                raise AssertionError("Benchmark corrupted retained synthetic evidence")
        if len(retained) != count:
            raise AssertionError("Successful acknowledgments changed retained file count")
        return {"existing_file_count": count, "synthetic_seed_bytes": seed_bytes,
                "seed_seconds": round(seed_seconds, 6), "legacy_per_save_scan": legacy,
                "cached_exclusive": cached, "measured_speedup": round(legacy["seconds"] / cached["seconds"], 2),
                "retained_file_count_after_ack": len(retained), "selected_record_integrity": True,
                "temporary_directory_removed_after_measurement": True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--counts", type=int, nargs="+", default=[1000, 45000])
    parser.add_argument("--writes", type=int, default=64)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if not 1 <= args.writes <= 256 or any(not args.writes <= count <= 45000 for count in args.counts):
        parser.error("Use 1..256 writes and counts between writes and 45,000")
    if args.output.exists():
        parser.error("Benchmark output already exists; choose a new evidence path")
    os.umask(0o077)
    report = {"created_at": datetime.now(timezone.utc).isoformat(), "python": sys.version.split()[0],
              "platform": platform.platform(), "scope": "Synthetic retained files; measured ACK writes only",
              "limitations": "Single measured phase per size, warm local filesystem; not a whole-crawl time estimate.",
              "durability_enabled_in_both_paths": True, "production_capture_files_touched": 0,
              "retention_policy_changed": False,
              "spool_source_sha256": hashlib.sha256(Path(__file__).with_name("capture_spool.py").read_bytes()).hexdigest(),
              "results": []}
    for count in args.counts:
        result = benchmark(count, args.writes)
        report["results"].append(result)
        print(json.dumps(result), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    args.output.chmod(0o600)
    print(json.dumps({"report": str(args.output.resolve())}), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
