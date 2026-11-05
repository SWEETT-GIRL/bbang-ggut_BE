import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_public_pull as public


def record(number, status='영업', name=None):
    return {'MNG_NO': str(number), 'SALS_STTS_NM': status, 'BPLC_NM': name or f'가게{number}'}


class PublicPullTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / 'run'
        self.seed = Path(self.directory.name) / 'seed'
        p = self.seed / 'matching/localdata_raw/bakeries.jsonl'
        p.parent.mkdir(parents=True)
        p.write_text('\n'.join(json.dumps(r) for r in [record(0), record(1), record(500)]) + '\n')
        self.baseline = p.read_bytes()

    def fetch(self, dataset, number):
        rows = [record(n, '폐업' if n == 1 else '영업') for n in range(101)]
        return {'totalCount': 101, 'items': {'item': rows[(number-1)*100:number*100]}}

    def test_partial_pages_never_publish_delta_and_resume_keeps_baseline(self):
        self.assertEqual(public.collect(self.root, self.seed, ['bakeries'], 1, 60, self.fetch), 4)
        self.assertFalse((self.root / 'matching/localdata_raw/delta/bakeries_upd.jsonl').exists())
        self.assertEqual(public.collect(self.root, self.seed, ['bakeries'], 2, 60, self.fetch), 0)
        report = json.loads((self.root / 'public-result.json').read_text())
        self.assertEqual(report['datasets']['bakeries']['counts'], {'raw': 101, 'new': 99, 'upd': 1, 'unobserved': 1})
        self.assertEqual((self.seed / 'matching/localdata_raw/bakeries.jsonl').read_bytes(), self.baseline)
        self.assertEqual(public.collect(self.root, self.seed, ['bakeries'], 1, 60, self.fetch), 0)

    def test_duplicate_page_boundary_is_not_a_complete_snapshot(self):
        def duplicate(dataset, number):
            body = self.fetch(dataset, number)
            if number == 2: body['items']['item'] = [record(0)]
            return body
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            public.collect(self.root, self.seed, ['bakeries'], 5, 60, duplicate)
        self.assertFalse((self.root / 'matching/localdata_raw/bakeries.jsonl').exists())

    def test_failed_request_and_quota_can_resume_without_losing_pages(self):
        def quota(dataset, number):
            if number == 2: raise public.QuotaExceeded()
            return self.fetch(dataset, number)
        self.assertEqual(public.collect(self.root, self.seed, ['bakeries'], 5, 60, quota), 3)
        self.assertTrue((self.root / 'pages/bakeries/1.json').exists())
        self.assertEqual(public.collect(self.root, self.seed, ['bakeries'], 2, 60, self.fetch), 0)

    def test_changed_first_page_and_corrupt_checkpoint_are_rejected(self):
        public.collect(self.root, self.seed, ['bakeries'], 1, 60, self.fetch)
        def changed(dataset, number):
            body = self.fetch(dataset, number)
            body['items']['item'][0]['BPLC_NM'] = '변경'
            return body
        with self.assertRaisesRegex(ValueError, 'first page changed'):
            public.collect(self.root, self.seed, ['bakeries'], 5, 60, changed)
        p = self.root / 'pages/bakeries/1.json'
        doc = json.loads(p.read_text())
        doc['rows'][0]['BPLC_NM'] = '손상'
        p.write_text(json.dumps(doc))
        with self.assertRaisesRegex(ValueError, 'checkpoint mismatch'):
            public.collect(self.root, self.seed, ['bakeries'], 5, 60, self.fetch)

    def test_missing_rows_and_changing_total_do_not_generate_false_closures(self):
        def short(dataset, number):
            body = self.fetch(dataset, number)
            body['items']['item'] = []
            return body
        with self.assertRaisesRegex(ValueError, 'row count'):
            public.collect(self.root, self.seed, ['bakeries'], 5, 60, short)
