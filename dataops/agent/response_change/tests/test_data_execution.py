"""Actual PostgreSQL authority/intent tests; fake child never opens a serving DB."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import psycopg2

from agent.response_change.approval import (ApprovalHold, ApprovalVerifier, PgReviewStore, PublicationBinding,
    PullRequestSnapshot, ReviewManifest, canonical, content_digest, render_data_manifest, render_data_preview)
from agent.response_change.data_execution import ExactDataApplyController
from agent.response_change.java_bridge import JavaBridgeConfig
from agent.response_change.release import ReleaseHold
from agent.tests.test_approval import manifest_document

ROOT=Path(__file__).resolve().parents[3]
SHA='a'*64
REPO='SWEETT-GIRL/bbang-ggut_dataops_BE'
RUN='01000000-0000-0000-0000-000000000001'
RELEASE_OPERATION='release-'+'1'*32
REPLAY_OPERATION='11111111-1111-1111-1111-111111111111'


class PgDataExecutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container='agent-data-execution-'+uuid.uuid4().hex[:10]
        try:
            subprocess.run(['docker','run','--rm','-d','--name',cls.container,'-e','POSTGRES_HOST_AUTH_METHOD=trust',
                '-e','POSTGRES_DB=data_execution_test','-p','127.0.0.1::5432','postgres:16-alpine'],check=True,capture_output=True,timeout=30)
            port=subprocess.check_output(['docker','port',cls.container,'5432/tcp'],text=True).strip().rsplit(':',1)[1]
            cls.dsn=f'host=127.0.0.1 port={port} dbname=data_execution_test user=postgres connect_timeout=2'
            for _ in range(40):
                try:
                    with psycopg2.connect(cls.dsn):break
                except psycopg2.OperationalError:time.sleep(.2)
            else:raise RuntimeError('isolated PostgreSQL unavailable')
            with psycopg2.connect(cls.dsn) as connection,connection.cursor() as cursor:
                cursor.execute('CREATE ROLE data_controller LOGIN')
        except (OSError,subprocess.SubprocessError,RuntimeError,psycopg2.Error) as error:
            subprocess.run(['docker','rm','-f',cls.container],capture_output=True)
            raise unittest.SkipTest('isolated PostgreSQL unavailable: '+type(error).__name__) from None

    @classmethod
    def tearDownClass(cls):subprocess.run(['docker','rm','-f',cls.container],capture_output=True)

    def admin(self):return psycopg2.connect(self.dsn,options='-c search_path='+self.schema)
    def connect(self):return psycopg2.connect(self.dsn.replace('user=postgres','user=data_controller'),options='-c search_path='+self.schema)

    def setUp(self):
        self.schema='case_'+uuid.uuid4().hex
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.root.chmod(0o700)
        props=self.root/'private.properties';props.write_text('# private test configuration\n');props.chmod(0o600)
        artifact=self.root/'test.jar';artifact.write_bytes(b'fixture artifact')
        self.candidate={'release_id':'release-1','generation':'generation-one','artifact_path':str(artifact),
            'artifact_sha256':sha256(artifact.read_bytes()).hexdigest(),'profile_digest':SHA,'parser_digest':SHA,
            'contract_digest':SHA,'validation_digest':SHA,'database_identity':SHA}
        self.registry=Mock();self.registry.validated_candidate.return_value=self.candidate
        self.provider=Mock();self.verifier=ApprovalVerifier(allowed_repository=REPO,allowed_actor_ids={7})
        self.controller=ExactDataApplyController(connect=self.connect,registry=self.registry,provider=self.provider,
            verifier=self.verifier,config=JavaBridgeConfig(Path(sys.executable).resolve(),props,self.root,1))
        self.manifest=self.data_manifest()
        self.publication=PublicationBinding(REPO,20,'b'*40,self.manifest.digest)
        self.snapshot=self.snapshot_for(self.manifest,20)
        self.provider.read_snapshot.return_value=self.snapshot
        with psycopg2.connect(self.dsn) as connection,connection.cursor() as cursor:
            cursor.execute('CREATE SCHEMA '+self.schema)
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('CREATE TABLE response_agent_run(run_id uuid PRIMARY KEY)')
            cursor.execute('INSERT INTO response_agent_run VALUES (%s)',(RUN,))
            cursor.execute('CREATE TABLE change_proposal(id bigint PRIMARY KEY,status text,operation_id uuid,apply_outcome_reason text)')
            for name in ('V20261014170000__agent_review_outbox.sql','V20261014190000__agent_release_journal.sql',
                         'V20261014200000__agent_data_apply_item.sql','V20261014210000__agent_execution_binding.sql',
                         'V20261014240000__agent_data_execution.sql'):
                cursor.execute((ROOT/'src/main/resources/db/migration'/name).read_text())
            parser=ReviewManifest(manifest_document())
            parser_publication=PublicationBinding(REPO,19,'b'*40,parser.digest)
            store=PgReviewStore(self.admin)
            store.freeze(cursor,parser,validated=True);store.bind_publication(cursor,parser_publication)
            parser_approval=store.approve(cursor,self.verifier.evaluate(parser,parser_publication,self.snapshot_for(parser,19)))
            store.consume(cursor,parser_approval,scope='DEPLOY_REPLAY_PROPOSE',operation_id=RELEASE_OPERATION)
            cursor.execute("INSERT INTO response_agent_release_service(service_id,fencing_epoch) VALUES ('service',1)")
            cursor.execute('''INSERT INTO response_agent_release_operation(operation_id,service_id,approval_digest,manifest_digest,
                request_digest,approval_scope,state) VALUES (%s,'service',%s,%s,%s,'DEPLOY_REPLAY_PROPOSE','ACTIVE')''',
                (RELEASE_OPERATION,parser_approval,parser.digest,SHA))
            values={'release_operation_id':RELEASE_OPERATION,'replay_operation_id':REPLAY_OPERATION,'manifest_digest':parser.digest,
                'approval_digest':parser_approval,'service_id':'service','release_id':'release-1','generation':'generation-one',
                'artifact_digest':self.candidate['artifact_sha256'],'parser_digest':SHA,'profile_digest':SHA,
                'contract_digest':SHA,'contract_version':'v1','policy_digest':SHA,'dataops_database_identity':SHA,
                'serving_database_identity':SHA,'database_identity':SHA,'validation_json':'{}','validation_digest':SHA,
                'plan_json':'{}','plan_digest':SHA,'canary_policy_json':'{}','canary_policy_digest':SHA}
            cursor.execute('INSERT INTO response_agent_execution_binding('+','.join(values)+') VALUES ('+','.join(['%s']*len(values))+')',tuple(values.values()))
            store.freeze(cursor,self.manifest,validated=True);store.bind_publication(cursor,self.publication)
            cursor.execute("INSERT INTO change_proposal VALUES (1,'PENDING',NULL,NULL),(2,'PENDING',NULL,NULL),(3,'PENDING',NULL,NULL)")
            cursor.execute('CREATE TABLE serving_sentinel(id int PRIMARY KEY,value text)')
            cursor.execute("INSERT INTO serving_sentinel VALUES (1,'unchanged')")
            cursor.execute('GRANT USAGE ON SCHEMA '+self.schema+' TO data_controller')
            cursor.execute('GRANT SELECT ON ALL TABLES IN SCHEMA '+self.schema+' TO data_controller')
            cursor.execute('GRANT UPDATE ON response_agent_review_manifest TO data_controller')
            cursor.execute('GRANT INSERT ON response_agent_review_approval TO data_controller')
            cursor.execute('GRANT INSERT,UPDATE ON response_agent_data_execution TO data_controller')

    def data_manifest(self):
        doc=manifest_document('DATA_APPLY');doc['review_id']='data-review';doc['artifact_sha256']=self.candidate['artifact_sha256']
        doc['expires_at']=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        doc['targets']=[{'proposal_id':i,'store_id':i,'external_id':str(i),'operation':'UPDATE','field':'store.phone',
            'old_value':'001-123','new_value':'001-456','capture_id':'capture-'+str(i),'capture_digest':SHA,
            'release_id':'release-1','reprocess_item_id':REPLAY_OPERATION} for i in (1,2)]
        prefix='docs/response-agent-reviews/data-review/'
        doc['preview_digest']=sha256(render_data_preview(doc['targets']).encode()).hexdigest()
        doc['files']={prefix+'review.md':doc['preview_digest'],prefix+'data-manifest.json':sha256(render_data_manifest(doc['targets']).encode()).hexdigest()}
        doc['row_limit']=2;doc['replay_target_manifest_digest']=content_digest(doc['targets'])
        return ReviewManifest(doc)

    def snapshot_for(self,manifest,number):
        return PullRequestSnapshot(REPO,number,'b'*40,True,'d'*40,7,datetime.now(timezone.utc).isoformat(),False,
            canonical(manifest.document['files']),manifest.document['candidate_tree_digest'],manifest.document['candidate_tree_digest'],
            REPO,canonical(manifest.document['files']))

    def claim_fake_child(self,approval,operation,states):
        """Model only the Java approval/item transaction; no serving writer exists."""
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('SELECT state FROM response_agent_data_execution WHERE approval_digest=%s',(approval,))
            self.assertEqual(('INTENT',),cursor.fetchone())
            result=PgReviewStore(self.admin).consume(cursor,approval,scope='APPLY_EXACT_MANIFEST',operation_id=operation)
            self.assertEqual(self.manifest.digest,result)
            for proposal_id,status,reason in states:
                proposal_operation=uuid.uuid4()
                cursor.execute('INSERT INTO response_agent_data_apply_item(approval_digest,operation_id,proposal_id,proposal_operation_id) VALUES (%s,%s,%s,%s)',
                    (approval,operation,proposal_id,str(proposal_operation)))
                cursor.execute('UPDATE change_proposal SET status=%s,operation_id=%s,apply_outcome_reason=%s WHERE id=%s',
                    (status,str(proposal_operation),reason,proposal_id))

    def invoke_with(self,states,exit_code=0):
        def child(candidate,binding,approval,operation,directory):
            self.assertTrue((directory/'intent.json').is_file())
            self.claim_fake_child(approval,operation,states)
            return exit_code
        return child

    def test_durable_intent_consumed_once_results_not_exit_code_and_no_serving_permission(self):
        with patch.object(self.controller,'_invoke',side_effect=self.invoke_with([(1,'APPROVED',None),(2,'APPROVED',None)],19)) as invoke:
            result=self.controller.advance(self.manifest,self.publication)
            self.assertEqual('APPLIED',result['state']);self.assertEqual(2,result['applied_count']);self.assertEqual(19,result['exit_code'])
            self.provider.read_snapshot.side_effect=AssertionError('duplicate must not call GitHub')
            again=self.controller.advance(self.manifest,self.publication)
            self.assertEqual(result,again);self.assertEqual(1,invoke.call_count)
        with self.connect() as connection,connection.cursor() as cursor:
            with self.assertRaises(psycopg2.errors.InsufficientPrivilege):cursor.execute("UPDATE serving_sentinel SET value='must not write'")
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('SELECT value FROM serving_sentinel');self.assertEqual(('unchanged',),cursor.fetchone())
            cursor.execute('SELECT state,operation_id FROM response_agent_review_approval WHERE manifest_digest=%s',(self.manifest.digest,))
            state,operation=cursor.fetchone();self.assertEqual('CONSUMED',state);self.assertEqual(result['operation_id'],operation)

    def test_concurrent_duplicate_starts_exactly_one_child(self):
        entered=threading.Event();release=threading.Event();calls=[]
        def child(*args):
            calls.append(1);entered.set();self.assertTrue(release.wait(5));return self.invoke_with([(1,'APPROVED',None),(2,'APPROVED',None)])(*args)
        with patch.object(self.controller,'_invoke',side_effect=child),ThreadPoolExecutor(max_workers=2) as executor:
            first=executor.submit(self.controller.advance,self.manifest,self.publication)
            self.assertTrue(entered.wait(5))
            second=executor.submit(self.controller.advance,self.manifest,self.publication)
            pending=second.result(timeout=5)
            self.assertEqual('RECOVERY_REQUIRED',pending['state'])
            release.set();done=first.result(timeout=5)
        self.assertEqual('APPLIED',done['state']);self.assertEqual(1,len(calls))

    def test_simultaneous_first_claims_race_on_database_and_launch_once(self):
        barrier=threading.Barrier(2);entered=threading.Event();release=threading.Event();calls=[]
        def snapshot(*args):barrier.wait(timeout=5);return self.snapshot
        self.provider.read_snapshot.side_effect=snapshot
        def child(*args):
            calls.append(1);entered.set();self.assertTrue(release.wait(5))
            return self.invoke_with([(1,'APPROVED',None),(2,'APPROVED',None)])(*args)
        with patch.object(self.controller,'_invoke',side_effect=child),ThreadPoolExecutor(max_workers=2) as executor:
            futures=[executor.submit(self.controller.advance,self.manifest,self.publication) for _ in range(2)]
            self.assertTrue(entered.wait(5));release.set()
            results=[future.result(timeout=5) for future in futures]
        self.assertEqual(1,len(calls));self.assertEqual(2,self.provider.read_snapshot.call_count)
        self.assertTrue(any(row['state']=='APPLIED' for row in results))
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('SELECT count(*) FROM response_agent_data_execution');self.assertEqual((1,),cursor.fetchone())

    def test_timeout_after_partial_apply_remains_unknown_and_never_relaunches(self):
        def child(*args):
            self.invoke_with([(1,'APPROVED',None),(2,'APPLYING',None)])(*args)
            raise ReleaseHold('DATA_RUNNER_TIMEOUT_UNKNOWN')
        with patch.object(self.controller,'_invoke',side_effect=child) as invoke:
            with self.assertRaisesRegex(ReleaseHold,'DATA_RUNNER_TIMEOUT_UNKNOWN'):self.controller.advance(self.manifest,self.publication)
            result=self.controller.advance(self.manifest,self.publication)
            self.assertEqual('UNKNOWN',result['process_state']);self.assertEqual('RECOVERY_REQUIRED',result['state'])
            self.assertEqual(1,result['applied_count']);self.assertEqual(1,invoke.call_count)

    def test_exit_zero_without_items_is_held_not_success(self):
        with patch.object(self.controller,'_invoke',return_value=0):result=self.controller.advance(self.manifest,self.publication)
        self.assertEqual('HELD',result['state']);self.assertEqual(0,result['applied_count'])
        self.assertEqual(['NOT_ATTEMPTED','NOT_ATTEMPTED'],[row['status'] for row in result['items']])

    def test_failed_conflict_reason_and_changed_operation_are_not_applied(self):
        with patch.object(self.controller,'_invoke',side_effect=self.invoke_with([(1,'APPROVED',None),(2,'FAILED','CAS_CONFLICT')],1)):
            result=self.controller.advance(self.manifest,self.publication)
        self.assertEqual('HELD',result['state']);self.assertEqual('CAS_CONFLICT',result['items'][1]['reason'])
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('UPDATE change_proposal SET operation_id=%s WHERE id=1',(str(uuid.uuid4()),))
        result=self.controller.status(self.manifest)
        self.assertEqual('RECOVERY_REQUIRED',result['state']);self.assertEqual(0,result['applied_count'])
        self.assertEqual('PROPOSAL_OPERATION_CHANGED',result['items'][0]['reason'])

    def test_revoked_unmerged_wrong_actor_cannot_create_intent(self):
        for snapshot in (replace(self.snapshot,merged=False),replace(self.snapshot,merged_by_id=999)):
            self.provider.read_snapshot.return_value=snapshot
            with patch.object(self.controller,'_invoke') as invoke:
                with self.assertRaises(ApprovalHold):self.controller.advance(self.manifest,self.publication)
                invoke.assert_not_called()
        self.provider.read_snapshot.return_value=self.snapshot
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute("UPDATE response_agent_review_manifest SET review_state='REJECTED' WHERE manifest_digest=%s",(self.manifest.digest,))
        with patch.object(self.controller,'_invoke') as invoke:
            with self.assertRaisesRegex(ApprovalHold,'APPROVAL_STORE_BINDING'):self.controller.advance(self.manifest,self.publication)
            invoke.assert_not_called()
        self.assertEqual('NOT_STARTED',self.controller.status(self.manifest)['state'])

    def test_postlaunch_revocation_still_allows_only_status(self):
        with patch.object(self.controller,'_invoke',side_effect=self.invoke_with([(1,'UNKNOWN','TRANSPORT_UNKNOWN')])) as invoke:
            self.controller.advance(self.manifest,self.publication)
            with self.admin() as connection,connection.cursor() as cursor:
                cursor.execute("UPDATE response_agent_review_manifest SET review_state='REJECTED',expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE manifest_digest=%s",(self.manifest.digest,))
            self.provider.read_snapshot.side_effect=AssertionError('must inspect durable outcome only')
            result=self.controller.advance(self.manifest,self.publication)
            self.assertEqual('RECOVERY_REQUIRED',result['state']);self.assertEqual(1,invoke.call_count)

    def test_launch_intent_identity_cannot_be_changed_or_deleted(self):
        with patch.object(self.controller,'_invoke',return_value=0):self.controller.advance(self.manifest,self.publication)
        for sql in ('UPDATE response_agent_data_execution SET request_digest=repeat(\'b\',64)','DELETE FROM response_agent_data_execution'):
            with self.admin() as connection,connection.cursor() as cursor:
                with self.assertRaisesRegex(psycopg2.Error,'data execution identity is immutable'):cursor.execute(sql)
        wrong=replace(self.publication,head_sha='f'*40)
        with patch.object(self.controller,'_invoke') as invoke:
            with self.assertRaisesRegex(ReleaseHold,'DATA_EXECUTION_REBOUND'):self.controller.advance(self.manifest,wrong)
            invoke.assert_not_called()

    def test_outside_target_item_is_rejected(self):
        with patch.object(self.controller,'_invoke',side_effect=self.invoke_with([(3,'APPROVED',None)])):
            with self.assertRaisesRegex(ReleaseHold,'DATA_EXECUTION_OUTSIDE_SCOPE'):self.controller.advance(self.manifest,self.publication)

    def test_crash_after_db_intent_before_file_does_not_retry(self):
        with patch('agent.response_change.data_execution.write_json',side_effect=OSError('lost local disk')),patch.object(self.controller,'_invoke') as invoke:
            with self.assertRaises(OSError):self.controller.advance(self.manifest,self.publication)
            invoke.assert_not_called()
        with patch.object(self.controller,'_invoke') as invoke:
            result=self.controller.advance(self.manifest,self.publication)
            self.assertEqual('RECOVERY_REQUIRED',result['state']);self.assertEqual('INTENT',result['process_state']);invoke.assert_not_called()

    def test_binding_mismatch_cannot_start_or_create_approval(self):
        self.registry.validated_candidate.return_value={**self.candidate,'release_id':'different'}
        with patch.object(self.controller,'_invoke') as invoke:
            with self.assertRaisesRegex(ReleaseHold,'DATA_CANDIDATE_CHANGED'):self.controller.advance(self.manifest,self.publication)
            invoke.assert_not_called()
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('SELECT count(*) FROM response_agent_review_approval WHERE manifest_digest=%s',(self.manifest.digest,))
            self.assertEqual((0,),cursor.fetchone())

    def test_changed_generation_stops_before_intent(self):
        self.registry.validated_candidate.return_value={**self.candidate,'generation':'different'}
        with patch.object(self.controller,'_invoke') as invoke:
            with self.assertRaisesRegex(ReleaseHold,'DATA_REPLAY_BINDING_CHANGED'):self.controller.advance(self.manifest,self.publication)
            invoke.assert_not_called()
        self.assertEqual('NOT_STARTED',self.controller.status(self.manifest)['state'])

    def test_fixed_child_arguments_do_not_enable_service_writer_or_inherit_secrets(self):
        class Child:
            def wait(self,timeout):return 0
            def poll(self):return 0
        with patch('agent.response_change.data_execution.subprocess.Popen',return_value=Child()) as spawn:
            result=self.controller.advance(self.manifest,self.publication)
        self.assertEqual('HELD',result['state'])
        argv=spawn.call_args.args[0]
        for argument in ('--dataops.serving.write-enabled=false','--spring.main.web-application-type=none',
            '--spring.flyway.enabled=false','--spring.sql.init.mode=never','--spring.jpa.hibernate.ddl-auto=validate',
            '--dataops.agent.apply.enabled=true','--dataops.agent.replay.enabled=false'):
            self.assertIn(argument,argv)
        self.assertEqual({'PATH','HOME','LANG'},set(spawn.call_args.kwargs['env']))
        self.assertNotIn('shell',spawn.call_args.kwargs)


if __name__=='__main__':unittest.main()
