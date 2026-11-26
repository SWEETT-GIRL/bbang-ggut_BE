"""Offline fault experiments: real files, processes, locks and loopback telemetry."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import batch_execution as journal
import weekly_refresh as weekly


class BatchExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        patcher = patch.dict(os.environ, {"DATAOPS_PUSHGATEWAY_URL": ""})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_automatic_apply_metrics_are_item_counts_and_exclude_identifiers(self):
        execution = journal.WorkflowRun(self.directory, 'freshness-refresh')
        with execution:
            execution.record['items'] = {'auto_apply_applied': 91, 'auto_apply_unknown': 1, 'proposal_id': 1234}
            execution.finish(1)
        metrics = journal.metrics(self.directory / 'runs', 'freshness-refresh')
        self.assertIn('result="auto_apply_applied"} 91', metrics)
        self.assertIn('result="auto_apply_unknown"} 1', metrics)
        self.assertNotIn('proposal_id', metrics)

    def test_failure_records_step_and_run_without_exception_message(self):
        execution = journal.WorkflowRun(self.directory, "nightly")
        with self.assertRaises(ValueError):
            with execution.exclusive(), execution:
                with execution.step("sync_worker.py"):
                    raise ValueError("SECRET-do-not-log")
        raw = execution.path.read_text()
        record = json.loads(raw)
        self.assertEqual(record["status"], "FAILED")
        self.assertEqual(record["steps"][0]["exit_code"], 1)
        self.assertGreaterEqual(record["duration_seconds"], 0)
        self.assertNotIn("SECRET", raw)

    def test_same_directory_rejects_overlap_then_allows_next_run(self):
        first = journal.WorkflowRun(self.directory, "nightly")
        second = journal.WorkflowRun(self.directory, "apply")
        with first.exclusive(), first:
            with self.assertRaises(journal.AlreadyRunning):
                with second.exclusive():
                    self.fail("concurrent workflow entered")
            first.finish(0)
        self.assertEqual(json.loads(second.path.read_text())["status"], "REJECTED_OVERLAP")
        with journal.WorkflowRun(self.directory, "nightly").exclusive():
            pass

    def test_atomic_state_failure_preserves_previous_watermark(self):
        path = self.directory / "state.json"
        journal.atomic_json(path, {"last_apply_at": "before"})
        with patch.object(journal.os, "replace", side_effect=OSError("disk failure")), self.assertRaises(OSError):
            journal.atomic_json(path, {"last_apply_at": "after"})
        self.assertEqual(json.loads(path.read_text())["last_apply_at"], "before")
        self.assertEqual(list(self.directory.glob(".write-*")), [])

    def test_metrics_preserve_last_success_when_next_run_fails_and_do_not_label_run_id(self):
        for code in (0, 3):
            execution = journal.WorkflowRun(self.directory, "nightly")
            with execution.exclusive(), execution:
                with execution.step("sync_worker.py") as step:
                    step["exit_code"] = code
                execution.finish(code)
        metrics = journal.metrics(self.directory / "runs", "nightly")
        self.assertIn("bbang_dataops_workflow_last_exit_code 3", metrics)
        self.assertIn('bbang_dataops_workflow_observed_runs{outcome="BLOCKED"} 1', metrics)
        self.assertNotIn("last_success_timestamp_seconds 0\n", metrics)
        self.assertNotIn(execution.record["run_id"], metrics)
        self.assertNotIn(str(self.directory), metrics)

    def test_metrics_failure_does_not_change_business_result(self):
        execution = journal.WorkflowRun(self.directory, "apply")
        with patch.dict(os.environ, {"DATAOPS_PUSHGATEWAY_URL": "http://monitor.invalid:9091"}), \
                patch.object(journal, "urlopen", side_effect=OSError("SECRET")):
            with execution.exclusive(), execution:
                execution.finish(0)
        record = json.loads(execution.path.read_text())
        self.assertEqual(record["exit_code"], 0)
        self.assertEqual(record["metrics_publish_status"], "FAILED")
        self.assertNotIn("SECRET", execution.path.read_text())

    def test_real_child_exit_is_recorded(self):
        script = self.directory / "worker.py"
        script.write_text("print('fixture: blocked')\nraise SystemExit(3)\n")
        execution = journal.WorkflowRun(self.directory, "nightly")
        with patch.object(weekly, "LOG_DIR", self.directory), patch.object(weekly, "SCRIPTS", self.directory):
            with execution.exclusive(), execution:
                code, tail = weekly.run_script("worker.py")
                execution.finish(code)
        self.assertEqual(code, 3)
        self.assertEqual(tail, ["fixture: blocked"])
        self.assertEqual(execution.record["steps"][0]["status"], "BLOCKED")

    def test_sigkill_leaves_unresolved_record_and_releases_lock_after_process_exit(self):
        code = """
