"""Revision freezes new counterexamples without rewriting the original evaluation."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_menu_agent_revision import CONTRACT_VERSION, PRIMITIVE_CONTRACT, prepare_revision


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class RevisionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.source, self.challenge, self.output = root / "original", root / "challenge", root / "v2"
        self.source.mkdir()
        self.challenge.mkdir()
        self.case = {"schema_version": 1, "id": "real-case", "case_id": "real-case",
                     "parser_base_commit": "e" * 40, "contracts": ["Preserve legacy Menu"],
                     "before": {"raw": {"Menu:old": {"price": "5300"}}, "source_payload_sha256": "a" * 64},
                     "after": {"raw": {"PlaceMenuItem:new": {"price": {"displayText": "5,300원"}}},
                               "source_payload_sha256": "b" * 64}}
        self.fixtures = {"schema_version": 1, "case_id": "real-case", "oracle_origin": "Independent oracle.",
                         "cases": [{"id": "original-" + str(i), "raw": {}, "expected": {"menus": []}}
                                   for i in range(7)]}
        write(self.source / "case.json", self.case)
        write(self.source / "fixtures.json", self.fixtures)
        write(self.source / "provenance.private.json", {"store_id": "PRIVATE-SOURCE-IDENTITY"})
        write(self.source / "manifest.json", {
            "case_id": "real-case", "base_commit": "e" * 40, "fixture_count": 7,
            "real_source_pair": True, "source_payloads_exported": False,
            "files": {name: file_hash(self.source / name) for name in
                      ("case.json", "fixtures.json", "provenance.private.json")}})
        self.challenges = []
        for case_id, price in (("post-review-variable-string", "시가"),
                               ("post-review-numeric-scalar", 5300),
                               ("post-review-range-string", "5000~7000")):
            self.challenges.append({"id": case_id, "kind": "post_review_challenge", "visibility": "hidden",
                "raw": {"PlaceMenuItem:new": {"__typename": "PlaceMenuItem", "name": "new", "price": price},
                        "Menu:legacy": {"__typename": "Menu", "name": "legacy", "price": price}},
                "expected": {"menus": [
                    {"name": "new", "price": None, "description": None, "imageUrls": [], "groupName": None},
                    {"name": "legacy", "price": str(price), "description": None, "imageUrls": [], "groupName": None}]}})
        self.challenge_manifest = {
            "evaluation_stage": "POST_HOC_REVIEW_CHALLENGE", "counts_as_original_benchmark": False,
            "original_fixture_file_sha256": file_hash(self.source / "fixtures.json"),
            "candidate_path": "DO-NOT-OPEN-CANDIDATE.java", "candidate_digest": "c" * 64,
            "cases": self.challenges}
        write(self.challenge / "challenge-manifest.json", self.challenge_manifest)

    def run_revision(self):
        return prepare_revision(self.source, self.challenge, self.output)

    def test_revision_preserves_original_bytes_and_raw_pair_and_adds_exact_challenges(self):
        before = {path: path.read_bytes() for path in (*self.source.iterdir(), *self.challenge.iterdir())}
        result = self.run_revision()
        self.assertEqual("FROZEN", result["status"])
        self.assertEqual(0, result["new_model_calls"])
        for path, data in before.items():
            self.assertEqual(data, path.read_bytes())
        revised_case = json.loads((self.output / "case.json").read_text())
        revised_fixtures = json.loads((self.output / "fixtures.json").read_text())
        self.assertEqual("real-case-v2", revised_case["case_id"])
        self.assertEqual(CONTRACT_VERSION, revised_case["contract_version"])
        self.assertEqual(2, revised_case["oracle_version"])
        self.assertEqual(self.case["before"], revised_case["before"])
        self.assertEqual(self.case["after"], revised_case["after"])
        self.assertEqual(self.case["parser_base_commit"], revised_case["parser_base_commit"])
        self.assertEqual(self.fixtures["cases"], revised_fixtures["cases"][:7])
        self.assertEqual(self.challenges, revised_fixtures["cases"][7:])
        self.assertIs(type(revised_fixtures["cases"][8]["raw"]["PlaceMenuItem:new"]["price"]), int)
        self.assertIn(PRIMITIVE_CONTRACT, revised_case["contracts"])
        self.assertFalse(revised_case["revision"]["counts_as_original_benchmark"])
        public = (self.output / "case.json").read_text()
        self.assertNotIn("PRIVATE-SOURCE-IDENTITY", public)
        self.assertNotIn("DO-NOT-OPEN-CANDIDATE", public)
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(10, manifest["fixture_count"])
        for name, expected in manifest["files"].items():
            self.assertEqual(expected, file_hash(self.output / name))
        self.assertEqual(0o700, self.output.stat().st_mode & 0o777)
        self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in self.output.iterdir()))

    def test_existing_output_cannot_be_replaced_on_retry(self):
        self.run_revision()
        before = {path.name: path.read_bytes() for path in self.output.iterdir()}
        with self.assertRaises(FileExistsError):
            self.run_revision()
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.output.iterdir()})

    def test_tampered_original_oracle_is_rejected_before_output_creation(self):
        (self.source / "fixtures.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            self.run_revision()
        self.assertFalse(self.output.exists())

    def test_challenge_from_another_original_oracle_is_rejected(self):
        self.challenge_manifest["original_fixture_file_sha256"] = "wrong"
        write(self.challenge / "challenge-manifest.json", self.challenge_manifest)
        with self.assertRaisesRegex(ValueError, "unchanged original oracle"):
            self.run_revision()
        self.assertFalse(self.output.exists())

    def test_weakening_new_price_expectation_or_changing_legacy_value_is_rejected(self):
        for menu_index, replacement in ((0, "5300"), (1, None)):
            with self.subTest(menu_index=menu_index):
                changed = copy.deepcopy(self.challenge_manifest)
                changed["cases"][1]["expected"]["menus"][menu_index]["price"] = replacement
                write(self.challenge / "challenge-manifest.json", changed)
                with self.assertRaisesRegex(ValueError, "preserving legacy"):
                    self.run_revision()
                self.assertFalse(self.output.exists())

    def test_numeric_price_cannot_be_silently_changed_to_text(self):
        self.challenge_manifest["cases"][1]["raw"]["PlaceMenuItem:new"]["price"] = "5300"
        write(self.challenge / "challenge-manifest.json", self.challenge_manifest)
        with self.assertRaisesRegex(ValueError, "value or type"):
            self.run_revision()

    def test_revision_cannot_be_written_inside_old_evidence(self):
        with self.assertRaisesRegex(ValueError, "separate"):
            prepare_revision(self.source, self.challenge, self.source / "new-revision")
        self.assertFalse((self.source / "new-revision").exists())


if __name__ == "__main__":
    unittest.main()
