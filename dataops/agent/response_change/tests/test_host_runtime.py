"""Real disposable HTTP subprocesses, fake JVM measurement/DB journal. No launchd installation."""
import copy
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from agent.response_change.host_runtime import ManagedHostAdapter, ManagedServiceConfig, write_json, RUNTIME_FIELDS
from agent.response_change.release import ReleaseHold


class Fence:
    service_id = "isolated-service"
    token = 1
    alive = True
    def assert_held(self):
        if not self.alive:
            raise ReleaseHold("TEST_FENCE_LOST")


class ActiveRuntime:
    def __init__(self): self.live = None; self.states = []
    def enroll(self, live, fence):
        fence.assert_held(); self.live = copy.deepcopy(live); self.states.append("ACTIVE")
    def transition(self, old, live, state, fence):
        fence.assert_held()
        if self.live["generation"] != old["generation"]:
            if not all(self.live[key] == live[key] for key in RUNTIME_FIELDS):
                raise ReleaseHold("TEST_RUNTIME_CAS")
        self.live = copy.deepcopy(live); self.states.append(state)


class ChildDriver:
    def __init__(self, config):
        self.config = config; self.children = {}; self.starts = 0; self.stops = 0; self.crash_after_start = False
    def require_owner(self, plan, live):
        child = self.children.get(plan["launch_nonce"])
        if child is None or child.poll() is not None or live["pid"] != child.pid:
            raise ReleaseHold("TEST_NOT_OWNED")
    def absent(self):
        return all(child.poll() is not None for child in self.children.values())
    def start(self, plan):
        path = self.config.root / (plan["launch_nonce"] + "-fixture.json"); write_json(path, plan)
        self.starts += 1
        self.children[plan["launch_nonce"]] = subprocess.Popen([sys.executable,
            str(Path(__file__).with_name("runtime_http_fixture.py")), str(path), str(self.config.root / "control.token"), str(self.config.port)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if self.crash_after_start:
            raise OSError("injected lost start response")
    def stop(self, plan, live):
        self.require_owner(plan, live); self.stops += 1
        child = self.children[plan["launch_nonce"]]; child.terminate(); child.wait(timeout=3)
    def close(self):
        for child in self.children.values():
            if child.poll() is None: child.terminate(); child.wait(timeout=3)


class ManagedHostTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        os.chmod(self.root, 0o700)
        properties = self.root / "private.properties"; properties.write_text("# fixture only\n"); properties.chmod(0o600)
        with socket.socket() as sock: sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
        self.config = ManagedServiceConfig("isolated-service", self.root, Path(sys.executable).resolve(), properties, port, 2)
        self.driver = ChildDriver(self.config); self.addCleanup(self.driver.close); self.active = ActiveRuntime(); self.fence = Fence()
        self.host = ManagedHostAdapter(self.config, driver=self.driver, active_runtime=self.active)
        self.base = self.release("base", "old"); self.candidate = self.release("next", "new")
        initial = self.host.prepare_initial_plan(self.base); self.driver.start(initial)
        deadline = time.monotonic() + 10
        while True:
            try:
                self.host.enroll_started(initial, self.fence); break
            except OSError:
                if time.monotonic() > deadline: raise
                time.sleep(0.02)
        self.command = {"operation_id": "release-" + "1" * 32, "manifest_digest": "a" * 64,
            "service_id": self.fence.service_id, "base": self.base, "candidate": self.candidate,
            "expected_generation": "old", "rollback_generation": "rollback", "rollback_release_id": "rollback-release", "writes_enabled": False,
            "action": "prepare", "prepared": None}
    def tearDown(self): self.driver.close(); self.temp.cleanup()
    def release(self, release, generation):
        artifact = self.root / (release + ".jar"); artifact.write_bytes(release.encode())
        return {"service_id": "isolated-service", "release_id": release, "generation": generation,
            "artifact_path": str(artifact), "artifact_sha256": hashlib.sha256(release.encode()).hexdigest(),
            **{key: str(index) * 64 for index, key in enumerate(("parser_digest", "profile_digest", "contract_digest",
                "database_identity", "dataops_database_identity", "serving_database_identity"), 1)}}
    def execute(self, action):
        self.command["action"] = action
        return getattr(self.host, action)(self.command, self.fence)
    def prepared_drained(self):
        self.command["prepared"] = self.execute("prepare"); self.execute("drain")
    def test_prepare_drain_start_and_fresh_generation_rollback(self):
        self.prepared_drained(); self.execute("start")
        live = self.host.observe(self.fence.service_id, self.fence)
        self.assertEqual("new", live["generation"]); self.assertFalse(live["writes_enabled"])
        self.assertTrue(live["admission_open"]); self.assertFalse(live["draining"])
        self.command["expected_generation"] = "new"; self.execute("rollback")
        restored = self.host.observe(self.fence.service_id, self.fence)
        self.assertEqual("rollback-release", restored["release_id"]); self.assertEqual("rollback", restored["generation"]); self.assertEqual(self.base["artifact_sha256"], restored["artifact_sha256"])
        self.assertEqual(["ACTIVE", "DRAINING", "STOPPED", "ACTIVE", "DRAINING", "STOPPED", "ACTIVE"], self.active.states)
        self.assertTrue(restored["admission_open"]); self.assertFalse(restored["draining"])
    def test_drained_old_plan_stays_closed_across_process_restart(self):
        self.prepared_drained(); plan = self.host._current(); live = self.host.observe(self.fence.service_id, self.fence)
        self.assertFalse(live["admission_open"])
        self.driver.stop(plan, live); self.driver.start(plan)
        restored = self.host._wait(plan, self.fence)
        self.assertTrue(restored["drained"]); self.assertFalse(restored["admission_open"])
    def test_base_configuration_is_frozen_for_reboot(self):
        plan = self.host._current()
        before = Path(plan["base_properties_path"]).read_bytes()
        self.config.properties.write_text("# later operator change\n")
        self.assertEqual(before, Path(plan["base_properties_path"]).read_bytes())
        self.assertIn("file:" + plan["base_properties_path"], " ".join(plan["argv"]))
    def test_start_before_drain_never_stops_old_process(self):
        self.command["prepared"] = self.execute("prepare")
        with self.assertRaisesRegex(ReleaseHold, "OLD_RUNTIME_NOT_DRAINED"): self.execute("start")
        self.assertEqual(0, self.driver.stops)
        self.assertEqual("UNKNOWN", self.host.reconcile(self.command["operation_id"], "start", self.fence)["outcome"])
    def test_lost_start_response_is_reconciled_without_second_start(self):
        self.prepared_drained(); self.driver.crash_after_start = True
        with self.assertRaises(OSError): self.execute("start")
        with self.assertRaisesRegex(ReleaseHold, "HOST_RECONCILIATION_REQUIRED"): self.execute("start")
        restarted = ManagedHostAdapter(self.config, driver=self.driver, active_runtime=self.active)
        deadline = time.monotonic() + 10
        while True:
            outcome = restarted.reconcile(self.command["operation_id"], "start", self.fence)
            if outcome["outcome"] == "DONE": break
            if time.monotonic() > deadline: self.fail("started child did not reconcile")
            time.sleep(0.02)
        self.assertEqual(2, self.driver.starts)
        self.assertEqual("new", restarted.observe(self.fence.service_id, self.fence)["generation"])
    def test_changed_artifact_holds_before_process_effect(self):
        self.command["prepared"] = self.execute("prepare"); self.execute("drain")
        path = Path(self.command["prepared"]["candidate_path"]); path.chmod(0o600); path.write_bytes(b"changed")
        with self.assertRaisesRegex(ReleaseHold, "PREPARED_ARTIFACT_CHANGED"): self.execute("start")
        self.assertEqual(0, self.driver.stops)
    def test_lost_or_stale_fence_and_changed_generation_hold(self):
        self.fence.token = 3; self.host.observe(self.fence.service_id, self.fence)
        self.fence.token = 2
        with self.assertRaisesRegex(ReleaseHold, "HOST_FENCE_STALE"): self.execute("prepare")
        self.fence.token = 3; self.fence.alive = False
        with self.assertRaisesRegex(ReleaseHold, "TEST_FENCE_LOST"): self.execute("prepare")
        self.assertEqual(0, self.driver.stops)
    def test_same_operation_cannot_change_its_command(self):
        self.execute("prepare"); self.command["manifest_digest"] = "b" * 64
        with self.assertRaisesRegex(ReleaseHold, "HOST_OPERATION_REBOUND"): self.execute("prepare")
    def test_foreign_pid_cannot_be_stopped(self):
        self.prepared_drained()
        plan = self.host._current(); child = self.driver.children.pop(plan["launch_nonce"]); child.terminate(); child.wait(timeout=3)
        with self.assertRaises((OSError, ReleaseHold)): self.execute("start")
        self.assertEqual(0, self.driver.stops)
    def test_configuration_and_token_are_not_copied_into_command(self):
        initial = self.host._current(); token = (self.root / "control.token").read_text().strip()
        self.assertNotIn(token, json.dumps(initial)); self.assertIn("--dataops.serving.write-enabled=false", initial["argv"])
        self.assertIn("--spring.flyway.enabled=false", initial["argv"])
        self.assertEqual(0o600, os.stat(initial["log_path"]).st_mode & 0o777)

if __name__ == "__main__": unittest.main()
