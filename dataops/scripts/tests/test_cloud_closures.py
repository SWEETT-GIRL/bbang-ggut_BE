from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sync_worker import FetchResult
from localdata_status_delta import closure_verdict, identity_matches


class ClosureVerdictTest(unittest.TestCase):
    def test_changed_store_identity_cannot_reuse_old_license_link(self):
        self.assertTrue(identity_matches((1, '가게', '123'), '새 이름', '123'))
        self.assertFalse(identity_matches((1, '가게', '123'), '가게', '456'))
        self.assertFalse(identity_matches((1, '', ''), '가게', '123'))
        self.assertFalse(identity_matches((1, '옛가게', ''), '새가게', '123'))

    def test_blocked_http_error_and_parser_failure_are_never_absence(self):
        for result in [FetchResult(None, True, 'BLOCKED', 403), FetchResult(None, False, 'HTTP_ERROR', 500),
                       FetchResult(None, False, 'APOLLO_MISSING', 200), FetchResult(None, False, 'APOLLO_INVALID', 200),
                       FetchResult({}, False, 'SUCCESS', 200)]:
            self.assertEqual(closure_verdict(result)[0], 'UNKNOWN_HOLD')

    def test_explicit_http_absence_can_only_be_a_proposal(self):
        for status in [404, 410]:
            self.assertEqual(closure_verdict(FetchResult(None, False, 'HTTP_ERROR', status)), ('ENQUEUE', 'HTTP_ABSENT'))

    def test_positive_liveness_overrides_license_closure(self):
        with patch('localdata_status_delta.place_base', return_value={'name': '영업중'}):
            self.assertEqual(closure_verdict(FetchResult({'base': {}}, False, 'SUCCESS', 200))[0], 'CONFLICT_HOLD')
        with patch('localdata_status_delta.place_base', return_value=None):
            self.assertEqual(closure_verdict(FetchResult({'unknown': {}}, False, 'SUCCESS', 200))[0], 'UNKNOWN_HOLD')
