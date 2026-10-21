from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

import psycopg2
from psycopg2.extras import Json

from agent.response_change import candidate_preparation as preparation, current_bundle, current_manifest
from agent.response_change.approval import ApprovalHold, ReviewManifest, PgReviewStore, content_digest
from agent.response_change.dispatch import Hold
from agent.response_change.full_registry import FullReleaseRegistry, PARSED_FIELDS
from agent.response_change.ledger import digest
from agent.response_change.runtime import LEGACY_TEST
from agent.response_change.tests.test_current_bundle import sample, incident, PROFILE, ROOT
from agent.tests.test_approval import manifest_document

RUN = '01000000-0000-0000-0000-000000000001'
SHA = 'a' * 64


class AliasCounterpartTest(unittest.TestCase):
    def test_full_raw_control_preserves_private_fields_values_order_and_original(self):
        capture = sample()
        raw = json.loads(capture['payload'])
        before = deepcopy(capture)
        control, evidence = current_bundle.reviewed_alias_counterpart(capture, PROFILE)
        self.assertEqual(before, capture)
        self.assertEqual(raw['auth'], control['auth'])
        self.assertEqual(raw['PlaceDetailBase:private-123'], control['PlaceDetailBase:private-123'])
        self.assertEqual(raw['ControlledMenuV9:a']['price'], control['PlaceMenuItem:a']['price'])
        self.assertEqual(raw['ControlledMenuV9:a']['description'], control['PlaceMenuItem:a']['description'])
        self.assertEqual('PlaceMenuItem:a', next(iter(control['ROOT_QUERY'].values()))['placeMenus']['items'][0]['__ref'])
        self.assertEqual(1, evidence['entity_count'])
        self.assertNotIn('private', json.dumps(evidence))

    def test_unrelated_delta_or_reference_collision_holds(self):
        capture = sample(); raw = json.loads(capture['payload'])
        control, _ = current_bundle.reviewed_alias_counterpart(capture, PROFILE)
        for transform in (lambda x:x['PlaceMenuItem:a']['price'].update(displayText='0원'),
                          lambda x:x['PlaceDetailBase:private-123'].update(id='other'),
                          lambda x:next(iter(x['ROOT_QUERY'].values()))['placeMenus'].update(menuCount=0)):
            broken = deepcopy(control); transform(broken)
            with self.assertRaisesRegex(Hold, 'WHOLE_CAPTURE_ALIAS_DELTA_CHANGED'):
                current_bundle.assert_alias_only_delta(raw, broken, 'ControlledMenuV9')
        raw['PlaceMenuItem:a'] = {'not': 'a menu'}
        with self.assertRaisesRegex(Hold, 'MENU_CACHE_ALIAS_COLLISION'):
            current_bundle.reviewed_alias_counterpart(sample(payload=json.dumps(raw)), PROFILE)

    def test_same_recipe_still_rejects_incomplete_or_ambiguous_meaning(self):
        raw = json.loads(sample()['payload'])
        for mutate in (lambda x:x['ControlledMenuV9:a']['price'].update(displayText='시가'),
                       lambda x:next(iter(x['ROOT_QUERY'].values()))['placeMenus'].pop('menuCount')):
            broken=deepcopy(raw); mutate(broken)
            with self.assertRaises(Hold):
                current_bundle.reviewed_alias_counterpart(sample(payload=json.dumps(broken)), PROFILE)


class PreparationInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        for path in (*current_manifest.EDITABLE_PATHS, LEGACY_TEST):
            file = self.root/'snapshot'/path; file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text((ROOT/path).read_text())
        self.manifest = {'parser_digest':'parser','profile_digest':'profile', 'jar_sha256':SHA,
            'provenance':{'git_commit':'c'*40}, 'classes':{'fixed':'class'}, 'runtime':{},
            'baseline_regression':{'found':44}}
        self.verify = patch.object(current_bundle,'verify_deployment',return_value=self.manifest)
        self.verify.start(); self.addCleanup(self.verify.stop)
        self.provider = current_bundle.CurrentParserBundleProvider(self.root/'manifest.json', SHA)
        self.sample = sample()
        self.bundle = self.provider.prepare(incident(), [self.sample])
        self.registry = FullReleaseRegistry(self.root/'registry')
        self.base = {'source_commit':'c'*40, 'artifact_sha256':SHA, 'parser_digest':'parser',
                     'profile_digest':'profile','record_digest':'b'*64}
        self.registry.committed_release=Mock(return_value=self.base)
        self.reviews=Mock(); self.reviews.preparer.repository='example/dataops'; self.reviews.base_branch='main'
        self.service=preparation.CandidatePreparationService(connect=Mock(),registry=self.registry,provider=self.provider,
            reviews=self.reviews,repository=ROOT,output_root=self.root/'runs',base_release='base')
        self.service._captures=Mock(return_value=[deepcopy(self.sample)])
        files={p:self.bundle.files[p] for p in current_manifest.EDITABLE_PATHS}
        files[current_manifest.PROFILE_PATH]+='\n'
        folder=self.root/'runs'/RUN/'patch-1'; folder.mkdir(parents=True)
        (folder/'candidate-files.json').write_text(json.dumps(files))
        patch_data={'patch_id':digest({'files':files,'cases':[]}), 'source_digest':digest(files[current_manifest.PARSER_PATH]),
                    'workspace_digest':digest(files),'directory':str(folder),'rationale':'test','cases':[]}
        result={'passed':True,'status':'PASS','manifest_digest':SHA,'source_digest':content_digest(files),
                'fixtures_digest':content_digest(self.bundle.fixtures)}
        result['report_digest']=content_digest(result)
        report={'status':'AWAITING_REVIEW','recipe':current_bundle.RECIPE,'dispatch_run_id':RUN,
                'incident_fingerprint':'a'*64,'deployment_manifest_digest':SHA,'requests':[{'cost_usd':.1}],
                'bundle_digest':self.bundle.digest(),'frozen_current_bundle':self.provider.freeze(self.bundle),
                'reprocess_targets':list(self.bundle.reprocess_targets),'oracle_digest':digest(self.bundle.fixtures),
                'patches':[{k:v for k,v in patch_data.items() if k!='cases'}],'recovery':{'patch':patch_data},
                'validations':[{'final':True,'patch_id':patch_data['patch_id'],'result':result}],
                'finished_at':datetime.now(timezone.utc).isoformat()}
        self.row={**incident(),'run_id':RUN,'state':'AWAITING_REVIEW','checkpoint':report,
                  'bundle_digest':self.bundle.digest(),'run_directory':str(self.root/'runs'/RUN)}
        self.current_verify=patch.object(current_manifest,'verify_deployment',return_value=self.manifest)
        self.current_verify.start();self.addCleanup(self.current_verify.stop)

    def inputs(self): return self.service._inputs(self.row,self.service._key(RUN))

    def test_terminal_current_patch_and_frozen_inputs_are_bound(self):
        request, bundle, captures, files, manifest, base = self.inputs()
        self.assertEqual(self.bundle.digest(), request['bundle_digest'])
        self.assertEqual(list(self.bundle.reprocess_targets),request['targets'])
        self.assertEqual('repair-run-'+RUN+'-r1',request['review_id'])
        self.assertEqual(self.sample['payload'],captures[0]['payload'])

    def test_tampered_candidate_failing_final_and_unpriced_call_hold(self):
        original=deepcopy(self.row)
        mutations=[lambda row:row['checkpoint']['requests'][0].pop('cost_usd'),
            lambda row:row['checkpoint']['validations'][-1].update(final=False),
            lambda row:row['checkpoint'].update(oracle_digest='0'*64),
            lambda row:row['checkpoint'].update(reprocess_targets=[])]
        for mutate in mutations:
            self.row=deepcopy(original); mutate(self.row)
            with self.assertRaises((ApprovalHold,Hold)):self.inputs()
        self.row=original
        file=self.root/'runs'/RUN/'patch-1/candidate-files.json'
        files=json.loads(file.read_text()); files[current_manifest.PARSER_PATH]+='//tamper'
        file.write_text(json.dumps(files))
        with self.assertRaisesRegex(ApprovalHold,'COMPLETED_PATCH_CONTENT_CHANGED'):self.inputs()

    def oracle_output(self):
        parsed={key:None for key in PARSED_FIELDS}
        parsed.update(name='private store',phone='private-phone',menus=[{'name':'private product','price':'5300',
            'imageUrls':['https://private.example/photo?secret=abc'],'description':'private prose','groupName':None}],baeminMenus=[])
        return {'status':'OK','classes':self.manifest['classes'],'parser_digest':'parser','profile_digest':'profile',
            'regression':{'found':44,'succeeded':44,'failed':0,'skipped':0,'aborted':0},
            'cases':[{'id':'capture-a','actual':{**parsed,'diagnostics':{'assessment':'READY','targetVerified':True,
                'menuAllowed':True,'modelEligible':False,'issueCodes':[]},
                'evidence':{'menuComplete':True,'identityVerified':True}}}]}

    def test_whole_oracle_uses_no_candidate_and_records_base_provenance(self):
        output=self.oracle_output()
        with patch.object(preparation.current_validator,'compile_and_run',return_value=output) as compiler:
            oracles=preparation.reviewed_whole_capture_oracles(bundle=self.bundle,captures=[self.sample],base=self.base,
                manifest=self.manifest,output=self.root/'oracle')
        self.assertEqual({},compiler.call_args.args[1])
        self.assertEqual('private-phone',oracles['capture-a']['expected_parsed']['phone'])
        self.assertEqual(self.sample['payload'],oracles['capture-a']['payload_json'])
        self.assertEqual(SHA,oracles['capture-a']['provenance']['base_artifact_sha256'])
        self.assertNotIn('private',oracles['capture-a']['reference'])

    def test_oracle_rejects_wrong_base_bytes_and_partial_outside_menu(self):
        for mutate in (lambda x:x.update(classes={'other':'class'}),
                       lambda x:x['cases'][0]['actual']['diagnostics'].update(assessment='PARTIAL'),
                       lambda x:x['cases'][0]['actual'].pop('phone')):
            output=self.oracle_output();mutate(output)
            with patch.object(preparation.current_validator,'compile_and_run',return_value=output), self.assertRaises(ApprovalHold):
                preparation.reviewed_whole_capture_oracles(bundle=self.bundle,captures=[self.sample],base=self.base,
                    manifest=self.manifest,output=self.root/'oracle')


