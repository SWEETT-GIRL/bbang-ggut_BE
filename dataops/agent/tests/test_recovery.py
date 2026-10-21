"""Crash injection at durable boundaries; never a live provider call."""
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from agent.response_change.runtime import PolicyError, Runner
from agent.response_change.dispatch import LostLease
from agent.tests.test_runtime import CASE, SOURCE, FIXTURES, repair_model, validation


class ProcessDeath(BaseException):
    pass


class RecoveryTests(unittest.TestCase):
    def exercise(self, phase, predicate=lambda report: True):
        with tempfile.TemporaryDirectory() as root:
            saved = []
            killed = False
            def checkpoint(report):
                nonlocal killed
                saved.append(copy.deepcopy(report))
                if not killed and report["recovery"]["phase"] == phase and predicate(report):
                    killed = True
                    raise ProcessDeath()
            validator = Mock(side_effect=lambda *a, **k: validation())
            directory = Path(root) / "run"
            runner = Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, validator)
            runner.checkpoint = checkpoint
            model = repair_model()
            with self.assertRaises(ProcessDeath):
                runner.run(model)
            prior = saved[-1]
            self.assertEqual("RUNNING", prior["status"])
            if phase in {"MODEL_IN_FLIGHT", "TOOL_IN_FLIGHT", "FINAL_IN_FLIGHT"}:
                before = model.calls
                with self.assertRaises(PolicyError):
                    Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, validator,
                           prior_report=prior)
                self.assertEqual(before, model.calls)
                return
            resumed = Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, validator,
                             prior_report=prior)
            report = resumed.run(model)
            self.assertEqual("AWAITING_REVIEW", report["status"])
            self.assertEqual(6, model.calls, "a committed paid response must not be requested again")
            self.assertEqual(7, len(report["tools"]))
            self.assertEqual(1, len(report["patches"]))
            self.assertEqual(2, validator.call_count, "only original plus mandatory final validation")
            self.assertEqual(1, report["recovery_count"])
            self.assertEqual(0.50, report["budget_usd"])
            self.assertGreaterEqual(report["elapsed_seconds"], prior["recovery"]["active_seconds"] - .001)

    def test_received_response_resumes_without_second_paid_call(self):
        self.exercise("RESPONSE_READY")

    def test_between_tools_in_same_model_turn_keeps_only_remaining_tools(self):
        self.exercise("TOOLS_READY", lambda r: len(r["tools"]) == 3)

    def test_after_patch_does_not_repeat_patch_files_or_budget(self):
        self.exercise("TOOLS_READY", lambda r: len(r["patches"]) == 1 and len(r["tools"]) == 5)

    def test_completed_tool_batch_resumes_next_model_turn(self):
        self.exercise("MODEL_READY", lambda r: len(r["tools"]) == 5)

    def test_final_decision_checkpoint_runs_only_mandatory_validation(self):
        self.exercise("FINAL_READY")

    def test_uncertain_model_invocation_is_held(self):
        self.exercise("MODEL_IN_FLIGHT")

    def test_uncertain_tool_side_effect_is_held(self):
        self.exercise("TOOL_IN_FLIGHT", lambda r: len(r["patches"]) == 1)

    def test_uncertain_final_validation_is_held(self):
        self.exercise("FINAL_IN_FLIGHT")

    def test_fencing_failure_before_reservation_prevents_paid_call(self):
        with tempfile.TemporaryDirectory() as root:
            runner = Runner(CASE, SOURCE, "legacy tests", FIXTURES, Path(root) / "run", Mock())
            def checkpoint(report):
                raise RuntimeError("lease lost")
            runner.checkpoint = checkpoint
            model = repair_model()
            with self.assertRaises(RuntimeError):
                runner.run(model)
            self.assertEqual(0, model.calls)

    def test_changed_budget_or_runtime_cannot_resume(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root) / "run"
            runner = Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, Mock())
            prior = copy.deepcopy(runner.report)
            with self.assertRaisesRegex(PolicyError, "budget_usd"):
                Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, Mock(),
                       budget=3, prior_report=prior)
            prior["runtime_sources"]["runtime.py"] = "changed"
            with self.assertRaisesRegex(PolicyError, "runtime_sources"):
                Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, Mock(), prior_report=prior)

    def test_fencing_loss_after_received_response_keeps_unknown_reservation(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root) / "run"
            runner = Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, Mock())
            durable = copy.deepcopy(runner.report)
            lost = False
            def checkpoint(report):
                nonlocal durable, lost
                if lost or any(row.get("state") == "RECEIVED" for row in report["requests"]):
                    lost = True
                    raise LostLease("expired after provider returned")
                durable = copy.deepcopy(report)
            runner.checkpoint = checkpoint
            model = repair_model()
            with self.assertRaises(LostLease):
                runner.run(model)
            self.assertEqual(1, model.calls)
            self.assertEqual("MODEL_IN_FLIGHT", durable["recovery"]["phase"])
            self.assertNotIn("cost_usd", durable["requests"][0])
            self.assertGreater(durable["unpriced_reserved_usd"], 0)
            with self.assertRaises(PolicyError):
                Runner(CASE, SOURCE, "legacy tests", FIXTURES, directory, Mock(), prior_report=durable)
            self.assertEqual(1, model.calls)


if __name__ == "__main__":
    unittest.main()
