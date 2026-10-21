"""Offline tests: requests, fetch, and sleeps are mocked; no source/API/database calls."""
import sys
import unittest
import tempfile
from datetime import datetime
from pathlib import Path
from types import ModuleType
from unittest.mock import Mock, patch
from uuid import UUID

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    import sync_worker
except ModuleNotFoundError as error:
    if error.name != "requests":
        raise
    # The isolated test needs no installed HTTP client. Every possible network path fails closed.
    requests_stub = ModuleType("requests")
    requests_stub.Response = object
    requests_stub.RequestException = OSError
    requests_stub.get = Mock(side_effect=AssertionError("Unexpected network call"))
    requests_stub.post = Mock(side_effect=AssertionError("Unexpected network call"))
    with patch.dict(sys.modules, {"requests": requests_stub}):
        import sync_worker


class SyncWorkerEvidenceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = patch.dict("os.environ", {"DATAOPS_CAPTURE_DIR": directory.name})
        patcher.start()
        self.addCleanup(patcher.stop)
    def test_submit_keeps_fetch_identity_and_offset_timestamp(self):
        response = Mock()
        response.json.return_value = {"data": {"changed": False, "captureId": "same-fetch", "assessment": "READY"}}
        with patch.object(sync_worker.requests, "post", return_value=response) as post:
            sync_worker.submit_snapshot("http://unused", 7, "NAVER", {"a": 1},
                                        external_id="123", observation_id="same-fetch",
                                        observed_at="2026-09-25T01:00:00+00:00")
        self.assertEqual(post.call_args.kwargs["json"], {
            "storeId": 7, "source": "NAVER", "payload": {"a": 1}, "externalId": "123",
            "observationId": "same-fetch", "observedAt": "2026-09-25T01:00:00+00:00",
        })

    def test_submit_carries_dispatch_attempt_generation(self):
        """배부 세대를 그대로 돌려보내야 서버가 늦게 도착한 이전 시도를 가려낼 수 있다 (v3 P2-1)."""
        response = Mock()
        response.json.return_value = {"data": {"changed": False, "captureId": "f", "assessment": "READY"}}
        with patch.object(sync_worker.requests, "post", return_value=response) as post:
            sync_worker.submit_snapshot("http://unused", 7, "NAVER", {"a": 1},
                                        external_id="123", observation_id="f",
                                        observed_at="2026-09-25T01:00:00+00:00", attempt_seq=42)
        self.assertEqual(post.call_args.kwargs["json"]["attemptSeq"], 42)

    def test_submit_omits_attempt_generation_when_dispatch_had_none(self):
        """세대를 모르면 주장하지 않는다 — 서버가 기존 요청으로 다룬다."""
        response = Mock()
        response.json.return_value = {"data": {"changed": False, "captureId": "f", "assessment": "READY"}}
        with patch.object(sync_worker.requests, "post", return_value=response) as post:
            sync_worker.submit_snapshot("http://unused", 7, "NAVER", {"a": 1},
                                        external_id="123", observation_id="f",
                                        observed_at="2026-09-25T01:00:00+00:00")
        self.assertNotIn("attemptSeq", post.call_args.kwargs["json"])

    def test_run_passes_dispatch_attempt_generation_through(self):
        due = {"storeId": 7, "source": "NAVER", "externalId": "123", "syncStateId": 8, "attemptSeq": 5}
        with patch.object(sync_worker, "fetch_due", side_effect=[[due], []]), \
                patch.object(sync_worker, "fetch_place", return_value=({"a": 1}, False)), \
                patch.object(sync_worker, "submit_snapshot", return_value={"changed": False}) as submit, \
                patch.object(sync_worker.time, "sleep"):
            sync_worker.run("http://unused", 1, 1, 45, 8)
        self.assertEqual(submit.call_args.kwargs["attempt_seq"], 5)

    def test_unchanged_payload_still_has_distinct_uuid_per_fetch(self):
        due = {"storeId": 7, "source": "NAVER", "externalId": "123", "syncStateId": 8}
        with patch.object(sync_worker, "fetch_due", side_effect=[[due], [due]]), \
                patch.object(sync_worker, "fetch_place", return_value=({"a": 1}, False)), \
                patch.object(sync_worker, "submit_snapshot", return_value={"changed": False}) as submit, \
                patch.object(sync_worker.time, "sleep"):
            self.assertEqual(sync_worker.run("http://unused", 1, 2, 45, 8), 0)
        first, second = [call.kwargs for call in submit.call_args_list]
        self.assertNotEqual(first["observation_id"], second["observation_id"])
        for values in (first, second):
            UUID(values["observation_id"])
            self.assertEqual(values["external_id"], "123")
            self.assertIsNotNone(datetime.fromisoformat(values["observed_at"]).utcoffset())


if __name__ == "__main__":
    unittest.main()
