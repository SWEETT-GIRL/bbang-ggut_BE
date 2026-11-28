"""Durable local evidence/outbox. It never interprets fields or sends data to an LLM."""
import hashlib
import json
import os
import tempfile
import threading
from contextlib import contextmanager
from pathlib import Path
from uuid import UUID


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


class CaptureSpool:
    def __init__(self, root=None, max_bytes=None):
        self.root = Path(root or os.environ.get("DATAOPS_CAPTURE_DIR") or
                         Path(__file__).resolve().parent.parent / "dataops-logs" / "captures")
        self.max_bytes = int(max_bytes or os.environ.get("DATAOPS_CAPTURE_MAX_BYTES", 1024 ** 3))
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._lock_owner = None
        self._usage_bytes = None
        self._usage_stamp = None

    def _owns_lock(self):
        return self._lock_owner == (os.getpid(), threading.get_ident())

    def _directory_stamp(self):
        status = self.root.stat()
        return status.st_ino, status.st_mtime_ns, status.st_ctime_ns

    def _scan_usage(self):
        with os.scandir(self.root) as entries:
            return sum(entry.stat().st_size for entry in entries if entry.name.endswith(".json"))

    def _usage(self):
        stamp = self._directory_stamp()
        if self._usage_bytes is None or stamp != self._usage_stamp:
            self._usage_bytes = self._scan_usage()
            self._usage_stamp = self._directory_stamp()
        return self._usage_bytes

    def path(self, capture_id):
        return self.root / (str(UUID(capture_id)) + ".json")

    def save(self, record):
        # All public writers honor the same lock. Batch callers keep exclusive()
        # open; individual callers get a fresh disk accounting for each operation.
        if not self._owns_lock():
            with self.exclusive():
                return self.save(record)
        path = self.path(record["capture_id"])
        payload = encoded(record)
        size = self._usage()
        previous_size = path.stat().st_size if path.exists() else 0
        next_size = size - previous_size + len(payload)
        if next_size > self.max_bytes:
            raise OSError("capture spool quota reached; preserve evidence and stop collection")
        fd, temporary = tempfile.mkstemp(prefix=".capture-", dir=self.root)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
            if os.name == "posix":
                directory = os.open(self.root, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            self._usage_bytes = next_size
            self._usage_stamp = self._directory_stamp()
        except BaseException:
            # Replacement can succeed before directory fsync fails. The next
            # attempt must recount actual files instead of trusting either size.
            self._usage_bytes = None
            self._usage_stamp = None
            raise
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def retain(self, capture_id, request, fetch, *, recovery=None):
        if not self._owns_lock():
            with self.exclusive():
                return self.retain(capture_id, request, fetch, recovery=recovery)
        digest = hashlib.sha256(encoded(request)).hexdigest()
        path = self.path(capture_id)
        if path.exists():
            record = self.read(path)
            if record["request_digest"] != digest:
                raise ValueError("capture ID conflict")
            return record
        record = {"capture_id": capture_id, "request": request, "request_digest": digest,
                  "fetch": fetch, "state": "PENDING" if request.get("payload") is not None else "FETCH_FAILURE"}
        if recovery is not None:
            record["recovery"] = recovery
        self.save(record)
        return record

    def read(self, path):
        record = json.loads(path.read_text(encoding="utf-8"))
        if self.path(record["capture_id"]) != path or hashlib.sha256(encoded(record["request"])).hexdigest() != record["request_digest"]:
            raise ValueError("capture integrity check failed")
        return record

    def pending(self):
        for path in sorted(self.root.glob("*.json")):
            record = self.read(path)
            if record["state"] == "PENDING":
                yield record

    def acknowledge(self, record, result):
        self.save({**record, "state": result["assessment"] if result.get("assessment") in ("HELD", "PARTIAL") else "ACK",
                   "receipt": result})

    @contextmanager
    def exclusive(self):
        # Current deployment target is macOS. A released file lock survives neither crash nor reboot.
        import fcntl
        if self._owns_lock():
            raise RuntimeError("capture worker lock is already held by this caller")
        with open(self.root / ".worker.lock", "a") as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RuntimeError("another capture worker is active") from error
            self._lock_owner = (os.getpid(), threading.get_ident())
            self._usage_bytes = None
            self._usage_stamp = None
            try:
                self._usage()
                yield
            finally:
                self._lock_owner = None
                self._usage_bytes = None
                self._usage_stamp = None
                fcntl.flock(handle, fcntl.LOCK_UN)
