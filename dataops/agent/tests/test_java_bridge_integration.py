"""Opt-in real Boot JAR + disposable PostgreSQL/PostGIS bridge test.

Approval/publication rows are explicitly synthetic fixtures, never GitHub approvals.
No running service, launchd job, external API, or operating database is touched.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import uuid
import zipfile
from datetime import datetime, timedelta, timezone

import psycopg2
from psycopg2.extras import Json
from psycopg2 import sql

from response_change.approval import (canonical, content_digest, ReviewManifest, PublicationBinding, PullRequestSnapshot,
    ApprovalVerifier, PgReviewStore, DIGEST_FIELDS, render_data_manifest, render_data_preview)
from response_change.data_execution import ExactDataApplyController
from response_change.java_bridge import JavaBridgeConfig, JavaReplayAdapter
from response_change.release import PgReleaseJournal, ReleaseHold
from response_change.provisioning import provision_roles, CONTROLLER, REPLAY, APPLY, ROLES

REPO=Path(__file__).resolve().parents[2]
DSN=os.environ.get('DATAOPS_BRIDGE_E2E_DSN','')
JAR=os.environ.get('DATAOPS_BRIDGE_E2E_JAR','')
JAVA=Path(os.environ.get('JAVA_HOME','/invalid'))/'bin/java'
ROLE_MATRIX=os.environ.get('DATAOPS_BRIDGE_E2E_ROLE_MATRIX')=='1'
HASH='a'*64

@unittest.skipUnless(re.fullmatch(r'host=127\.0\.0\.1 port=\d+ dbname=agent_bridge_test user=postgres',DSN) and JAR and JAVA.is_file(),
    'Explicit disposable bridge DB, built JAR and Java17 required')
class JavaBridgeIntegrationTest(unittest.TestCase):
    def test_real_boot_jar_canary_replay_and_readonly_status_use_durable_receipts(self):
        work=Path(tempfile.mkdtemp(prefix='agent-java-bridge-e2e-')).resolve()
        os.chmod(work,0o700)
        self.addCleanup(shutil.rmtree,work)
        self.owned_role_marker=None
        if ROLE_MATRIX:self.addCleanup(self.drop_owned_roles)
        artifact=work/'candidate.jar';shutil.copyfile(JAR,artifact);os.chmod(artifact,0o600)
        admin=psycopg2.connect(DSN,connect_timeout=3);admin.autocommit=True
        dataops_db='agent_bridge_data_'+uuid.uuid4().hex[:12]
        with admin.cursor() as c:c.execute('CREATE DATABASE '+dataops_db)
        admin.close()
        self.addCleanup(self.drop_database,dataops_db)
        dataops_dsn=DSN.replace('dbname=agent_bridge_test','dbname='+dataops_db)
        self.connection=psycopg2.connect(dataops_dsn,connect_timeout=3)
        self.addCleanup(self.connection.close)
        with self.connection,self.connection.cursor() as c:
            c.execute("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='store_sync_state'")
            self.assertEqual(c.fetchone()[0],0,'E2E requires an empty disposable DataOps database')
            for migration in sorted((REPO/'src/main/resources/db/migration').glob('V*.sql')):
                c.execute(migration.read_text())
            if ROLE_MATRIX:c.execute('CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY,version text)')
        control_dsn=dataops_dsn
        dataops_role='postgres';dataops_password=''
        if ROLE_MATRIX:
            credentials_path=work/'roles.json'
            provision_roles(lambda:psycopg2.connect(dataops_dsn,connect_timeout=3),credentials_path)
            credentials=json.loads(credentials_path.read_text())
            self.owned_role_marker='bbanggut-dataops-agent:'+credentials['provisioning_id']
            self.role_credentials=credentials['roles']
            parameters=psycopg2.extensions.parse_dsn(dataops_dsn)
            parameters.update(user=CONTROLLER,password=credentials['roles'][CONTROLLER]['password'])
            control_dsn=psycopg2.extensions.make_dsn(**parameters)
            dataops_role=REPLAY;dataops_password=credentials['roles'][REPLAY]['password']
        serving_db='agent_bridge_serving_'+uuid.uuid4().hex[:12]
        self.connection.autocommit=True
        with self.connection.cursor() as c:c.execute('CREATE DATABASE '+serving_db)
        self.connection.autocommit=False
        self.addCleanup(self.drop_database,serving_db)
        serving_dsn=DSN.replace('dbname=agent_bridge_test','dbname='+serving_db)
        serving=psycopg2.connect(serving_dsn);self.addCleanup(serving.close)
        with serving,serving.cursor() as c:
            c.execute('CREATE EXTENSION IF NOT EXISTS postgis')
            c.execute('''CREATE TABLE store(id bigint PRIMARY KEY,name text,address text,phone text,status text,naver_place_id text,
              instagram_handle text,category text,location geography(Point,4326),region_1st text,region_2nd text,region_3rd text,updated_at timestamp);
              CREATE TABLE menu(id bigint PRIMARY KEY,store_id bigint,name text,normalized_name text,price integer,description text,deleted_at timestamp,
              like_count bigint,menu_category_id bigint,created_at timestamp,updated_at timestamp,created_by_member_id bigint);
              CREATE TABLE menu_category(id bigint,name text);
              CREATE TABLE store_name_alias(id bigint,store_id bigint,alias text,created_at timestamp);
              CREATE TABLE store_business_hour(id bigint,store_id bigint,day_of_week text,open_time time,close_time time,is_closed boolean);
              CREATE TABLE contribution_report(id bigint,report_type text,status text);
              CREATE TABLE menu_contribution_report(id bigint,contribution_report_id bigint,store_id bigint,menu_id bigint,name text,status text);
              INSERT INTO store(id,name,status,naver_place_id) VALUES (1,'old test bakery','ACTIVE','123');
              INSERT INTO menu(id,store_id,name,normalized_name,price) VALUES (1,1,'빵','빵',5300);
              CREATE TABLE write_audit(id bigserial PRIMARY KEY);
              CREATE FUNCTION count_store_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO write_audit DEFAULT VALUES; RETURN NEW; END $$;
              CREATE TRIGGER write_audit AFTER UPDATE ON store FOR EACH ROW EXECUTE FUNCTION count_store_write();''')
        raw={'PlaceDetailBase:123':{'id':'123','name':'빵집','phone':'02-1234-5678'},'ROOT_QUERY':{'placeDetail({"input":{"id":"123"}})':{
            'placeMenus':{'items':[{'__ref':'PlaceMenuItem:a'}],'menuCount':1},
            'newBusinessHours':[{'businessHours':[{'day':'매일','businessHours':{'start':'09:00','end':'18:00'}}]}]}},
            'PlaceMenuItem:a':{'id':'a','__typename':'PlaceMenuItem','name':'빵','price':{'displayText':'5,300원'},'images':[{'url':'image'}]}}
        raw_text=canonical(raw);raw_path=work/'raw.json';raw_path.write_text(raw_text)
        # Fixed independent full ParsedPlace oracle, not copied from candidate output.
        expected={key:None for key in ('name','phone','address','roadAddress','longitude','latitude','businessStatusDescription','businessHoursRaw','instagramHandle')}
        expected.update(name='빵집',phone='02-1234-5678',businessHoursRaw='[{"businessHours":{"end":"18:00","start":"09:00"},"day":"매일"}]',
            menus=[{'name':'빵','price':'5300','description':None,'imageUrls':['image'],'groupName':None}],baeminMenus=[])
        meta=self.probe(work,artifact,raw_path)
        self.assertEqual(meta['parsed'],expected)
        artifact_digest=hashlib.sha256(artifact.read_bytes()).hexdigest()
        dataops_id=self.database_identity(self.connection);serving_id=self.database_identity(serving)
        composite=content_digest({'dataops':dataops_id,'serving':serving_id})
        observed='2026-10-14T00:00:00Z'
        target={'capture_id':'capture-e2e','payload_digest':hashlib.sha256(raw_text.encode()).hexdigest(),'store_id':1,
            'external_id':'123','observed_at':observed,'attempt_seq':0}
        oracle={**target,'expected_parsed_digest':meta['parsed_digest'],'oracle_kind':'REVIEWED_CONTRACT',
            'oracle_reference':'test_java_bridge_integration.py fixed 11-field oracle','oracle_digest':content_digest(expected)}
        policy={'mode':'READ_ONLY','targets':[target],'expected_result_digest':content_digest([{'capture_id':target['capture_id'],'parsed_digest':meta['parsed_digest']}])}
        plan={'schema_version':1,'targets':[oracle],'canary_capture_ids':['capture-e2e']}
        validation={'schema_version':1,'kind':'FULL_SERVICE_CANDIDATE_VALIDATION','base_build_receipt_digest':HASH,'base_artifact_sha256':HASH,
            'base_commit':'1'*40,'candidate_artifact_sha256':artifact_digest,'candidate_parser_digest':meta['parser_digest'],
            'candidate_profile_digest':meta['profile_digest'],'runtime_contract_digest':meta['contract_digest'],
            'semantic_source_contract_digest':HASH,'current_validation_digest':HASH,'candidate_tree_digest':HASH,'patch_digest':HASH,
            'build_recipe_digest':HASH,'capture_oracles':[oracle]}
        manifest={'kind':'PARSER_REPAIR','scope':'DEPLOY_REPLAY_PROPOSE','expires_at':(datetime.now(timezone.utc)+timedelta(minutes=10)).isoformat(),
            'base_commit':'1'*40,'artifact_sha256':artifact_digest,'structure_profile_digest':meta['profile_digest'],
            'semantic_contract_digest':meta['contract_digest'],'candidate_tree_digest':HASH,'patch_digest':HASH,'build_recipe_digest':HASH,
            'row_limit':1,'replay_target_manifest_digest':content_digest([target]),'targets':[target],
            'validation_digest':content_digest(validation),'canary_policy_digest':content_digest(policy)}
        manifest_digest=content_digest(manifest);operation='release-'+uuid.uuid4().hex;service='e2e-service';generation='e2e-generation'
        evidence={'manifest_digest':manifest_digest,'scope':'DEPLOY_REPLAY_PROPOSE','repository':'synthetic/fixture','pr_number':1,'head_sha':'2'*40}
        approval_digest=content_digest(evidence)
        candidate={'release_id':'synthetic-fixture-release','generation':generation,'artifact_sha256':artifact_digest,**{k:meta[k] for k in
            ('parser_digest','profile_digest','contract_digest','contract_version','policy_digest')},'dataops_database_identity':dataops_id,
            'serving_database_identity':serving_id,'database_identity':composite,'validation_digest':content_digest(validation),
            'canary_policy_digest':content_digest(policy),'plan_digest':content_digest(plan),'artifact_path':str(artifact)}
        run=str(uuid.uuid4())
        with self.connection,self.connection.cursor() as c:
            # Test-only authority rows use the real schemas, without claiming a provider-approved event.
            c.execute("INSERT INTO response_incident(fingerprint,source,parser_digest,contract_version,diagnostics,model_eligible) VALUES (%s,'NAVER',%s,%s,'fixture',false)",(HASH,meta['parser_digest'],meta['contract_version']))
            c.execute("INSERT INTO response_agent_run(run_id,fingerprint,parser_digest,contract_version,state,owner,lease_until,budget_usd,run_directory) VALUES (%s,%s,%s,%s,'HELD','fixture',now(),0.5,'fixture')",(run,HASH,meta['parser_digest'],meta['contract_version']))
            c.execute("INSERT INTO response_agent_review_manifest(manifest_digest,run_id,revision,kind,scope,document,expires_at,review_state) VALUES (%s,%s,1,'PARSER_REPAIR','DEPLOY_REPLAY_PROPOSE',%s,%s,'VALIDATED')",(manifest_digest,run,Json(manifest),manifest['expires_at']))
            c.execute("INSERT INTO response_agent_review_publication(manifest_digest,repository,pr_number,head_sha) VALUES (%s,'synthetic/fixture',1,%s)",(manifest_digest,'2'*40))
            c.execute("INSERT INTO response_agent_review_approval(approval_digest,manifest_digest,event_key,actor_id,scope,evidence,state,operation_id,consumed_at) VALUES (%s,%s,'synthetic-merge',1,'DEPLOY_REPLAY_PROPOSE',%s,'CONSUMED',%s,now())",(approval_digest,manifest_digest,Json(evidence),operation))
            c.execute("INSERT INTO response_agent_release_service(service_id) VALUES (%s)",(service,))
            c.execute("INSERT INTO response_agent_release_operation(operation_id,service_id,approval_digest,manifest_digest,request_digest,approval_scope,state,pending_action) VALUES (%s,%s,%s,%s,%s,'DEPLOY_REPLAY_PROPOSE','CANARY_IN_FLIGHT','canary')",(operation,service,approval_digest,manifest_digest,HASH))
            c.execute("""INSERT INTO response_agent_active_runtime(service_id,generation,release_id,artifact_sha256,parser_digest,profile_digest,contract_digest,
              database_identity,dataops_database_identity,serving_database_identity,state,fence_epoch) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'ACTIVE',1)""",
                (service,generation,candidate['release_id'],artifact_digest,meta['parser_digest'],meta['profile_digest'],meta['contract_digest'],composite,dataops_id,serving_id))
            c.execute("INSERT INTO store_sync_state(store_id,source,external_id) VALUES (1,'NAVER','123')")
            c.execute("""INSERT INTO response_capture(capture_id,request_digest,store_id,source,external_id,observed_at,received_at,attempt_seq,payload,payload_digest,status,target_verified)
              VALUES ('capture-e2e',%s,1,'NAVER','123',%s,%s,0,%s,%s,'HELD',true)""",(HASH,observed,observed,raw_text,target['payload_digest']))
        port=re.search(r'port=(\d+)',DSN).group(1)
        properties=work/'bridge-fixture.properties'
        properties.write_text(f'''spring.profiles.active=e2e
spring.datasource.url=jdbc:postgresql://127.0.0.1:{port}/{dataops_db}
spring.datasource.username={dataops_role}
spring.datasource.password={dataops_password}
dataops.prod-db.url=jdbc:postgresql://127.0.0.1:{port}/{serving_db}
dataops.prod-db.username=postgres
dataops.prod-db.password=
dataops.agent.apply.jdbc-url=jdbc:postgresql://127.0.0.1:{port}/{serving_db}
dataops.agent.apply.username=postgres
dataops.agent.apply.password=disposable-fixture-only
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false
''');os.chmod(properties,0o600)
        class Registry:
            def validated_candidate(self,digest):return candidate
            def validation_document(self,digest):return validation
            def replay_plan(self,digest):return plan
            def canary_policy(self,digest):return policy
        adapter=JavaReplayAdapter(JavaBridgeConfig(JAVA,properties,work,90),Registry())
        journal=PgReleaseJournal(lambda:psycopg2.connect(control_dsn,connect_timeout=3))
        base={'service_id':service,'operation_id':operation,'manifest_digest':manifest_digest,'candidate':candidate,
            'writes_enabled':False,'prepared':{'candidate_path':str(artifact)},'targets':[target],'targets_digest':content_digest([target])}
        before=self.snapshot(serving)
        try:
            with journal.service_lock(service) as fence:
                command={**base,'action':'canary'}
                result=adapter.canary(command,fence)
                self.assertTrue(result['passed']);self.assertEqual(result['items'][0]['status'],'PARSED')
                self.assertEqual(result['result_digest'],policy['expected_result_digest'])
                with self.connection,self.connection.cursor() as c:c.execute("UPDATE response_agent_release_operation SET state='REPLAY_IN_FLIGHT',pending_action='replay' WHERE operation_id=%s",(operation,))
                result=adapter.replay({**base,'action':'replay'},fence)
                self.assertTrue(result['passed']);self.assertEqual(result['items'][0]['status'],'PROPOSED')
                self.assertGreater(len(result['items'][0]['created_proposal_ids']),0)
                with self.assertRaisesRegex(ReleaseHold,'REPLAY_RECONCILIATION_REQUIRED'):adapter.replay({**base,'action':'replay'},fence)
                with self.connection,self.connection.cursor() as c:c.execute("UPDATE response_agent_review_manifest SET review_state='REJECTED',expires_at=now()-interval '1 second'")
                recovered=adapter.reconcile(operation,'replay',fence)
                self.assertEqual(recovered['outcome'],'DONE');self.assertTrue(recovered['result']['passed'])
            self.assertEqual(before,self.snapshot(serving))
            with self.connection,self.connection.cursor() as c:
                c.execute("SELECT count(*) FROM place_snapshot");self.assertEqual(c.fetchone()[0],0)
                c.execute("SELECT visit_count,change_count FROM store_sync_state");self.assertEqual(c.fetchone(),(0,0))
                c.execute("SELECT count(*) FROM response_replay_receipt");self.assertEqual(c.fetchone()[0],2)
                c.execute("SELECT count(*) FROM response_agent_execution_binding");self.assertEqual(c.fetchone()[0],1)
                c.execute("SELECT count(*) FROM change_proposal");proposal_count=c.fetchone()[0]
            report={'kind':'ISOLATED_REAL_BOOT_JAR_BRIDGE_TEST','github_approval':'SYNTHETIC_FIXTURE_ONLY','artifact_sha256':artifact_digest,
                'verified_at':datetime.now(timezone.utc).isoformat(),
                'build_provenance':'LOCAL_WORKTREE_TEST_BUILD_NOT_COMMITTED_RELEASE',
                'git_head_context':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
                'source_tree_dirty':bool(subprocess.check_output(['git','status','--porcelain','--','src/main','build.gradle','settings.gradle'],cwd=REPO,text=True).strip()),
                'parser_digest':meta['parser_digest'],'profile_digest':meta['profile_digest'],'runtime_contract_digest':meta['contract_digest'],
                'canary':'PARSED','replay':'PROPOSED','status_after_review_revocation':'DONE','duplicate_execution':'HELD',
                'created_proposals':proposal_count,'replay_serving_writes':0,'production_operating_writes':0,'new_observations':0,'durable_receipts':2,'binding_count':1}
            apply_properties=properties
            if ROLE_MATRIX:
                apply_properties=work/'apply-fixture.properties'
                apply_properties.write_text(properties.read_text().replace('spring.datasource.username='+REPLAY,
                    'spring.datasource.username='+APPLY).replace('spring.datasource.password='+dataops_password,
                    'spring.datasource.password='+self.role_credentials[APPLY]['password']))
                apply_properties.chmod(0o600)
            report['data_apply']=self.apply_phase(control_dsn,serving,work,apply_properties,candidate,Registry(),run,target,operation,service)
            report['dataops_roles']={'controller':CONTROLLER,'replay':REPLAY,'apply':APPLY} if ROLE_MATRIX else {'all':'postgres'}
            output=REPO/'artifacts/response-agent/reviews'/('java-bridge-role-e2e-20261014.json' if ROLE_MATRIX else 'java-bridge-e2e-20261014.json');output.parent.mkdir(parents=True,exist_ok=True)
            output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        except Exception:
            logs='\n'.join(path.read_text(errors='replace')[-14000:] for path in sorted(work.rglob('*.log')))
            receipts='\n'.join(path.read_text() for path in work.rglob('latest.json'))
            self.fail('Isolated Java bridge failed; receipts:\n'+receipts+'\nlogs:\n'+logs)

    def apply_phase(self,dsn,serving,work,properties,candidate,registry,run,capture,release_operation,service):
        with self.connection,self.connection.cursor() as c:
            c.execute("UPDATE response_agent_release_operation SET state='ACTIVE',pending_action=NULL WHERE operation_id=%s",(release_operation,))
            c.execute("SELECT proposal_id FROM response_replay_proposal p JOIN change_proposal q ON q.id=p.proposal_id WHERE q.target='store.name'");name_id=c.fetchone()[0]
            c.execute("SELECT proposal_id FROM response_replay_proposal p JOIN change_proposal q ON q.id=p.proposal_id WHERE q.target='store.phone'");phone_id=c.fetchone()[0]
            c.execute("SELECT replay_operation_id FROM response_agent_execution_binding");replay_id=str(c.fetchone()[0])
        class SyntheticProvider:
            calls=0
            def read_snapshot(self,binding,manifest):
                self.calls+=1
                return PullRequestSnapshot(binding.repository,binding.pr_number,binding.head_sha,True,str(binding.pr_number%10)*40,7,
                    (datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),False,canonical(manifest.document['files']),
                    HASH,HASH,binding.repository,canonical(manifest.document['files']))
        provider=SyntheticProvider()
        controller=ExactDataApplyController(connect=lambda:psycopg2.connect(dsn,connect_timeout=3),registry=registry,provider=provider,
            verifier=ApprovalVerifier(allowed_repository='synthetic/fixture',allowed_actor_ids={7}),config=JavaBridgeConfig(JAVA,properties,work,90))
        def document(proposal_id,field,old,next,revision):
            target={'proposal_id':proposal_id,'store_id':1,'external_id':'123','operation':'UPDATE','field':field,'old_value':old,'new_value':next,
                'capture_id':capture['capture_id'],'capture_digest':capture['payload_digest'],'release_id':candidate['release_id'],'reprocess_item_id':replay_id}
            prefix=f'docs/response-agent-reviews/data-fixture-{revision}/'
            preview=hashlib.sha256(render_data_preview([target]).encode()).hexdigest()
            doc={name:HASH for name in DIGEST_FIELDS}
            doc.update(schema_version=1,review_id=f'data-fixture-{revision}',kind='DATA_APPLY',scope='APPLY_EXACT_MANIFEST',incident_id='fixture-incident',
                run_id=run,revision=revision,base_release=candidate['release_id'],base_commit='1'*40,
                expires_at=(datetime.now(timezone.utc)+timedelta(minutes=10)).isoformat(),targets=[target],row_limit=1,preview_digest=preview,
                files={prefix+'review.md':preview,prefix+'data-manifest.json':hashlib.sha256(render_data_manifest([target]).encode()).hexdigest()},
                replay_target_manifest_digest=content_digest([target]),artifact_sha256=candidate['artifact_sha256'],structure_profile_digest=candidate['profile_digest'],
                semantic_contract_digest=candidate['contract_digest'],validation_digest=candidate['validation_digest'],canary_policy_digest=candidate['canary_policy_digest'])
            manifest=ReviewManifest(doc);binding=PublicationBinding('synthetic/fixture',10+revision,str(revision+2)*40,manifest.digest)
            with self.connection,self.connection.cursor() as c:
                store=PgReviewStore(lambda:psycopg2.connect(dsn));store.freeze(c,manifest,validated=True);store.bind_publication(c,binding)
            return manifest,binding
        manifest,binding=document(name_id,'store.name','old test bakery','빵집',1)
        applied=controller.advance(manifest,binding)
        self.assertEqual(applied['state'],'APPLIED',applied);self.assertEqual(applied['applied_count'],1)
        with serving,serving.cursor() as c:
            c.execute('SELECT name,phone FROM store WHERE id=1');self.assertEqual(c.fetchone(),('빵집',None))
            c.execute('SELECT count(*) FROM write_audit');self.assertEqual(c.fetchone()[0],1)
        calls=provider.calls
        self.assertEqual(controller.advance(manifest,binding),applied)
        self.assertEqual(provider.calls,calls)
        with serving,serving.cursor() as c:
            c.execute('SELECT count(*) FROM write_audit');self.assertEqual(c.fetchone()[0],1)
        # A new separately approved exact phone proposal cannot use an old running generation.
        next_manifest,next_binding=document(phone_id,'store.phone',None,'02-1234-5678',2)
        with self.connection,self.connection.cursor() as c:
            c.execute("UPDATE response_agent_active_runtime SET generation='foreign-generation' WHERE service_id=%s",(service,))
        held=controller.advance(next_manifest,next_binding)
        self.assertEqual(held['state'],'HELD',held);self.assertEqual(held['items'][0]['status'],'NOT_ATTEMPTED')
        with serving,serving.cursor() as c:
            c.execute('SELECT phone FROM store WHERE id=1');self.assertIsNone(c.fetchone()[0])
            c.execute('SELECT count(*) FROM write_audit');self.assertEqual(c.fetchone()[0],1)
        with self.connection,self.connection.cursor() as c:
            c.execute('SELECT status FROM change_proposal WHERE id=%s',(phone_id,));self.assertEqual(c.fetchone()[0],'PENDING')
            c.execute('SELECT count(*) FROM response_agent_data_apply_item');self.assertEqual(c.fetchone()[0],1)
            c.execute('SELECT count(*) FROM apply_log');self.assertEqual(c.fetchone()[0],1)
        return {'approval':'SYNTHETIC_SEPARATE_DATA_FIXTURE','exact_name_cas':'APPLIED','isolated_serving_updates':1,'store_update_trigger_count':1,
            'duplicate_additional_writes':0,'generation_changed_phone':'NOT_ATTEMPTED','phone_still_pending':True,'apply_log_count':1}

    @staticmethod
    def drop_database(name):
        if not re.fullmatch(r'agent_bridge_(?:data|serving)_[0-9a-f]{12}',name):
            raise AssertionError('Refusing to clean a database not created by this test')
        connection=psycopg2.connect(DSN,connect_timeout=3)
        try:
            connection.autocommit=True
            with connection.cursor() as cursor:cursor.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(name)))
        finally:connection.close()

    def drop_owned_roles(self):
        if self.owned_role_marker is None:return
        with psycopg2.connect(DSN,connect_timeout=3) as connection,connection.cursor() as cursor:
            cursor.execute("SELECT rolname,shobj_description(oid,'pg_authid') FROM pg_roles WHERE rolname=ANY(%s)",(list(ROLES),))
            rows=cursor.fetchall()
            if len(rows)!=len(ROLES) or any(marker!=self.owned_role_marker for _,marker in rows):
                raise AssertionError('Refusing to clean roles not owned by this isolated fixture')
            for role in ROLES:cursor.execute(sql.SQL('DROP ROLE {}').format(sql.Identifier(role)))

    @staticmethod
    def snapshot(connection):
        with connection,connection.cursor() as c:
            c.execute("SELECT row_to_json(s)::text FROM store s ORDER BY id");stores=c.fetchall()
            c.execute("SELECT row_to_json(m)::text FROM menu m ORDER BY id");menus=c.fetchall()
            c.execute("SELECT row_to_json(h)::text FROM store_business_hour h ORDER BY id");hours=c.fetchall()
            return stores,menus,hours
    @staticmethod
    def database_identity(connection):
        with connection,connection.cursor() as c:
            c.execute("SELECT control.system_identifier::text,d.oid::text,d.datname FROM pg_control_system() control JOIN pg_database d ON d.datname=current_database()")
            row=c.fetchone();return hashlib.sha256(('serving-postgres-v1\n'+'\n'.join(row)+'\n').encode()).hexdigest()
    @staticmethod
    def probe(work,artifact,raw):
        classes=work/'unpacked';classes.mkdir()
        with zipfile.ZipFile(artifact) as jar:jar.extractall(classes)
        source=work/'BridgeFixtureProbe.java'
        source.write_text('''import sweetgirl.dataops.runtimeattestation.RuntimeMeasurement;
import sweetgirl.dataops.snapshot.parser.*;
import sweetgirl.dataops.snapshot.service.*;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.reconcile.policy.*;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import tools.jackson.databind.ObjectMapper;
public class BridgeFixtureProbe { public static void main(String[] a) throws Exception {
 var m=new ObjectMapper();var p=new NaverPlaceParser();var d=new ResponseDiagnostics(m,p,new BusinessHoursParser(),new MenuEvidenceParser(m));
 var parsed=m.valueToTree(p.parse(m.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(a[0])))));
 var result=m.createObjectNode().put("parser_digest",d.runtimeParserDigest()).put("profile_digest",d.structureProfile().digest())
 .put("contract_digest",RuntimeMeasurement.semanticContractDigest()).put("contract_version",ResponseDiagnostics.CONTRACT)
 .put("policy_digest",ResponseDigests.sha256("capture-replay-policy-v1\\nnaver-reconcile-2026-10-13-scoped\\n"+
 ResponseDigests.classes(ReconcileService.class,MergePolicy.class,BeverageFilter.class,MenuCategoryClassifier.class,BusinessHoursParser.class,ProposalCommandService.class)))
 .put("parsed_digest",ResponseDigests.sha256(ResponseDigests.canonical(parsed,m)));result.set("parsed",parsed);System.out.println(m.writeValueAsString(result));
}}''')
        cp=str(classes/'BOOT-INF/classes')+':'+str(classes/'BOOT-INF/lib/*')
        subprocess.run([str(JAVA.with_name('javac')),'-cp',cp,'-d',str(work),str(source)],check=True,capture_output=True,text=True)
        output=subprocess.run([str(JAVA),'-cp',str(work)+':'+cp,'BridgeFixtureProbe',str(raw)],check=True,capture_output=True,text=True)
        return json.loads(output.stdout)

if __name__=='__main__':unittest.main()
