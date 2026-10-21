import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

import psycopg2

from agent.response_change.dispatch import ApprovedBundleCatalog, FrozenBundle, Hold, LostLease, PgDispatcher

ROOT = Path(__file__).resolve().parents[3]
PARSER = 'test-deployed-parser-class-digest'
CONTRACT = 'naver-response-v1'
FINGERPRINT = '1' * 64
RAW = '{"private":"not sent to model","menu":{"price":"5300"}}'
RAW_DIGEST = hashlib.sha256(RAW.encode()).hexdigest()


def bundle():
    return FrozenBundle({'case_id': 'redacted-case', 'before': {'raw': {}}, 'after': {'raw': {}}},
                        'frozen parser', 'immutable test', [{'id': 'contract', 'expected': {}}],
                        PARSER, CONTRACT, (RAW_DIGEST,))


def catalog(bundles=None, **kwargs):
    return ApprovedBundleCatalog({FINGERPRINT: bundle()} if bundles is None else bundles,
                                 current_parser_digest=kwargs.get('parser', PARSER),
                                 contract_version=kwargs.get('contract', CONTRACT))


def incident(**kwargs):
    return dict(fingerprint=FINGERPRINT, source='NAVER', parser_digest=PARSER,
                contract_version=CONTRACT, **kwargs)


def sample(**kwargs):
    result = dict(source='NAVER', parser_digest=PARSER, contract_version=CONTRACT,
                  payload_digest=RAW_DIGEST, payload=RAW)
    result.update(kwargs)
    return result


class CatalogTest(unittest.TestCase):
    def test_approved_case_only_not_raw_private_payload(self):
        result = catalog().prepare(incident(), [sample()])
        self.assertNotIn('not sent to model', json.dumps(result.__dict__))
        self.assertEqual('redacted-case', result.case['case_id'])

    def test_catalog_deep_freezes_inputs_and_returned_values(self):
        original = bundle()
        provider = catalog({FINGERPRINT: original})
        original.case['secret'] = 'changed'
        output = provider.prepare(incident(), [sample()])
        output.case['secret'] = 'changed again'
        self.assertNotIn('secret', provider.prepare(incident(), [sample()]).case)

    def test_new_unsupported_incident_holds(self):
        with self.assertRaisesRegex(Hold, 'APPROVED_REDACTED_CASE_REQUIRED'):
            catalog({}).prepare(incident(), [sample()])

    def test_deployment_digest_is_required_not_merely_source(self):
        with self.assertRaisesRegex(Hold, 'DEPLOYED_PARSER_MISMATCH'):
            catalog(parser='new deployment').prepare(incident(), [sample()])
        with self.assertRaisesRegex(Hold, 'FIELD_CONTRACT_MISMATCH'):
            catalog(contract='new contract').prepare(incident(), [sample()])

    def test_capture_tamper_and_wrong_mapping_are_held(self):
        with self.assertRaisesRegex(Hold, 'CAPTURE_DIGEST_MISMATCH'):
            catalog().prepare(incident(), [sample(payload='tampered')])
        with self.assertRaisesRegex(Hold, 'APPROVED_CAPTURE_NOT_FOUND'):
            catalog().prepare(incident(), [sample(payload=None)])

    def test_mutated_registered_bundle_is_held(self):
        provider = catalog()
        provider.bundles[FINGERPRINT].fixtures[0]['expected']['price'] = 'unsafe'
        with self.assertRaisesRegex(Hold, 'APPROVED_BUNDLE_BINDING_MISMATCH'):
            provider.prepare(incident(), [sample()])


class BudgetIdentityTest(unittest.TestCase):
    def test_identity_excludes_versions_and_normalizes_dynamic_paths(self):
        from agent.response_change.dispatch import _budget_identity
        def identity(path, **changes):
            value = incident(diagnostics=json.dumps([{'path': path, 'code': 'TYPE_CHANGED',
                                                      'actualType': 'PlaceMenuItemV3', 'modelEligible': True}]))
            value.update(changes)
            return _budget_identity(value)
        self.assertEqual(identity('PlaceMenuItem:123.menus({"at":123})[0].price'),
                         identity('PlaceMenuItem:456.menus({"at":999})[5].price',
                                  parser_digest='new', contract_version='new', fingerprint='different'))
        self.assertNotEqual(identity('menus.price'), identity('menus.image'))

    def test_above_approved_limits_are_rejected_without_database(self):
        for options in ({'incident_budget_usd': .51}, {'daily_budget_usd': 2.01}):
            with self.assertRaisesRegex(ValueError, 'may not be increased'):
                PgDispatcher(lambda: None, catalog(), '/tmp/unused-agent-budget', **options)


