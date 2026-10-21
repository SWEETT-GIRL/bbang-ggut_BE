"""Quota caching must preserve durable evidence and single-writer safety."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import capture_spool
from capture_spool import CaptureSpool, encoded


class SpoolScaleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.spool = CaptureSpool(self.root)

    def test_one_initial_scan_for_batch_retention_and_receipt_replacement(self):
        with patch.object(self.spool, "_scan_usage", wraps=self.spool._scan_usage) as scan:
            with self.spool.exclusive():
                for _ in range(25):
                    record = self.spool.retain(str(uuid4()), {"payload": {"value": "x" * 32}}, {})
                    self.spool.acknowledge(record, {"assessment": "READY"})
                self.assertEqual(1, scan.call_count)
                self.assertEqual(sum(path.stat().st_size for path in self.root.glob("*.json")), self.spool._usage_bytes)
        self.assertEqual(25, len(list(self.root.glob("*.json"))))
        self.assertEqual([], list(self.spool.pending()))

    def test_ack_keeps_raw_evidence_and_does_not_delete_successful_files(self):
        identity = str(uuid4())
        request = {"payload": {"nested": ["original", 123]}, "observationId": identity}
        with self.spool.exclusive():
            record = self.spool.retain(identity, request, {"original": "source"})
            self.spool.acknowledge(record, {"assessment": "READY", "captureId": identity})
            saved = self.spool.read(self.spool.path(identity))
            self.assertEqual("ACK", saved["state"])
            self.assertEqual(request, saved["request"])
            self.assertEqual(record["request_digest"], saved["request_digest"])
            self.assertEqual(record["fetch"], saved["fetch"])
        self.assertTrue(self.spool.path(identity).exists())

    def test_public_save_and_retain_cannot_bypass_another_workers_lock(self):
        record = self.spool.retain(str(uuid4()), {"payload": {}}, {})
        competing = CaptureSpool(self.root)
        with self.spool.exclusive():
            with self.assertRaises(RuntimeError):
                competing.save(record)
            with self.assertRaises(RuntimeError):
                competing.retain(str(uuid4()), {"payload": {}}, {})
            with self.assertRaises(RuntimeError):
                competing.acknowledge(record, {"assessment": "READY"})
        self.assertEqual("PENDING", self.spool.read(self.spool.path(record["capture_id"]))["state"])

    def test_cache_recounts_after_releasing_lock_and_other_writer_adds_data(self):
        with self.spool.exclusive():
            record = self.spool.retain(str(uuid4()), {"payload": {}}, {})
        CaptureSpool(self.root).retain(str(uuid4()), {"payload": {"extra": "x" * 100}}, {})
        actual = sum(path.stat().st_size for path in self.root.glob("*.json"))
        self.spool.max_bytes = actual
        with self.spool.exclusive():
            self.assertEqual(actual, self.spool._usage_bytes)
            with self.assertRaises(OSError):
                self.spool.acknowledge(record, {"assessment": "READY", "note": "extra receipt"})
        self.assertEqual(2, len(list(self.root.glob("*.json"))))

    def test_disappeared_file_is_recounted_before_receipt_recovery(self):
        with self.spool.exclusive():
            record = self.spool.retain(str(uuid4()), {"payload": {"retained": "raw"}}, {})
            self.spool.path(record["capture_id"]).unlink()  # Simulate external loss, not a retention action.
            receipt = {"assessment": "READY"}
            expected = {**record, "state": "ACK", "receipt": receipt}
            self.spool.max_bytes = len(encoded(expected))
            self.spool.acknowledge(record, receipt)
            self.assertEqual(len(encoded(expected)), self.spool._usage_bytes)
            self.assertEqual(record["request"], self.spool.read(self.spool.path(record["capture_id"]))["request"])

    def test_failed_replace_preserves_existing_file_and_recounts_before_retry(self):
        with self.spool.exclusive():
            record = self.spool.retain(str(uuid4()), {"payload": {}}, {})
            original = self.spool.path(record["capture_id"]).read_bytes()
            with patch.object(capture_spool.os, "replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    self.spool.acknowledge(record, {"assessment": "READY"})
            self.assertEqual(original, self.spool.path(record["capture_id"]).read_bytes())
            self.assertIsNone(self.spool._usage_bytes)
            self.spool.acknowledge(record, {"assessment": "READY"})
            self.assertEqual(sum(path.stat().st_size for path in self.root.glob("*.json")), self.spool._usage_bytes)
        self.assertEqual([], list(self.root.glob(".capture-*")))

    def test_directory_fsync_failure_after_replace_invalidates_cached_size(self):
        with self.spool.exclusive():
            record = self.spool.retain(str(uuid4()), {"payload": {}}, {})
            original_fsync = capture_spool.os.fsync
            calls = 0

            def fail_directory(fd):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("directory fsync failed after replacement")
                return original_fsync(fd)

            with patch.object(capture_spool.os, "fsync", side_effect=fail_directory):
                with self.assertRaises(OSError):
                    self.spool.acknowledge(record, {"assessment": "READY", "details": "larger"})
            self.assertIsNone(self.spool._usage_bytes)
            actual = self.spool.path(record["capture_id"]).stat().st_size
            self.spool.max_bytes = actual
            # Replacing the already-installed receipt must not count its bytes twice.
            self.spool.acknowledge(record, {"assessment": "READY", "details": "larger"})
            self.assertEqual(actual, self.spool._usage_bytes)

    def test_quota_refusal_does_not_mutate_existing_evidence(self):
        with self.spool.exclusive():
            record = self.spool.retain(str(uuid4()), {"payload": {}}, {})
            old_bytes = self.spool.path(record["capture_id"]).read_bytes()
            self.spool.max_bytes = len(old_bytes)
            with self.assertRaises(OSError):
                self.spool.acknowledge(record, {"assessment": "READY"})
            self.assertEqual(old_bytes, self.spool.path(record["capture_id"]).read_bytes())
            self.assertEqual(len(old_bytes), self.spool._usage_bytes)
            self.assertEqual([], list(self.root.glob(".capture-*")))


if __name__ == "__main__":
    unittest.main()
