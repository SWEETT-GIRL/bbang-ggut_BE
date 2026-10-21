"""End-to-end pure evidence-to-profile/label tests, without DB/HTTP/.env reads."""
import copy
import json
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from kind_evidence import build_store_evidence
from kind_evidence_store import freeze_context, restore_context, load_store_evidence, menu_fingerprint
from store_profile_batch import bakery_score, kind_of_label, review_kind_hits, signature, signature_kinds
from recommendation_feature_load import DIMS, label_inputs, read_draft
from test_kind_evidence import observation, MENU, REMOVAL

SIGNAL = {"reviewMenus": [{"label": "식빵", "count": 50}],
          "recommendMenus": [{"name": "우유식빵"}]}
LABEL_TABLE = {"식빵": {dim: 0.5 for dim in DIMS}, "도넛": {dim: 0.1 for dim in DIMS}}


class EvidenceConsumerTest(unittest.TestCase):
    def build(self, events=(), removals=(), menus=(MENU,)):
        return build_store_evidence(events, removals, menus, "place-1")

    def test_one_missing_changes_signature_only_two_changes_score_outputs(self):
        first = self.build([observation(1, 0)])
        self.assertEqual(signature(SIGNAL, LABEL_TABLE, evidence=first), [])
        self.assertEqual(review_kind_hits(SIGNAL, LABEL_TABLE, first), [("식빵", 50)])
        self.assertEqual(label_inputs(SIGNAL, [], LABEL_TABLE, first)[1], "REVIEW")
        second = self.build([observation(1, 0), observation(2, 6)])
        self.assertEqual(signature(SIGNAL, LABEL_TABLE, evidence=second), [])
        self.assertEqual(review_kind_hits(SIGNAL, LABEL_TABLE, second), [])
        self.assertIsNone(label_inputs(SIGNAL, [], LABEL_TABLE, second)[0])
        self.assertEqual(second.filter_menus([MENU]), [])

    def test_approved_deleted_menu_does_not_resurrect_in_any_consumer(self):
        state = self.build(removals=[dict(REMOVAL, status_at_capture="APPROVED")], menus=[])
        self.assertEqual(review_kind_hits(SIGNAL, LABEL_TABLE, state), [])
        self.assertEqual(signature(SIGNAL, LABEL_TABLE, evidence=state), [])
        self.assertEqual(label_inputs(SIGNAL, [], LABEL_TABLE, state)[1], "NONE")

    def test_new_menu_can_restore_kind_without_restoring_old_menu_name(self):
        current = observation(1, 0, ["밤식빵"])
        current["representative_names"] = ["밤식빵"]
        state = self.build([current], [REMOVAL], menus=[])
        signal = dict(SIGNAL, recommendMenus=[{"name": "밤식빵"}])
        self.assertEqual(signature(signal, LABEL_TABLE, evidence=state)[0], "밤식빵")
        self.assertTrue(review_kind_hits(signal, LABEL_TABLE, state))
        self.assertFalse(state.allows_name("우유식빵"))

    def test_latest_ledger_representatives_replace_stale_signal_even_when_partial_empty(self):
        signal = {"recommendMenus": [{"name": "초코크로와상"}]}
        current = observation(1, 0, ["밤식빵"])
        current["representative_names"] = ["밤식빵"]
        state = self.build([current], menus=[])
        self.assertEqual(signature(signal, LABEL_TABLE, evidence=state), ["밤식빵"])
        self.assertNotIn("크루아상", signature_kinds(signature(signal, LABEL_TABLE, evidence=state)))
        partial = self.build([current, observation(2, 8, complete=False)], menus=[])
        self.assertEqual(signature(signal, LABEL_TABLE, evidence=partial), [])
        self.assertEqual(partial.name_states["밤식빵"], "PRESENT")
        # Before the first verified observation, the legacy source can still be
        # considered, subject to the same tombstone and classifier checks.
        self.assertEqual(signature(signal, LABEL_TABLE, evidence=self.build(menus=[])), ["초코크로와상"])

    def test_signature_checks_raw_name_tombstone_before_cleaning_quantity(self):
        raw_name = "우유식빵 1개"
        removal = dict(REMOVAL, menu_name=raw_name)
        current = observation(1, 0, ["밤식빵"])
        # A conflicting positive at the same observed time is not newer than the
        # absence. Its raw name stays held even though the other 식빵 is present.
        tied = observation(2, 0, complete=False)
        tied["representative_names"] = [raw_name]
        state = self.build([current, tied], [removal], menus=[])
        self.assertFalse(state.allows_name(raw_name))
        self.assertTrue(state.allows_name("우유식빵"))
        self.assertEqual(signature({"recommendMenus": [{"name": raw_name}]}, LABEL_TABLE, evidence=state), [])

    def test_aliases_are_identical_for_reviews_and_representatives(self):
        state = self.build(menus=[])
        kinds = ["도넛", "크루아상"]
        signal = {"reviewMenus": [{"label": "찹쌀꽈배기", "count": 20}, {"label": "초코크로와상", "count": 30}]}
        self.assertEqual(kind_of_label("찹쌀꽈배기", kinds), "도넛")
        self.assertEqual({kind for kind, _ in review_kind_hits(signal, kinds, state)}, set(kinds))
        self.assertEqual(set(signature_kinds(signature(signal, kinds, evidence=state))), set(kinds))

    def test_review_filter_does_not_change_bakery_formula_input(self):
        state = self.build(removals=[REMOVAL])
        before = bakery_score("카페,디저트", ["식빵", "빙수"], SIGNAL)
        self.assertEqual(review_kind_hits(SIGNAL, LABEL_TABLE, state), [])
        self.assertEqual(bakery_score("카페,디저트", ["식빵", "빙수"], SIGNAL), before)

    def test_frozen_context_rejects_input_changes_and_shadow(self):
        state = self.build([observation(1, 0), observation(2, 6)])
        menus = [dict(MENU, likes="1")]
        context, fp = freeze_context(state, SIGNAL, menus, 10, {"observation_id": 2})
        restored = restore_context(json.dumps(context), fp, SIGNAL, menus, 10, external_id="place-1")
        self.assertEqual(restored.fingerprint, state.fingerprint)
        for sig, ms, snapshot in ((dict(SIGNAL, reviewMenus=[]), menus, 10),
                                  (SIGNAL, [dict(menus[0], likes=2)], 10), (SIGNAL, menus, 11)):
            with self.assertRaises(ValueError):
                restore_context(context, fp, sig, ms, snapshot, external_id="place-1")
        with self.assertRaises(ValueError):
            restore_context(context, fp, SIGNAL, menus, 10, external_id="different-place")
        shadow, shadow_fp = freeze_context(state, SIGNAL, menus, 10, {}, shadow=True)
        with self.assertRaisesRegex(ValueError, "shadow"):
            restore_context(shadow, shadow_fp, SIGNAL, menus, 10, external_id="place-1")

    def test_menu_fingerprint_is_order_independent_but_includes_names_and_likes(self):
        menus = [dict(MENU, likes=0), dict(MENU, id=8, name="밤식빵", likes=1)]
        self.assertEqual(menu_fingerprint(menus), menu_fingerprint(list(reversed(menus))))
        self.assertNotEqual(menu_fingerprint(menus), menu_fingerprint([dict(m, likes=3) for m in menus]))

    def test_adapter_bounds_queries_and_does_not_query_pending_proposals(self):
        queries = []
        def query(sql):
            queries.append(sql)
            if "max(id)" in sql:
                return [{"observation_id": "2", "legacy_proposal_id": "8"}]
            if "store_sync_state" in sql:
                return [{"store_id": "1", "external_id": "place-1"}]
            if "FROM menu_evidence_observation" in sql:
                return [dict(observation(1, 0), store_id="1", menu_names="[]", representative_names="[]",
                             identity_verified="t", menu_complete="t")]
            return [dict(REMOVAL, store_id="1")]
        result, cutoff = load_store_evidence([1], [dict(MENU, store_id="1")], query)
        self.assertFalse(result["1"].allows_kind("식빵"))
        self.assertEqual(cutoff["observation_id"], 2)
        self.assertFalse(any("change_proposal" in sql for sql in queries))
        self.assertTrue(any("id <= 2" in sql for sql in queries))

    def test_without_labels_is_an_explicit_outcome_not_silently_skipped(self):
        row = {"store_id": "1", "build_status": "WITHOUT_LABELS", "profile_evidence_fingerprint": "a" * 64}
        with patch("recommendation_feature_load.local_csv", return_value=[row]):
            result = read_draft()
        self.assertEqual(result.rows, [])
        self.assertEqual(result.outcomes["1"]["status"], "WITHOUT_LABELS")

    def test_unverified_legacy_feature_draft_is_not_publishable(self):
        row = {"store_id": "1", "build_status": "", "profile_evidence_fingerprint": ""}
        with patch("recommendation_feature_load.local_csv", return_value=[row]):
            with self.assertRaises(ValueError):
                read_draft()


if __name__ == "__main__":
    unittest.main()
