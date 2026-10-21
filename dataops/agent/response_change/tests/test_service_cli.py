"""Initial install recovery and launchd command contracts; no real launchd mutations."""
import copy
from dataclasses import replace
import hashlib
import io
import json
import os
from pathlib import Path
import plistlib
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from agent.response_change.approval import content_digest
from agent.response_change.host_runtime import (LaunchdDriver, ManagedHostAdapter,
    ManagedServiceConfig, atomic_write, write_json)
from agent.response_change.release import ReleaseHold
from agent.response_change.service_cli import main, prepare, summary
from agent.response_change.tests.test_host_runtime import ActiveRuntime, ChildDriver, Fence


class InstallationFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve(); self.root.chmod(0o700)
        props = self.root / "server.properties"; props.write_text("# private fixture\n"); props.chmod(0o600)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
        self.config = ManagedServiceConfig("isolated-service", self.root, Path(sys.executable).resolve(), props, port, 2)
        self.driver = ChildDriver(self.config); self.addCleanup(self.driver.close)
        self.active = ActiveRuntime(); self.fence = Fence()
        self.host = ManagedHostAdapter(self.config, driver=self.driver, active_runtime=self.active)
        artifact = self.root / "source.jar"; artifact.write_bytes(b"fixture-artifact")
        self.runtime = {"service_id":"isolated-service", "release_id":"initial-base", "generation":"initial-generation",
            "artifact_path":str(artifact), "artifact_sha256":hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "parser_digest":"1"*64, "profile_digest":"2"*64, "contract_digest":"3"*64,
            "dataops_database_identity":"4"*64, "serving_database_identity":"5"*64}
        self.runtime["database_identity"] = content_digest({"dataops":"4"*64,"serving":"5"*64})
        self.build_digest = "6"*64
        self.receipt = self.host.prepare_installation(self.runtime, self.build_digest)

    def reconcile_until_done(self):
        deadline = time.monotonic() + 5
        while True:
            result = self.host.reconcile_initial(self.fence)
            if result["outcome"] == "DONE": return result
            if time.monotonic() > deadline: self.fail("initial process did not reconcile")
            time.sleep(.02)


