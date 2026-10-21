"""Legacy preservation does not authorize an unverified change to serving data."""
import copy
import json
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import kind_evidence
from kind_evidence import StoreEvidence, build_store_evidence
from kind_evidence_store import digest, freeze_context, read_frozen_evidence
import recommendation_feature_load as feature
import store_profile_load as profile
from test_kind_evidence import MENU, REMOVAL, observation


class LegacyPublicationGateTest(unittest.TestCase):
    def state(self, events=(), removals=(REMOVAL,)):
        return build_store_evidence(events, removals, [MENU], "place-1")

    def test_one_unverified_legacy_removal_is_held_not_published_as_absence(self):
        for status in ("PENDING", "APPLYING", "APPROVED"):
            state = self.state([observation(1, 0)], [dict(REMOVAL, status_at_capture=status)])
            self.assertEqual(state.publication_status, "NEEDS_REVIEW")
            self.assertEqual({r["dimension"] for r in state.publication_blockers}, {"name", "kind"})
            with self.assertRaisesRegex(ValueError, "NEEDS_REVIEW"):
                state.require_publishable()
        # Same single valid absence without legacy ambiguity still uses score=2.
        state = self.state([observation(1, 0)], [])
        self.assertEqual(state.publication_status, "READY")
        self.assertTrue(state.allows_kind("식빵", "score"))

    def test_two_independent_complete_negatives_resolve_legacy_for_publish(self):
        state = self.state([observation(1, 0), observation(2, 6)])
        self.assertEqual(state.publication_status, "READY")
        state.require_publishable()
        self.assertFalse(state.allows_kind("식빵", "score"))
        for second in (observation(2, 1), observation(2, 6, complete=False)):
            self.assertEqual(self.state([observation(1, 0), second]).publication_status, "NEEDS_REVIEW")

    def test_current_positive_resolves_hold_but_other_name_keeps_name_blocker(self):
        self.assertEqual(self.state([observation(1, 0, ["우유식빵"], complete=False)]).publication_status,
                         "READY")
        state = self.state([observation(1, 0, ["밤식빵"], complete=False)])
        self.assertTrue(state.allows_kind("식빵"))
        self.assertEqual(state.publication_status, "NEEDS_REVIEW")
        self.assertEqual([r["dimension"] for r in state.publication_blockers], ["name"])

    def test_unknown_time_and_unmapped_legacy_cannot_silently_pass(self):
        for removal in (dict(REMOVAL, evidence_at=None), dict(REMOVAL, menu_name=None)):
            state = self.state([observation(1, 0, ["우유식빵"], complete=False)], [removal])
            self.assertEqual(state.publication_status, "NEEDS_REVIEW")
            restored = StoreEvidence.from_dict(state.as_dict())
            self.assertEqual(restored.publication_blockers, state.publication_blockers)
        self.assertEqual(state.publication_blockers[0]["reason"], "UNMAPPED_LEGACY")

    def test_unknown_time_cannot_be_cleared_by_replaying_two_negatives(self):
        state = self.state([observation(1, 0), observation(2, 6)], [dict(REMOVAL, evidence_at=None)])
        self.assertEqual(state.states["식빵"], "UNKNOWN")
        self.assertEqual(state.publication_status, "NEEDS_REVIEW")
        self.assertTrue(all(row["missing_count"] == 0 for row in state.publication_blockers))

    def test_frozen_metadata_cannot_override_actual_hold_or_load_old_format(self):
        context, fp = freeze_context(self.state(), {}, [MENU], 1, {})
        self.assertEqual(read_frozen_evidence(context, fp).publication_status, "NEEDS_REVIEW")
        forged = copy.deepcopy(context)
        forged.update(publication_status="READY", publication_blockers=[])
        with self.assertRaisesRegex(ValueError, "publication status differs"):
            read_frozen_evidence(forged, digest(forged))
        old = self.state().as_dict()
        old.pop("fingerprint")
        old["format_version"] = 1
        old["fingerprint"] = kind_evidence._digest(old)
        with self.assertRaisesRegex(ValueError, "unsupported evidence format"):
            StoreEvidence.from_dict(old)

    def test_profile_loader_rechecks_evidence_before_any_target_connection(self):
        context, fp = freeze_context(self.state(), {}, [MENU], 1, {})
        row = {"store_id": "1", "kind_evidence": json.dumps(context),
               "kind_evidence_fingerprint": fp, "current_external_id": "place-1"}
        with patch.object(profile, "local_csv", return_value=[row]), patch.object(profile, "connect") as connect:
            with self.assertRaisesRegex(ValueError, "NEEDS_REVIEW"):
                profile.read_draft(profile.BATCH_VERSION, {"1": fp})
            connect.assert_not_called()

    def test_blocked_feature_is_not_without_labels_and_mixed_scope_cannot_publish(self):
        context, fp = freeze_context(self.state(), {}, [MENU], 1, {})
        local_rows = [[{"store_id": "1", "kind_evidence": json.dumps(context), "kind_evidence_fingerprint": fp}],
                      [{"store_id": "1", "snapshot_id": "1", "signals": "{}"}],
                      [{"store_id": "1", "external_id": "place-1"}]]
        with patch.object(feature, "load_label_table", return_value=({}, [])), \
                patch.object(feature, "local_csv", side_effect=local_rows), \
                patch.object(feature, "prod_csv", return_value=[dict(MENU, store_id="1")]), \
                patch.object(feature, "label_inputs", wraps=feature.label_inputs) as calculate:
            result = feature.build("unused", [1])
            calculate.assert_called_once()
        self.assertEqual(result.rows, [])
        self.assertEqual(result.outcomes["1"]["status"], "NEEDS_REVIEW")
        result.outcomes["2"] = {"status": "WITH_LABELS", "profile_fingerprint": "a" * 64}
        with patch.object(feature, "read_draft", return_value=result), \
                patch.object(feature, "connect") as connect, \
                patch.object(sys, "argv", ["feature", "--target", "lab", "--apply-invalidations", "--dry-run"]):
            with self.assertRaisesRegex(ValueError, "selected publish blocked"):
                feature.main()
            connect.assert_not_called()

    def test_needs_review_roundtrips_from_draft_without_becoming_deletion(self):
        rows = [{"store_id": "1", "build_status": "NEEDS_REVIEW", "profile_evidence_fingerprint": "a" * 64}]
        with patch.object(feature, "local_csv", return_value=rows):
            result = feature.read_draft([1])
        self.assertEqual(result.rows, [])
        self.assertEqual(result.outcomes["1"]["status"], "NEEDS_REVIEW")
        with self.assertRaisesRegex(ValueError, "NEEDS_REVIEW"):
            feature.require_publishable_outcomes(result)

    def test_held_shadow_vector_is_inspectable_but_never_enters_publish_rows(self):
        signal = {"reviewMenus": [{"label": "도넛", "count": 50}]}
        context, fp = freeze_context(self.state(), signal, [MENU], 1, {})
        local_rows = [[{"store_id": "1", "kind_evidence": json.dumps(context), "kind_evidence_fingerprint": fp}],
                      [{"store_id": "1", "snapshot_id": "1", "signals": json.dumps(signal)}],
                      [{"store_id": "1", "external_id": "place-1"}]]
        table = {"도넛": {dim: 0.5 for dim in feature.DIMS}}
        with patch.object(feature, "load_label_table", return_value=(table, list(table))), \
                patch.object(feature, "local_csv", side_effect=local_rows), \
                patch.object(feature, "prod_csv", return_value=[dict(MENU, store_id="1")]):
            shadow = feature.build("unused", [1])
        self.assertEqual(len(shadow.rows), 1)
        self.assertEqual(shadow.outcomes["1"]["status"], "NEEDS_REVIEW")
        draft = dict(zip(["store_id"] + feature.COLS, shadow.rows[0]), build_status="NEEDS_REVIEW",
                     profile_evidence_fingerprint=fp)
        with patch.object(feature, "local_csv", return_value=[draft]):
            publish_input = feature.read_draft([1])
        self.assertEqual(publish_input.rows, [])
        with self.assertRaisesRegex(ValueError, "NEEDS_REVIEW"):
            feature.publish_profile_and_features(None, None, [], shadow, [])


if __name__ == "__main__":
    unittest.main()
