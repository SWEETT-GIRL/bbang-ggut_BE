from copy import deepcopy
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid
from unittest.mock import Mock, patch
import zipfile

from agent.response_change.approval import ApprovalHold, content_digest, code_tree_digest
from agent.response_change.current_manifest import PARSER_PATH, PROFILE_PATH
from agent.response_change.full_registry import FullReleaseRegistry, PARSED_FIELDS, git_inventory
from agent.response_change.review_preparation import ExactReviewPreparation, GitExactReviewPreparer, PgReplayProposalLoader

SHA='a'*64


class FullRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve(); self.repo=self.root/'repo';self.repo.mkdir()
        def git(*args):
            return subprocess.run(['git','-C',str(self.repo),*args],capture_output=True,text=True,check=True).stdout.strip()
        self.git=git
        git('init','-q','-b','main');git('config','user.name','Test');git('config','user.email','test@example.invalid')
        git('remote','add','origin','https://github.com/example/dataops.git')
        for path,text in {PARSER_PATH:'old parser',PROFILE_PATH:'old profile','app.txt':'protected app'}.items():
            file=self.repo/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
        git('add','.');git('commit','-qm','✨ feat: 검증 기준선')
        self.commit=git('rev-parse','HEAD')
        self.registry=FullReleaseRegistry(self.root/'registry')
        self.basejar=self.root/'base.jar';self.candidatejar=self.root/'candidate.jar'
        self.jar(self.basejar,'old'); self.jar(self.candidatejar,'new')
        self.build={'kind':'COMMITTED_OFFLINE_SERVICE_BUILD','source_commit':self.commit,
            'artifact_path':str(self.basejar),'artifact_sha256':self.hash(self.basejar),
            'inputs':{'image_id':'sha256:'+SHA,'recipe_digest':SHA}}
        self.build['receipt_digest']=content_digest(self.build)
        self.registry._put('builds',self.build['receipt_digest'],self.build)
        self.measure={'parser_digest':'b'*64,'profile_digest':'c'*64,'contract_digest':'d'*64,'contract_version':'v1'}
        self.live={'service_id':'test-service','release_id':'base','generation':'base-generation','healthy':True,
            'artifact_sha256':self.hash(self.basejar),**self.measure,'dataops_database_identity':SHA,
            'serving_database_identity':'e'*64,'database_identity':content_digest({'dataops':SHA,'serving':'e'*64}),'policy_digest':SHA}
        self.recovery={'schema_compatible':True,'backup_verified':True,'schema_receipt_digest':SHA,'backup_receipt_digest':SHA}
        with patch('agent.response_change.full_registry.probe_artifact',return_value=self.measure):
            self.base=self.registry.register_base(build_receipt_digest=self.build['receipt_digest'],release_id='base',
                observe_runtime=lambda:self.live,verify_recovery=lambda *_:self.recovery)
        self.payload='{"some": "raw", "n": 1}'
        self.target={'capture_id':'capture-one','payload_digest':sha256(self.payload.encode()).hexdigest(),
            'store_id':1,'external_id':'123','observed_at':'2026-10-14T00:00:00Z','attempt_seq':1}
        self.parsed={key:None for key in PARSED_FIELDS};self.parsed.update(name='가게',menus=[],baeminMenus=[])
        self.oracle={'target':self.target,'payload_json':self.payload,'expected_parsed':self.parsed,
            'kind':'HUMAN_CONFIRMED','reference':'reviewed-oracle-v1'}
        self.candidates={PARSER_PATH:'old parser',PROFILE_PATH:'new profile'}
        self.deployment={'provenance':{'git_commit':self.commit},'jar_sha256':self.hash(self.basejar),'runtime':{}}
        self.report={'passed':True,'status':'PASS','artifact':{'path':str(self.candidatejar),'sha256':self.hash(self.candidatejar),
            'base_jar_sha256':self.hash(self.basejar),'parser_digest':self.measure['parser_digest'],'profile_digest':self.measure['profile_digest']},
            'candidate':{'classes':{'parser.class':SHA}},'contract_digest':'f'*64,'build_input_digest':SHA}
        self.report['report_digest']=content_digest(self.report)
        self.full={'status':'OK','classes':{'parser.class':SHA},'cases':[{'id':'capture-one','parsed_digest':content_digest(self.parsed),'actual':{
            **self.parsed,'diagnostics':{'assessment':'READY','targetVerified':True,'menuAllowed':True}}}]}

    def hash(self,path):return sha256(path.read_bytes()).hexdigest()

    def jar(self,path,parser,other='unchanged'):
        with zipfile.ZipFile(path,'w') as archive:
            archive.writestr('BOOT-INF/classes/sweetgirl/dataops/snapshot/parser/NaverPlaceParser.class',parser)
            archive.writestr('BOOT-INF/classes/response-agent/menu-structure-profile-v1.json',parser)
            archive.writestr('BOOT-INF/classes/unrelated.class',other)

    def validate(self,**overrides):
        with patch('agent.response_change.full_registry.current_manifest.verify_deployment',return_value=self.deployment), \
            patch('agent.response_change.full_registry.current_validator.validate',return_value=self.report), \
            patch('agent.response_change.full_registry.current_validator.compile_and_run',return_value=self.full), \
            patch('agent.response_change.full_registry.probe_artifact',return_value=self.measure):
            return self.registry.validate_candidate(**{'base_release':'base','repository':self.repo,'review_id':'repair-one',
                'release_id':'next','generation':'next-generation','deployment_manifest':self.root/'manifest.json',
                'deployment_manifest_digest':SHA,'candidate_files':self.candidates,'fixtures':[], 'targets':[self.target],
                'oracle_loader':lambda _:deepcopy(self.oracle),'canary_capture_ids':['capture-one'],**overrides})

    def test_full_build_receipt_is_not_deployed_without_live_and_recovery(self):
        with self.assertRaisesRegex(ApprovalHold,'REGISTRY_ENTRY_UNAVAILABLE'):
            self.registry.committed_release('unknown')
        with patch('agent.response_change.full_registry.probe_artifact',return_value=self.measure):
            with self.assertRaisesRegex(ApprovalHold,'LIVE_BUILD_IDENTITY'):
                self.registry.register_base(build_receipt_digest=self.build['receipt_digest'],release_id='wrong',
                    observe_runtime=lambda:{**self.live,'artifact_sha256':SHA},verify_recovery=lambda *_:self.recovery)
            with self.assertRaisesRegex(ApprovalHold,'RECOVERY_UNVERIFIED'):
                self.registry.register_base(build_receipt_digest=self.build['receipt_digest'],release_id='wrong',
                    observe_runtime=lambda:{**self.live,'release_id':'wrong'},verify_recovery=lambda *_:{'backup_verified':True})

    def test_full_capture_limit_holds_before_build_or_oracle_calls(self):
        loader=Mock()
        with patch.object(self.registry,'committed_release') as release:
            with self.assertRaisesRegex(ApprovalHold,'CAPTURE_TARGET_BOUNDS'):
                self.validate(targets=[{**self.target,'capture_id':'capture-'+str(i)} for i in range(101)],oracle_loader=loader)
            release.assert_not_called()
            loader.assert_not_called()

    def test_parser_only_record_cannot_be_full_build(self):
        self.registry._put('builds',SHA,{'provenance':{'entire_jar_source_verified':False}})
        with self.assertRaisesRegex(ApprovalHold,'FULL_BUILD_RECEIPT_REQUIRED'):
            self.registry.build_receipt(SHA)

    def test_candidate_freezes_full_oracles_and_distinct_runtime_contract(self):
        candidate=self.validate();saved=self.registry.validated_candidate(candidate['validation_digest'])
        validation=self.registry.validation_document(candidate['validation_digest'])
        self.assertEqual('d'*64,validation['runtime_contract_digest'])
        self.assertEqual('f'*64,validation['semantic_source_contract_digest'])
        self.assertEqual(content_digest(self.parsed),validation['capture_oracles'][0]['expected_parsed_digest'])
        self.assertNotIn('payload_json',json.dumps(validation))
        self.assertEqual([PROFILE_PATH],list(saved['candidate_files']))
        plan=self.registry.replay_plan(candidate['validation_digest'])
        self.assertEqual(['capture-one'],plan['canary_capture_ids'])
        policy=self.registry.canary_policy(candidate['canary_policy_digest'])
        self.assertEqual(content_digest([{'capture_id':'capture-one','parsed_digest':content_digest(self.parsed)}]),policy['expected_result_digest'])

    def test_original_json_bytes_not_reserialized_payload_digest(self):
        self.oracle['payload_json']=json.dumps(json.loads(self.payload),separators=(',',':'))
        with self.assertRaisesRegex(ApprovalHold,'ORIGINAL_CAPTURE_BINDING_CHANGED'):self.validate()

    def test_java_canonical_digest_adopted_only_after_all_independent_values_match(self):
        self.full['cases'][0]['parsed_digest']='0'*64  # Java number serialization can differ from Python.
        candidate=self.validate()
        validation=self.registry.validation_document(candidate['validation_digest'])
        self.assertEqual('0'*64,validation['capture_oracles'][0]['expected_parsed_digest'])
        self.full['cases'][0]['actual']['name']='candidate invented value'
        with self.assertRaisesRegex(ApprovalHold,'WHOLE_CAPTURE_ORACLE_FAILED'):self.validate()

    def test_partial_oracle_and_candidate_derived_wrong_result_rejected(self):
        self.oracle['expected_parsed']={'menus':[]}
        with self.assertRaisesRegex(ApprovalHold,'COMPLETE_INDEPENDENT'):self.validate()
        self.oracle['expected_parsed']=self.parsed
        self.full['cases'][0]['actual']['phone']='unexpected'
        with self.assertRaisesRegex(ApprovalHold,'WHOLE_CAPTURE_ORACLE_FAILED'):self.validate()

    def test_whole_raw_not_ready_and_unrelated_jar_mutation_rejected(self):
        self.full['cases'][0]['actual']['diagnostics']['assessment']='HELD'
        with self.assertRaisesRegex(ApprovalHold,'WHOLE_CAPTURE_NOT_READY'):self.validate()
        self.jar(self.candidatejar,'new','modified unrelated service')
        self.report['artifact']['sha256']=self.hash(self.candidatejar)
        self.report.pop('report_digest');self.report['report_digest']=content_digest(self.report)
        with self.assertRaisesRegex(ApprovalHold,'UNRELATED_SERVICE_BYTES_CHANGED'):self.validate()

    def test_registry_artifact_tamper_holds(self):
        candidate=self.validate();Path(candidate['artifact_path']).write_bytes(b'tampered')
        with self.assertRaisesRegex(ApprovalHold,'CANDIDATE_ARTIFACT_CHANGED'):
            self.registry.validated_candidate(candidate['validation_digest'])

    def test_immutable_registration_and_private_storage(self):
        with self.assertRaisesRegex(ApprovalHold,'IMMUTABLE_REGISTRY_CONFLICT'):
            self.registry._put('releases','base',{**self.base,'generation':'different'})
        directory=self.root/'public';directory.mkdir(mode=0o755)
        with self.assertRaisesRegex(ApprovalHold,'PRIVATE_REGISTRY_REQUIRED'):FullReleaseRegistry(directory)

    def prepare(self):
        self.store=Mock();preparer=GitExactReviewPreparer(self.repo,repository='example/dataops')
        return ExactReviewPreparation(registry=self.registry,preparer=preparer,store=self.store,base_branch='main')

    def test_parser_publication_exact_idempotent_and_keeps_user_index(self):
        candidate=self.validate();publication=self.prepare()
        (self.repo/'app.txt').write_text('user staged');self.git('add','app.txt')
        index=(self.repo/'.git/index').read_bytes()
        arguments={'validation_digest':candidate['validation_digest'],'incident_id':'incident','run_id':'run','revision':1,'expires_at':'2027-01-01T00:00:00Z'}
        one=publication.parser(None,**arguments);two=publication.parser(None,**arguments)
        self.assertEqual(one,two);self.assertTrue(one.head_branch.startswith('fix/'))
        self.assertEqual(index,(self.repo/'.git/index').read_bytes());self.assertEqual(self.commit,self.git('rev-parse','HEAD'))
        self.assertEqual({PROFILE_PATH,'docs/response-agent-reviews/repair-one/review.md'},set(one.manifest.document['files']))
        self.store.freeze.assert_called_with(None,one.manifest,validated=True)
        self.assertTrue(self.git('log','-1','--format=%s',one.head_sha).startswith('🐛 fix:'))

    def test_data_review_only_created_exact_lineage_and_values(self):
        candidate=self.validate();publication=self.prepare()
        receipt={'outcome':'DONE','complete':True,'phase':'REPLAY','operating_writes':0,'manifest_digest':SHA,
            'replay_operation_id':'11111111-1111-1111-1111-111111111111','targets_digest':content_digest([self.target]),
            'runtime':{k:candidate[k] for k in ('release_id','artifact_sha256','parser_digest','profile_digest','contract_digest','database_identity')},
            'items':[{'capture_id':'capture-one','status':'PROPOSED','created_proposal_ids':[5],'reused_proposal_ids':[6]}]}
        target={'proposal_id':5,'store_id':1,'external_id':'123','operation':'UPDATE','field':'store.phone',
            'old_value':'123','new_value':'456','capture_id':'capture-one','capture_digest':self.target['payload_digest'],
            'release_id':'next','reprocess_item_id':receipt['replay_operation_id']}
        loader=Mock(return_value={'relation':'CREATED','state':'PENDING','target':target,
            'validation_digest':candidate['validation_digest'],'manifest_digest':SHA})
        arguments={'validation_digest':candidate['validation_digest'],'replay_receipt':receipt,'proposal_loader':loader,
            'review_id':'data-one','base_commit':self.commit,'incident_id':'incident','run_id':'run-data','revision':1,'expires_at':'2027-01-01T00:00:00Z'}
        request=publication.data(None,**arguments)
        self.assertEqual([target],request.manifest.document['targets']);self.assertEqual(1,loader.call_count)
        text=self.git('show',request.head_sha+':docs/response-agent-reviews/data-one/review.md')
        self.assertIn('"old_value": "123"',text);self.assertIn('"new_value": "456"',text)
        loader.return_value['relation']='REUSED'
        with self.assertRaisesRegex(ApprovalHold,'CREATED_PENDING'):publication.data(None,**arguments)
        loader.return_value['relation']='CREATED';loader.return_value['validation_digest']='f'*64
        with self.assertRaisesRegex(ApprovalHold,'REPLAY_APPROVAL_LINEAGE_CHANGED'):publication.data(None,**arguments)
        receipt['outcome']='UNKNOWN'
        with self.assertRaisesRegex(ApprovalHold,'REPLAY_COMPLETION_REQUIRED'):publication.data(None,**arguments)


