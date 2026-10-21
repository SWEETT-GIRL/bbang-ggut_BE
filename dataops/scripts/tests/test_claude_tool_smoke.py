import sys
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import claude_tool_smoke as smoke
from claude_tool_smoke import CASE, OPUS, SONNET, InvalidToolCall, Probe, budget_summary, cost
from langchain_core.messages import AIMessage, HumanMessage


class FakeSonnet:
    model = SONNET

    def __init__(self):
        self.step = 0

    def get_num_tokens_from_messages(self, messages, tools):
        return 100

    def bind_tools(self, tools, tool_choice):
        assert tool_choice == "auto"
        return self

    def invoke(self, messages):
        choices = [("get_case", {"case_id": CASE["case_id"]}),
                   ("read_file", {"path": "parser/MenuPriceParser.java"}),
                   ("finish_analysis", {"disposition": "hold", "reason": "Unknown numeric meaning",
                                        "evidence_refs": [CASE["case_id"], "parser/MenuPriceParser.java"]})]
        name, args = choices[self.step]
        self.step += 1
        return AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": str(self.step)}],
                         usage_metadata={"input_tokens": 100, "output_tokens": 20, "total_tokens": 120})


def call(name, args):
    return {"name": name, "args": args, "id": name}


def finish(refs=None):
    return call("finish_analysis", {"disposition": "hold", "reason": "Unknown numeric meaning",
        "evidence_refs": refs if refs is not None else [CASE["case_id"], "parser/MenuPriceParser.java"]})


def message(calls, invalid_calls=None):
    return AIMessage(content="", tool_calls=calls, invalid_tool_calls=invalid_calls or [],
                     usage_metadata={"input_tokens": 100, "output_tokens": 20, "total_tokens": 120})


class ScriptedSonnet(FakeSonnet):
    def __init__(self, answers):
        self.answers = iter(answers)

    def invoke(self, messages):
        return next(self.answers)


class ToolProbeTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.probe = Probe(Path(directory.name) / "report.json")

    def test_tool_executor_cannot_read_host_files_or_another_case(self):
        for path in (".env", "../../.env", "/etc/passwd"):
            with self.assertRaises(ValueError):
                self.probe.tools_by_name["read_file"].invoke({"path": path})
        with self.assertRaises(ValueError):
            self.probe.tools_by_name["get_case"].invoke({"case_id": "another"})

    def test_finish_without_evidence_is_rejected(self):
        with self.assertRaises(ValueError):
            self.probe.tools_by_name["finish_analysis"].invoke({"disposition": "hold", "reason": "guess",
                                                              "evidence_refs": [CASE["case_id"]]})

    def test_graph_roundtrip_and_paired_context_are_actual_tool_results(self):
        self.probe.graph(FakeSonnet()).invoke({"messages": [HumanMessage(content="Analyze")]})
        context, answer, index = self.probe.candidates[-1]
        self.assertEqual(index, 2)
        self.assertEqual([m.type for m in context], ["system", "human", "ai", "tool", "ai", "tool"])
        self.assertTrue(self.probe.evaluate(answer)["passed"])
        self.assertEqual([t["name"] for t in self.probe.report["tools"]],
                         ["get_case", "read_file", "finish_analysis"])

    def test_wrong_decision_is_not_passed_by_fixed_evaluator(self):
        self.probe.read_refs.update([CASE["case_id"], "parser/MenuPriceParser.java"])
        answer = AIMessage(content="", tool_calls=[{"name": "finish_analysis", "id": "1", "args":
            {"disposition": "needs_patch", "reason": "assume value is price",
             "evidence_refs": list(self.probe.read_refs)}}])
        self.assertFalse(self.probe.evaluate(answer)["passed"])

    def test_usage_cost_and_unknown_cache_fail_closed(self):
        usage = {"input_tokens": 1000, "output_tokens": 100}
        self.assertAlmostEqual(cost(SONNET, usage), 0.003)
        self.assertAlmostEqual(cost(OPUS, usage), 0.006)
        with self.assertRaises(ValueError):
            cost(SONNET, {**usage, "input_token_details": {"cache_read": 100}})

    def test_opus_is_limited_to_one_inference_including_unknown_outcomes(self):
        model = Mock(model=OPUS)
        self.probe.report["requests"].append({"model": OPUS, "state": "IN_FLIGHT", "reserved_usd": 0.02})
        with self.assertRaisesRegex(RuntimeError, "request limit"):
            self.probe.invoke(model, [])
        model.get_num_tokens_from_messages.assert_not_called()
        model.bind_tools.assert_not_called()

    def test_budget_is_checked_before_billable_inference(self):
        model = Mock(model=SONNET)
        model.get_num_tokens_from_messages.return_value = 3000
        self.probe.report["requests"].append({"model": SONNET, "state": "IN_FLIGHT", "reserved_usd": 0.149})
        with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
            self.probe.invoke(model, [])
        model.bind_tools.assert_not_called()

    def test_executor_and_evaluator_both_require_case_and_parser_citations(self):
        self.probe.delivered_refs.update([CASE["case_id"], "parser/MenuPriceParser.java", "tests/price-contract.txt"])
        for refs in (["parser/MenuPriceParser.java", "tests/price-contract.txt"],
                     ["get_case:" + CASE["case_id"], "parser/MenuPriceParser.java"],
                     [CASE["case_id"]]):
            with self.subTest(refs=refs):
                with self.assertRaises(ValueError):
                    self.probe.tools_by_name["finish_analysis"].invoke(finish(refs)["args"])
                self.assertFalse(self.probe.evaluate(message([finish(refs)]))["passed"])
                self.assertIsNone(self.probe.decision)
        valid = finish()["args"]
        self.assertTrue(self.probe.tools_by_name["finish_analysis"].invoke(valid)["recorded"])
        self.assertTrue(self.probe.evaluate(message([finish()]))["passed"])

    def test_reading_without_delivering_the_result_cannot_support_finish(self):
        self.probe.tools_by_name["get_case"].invoke({"case_id": CASE["case_id"]})
        self.probe.tools_by_name["read_file"].invoke({"path": "parser/MenuPriceParser.java"})
        with self.assertRaisesRegex(ValueError, "previous model turn"):
            self.probe.tools_by_name["finish_analysis"].invoke(finish()["args"])
        self.assertFalse(self.probe.evaluate(message([finish()]))["passed"])

    def test_finish_cannot_follow_reads_in_the_same_assistant_batch(self):
        model = ScriptedSonnet([
            message([call("get_case", {"case_id": CASE["case_id"]}),
                     call("read_file", {"path": "parser/MenuPriceParser.java"}), finish()]),
            message([finish()]),
        ])
        self.probe.graph(model).invoke({"messages": [HumanMessage(content="Analyze")]})
        self.assertEqual([row["status"] for row in self.probe.report["tools"]],
                         ["success", "success", "error", "success"])
        evaluation = self.probe.evaluate(self.probe.candidates[-1][1])
        self.assertTrue(evaluation["passed"])
        self.assertEqual(self.probe.outcome([evaluation]), "RECOVERED")

    def test_invalid_calls_are_recorded_and_reject_otherwise_valid_final_call(self):
        answer = message([finish()], [{"name": "read_file", "args": "{bad-json", "id": "bad",
                                       "error": "not valid JSON", "type": "invalid_tool_call"}])
        self.probe.delivered_refs.update([CASE["case_id"], "parser/MenuPriceParser.java"])
        with self.assertRaises(InvalidToolCall):
            self.probe.invoke(ScriptedSonnet([answer]), [])
        self.assertEqual(self.probe.report["tools"][0]["error_kind"], "invalid_tool_call")
        self.assertEqual(len(self.probe.report["requests"][0]["invalid_tool_calls"]), 1)
        self.assertFalse(self.probe.evaluate(answer)["passed"])
        self.assertEqual(self.probe.outcome([{"passed": True}]), "BLOCKED")
        self.assertIsNone(self.probe.decision)

    def test_forbidden_attempt_cannot_be_erased_by_later_valid_finish(self):
        model = ScriptedSonnet([
            message([call("get_case", {"case_id": CASE["case_id"]}),
                     call("read_file", {"path": "parser/MenuPriceParser.java"})]),
            message([call("read_file", {"path": ".env"})]),
            message([finish()]),
        ])
        self.probe.graph(model).invoke({"messages": [HumanMessage(content="Analyze")]})
        self.assertEqual(self.probe.report["tools"][2]["error_kind"], "forbidden_attempt")
        evaluation = self.probe.evaluate(self.probe.candidates[-1][1])
        self.assertFalse(evaluation["passed"])
        self.assertEqual(self.probe.outcome([evaluation]), "BLOCKED")

    def test_unknown_cost_is_reserved_and_not_reported_as_zero_actual_cost(self):
        summary = budget_summary([{"cost_usd": 0.003, "reserved_usd": 0.01},
                                  {"state": "IN_FLIGHT", "reserved_usd": 0.0085}])
        self.assertIsNone(summary["actual_usage_estimated_usd"])
        self.assertAlmostEqual(summary["known_usage_estimated_usd"], 0.003)
        self.assertAlmostEqual(summary["unresolved_reserved_usd"], 0.0085)
        self.assertAlmostEqual(summary["maximum_exposure_usd"], 0.0115)
        self.assertEqual(summary["unpriced_requests"], 1)

    def test_cli_defaults_to_sonnet_and_opus_requires_explicit_comparison(self):
        for compare in (False, True):
            with self.subTest(compare=compare), tempfile.TemporaryDirectory() as directory:
                constructed = []

                def factory(**kwargs):
                    constructed.append(kwargs["model"])
                    model = ScriptedSonnet([message([finish()])]) if kwargs["model"] == OPUS else FakeSonnet()
                    model.model = kwargs["model"]
                    return model

                arguments = ["claude_tool_smoke.py", "--live"] + (["--compare-opus"] if compare else [])
                stdout = io.StringIO()
                with patch.object(smoke, "ROOT", Path(directory)), patch.object(smoke, "ChatAnthropic", factory), \
                     patch.dict("os.environ", {"ANTHROPIC_API_KEY": "offline-test-key"}), \
                     patch.object(sys, "argv", arguments), contextlib.redirect_stdout(stdout):
                    self.assertEqual(smoke.main(), 0)
                self.assertEqual(constructed, [SONNET, OPUS] if compare else [SONNET])
                result = json.loads(stdout.getvalue())
                self.assertEqual(result["status"], "PASS")
                self.assertEqual(result["unresolved_reserved_usd"], 0)


if __name__ == "__main__":
    unittest.main()
