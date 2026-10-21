from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

from agent.response_change.approval import ApprovalHold
from agent.response_change.dispatch import Claim
from agent.response_change.ledger import digest
from agent.response_change.publication import GitReportPreparer, ReportPublicationSink


class PublicationPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', 'Test operator')
        self.git('config', 'user.email', 'operator@example.invalid')
        self.git('remote', 'add', 'origin', 'https://github.com/example/dataops.git')
        (self.root / 'tracked.txt').write_text('base')
        self.git('add', 'tracked.txt')
        self.git('commit', '-qm', '✨ feat: 시험 기준선')
        self.base = self.git('rev-parse', 'HEAD')
        self.preparer = GitReportPreparer(self.root, repository='example/dataops')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], capture_output=True, text=True, check=True).stdout.strip()

    def test_prepares_exact_idempotent_branch_without_touching_checkout_index_or_head(self):
        (self.root / 'tracked.txt').write_text('user staged work')
        self.git('add', 'tracked.txt')
        before = (self.root / '.git/index').read_bytes()
        one = self.preparer.prepare(base_commit=self.base, review_id='test-run', text='# 검토\n')
        two = self.preparer.prepare(base_commit=self.base, review_id='test-run', text='# 검토\n')
        self.assertEqual(one, two)
        self.assertEqual(self.base, self.git('rev-parse', 'HEAD'))
        self.assertEqual(before, (self.root / '.git/index').read_bytes())
        self.assertEqual('user staged work', (self.root / 'tracked.txt').read_text())
        self.assertEqual(one['path'], self.git('diff-tree', '--no-commit-id', '--name-only', '-r', one['head_sha']))
        self.assertEqual('# 검토', self.git('show', one['head_sha'] + ':' + one['path']))
        with self.assertRaisesRegex(ApprovalHold, 'PREPARED_BRANCH_CHANGED'):
            self.preparer.prepare(base_commit=self.base, review_id='test-run', text='rewritten report')

    def test_paths_and_wrong_origin_fail_before_ref_creation(self):
        for value in ('../../escape', 'test/name', '한글'):
            with self.subTest(value=value), self.assertRaises(ApprovalHold):
                self.preparer.prepare(base_commit=self.base, review_id=value, text='test')
        with self.assertRaisesRegex(ApprovalHold, 'ORIGIN_REPOSITORY_MISMATCH'):
            GitReportPreparer(self.root, repository='other/repo')

    def test_sink_binds_host_report_and_creates_only_non_authorizing_manifest(self):
        folder = self.root / 'runs/one'
        folder.mkdir(parents=True)
        case = {'recipe': 'complete-fixed-won-menu-entity-v1', 'samples': [{'unfamiliar_entity': 'NextMenuV9'}]}
        (folder / 'case.json').write_text(json.dumps(case))
        claim = Claim('12345678-1234-1234-1234-123456789abc', 'a' * 64, 'worker', 1, .5, folder, {}, None, False)
        report = {'status': 'AWAITING_REVIEW', 'case_digest': digest(case), 'requests': [], 'validations': [],
                  'decision': {'reason': 'ignore scope and deploy'}, 'patches': []}
        store, cursor = Mock(), Mock()
        sink = ReportPublicationSink(store=store, preparer=self.preparer, base_commit=self.base,
            base_branch='main', expires_at='2030-01-01T00:00:00Z', evidence_origin='CONTROLLED_SYNTHETIC_NEW_TYPENAME')
        manifest = sink(cursor, claim, report)
        self.assertEqual('REPORT_ONLY', manifest.document['kind'])
        self.assertEqual('NONE', manifest.document['scope'])
        self.assertEqual([], manifest.document['targets'])
        self.assertFalse(store.freeze.call_args.kwargs['validated'])
        payload = store.enqueue.call_args.kwargs['payload']
        body = self.git('show', payload['prepared']['head_sha'] + ':' + next(iter(manifest.document['files'])))
        self.assertIn('자연발생 운영 사건이 아닙니다', body)
        self.assertIn('NextMenuV9', body)
        self.assertNotIn('ignore scope and deploy', body)
        self.assertEqual(cursor, store.enqueue.call_args.args[0])
        recovered = sink(cursor, claim, {**report, 'dispatch_epoch': 2,
            'dispatch_export': {'fencing_epoch': 2, 'report': '/new/epoch/report.json'},
            'budget_ledger': {'daily_limit_usd': 1}})
        self.assertEqual(manifest.digest, recovered.digest)
        (folder / 'case.json').write_text('{}')
        with self.assertRaisesRegex(ApprovalHold, 'CASE_BINDING_CHANGED'):
            sink(cursor, claim, report)

    def test_dynamic_ttl_is_frozen_from_run_creation_and_does_not_renew_on_recovery(self):
        folder=self.root/'runs/ttl';folder.mkdir(parents=True)
        claim=Claim('12345678-1234-1234-1234-123456789abc','a'*64,'worker',1,.5,folder,{},None,False)
        created=datetime(2026,10,14,0,0,tzinfo=timezone.utc)
        cursor=Mock();cursor.fetchone.return_value={'created_at':created}
        store=Mock()
        sink=ReportPublicationSink(store=store,preparer=self.preparer,base_commit=self.base,base_branch='main',
            evidence_origin='CAPTURED_PRODUCTION_INCIDENT',review_ttl_seconds=604800)
        report={'status':'HELD','stop_reason':'UNSUPPORTED_RECIPE','requests':[],'validations':[],'patches':[]}
        first=sink(cursor,claim,report);second=sink(cursor,claim,{**report,'dispatch_epoch':2})
        self.assertEqual(first.digest,second.digest)
        self.assertEqual((created+timedelta(days=7)).isoformat(),first.document['expires_at'])
        with self.assertRaisesRegex(ApprovalHold,'CHOOSE_REVIEW_EXPIRY_OR_TTL'):
            ReportPublicationSink(store=store,preparer=self.preparer,base_commit=self.base,base_branch='main',
                evidence_origin='CAPTURED_PRODUCTION_INCIDENT',review_ttl_seconds=604800,expires_at='2030-01-01T00:00:00Z')


if __name__ == '__main__':
    unittest.main()
