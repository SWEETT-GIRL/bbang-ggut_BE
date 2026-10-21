"""Offline agent acceptance tests: real LangGraph/tools, scripted model, no API or Docker."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from langchain_core.messages import AIMessage

from agent.response_change.ledger import Inbox, detect, digest
from agent.response_change.runtime import LEGACY_TEST, PARSER, PolicyError, Runner


SOURCE = 'class Parser { String price() { return "old"; } }\n'
CASE = {"case_id": "offline-historical-case", "before": {}, "after": {}}
FIXTURES = [
    {"id": "fixed-before", "visibility": "public", "raw": {}, "expected": {"menus": []}},
    {"id": "fixed-hidden", "visibility": "hidden", "raw": {"private": "HIDDEN RAW"},
     "expected": {"name": "HIDDEN EXPECTED"}},
]
GENERATED = {"id": "generated-format", "raw": {}, "expected": {"menus": []}}


def call(name, args):
    return {"name": name, "args": args, "id": "call-" + name}


def response(calls, *, invalid=None, content=""):
    return AIMessage(content=content, tool_calls=calls, invalid_tool_calls=invalid or [],
                     usage_metadata={"input_tokens": 120, "output_tokens": 30, "total_tokens": 150})


def result_from(messages, name):
    return json.loads(next(message.content for message in reversed(messages)
                           if message.type == "tool" and message.tool_call_id == "call-" + name))


def patch_args(source=SOURCE, *, cases=None, old='"old"', new='"new"'):
    return {"path": PARSER, "source_digest": digest(source), "rationale": "Read the new price shape",
            "edits": [{"old": old, "new": new}],
            "regression_cases": copy.deepcopy([GENERATED] if cases is None else cases)}


def finish_args(disposition="needs_patch"):
    return {"disposition": disposition, "reason": "Reviewed fixed validation results",
            "evidence_refs": [CASE["case_id"], PARSER, LEGACY_TEST]}


class ScriptedModel:
    """Responses are driven by actual ToolMessages instead of Runner internals."""

    def __init__(self, responses, tokens=120):
        self.responses = iter(responses)
        self.tokens = tokens
        self.calls = 0
        self.count_calls = 0
        self.contexts = []

    def get_num_tokens_from_messages(self, messages, tools):
        self.count_calls += 1
        return self.tokens

    def bind_tools(self, tools, tool_choice):
        if tool_choice != "auto":
            raise AssertionError("model must choose the tool")
        return self

    def invoke(self, messages):
        self.calls += 1
        self.contexts.append(messages)
        answer = next(self.responses)
        if isinstance(answer, Exception):
            raise answer
        return answer(messages) if callable(answer) else answer


def repair_model():
    def propose(messages):
        parser_result = next(json.loads(message.content) for message in reversed(messages)
                             if message.type == "tool" and
                             json.loads(message.content).get("path") == PARSER)
        arguments = patch_args(parser_result["content"])
        arguments["source_digest"] = parser_result["source_digest"]
        return response([call("propose_patch", arguments)])

    def validate(messages):
        return response([call("run_validation", {"patch_id": result_from(messages, "propose_patch")["patch_id"]})])

    return ScriptedModel([
        response([call("get_case", {"case_id": CASE["case_id"]})]),
        response([call("search_code", {"query": "price"})]),
        response([call("read_file", {"path": PARSER}), call("read_file", {"path": LEGACY_TEST})]),
        propose, validate, response([call("finish_analysis", finish_args())]),
    ])


def validation(passed=True):
    return {"passed": passed, "status": "PASS" if passed else "ASSERTION_FAILED",
            "cases": [{"id": "fixed-before", "passed": True, "actual": {"menus": []}},
                      {"id": "fixed-hidden", "passed": passed, "actual": {"name": "HIDDEN RAW"},
                       "mismatches": {"name": {"expected": "HIDDEN EXPECTED"}}}],
            "proposed_cases": [{"id": "generated-format", "passed": True}],
            "fixed_regression": {"found": 11, "succeeded": 11, "failed": 0, "skipped": 0}}


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.validator = Mock(side_effect=lambda *a, **k: validation())
        self.runner = Runner(CASE, SOURCE, "legacy price tests", FIXTURES, self.root / "run", self.validator)

    def deliver_basics(self):
        for name, arguments in (("get_case", {"case_id": CASE["case_id"]}),
                                ("read_file", {"path": PARSER}), ("read_file", {"path": LEGACY_TEST})):
            self.runner.by_name[name].invoke(arguments)
        self.runner.delivered_refs = self.runner.read_refs.copy()

    def test_candidate_output_and_exceptions_cannot_exfiltrate_hidden_fixture_feedback(self):
        result = validation(False)
        result["error"] = "SECRET_HOLDOUT_INPUT"
        result["fixed_regression"]["failures"] = ["SECRET_HOLDOUT_INPUT"]
        result["cases"][0]["actual"] = {"name": "SECRET_HOLDOUT_INPUT"}
        result["proposed_cases"][0]["actual"] = {"name": "SECRET_HOLDOUT_INPUT"}
        feedback = json.dumps(self.runner.validation_feedback(result))
        self.assertNotIn("SECRET_HOLDOUT_INPUT", feedback)
        self.assertNotIn("HIDDEN RAW", feedback)
        self.assertNotIn("HIDDEN EXPECTED", feedback)
        self.assertIn("independent-holdout", feedback)



    def test_six_tool_graph_produces_review_and_mandatory_independent_rerun(self):
        model = repair_model()
        report = self.runner.run(model)
        self.assertEqual("AWAITING_REVIEW", report["status"])
        self.assertEqual("CLEAN", report["routing_quality"])
        self.assertEqual(["get_case", "search_code", "read_file", "read_file", "propose_patch",
                          "run_validation", "finish_analysis"], [item["name"] for item in report["tools"]])
        self.assertEqual([False, True], [item["final"] for item in report["validations"]])
        self.assertEqual(2, self.validator.call_count)
        for args, kwargs in self.validator.call_args_list:
            self.assertEqual(SOURCE.replace('"old"', '"new"'), args[0])
            self.assertEqual(FIXTURES, args[1])
            self.assertEqual([GENERATED], kwargs["proposed_fixtures"])
        self.assertEqual(6, model.calls)
        self.assertTrue((self.root / "run/review.md").exists())
        self.assertEqual(SOURCE, (self.root / "run/baseline.java").read_text())
        self.assertTrue((self.root / "run/patch-1/candidate.patch").exists())
        self.assertEqual(0, report["production_writes"])
        final_context = " ".join(str(message.content) for message in model.contexts[-1])
        self.assertNotIn("HIDDEN RAW", final_context)
        self.assertNotIn("HIDDEN EXPECTED", final_context)

    def test_final_rerun_failure_cannot_be_overridden_by_model_finish(self):
        self.validator.side_effect = [validation(True), validation(False)]
        report = self.runner.run(repair_model())
        self.assertEqual("HELD", report["status"])
        self.assertEqual("needs_patch", report["decision"]["disposition"])
        self.assertFalse(report["validations"][-1]["result"]["passed"])
        self.assertEqual(2, self.validator.call_count)

    def test_validation_feedback_never_exposes_candidate_output_or_failure_text(self):
        sentinel = "PRIVATE-CANDIDATE-CONTROLLED-HOLDOUT"
        result = validation(False)
        result.update(error=sentinel, compile_error=sentinel, runtime_error=sentinel)
        result["fixed_regression"]["failures"] = [{"test": sentinel, "error": sentinel}]
        result["cases"][0].update(actual={"name": sentinel}, error=sentinel,
                                  mismatches={"menus": {"actual": sentinel, "expected": sentinel}})
        result["proposed_cases"][0].update(actual={"name": sentinel}, error=sentinel,
                                          mismatches={"menus": {"actual": sentinel, "expected": sentinel}})
        feedback = self.runner.validation_feedback(result)
        self.assertNotIn(sentinel, json.dumps(feedback))
        self.assertNotIn("HIDDEN RAW", json.dumps(feedback))
        self.assertNotIn("HIDDEN EXPECTED", json.dumps(feedback))
        self.assertEqual(["menus"], feedback["cases"][0]["mismatch_fields"])
        self.assertEqual(["menus"], feedback["proposed_cases"][0]["mismatch_fields"])
        self.assertEqual({"found", "succeeded", "failed", "skipped"}, set(feedback["fixed_regression"]))

    def test_generated_case_ids_cannot_collide_with_fixed_or_each_other(self):
        self.deliver_basics()
        for cases in ([{**GENERATED, "id": "fixed-hidden"}], [GENERATED, GENERATED]):
            with self.subTest(cases=cases), self.assertRaisesRegex(ValueError, "unique.*frozen"):
                self.runner.by_name["propose_patch"].invoke(patch_args(cases=cases))
        self.assertEqual(SOURCE, self.runner.source)
        self.assertEqual([], self.runner.report["patches"])
        self.validator.assert_not_called()

    def test_stale_source_cannot_overwrite_a_newer_patch(self):
        self.deliver_basics()
        self.runner.by_name["propose_patch"].invoke(patch_args())
        with self.assertRaisesRegex(ValueError, "stale source"):
            self.runner.by_name["propose_patch"].invoke(patch_args(new='"other"'))
        self.assertEqual(SOURCE.replace('"old"', '"new"'), self.runner.source)
        self.assertEqual(1, len(self.runner.report["patches"]))

    def test_new_patch_invalidates_previous_validation_and_finish_authority(self):
        self.deliver_basics()
        first = self.runner.by_name["propose_patch"].invoke(patch_args())
        self.runner.delivered_refs.add("patch:" + first["patch_id"])
        self.runner.by_name["run_validation"].invoke({"patch_id": first["patch_id"]})
        self.runner.delivered_refs.add("validation:" + first["patch_id"])
        second = self.runner.by_name["propose_patch"].invoke(
            patch_args(self.runner.source, old='"new"', new='"repaired"'))
        self.assertNotEqual(first["patch_id"], second["patch_id"])
        with self.assertRaisesRegex(ValueError, "passing fixed validation"):
            self.runner.by_name["finish_analysis"].invoke(finish_args())
        with self.assertRaisesRegex(ValueError, "current patch_id"):
            self.runner.by_name["run_validation"].invoke({"patch_id": first["patch_id"]})
        self.assertIsNone(self.runner.decision)

    def test_read_tools_alone_do_not_prove_model_received_their_results(self):
        self.deliver_basics()
        self.runner.delivered_refs.clear()
        with self.assertRaisesRegex(ValueError, "prior turns"):
            self.runner.by_name["propose_patch"].invoke(patch_args())
        with self.assertRaisesRegex(ValueError, "prior turns"):
            self.runner.by_name["finish_analysis"].invoke(finish_args("hold"))
        self.assertIsNone(self.runner.patch)
        self.assertIsNone(self.runner.decision)

    def test_validation_and_finish_require_previous_model_turn_results(self):
        self.deliver_basics()
        patch = self.runner.by_name["propose_patch"].invoke(patch_args())
        with self.assertRaisesRegex(ValueError, "receive patch result"):
            self.runner.by_name["run_validation"].invoke({"patch_id": patch["patch_id"]})
        self.validator.assert_not_called()
        self.runner.delivered_refs.add("patch:" + patch["patch_id"])
        self.runner.by_name["run_validation"].invoke({"patch_id": patch["patch_id"]})
        with self.assertRaisesRegex(ValueError, "prior model turn"):
            self.runner.by_name["finish_analysis"].invoke(finish_args())
        self.assertIsNone(self.runner.decision)

    def test_same_batch_evidence_and_finish_is_held_without_recording_decision(self):
        model = ScriptedModel([response([call("get_case", {"case_id": CASE["case_id"]}),
                                         call("finish_analysis", finish_args("hold"))])])
        report = self.runner.run(model)
        self.assertEqual("HELD", report["status"])
        self.assertEqual("BLOCKED", report["routing_quality"])
        self.assertIsNone(self.runner.decision)
        self.validator.assert_not_called()

    def test_secret_and_unknown_tools_are_blocked_without_host_access(self):
        for path in (".env", "../../.env", "/etc/passwd"):
            with self.subTest(path=path), self.assertRaises(PolicyError):
                self.runner.by_name["read_file"].invoke({"path": path})
        report = self.runner.run(ScriptedModel([response([call("shell", {"cmd": "cat .env"})])]))
        self.assertEqual("HELD", report["status"])
        self.assertEqual("BLOCKED", report["routing_quality"])
        self.assertEqual(1, report["blocked_tools"])
        self.validator.assert_not_called()

    def test_malformed_tool_call_blocks_valid_calls_in_the_same_response(self):
        malformed = {"name": "read_file", "args": "{bad-json", "id": "bad", "error": "invalid JSON",
                     "type": "invalid_tool_call"}
        report = self.runner.run(ScriptedModel([response(
            [call("get_case", {"case_id": CASE["case_id"]})], invalid=[malformed])]))
        self.assertEqual("HELD", report["status"])
        self.assertEqual("BLOCKED", report["routing_quality"])
        self.assertEqual(1, len(report["requests"][0]["invalid_tool_calls"]))
        self.assertEqual([], report["tools"])
        self.assertEqual(set(), self.runner.read_refs)

    def test_unknown_timeout_cost_stays_reserved_and_exception_text_is_not_logged(self):
        model = ScriptedModel([TimeoutError("DO-NOT-LOG-API-SECRET")])
        report = self.runner.run(model)
        self.assertEqual("ERROR", report["status"])
        self.assertEqual(1, model.calls)
        self.assertEqual("IN_FLIGHT", report["requests"][0]["state"])
        self.assertGreater(report["unpriced_reserved_usd"], 0)
        self.assertIsNone(report["total_usage_estimated_usd"])
        self.assertEqual(report["unpriced_reserved_usd"], report["exposure_estimated_usd"])
        self.assertNotIn("DO-NOT-LOG-API-SECRET", (self.root / "run/report.json").read_text())

    def test_budget_exhaustion_is_checked_before_billable_inference(self):
        self.runner.budget = 0.001
        model = ScriptedModel([])
        report = self.runner.run(model)
        self.assertEqual("HELD", report["status"])
        self.assertEqual(0, model.calls)
        self.assertEqual([], report["requests"])
        self.assertIn("budget exhausted", report["stop_reason"])

    def test_modified_frozen_oracle_stops_before_validator_execution(self):
        self.deliver_basics()
        patch = self.runner.by_name["propose_patch"].invoke(patch_args())
        self.runner.delivered_refs.add("patch:" + patch["patch_id"])
        self.runner.fixtures[0]["expected"] = {"menus": ["weakened"]}
        with self.assertRaisesRegex(PolicyError, "oracle changed"):
            self.runner.by_name["run_validation"].invoke({"patch_id": patch["patch_id"]})
        self.validator.assert_not_called()


class InboxTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.before = {"Menu:old": {"__typename": "Menu", "id": "old", "name": "bread",
                                   "price": "5300", "reviews": 2, "images": ["before.jpg"]}}
        self.after = {"PlaceMenuItem:new": {"__typename": "PlaceMenuItem", "id": "new", "name": "bread",
                                          "price": {"displayText": "5,300원"}, "reviews": 2,
                                          "images": [{"url": "after.jpg"}]}}
        self.manifest = {"diagnostic": detect(self.before, self.after), "base_digest": digest(SOURCE),
                         "contract_version": "frozen-v1", "case_id": "first"}

    def test_value_ids_time_arguments_and_duplicate_entities_do_not_trigger_analysis(self):
        after = copy.deepcopy(self.before)
        entity = after.pop("Menu:old")
        entity.update(id="new", name="new bread", price="9000", reviews=500, images=["after.jpg", "other.jpg"])
        after["Menu:new"] = entity
        after["Menu:duplicate"] = copy.deepcopy(entity)
        self.assertFalse(detect(self.before, after)["eligible"])
        before_args = copy.deepcopy(self.before)
        before_args["Menu:old"]["details({\"id\":1})"] = {"timestamp": "old", "count": 1}
        after_args = copy.deepcopy(self.before)
        after_args["Menu:old"]["details({\"id\":2})"] = {"timestamp": "new", "count": 100}
        self.assertEqual("VALUE_OR_NO_CHANGE", detect(before_args, after_args)["classification"])

    def test_transport_failure_and_missing_evidence_are_not_model_eligible(self):
        for status in ("TIMEOUT", "HTTP_429", "HTTP_500"):
            with self.subTest(status=status):
                result = detect(self.before, self.after, transport_status=status)
                self.assertFalse(result["eligible"])
                self.assertEqual("TRANSPORT_FAILURE", result["classification"])
        self.assertFalse(detect(self.before, {})["eligible"])
        inbox = Inbox(self.root / "inbox.sqlite3")
        self.assertIsNone(inbox.enqueue({**self.manifest, "diagnostic": detect(self.before, self.before)}))

    def test_duplicate_incident_is_claimed_once_across_reopened_inboxes(self):
        first = Inbox(self.root / "inbox.sqlite3")
        fingerprint = first.enqueue(self.manifest)
        duplicate = first.enqueue({**self.manifest, "case_id": "second sample of same root cause"})
        self.assertEqual(fingerprint, duplicate)
        second = Inbox(self.root / "inbox.sqlite3")
        self.assertTrue(first.claim(fingerprint, self.root / "first-run"))
        self.assertFalse(second.claim(fingerprint, self.root / "duplicate-run"))
        self.assertEqual("RUNNING", second.get(fingerprint)["state"])
        # A crash/restart does not requeue or grant a second paid attempt.
        reopened = Inbox(self.root / "inbox.sqlite3")
        self.assertEqual(fingerprint, reopened.enqueue(self.manifest))
        self.assertFalse(reopened.claim(fingerprint, self.root / "after-crash"))
        first.finish(fingerprint, "AWAITING_REVIEW")
        self.assertEqual("AWAITING_REVIEW", second.get(fingerprint)["state"])
        self.assertFalse(second.claim(fingerprint, self.root / "after-finish"))
        with self.assertRaisesRegex(ValueError, "not claimed"):
            second.finish(fingerprint, "HELD")

    def test_changed_contract_or_parser_creates_a_distinct_review_event(self):
        inbox = Inbox(self.root / "inbox.sqlite3")
        original = inbox.enqueue(self.manifest)
        changed_parser = inbox.enqueue({**self.manifest, "base_digest": digest("new parser")})
        changed_contract = inbox.enqueue({**self.manifest, "contract_version": "frozen-v2"})
        self.assertEqual(3, len({original, changed_parser, changed_contract}))


if __name__ == "__main__":
    unittest.main()