class InitialInstallationTest(InstallationFixture):
    def test_private_process_umask_preserves_exact_artifact_and_secret_modes(self):
        previous = os.umask(0o077)
        try:
            artifact = self.root / "masked.jar"
            secret = self.root / "masked.token"
            atomic_write(artifact, b"immutable", mode=0o444)
            atomic_write(secret, b"private")
            self.assertEqual(0o444, artifact.stat().st_mode & 0o777)
            self.assertEqual(0o600, secret.stat().st_mode & 0o777)
        finally:
            os.umask(previous)

    def test_prepare_is_durable_idempotent_closed_and_has_no_process_or_db_effect(self):
        second = self.host.prepare_installation(self.runtime, self.build_digest)
        self.assertEqual(self.receipt, second)
        self.assertEqual("CLOSED\n", Path(second["plan"]["admission_path"]).read_text())
        self.assertEqual(0, self.driver.starts); self.assertEqual([], self.active.states)
        self.assertEqual("NOT_STARTED", self.host.reconcile_initial(self.fence)["outcome"])
        self.assertEqual("KEEP_ALIVE_AFTER_USER_LOGIN", summary(second)["restart_policy"])

    def test_different_runtime_or_configuration_cannot_rebind_prepared_install(self):
        changed = {**self.runtime, "generation":"other"}
        with self.assertRaisesRegex(ReleaseHold, "INITIAL_INSTALLATION_REBOUND"):
            self.host.prepare_installation(changed, self.build_digest)
        self.config.properties.write_text("# edited after prepare\n")
        with self.assertRaisesRegex(ReleaseHold, "INITIAL_INSTALLATION_REBOUND"):
            self.host.prepare_installation(self.runtime, self.build_digest)

    def test_initial_start_enrolls_before_open_and_repeats_without_restart(self):
        original = self.active.enroll
        def enroll(live, fence):
            self.assertFalse(live["admission_open"])
            self.assertEqual("CLOSED\n", Path(self.receipt["plan"]["admission_path"]).read_text())
            original(live, fence)
        self.active.enroll = enroll
        live = self.host.start_initial(self.fence)
        self.assertTrue(live["admission_open"]); self.assertFalse(live["draining"])
        self.assertEqual("ENROLLED", self.host._initial()["state"])
        self.assertEqual(live["pid"], self.host.start_initial(self.fence)["pid"])
        self.assertEqual(1, self.driver.starts)

    def test_lost_start_response_reconciles_without_second_start(self):
        self.driver.crash_after_start = True
        with self.assertRaises(OSError): self.host.start_initial(self.fence)
        self.assertEqual("IN_FLIGHT", self.host._initial()["state"])
        with self.assertRaisesRegex(ReleaseHold, "INITIAL_RECONCILIATION_REQUIRED"):
            self.host.start_initial(self.fence, resume_absent=True)
        result = self.reconcile_until_done()
        self.assertEqual("ENROLLED", result["state"]); self.assertTrue(result["runtime"]["admission_open"])
        self.assertEqual(1, self.driver.starts)

    def test_crash_after_database_commit_before_current_recovers_closed_plan(self):
        original = self.active.enroll
        def lost_reply(live, fence):
            original(live, fence); raise OSError("lost database commit response")
        self.active.enroll = lost_reply
        with self.assertRaises(OSError): self.host.start_initial(self.fence)
        self.assertIsNotNone(self.active.live); self.assertFalse((self.root / "current.json").exists())
        self.assertEqual("CLOSED\n", Path(self.receipt["plan"]["admission_path"]).read_text())
        self.active.enroll = original
        self.assertEqual("DONE", self.reconcile_until_done()["outcome"])
        self.assertEqual(1, self.driver.starts)

    def test_unstarted_unknown_intent_requires_explicit_resume_and_confirmed_absence(self):
        with patch.object(self.driver, "start", side_effect=OSError("launchctl transport failure")):
            with self.assertRaises(OSError): self.host.start_initial(self.fence)
        self.assertEqual("UNKNOWN", self.host.reconcile_initial(self.fence)["outcome"])
        with self.assertRaisesRegex(ReleaseHold, "INITIAL_RECONCILIATION_REQUIRED"):
            self.host.start_initial(self.fence)
        with patch.object(self.driver, "absent", side_effect=ReleaseHold("LAUNCHD_JOB_STATE_UNKNOWN")):
            with self.assertRaisesRegex(ReleaseHold, "LAUNCHD_JOB_STATE_UNKNOWN"):
                self.host.start_initial(self.fence, resume_absent=True)
        self.assertTrue(self.host.start_initial(self.fence, resume_absent=True)["admission_open"])
        self.assertEqual(1, self.driver.starts)

    def test_frozen_artifact_and_private_configuration_tampering_hold(self):
        frozen = Path(self.receipt["plan"]["base_properties_path"])
        frozen.write_text("changed")
        with self.assertRaisesRegex(ReleaseHold, "INITIAL_ARTIFACT_OR_CONFIGURATION_CHANGED"):
            self.host.start_initial(self.fence)
        self.assertEqual(0, self.driver.starts)

    def test_changed_host_configuration_cannot_start_another_prepared_plan(self):
        changed = ManagedHostAdapter(replace(self.config, port=self.config.port+1),
            driver=self.driver,active_runtime=self.active)
        with self.assertRaisesRegex(ReleaseHold,"INITIAL_SERVICE_CONFIGURATION_CHANGED"):
            changed.start_initial(self.fence)
        self.assertEqual(0,self.driver.starts)

    def test_cli_prepare_requires_exact_committed_build_artifact(self):
        runtime_path = self.root / "runtime.json"; write_json(runtime_path, self.runtime)
        registry = Mock(); registry.build_receipt.return_value = {"artifact_sha256":self.runtime["artifact_sha256"],
            "artifact_path":self.runtime["artifact_path"]}
        self.assertEqual(self.receipt, prepare(self.host, registry, runtime_path, self.build_digest))
        registry.build_receipt.return_value["artifact_path"] = str(self.root / "other.jar")
        with self.assertRaisesRegex(ReleaseHold, "INITIAL_COMMITTED_BUILD_CHANGED"):
            prepare(self.host, registry, runtime_path, self.build_digest)

    def test_cli_requires_live_flag_before_connecting_to_postgres_or_launchd(self):
        configuration = self.root / "service.json"
        write_json(configuration,{"service_id":self.config.service_id,"host_directory":str(self.root),
            "java":str(self.config.java),"server_properties":str(self.config.properties),
            "server_port":self.config.port,"registry_directory":str(self.root / "registry")})
        with patch("agent.response_change.service_cli.psycopg2.connect") as connect, patch("sys.stdout",new_callable=io.StringIO) as output:
            self.assertEqual(2, main(["--configuration",str(configuration),"start"]))
            self.assertEqual("EXPLICIT_INITIAL_SERVICE_EXECUTION_REQUIRED", json.loads(output.getvalue())["code"])
            connect.assert_not_called()