class FakeCursor:
    def __init__(self, db):self.db=db;self.row=None
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def execute(self,sql,args=None):
        if 'pg_try_advisory_lock' in sql:self.row=(self.db.lock_available,)
        elif 'pg_advisory_unlock' in sql:self.row=(True,)
        elif 'response_agent_review_manifest' in sql:self.row={'document':self.db.manifest.document} if self.db.manifest else None
        elif 'response_agent_run' in sql:self.row=deepcopy(self.db.run)
        else:raise AssertionError(sql)
    def fetchone(self):return self.row


class FakeDB:
    def __init__(self,row):self.run=row;self.manifest=None;self.lock_available=True
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def cursor(self,**kwargs):return FakeCursor(self)
    def rollback(self):pass
    def close(self):pass
    def set_session(self,**kwargs):pass


class PreparationRecoveryTest(unittest.TestCase):
    def setUp(self):
        PreparationInputsTest.setUp(self)
        self.db=FakeDB(self.row);self.service.connect=lambda:self.db
        self.service._active_base=Mock()
        self.candidate=None
        def validate(**kwargs):
            self.candidate={'validation_digest':'d'*64,'preparation_request_digest':kwargs['preparation_request_digest']}
            return self.candidate
        self.registry.prepared_candidate=Mock(side_effect=lambda _:self.candidate)
        self.registry.validated_candidate=Mock(side_effect=lambda _:self.candidate)
        self.registry.validate_candidate=Mock(side_effect=validate)
        def parser(cursor,**kwargs):
            document=manifest_document();document['validation_digest']=kwargs['validation_digest']
            document['review_id']='repair-'+self.service._key(RUN)
            document['files']={('docs/response-agent-reviews/'+document['review_id']+'/review.md'
                if key.endswith('review.md') else key):value for key,value in document['files'].items()}
            document['targets']=list(self.bundle.reprocess_targets)
            document['replay_target_manifest_digest']=content_digest(document['targets'])
            self.db.manifest=ReviewManifest(document)
            return SimpleNamespace(manifest=self.db.manifest)
        self.reviews.parser.side_effect=parser
        self.oracle=patch.object(preparation,'reviewed_whole_capture_oracles',return_value={'capture-a':{'provenance':{'verified':'test'}}})
        self.oracle.start();self.addCleanup(self.oracle.stop)

    def test_duplicate_preparation_never_revalidates_or_republishes(self):
        first=self.service.prepare(RUN);second=self.service.prepare(RUN)
        self.assertEqual('PREPARED',first['outcome'],first)
        self.assertEqual(first,second)
        self.registry.validate_candidate.assert_called_once()
        self.reviews.parser.assert_called_once()
        self.assertEqual(first,self.service.status(RUN))

    def test_outbox_failure_does_not_recompile_candidate_on_explicit_recovery(self):
        prepare=self.reviews.parser.side_effect
        self.reviews.parser.side_effect=RuntimeError('database failure')
        self.assertEqual('UNKNOWN',self.service.prepare(RUN)['outcome'])
        self.assertEqual('UNKNOWN',self.service.status(RUN)['outcome'])
        self.reviews.parser.side_effect=prepare
        self.assertEqual('PREPARED',self.service.prepare(RUN)['outcome'])
        self.registry.validate_candidate.assert_called_once()

    def test_status_recovers_after_outbox_commit_without_writing_or_model_call(self):
        first=self.service.prepare(RUN)
        (self.registry.root/'preparation-results'/(self.service._key(RUN)+'.json')).unlink()
        recovered=self.service.status(RUN)
        self.assertEqual(first,recovered)
        self.registry.validate_candidate.assert_called_once();self.reviews.parser.assert_called_once()
        self.assertFalse((self.registry.root/'preparation-results'/(self.service._key(RUN)+'.json')).exists())

    def test_unclaimed_run_and_changed_policy_cannot_duplicate_authority(self):
        self.db.lock_available=False
        self.assertEqual('PREPARATION_BUSY',self.service.prepare(RUN)['reason_code'])
        self.registry.validate_candidate.assert_not_called()
        self.db.lock_available=True
        self.assertEqual('PREPARED',self.service.prepare(RUN)['outcome'])
        self.service.ttl-=1
        self.assertEqual('HELD',self.service.prepare(RUN)['outcome'])
        self.reviews.parser.assert_called_once()

    def test_old_baseline_cannot_prepare_next_generation(self):
        self.service._active_base.side_effect=ApprovalHold('BASELINE_PROMOTION_REQUIRED')
        result=self.service.prepare(RUN)
        self.assertEqual('BASELINE_PROMOTION_REQUIRED',result['reason_code'])
        self.registry.validate_candidate.assert_not_called();self.reviews.parser.assert_not_called()


