"""Disposable PostgreSQL queue tests; injected adapters make no operating writes."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import psycopg2
from psycopg2.extras import Json

from agent.response_change.approval import (ApprovalEvidence, ApprovalHold, PgReviewStore, PublicationBinding,
    ReviewManifest, content_digest, code_tree_digest, render_data_manifest, render_data_preview)
from agent.response_change.orchestration import (CURRENT_SCOPE, Orchestrator, PgOrchestrationStore, VerifiedMergeResolver, key)
from agent.response_change.orchestration_cli import configuration, main
from agent.tests.test_approval import manifest_document, RUN, REPO, SHA

ROOT = Path(__file__).resolve().parents[3]


class PgOrchestrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'agent-orchestration-' + uuid.uuid4().hex[:10]
        try:
            subprocess.run(['docker','run','--rm','-d','--name',cls.container,'-e','POSTGRES_HOST_AUTH_METHOD=trust',
                '-e','POSTGRES_DB=orchestration_test','-p','127.0.0.1::5432','postgres:16-alpine'],check=True,capture_output=True,timeout=30)
            port = subprocess.check_output(['docker','port',cls.container,'5432/tcp'],text=True).strip().rsplit(':',1)[1]
            cls.dsn = f'host=127.0.0.1 port={port} dbname=orchestration_test user=postgres connect_timeout=2'
            for _ in range(50):
                try:
                    with psycopg2.connect(cls.dsn): break
                except psycopg2.OperationalError: time.sleep(.2)
            else: raise RuntimeError('isolated PostgreSQL unavailable')
            with psycopg2.connect(cls.dsn) as connection, connection.cursor() as cursor:
                cursor.execute('CREATE ROLE agent_orchestrator LOGIN')
        except (OSError,subprocess.SubprocessError,RuntimeError,psycopg2.Error):
            subprocess.run(['docker','rm','-f',cls.container],capture_output=True)
            raise unittest.SkipTest('disposable PostgreSQL unavailable') from None

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker','rm','-f',cls.container],capture_output=True)

    def admin(self): return psycopg2.connect(self.dsn,options='-c search_path='+self.schema)
    def connect(self): return psycopg2.connect(self.dsn.replace('user=postgres','user=agent_orchestrator'),options='-c search_path='+self.schema)

    def setUp(self):
        self.schema = 'case_' + uuid.uuid4().hex
        with psycopg2.connect(self.dsn) as connection, connection.cursor() as cursor:
            cursor.execute('CREATE SCHEMA '+self.schema)
        with self.admin() as connection, connection.cursor() as cursor:
            cursor.execute('''CREATE TABLE response_agent_run(run_id uuid PRIMARY KEY,state text,checkpoint jsonb,created_at timestamptz DEFAULT CURRENT_TIMESTAMP);
                CREATE TABLE response_capture(capture_id text,payload text);
                CREATE TABLE change_proposal(id bigint,value text);
                CREATE TABLE serving_sentinel(id int,value text);
                INSERT INTO serving_sentinel VALUES (1,'unchanged')''')
            for migration in ('V20261014170000__agent_review_outbox.sql','V20261014190000__agent_release_journal.sql',
                              'V20261014250000__agent_orchestration.sql','V20261014260000__agent_status_notification.sql'):
                cursor.execute((ROOT/'src/main/resources/db/migration'/migration).read_text())
            cursor.execute('GRANT USAGE ON SCHEMA '+self.schema+' TO agent_orchestrator')
            cursor.execute('GRANT SELECT ON ALL TABLES IN SCHEMA '+self.schema+' TO agent_orchestrator')
            cursor.execute('GRANT INSERT,UPDATE ON response_agent_orchestration_job TO agent_orchestrator')
            cursor.execute('GRANT INSERT ON response_agent_orchestration_event TO agent_orchestrator')
            cursor.execute('GRANT INSERT ON response_agent_status_notification TO agent_orchestrator')
            cursor.execute('GRANT INSERT,UPDATE ON response_agent_review_manifest,response_agent_notification_outbox TO agent_orchestrator')
        self.store = PgOrchestrationStore(self.connect,'service')
        self.candidate,self.release,self.data,self.reviews,self.resolver,self.promoter = (Mock() for _ in range(6))
        self.candidate.prepare.return_value = {'outcome':'PREPARED','manifest_digest':SHA,'validation_digest':SHA}
        self.runner = Orchestrator(store=self.store,candidate=self.candidate,release=self.release,data=self.data,
            reviews=self.reviews,merge_resolver=self.resolver,promoter=self.promoter,poll_seconds=60)

    def run_row(self, run=RUN, scope=CURRENT_SCOPE, state='AWAITING_REVIEW'):
        with self.admin() as connection, connection.cursor() as cursor:
            cursor.execute('INSERT INTO response_agent_run(run_id,state,checkpoint) VALUES (%s,%s,%s) ON CONFLICT DO NOTHING',
                (run,state,Json({'scope':scope,'frozen_current_bundle':{}})))

    def published(self, kind='PARSER_REPAIR'):
        self.run_row(state='HELD')
        manifest = ReviewManifest(manifest_document(kind))
        with self.admin() as connection, connection.cursor() as cursor:
            store=PgReviewStore(self.admin)
            store.freeze(cursor,manifest,validated=kind!='REPORT_ONLY')
            store.bind_publication(cursor,PublicationBinding(REPO,21 if kind=='REPORT_ONLY' else 20,'b'*40,manifest.digest))
        return manifest

    def due(self):
        with self.admin() as connection, connection.cursor() as cursor:
            cursor.execute("UPDATE response_agent_orchestration_job SET next_attempt_at=CURRENT_TIMESTAMP-interval '1 second'")

    def rows(self, sql, parameters=()):
        with self.admin() as connection, connection.cursor() as cursor:
            cursor.execute(sql,parameters)
            return cursor.fetchall()

    def test_completed_current_run_prepares_once_and_legacy_report_has_no_authority(self):
        self.run_row()
        self.run_row(str(uuid.uuid4()),'historical evaluation')
        self.published('REPORT_ONLY')
        result=self.runner.tick()
        self.assertEqual('DONE',result['state']);self.assertEqual('PREPARE_PARSER',result['kind'])
        self.assertEqual('IDLE',self.runner.tick()['state']);self.candidate.prepare.assert_called_once_with(RUN)
        self.release.advance.assert_not_called();self.data.advance.assert_not_called()
        self.assertEqual([('START',),('FINISH',)],self.rows('SELECT event FROM response_agent_orchestration_event ORDER BY sequence'))

    def test_concurrent_tick_serializes_one_effect_and_preserves_input(self):
        self.run_row();entered=threading.Event();release=threading.Event()
        def prepare(run):
            entered.set();self.assertTrue(release.wait(5));return {'outcome':'PREPARED'}
        self.candidate.prepare.side_effect=prepare
        with ThreadPoolExecutor(max_workers=2) as executor:
            first=executor.submit(self.runner.tick)
            self.assertTrue(entered.wait(5))
            try:
                with self.assertRaisesRegex(ApprovalHold,'ORCHESTRATION_BUSY'):self.runner.tick()
            finally:release.set()
            self.assertEqual('DONE',first.result(timeout=5)['state'])
        self.assertEqual(1,self.candidate.prepare.call_count)

    def test_process_crash_is_unknown_and_read_only_status_can_settle(self):
        self.run_row();self.candidate.prepare.side_effect=SystemExit('injected crash')
        with self.assertRaises(SystemExit):self.runner.tick()
        self.assertEqual([('RUNNING',)],self.rows('SELECT state FROM response_agent_orchestration_job'))
        self.assertEqual('IDLE',self.runner.tick()['state'])
        self.assertEqual([('UNKNOWN',)],self.rows('SELECT state FROM response_agent_orchestration_job'))
        self.candidate.status.return_value={'outcome':'PREPARED','manifest_digest':SHA}
        self.assertEqual('DONE',self.runner.tick(reconcile=True)['state'])
        self.assertEqual(1,self.candidate.prepare.call_count);self.candidate.status.assert_called_once_with(RUN)
        self.assertEqual(['START','INTERRUPTED','RECONCILED'],[row[0] for row in self.rows('SELECT event FROM response_agent_orchestration_event ORDER BY sequence')])

    def test_unknown_not_started_status_does_not_restart_work(self):
        self.run_row();self.candidate.prepare.return_value={'outcome':'UNKNOWN'}
        self.assertEqual('UNKNOWN',self.runner.tick()['state'])
        self.assertEqual('IDLE',self.runner.tick()['state'])
        self.candidate.status.return_value={'outcome':'NOT_STARTED'}
        self.assertEqual('UNKNOWN',self.runner.tick(reconcile=True)['state'])
        self.assertEqual(1,self.candidate.prepare.call_count)

    def test_poll_waits_for_authenticated_merge_then_advances_one_step(self):
        manifest=self.published();self.release.advance.side_effect=[ApprovalHold('PR_NOT_MERGED'),{'state':'PREPARED'}]
        self.assertEqual('WAITING',self.runner.tick(phase='approval')['state'])
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        self.due()
        result=self.runner.tick(phase='approval')
        self.assertEqual('WAITING',result['state']);self.assertEqual('PREPARED',result['controller_state'])
        self.assertEqual(2,self.release.advance.call_count)
        self.assertEqual(manifest.digest,self.release.advance.call_args.args[0].digest)
        self.assertEqual([],self.rows('SELECT * FROM response_agent_review_approval'))

    def test_wrong_actor_holds_and_report_only_manually_enqueued_cannot_execute(self):
        self.published();self.release.advance.side_effect=ApprovalHold('MERGER_NOT_ALLOWED')
        self.assertEqual('HELD',self.runner.tick(phase='approval')['state']);self.due()
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        report=self.published('REPORT_ONLY')
        with self.admin() as connection,connection.cursor() as cursor:
            self.store.enqueue(cursor,'ADVANCE_REVIEW',report.digest,RUN,{'manifest_digest':report.digest})
        result=self.runner.tick(phase='approval')
        self.assertEqual('HELD',result['state']);self.assertEqual('EXACT_MANIFEST_REQUIRED',result['reason_code'])
        self.assertEqual(1,self.release.advance.call_count)

    def test_release_unknown_reconciles_receipt_without_advancing_again(self):
        self.published();self.release.advance.return_value={'state':'RECOVERY_REQUIRED','pending_action':'replay'}
        self.assertEqual('UNKNOWN',self.runner.tick(phase='approval')['state'])
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        self.release.reconcile.return_value={'state':'ACTIVE','pending_action':None}
        self.assertEqual('DONE',self.runner.tick(phase='approval',reconcile=True)['state'])
        self.assertEqual(1,self.release.advance.call_count);self.assertEqual(1,self.release.reconcile.call_count)

    def test_held_replay_notifies_recovery_without_marking_release_successful(self):
        self.published();self.release.advance.return_value={'state':'REPLAY_HELD','pending_action':None,'operating_writes':0}
        result=self.runner.tick(phase='approval')
        self.assertEqual('HELD',result['state'])
        self.assertEqual('REPLAY_HELD',result['controller_state'])
        self.assertEqual('REPLAY_REVIEW_REQUIRED',result['reason_code'])
        payload=self.rows('SELECT payload FROM response_agent_status_notification')[0][0]
        self.assertEqual('HELD',payload['state'])
        self.assertEqual('REPLAY_REVIEW_REQUIRED',payload['reason_code'])
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        self.promoter.promote.assert_not_called();self.candidate.prepare.assert_not_called()

    def test_reconciled_rollback_notifies_baseline_recovery_without_reexecuting(self):
        self.published();self.release.advance.return_value={'state':'RECOVERY_REQUIRED','pending_action':'rollback'}
        self.assertEqual('UNKNOWN',self.runner.tick(phase='approval')['state'])
        self.release.reconcile.return_value={'state':'ROLLED_BACK','pending_action':None,'operating_writes':0}
        result=self.runner.tick(phase='approval',reconcile=True)
        self.assertEqual('HELD',result['state'])
        self.assertEqual('ROLLED_BACK',result['controller_state'])
        self.assertEqual('BASELINE_RECOVERY_REQUIRED',result['reason_code'])
        payloads=self.rows("SELECT payload FROM response_agent_status_notification WHERE payload->>'state'='HELD'")
        self.assertEqual(1,len(payloads))
        self.assertEqual('BASELINE_RECOVERY_REQUIRED',payloads[0][0]['reason_code'])
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        self.assertEqual(1,self.release.advance.call_count)
        self.promoter.promote.assert_not_called();self.candidate.prepare.assert_not_called()

    def test_data_unknown_uses_status_only_and_does_not_relaunch(self):
        self.published('DATA_APPLY');self.data.advance.return_value={'state':'RECOVERY_REQUIRED','applied_count':1}
        result=self.runner.tick(phase='approval');self.assertEqual('UNKNOWN',result['state'])
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        self.data.status.return_value={'state':'APPLIED','applied_count':2}
        result=self.runner.tick(phase='approval',reconcile=True)
        self.assertEqual('DONE',result['state']);self.assertEqual(2,result['applied_count'])
        self.assertEqual(1,self.data.advance.call_count);self.assertEqual(1,self.data.status.call_count)

    def replay(self):
        manifest=self.published()
        operation='release-'+uuid.uuid4().hex
        evidence=ApprovalEvidence(manifest.digest,REPO,20,'b'*40,'d'*40,7,datetime.now(timezone.utc).isoformat(),'DEPLOY_REPLAY_PROPOSE')
        receipt={'outcome':'DONE','complete':True,'phase':'REPLAY','operating_writes':0,'items':[], 'manifest_digest':manifest.digest}
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute("INSERT INTO response_agent_review_approval(approval_digest,manifest_digest,event_key,actor_id,scope,evidence,state,operation_id,consumed_at) VALUES (%s,%s,%s,7,%s,%s,'CONSUMED',%s,CURRENT_TIMESTAMP)",
                (evidence.digest,manifest.digest,evidence.event_key,evidence.scope,Json(evidence.__dict__),operation))
            cursor.execute("INSERT INTO response_agent_release_service(service_id) VALUES ('service')")
            cursor.execute("INSERT INTO response_agent_release_operation(operation_id,service_id,approval_digest,manifest_digest,request_digest,approval_scope,state,receipt) VALUES (%s,'service',%s,%s,%s,'DEPLOY_REPLAY_PROPOSE','ACTIVE',%s)",
                (operation,evidence.digest,manifest.digest,SHA,Json({'replay_receipt':receipt})))
        return manifest,operation

    def data_review(self, cursor, **kwargs):
        doc=manifest_document('DATA_APPLY');old='docs/response-agent-reviews/review-1/'
        doc['review_id']=kwargs['review_id'];doc['expires_at']=kwargs['expires_at'];doc['base_commit']=kwargs['base_commit']
        doc['files']={p.replace(old,'docs/response-agent-reviews/'+doc['review_id']+'/'):v for p,v in doc['files'].items()}
        manifest=ReviewManifest(doc);store=PgReviewStore(self.admin)
        store.freeze(cursor,manifest,validated=True)
        store.enqueue(cursor,manifest_digest=manifest.digest,event_kind='TERMINAL_REVIEW',destination=REPO,revision=1,payload={'fixture':True})
        result=Mock();result.manifest=manifest;return result

    def test_completed_replay_enqueues_exact_data_review_once_with_frozen_expiry(self):
        self.replay();self.resolver.resolve.return_value='d'*40;self.reviews.data.side_effect=self.data_review
        result=self.runner.tick(phase='data-review');self.assertEqual('DONE',result['state'])
        self.assertEqual('IDLE',self.runner.tick(phase='data-review')['state'])
        self.assertEqual(1,self.reviews.data.call_count);self.assertEqual(1,self.resolver.resolve.call_count)
        self.assertEqual([(1,)],self.rows('SELECT count(*) FROM response_agent_notification_outbox'))
        args=self.reviews.data.call_args.kwargs
        self.assertEqual('d'*40,args['base_commit']);self.assertEqual(RUN,args['run_id'])
        self.assertEqual([],self.rows("SELECT * FROM response_agent_review_approval WHERE scope='APPLY_EXACT_MANIFEST'"))

    def test_data_review_commit_before_scheduler_crash_recovers_without_fetch_or_new_review(self):
        self.replay();self.resolver.resolve.return_value='d'*40;self.reviews.data.side_effect=self.data_review
        original=self.store.finish
        with patch.object(self.store,'finish',side_effect=SystemExit('crash after outbox commit')):
            with self.assertRaises(SystemExit):self.runner.tick(phase='data-review')
        self.assertEqual('DONE',self.runner.tick(phase='data-review',reconcile=True)['state'])
        self.assertEqual(1,self.reviews.data.call_count);self.assertEqual(1,self.resolver.resolve.call_count)
        self.assertEqual([(1,)],self.rows('SELECT count(*) FROM response_agent_notification_outbox'))

    def test_replay_no_new_proposals_is_held_without_new_authority(self):
        self.replay();self.resolver.resolve.return_value='d'*40;self.reviews.data.side_effect=ApprovalHold('NO_NEW_PENDING_DATA_TARGETS')
        result=self.runner.tick(phase='data-review');self.assertEqual('HELD',result['state'])
        self.assertEqual('NO_NEW_PENDING_DATA_TARGETS',result['reason_code'])
        self.assertEqual([(0,)],self.rows('SELECT count(*) FROM response_agent_notification_outbox'))

    def test_completed_release_promotes_baseline_before_preparing_next_incident(self):
        manifest,_=self.replay();self.run_row(str(uuid.uuid4()))
        self.promoter.promote.return_value={'outcome':'PROMOTED','pointer_digest':SHA}
        result=self.runner.tick()
        self.assertEqual('DONE',result['state']);self.assertEqual('PROMOTE_BASELINE',result['kind'])
        self.promoter.promote.assert_called_once_with(manifest.digest);self.candidate.prepare.assert_not_called()
        self.assertEqual('IDLE',self.runner.tick(phase='baseline')['state'])

    def test_promotion_unknown_uses_status_without_promoting_twice(self):
        self.replay();self.promoter.promote.return_value={'outcome':'UNKNOWN'}
        self.assertEqual('UNKNOWN',self.runner.tick(phase='baseline')['state'])
        self.assertEqual('IDLE',self.runner.tick(phase='baseline')['state'])
        self.promoter.status.return_value={'outcome':'PROMOTED','pointer_digest':SHA}
        self.assertEqual('DONE',self.runner.tick(phase='baseline',reconcile=True)['state'])
        self.assertEqual(1,self.promoter.promote.call_count);self.assertEqual(1,self.promoter.status.call_count)

    def test_discovery_bound_and_phase_filter(self):
        for _ in range(5):self.run_row(str(uuid.uuid4()))
        self.runner.discovery_limit=2
        self.assertEqual('IDLE',self.runner.tick(phase='approval')['state'])
        self.assertEqual([(0,)],self.rows('SELECT count(*) FROM response_agent_orchestration_job'))
        self.runner.tick(phase='prepare')
        self.assertEqual([(2,)],self.rows('SELECT count(*) FROM response_agent_orchestration_job'))

    def test_job_identity_and_history_immutable_and_scheduler_cannot_mutate_source_or_serving(self):
        self.run_row();self.runner.tick()
        for sql in ("UPDATE response_agent_orchestration_job SET request='{}'", "DELETE FROM response_agent_orchestration_job",
                    "UPDATE response_agent_orchestration_event SET detail='{}'", "DELETE FROM response_agent_orchestration_event"):
            with self.admin() as connection,connection.cursor() as cursor:
                with self.assertRaises(psycopg2.Error):cursor.execute(sql)
        for sql in ("UPDATE serving_sentinel SET value='unsafe'", "UPDATE response_capture SET payload='unsafe'", "UPDATE change_proposal SET value='unsafe'"):
            with self.connect() as connection,connection.cursor() as cursor:
                with self.assertRaises(psycopg2.errors.InsufficientPrivilege):cursor.execute(sql)
        self.assertEqual([('unchanged',)],self.rows('SELECT value FROM serving_sentinel'))

    def test_preview_does_not_discover_or_execute(self):
        self.run_row();result=self.store.preview()
        self.assertEqual({'mode':'PREVIEW','states':{},'side_effects':0},result)
        self.candidate.prepare.assert_not_called()


class MergeResolverTest(unittest.TestCase):
    def setUp(self):
        self.manifest=ReviewManifest(manifest_document())
        self.evidence=ApprovalEvidence(self.manifest.digest,REPO,20,'b'*40,'d'*40,7,datetime.now(timezone.utc).isoformat(),'DEPLOY_REPLAY_PROPOSE')
        self.approval={'evidence':self.evidence.__dict__,'approval_digest':self.evidence.digest,'state':'CONSUMED'}
        self.preparer=Mock();self.preparer.root=Path('/fixed/repository');self.preparer.repository=REPO
        self.preparer._git.side_effect=[('https://github.com/'+REPO+'.git').encode(),b'd'*40]
        self.resolver=VerifiedMergeResolver(self.preparer)

    def test_missing_merge_fetches_only_verified_sha_no_checkout_merge_or_reset(self):
        with patch('agent.response_change.orchestration.subprocess.run',side_effect=[Mock(returncode=1),Mock(returncode=0)]) as run, \
                patch('agent.response_change.orchestration.git_inventory',return_value={}), \
                patch('agent.response_change.orchestration.code_tree_digest',return_value=self.manifest.document['candidate_tree_digest']):
            self.assertEqual('d'*40,self.resolver.resolve(self.manifest,self.approval))
        command=run.call_args_list[1].args[0]
        self.assertEqual(['fetch','--no-tags','origin','d'*40],command[-4:])
        self.assertNotIn('checkout',command);self.assertNotIn('merge',command);self.assertNotIn('reset',command)

    def test_unconsumed_or_changed_evidence_cannot_fetch(self):
        for values in ({**self.approval,'state':'APPROVED'}, {**self.approval,'approval_digest':'e'*64}):
            with patch('agent.response_change.orchestration.subprocess.run') as run:
                with self.assertRaisesRegex(ApprovalHold,'CONSUMED_MERGE_REQUIRED'):self.resolver.resolve(self.manifest,values)
                run.assert_not_called()

    def test_changed_remote_or_tree_cannot_supply_data_base(self):
        self.preparer._git.side_effect=[b'https://github.com/untrusted/project.git']
        with self.assertRaisesRegex(ApprovalHold,'ORIGIN_REPOSITORY_MISMATCH'):self.resolver.resolve(self.manifest,self.approval)
        self.preparer._git.side_effect=[('https://github.com/'+REPO+'.git').encode(),b'd'*40]
        with patch('agent.response_change.orchestration.subprocess.run',return_value=Mock(returncode=0)), \
                patch('agent.response_change.orchestration.git_inventory',return_value={}):
            with self.assertRaisesRegex(ApprovalHold,'VERIFIED_MERGE_TREE_CHANGED'):self.resolver.resolve(self.manifest,self.approval)


class OrchestrationCliTest(unittest.TestCase):
    def test_no_dotenv_or_model_or_remote_call_in_preview(self):
        with patch('agent.response_change.orchestration_cli.configuration',return_value={'release_configuration':'/config'}), \
                patch('agent.response_change.orchestration_cli.release_configuration',return_value={'service_id':'service'}), \
                patch.dict(os.environ,{'AGENT_ORCHESTRATION_DSN':'fixture'}), \
                patch('agent.response_change.orchestration_cli.PgOrchestrationStore') as store, \
                patch('agent.response_change.orchestration_cli.build_orchestrator') as build:
            store.return_value.preview.return_value={'mode':'PREVIEW','side_effects':0}
            self.assertEqual(0,main(['--configuration','/config']))
            build.assert_not_called();store.return_value.preview.assert_called_once()

    def test_configuration_bounds_and_explicit_non_main_branch(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory).resolve()/'config.json'
            values={'release_configuration':'/private/release.json','repository_path':'/repo','baseline_pointer':'/private/active-baseline.json','recovery_receipt':'/private/recovery.json',
                'worker_output_directory':'/private/worker',
                'base_branch':'feat/response-agent','poll_seconds':60,'review_ttl_seconds':604800,'discovery_limit':100}
            path.write_text(json.dumps(values));path.chmod(0o600)
            self.assertEqual(values,configuration(path))
            for ttl in (60,3599,604801,2592000):
                values['review_ttl_seconds']=ttl;path.write_text(json.dumps(values))
                with self.subTest(ttl=ttl), self.assertRaisesRegex(ApprovalHold,'ORCHESTRATION_CONFIGURATION_BOUNDS'):
                    configuration(path)
            for ttl in (3600,604800):
                values['review_ttl_seconds']=ttl;path.write_text(json.dumps(values))
                self.assertEqual(ttl,configuration(path)['review_ttl_seconds'])
            values['discovery_limit']=101;path.write_text(json.dumps(values))
            with self.assertRaisesRegex(ApprovalHold,'ORCHESTRATION_CONFIGURATION_BOUNDS'):configuration(path)

    def test_orchestrator_rejects_ttl_outside_candidate_contract_before_discovery(self):
        adapters={name:Mock() for name in ('store','candidate','release','data','reviews','merge_resolver')}
        for ttl in (60,3599,604801,2592000):
            with self.subTest(ttl=ttl), self.assertRaisesRegex(ApprovalHold,'REVIEW_TTL_INVALID'):
                Orchestrator(**adapters,review_ttl_seconds=ttl)
        adapters['store'].discover.assert_not_called()

    def test_installed_worker_uses_atomic_pointer_and_never_loads_checkout_dotenv(self):
        from agent.response_change import worker_cli
        baseline={'deployment_manifest':'/private/frozen/manifest.json','deployment_manifest_digest':SHA,'source_commit':'b'*40}
        database=Mock();database.__enter__=Mock(return_value=database);database.__exit__=Mock(return_value=False)
        cursor=Mock();cursor.__enter__=Mock(return_value=cursor);cursor.__exit__=Mock(return_value=False)
        cursor.fetchone.return_value=(0,);database.cursor.return_value=cursor
        with patch.dict(os.environ,{'AGENT_DISPATCH_DSN':'fixture'}), \
                patch('dotenv.dotenv_values') as dotenv, \
                patch('agent.response_change.worker_cli.psycopg2.connect',return_value=database), \
                patch('agent.response_change.baseline_promotion.BaselinePointerStore') as pointer, \
                patch('agent.response_change.full_registry.FullReleaseRegistry'), \
                patch('agent.response_change.current_bundle.CurrentParserBundleProvider') as provider:
            pointer.return_value.read.return_value=baseline
            provider.return_value.bundles={}
            result=worker_cli.main(['--baseline-pointer','/private/active-baseline.json','--registry-directory','/private/registry',
                '--service-id','service','--repository-path','/fixed/repository'])
            self.assertEqual(0,result);dotenv.assert_not_called();pointer.return_value.read.assert_called_once()
            self.assertEqual((Path(baseline['deployment_manifest']),SHA),provider.call_args.args)
            self.assertEqual('service',pointer.call_args.kwargs['service_id'])

    def test_installed_publisher_routes_fixed_repo_and_never_loads_dotenv(self):
        from agent.response_change import publication_cli
        claim=Mock();claim.payload={'manifest':{},'prepared':{}}
        with patch.dict(os.environ,{'AGENT_PUBLICATION_DSN':'fixture'}), \
                patch('agent.response_change.publication_cli.dotenv_values') as dotenv, \
                patch('agent.response_change.publication_cli.PgReviewStore') as store, \
                patch('agent.response_change.publication_cli.ReviewManifest'), \
                patch('agent.response_change.publication_cli.PreparedReview'), \
                patch('agent.response_change.publication_cli.GitReportPreparer') as preparer, \
                patch('agent.response_change.publication_cli.GitHubAdapter') as provider, \
                patch('agent.response_change.publication_cli.deliver_claim',return_value={'url':'fixture'}):
            store.return_value.claim.return_value=claim
            self.assertEqual(0,publication_cli.main(['--repository',REPO,'--repository-path','/fixed/repository','--live']))
            dotenv.assert_not_called()
            self.assertEqual(Path('/fixed/repository'),preparer.call_args.args[0])
            self.assertEqual(Path('/fixed/repository'),provider.call_args.kwargs['workspace'])


if __name__ == '__main__':unittest.main()
