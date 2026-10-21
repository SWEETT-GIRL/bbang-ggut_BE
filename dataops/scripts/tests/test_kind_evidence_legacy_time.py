"""Legacy observation time regressions; pure fixtures, no DB/network/.env."""
from datetime import datetime, timezone, timedelta
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kind_evidence import build_store_evidence


KST = timezone(timedelta(hours=9))
REMOVAL = {"proposal_id": 1, "store_id": 10, "menu_id": "7", "menu_name": "소금빵",
           "source": "NAVER", "status_at_capture": "APPROVED", "snapshot_id": 101,
           "evidence_at": "2026-09-25T08:00:00+09:00",
           "proposal_created_at": "2026-09-25T12:00:00+09:00", "time_basis": "SNAPSHOT_FETCHED_AT"}


def positive(hour):
    return {"id": 1, "observation_id": "positive-1", "store_id": 10, "source": "NAVER",
            "external_id": "place-1", "observed_at": datetime(2026, 9, 25, hour, tzinfo=KST),
            "received_at": datetime(2026, 9, 25, 13, tzinfo=KST),
            "menu_names": ["소금빵"], "representative_names": ["소금빵"],
            "menu_complete": True, "identity_verified": True, "parser_version": "fixture-v1"}


class LegacyEvidenceTimeTest(unittest.TestCase):
    def build(self, removal, events):
        return build_store_evidence(events, [removal], [], "place-1")

    def test_positive_between_fetch_and_reproposal_clears_hold(self):
        state = self.build(REMOVAL, [positive(11)])
        self.assertEqual(state.states["소금빵"], "PRESENT")
        self.assertTrue(state.allows_kind("소금빵", "score"))
        self.assertTrue(state.allows_name("소금빵", "signature"))

    def test_administrative_creation_time_does_not_move_observation_boundary(self):
        for created_at in ("2026-09-25T12:00:00+09:00", "2026-10-01T12:00:00+09:00"):
            with self.subTest(created_at=created_at):
                state = self.build(dict(REMOVAL, proposal_created_at=created_at), [positive(11)])
                self.assertEqual(state.states["소금빵"], "PRESENT")

    def test_positive_not_newer_than_snapshot_cannot_clear_hold(self):
        for hour in (7, 8):
            with self.subTest(hour=hour):
                state = self.build(REMOVAL, [positive(hour)])
                self.assertFalse(state.allows_kind("소금빵"))

    def test_missing_or_mismatched_snapshot_preserves_unknown_hold(self):
        for snapshot_id in (None, 999, 102):
            with self.subTest(snapshot_id=snapshot_id):
                removal = dict(REMOVAL, snapshot_id=snapshot_id, evidence_at=None, time_basis="UNKNOWN")
                state = self.build(removal, [positive(13)])
                self.assertEqual(state.states["소금빵"], "UNKNOWN")
                self.assertFalse(state.allows_kind("소금빵"))
                self.assertFalse(state.allows_name("소금빵"))

    def test_legacy_timestamp_does_not_forge_missing_observation_counts(self):
        state = self.build(REMOVAL, [])
        self.assertEqual(state.states["소금빵"], "UNKNOWN")
        self.assertEqual(state.as_dict()["kinds"]["소금빵"]["missing_count"], 0)


if __name__ == "__main__":
    unittest.main()