import sys,time
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from batch_execution import WorkflowRun
execution=WorkflowRun(Path(sys.argv[2]),'nightly')
with execution.exclusive(), execution:
    print('ready',flush=True)
    time.sleep(60)
"""
        process = subprocess.Popen([sys.executable, "-u", "-c", code, str(Path(journal.__file__).parent), str(self.directory)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self._wait_ready(process)
            process.kill()
            process.communicate(timeout=5)
            records, invalid = journal.read_records(self.directory / "runs")
            self.assertEqual(invalid, 0)
            self.assertEqual(records[0]["status"], "RUNNING")
            self.assertIsNone(records[0]["finished_at"])
            with journal.WorkflowRun(self.directory, "nightly").exclusive():
                pass
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)

    def test_parent_sigkill_keeps_lock_while_child_is_alive(self):
        script = self.directory / "worker.py"
        script.write_text("import os,time\nprint('child='+str(os.getpid()),flush=True)\ntime.sleep(60)\n")
        code = """
import sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import weekly_refresh as weekly
from batch_execution import WorkflowRun
weekly.LOG_DIR=weekly.SCRIPTS=Path(sys.argv[2])
execution=WorkflowRun(weekly.LOG_DIR,'nightly')
with execution.exclusive(),execution:
    weekly.run_script('worker.py')
"""
        process = subprocess.Popen([sys.executable, "-u", "-c", code, str(Path(journal.__file__).parent), str(self.directory)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        child = None
        try:
            line = self._wait_ready(process, "child=")
            child = int(line.strip().split("child=")[1])
            process.kill()
            process.wait(timeout=5)
            with self.assertRaises(journal.AlreadyRunning):
                with journal.WorkflowRun(self.directory, "nightly").exclusive():
                    self.fail("orphan child must still hold lock")
        finally:
            if child:
                try:
                    os.kill(child, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)

    def test_sigterm_records_interruption_and_stops_child(self):
        script = self.directory / "worker.py"
        script.write_text("import os,time\nprint('child='+str(os.getpid()),flush=True)\ntime.sleep(60)\n")
        code = """
import sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import weekly_refresh as weekly
from batch_execution import WorkflowRun,termination_signals,Interrupted
weekly.LOG_DIR=weekly.SCRIPTS=Path(sys.argv[2])
execution=WorkflowRun(weekly.LOG_DIR,'nightly')
try:
    with execution.exclusive(),execution,termination_signals():
        weekly.run_script('worker.py')
except Interrupted as error:
    raise SystemExit(error.code)
"""
        process = subprocess.Popen([sys.executable, "-u", "-c", code, str(Path(journal.__file__).parent), str(self.directory)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self._wait_ready(process, "child=")
            process.terminate()
            process.communicate(timeout=10)
            self.assertEqual(process.returncode, 143)
            record = journal.read_records(self.directory / "runs")[0][0]
            self.assertEqual(record["status"], "INTERRUPTED")
            self.assertEqual(record["steps"][0]["status"], "INTERRUPTED")
            with journal.WorkflowRun(self.directory, "nightly").exclusive():
                pass
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)

    def _wait_ready(self, process, marker="ready"):
        # Poll an actual output file descriptor with a deadline, never a blind sleep.
        import select
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            ready, _, _ = select.select([process.stdout], [], [], .1)
            if ready:
                line = process.stdout.readline()
                if marker in line:
                    return line
            if process.poll() is not None:
                self.fail("child exited before readiness")
        self.fail("child did not become ready")


if __name__ == "__main__":
    unittest.main()