@unittest.skipUnless(os.getenv('RUN_PG_AGENT_TESTS') == 'true', 'isolated Docker PostgreSQL opt-in')
class PgDispatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'agent-dispatch-test-' + uuid.uuid4().hex[:10]
        subprocess.run(['docker', 'run', '-d', '--name', cls.container,
                        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=agent_dispatch_test',
                        '-p', '127.0.0.1::5432', 'postgres:16-alpine'], check=True, capture_output=True)
        try:
            port = subprocess.check_output(['docker', 'port', cls.container, '5432/tcp'], text=True).strip().rsplit(':', 1)[1]
            cls.dsn = f'host=127.0.0.1 port={port} dbname=agent_dispatch_test user=postgres connect_timeout=2'
            deadline = time.monotonic() + 30
            while True:
                try:
                    with psycopg2.connect(cls.dsn) as db:
                        with db.cursor() as cursor:
                            cursor.execute('SELECT 1')
                    break
                except psycopg2.OperationalError:
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(0.2)
            with psycopg2.connect(cls.dsn) as db:
                with db.cursor() as cursor:
                    for name in ('V20261013100000__response_capture_and_incident.sql',
                                 'V20261014111000__agent_dispatch.sql',
                                 'V20261014160000__agent_cumulative_budget.sql'):
                        cursor.execute((ROOT / 'src/main/resources/db/migration' / name).read_text())
                    cursor.execute('CREATE TABLE serving_sentinel (id INT PRIMARY KEY, value TEXT)')
                    cursor.execute("INSERT INTO serving_sentinel VALUES (1, 'unchanged')")
                    cursor.execute('CREATE ROLE agent_worker LOGIN')
                    cursor.execute('GRANT SELECT ON response_incident,response_incident_sample,response_capture TO agent_worker')
                    cursor.execute('GRANT SELECT,INSERT,UPDATE ON response_agent_run TO agent_worker')
                    cursor.execute('GRANT SELECT ON response_agent_budget_policy TO agent_worker')
                    cursor.execute('GRANT SELECT,INSERT,UPDATE ON response_agent_budget_incident,response_agent_budget_day TO agent_worker')
                    cursor.execute('GRANT SELECT,INSERT ON response_agent_budget_alias,response_agent_budget_request,response_agent_budget_settlement TO agent_worker')
        except BaseException:
            subprocess.run(['docker', 'rm', '-f', cls.container], capture_output=True)
            raise

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.container], check=True, capture_output=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.sql('''TRUNCATE response_agent_budget_settlement,response_agent_budget_request,response_agent_budget_day,
                    response_agent_run,response_agent_budget_alias,response_agent_budget_incident,
                    response_incident_sample,response_incident,response_capture''')
        self.sql('UPDATE response_agent_budget_policy SET legacy_reconciliation_required=FALSE')
        self.dispatch = PgDispatcher(lambda: psycopg2.connect(self.dsn.replace('user=postgres', 'user=agent_worker')),
                                     catalog(), self.temp.name)

    def sql(self, query, params=None):
        with psycopg2.connect(self.dsn) as db:
            with db.cursor() as cursor:
                cursor.execute(query, params)
                return cursor.fetchall() if cursor.description else None

    def seed(self, fingerprint=FINGERPRINT, eligible=True, diagnostics=None):
        capture_id = 'capture-' + fingerprint[:8]
        self.sql('''INSERT INTO response_capture
                 (capture_id,request_digest,store_id,source,payload,payload_digest,parser_digest,contract_version)
                 VALUES (%s,%s,1,'NAVER',%s,%s,%s,%s)''',
                 (capture_id, 'a' * 64, RAW, RAW_DIGEST, PARSER, CONTRACT))
        self.sql('''INSERT INTO response_incident
                 (fingerprint,source,parser_digest,contract_version,diagnostics,model_eligible)
                 VALUES (%s,'NAVER',%s,%s,%s,%s)''', (fingerprint, PARSER, CONTRACT, json.dumps(diagnostics or []), eligible))
        self.sql('INSERT INTO response_incident_sample VALUES (%s,%s)', (fingerprint, capture_id))

    def claim(self, owner='worker-one', budget=.50):
        return self.dispatch.claim(owner=owner, budget_usd=budget)[0]

    def expire(self, claim):
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE run_id=%s",
                 (claim.run_id,))

    def runrow(self, claim):
        return self.sql('SELECT state, checkpoint, budget_usd FROM response_agent_run WHERE run_id=%s', (claim.run_id,))[0]

    def current_dispatch(self):
        from unittest.mock import Mock
        from agent.response_change.tests.test_current_bundle import CurrentBundleTest, sample as current_sample
        fixture = CurrentBundleTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.verify.return_value = {'parser_digest': PARSER}
        original = current_sample(capture_id='capture-' + FINGERPRINT[:8], parser_digest=PARSER, status='HELD')
        prior = current_sample('PlaceMenuItem', capture_id='prior-control', parser_digest=PARSER,
                               observed_at='2026-10-13T01:00:00Z', status='READY')
        self.seed(diagnostics=[{'code': 'MENU_ENTITY_UNSUPPORTED'}])
        self.sql('DELETE FROM response_incident_sample')
        self.sql('DELETE FROM response_capture')
        for row in (original, prior):
            self.insert_current_capture(row)
        self.sql('INSERT INTO response_incident_sample VALUES (%s,%s)', (FINGERPRINT, original['capture_id']))
        normal_loader = Mock(return_value=[prior])
        self.dispatch.provider = fixture.provider(normal_loader=normal_loader)
        return original, prior, normal_loader

    def insert_current_capture(self, row):
        self.sql('''INSERT INTO response_capture
            (capture_id,request_digest,store_id,source,external_id,observed_at,attempt_seq,status,
             payload,payload_digest,parser_digest,contract_version)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)''',
            (row['capture_id'], 'a' * 64, row['store_id'], row['source'], row['external_id'], row['observed_at'],
             row['attempt_seq'], row['status'], row['payload'], row['payload_digest'], row['parser_digest'], row['contract_version']))

    def test_current_frozen_paid_run_resumes_original_sources_despite_added_samples(self):
        from agent.tests.test_recovery import ProcessDeath
        from agent.response_change.tests.test_current_bundle import sample as current_sample
        original, prior, normal_loader = self.current_dispatch()
        before = []
        def first_executor(value, claim, checkpoint):
            self.assertFalse(claim.prior_report, 'freezing must not mutate the initial executor checkpoint')
            before.append(value)
            checkpoint({'status': 'RUNNING', 'requests': [{'reserved_usd': .05, 'cost_usd': .02}],
                        'recovery': {'phase': 'RESPONSE_READY'}, 'saved_response': 'exact response'})
            raise ProcessDeath()
        with self.assertRaises(ProcessDeath):
            self.dispatch.run_one(first_executor, owner='original-worker', budget_usd=.5)
        newer = current_sample(capture_id='capture-000-new', parser_digest=PARSER, status='HELD', attempt_seq=6,
                               observed_at='2026-10-14T02:00:00Z')
        self.insert_current_capture(newer)
        self.sql('INSERT INTO response_incident_sample VALUES (%s,%s)', (FINGERPRINT, newer['capture_id']))
        normal_loader.side_effect = AssertionError('recovery must not reselect prior controls')
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        def resumed(value, claim, checkpoint):
            self.assertEqual(before[0].digest(), value.digest())
            self.assertEqual([original['capture_id']], [row['capture_id'] for row in value.reprocess_targets])
            self.assertEqual('exact response', claim.prior_report['saved_response'])
            return dict(claim.prior_report, status='AWAITING_REVIEW')
        result = self.dispatch.run_one(resumed, owner='resumed-worker', budget_usd=.5)
        self.assertEqual('AWAITING_REVIEW', result['status'])
        self.assertEqual(0, result['new_model_calls'])
        self.assertEqual(1, normal_loader.call_count)
        report = self.sql('SELECT checkpoint FROM response_agent_run')[0][0]
        self.assertIn('frozen_current_bundle', report)
        self.assertEqual(1, len(report['requests']))
        self.assertEqual([('unchanged',)], self.sql('SELECT value FROM serving_sentinel'))

    def test_current_crash_before_runner_initialization_resumes_empty_executor_state(self):
        from agent.tests.test_recovery import ProcessDeath
        self.current_dispatch()
        saved = self.dispatch.checkpoint
        def crash_after_freeze(claim, report, **kwargs):
            saved(claim, report, **kwargs)
            if 'frozen_current_bundle' in report and not report.get('status'):
                raise ProcessDeath()
        self.dispatch.checkpoint = crash_after_freeze
        with self.assertRaises(ProcessDeath):
            self.dispatch.run_one(lambda *args: self.fail('crash must precede executor'), owner='first', budget_usd=.5)
        self.dispatch.checkpoint = saved
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        def resumed(value, claim, checkpoint):
            self.assertTrue(claim.recovered)
            self.assertEqual({}, claim.prior_report)
            return {'status': 'HELD', 'requests': []}
        self.assertEqual('HELD', self.dispatch.run_one(resumed, owner='second', budget_usd=.5)['status'])

    def test_current_frozen_capture_tamper_holds_before_executor(self):
        from agent.tests.test_recovery import ProcessDeath
        original, prior, _ = self.current_dispatch()
        def first(value, claim, checkpoint):
            checkpoint({'status': 'RUNNING', 'requests': []})
            raise ProcessDeath()
        with self.assertRaises(ProcessDeath):
            self.dispatch.run_one(first, owner='first', budget_usd=.5)
        self.sql('UPDATE response_capture SET payload=payload || %s WHERE capture_id=%s', (' ', prior['capture_id']))
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        result = self.dispatch.run_one(lambda *args: self.fail('tampered frozen source must hold'), owner='second', budget_usd=.5)
        self.assertEqual('CAPTURE_DIGEST_MISMATCH', result['reason'])
        self.assertEqual(0, result['new_model_calls'])

    def test_checkpoint_cannot_remove_or_replace_frozen_bundle(self):
        self.seed()
        claim = self.claim()
        self.dispatch.checkpoint(claim, {'requests': [], 'frozen_current_bundle': {'identity': 'fixed'}})
        for report in ({'requests': []}, {'requests': [], 'frozen_current_bundle': {'identity': 'changed'}}):
            with self.subTest(report=report), self.assertRaisesRegex(Hold, 'FROZEN_BUNDLE_CHANGED_DURING_RUN'):
                self.dispatch.checkpoint(claim, report)

    def test_legacy_current_paid_checkpoint_cannot_adopt_a_new_bundle(self):
        self.current_dispatch()
        claim = self.claim()
        self.dispatch.checkpoint(claim, {'status': 'RUNNING', 'requests': [{'reserved_usd': .05, 'cost_usd': .02}]})
        self.expire(claim)
        result = self.dispatch.run_one(lambda *args: self.fail('legacy paid run requires explicit adoption'),
                                       owner='recovery', budget_usd=.5)
        self.assertEqual('FROZEN_BUNDLE_MISSING_REVIEW_REQUIRED', result['reason'])
        self.assertEqual(.02, self.runrow(claim)[1]['requests'][0]['cost_usd'])

    def test_only_model_eligible_incidents_claimed(self):
        self.seed(eligible=False)
        self.assertIsNone(self.dispatch.claim(owner='worker', budget_usd=.50))
        self.assertEqual([], self.sql('SELECT run_id FROM response_agent_run'))

    def test_successful_delivery_and_duplicate_make_only_one_executor_call(self):
        self.seed()
        called = []
        def executor(value, claim, checkpoint):
            called.append(value)
            checkpoint({'status': 'RUNNING', 'requests': []})
            return {'status': 'AWAITING_REVIEW', 'requests': []}
        first = self.dispatch.run_one(executor, owner='worker', budget_usd=.5)
        second = self.dispatch.run_one(executor, owner='worker', budget_usd=.5)
        self.assertEqual('AWAITING_REVIEW', first['status'])
        self.assertEqual('IDLE', second['status'])
        self.assertEqual(1, len(called))
        self.assertEqual([('unchanged',)], self.sql('SELECT value FROM serving_sentinel'))

    def test_unsupported_case_records_hold_without_executor(self):
        self.seed('2' * 64)
        result = self.dispatch.run_one(lambda *args: self.fail('must not invoke'), owner='worker', budget_usd=.5)
        self.assertEqual('APPROVED_REDACTED_CASE_REQUIRED', result['reason'])
        self.assertEqual('HELD', result['status'])
        self.assertEqual(0, result['new_model_calls'])

    def test_concurrent_claims_are_exactly_once(self):
        self.seed()
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda n: self.dispatch.claim(owner=f'worker-{n}', budget_usd=.5), range(8)))
        self.assertEqual(1, sum(result is not None for result in results))

    def test_skip_locked_advances_to_other_incident(self):
        self.seed()
        self.seed('2' * 64)
        self.dispatch.enqueue(budget_usd=.5)
        with psycopg2.connect(self.dsn) as blocker:
            with blocker.cursor() as cursor:
                cursor.execute('SELECT fingerprint FROM response_agent_run WHERE fingerprint=%s FOR UPDATE', (FINGERPRINT,))
                result = self.dispatch.claim(owner='worker', budget_usd=.5)
                self.assertEqual('2' * 64, result[0].fingerprint)

    def test_expired_claim_fences_old_owner_and_preserves_budget(self):
        self.seed()
        old = self.claim(budget=.2)
        self.expire(old)
        with self.assertRaises(LostLease):
            self.dispatch.checkpoint(old, {'requests': []})
        new = self.claim(owner='worker-two', budget=5)
        self.assertEqual(old.run_id, new.run_id)
        self.assertEqual(old.epoch + 1, new.epoch)
        self.assertEqual(.2, new.budget_usd)
        with self.assertRaises(LostLease):
            self.dispatch.checkpoint(old, {'requests': []})
        self.dispatch.checkpoint(new, {'requests': []})

    def test_crash_before_paid_call_can_resume(self):
        self.seed()
        old = self.claim()
        self.dispatch.checkpoint(old, {'status': 'RUNNING', 'requests': [], 'checkpoint_marker': 'durable'})
        self.expire(old)
        def executor(value, claim, checkpoint):
            self.assertTrue(claim.recovered)
            self.assertEqual('durable', claim.prior_report['checkpoint_marker'])
            return dict(claim.prior_report, status='HELD')
        result = self.dispatch.run_one(executor, owner='restart', budget_usd=1)
        self.assertEqual('HELD', result['status'])
        self.assertEqual(0, result['new_model_calls'])

    def test_unknown_billable_call_cannot_retry_and_reservation_persists(self):
        self.seed()
        old = self.claim()
        journal = {'status': 'RUNNING', 'requests': [{'request_id': 'req1', 'state': 'IN_FLIGHT', 'reserved_usd': .08}]}
        self.dispatch.checkpoint(old, journal)
        self.expire(old)
        result = self.dispatch.run_one(lambda *args: self.fail('unresolved call must not retry'), owner='restart', budget_usd=5)
        self.assertEqual('UNRESOLVED_PAID_REQUEST_REQUIRES_RECONCILIATION', result['reason'])
        self.assertEqual(.08, self.runrow(old)[1]['requests'][0]['reserved_usd'])
        self.assertEqual('HELD', self.runrow(old)[0])

    def test_crash_after_terminal_report_recovers_without_executor(self):
        self.seed()
        old = self.claim()
        journal = {'status': 'AWAITING_REVIEW', 'requests': [{'reserved_usd': .08, 'cost_usd': .04}], 'patch_digest': 'fixed'}
        self.dispatch.checkpoint(old, journal)
        self.expire(old)
        result = self.dispatch.run_one(lambda *args: self.fail('terminal report must not rerun'), owner='restart', budget_usd=5)
        self.assertEqual('AWAITING_REVIEW', result['status'])
        self.assertEqual('fixed', self.runrow(old)[1]['patch_digest'])
        self.assertEqual(0, result['new_model_calls'])

    def test_crash_after_received_paid_call_delivers_exact_checkpoint_for_resume(self):
        self.seed()
        old = self.claim()
        journal = {'status': 'RUNNING', 'requests': [{'reserved_usd': .08, 'cost_usd': .04}], 'messages': ['saved']}
        self.dispatch.checkpoint(old, journal)
        self.expire(old)
        def executor(value, claim, checkpoint):
            self.assertEqual(journal, claim.prior_report)
            self.assertEqual(.5, claim.budget_usd)
            return dict(claim.prior_report, status='HELD')
        result = self.dispatch.run_one(executor, owner='restart', budget_usd=50)
        self.assertEqual('HELD', result['status'])
        self.assertEqual(0, result['new_model_calls'])

    def test_budget_reserved_before_call_and_history_cannot_be_reset(self):
        self.seed()
        claim = self.claim(budget=.1)
        with self.assertRaisesRegex(ValueError, 'budget exceeded'):
            self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .11}]})
        pending = {'requests': [{'request_id': 'one', 'reserved_usd': .08, 'state': 'IN_FLIGHT'}]}
        self.dispatch.checkpoint(claim, pending)
        with self.assertRaisesRegex(ValueError, 'erase'):
            self.dispatch.checkpoint(claim, {'requests': []})
        with self.assertRaisesRegex(ValueError, 'rewrite'):
            self.dispatch.checkpoint(claim, {'requests': [{'request_id': 'one', 'reserved_usd': .001}]})
        paid = {'requests': [{'request_id': 'one', 'reserved_usd': .08, 'cost_usd': .03, 'state': 'RECEIVED'}]}
        self.dispatch.checkpoint(claim, paid)
        with self.assertRaisesRegex(ValueError, 'rewrite'):
            self.dispatch.checkpoint(claim, pending)

    def test_changed_bundle_on_restart_is_held(self):
        self.seed()
        old = self.claim()
        self.dispatch.checkpoint(old, {'requests': []}, bundle_digest='0' * 64)
        self.expire(old)
        result = self.dispatch.run_one(lambda *args: self.fail('changed bundle'), owner='restart', budget_usd=.5)
        self.assertEqual('APPROVED_BUNDLE_CHANGED', result['reason'])

    def test_queued_run_uses_lower_current_invocation_budget(self):
        self.seed()
        self.dispatch.enqueue(budget_usd=.5)
        claim = self.claim(budget=.01)
        self.assertEqual(.01, claim.budget_usd)
        self.assertEqual(.01, float(self.runrow(claim)[2]))
        with self.assertRaisesRegex(ValueError, 'budget exceeded'):
            self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .02}]})

    def test_recovery_with_lower_current_cap_holds_without_resetting_history(self):
        self.seed()
        old = self.claim(budget=.5)
        self.dispatch.checkpoint(old, {'requests': [{'reserved_usd': .08, 'cost_usd': .04}]})
        self.expire(old)
        result = self.dispatch.run_one(lambda *args: self.fail('lower resumed budget must not call'),
                                      owner='lower-budget', budget_usd=.01)
        self.assertEqual('LOWER_RESUME_BUDGET_REQUIRES_REVIEW', result['reason'])
        row = self.runrow(old)
        self.assertEqual(.5, float(row[2]))
        self.assertEqual(.04, row[1]['requests'][0]['cost_usd'])

    def test_terminal_checkpoint_recovers_missing_review_files_from_db_without_model(self):
        self.seed()
        old = self.claim()
        report = {'status': 'AWAITING_REVIEW', 'requests': [{'reserved_usd': .08, 'cost_usd': .04}],
                  'case_id': 'frozen-case', 'capture_payload_digests': [RAW_DIGEST], 'proof': 'immutable-validation'}
        self.dispatch.checkpoint(old, report)
        old.run_directory.mkdir()
        (old.run_directory / 'report.json').write_text('{"status":"RUNNING"}')
        self.expire(old)
        result = self.dispatch.run_one(lambda *args: self.fail('terminal export needs no model'),
                                      owner='recover-artifacts', budget_usd=.5)
        export = Path(result['report'])
        self.assertEqual('dispatch-epoch-2', export.parent.name)
        saved = json.loads(export.read_text())
        self.assertEqual('AWAITING_REVIEW', saved['status'])
        self.assertEqual('immutable-validation', saved['proof'])
        self.assertTrue((export.parent / 'dispatch-review.md').is_file())
        self.assertEqual([RAW_DIGEST], json.loads((export.parent / 'reprocess-targets.json').read_text())['source_digests'])
        # A delayed old worker cannot overwrite the new authoritative epoch export.
        (old.run_directory / 'report.json').write_text('{"status":"RUNNING"}')
        self.assertEqual('AWAITING_REVIEW', json.loads(export.read_text())['status'])

    def test_terminal_artifact_failure_leaves_completed_db_checkpoint_recoverable(self):
        self.seed()
        original = self.dispatch._persist_terminal
        def cannot_write(*args):
            raise OSError('disk temporarily unavailable')
        self.dispatch._persist_terminal = cannot_write
        with self.assertRaises(OSError):
            self.dispatch.run_one(lambda *args: {'status': 'AWAITING_REVIEW', 'requests': []},
                                  owner='worker', budget_usd=.5)
        row = self.sql('SELECT state,checkpoint FROM response_agent_run')[0]
        self.assertEqual('RUNNING', row[0])
        self.assertEqual('AWAITING_REVIEW', row[1]['status'])
        self.dispatch._persist_terminal = original
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        result = self.dispatch.run_one(lambda *args: self.fail('do not repeat after export failure'),
                                      owner='recovery', budget_usd=.5)
        self.assertEqual('AWAITING_REVIEW', result['status'])
        self.assertTrue(Path(result['report']).is_file())

    def test_executor_error_keeps_newly_reserved_cost_and_no_exception_text(self):
        self.seed()
        def executor(value, claim, checkpoint):
            checkpoint({'status': 'RUNNING', 'requests': [{'reserved_usd': .08}]})
            raise RuntimeError('sensitive SDK token must not be persisted')
        result = self.dispatch.run_one(executor, owner='worker', budget_usd=.5)
        self.assertEqual('HELD', result['status'])
        self.assertEqual(1, result['new_model_calls'])
        rows = self.sql('SELECT checkpoint FROM response_agent_run')
        self.assertEqual(.08, rows[0][0]['requests'][0]['reserved_usd'])
        self.assertNotIn('sensitive SDK token', json.dumps(rows))

    def test_executor_hold_keeps_received_request_checkpoint(self):
        self.seed()
        def executor(value, claim, checkpoint):
            checkpoint({'status': 'RUNNING', 'requests': [{'reserved_usd': .08, 'cost_usd': .04}]})
            raise Hold('FIXED_CONTRACT_FAILED')
        result = self.dispatch.run_one(executor, owner='worker', budget_usd=.5)
        self.assertEqual('HELD', result['status'])
        self.assertEqual('FIXED_CONTRACT_FAILED', result['reason'])
        self.assertEqual(.04, self.sql('SELECT checkpoint FROM response_agent_run')[0][0]['requests'][0]['cost_usd'])

    def scripted_dispatch(self):
        from agent.tests.test_runtime import CASE, SOURCE, FIXTURES, validation
        from agent.response_change.ledger import atomic_json, digest
        value = FrozenBundle(CASE, SOURCE, 'legacy tests', FIXTURES, PARSER, CONTRACT, (RAW_DIGEST,))
        self.dispatch.provider = catalog({FINGERPRINT: value})
        self.seed()
        def validator(source, fixtures, directory, **kwargs):
            directory.mkdir(parents=True)
            if source == SOURCE:
                result = {'passed': False, 'status': 'ASSERTION_FAILED',
                          'cases': [{'id': 'actual-legacy-before', 'passed': True},
                                    {'id': 'actual-format-after', 'passed': False}],
                          'fixed_regression': {'succeeded': 11}}
            else:
                result = validation()
            result.update(source_digest=digest(source), fixtures_digest=digest(fixtures))
            atomic_json(directory / 'validation.json', result)
            return result
        return validator

    def test_real_runner_postgres_response_boundary_crash_resumes_without_paid_repeat(self):
        from agent.response_change.worker_cli import make_executor
        from agent.tests.test_runtime import repair_model
        from agent.tests.test_recovery import ProcessDeath
        validator = self.scripted_dispatch()
        model = repair_model()
        executor = make_executor(lambda: model, validator=validator)
        original_checkpoint = self.dispatch.checkpoint
        killed = False
        def crash_checkpoint(claim, report, **kwargs):
            nonlocal killed
            original_checkpoint(claim, report, **kwargs)
            if not killed and report.get('recovery', {}).get('phase') == 'RESPONSE_READY':
                killed = True
                raise ProcessDeath()
        self.dispatch.checkpoint = crash_checkpoint
        with self.assertRaises(ProcessDeath):
            self.dispatch.run_one(executor, owner='before-crash', budget_usd=.5)
        self.dispatch.checkpoint = original_checkpoint
        self.assertEqual(1, model.calls)
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        result = self.dispatch.run_one(executor, owner='after-crash', budget_usd=5)
        self.assertEqual('AWAITING_REVIEW', result['status'])
        self.assertEqual(6, model.calls, 'committed first response must not incur a second paid call')
        row = self.sql('SELECT checkpoint,fencing_epoch,budget_usd FROM response_agent_run')[0]
        self.assertEqual(6, len(row[0]['requests']))
        self.assertEqual(7, len(row[0]['tools']))
        self.assertEqual(1, row[0]['recovery_count'])
        self.assertEqual(2, row[1])
        self.assertEqual(.5, float(row[2]))
        self.assertEqual('IDLE', self.dispatch.run_one(executor, owner='duplicate', budget_usd=.5)['status'])
        self.assertEqual(6, model.calls)

    def test_real_runner_postgres_uncertain_paid_request_is_not_reissued(self):
        from agent.response_change.worker_cli import make_executor
        from agent.tests.test_runtime import repair_model
        from agent.tests.test_recovery import ProcessDeath
        validator = self.scripted_dispatch()
        model = repair_model()
        executor = make_executor(lambda: model, validator=validator)
        original_checkpoint = self.dispatch.checkpoint
        def crash_checkpoint(claim, report, **kwargs):
            original_checkpoint(claim, report, **kwargs)
            if report.get('recovery', {}).get('phase') == 'MODEL_IN_FLIGHT':
                raise ProcessDeath()
        self.dispatch.checkpoint = crash_checkpoint
        with self.assertRaises(ProcessDeath):
            self.dispatch.run_one(executor, owner='before-crash', budget_usd=.5)
        self.dispatch.checkpoint = original_checkpoint
        self.sql("UPDATE response_agent_run SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        result = self.dispatch.run_one(lambda *args: self.fail('uncertain request reached executor'),
                                      owner='after-crash', budget_usd=5)
        self.assertEqual('HELD', result['status'])
        self.assertEqual('UNRESOLVED_PAID_REQUEST_REQUIRES_RECONCILIATION', result['reason'])
        report = self.sql('SELECT checkpoint FROM response_agent_run')[0][0]
        self.assertEqual(1, len(report['requests']))
        self.assertGreater(report['unpriced_reserved_usd'], 0)
        self.assertEqual(0, model.calls)

    def test_real_historical_payload_reaches_runner_through_pg_using_only_redacted_case(self):
        from scripts.run_response_agent_pg_replay import historical_bundle, seed_historical
        from agent.response_change.worker_cli import make_executor
        from agent.response_change.ledger import atomic_json, digest
        from agent.response_change.runtime import PARSER as PARSER_PATH, LEGACY_TEST
        from agent.tests.test_runtime import ScriptedModel, call, response
        directory = ROOT / 'artifacts/response-agent/historical-menu-case-v2'
        saved = ROOT / 'artifacts/response-agent/preflight/saved-sample.jsonl'
        if not directory.is_dir() or not saved.is_file():
            self.skipTest('private historical raw/fixture bundle is local-only')
        value, fingerprint, payload, snapshot_id = historical_bundle(directory, saved)
        seed_historical(self.dsn, value, fingerprint, payload)
        self.dispatch.provider = ApprovedBundleCatalog({fingerprint: value},
            current_parser_digest=value.deployment_parser_digest, contract_version=value.contract_version)
        model = ScriptedModel([
            response([call('get_case', {'case_id': value.case['case_id']}),
                      call('read_file', {'path': PARSER_PATH}), call('read_file', {'path': LEGACY_TEST})]),
            response([call('finish_analysis', {'disposition': 'hold', 'reason': 'Scripted routing test only',
                      'evidence_refs': [value.case['case_id'], PARSER_PATH, LEGACY_TEST]})])])
        def fixed_baseline(source, fixtures, output, **kwargs):
            output.mkdir(parents=True)
            result = {'passed': False, 'status': 'ASSERTION_FAILED', 'source_digest': digest(source),
                      'fixtures_digest': digest(fixtures), 'fixed_regression': {'succeeded': 11},
                      'cases': [{'id': 'actual-legacy-before', 'passed': True},
                                {'id': 'actual-format-after', 'passed': False}]}
            atomic_json(output / 'validation.json', result)
            return result
        result = self.dispatch.run_one(make_executor(lambda: model, validator=fixed_baseline),
                                      owner='real-historical-scripted-routing', budget_usd=.5)
        self.assertEqual('HELD', result['status'])
        self.assertEqual(2, model.calls)
        report = self.sql('SELECT checkpoint FROM response_agent_run')[0][0]
        self.assertEqual(value.case['case_id'], report['tools'][0]['result']['case_id'])
        self.assertEqual('CLEAN', report['routing_quality'])
        self.assertEqual(digest(value.case), report['case_digest'])
        self.assertNotEqual(json.loads(payload), report['tools'][0]['result']['after']['raw'])
        self.assertTrue(report['deployment_parser_digest'].startswith('isolated-historical:'))
        self.assertEqual('IDLE', self.dispatch.run_one(lambda *args: self.fail('duplicate model call'),
            owner='duplicate', budget_usd=.5)['status'])

    def test_two_run_revisions_share_incident_cap_under_concurrent_reservation(self):
        self.seed()
        self.seed('2' * 64)
        first, second = self.claim(), self.claim(owner='other-worker')
        self.assertEqual(first.budget_incident_id, second.budget_incident_id)
        def reserve(claim):
            try:
                self.dispatch.checkpoint(claim, {'requests': [{'request_id': 'one', 'reserved_usd': .3}]})
                return 'reserved'
            except Hold as error:
                return str(error)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(reserve, [first, second]))
        self.assertCountEqual(['reserved', 'INCIDENT_BUDGET_EXHAUSTED'], outcomes)
        self.assertEqual(.3, float(self.sql('SELECT SUM(reserved_usd) FROM response_agent_budget_request')[0][0]))

    def test_distinct_incidents_share_atomic_daily_cap_and_lower_limit_persists(self):
        self.dispatch.daily_budget = __import__('decimal').Decimal('.5')
        self.seed(diagnostics=[{'path': 'menus.price', 'code': 'TYPE', 'actualType': 'object'}])
        self.seed('2' * 64, diagnostics=[{'path': 'menus.image', 'code': 'TYPE', 'actualType': 'array'}])
        first, second = self.claim(), self.claim(owner='other-worker')
        self.assertNotEqual(first.budget_incident_id, second.budget_incident_id)
        def reserve(claim):
            try:
                self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .3}]})
                return 'reserved'
            except Hold as error:
                return str(error)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(reserve, [first, second]))
        self.assertCountEqual(['reserved', 'DAILY_BUDGET_EXHAUSTED'], outcomes)
        self.dispatch.daily_budget = __import__('decimal').Decimal('2')
        loser = first if not self.runrow(first)[1].get('requests') else second
        with self.assertRaisesRegex(Hold, 'DAILY_BUDGET_EXHAUSTED'):
            self.dispatch.checkpoint(loser, {'requests': [{'reserved_usd': .3}]})

    def test_five_different_incidents_cannot_race_past_two_dollar_day(self):
        for index in range(1, 6):
            self.seed(str(index) * 64,
                      diagnostics=[{'path': 'menus.price', 'code': 'TYPE', 'actualType': 'shape-' + str(index)}])
        claims = [self.claim(owner='worker-' + str(index)) for index in range(5)]
        def reserve(claim):
            try:
                self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .5}]})
                return 'reserved'
            except Hold as error:
                return str(error)
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            outcomes = list(pool.map(reserve, claims))
        self.assertCountEqual(['reserved'] * 4 + ['DAILY_BUDGET_EXHAUSTED'], outcomes)
        self.assertEqual(2, float(self.sql('SELECT SUM(reserved_usd) FROM response_agent_budget_request')[0][0]))

    def test_bound_run_cannot_reset_identity_or_raise_stored_limits(self):
        self.seed()
        claim = self.claim()
        self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .1}]})
        with self.assertRaisesRegex(psycopg2.Error, 'binding is immutable'):
            self.sql('UPDATE response_agent_run SET budget_incident_id=NULL')
        self.sql('UPDATE response_agent_budget_incident SET budget_usd=.2')
        with self.assertRaisesRegex(psycopg2.Error, 'increase requires'):
            self.sql('UPDATE response_agent_budget_incident SET budget_usd=.5')
        self.sql('UPDATE response_agent_budget_day SET budget_usd=1')
        with self.assertRaisesRegex(psycopg2.Error, 'increase requires'):
            self.sql('UPDATE response_agent_budget_day SET budget_usd=2')

    def test_unknown_terminal_reservation_still_counts_in_new_run(self):
        self.seed()
        first = self.claim()
        self.dispatch.finish(first, {'status': 'HELD', 'requests': [{'reserved_usd': .4}]})
        self.seed('2' * 64)
        second = self.claim(owner='revision')
        with self.assertRaisesRegex(Hold, 'INCIDENT_BUDGET_EXHAUSTED'):
            self.dispatch.checkpoint(second, {'requests': [{'reserved_usd': .2}]})
        self.assertEqual(1, self.sql('SELECT COUNT(*) FROM response_agent_budget_request')[0][0])

    def test_unknown_prior_run_cannot_retry_even_with_remaining_incident_budget(self):
        self.seed()
        first = self.claim()
        self.dispatch.finish(first, {'status': 'HELD', 'requests': [{'reserved_usd': .1}]})
        self.seed('2' * 64)
        second = self.claim(owner='revision')
        with self.assertRaisesRegex(Hold, 'UNRESOLVED_PAID_REQUEST_REQUIRES_RECONCILIATION'):
            self.dispatch.checkpoint(second, {'requests': [{'reserved_usd': .1}]})
        self.assertEqual(1, self.sql('SELECT COUNT(*) FROM response_agent_budget_request')[0][0])

    def test_new_run_budget_exhaustion_finishes_held_with_no_unreserved_call(self):
        self.seed()
        first = self.claim()
        self.dispatch.finish(first, {'status': 'HELD', 'requests': [{'reserved_usd': .4}]})
        self.seed('2' * 64)
        self.dispatch.provider = catalog({'2' * 64: bundle()})
        external_calls = []
        def executor(value, claim, checkpoint):
            checkpoint({'status': 'RUNNING', 'requests': [{'reserved_usd': .2}]})
            external_calls.append('must never reach invocation')
        result = self.dispatch.run_one(executor, owner='revision', budget_usd=.5)
        self.assertEqual('HELD', result['status'])
        self.assertEqual('INCIDENT_BUDGET_EXHAUSTED', result['reason'])
        self.assertEqual(0, result['new_model_calls'])
        self.assertEqual([], external_calls)

    def test_settlement_releases_unused_reservation_for_same_incident(self):
        self.seed()
        first = self.claim()
        self.dispatch.checkpoint(first, {'requests': [{'reserved_usd': .4}]})
        self.dispatch.finish(first, {'status': 'HELD', 'requests': [{'reserved_usd': .4, 'cost_usd': .1}]})
        self.seed('2' * 64)
        second = self.claim(owner='revision')
        self.dispatch.checkpoint(second, {'requests': [{'reserved_usd': .4}]})
        self.assertEqual(2, self.sql('SELECT COUNT(*) FROM response_agent_budget_request')[0][0])
        self.assertEqual(1, self.sql('SELECT COUNT(*) FROM response_agent_budget_settlement')[0][0])

    def test_reserved_day_uses_kst_and_late_settlement_stays_original_day(self):
        self.seed()
        claim = self.claim()
        # Seed a pre-midnight durable reservation exactly as a prior process did.
        report = {'requests': [{'request_id': 'old', 'reserved_usd': .4}]}
        self.sql("INSERT INTO response_agent_budget_day VALUES (DATE '2026-10-13',2,CURRENT_TIMESTAMP)")
        self.sql("""INSERT INTO response_agent_budget_request
            (run_id,request_id,budget_incident_id,budget_day_kst,reserved_usd,reservation)
            VALUES (%s,'old',%s,DATE '2026-10-13',.4,%s::jsonb)""",
            (claim.run_id, claim.budget_incident_id, json.dumps(report['requests'][0])))
        self.sql('UPDATE response_agent_run SET checkpoint=%s::jsonb WHERE run_id=%s', (json.dumps(report), claim.run_id))
        report['requests'][0]['cost_usd'] = .1
        self.dispatch.checkpoint(claim, report)
        report['requests'].append({'request_id': 'new', 'reserved_usd': .1})
        self.dispatch.checkpoint(claim, report)
        rows = self.sql("""SELECT request_id,budget_day_kst,
            budget_day_kst=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
            FROM response_agent_budget_request ORDER BY request_id""")
        self.assertEqual('2026-10-13', str(rows[1][1]))
        self.assertTrue(rows[0][2])
        self.assertEqual(.1, float(self.sql("""SELECT s.cost_usd FROM response_agent_budget_settlement s
            JOIN response_agent_budget_request r USING(run_id,request_id)
            WHERE r.budget_day_kst=DATE '2026-10-13'""")[0][0]))
        self.assertEqual([('2026-10-13', '2026-10-14')], self.sql("""SELECT
            ((TIMESTAMPTZ '2026-10-13 14:59:59+00' AT TIME ZONE 'Asia/Seoul')::date)::text,
            ((TIMESTAMPTZ '2026-10-13 15:00:00+00' AT TIME ZONE 'Asia/Seoul')::date)::text"""))

    def test_cost_overrun_is_recorded_and_blocks_further_calls(self):
        self.seed()
        claim = self.claim()
        self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .1}]})
        self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .1, 'cost_usd': .6}]})
        self.assertEqual(.6, float(self.sql('SELECT cost_usd FROM response_agent_budget_settlement')[0][0]))
        with self.assertRaises(Hold):
            self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .1, 'cost_usd': .6}, {'reserved_usd': .01}]})

    def test_paid_legacy_migration_holds_all_new_paid_calls(self):
        self.seed()
        self.sql('UPDATE response_agent_budget_policy SET legacy_reconciliation_required=TRUE')
        claim = self.claim()
        self.dispatch.checkpoint(claim, {'requests': []})
        with self.assertRaisesRegex(Hold, 'LEGACY_BUDGET_RECONCILIATION_REQUIRED'):
            self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .01}]})
        self.assertEqual(0, self.sql('SELECT COUNT(*) FROM response_agent_budget_request')[0][0])

    def test_migration_detects_existing_paid_checkpoint_without_guessing_its_day(self):
        schema = 'legacy_' + uuid.uuid4().hex
        with psycopg2.connect(self.dsn) as db:
            with db.cursor() as cursor:
                cursor.execute('CREATE SCHEMA ' + schema)
                cursor.execute('SET LOCAL search_path TO ' + schema)
                for name in ('V20261013100000__response_capture_and_incident.sql',
                             'V20261014111000__agent_dispatch.sql'):
                    cursor.execute((ROOT / 'src/main/resources/db/migration' / name).read_text())
                cursor.execute("""INSERT INTO response_incident
                    VALUES (%s,'NAVER',%s,%s,'[]',TRUE,CURRENT_TIMESTAMP)""", (FINGERPRINT, PARSER, CONTRACT))
                cursor.execute("""INSERT INTO response_agent_run
                    (run_id,fingerprint,parser_digest,contract_version,state,owner,lease_until,budget_usd,run_directory,checkpoint)
                    VALUES (%s,%s,%s,%s,'HELD','legacy',CURRENT_TIMESTAMP,.5,'/legacy',%s::jsonb)""",
                    (str(uuid.uuid4()), FINGERPRINT, PARSER, CONTRACT,
                     json.dumps({'requests': [{'reserved_usd': .1, 'cost_usd': .05}]})))
                cursor.execute((ROOT / 'src/main/resources/db/migration/V20261014160000__agent_cumulative_budget.sql').read_text())
                cursor.execute('SELECT legacy_reconciliation_required FROM response_agent_budget_policy')
                self.assertTrue(cursor.fetchone()[0])
                cursor.execute('SELECT COUNT(*) FROM response_agent_budget_request')
                self.assertEqual(0, cursor.fetchone()[0], 'migration must not assign old cost to migration day')
                cursor.execute('DROP SCHEMA ' + schema + ' CASCADE')

    def test_empty_legacy_queued_run_is_adopted_without_resetting_budget(self):
        self.seed()
        run_id = str(uuid.uuid4())
        self.sql("""INSERT INTO response_agent_run
            (run_id,fingerprint,parser_digest,contract_version,state,owner,lease_until,budget_usd,run_directory)
            VALUES (%s,%s,%s,%s,'QUEUED','legacy',CURRENT_TIMESTAMP,.1,%s)""",
            (run_id, FINGERPRINT, PARSER, CONTRACT, str(Path(self.temp.name) / run_id)))
        claim = self.claim()
        self.assertEqual(run_id, claim.run_id)
        self.assertEqual(.1, claim.budget_usd)
        self.assertIsNotNone(claim.budget_incident_id)
        self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .05}]})

    def test_billing_context_and_decimal_rounding_remain_auditable(self):
        self.seed()
        claim = self.claim()
        report = {'model': 'test-sonnet', 'price_checked_date': '2026-10-14',
                  'pricing_version': 'test-rates-v1', 'requests': [{'reserved_usd': .10000000001}]}
        self.dispatch.checkpoint(claim, report)
        report['requests'][0]['cost_usd'] = .05000000001
        self.dispatch.checkpoint(claim, report)
        self.dispatch.checkpoint(claim, report)
        reservation, reserved = self.sql('SELECT reservation,reserved_usd FROM response_agent_budget_request')[0]
        self.assertEqual('test-rates-v1', reservation['billing_context']['pricing_version'])
        self.assertEqual('test-sonnet', reservation['billing_context']['model'])
        self.assertEqual(.10000001, float(reserved))
        self.assertEqual(.05000001, float(self.sql('SELECT cost_usd FROM response_agent_budget_settlement')[0][0]))
        self.assertEqual(.5, report['budget_ledger']['incident_limit_usd'])
        self.assertEqual(claim.budget_incident_id, report['budget_ledger']['budget_incident_id'])

    def test_budget_journals_and_aliases_cannot_be_modified_even_by_owner(self):
        self.seed()
        claim = self.claim()
        self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .1, 'cost_usd': .05}]})
        for table in ('response_agent_budget_request', 'response_agent_budget_settlement', 'response_agent_budget_alias'):
            with self.assertRaisesRegex(psycopg2.Error, 'immutable'):
                self.sql('DELETE FROM ' + table)
        for query in ('UPDATE response_agent_budget_request SET reserved_usd=0',
                      'DELETE FROM response_agent_budget_settlement',
                      'UPDATE response_agent_budget_policy SET legacy_reconciliation_required=FALSE'):
            with self.assertRaises(psycopg2.errors.InsufficientPrivilege):
                with self.dispatch.transaction() as cursor:
                    cursor.execute(query)

    def test_confirmed_snapshot_cannot_bypass_reservation_cap(self):
        self.seed()
        claim = self.claim(budget=.1)
        with self.assertRaisesRegex(Hold, 'budget exceeded'):
            self.dispatch.checkpoint(claim, {'requests': [{'reserved_usd': .2, 'cost_usd': .01}]})
        self.assertEqual(0, self.sql('SELECT COUNT(*) FROM response_agent_budget_request')[0][0])

    def test_worker_role_cannot_write_capture_or_serving(self):
        for query in ("UPDATE response_capture SET payload='changed'",
                      "UPDATE response_incident SET model_eligible=FALSE",
                      "UPDATE serving_sentinel SET value='changed'"):
            with self.assertRaises(psycopg2.errors.InsufficientPrivilege):
                with self.dispatch.transaction() as cursor:
                    cursor.execute(query)

    def test_budget_required_and_finite(self):
        for invalid in (0, -1, float('inf'), float('nan'), True):
            with self.assertRaises(ValueError):
                self.dispatch.claim(owner='worker', budget_usd=invalid)


if __name__ == '__main__':
    unittest.main()
