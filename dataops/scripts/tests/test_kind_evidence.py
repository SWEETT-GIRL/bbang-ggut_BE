"""Pure evidence contract tests; no database, HTTP, or runtime credentials."""
import copy
import json
from datetime import timedelta
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import kind_evidence
from kind_evidence import EvidencePolicy, StoreEvidence, build_store_evidence, canonical_kind


def observation(number, hour, names=(), complete=True, **extra):
    return dict(id=number, observation_id=str(number), source="NAVER", external_id="place-1",
                observed_at=f"2026-09-{25 + hour // 24:02d}T{hour % 24:02d}:00:00+09:00",
                received_at=f"2026-09-{25 + hour // 24:02d}T{hour % 24:02d}:01:00+09:00",
                menu_names=list(names), representative_names=[], menu_complete=complete,
                identity_verified=True, parser_version=1, **extra)


MENU = {"id": "7", "name": "우유식빵", "kind": "식빵"}
REMOVAL = {"proposal_id": 8, "menu_id": 7, "menu_name": "우유식빵", "source": "NAVER",
           "evidence_at": "2026-09-24T00:00:00+09:00", "status_at_capture": "PENDING"}


class KindEvidenceTest(unittest.TestCase):
    def build(self, events=(), removals=(), menus=(MENU,), **kwargs):
        return build_store_evidence(events, removals, menus, "place-1", **kwargs)

    def test_aliases_and_exact_kinds_are_shared_but_ambiguous_names_are_not(self):
        self.assertEqual(canonical_kind("찹쌀꽈배기"), "도넛")
        self.assertEqual(canonical_kind("초코크로와상"), "크루아상")
        self.assertEqual(canonical_kind("롤"), "롤")
        self.assertIsNone(canonical_kind("캐롤"))
        self.assertIsNone(canonical_kind("키슈"))
        self.assertIsNone(canonical_kind("기타"))
        self.assertIsNone(canonical_kind("밤식빵", {"도넛"}))
        self.assertEqual(canonical_kind("새정식종류", {"새정식종류"}), "새정식종류")

    def test_approval_and_soft_delete_do_not_erase_legacy_hold(self):
        before = self.build(removals=[REMOVAL])
        approved = self.build(removals=[dict(REMOVAL, status_at_capture="APPROVED")])
        self.assertEqual(before.fingerprint, approved.fingerprint)
        after = self.build(removals=[dict(REMOVAL, status_at_capture="APPROVED")], menus=[])
        for evidence in (before, after):
            self.assertEqual(evidence.states["식빵"], "UNKNOWN")
            self.assertFalse(evidence.allows_kind("식빵"))
            self.assertFalse(evidence.allows_name("우유식빵"))

    def test_same_old_reviews_rescraped_never_restore_a_kind(self):
        event = observation(1, 0, complete=False)
        event["reviewMenus"] = [{"label": "식빵", "count": 1000}]
        evidence = self.build([event], [REMOVAL])
        self.assertFalse(evidence.allows_kind("식빵"))

    def test_new_current_name_restores_kind_but_not_old_name(self):
        evidence = self.build([observation(1, 0, ["밤식빵"])], [REMOVAL], menus=[])
        self.assertEqual(evidence.states["식빵"], "PRESENT")
        self.assertTrue(evidence.allows_kind("식빵", "signature"))
        self.assertTrue(evidence.allows_name("밤식빵"))
        self.assertFalse(evidence.allows_name("우유식빵"))

    def test_representative_name_is_direct_positive_even_without_menu_add(self):
        event = observation(1, 0, complete=False)
        event["representative_names"] = ["밤식빵"]
        evidence = self.build([event], [REMOVAL], menus=[])
        self.assertTrue(evidence.allows_name("밤식빵"))
        self.assertEqual(evidence.representative_names, ("밤식빵",))
        self.assertEqual(self.build([event, observation(2, 8, complete=False)]).representative_names, ())

    def test_observed_unclassifiable_name_can_use_existing_taxonomy_not_presence(self):
        menu = dict(MENU, name="아침의미소")
        self.assertEqual(self.build(menus=[menu]).states["식빵"], "UNKNOWN")
        evidence = self.build([observation(1, 0, ["아침의미소"])], menus=[menu])
        self.assertEqual(evidence.states["식빵"], "PRESENT")

    def test_another_observed_menu_of_same_kind_prevents_kind_suppression(self):
        events = [observation(1, 0, ["밤식빵"]), observation(2, 8, ["밤식빵"])]
        evidence = self.build(events)
        self.assertEqual(evidence.name_states["우유식빵"], "CONFIRMED_MISSING")
        self.assertEqual(evidence.states["식빵"], "PRESENT")
        self.assertEqual(evidence.filter_menus([MENU]), [])

    def test_signature_one_score_two_use_same_evidence(self):
        first = self.build([observation(1, 0)])
        self.assertEqual(first.states["식빵"], "MISSING_ONCE")
        self.assertTrue(first.allows_kind("식빵", "score"))
        self.assertFalse(first.allows_kind("식빵", "signature"))
        second = self.build([observation(1, 0), observation(2, 6)])
        self.assertEqual(second.states["식빵"], "CONFIRMED_MISSING")
        self.assertFalse(second.allows_kind("식빵", "score"))

    def test_duplicate_and_input_permutation_are_idempotent(self):
        first, second = observation(1, 0), observation(2, 8)
        original = self.build([first, second])
        duplicate = self.build([second, first, dict(first)])
        self.assertEqual(original.as_dict(), duplicate.as_dict())
        conflicting = dict(first, menu_names=["우유식빵"])
        with self.assertRaises(ValueError):
            self.build([first, conflicting])

    def test_late_and_tight_retries_do_not_increment_or_restore(self):
        late = observation(3, 1, ["우유식빵"])
        late["received_at"] = "2026-09-25T09:00:00+09:00"
        evidence = self.build([observation(1, 0), observation(2, 8), late])
        self.assertEqual(evidence.states["식빵"], "CONFIRMED_MISSING")
        self.assertEqual(evidence.ignored_events["late"], 1)
        tight = self.build([observation(1, 0), observation(2, 1)])
        self.assertEqual(tight.states["식빵"], "MISSING_ONCE")

    def test_positive_at_same_timestamp_does_not_override_negative(self):
        positive = observation(3, 8, ["우유식빵"])
        positive["received_at"] = "2026-09-25T09:00:00+09:00"
        evidence = self.build([observation(1, 0), observation(2, 8), positive])
        self.assertFalse(evidence.allows_kind("식빵"))

    def test_partial_never_counts_absence_and_gap_restarts_unconfirmed_streak(self):
        policy = EvidencePolicy(max_interval=timedelta(hours=12))
        events = [observation(1, 0), observation(2, 8, complete=False), observation(3, 16)]
        evidence = self.build(events, policy=policy)
        self.assertEqual(evidence.states["식빵"], "MISSING_ONCE")
        self.assertTrue(evidence.allows_kind("식빵"))
        self.assertNotEqual(evidence.fingerprint, self.build(events).fingerprint)

    def test_confirmed_negative_does_not_expire_back_into_present(self):
        policy = EvidencePolicy(max_interval=timedelta(hours=12))
        evidence = self.build([observation(1, 0), observation(2, 6), observation(3, 24)], policy=policy)
        self.assertFalse(evidence.allows_kind("식빵"))

    def test_other_place_or_unverified_event_cannot_restore(self):
        other = observation(1, 0, ["우유식빵"])
        other["external_id"] = "old-place"
        unverified = observation(2, 8, ["우유식빵"])
        unverified["identity_verified"] = False
        evidence = self.build([other, unverified], [REMOVAL])
        self.assertFalse(evidence.allows_kind("식빵"))
        self.assertFalse(evidence.has_observations)
        self.assertIsNone(evidence.latest_observed_at)

    def test_observation_metadata_distinguishes_empty_current_from_no_observation(self):
        baseline = self.build()
        current = self.build([observation(1, 0, complete=False)])
        self.assertEqual(baseline.representative_names, current.representative_names)
        self.assertFalse(baseline.has_observations)
        self.assertTrue(current.has_observations)
        self.assertEqual(current.latest_observed_at.isoformat(), "2026-09-25T00:00:00+09:00")
        self.assertEqual(StoreEvidence.from_dict(current.as_dict()).latest_observed_at,
                         current.latest_observed_at)

    def test_unknown_time_hold_and_baseline_row_do_not_forge_presence(self):
        removal = dict(REMOVAL, evidence_at=None)
        evidence = self.build([observation(1, 0, ["우유식빵"])], [removal])
        self.assertFalse(evidence.allows_kind("식빵"))
        baseline = self.build()
        self.assertEqual(baseline.states["식빵"], "UNKNOWN")
        self.assertTrue(baseline.allows_kind("식빵"))

    def test_json_roundtrip_and_tamper_or_classifier_change_rejection(self):
        evidence = self.build([observation(1, 0), observation(2, 6)])
        serialized = json.loads(json.dumps(evidence.as_dict()))
        self.assertEqual(StoreEvidence.from_dict(serialized).as_dict(), serialized)
        tampered = copy.deepcopy(serialized)
        tampered["kinds"]["식빵"]["legacy_hold"] = True
        with self.assertRaises(ValueError):
            StoreEvidence.from_dict(tampered)
        with patch("kind_evidence.CLASSIFIER_FINGERPRINT", "new-dictionary"):
            with self.assertRaisesRegex(ValueError, "classifier"):
                StoreEvidence.from_dict(serialized)

    def test_classifier_rule_only_change_invalidates_frozen_result(self):
        # Keep vocabulary and manual version unchanged; changing rule source is
        # sufficient to require a new build. No actual source file is modified.
        before = kind_evidence._classifier_fingerprint(b"def doubt(): return False")
        after = kind_evidence._classifier_fingerprint(b"def doubt(): return True")
        self.assertNotEqual(before, after)
        with patch("kind_evidence.CLASSIFIER_FINGERPRINT", before):
            frozen = self.build().as_dict()
        with patch("kind_evidence.CLASSIFIER_FINGERPRINT", after):
            with self.assertRaisesRegex(ValueError, "classifier"):
                StoreEvidence.from_dict(frozen)


if __name__ == "__main__":
    unittest.main()
