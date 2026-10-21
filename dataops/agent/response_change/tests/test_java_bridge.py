"""Transport and replay retry-boundary tests. The Java/PG tests validate actual execution authority."""
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

from agent.response_change.approval import content_digest
from agent.response_change.host_runtime import write_json
from agent.response_change.java_bridge import JavaBridgeConfig, JavaReplayAdapter
from agent.response_change.release import ReleaseHold, _identity
from agent.response_change.tests.test_host_runtime import Fence


class JavaBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name).resolve(); root.chmod(0o700)
        props = root / "config.properties"; props.write_text("# test\n"); props.chmod(0o600)
        jar = root / "candidate.jar"; jar.write_bytes(b"fixture")
        from hashlib import sha256
        self.adapter = JavaReplayAdapter(JavaBridgeConfig(Path(sys.executable).resolve(), props, root, 1), object())
        candidate = {"release_id": "candidate", "generation": "next", "artifact_sha256": sha256(b"fixture").hexdigest(),
            **{key: "a" * 64 for key in ("parser_digest", "profile_digest", "contract_digest", "database_identity")}}
        targets = [{"capture_id": "capture-1"}, {"capture_id": "capture-2"}]
        self.command = {"operation_id": "release-" + "1" * 32, "manifest_digest": "b" * 64, "candidate": candidate,
            "prepared": {"candidate_path": str(jar)}, "action": "replay", "targets": targets, "targets_digest": content_digest(targets)}
        self.result = {"schema_version": 1, "operation_id": self.command["operation_id"],
            "replay_operation_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "response-replay:" + self.command["operation_id"])),
            "manifest_digest": "b" * 64, "generation": "next", "phase": "REPLAY", "targets_digest": content_digest(targets),
            "runtime": _identity(candidate), "operating_writes": 0, "passed": False, "complete": True, "outcome": "DONE",
            "items": [{"capture_id": "capture-1", "status": "HELD"}, {"capture_id": "capture-2", "status": "NOT_ATTEMPTED"}]}
    def test_partial_completed_phase_preserves_not_attempted(self):
        self.adapter._check_result(self.result, self.command)
        self.assertEqual("NOT_ATTEMPTED", self.result["items"][1]["status"])
    def test_changed_runtime_targets_or_completion_is_rejected(self):
        for key, value in (("operating_writes", True), ("generation", "old"), ("complete", False),
                           ("targets_digest", "c" * 64), ("runtime", {}), ("items", [])):
            result = copy.deepcopy(self.result); result[key] = value
            with self.assertRaises(ReleaseHold): self.adapter._check_result(result, self.command)
    def test_timeout_or_lost_response_never_reexecutes(self):
        with patch.object(self.adapter, "_binding", return_value={}) as binding, patch.object(self.adapter, "_invoke", side_effect=OSError("lost")) as invoke:
            with self.assertRaises(OSError): self.adapter.replay(self.command, Fence())
            with self.assertRaisesRegex(ReleaseHold, "REPLAY_RECONCILIATION_REQUIRED"): self.adapter.replay(self.command, Fence())
            self.assertEqual(1, invoke.call_count); self.assertEqual(1, binding.call_count)
    def test_reconciliation_only_invokes_status(self):
        directory = self.adapter._directory(self.command["operation_id"], "replay")
        write_json(directory / "intent.json", {"command": self.command, "command_digest": content_digest(self.command)})
        with patch.object(self.adapter, "_invoke", return_value=self.result) as invoke:
            result = self.adapter.reconcile(self.command["operation_id"], "replay", Fence())
            self.assertEqual("DONE", result["outcome"])
            self.assertTrue(invoke.call_args.kwargs["status"])
    def test_no_local_intent_cannot_prove_nonexecution(self):
        self.assertEqual({"outcome": "UNKNOWN"}, self.adapter.reconcile(self.command["operation_id"], "replay", Fence()))
    def test_exit_zero_without_durable_receipt_is_unknown(self):
        class Child:
            def poll(self): return 0
        with patch("agent.response_change.java_bridge.subprocess.Popen", return_value=Child()) as popen:
            directory = self.adapter._directory(self.command["operation_id"], "replay")
            with self.assertRaisesRegex(ReleaseHold, "REPLAY_RECEIPT_MISSING"):
                self.adapter._invoke(self.command, Fence(), directory, status=False)
            argv = popen.call_args.args[0]
            self.assertIn("--spring.flyway.enabled=false", argv)
            self.assertIn("--dataops.agent.apply.enabled=false", argv)
            self.assertNotIn("ANTHROPIC_API_KEY", popen.call_args.kwargs["env"])
            self.assertNotIn("shell", popen.call_args.kwargs)
    def test_java_status_command_cannot_execute_replay(self):
        class Child:
            def poll(self): return 0
        def spawn(argv, **kwargs):
            path = Path(next(arg.split("=", 1)[1] for arg in argv if arg.startswith("--dataops.agent.replay.receipt-path=")))
            self.assertIn("--dataops.agent.replay.action=STATUS", argv)
            write_json(path, self.result)
            return Child()
        with patch("agent.response_change.java_bridge.subprocess.Popen", side_effect=spawn):
            result = self.adapter._invoke(self.command, Fence(), self.adapter._directory(self.command["operation_id"], "replay"), status=True)
            self.assertEqual(self.result, result)

if __name__ == "__main__": unittest.main()
