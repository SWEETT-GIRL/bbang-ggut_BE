from datetime import datetime
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import freshness_cycle as cycle
from batch_execution import WorkflowRun, metrics, outcome


class FreshnessCycleTest(unittest.TestCase):
    def test_operator_transition_preserves_old_work_and_reason_and_new_cycle_is_not_repeated(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            old = cycle.select_cycle(state, 'public')
            old.mkdir(parents=True)
            (old/'checkpoint.json').write_text('original')
            cycle.start_cycle(state, ['public'], 'scope-002', 'scope change')
            selected = cycle.select_cycle(state, 'public')
            self.assertEqual(selected.name, 'scope-002')
            self.assertEqual((old/'checkpoint.json').read_text(), 'original')
            self.assertEqual(json.loads((selected/'transition.json').read_text())['reason'], 'scope change')
            cycle.finish_cycle(selected, None, 0)
            self.assertEqual(cycle.select_cycle(state, 'public'), selected)
            with self.assertRaisesRegex(ValueError, 'new cycle'):
                cycle.start_cycle(state, ['public'], 'scope-002', 'again')

    def test_unfinished_cycle_survives_month_boundary_and_completed_one_advances(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            november = cycle.select_cycle(state, 'public', now=datetime(2026, 11, 30))
            december = cycle.select_cycle(state, 'public', now=datetime(2026, 12, 1))
            self.assertEqual(november, december)
            cycle.finish_cycle(november, None, 4)
            self.assertEqual(cycle.select_cycle(state, 'public', now=datetime(2026, 12, 2)), november)
            cycle.finish_cycle(november, None, 0)
            self.assertEqual(cycle.select_cycle(state, 'public', now=datetime(2026, 12, 2)).name, '202612')
            manual = cycle.select_cycle(state, 'public', 'smoke')
            cycle.finish_cycle(manual, 'smoke', 0)
            self.assertEqual(json.loads((state/'public/active.json').read_text())['cycle_id'], '202612')

    def test_independent_branches_continue_and_restart_skips_completed_stages(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stages = [cycle.stage('pull','x',[],['pull']), cycle.stage('verify','x',[],['verify'],['pull']),
                      cycle.stage('closures','x',[],['closures'],['pull'])]
            called = []
            def first(node):
                called.append(node['name'])
                if node['name'] == 'verify': return 4
                (root / node['outputs'][0]).write_text('ok')
                return 0
            self.assertEqual(cycle.execute_graph(root, stages, first), 4)
            self.assertEqual(called, ['pull','verify','closures'])
            called.clear()
            def resumed(node):
                called.append(node['name'])
                (root / node['outputs'][0]).write_text('ok')
                return 0
            state = json.loads((root/'checkpoint.json').read_text())
            self.assertEqual(cycle.execute_graph(root, stages, resumed, state), 0)
            self.assertEqual(called, ['verify'])
            (root/'pull').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'artifact changed'):
                cycle.execute_graph(root, stages, resumed, state)

    def test_partial_public_input_blocks_both_downstream_branches(self):
        with tempfile.TemporaryDirectory() as directory:
            stages=[cycle.stage('pull','x',[],[]),cycle.stage('new','x',[],[],['pull']),cycle.stage('closures','x',[],[],['pull'])]
            called=[]
            self.assertEqual(cycle.execute_graph(Path(directory),stages,lambda n: called.append(n['name']) or 3),3)
            self.assertEqual(called,['pull'])

    def test_scope_change_is_rejected_before_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'scope.json').write_text(json.dumps({'datasets':['bakeries']}))
            with self.assertRaisesRegex(ValueError,'scope changed'):
                cycle.prepare(root,root,{}, {'datasets':['general_restaurants']})

    def test_schedule_granularity_and_incomplete_are_explicit(self):
        now=datetime(2026,11,5)
        self.assertEqual(cycle.scheduled_cycle('refresh',now),'20261105')
        self.assertEqual(cycle.scheduled_cycle('public',now),'202611')
        self.assertEqual(cycle.scheduled_cycle('discovery',now),'2026-W45')
        self.assertEqual(outcome(4),'INCOMPLETE')

    def test_metrics_use_fixed_labels_and_incomplete_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict('os.environ',{},clear=True):
            run=WorkflowRun(directory,'freshness-public')
            with run:
                run.record['items']={'public_raw':100, 'store_id_123':1}
                run.finish(4)
            text=metrics(Path(directory)/'runs','freshness-public')
            self.assertIn('result="public_raw"} 100',text)
            self.assertNotIn('store_id_123',text)
            self.assertIn('last_success_timestamp_seconds 0',text)
            self.assertIn('outcome="INCOMPLETE"} 1',text)
