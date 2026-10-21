"""Scoped draft validation; no database, environment or HTTP access."""
import json
import datetime as dt
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import recommendation_feature_load as feature
import store_profile_load as profile
from kind_evidence import build_store_evidence
from kind_evidence_store import freeze_context
from test_kind_evidence import observation


class DraftValidationTest(unittest.TestCase):
    def test_scope_requires_positive_ids_and_deduplicates(self):
        self.assertEqual(feature.scope_clause([3, 1, 3]), " AND store_id IN (1,3)")
        for ids in ([], [0], [-1], ["1);DELETE"]):
            with self.assertRaises(ValueError):
                feature.scope_clause(ids)

    def test_selected_feature_scope_cannot_silently_shrink(self):
        row = {"store_id": "1", "build_status": "WITHOUT_LABELS",
               "profile_evidence_fingerprint": "a" * 64}
        with patch.object(feature, "local_csv", return_value=[row]):
            with self.assertRaisesRegex(ValueError, "scope is incomplete"):
                feature.read_draft([1, 2])
            self.assertEqual(set(feature.read_draft([1]).outcomes), {"1"})

    def test_mapping_change_after_label_build_refuses_profile_publish(self):
        state = build_store_evidence([], [], [], external_id="place-1")
        frozen, fp = freeze_context(state, {}, [], 1, {})
        row = {key: "" for key in profile.COLS + list(profile.OPTIONAL_COLS)}
        row.update(store_id="1", kind_evidence=json.dumps(frozen), kind_evidence_fingerprint=fp,
                   current_external_id="place-2")
        with patch.object(profile, "local_csv", return_value=[row]):
            with self.assertRaisesRegex(ValueError, "mapping changed"):
                profile.read_draft(profile.BATCH_VERSION, {"1": fp})
            row["current_external_id"] = "place-1"
            self.assertEqual(len(profile.read_draft(profile.BATCH_VERSION, {"1": fp})), 1)

    def test_feature_build_uses_frozen_observed_at_and_current_identity(self):
        state = build_store_evidence([observation(1, 0, ["밤식빵"])], [], [], "place-1")
        signal = {"reviewMenus": [{"label": "식빵", "count": 50}]}
        frozen, fp = freeze_context(state, signal, [], "10", {})
        identity = {"store_id": "1", "external_id": "place-1"}

        def query(sql):
            if "FROM store_profile_draft" in sql:
                return [{"store_id": "1", "kind_evidence": json.dumps(frozen),
                         "kind_evidence_fingerprint": fp}]
            if "FROM store_signal" in sql:
                return [{"store_id": "1", "snapshot_id": "10", "signals": json.dumps(signal)}]
            if "FROM store_sync_state" in sql:
                self.assertNotIn("last_success_at", sql)
                return [identity]
            self.fail(f"unexpected query {sql}")

        table = {"식빵": {dim: 0.5 for dim in feature.DIMS}}
        with patch.object(feature, "local_csv", side_effect=query), \
                patch.object(feature, "prod_csv", return_value=[]), \
                patch.object(feature, "load_label_table", return_value=(table, list(table))):
            result = feature.build("unused", [1])
            values = dict(zip(["store_id"] + feature.COLS, result.rows[0]))
            self.assertEqual(values["observed_at"], dt.datetime(2026, 9, 25))
            identity["external_id"] = "place-2"
            with self.assertRaisesRegex(ValueError, "mapping changed"):
                feature.build("unused", [1])


if __name__ == "__main__":
    unittest.main()
