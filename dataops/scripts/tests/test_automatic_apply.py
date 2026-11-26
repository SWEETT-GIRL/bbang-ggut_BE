import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import sync_worker as worker
from capture_spool import CaptureSpool


class AutomaticApplyTest(unittest.TestCase):
    def record(self, spool):
        return spool.retain('7ba801cd-c154-4b7f-93d4-0dc600657c50', {'storeId': 1, 'source': 'NAVER', 'payload': {},
                            'externalId': '123', 'observationId': '7ba801cd-c154-4b7f-93d4-0dc600657c50',
                            'observedAt': '2026-11-27T00:00:00Z'}, {'kind': 'SUCCESS'})

    def receipt(self, **counts):
        return {'captureId': '7ba801cd-c154-4b7f-93d4-0dc600657c50', 'applied': 1, 'alreadyApplied': 0, 'held': 0,
                'failed': 0, 'unknown': 0, **counts}

    def test_apply_timeout_retains_identity_then_resumes_before_new_crawl(self):
        with tempfile.TemporaryDirectory() as root:
            spool = CaptureSpool(Path(root) / 'captures')
            record = self.record(spool)
            response = Mock()
            response.json.return_value = {'data': self.receipt(applied=0, alreadyApplied=1)}
            with patch.object(worker, 'submit_snapshot', return_value={'captureId': '7ba801cd-c154-4b7f-93d4-0dc600657c50', 'assessment': 'READY', 'changed': True}), \
                 patch.object(worker.requests, 'post', side_effect=worker.requests.Timeout()):
                self.assertEqual(worker.run('http://unused', 1, 1, 0, 0, spool=spool, auto_apply=True), 1)
            self.assertEqual(len(list(spool.pending())), 1)
            with patch.object(worker, 'submit_snapshot', return_value={'captureId': '7ba801cd-c154-4b7f-93d4-0dc600657c50', 'assessment': 'READY', 'changed': True}), \
                 patch.object(worker.requests, 'post', return_value=response) as post, \
                 patch.object(worker, 'fetch_due', return_value=[]):
                summary = Path(root) / 'summary.json'
                self.assertEqual(worker.run('http://unused', 1, 1, 0, 0, spool=spool, summary_path=summary, auto_apply=True), 0)
            self.assertEqual(list(spool.pending()), [])
            self.assertIn('/7ba801cd-c154-4b7f-93d4-0dc600657c50/auto-apply', post.call_args.args[0])
            self.assertEqual(json.loads(summary.read_text())['auto_apply_already_applied'], 1)

    def test_unknown_is_recorded_and_exits_nonzero_without_retrying_it(self):
        with tempfile.TemporaryDirectory() as root:
            spool = CaptureSpool(Path(root) / 'captures')
            self.record(spool)
            response = Mock()
            response.json.return_value = {'data': self.receipt(applied=0, unknown=1)}
            with patch.object(worker, 'submit_snapshot', return_value={'captureId': '7ba801cd-c154-4b7f-93d4-0dc600657c50', 'assessment': 'READY', 'changed': True}), \
                 patch.object(worker.requests, 'post', return_value=response), \
                 patch.object(worker, 'fetch_due', return_value=[]):
                self.assertEqual(worker.run('http://unused', 1, 1, 0, 0, spool=spool, auto_apply=True), 1)
            self.assertEqual(list(spool.pending()), [])

    def test_missing_capability_never_silently_falls_back_to_collection_only(self):
        response = Mock()
        response.json.return_value = {'data': {'captureProtocol': 1, 'wholeObservationHold': True,
                                              'menuScopedHold': True, 'writesEnabled': False}}
        with patch.object(worker.requests, 'get', return_value=response):
            with self.assertRaises(RuntimeError): worker.safe_server('http://unused', auto_apply=True)

    def test_capture_mismatch_does_not_acknowledge(self):
        with tempfile.TemporaryDirectory() as root:
            spool = CaptureSpool(Path(root) / 'captures')
            record = self.record(spool)
            response = Mock()
            response.json.return_value = {'data': self.receipt(captureId='another')}
            with patch.object(worker, 'submit_snapshot', return_value={'captureId': '7ba801cd-c154-4b7f-93d4-0dc600657c50', 'assessment': 'PARTIAL'}), \
                 patch.object(worker.requests, 'post', return_value=response):
                with self.assertRaises(ValueError): worker.submit_retained('http://unused', record, auto_apply=True)
            self.assertEqual(len(list(spool.pending())), 1)