class LaunchdContractTest(InstallationFixture):
    # These methods use a simulated launchctl response only; no service is installed or stopped.
    def launchd(self):
        driver = LaunchdDriver(self.config, launch_agents_directory=self.root / "LaunchAgents")
        driver._run = Mock(return_value=subprocess.CompletedProcess([], 113, b"", b'Could not find service "isolated"'))
        return driver

    def test_login_persistent_job_has_restart_policy_and_matches_owned_plan(self):
        driver = self.launchd()
        driver._run.side_effect = [subprocess.CompletedProcess([],113,b"",b"Could not find service"),
            subprocess.CompletedProcess([],0,b"",b"")]
        driver.start(self.receipt["plan"])
        target = driver._installation_path()
        plist = plistlib.loads(target.read_bytes())
        self.assertTrue(plist["KeepAlive"]); self.assertTrue(plist["RunAtLoad"])
        self.assertEqual(10, plist["ThrottleInterval"]); self.assertEqual(30, plist["ExitTimeOut"])
        self.assertEqual(self.receipt["plan"]["argv"], plist["ProgramArguments"])
        self.assertEqual(0o600, target.stat().st_mode & 0o777)
        self.assertEqual(["bootstrap",driver.domain,str(target)], driver._run.call_args.args[0])
        driver._run.side_effect = None; driver._run.return_value = subprocess.CompletedProcess([],0,b"  pid = 123\n",b"")
        driver.require_owner(self.receipt["plan"], {"pid":123,"launch_nonce":self.receipt["plan"]["launch_nonce"]})

    def test_foreign_persistent_plist_and_loaded_job_are_never_overwritten(self):
        driver = self.launchd(); target = driver._installation_path()
        target.write_bytes(b"foreign"); target.chmod(0o600)
        with self.assertRaisesRegex(ReleaseHold,"FOREIGN_LAUNCHD_INSTALLATION"):
            driver.start(self.receipt["plan"])
        self.assertEqual(b"foreign", target.read_bytes())
        driver._run.return_value = subprocess.CompletedProcess([],0,b"pid = 123\n",b"")
        with self.assertRaisesRegex(ReleaseHold,"LAUNCHD_JOB_ALREADY_LOADED"):
            driver.start(self.receipt["plan"])
        self.assertFalse(any(call.args[0][0] in {"bootstrap","bootout"} for call in driver._run.call_args_list))

    def test_unknown_launchctl_error_is_not_absence(self):
        driver = self.launchd(); driver._run.return_value = subprocess.CompletedProcess([],1,b"",b"permission denied")
        with self.assertRaisesRegex(ReleaseHold,"LAUNCHD_JOB_STATE_UNKNOWN"): driver.start(self.receipt["plan"])
        self.assertEqual(1,driver._run.call_count)

    def test_failed_bootstrap_receipt_can_recover_owned_plist_when_job_confirmed_absent(self):
        driver = self.launchd()
        absent = subprocess.CompletedProcess([],113,b"",b"Could not find service")
        driver._run.side_effect = [absent,subprocess.CompletedProcess([],1,b"",b"interrupted")]
        with self.assertRaisesRegex(ReleaseHold,"LAUNCHD_START_UNKNOWN"): driver.start(self.receipt["plan"])
        self.assertTrue((self.root / "launchd-installation.json").exists())
        driver._run.side_effect = [absent,subprocess.CompletedProcess([],0,b"",b"")]
        driver.start(self.receipt["plan"])

    def test_frozen_base_configuration_change_prevents_bootstrap(self):
        driver = self.launchd(); Path(self.receipt["plan"]["base_properties_path"]).write_text("changed")
        with self.assertRaisesRegex(ReleaseHold,"FROZEN_CONFIGURATION_CHANGED"): driver.start(self.receipt["plan"])
        self.assertEqual(1,driver._run.call_count)


if __name__ == "__main__": unittest.main()
