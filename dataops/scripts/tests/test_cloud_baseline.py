import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_baseline as baseline
import freshness_cycle as cycle
import weekly_refresh


class CloudBaselineTest(unittest.TestCase):
    def store(self, sid='1', address='서울특별시 강남구 강남대로 10', name='빵집'):
        return dict(id=sid, name=name, address=address, naver_place_id='123')

    def record(self, mgmt='a', address='서울특별시 강남구 강남대로 10', name='빵집'):
        return dict(MNG_NO=mgmt, ROAD_NM_ADDR=address, BPLC_NM=name, SALS_STTS_NM='영업')

    def test_only_unique_exact_full_address_and_name_links_are_accepted(self):
        linked, counts = baseline.match_identities([self.store()], [self.record()])
        self.assertEqual(counts['mapped'], 1)
        self.assertEqual(linked[0]['naver_place_id'], '123')
        for records in ([self.record(), self.record('b')],
                        [self.record(address='부산광역시 강남구 강남대로 10')],
                        [self.record(address='서울특별시 강남구 강남대로 10-1')],
                        [self.record(address='강남대로 10')], [self.record(name='빵집 지점')]):
            self.assertEqual(baseline.match_identities([self.store()], records)[0], [])
        self.assertEqual(baseline.match_identities([self.store(), self.store('2')], [self.record()])[0], [])

    def test_bootstrap_needs_no_local_data_and_resumes_without_publishing_partial_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def fetch(dataset, page):
                records = [self.record(str(i), name='빵집' if i == 0 else f'가게{i}') for i in range(101)]
                return {'totalCount': 101, 'items': {'item': records[(page-1)*100:page*100]}}
            self.assertEqual(baseline.bootstrap(root, ['bakeries'], 1, 60, fetch, lambda: [self.store()]), 4)
            self.assertFalse((root/'manifest.json').exists())
            self.assertEqual(baseline.bootstrap(root, ['bakeries'], 2, 60, fetch, lambda: [self.store()]), 0)
            self.assertEqual(json.loads((root/'identity-summary.json').read_text())['mapped'], 1)
            metrics = cycle.item_metrics(root, baseline=True)
            self.assertEqual(metrics['public_raw'], 101)
            self.assertNotIn('public_new', metrics)
            self.assertEqual((root/'matching/new_review_judgments.jsonl').read_text(), '')
            def forbidden(*args): self.fail('completed baseline fetched again')
            self.assertEqual(baseline.bootstrap(root, ['bakeries'], 1, 60, forbidden, forbidden), 0)
            with self.assertRaisesRegex(ValueError, 'scope changed'):
                baseline.bootstrap(root, ['rest_cafes'], 1, 60, forbidden, forbidden)

    def test_refresh_can_run_while_initial_public_baseline_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict('os.environ', {}, clear=True):
            with patch.object(weekly_refresh, 'LOG_DIR', Path(directory)/'workflow'), \
                 patch.object(cycle, 'safe_server'), patch.object(cycle, 'bootstrap', return_value=4), \
                 patch.object(cycle, 'execute_track', return_value=0) as execute:
                result = cycle.main(['run', '--state-dir', directory])
            self.assertEqual(result, 4)
            self.assertEqual([call.args[0] for call in execute.call_args_list], ['refresh'])

    def test_plan_does_not_require_an_import_or_a_database(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict('os.environ', {}, clear=True), \
             patch.object(weekly_refresh, 'LOG_DIR', Path(directory)/'workflow'), \
             patch.object(cycle, 'safe_server', side_effect=AssertionError):
            self.assertEqual(cycle.main(['plan', '--state-dir', directory]), 0)