@unittest.skipUnless(os.getenv('RUN_CANDIDATE_PREPARATION_SANDBOX_TESTS')=='true','offline Docker oracle opt-in')
class WholeOracleSandboxTest(unittest.TestCase):
    def test_committed_baseline_contract_reads_whole_counterpart_without_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve()
            runtime=json.loads((preparation.current_validator.RUNTIME/'manifest.json').read_text())
            baseline=preparation.current_validator.compile_and_run(ROOT,{},[],root/'baseline',runtime)
            self.assertEqual('OK',baseline['status'])
            manifest={'parser_digest':baseline['parser_digest'],'profile_digest':baseline['profile_digest'],
                'jar_sha256':SHA,'provenance':{'git_commit':'c'*40},'classes':baseline['classes'],
                'runtime':runtime,'baseline_regression':baseline['regression']}
            # The fixture-only source path is the current repository; production
            # uses a verified frozen committed snapshot before entering this API.
            capture=sample(parser_digest=baseline['parser_digest'])
            with patch.object(current_bundle,'verify_deployment',return_value=manifest):
                # Prepare under a real temporary snapshot so source equality is checked.
                import shutil
                for path in current_manifest.ALL_PATHS:
                    target=root/'snapshot'/path;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,target)
                provider=current_bundle.CurrentParserBundleProvider(root/'manifest.json',SHA)
                bundle=provider.prepare(incident(parser_digest=baseline['parser_digest']),[capture])
            base={'source_commit':'c'*40,'artifact_sha256':SHA,'parser_digest':baseline['parser_digest'],
                  'profile_digest':baseline['profile_digest']}
            oracles=preparation.reviewed_whole_capture_oracles(bundle=bundle,captures=[capture],base=base,
                manifest=manifest,output=root/'oracle')
            self.assertEqual('private-phone',oracles['capture-a']['expected_parsed']['phone'])
            self.assertEqual('5300',oracles['capture-a']['expected_parsed']['menus'][0]['price'])
            self.assertEqual(capture['payload'],oracles['capture-a']['payload_json'])


