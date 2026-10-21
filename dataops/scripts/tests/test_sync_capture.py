import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sync_worker as worker
from capture_spool import CaptureSpool


class SyncCaptureTest(unittest.TestCase):
    def test_partial_receipt_is_accepted_as_distinct_scoped_result(self):
        response = Mock()
        identity = "partial-receipt"
        response.json.return_value = {"data": {"captureId": identity, "assessment": "PARTIAL", "changed": True,
                                                 "reasons": ["MENU_INCOMPLETE"]}}
        with patch.object(worker.requests, "post", return_value=response):
            result = worker.submit_snapshot("http://unused", 1, "NAVER", {}, observation_id=identity,
                                            external_id="123", observed_at="2026-10-13T01:00:00Z")
        self.assertEqual(result["assessment"], "PARTIAL")

    def test_captcha_library_on_valid_place_page_does_not_block_collection(self):
        html = b'<script>function initNcaptcha() { window.ncaptcha = {}; }</script><script>window.__APOLLO_STATE__={"ROOT_QUERY":{}}</script>'
        response = Mock(status_code=200, url="https://m.place.naver.com/restaurant/1/home", content=html)
        with patch.object(worker.requests, "get", return_value=response):
            result = worker.fetch_place("1")
        self.assertEqual(result.kind, "SUCCESS")
        self.assertFalse(result.blocked)

    def test_actual_challenge_and_http_denial_are_retained_and_stop(self):
        for status, html in ((200, '<html><p>비정상적인 접근으로 접근이 제한됩니다</p></html>'),
                             (403, 'Forbidden'), (429, 'Too many requests')):
            response = Mock(status_code=status, url="https://source", content=html.encode())
            with patch.object(worker.requests, "get", return_value=response):
                result = worker.fetch_place("1")
            self.assertEqual(result.kind, "BLOCKED")
            self.assertTrue(result.blocked)
            self.assertEqual(result.failure_excerpt, html)
            self.assertIsNotNone(result.body_digest)

    def test_success_http_without_matching_capture_receipt_preserves_pending(self):
        response = Mock()
        with tempfile.TemporaryDirectory() as directory:
            spool = CaptureSpool(directory)
            identity = "7ba801cd-c154-4b7f-93d4-0dc600657c50"
            request = {"storeId": 1, "source": "NAVER", "payload": {}, "externalId": "123",
                       "observationId": identity, "observedAt": "2026-10-13T01:00:00Z"}
            record = spool.retain(identity, request, {"kind": "SUCCESS"})
            for receipt in ({"changed": False}, {"captureId": "other", "assessment": "HELD"},
                            {"captureId": identity, "assessment": "UNKNOWN"}):
                response.json.return_value = {"data": receipt}
                with patch.object(worker.requests, "post", return_value=response), self.assertRaises(ValueError):
                    spool.acknowledge(record, worker.submit_retained("http://unused", record))
                self.assertEqual(len(list(spool.pending())), 1)

    def test_timeout_http_and_missing_apollo_are_distinct(self):
        response = Mock(status_code=503, url="https://source", content=b"unavailable")
        with patch.object(worker.requests, "get", return_value=response):
            self.assertEqual(worker.fetch_place("1").kind, "HTTP_ERROR")
        response.status_code = 200
        response.content = b"<html>changed layout</html>"
        with patch.object(worker.requests, "get", return_value=response):
            result = worker.fetch_place("1")
            self.assertEqual(result.kind, "APOLLO_MISSING")
            self.assertIn("changed layout", result.failure_excerpt)

    def test_source_before_nul_normalization_is_retained(self):
        response = Mock(status_code=200, url="https://source", content=b'window.__APOLLO_STATE__={"name":"a\\u0000b"}')
        with patch.object(worker.requests, "get", return_value=response):
            result = worker.fetch_place("1")
        self.assertEqual(result.payload["name"], "ab")
        self.assertEqual(result.source_payload["name"], "a\x00b")
        payload, blocked = result
        self.assertFalse(blocked)

    def test_failed_submit_is_retried_before_fetch_with_same_observation(self):
        due = {"storeId": 7, "source": "NAVER", "externalId": "123", "syncStateId": 8, "attemptSeq": 5}
        with tempfile.TemporaryDirectory() as directory:
            spool = CaptureSpool(directory)
            with patch.object(worker, "fetch_due", return_value=[due]), \
                 patch.object(worker, "fetch_place", return_value=worker.FetchResult({}, False, "SUCCESS")), \
                 patch.object(worker, "submit_snapshot", side_effect=worker.requests.RequestException("lost ack")):
                self.assertEqual(worker.run("http://unused", 1, 1, 45, 8, spool=spool), 1)
            pending = list(spool.pending())[0]
            with patch.object(worker, "fetch_due", return_value=[]), \
                 patch.object(worker, "fetch_place") as fetch, \
                 patch.object(worker, "submit_snapshot", return_value={"assessment": "HELD", "changed": False}) as submit:
                worker.run("http://unused", 1, 1, 45, 8, spool=spool)
            fetch.assert_not_called()
            self.assertEqual(submit.call_args.kwargs["observation_id"], pending["capture_id"])
            self.assertEqual(list(spool.pending()), [])

    def test_cooldown_is_bounded_by_remaining_runtime(self):
        due = {"storeId": 7, "source": "NAVER", "externalId": "123", "syncStateId": 8}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(worker.time, "monotonic", side_effect=[0, 0, 0, 10, 31]), \
             patch.object(worker, "fetch_due", return_value=[due]), \
             patch.object(worker, "fetch_place", return_value=worker.FetchResult(None, True, "BLOCKED")), \
             patch.object(worker.time, "sleep") as sleep:
            worker.run("http://unused", 1, 1, 45, 8, max_hours=30/3600, spool=CaptureSpool(directory))
            sleep.assert_called_once_with(20)

    def test_collection_rejects_writer_enabled_or_old_server(self):
        response = Mock()
        for data in ({}, {"captureProtocol": 1, "wholeObservationHold": True, "writesEnabled": True},
                     {"captureProtocol": 1, "wholeObservationHold": True, "writesEnabled": False}):
            response.json.return_value = {"data": data}
            with patch.object(worker.requests, "get", return_value=response), self.assertRaises(RuntimeError):
                worker.safe_server("http://unused")
        response.json.return_value = {"data": {"captureProtocol": 1, "wholeObservationHold": True,
                                               "menuScopedHold": True, "writesEnabled": False}}
        with patch.object(worker.requests, "get", return_value=response):
            worker.safe_server("http://unused")


if __name__ == "__main__":
    unittest.main()
