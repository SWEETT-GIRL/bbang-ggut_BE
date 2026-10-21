import json
import sys
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from capture_spool import CaptureSpool


class CaptureSpoolTest(unittest.TestCase):
    def test_partial_receipt_retains_reason_without_resubmitting_or_claiming_full_success(self):
        identity = "b37befd5-1b28-420c-ac7f-01a335bb8c18"
        record = self.spool.retain(identity, {"payload": {}}, {"kind": "SUCCESS"})
        self.spool.acknowledge(record, {"captureId": identity, "assessment": "PARTIAL", "reasons": ["MENU_INCOMPLETE"]})
        retained = self.spool.read(self.spool.path(identity))
        self.assertEqual(retained["state"], "PARTIAL")
        self.assertEqual(list(self.spool.pending()), [])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.spool = CaptureSpool(self.temp.name)

    def test_lost_ack_can_be_replayed_with_identical_identity_and_content(self):
        identity = str(uuid4())
        request = {"observationId": identity, "observedAt": "2026-10-13T01:00:00Z", "payload": {"a": 1}}
        self.spool.retain(identity, request, {"kind": "SUCCESS"})
        recovered = list(CaptureSpool(self.temp.name).pending())
        self.assertEqual(recovered[0]["request"], request)
        self.spool.acknowledge(recovered[0], {"assessment": "HELD"})
        self.assertEqual(list(self.spool.pending()), [])
        self.assertEqual(self.spool.read(self.spool.path(identity))["state"], "HELD")

    def test_identity_conflict_corruption_and_path_escape_fail_closed(self):
        identity = str(uuid4())
        self.spool.retain(identity, {"payload": {}}, {})
        with self.assertRaises(ValueError):
            self.spool.retain(identity, {"payload": {"different": True}}, {})
        with self.assertRaises(ValueError):
            self.spool.path("../../escape")
        path = self.spool.path(identity)
        record = json.loads(path.read_text())
        record["request"]["payload"] = {"changed": True}
        path.write_text(json.dumps(record))
        with self.assertRaises(ValueError):
            list(self.spool.pending())

    def test_quota_preserves_existing_evidence_and_lock_prevents_overlap(self):
        self.spool.retain(str(uuid4()), {"payload": {}}, {})
        before = set(self.spool.root.glob("*.json"))
        with self.assertRaises(OSError):
            CaptureSpool(self.temp.name, max_bytes=1).retain(str(uuid4()), {"payload": {}}, {})
        self.assertEqual(set(self.spool.root.glob("*.json")), before)
        with self.spool.exclusive():
            with self.assertRaises(RuntimeError):
                with CaptureSpool(self.temp.name).exclusive():
                    self.fail("overlapping worker")

    def test_receipt_replacement_counts_only_final_file_size(self):
        identity = str(uuid4())
        record = self.spool.retain(identity, {"payload": {"large": "x" * 5000}}, {})
        self.spool.max_bytes = self.spool.path(identity).stat().st_size + 200
        self.spool.acknowledge(record, {"assessment": "READY", "captureId": identity})
        self.assertEqual(list(self.spool.pending()), [])


if __name__ == "__main__":
    unittest.main()
