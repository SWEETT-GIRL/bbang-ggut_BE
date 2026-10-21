"""Offline frozen-artifact comparison tests. No production inputs or connections."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kind_evidence_impact import ImpactInputError, compare_artifacts, load_artifact, main


def artifact(name="baseline"):
    return {
        "schema_version": 1, "artifact_id": name,
        "scope": {"cohort_id": "fixed", "store_ids": [1, 2, 3],
                  "axis_dimensions": ["taste", "texture", "filling", "vibe"],
                  "label_dimensions": ["sweet", "crispy"]},
        "stores": [{"store_id": sid,
                    "profile": {"kinds": ["식빵", "도넛"], "signature_kinds": ["식빵"],
                                "axes": {"taste": 0.2, "texture": -0.1, "filling": 0, "vibe": 0.5},
                                "type": "SCLN", "type_label": "기존 타입", "bakery_score": 0.6},
                    "labels": {"sweet": 0.3, "crispy": 0.4},
                    "serving_outcome": "READY", "publish_selected": False} for sid in (1, 2, 3)],
    }


def recommendations(ids=(1, 2, 3), *, content=True, request_id="r1"):
    row = {"request_id": request_id, "input_fingerprint": "fixed-user-query-candidates",
           "ranked_store_ids": list(ids)}
    if content:
        row["badges"] = {str(sid): ["식빵 취향"] for sid in ids}
        row["reasons"] = {str(sid): "식빵 취향에 맞아요." for sid in ids}
    return {"request_ids": [request_id], "top_k": 2, "requests": [row]}


class KindEvidenceImpactTest(unittest.TestCase):
    def test_equal_profiles_do_not_fabricate_missing_proposals_or_recommendation_measurement(self):
        result = compare_artifacts(artifact(), artifact("candidate"))
        self.assertEqual(result["proposed_all_stores"]["changed_store_count"], 0)
        self.assertEqual(result["publication"]["status"], "NOT_EXECUTED")
        self.assertEqual(result["recommendations"]["status"], "NOT_MEASURED")
        self.assertNotIn("request_count", result["recommendations"])
        self.assertEqual(result["proposals"]["candidate"]["status"], "NOT_MEASURED")
        self.assertNotIn("proposal_count", result["proposals"]["candidate"])

    def test_proposal_rows_unique_stores_and_changed_store_kind_pairs_are_different(self):
        before, after = artifact(), artifact("candidate")
        after["proposals"] = [{"proposal_id": pid, "store_id": 1, "kind": "식빵"} for pid in range(1, 4)]
        after["stores"][0]["profile"]["kinds"] = ["도넛"]
        after["stores"][0]["profile"]["signature_kinds"] = []
        result = compare_artifacts(before, after)
        self.assertEqual(result["proposals"]["candidate"]["proposal_count"], 3)
        self.assertEqual(result["proposals"]["candidate"]["unique_store_count"], 1)
        self.assertEqual(result["proposals"]["candidate"]["unique_store_kind_pairs"], 1)
        self.assertEqual(result["proposed_all_stores"]["unique_changed_store_kind_pairs"], 1)
        self.assertEqual(result["proposed_all_stores"]["field_changed_store_counts"]["kinds"], 1)
        self.assertEqual(result["proposed_all_stores"]["field_changed_store_counts"]["signature_kinds"], 1)

    def test_blocked_and_unselected_proposals_do_not_become_planned_publish_effects(self):
        before, after = artifact(), artifact("candidate")
        for row in after["stores"]:
            row["profile"]["kinds"] = []
        after["stores"][0]["serving_outcome"] = "NEEDS_REVIEW"
        after["stores"][1]["publish_selected"] = True
        result = compare_artifacts(before, after)
        self.assertEqual(result["publication"]["blocked_store_ids"], [1])
        self.assertEqual(result["publication"]["selected_store_ids"], [2])
        self.assertEqual(result["publication"]["unselected_store_ids"], [3])
        self.assertEqual(result["proposed_all_stores"]["changed_store_count"], 3)
        self.assertEqual(result["planned_publish_only"]["changed_store_count"], 1)
        self.assertEqual(result["planned_publish_only"]["store_count"], 1)
        self.assertEqual(result["stores"][0]["planned_publish_delta"], {})
        self.assertTrue(result["stores"][0]["proposed_delta"])
        self.assertEqual(result["stores"][2]["planned_publish_delta"], {})

    def test_selected_without_labels_is_explicit_invalidation_and_null_score_is_not_zero(self):
        before, after = artifact(), artifact("candidate")
        row = after["stores"][0]
        row.update(labels=None, serving_outcome="WITHOUT_LABELS", publish_selected=True)
        row["profile"]["bakery_score"] = None
        result = compare_artifacts(before, after)
        self.assertEqual(result["publication"]["selected_invalidation_store_ids"], [1])
        score_gate = result["planned_publish_only"]["bakery_score_gate"]
        self.assertEqual(score_gate["not_comparable_store_ids"], [1])
        self.assertEqual(score_gate["exited_store_ids"], [])
        self.assertIsNone(result["stores"][0]["proposed_delta"]["labels"]["after"])

    def test_axis_type_labels_score_deltas_and_point_five_boundary_are_separate(self):
        before, after = artifact(), artifact("candidate")
        before["stores"][0]["profile"]["bakery_score"] = 0.49
        row = after["stores"][0]
        row["profile"].update(type="PCLN", type_label="새 타입", bakery_score=0.5)
        row["profile"]["axes"]["taste"] = -0.2
        row["labels"]["sweet"] = 0.8
        after["stores"][1]["profile"]["bakery_score"] = 0.499
        result = compare_artifacts(before, after)
        gate = result["proposed_all_stores"]["bakery_score_gate"]
        self.assertEqual(gate["entered_store_ids"], [1])
        self.assertEqual(gate["exited_store_ids"], [2])
        delta = result["stores"][0]["proposed_delta"]
        self.assertAlmostEqual(delta["axes"]["dimension_changes"]["taste"]["numeric_delta"], -0.4)
        self.assertAlmostEqual(delta["labels"]["dimension_changes"]["sweet"]["numeric_delta"], 0.5)
        self.assertAlmostEqual(delta["bakery_score"]["numeric_delta"], 0.01)
        self.assertEqual(delta["type"]["after"], "PCLN")
        self.assertIn("type_label", delta)

    def test_kind_reordering_does_not_count_as_membership_loss(self):
        before, after = artifact(), artifact("candidate")
        after["stores"][0]["profile"]["kinds"].reverse()
        result = compare_artifacts(before, after)
        kinds = result["stores"][0]["proposed_delta"]["kinds"]
        self.assertTrue(kinds["reordered"])
        self.assertEqual(kinds["added"], [])
        self.assertEqual(kinds["removed"], [])
        self.assertEqual(result["proposed_all_stores"]["unique_changed_store_kind_pairs"], 0)

    def test_top_k_replacement_rank_badges_and_sentences_use_paired_frozen_outputs(self):
        before, after = artifact(), artifact("candidate")
        before["recommendations"] = recommendations()
        after["recommendations"] = recommendations((3, 1, 2))
        after["recommendations"]["requests"][0]["badges"]["1"] = ["도넛 취향"]
        after["recommendations"]["requests"][0]["reasons"]["1"] = "도넛 취향에 맞아요."
        reco = compare_artifacts(before, after)["recommendations"]
        self.assertEqual(reco["status"], "MEASURED")
        self.assertEqual(reco["baseline_slot_replacement_rate"], 0.5)
        self.assertEqual(reco["requests"][0]["top_k"]["entered_store_ids"], [3])
        self.assertEqual(reco["requests"][0]["top_k"]["exited_store_ids"], [2])
        self.assertEqual(reco["requests"][0]["top_k"]["rank_changes"], [{"store_id": 1, "before": 1, "after": 2}])
        for field in ("badges", "reasons"):
            self.assertEqual(reco[field]["changed_request_store_pair_count"], 1)
            self.assertEqual(reco[field]["unique_changed_store_count"], 1)

    def test_partial_content_is_reported_with_unmeasured_request_ids(self):
        before, after = artifact(), artifact("candidate")
        for document in (before, after):
            document["recommendations"] = recommendations()
            second = recommendations(request_id="r2", content=False)["requests"][0]
            document["recommendations"]["request_ids"].append("r2")
            document["recommendations"]["requests"].append(second)
        result = compare_artifacts(before, after)["recommendations"]
        for field in ("badges", "reasons"):
            self.assertEqual(result[field]["status"], "PARTIALLY_MEASURED")
            self.assertEqual(result[field]["unmeasured_request_ids"], ["r2"])
        del after["recommendations"]["requests"][0]["reasons"]
        result = compare_artifacts(before, after)["recommendations"]
        self.assertEqual(result["reasons"]["status"], "NOT_MEASURED")
        self.assertNotIn("changed_common_content_request_count", result["reasons"])

    def test_repeated_store_content_changes_are_request_pairs_not_unique_stores(self):
        before, after = artifact(), artifact("candidate")
        for document in (before, after):
            document["recommendations"] = recommendations()
            document["recommendations"]["request_ids"].append("r2")
            document["recommendations"]["requests"].append(recommendations(request_id="r2")["requests"][0])
        for request in after["recommendations"]["requests"]:
            request["reasons"]["1"] = "바뀐 문장"
        result = compare_artifacts(before, after)["recommendations"]["reasons"]
        self.assertEqual(result["changed_request_store_pair_count"], 2)
        self.assertEqual(result["unique_changed_store_count"], 1)
        self.assertEqual(result["changed_common_content_request_count"], 2)

    def test_complete_top_k_replacement_is_not_claimed_as_zero_content_changes(self):
        before, after = artifact(), artifact("candidate")
        before["recommendations"] = recommendations((1,))
        after["recommendations"] = recommendations((2,))
        result = compare_artifacts(before, after)["recommendations"]
        self.assertEqual(result["baseline_slot_replacement_rate"], 1.0)
        for field in ("badges", "reasons"):
            self.assertEqual(result[field]["status"], "NOT_MEASURED")
            self.assertNotIn("changed_request_store_pair_count", result[field])
            self.assertEqual(result["requests"][0][field]["entered_store_ids"], [2])

    def test_unpaired_recommendations_and_empty_baseline_have_no_invented_zero_rate(self):
        before, after = artifact(), artifact("candidate")
        before["recommendations"] = recommendations()
        self.assertEqual(compare_artifacts(before, after)["recommendations"]["status"], "NOT_MEASURED")
        before["recommendations"] = recommendations(())
        after["recommendations"] = recommendations((1,))
        result = compare_artifacts(before, after)["recommendations"]
        self.assertIsNone(result["baseline_slot_replacement_rate"])
        self.assertEqual(result["requests"][0]["top_k"]["entered_store_ids"], [1])

    def test_missing_extra_duplicate_and_different_scope_store_ids_fail_closed(self):
        mutations = [
            lambda a: a["stores"].pop(),
            lambda a: a["stores"].append(copy.deepcopy(a["stores"][0])),
            lambda a: a["stores"][0].update(store_id=99),
            lambda a: a["scope"]["store_ids"].append(1),
            lambda a: a["scope"].update(cohort_id="different"),
            lambda a: (a["scope"]["store_ids"].remove(3), a["stores"].pop()),
            lambda a: a["stores"][0]["profile"].pop("signature_kinds"),
            lambda a: a["stores"][0]["profile"]["axes"].pop("taste"),
            lambda a: a["stores"][0].update(publish_selected=1),
            lambda a: a["scope"].update(store_ids=[]),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                after = artifact("candidate")
                mutate(after)
                with self.assertRaises(ImpactInputError):
                    compare_artifacts(artifact(), after)

    def test_invalid_status_labels_and_numbers_fail_closed(self):
        mutations = [
            lambda row: row.update(serving_outcome="NEEDS_REVIEW", publish_selected=True),
            lambda row: row.update(serving_outcome="WITHOUT_LABELS"),
            lambda row: row.update(labels=None),
            lambda row: row.update(serving_outcome="WITH_LABELS"),
            lambda row: row["profile"].update(bakery_score=float("nan")),
            lambda row: row["profile"].update(bakery_score=True),
            lambda row: row["profile"].update(bakery_score=1.1),
            lambda row: row["profile"].update(kinds=["식빵", "식빵"]),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                after = artifact("candidate")
                mutate(after["stores"][0])
                with self.assertRaises(ImpactInputError):
                    compare_artifacts(artifact(), after)

    def test_recommendation_scope_context_and_content_coverage_fail_closed(self):
        mutations = [
            lambda a: a["recommendations"]["requests"][0].update(ranked_store_ids=[1, 1]),
            lambda a: a["recommendations"]["requests"][0].update(ranked_store_ids=[99]),
            lambda a: a["recommendations"]["requests"][0].update(input_fingerprint="changed-user"),
            lambda a: a["recommendations"].update(top_k=3),
            lambda a: a["recommendations"]["requests"].append(copy.deepcopy(a["recommendations"]["requests"][0])),
            lambda a: a["recommendations"]["requests"][0]["badges"].pop("1"),
            lambda a: a["recommendations"]["request_ids"].append("missing"),
            lambda a: a["recommendations"].update(request_ids=[]),
        ]
        before = artifact()
        before["recommendations"] = recommendations()
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                after = copy.deepcopy(before)
                mutate(after)
                with self.assertRaises(ImpactInputError):
                    compare_artifacts(before, after)

    def test_duplicate_proposal_ids_and_out_of_scope_proposals_fail(self):
        before = artifact()
        for rows in ([{"proposal_id": 1, "store_id": 1, "kind": "식빵"},
                      {"proposal_id": "1", "store_id": 1, "kind": "식빵"}],
                     [{"proposal_id": 2, "store_id": 99, "kind": "식빵"}]):
            after = artifact("candidate")
            after["proposals"] = rows
            with self.assertRaises(ImpactInputError):
                compare_artifacts(before, after)

    def test_comparison_is_pure_and_input_row_order_does_not_change_effects(self):
        before, after = artifact(), artifact("candidate")
        originals = copy.deepcopy((before, after))
        expected = compare_artifacts(before, after)
        self.assertEqual((before, after), originals)
        after["stores"].reverse()
        after["scope"]["store_ids"].reverse()
        self.assertEqual(compare_artifacts(before, after), expected)

    def test_default_cli_only_reads_two_artifacts_and_writes_stdout(self):
        files = {"before": json.dumps(artifact()).encode(), "after": json.dumps(artifact("candidate")).encode()}
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(Path, "read_bytes", autospec=True, side_effect=lambda p: files[str(p)]) as read, \
                patch.object(Path, "open", side_effect=AssertionError("unexpected file write")), \
                patch("socket.socket", side_effect=AssertionError("unexpected network")), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            code = main(["--baseline", "before", "--candidate", "after"])
        self.assertEqual(code, 0)
        self.assertEqual(read.call_count, 2)
        self.assertEqual(stderr.getvalue(), "")
        report = json.loads(stdout.getvalue())
        self.assertEqual(report["recommendations"]["status"], "NOT_MEASURED")
        self.assertEqual(len(report["input_sha256"]["baseline"]), 64)

    def test_cli_duplicate_json_keys_are_rejected_without_partial_stdout(self):
        with patch.object(Path, "read_bytes", return_value=b'{"schema_version":1,"schema_version":1}'), \
                redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(main(["--baseline", "before", "--candidate", "after"]), 2)
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(json.loads(stderr.getvalue())["status"], "INVALID_INPUT")
        self.assertIn("duplicate JSON object key", stderr.getvalue())

    def test_cli_output_requires_explicit_new_path_and_never_overwrites_inputs(self):
        # Synthetic fixtures only; no repository files or production artifacts are touched.
        with tempfile.TemporaryDirectory(prefix="kind-impact-test-") as directory:
            before, after, output = [Path(directory) / name for name in ("before.json", "after.json", "report.json")]
            before.write_text(json.dumps(artifact()), encoding="utf-8")
            after.write_text(json.dumps(artifact("candidate")), encoding="utf-8")
            args = ["--baseline", str(before), "--candidate", str(after)]
            with redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()):
                self.assertEqual(main(args + ["--output", str(output)]), 0)
                self.assertEqual(output.read_text(encoding="utf-8"), stdout.getvalue())
            original = output.read_bytes()
            for target in (output, before, after):
                with redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()):
                    self.assertEqual(main(args + ["--output", str(target)]), 2)
                    self.assertEqual(stdout.getvalue(), "")
            self.assertEqual(output.read_bytes(), original)

    def test_nonfinite_json_and_unknown_fields_are_rejected(self):
        with patch.object(Path, "read_bytes", return_value=b'{"value":NaN}'):
            with self.assertRaises(ImpactInputError):
                load_artifact("unused")
        after = artifact("candidate")
        after["recommendation"] = {}  # A typo must not silently become NOT_MEASURED.
        with self.assertRaises(ImpactInputError):
            compare_artifacts(artifact(), after)


if __name__ == "__main__":
    unittest.main()