@unittest.skipUnless(os.getenv('RUN_PG_CANDIDATE_PREPARATION_TESTS')=='true','disposable PostgreSQL opt-in')
class PreparationPostgresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container='candidate-preparation-test-'+uuid.uuid4().hex[:10]
        subprocess.run(['docker','run','-d','--name',cls.container,'-e','POSTGRES_HOST_AUTH_METHOD=trust',
            '-e','POSTGRES_DB=candidate_test','-p','127.0.0.1::5432','postgres:16-alpine'],check=True,capture_output=True)
        try:
            port=subprocess.run(['docker','port',cls.container,'5432'],check=True,capture_output=True,text=True).stdout.strip().rsplit(':',1)[1]
            cls.dsn=f'host=127.0.0.1 port={port} dbname=candidate_test user=postgres connect_timeout=3'
            deadline=time.monotonic()+30
            while True:
                try:
                    with psycopg2.connect(cls.dsn) as db,db.cursor() as cursor:cursor.execute('SELECT 1')
                    break
                except psycopg2.OperationalError:
                    if time.monotonic()>deadline:raise
                    time.sleep(.2)
            with psycopg2.connect(cls.dsn) as db,db.cursor() as cursor:
                for name in ('V20261013100000__response_capture_and_incident.sql','V20261014111000__agent_dispatch.sql',
                             'V20261014170000__agent_review_outbox.sql'):
                    cursor.execute((ROOT/'src/main/resources/db/migration'/name).read_text())
                cursor.execute('CREATE TABLE response_agent_active_runtime (service_id text PRIMARY KEY,state text,'+
                    ','.join(key+' text' for key in ('release_id','generation','artifact_sha256','parser_digest','profile_digest',
                        'contract_digest','database_identity','dataops_database_identity','serving_database_identity'))+')')
        except BaseException:
            subprocess.run(['docker','rm','-f',cls.container],capture_output=True);raise

    @classmethod
    def tearDownClass(cls):subprocess.run(['docker','rm','-f',cls.container],check=True,capture_output=True)

    def setUp(self):
        PreparationRecoveryTest.setUp(self)
        self.connect=lambda:psycopg2.connect(self.dsn)
        self.service.connect=self.connect
        self.service._captures=preparation.CandidatePreparationService._captures.__get__(self.service)
        self.service._active_base=preparation.CandidatePreparationService._active_base.__get__(self.service)
        self.base.update(service_id='service',release_id='base',generation='base-generation',contract_digest='c'*64,
            database_identity='d'*64,dataops_database_identity='e'*64,serving_database_identity='f'*64)
        with self.connect() as db,db.cursor() as cursor:
            cursor.execute('TRUNCATE response_agent_review_approval,response_agent_review_publication,response_agent_notification_outbox,'+
                'response_agent_review_manifest,response_agent_run,response_incident_sample,response_incident,response_capture,response_agent_active_runtime')
            row=self.sample
            cursor.execute('''INSERT INTO response_capture(capture_id,request_digest,store_id,source,external_id,observed_at,attempt_seq,
                payload,payload_digest,status,parser_digest,contract_version) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'HELD',%s,%s)''',
                (row['capture_id'],SHA,row['store_id'],row['source'],row['external_id'],row['observed_at'],row['attempt_seq'],
                 row['payload'],row['payload_digest'],row['parser_digest'],row['contract_version']))
            cursor.execute('''INSERT INTO response_incident(fingerprint,source,parser_digest,contract_version,diagnostics,model_eligible)
                VALUES (%s,%s,%s,%s,%s,TRUE)''',('a'*64,'NAVER','parser',current_bundle.CONTRACT,json.dumps(incident()['diagnostics'])))
            cursor.execute('''INSERT INTO response_agent_run(run_id,fingerprint,parser_digest,contract_version,state,owner,lease_until,
                budget_usd,bundle_digest,run_directory,checkpoint) VALUES (%s,%s,%s,%s,'AWAITING_REVIEW','finished',CURRENT_TIMESTAMP,
                .5,%s,%s,%s)''',(RUN,'a'*64,'parser',current_bundle.CONTRACT,self.row['bundle_digest'],self.row['run_directory'],Json(self.row['checkpoint'])))
            fields=('service_id','release_id','generation','artifact_sha256','parser_digest','profile_digest','contract_digest',
                    'database_identity','dataops_database_identity','serving_database_identity')
            cursor.execute('INSERT INTO response_agent_active_runtime(state,'+','.join(fields)+') VALUES (\'ACTIVE\','+
                ','.join('%s' for _ in fields)+')',[self.base[key] for key in fields])
        original=self.reviews.parser.side_effect
        self.store=PgReviewStore(self.connect)
        def publish(cursor,**kwargs):
            request=original(cursor,**kwargs)
            self.store.freeze(cursor,request.manifest,validated=True)
            self.store.enqueue(cursor,manifest_digest=request.manifest.digest,event_kind='TERMINAL_REVIEW',destination='example/dataops',
                revision=1,payload={'test':'frozen request'})
            return request
        self.reviews.parser.side_effect=publish

    def count(self,table):
        with self.connect() as db,db.cursor() as cursor:
            cursor.execute('SELECT count(*) FROM '+table);return cursor.fetchone()[0]

    def test_session_lock_and_terminal_to_outbox_are_idempotent_in_actual_postgres(self):
        lock=self.connect()
        try:
            with lock,lock.cursor() as cursor:
                cursor.execute('SELECT pg_advisory_lock(hashtextextended(%s,0))',('response-agent-candidate:'+self.service._key(RUN),))
            self.assertEqual('PREPARATION_BUSY',self.service.prepare(RUN)['reason_code'])
            self.registry.validate_candidate.assert_not_called()
        finally:lock.close()
        first=self.service.prepare(RUN)
        self.assertEqual('PREPARED',first['outcome'],first)
        self.assertEqual(first,self.service.prepare(RUN));self.assertEqual(first,self.service.status(RUN))
        self.assertEqual(1,self.count('response_agent_notification_outbox'))
        self.registry.validate_candidate.assert_called_once()

    def test_outbox_transaction_rollback_has_no_partial_authority_or_new_analysis(self):
        original=self.reviews.parser.side_effect
        def fail(cursor,**kwargs):original(cursor,**kwargs);raise RuntimeError('after enqueue')
        self.reviews.parser.side_effect=fail
        self.assertEqual('UNKNOWN',self.service.prepare(RUN)['outcome'])
        self.assertEqual(0,self.count('response_agent_notification_outbox'))
        self.assertEqual(0,self.count('response_agent_review_manifest'))
        self.reviews.parser.side_effect=original
        result=self.service.prepare(RUN)
        self.assertEqual('PREPARED',result['outcome'],result)
        self.registry.validate_candidate.assert_called_once()
        self.assertEqual(1,self.count('response_agent_notification_outbox'))


if __name__=='__main__':unittest.main()
