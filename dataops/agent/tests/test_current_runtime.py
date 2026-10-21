"""Current parser/profile tool loop, using actual tool messages and no paid API."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from agent.response_change.ledger import digest
from agent.response_change.runtime import LEGACY_TEST, PARSER, PolicyError, Runner
from agent.tests.test_runtime import (CASE, FIXTURES, GENERATED, SOURCE, ScriptedModel,
                                      call, response, result_from, validation)

PROFILE = "src/main/resources/response-agent/menu-structure-profile-v1.json"
PROFILE_SOURCE = '{"entities": ["Menu", "PlaceMenuItem"]}\n'


class CurrentRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name) / "run"
        self.files = {PARSER: SOURCE, LEGACY_TEST: "immutable current tests", PROFILE: PROFILE_SOURCE}
        self.validator = Mock(side_effect=lambda *a, **k: validation())

    def runner(self, prior=None):
        return Runner(CASE, SOURCE, self.files[LEGACY_TEST], FIXTURES, self.folder,
                      self.validator, files=self.files, editable_paths=(PARSER, PROFILE),
                      required_refs=(PARSER, LEGACY_TEST, PROFILE), prior_report=prior)

    def profile_patch(self, new='"PlaceMenuItem", "NextMenu"'):
        return {"path": PROFILE, "source_digest": digest(PROFILE_SOURCE), "rationale": "새 메뉴 엔티티 지원",
                "edits": [{"old": '"PlaceMenuItem"', "new": new}],
                "regression_cases": [{**GENERATED, "external_id": "sample-store"}]}

    def evidence(self, runner):
        runner.by_name["get_case"].invoke({"case_id": CASE["case_id"]})
        for path in self.files:
            runner.by_name["read_file"].invoke({"path": path})
        runner.delivered_refs = runner.read_refs.copy()

    def test_profile_edit_uses_current_workspace_and_final_independent_validation(self):
        runner = self.runner()
        model = ScriptedModel([
            response([call("get_case", {"case_id": CASE["case_id"]})]),
            response([call("read_file", {"path": path}) for path in self.files]),
            response([call("propose_patch", self.profile_patch())]),
            lambda messages: response([call("run_validation", {
                "patch_id": result_from(messages, "propose_patch")["patch_id"]})]),
            response([call("finish_analysis", {"disposition": "needs_patch", "reason": "독립 검증 확인",
                "evidence_refs": [CASE["case_id"], *self.files]})]),
        ])
        report = runner.run(model)
        self.assertEqual("AWAITING_REVIEW", report["status"])
        self.assertEqual(2, self.validator.call_count)
        candidate = self.validator.call_args.args[0]
        self.assertEqual({PARSER, PROFILE}, set(candidate))
        self.assertEqual(SOURCE, candidate[PARSER])
        self.assertIn("NextMenu", candidate[PROFILE])
        self.assertNotIn(LEGACY_TEST, candidate)
        self.assertEqual(FIXTURES, self.validator.call_args.args[1])
        self.assertTrue((self.folder / "patch-1/candidate-files.json").exists())
        self.assertIn("current deployment", model.contexts[0][0].content)

    def test_required_profile_must_be_delivered_before_patching(self):
        runner = self.runner()
        self.evidence(runner)
        runner.delivered_refs.remove(PROFILE)
        with self.assertRaisesRegex(ValueError, "prior turns"):
            runner.by_name["propose_patch"].invoke(self.profile_patch())
        self.assertIsNone(runner.patch)

    def test_protected_test_and_credential_path_not_in_edit_schema(self):
        runner = self.runner()
        schema = runner.by_name["propose_patch"].args_schema.model_json_schema()
        self.assertEqual({PARSER, PROFILE}, set(schema["properties"]["path"]["enum"]))
        self.evidence(runner)
        for path in (LEGACY_TEST, ".env", "../../elsewhere.json"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                runner.by_name["propose_patch"].invoke({**self.profile_patch(), "path": path})
        self.assertEqual(self.files[LEGACY_TEST], runner.files[LEGACY_TEST])

    def test_profile_and_parser_edits_accumulate_and_old_validation_is_invalidated(self):
        runner = self.runner()
        self.evidence(runner)
        first = runner.by_name["propose_patch"].invoke(self.profile_patch())
        runner.delivered_refs.add("patch:" + first["patch_id"])
        runner.by_name["run_validation"].invoke({"patch_id": first["patch_id"]})
        second = runner.by_name["propose_patch"].invoke({
            **self.profile_patch(), "path": PARSER, "source_digest": digest(SOURCE),
            "edits": [{"old": '"old"', "new": '"new"'}]})
        self.assertNotEqual(first["patch_id"], second["patch_id"])
        self.assertIsNone(runner.validation)
        self.assertIn("NextMenu", runner.files[PROFILE])
        self.assertIn('"new"', runner.source)
        with self.assertRaisesRegex(ValueError, "current patch_id"):
            runner.by_name["run_validation"].invoke({"patch_id": first["patch_id"]})

    def test_resume_restores_profile_and_rejects_changed_candidate_or_protected_context(self):
        runner = self.runner()
        self.evidence(runner)
        runner.by_name["propose_patch"].invoke(self.profile_patch())
        saved = copy.deepcopy(runner.report)
        resumed = self.runner(saved)
        self.assertIn("NextMenu", resumed.files[PROFILE])
        self.assertEqual(runner.patch, resumed.patch)
        artifact = self.folder / "patch-1/candidate-files.json"
        content = json.loads(artifact.read_text())
        content[PROFILE] = PROFILE_SOURCE
        artifact.write_text(json.dumps(content))
        with self.assertRaisesRegex(PolicyError, "candidate workspace changed"):
            self.runner(saved)
        self.files[LEGACY_TEST] = "weakened tests"
        with self.assertRaisesRegex(PolicyError, "binding changed"):
            self.runner(saved)


if __name__ == "__main__":
    unittest.main()
