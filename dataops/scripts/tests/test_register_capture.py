import argparse
import contextlib
import datetime as dt
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from uuid import UUID

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import localdata_register_worker as worker
from capture_spool import CaptureSpool


PAYLOAD = {"PlaceDetailBase:123": {"name": "빵집", "roadAddress": "주소", "phone": "",
                                 "coordinate": {"x": "127.0", "y": "37.5"}}}


class RegisterCaptureTest(unittest.TestCase):
    def args(self, **changes):
        values = dict(server="http://unused", only=None, max_items=None, preview=False,
                      probe=False, interval=0, report=None)
        values.update(changes)
        return argparse.Namespace(**values)

    def mock_dependencies(self, stack, matched):
        stack.enter_context(patch.object(worker, "load_matched", return_value=matched))
        stack.enter_context(patch.object(worker, "load_jibun", return_value={}))
        stack.enter_context(patch.object(worker.local_db, "connect", return_value=Mock()))
        resolver = Mock()
        resolver.resolve.return_value = {"status": "RESOLVED", "reused": False}
        stack.enter_context(patch.object(worker, "PointResolver", return_value=resolver))
        stack.enter_context(patch.object(worker, "registered_store_ids", return_value={m[0]: i + 1 for i, m in enumerate(matched)}))
        fetch = stack.enter_context(patch.object(worker, "fetch_place", return_value=(PAYLOAD, False)))
        stack.enter_context(patch.object(worker.time, "sleep"))
        return fetch

    def response(self, data):
        response = Mock()
        response.json.return_value = {"data": data}
        return response

    def registration_response(self, count):
        return self.response(dict(registered=count, dedupResolved=0, skipped=0, failed=0))

    def test_registration_submits_retained_metadata_and_counts_assessments(self):
        matched = [(f"m{i}", "빵집", str(100 + i), None) for i in range(3)]
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            spool = CaptureSpool(directory)
            self.mock_dependencies(stack, matched)
            output = io.StringIO()
            stack.enter_context(contextlib.redirect_stdout(output))
            sent = []

            def post(url, *, json, timeout, headers=None):
                if url.endswith("register-batch"):
                    return self.registration_response(3)
                self.assertTrue(url.endswith("/snapshots"))
                # All three are durable before the first network send.
                self.assertEqual(len(list(Path(directory).glob("*.json"))), 3)
                record = spool.read(spool.path(json["observationId"]))
                self.assertEqual(record["request"], json)
                UUID(json["observationId"])
                self.assertIsNotNone(dt.datetime.fromisoformat(json["observedAt"]).tzinfo)
                self.assertEqual(json["externalId"], str(100 + len(sent)))
                sent.append(json)
                assessment = ("READY", "PARTIAL", "HELD")[len(sent) - 1]
                return self.response({"captureId": json["observationId"], "assessment": assessment,
                                      "snapshotId": 20 if assessment == "PARTIAL" else None})

            stack.enter_context(patch.object(worker.requests, "post", side_effect=post))
            with spool.exclusive():
                self.assertEqual(worker.run(self.args(), spool), 0)
            self.assertEqual(len(sent), 3)
            self.assertEqual(list(spool.pending()), [])
            for value in ("'snapshots': 1", "'snapshot_ready': 1", "'snapshot_partial': 1", "'snapshot_held': 1"):
                self.assertIn(value, output.getvalue())

    def test_lost_ack_replays_before_empty_worklist_without_refetch(self):
        with tempfile.TemporaryDirectory() as directory:
            spool = CaptureSpool(directory)
            sent = []
            with contextlib.ExitStack() as stack:
                self.mock_dependencies(stack, [("m1", "빵집", "123", None), ("m2", "빵집", "124", None)])

                def lost_ack(url, *, json, timeout, headers=None):
                    if url.endswith("register-batch"):
                        return self.registration_response(2)
                    sent.append(json)
                    raise worker.requests.RequestException("lost ACK")

                stack.enter_context(patch.object(worker.requests, "post", side_effect=lost_ack))
                with spool.exclusive():
                    self.assertEqual(worker.run(self.args(), spool), 1)
            pending = list(spool.pending())
            self.assertEqual(len(pending), 2)
            replayed = []
            with contextlib.ExitStack() as stack:
                fetch = self.mock_dependencies(stack, [])

                def accepted(url, *, json, timeout, headers=None):
                    self.assertTrue(url.endswith("/snapshots"))
                    replayed.append(json)
                    return self.response({"captureId": json["observationId"], "assessment": "HELD", "snapshotId": None})

                stack.enter_context(patch.object(worker.requests, "post", side_effect=accepted))
                with spool.exclusive():
                    self.assertEqual(worker.run(self.args(), spool), 0)
                fetch.assert_not_called()
            self.assertIn(sent[0], replayed)
            self.assertEqual({r["observationId"] for r in replayed}, {r["capture_id"] for r in pending})
            self.assertEqual(list(spool.pending()), [])

    def test_http_success_without_matching_receipt_remains_pending(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(worker, "fetch_place", return_value=(PAYLOAD, False)):
            spool = CaptureSpool(directory)
            capture, _ = worker.capture_fetch("123")
            record = worker.retain_snapshot(spool, 1, "m1", capture)
            totals = {"snapshot_pending": 0}
            with patch.object(worker.requests, "post", return_value=self.response({"assessment": "READY", "captureId": "other"})):
                self.assertFalse(worker.deliver_snapshot("http://unused", spool, record, totals))
            self.assertEqual(totals, {"snapshot_pending": 1})
            self.assertEqual(len(list(spool.pending())), 1)

    def test_source_raw_is_retained_before_normalization(self):
        source = {"name": "a\x00b"}
        fetched = worker.FetchResult({"name": "ab"}, False, "SUCCESS", 200, "body-hash", source)
        with tempfile.TemporaryDirectory() as directory, patch.object(worker, "fetch_place", return_value=fetched):
            capture, _ = worker.capture_fetch("123")
            record = worker.retain_snapshot(CaptureSpool(directory), 1, "m1", capture)
            self.assertEqual(record["fetch"]["source_payload"], source)
            self.assertEqual(record["request"]["payload"], {"name": "ab"})

    def test_preview_never_replays_or_creates_capture_spool(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            self.mock_dependencies(stack, [("m1", "빵집", "123", None)])
            report = Path(directory) / "preview.tsv"
            capture_dir = Path(directory) / "captures"
            argv = ["register", "--preview", "--report", str(report), "--capture-dir", str(capture_dir)]
            stack.enter_context(patch.object(sys, "argv", argv))

            def preview(url, **kwargs):
                self.assertTrue(url.endswith("register-preview"))
                return self.response([{"mgmtNo": "m1", "name": "빵집", "plan": "INSERT"}])

            post = stack.enter_context(patch.object(worker.requests, "post", side_effect=preview))
            self.assertEqual(worker.main(), 0)
            post.assert_called_once()
            self.assertTrue(report.exists())
            self.assertFalse(capture_dir.exists())

    def test_main_holds_exclusive_lock_and_empty_queue_needs_no_fetch(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(sys, "argv", ["register", "--capture-dir", directory]))
            fetch = stack.enter_context(patch.object(worker, "fetch_place"))

            def no_work(*args):
                with self.assertRaisesRegex(RuntimeError, "another capture worker"):
                    with CaptureSpool(directory).exclusive():
                        pass
                return []

            stack.enter_context(patch.object(worker, "load_matched", side_effect=no_work))
            self.assertEqual(worker.main(), 0)
            fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