@unittest.skipUnless(os.getenv('RUN_PG_AGENT_TESTS')=='true','isolated Docker PostgreSQL opt-in')
class PgReplayProposalLoaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import psycopg2
        cls.container='agent-review-loader-'+uuid.uuid4().hex[:10]
        subprocess.run(['docker','run','--rm','-d','--name',cls.container,'-e','POSTGRES_HOST_AUTH_METHOD=trust',
            '-e','POSTGRES_DB=review_test','-p','127.0.0.1::5432','postgres:16-alpine'],check=True,capture_output=True,timeout=30)
        try:
            port=subprocess.check_output(['docker','port',cls.container,'5432/tcp'],text=True).strip().rsplit(':',1)[1]
            cls.dsn=f'host=127.0.0.1 port={port} dbname=review_test user=postgres connect_timeout=2'
            for _ in range(40):
                try:
                    with psycopg2.connect(cls.dsn):break
                except psycopg2.OperationalError:time.sleep(.2)
            else:raise RuntimeError('isolated PostgreSQL unavailable')
            with psycopg2.connect(cls.dsn) as connection,connection.cursor() as c:
                c.execute('''CREATE TABLE response_replay_proposal(operation_id uuid,capture_id text,proposal_id bigint,relation text,original_proposal_json jsonb);
                    CREATE TABLE response_replay_run(operation_id uuid,release_id text,manifest_digest text);
                    CREATE TABLE response_agent_review_manifest(manifest_digest text,kind text,document jsonb);
                    CREATE TABLE response_replay_receipt(operation_id uuid,capture_id text,phase text,status text);
                    CREATE TABLE response_replay_completion(operation_id uuid,phase text,passed boolean);
                    CREATE TABLE response_capture(capture_id text,store_id bigint,external_id text,payload_digest text,payload text);
                    CREATE TABLE change_proposal(id bigint,store_id bigint,status text,target text,old_value jsonb,new_value jsonb);
                    CREATE ROLE review_reader LOGIN; GRANT SELECT ON ALL TABLES IN SCHEMA public TO review_reader;''')
        except BaseException:
            subprocess.run(['docker','rm','-f',cls.container],capture_output=True)
            raise

    @classmethod
    def tearDownClass(cls):subprocess.run(['docker','rm','-f',cls.container],capture_output=True)

    def test_exact_json_types_and_original_values_from_readonly_role(self):
        import psycopg2
        from psycopg2.extras import Json
        operation='11111111-1111-1111-1111-111111111111'
        payload='{"menus":[]}'
        with psycopg2.connect(self.dsn) as connection,connection.cursor() as c:
            c.execute("INSERT INTO response_replay_run VALUES (%s,'next',%s)",(operation,SHA))
            c.execute("INSERT INTO response_agent_review_manifest VALUES (%s,'PARSER_REPAIR',%s)",(SHA,Json({'validation_digest':SHA})))
            c.execute("INSERT INTO response_replay_receipt VALUES (%s,'capture','REPLAY','PROPOSED')",(operation,))
            c.execute("INSERT INTO response_replay_completion VALUES (%s,'REPLAY',true),(%s,'CANARY',true)",(operation,operation))
            c.execute("INSERT INTO response_capture VALUES ('capture',1,'123',%s,%s)",(sha256(payload.encode()).hexdigest(),payload))
        loader=PgReplayProposalLoader(lambda:psycopg2.connect(self.dsn.replace('user=postgres','user=review_reader')))
        for old,new in [('001-123','001-456'),(None,0),(0,5300),([], [{'day':'MON','closed':True}])]:
            with psycopg2.connect(self.dsn) as connection,connection.cursor() as c:
                c.execute('DELETE FROM change_proposal;DELETE FROM response_replay_proposal')
                c.execute("INSERT INTO change_proposal VALUES (5,1,'PENDING','store.phone',%s,%s)",(Json(old),Json(new)))
                c.execute("INSERT INTO response_replay_proposal VALUES (%s,'capture',5,'CREATED',%s)",(operation,Json({
                    'target':'store.phone','oldValue':json.dumps(old),'newValue':json.dumps(new)})))
            row=loader(operation,'capture',5)
            self.assertEqual(old,row['target']['old_value']);self.assertEqual(new,row['target']['new_value'])
            self.assertEqual('CREATED',row['relation'])
        with psycopg2.connect(self.dsn) as connection,connection.cursor() as c:
            c.execute("UPDATE change_proposal SET new_value='42'")
        with self.assertRaisesRegex(ApprovalHold,'REPLAY_PROPOSAL_VALUE_CHANGED'):loader(operation,'capture',5)


if __name__=='__main__':unittest.main()
