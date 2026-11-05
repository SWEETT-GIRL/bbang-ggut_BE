from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_candidates import normalized


class CandidateNormalizationTest(unittest.TestCase):
    def row(self, place):
        return dict(mgmt_no='license', licenseName='빵집', address='주소', dataset='bakeries', licensed_ymd='20261101', placeId=place)

    def test_repeated_same_identity_is_one_candidate_and_conflicting_identity_is_held(self):
        candidates, conflicts = normalized([self.row('1'), self.row('1')], 'discovery')
        self.assertEqual((len(candidates), conflicts), (1, []))
        self.assertEqual(candidates[0]['date'], '2026-11-01')
        self.assertEqual(normalized([self.row('1'), self.row('2')], 'discovery'), ([], ['license']))

    def test_public_confirmation_uses_the_same_candidate_contract(self):
        candidates, conflicts = normalized([dict(mgmt_no='license', lic_name='빵집', road_address='주소', dataset='bakeries',
                                                 licensed_ymd='2026-11-01', naver_place_id='1')], 'public')
        self.assertEqual(candidates[0]['place_id'], '1')
        with self.assertRaises(ValueError): normalized([self.row('')], 'discovery')
