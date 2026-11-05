"""Count provenance across failed/repeated runs; no live database or collection."""
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from batch_execution import WorkflowRun, atomic_json, read_records
import freshness_cycle as cycle
import weekly_refresh as weekly

ITEMS = dict(attempted=20, changed=7, unchanged=10, partial=1, held=1, failed=1)


class InvocationCountsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.logs = self.root / 'weekly'
        env = patch.dict(os.environ, {'DATAOPS_INVOCATION_ID': str(uuid4()), 'DATAOPS_PUSHGATEWAY_URL': ''})
        env.start(); self.addCleanup(env.stop)
        log = patch.object(weekly, 'LOG_DIR', self.logs)
        log.start(); self.addCleanup(log.stop)
        self.args = SimpleNamespace(datasets=['bakeries'], queries=['빵집'], max_regions=5,
                                    max_items=20, server='http://127.0.0.1:8090', crawl_hours=.25)

    def invoke_refresh(self, code, items=None):
        def worker(script, *arguments, **kwargs):
            if items is not None:
                summary = Path(arguments[arguments.index('--summary') + 1])
                atomic_json(summary, items)
            return code, []
        with patch.object(weekly, 'run_script', side_effect=worker):
            return cycle.execute_track('refresh', self.root / 'cycle', None, None, self.args, None)

    def test_invocation_prefix_is_validated_and_preserves_journal_reading(self):
        run = WorkflowRun(self.logs, 'nightly')
        with run: run.finish(0)
        identity = os.environ['DATAOPS_INVOCATION_ID']
        self.assertTrue(run.path.name.startswith(identity + '_'))
        self.assertEqual(read_records(self.logs / 'runs')[0][0]['invocation_id'], identity)
        with patch.dict(os.environ, {'DATAOPS_INVOCATION_ID': '../secret'}):
            local = WorkflowRun(self.logs, 'nightly')
            self.assertNotIn('invocation_id', local.record)
            self.assertEqual(local.path.parent, self.logs / 'runs')

    def test_item_failures_override_process_zero_and_failed_retry_has_no_old_counts(self):
        self.assertEqual(self.invoke_refresh(0, ITEMS), 1)
        self.assertEqual(self.invoke_refresh(1), 1)
        records = read_records(self.logs / 'runs')[0]
        self.assertEqual(records[0]['items_current'], ITEMS)
        self.assertNotIn('items_current', records[1])
        self.assertEqual(records[1]['items']['attempted'], 20)  # Explicit cycle artifact only.
        self.assertEqual(records[1]['status'], 'FAILED')

    def test_success_without_a_new_summary_cannot_reuse_old_cycle_result(self):
        self.invoke_refresh(1, ITEMS)
        with self.assertRaisesRegex(ValueError, 'this attempt summary'):
            self.invoke_refresh(0)
        record = read_records(self.logs / 'runs')[0][-1]
        self.assertEqual(record['status'], 'FAILED')
        self.assertNotIn('items_current', record)

    def test_completed_cycle_skips_processing_and_reports_the_skip(self):
        healthy = {**ITEMS, 'failed': 0, 'unchanged': 11}
        self.assertEqual(self.invoke_refresh(0, healthy), 0)
        parent = WorkflowRun(self.logs, 'freshness-cycle')
        with parent, patch.object(weekly, 'run_script') as worker:
            self.assertEqual(cycle.execute_track('refresh', self.root / 'cycle', None, None, self.args, None), 0)
            parent.finish(0)
            worker.assert_not_called()
        self.assertEqual(parent.record['skipped_tracks'], ['refresh'])
        self.assertNotIn('items_current', parent.record)
        self.assertEqual(len(read_records(self.logs / 'runs')[0]), 2)

    def test_legacy_nightly_captures_partial_counts_and_marks_item_failures(self):
        def worker(script, *arguments):
            atomic_json(Path(arguments[arguments.index('--summary') + 1]), ITEMS)
            return 0, []
        execution = WorkflowRun(self.logs, 'nightly')
        with execution, patch.object(weekly, 'run_script', side_effect=worker):
            code, _ = weekly.crawl(self.args.server, .25, 20)
            execution.finish(code)
        self.assertEqual(code, 1)
        self.assertEqual(execution.record['items_current'], ITEMS)

    def test_empty_attempt_is_incomplete_and_not_a_fabricated_success(self):
        empty = {key: 0 for key in ITEMS}
        self.assertEqual(self.invoke_refresh(0, empty), 4)
        record = read_records(self.logs / 'runs')[0][0]
        self.assertEqual(record['status'], 'INCOMPLETE')
        self.assertEqual(record['items_current'], empty)
