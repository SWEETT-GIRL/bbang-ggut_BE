"""Opt-in authorization tests against a dedicated disposable PostgreSQL database."""
from contextlib import closing
import copy
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
import uuid
from unittest.mock import patch

import psycopg2
from psycopg2 import sql

from agent.response_change.host_runtime import write_json
from agent.response_change.provisioning import (APPLY, CONTROLLER, PUBLISHER, REPLAY, ROLES, SERVICE, WORKER,
    provision_roles, _password_matches)
from agent.response_change.release import ReleaseHold

ROOT=Path(__file__).resolve().parents[3]
DSN=os.environ.get("DATAOPS_ROLE_TEST_DSN", "")
SAFE=re.fullmatch(r"host=127\.0\.0\.1 port=\d+ dbname=agent_role_test user=postgres password=isolated_test",DSN)
RUN="12345678-1234-1234-1234-123456789012"


def collection_sql(relative_path, prefix):
    """Exercise the collection JDBC statements themselves, including conflict paths."""
    source=(ROOT/'src/main/java/sweetgirl/dataops'/relative_path).read_text()
    statements=re.findall(r'"""(.*?)"""|"([^"\n]*)"',source,re.DOTALL)
    matches=[(block or line).strip() for block,line in statements if (block or line).strip().startswith(prefix)]
    if len(matches)!=1:raise AssertionError('collection SQL must identify exactly one production statement: '+prefix)
    return matches[0].replace('?', '%s')


@unittest.skipUnless(SAFE,"Explicit isolated agent_role_test PostgreSQL required")
class ProvisioningPostgresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.addClassCleanup(cls.temp.cleanup)
        cls.root=Path(cls.temp.name).resolve();cls.root.chmod(0o700);cls.path=cls.root/"roles.json"
        with closing(psycopg2.connect(DSN)) as db,db,db.cursor() as c:
            c.execute("SELECT count(*) FROM pg_tables WHERE schemaname='public'")
            if c.fetchone()[0]:raise RuntimeError("disposable empty database required")
            c.execute("SELECT count(*) FROM pg_roles WHERE rolname=ANY(%s)",(list(ROLES),))
            if c.fetchone()[0]:raise RuntimeError("disposable cluster without managed roles required")
            for migration in sorted((ROOT/"src/main/resources/db/migration").glob("V*.sql")):c.execute(migration.read_text())
            c.execute("CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY,version text)")
            c.execute("CREATE TABLE store(id bigint PRIMARY KEY,name text)") # deliberately outside DataOps allowlist
            c.execute("INSERT INTO store VALUES (1,'not granted')")
            c.execute("INSERT INTO store_sync_state(store_id,source,external_id) VALUES (1,'NAVER','123')")
            c.execute("INSERT INTO response_incident VALUES (%s,'NAVER','parser','contract','{}',true,now())",('a'*64,))
            c.execute("""INSERT INTO response_agent_run(run_id,fingerprint,parser_digest,contract_version,state,owner,lease_until,budget_usd,run_directory)
                VALUES (%s,%s,'parser','contract','AWAITING_REVIEW','fixture',now()+interval '1 hour',.5,'fixture')""",(RUN,'a'*64))
            for digest,kind,scope in [('b'*64,'REPORT_ONLY','NONE'),('c'*64,'PARSER_REPAIR','DEPLOY_REPLAY_PROPOSE')]:
                c.execute("""INSERT INTO response_agent_review_manifest(manifest_digest,run_id,revision,kind,scope,document,expires_at,review_state)
                    VALUES(%s,%s,1,%s,%s,'{}',now()+interval '1 hour','VALIDATED')""",(digest,RUN,kind,scope))
            c.execute("INSERT INTO response_agent_review_publication VALUES (%s,'test/repo',1,%s,now())",('c'*64,'d'*40))
            c.execute("""INSERT INTO response_agent_review_approval(approval_digest,manifest_digest,event_key,actor_id,scope,evidence,state)
                VALUES(%s,%s,'test-event',1,'DEPLOY_REPLAY_PROPOSE','{}','APPROVED')""",('e'*64,'c'*64))
        cls.metadata=provision_roles(lambda:psycopg2.connect(DSN),cls.path)
        cls.credentials=json.loads(cls.path.read_text())

    @classmethod
    def role_connection(cls,role):
        params=psycopg2.extensions.parse_dsn(DSN)
        params.update(user=role,password=cls.credentials['roles'][role]['password'])
        return psycopg2.connect(**params)

    def execute(self,role,query,args=()):
        with closing(self.role_connection(role)) as db:
            try:
                with db.cursor() as c:
                    c.execute(query,args)
                    return c.fetchall() if c.description else c.rowcount
            finally:db.rollback()

    def denied(self,role,query,args=()):
        with self.assertRaises(psycopg2.errors.InsufficientPrivilege):self.execute(role,query,args)

    def test_scram_credentials_are_private_and_reused_without_reset(self):
        self.assertEqual(0o600,self.path.stat().st_mode&0o777)
        before=json.loads(self.path.read_text())
        self.assertEqual(self.metadata,provision_roles(lambda:psycopg2.connect(DSN),self.path))
        self.assertEqual(before,json.loads(self.path.read_text()))
        for role in ROLES:
            value=before['roles'][role]
            self.assertTrue(_password_matches(value['password'],value['verifier']))
            self.assertFalse(_password_matches('wrong-password',value['verifier']))
            self.assertEqual([(role,)],self.execute(role,'SELECT current_user'))
            self.assertNotIn(value['password'],json.dumps(self.metadata))
        parameters=psycopg2.extensions.parse_dsn(DSN);parameters.update(user=WORKER,password='wrong-password')
        with self.assertRaises(psycopg2.OperationalError):psycopg2.connect(**parameters)

    def test_worker_reports_only_and_cannot_insert_approval_or_control(self):
        self.assertEqual([('REPORT_ONLY',)],self.execute(WORKER,'SELECT kind FROM response_agent_review_manifest'))
        insert="""INSERT INTO response_agent_review_manifest(manifest_digest,run_id,revision,kind,scope,document,expires_at,review_state)
            VALUES(%s,%s,2,%s,%s,'{}',now()+interval '1 hour','VALIDATED')"""
        self.assertEqual(1,self.execute(WORKER,insert,('f'*64,RUN,'REPORT_ONLY','NONE')))
        self.denied(WORKER,insert,('f'*64,RUN,'PARSER_REPAIR','DEPLOY_REPLAY_PROPOSE'))
        self.denied(WORKER,"INSERT INTO response_agent_review_approval(approval_digest) VALUES(%s)",('f'*64,))
        self.denied(WORKER,"UPDATE response_agent_active_runtime SET generation='forged'")
        self.denied(WORKER,"INSERT INTO response_agent_execution_binding(release_operation_id) VALUES('forged')")
        self.denied(WORKER,"UPDATE response_capture SET payload='forged'")

    def test_worker_outbox_cannot_reference_parser_manifest(self):
        query="""INSERT INTO response_agent_notification_outbox(event_id,event_key,manifest_digest,event_kind,destination,revision,payload_digest,payload,marker)
            VALUES(%s,%s,%s,'REVIEW_REQUEST','github',1,%s,'{}',%s)"""
        args=(str(uuid.uuid4()),'f'*64,'b'*64,'a'*64,'marker-role-test')
        self.assertEqual(1,self.execute(WORKER,query,args))
        self.denied(WORKER,query,(*args[:2],'c'*64,*args[3:]))
        self.denied(WORKER,"UPDATE response_agent_notification_outbox SET state='DELIVERED'")

    def test_worker_budget_history_cannot_be_deleted_or_policy_reset(self):
        for table in ('response_agent_budget_request','response_agent_budget_settlement','response_agent_budget_alias',
                      'response_agent_budget_incident','response_agent_budget_day'):
            self.denied(WORKER,'DELETE FROM '+table)
        self.denied(WORKER,'UPDATE response_agent_budget_policy SET legacy_reconciliation_required=false')
        self.denied(WORKER,"UPDATE response_agent_budget_incident SET source='OTHER'")

    def test_controller_cannot_write_raw_proposals_budget_or_serving_shaped_table(self):
        for query in ("UPDATE response_capture SET payload='bad'","UPDATE change_proposal SET new_value='{}'",
                      "DELETE FROM response_agent_budget_request","UPDATE store SET name='bad'"):
            self.denied(CONTROLLER,query)
        self.assertEqual(1,self.execute(CONTROLLER,"INSERT INTO response_agent_release_service(service_id) VALUES('role-test')"))
        self.assertEqual(1,len(self.execute(CONTROLLER,'SELECT * FROM pg_control_system()')))

    def test_publisher_can_publish_but_cannot_approve_or_mutate_manifest(self):
        self.assertEqual(2,len(self.execute(PUBLISHER,'SELECT * FROM response_agent_review_manifest')))
        self.assertEqual([],self.execute(PUBLISHER,'SELECT * FROM response_agent_notification_outbox FOR UPDATE'))
        self.denied(PUBLISHER,"UPDATE response_agent_review_manifest SET review_state='VALIDATED'")
        self.denied(PUBLISHER,"INSERT INTO response_agent_review_approval(approval_digest) VALUES(%s)",('f'*64,))

    def test_java_authority_lock_queries_have_minimal_column_update_grants(self):
        for role in (REPLAY,APPLY):
            for table in ('response_agent_release_service','response_agent_release_operation','response_agent_review_manifest',
                          'response_agent_review_approval','response_agent_active_runtime'):
                self.execute(role,'SELECT * FROM '+table+' FOR SHARE')
            self.denied(role,"UPDATE response_agent_review_manifest SET document='{}'")
            self.denied(role,"UPDATE response_agent_active_runtime SET generation='forged'")
        self.execute(REPLAY,'SELECT * FROM response_replay_run FOR UPDATE')
        self.execute(REPLAY,'SELECT * FROM store_sync_state FOR UPDATE')
        self.denied(REPLAY,"UPDATE store_sync_state SET attempt_seq=99")
        self.denied(REPLAY,"UPDATE response_agent_review_approval SET state='REVOKED'")
        self.execute(APPLY,'SELECT * FROM response_agent_review_approval FOR UPDATE')
        self.denied(APPLY,"UPDATE response_agent_review_approval SET evidence='{}'")
        self.denied(APPLY,"INSERT INTO response_agent_review_approval(approval_digest) VALUES(%s)",('f'*64,))

    def test_java_replay_proposal_and_apply_audit_sequences_are_usable(self):
        self.assertEqual(1,self.execute(REPLAY,"INSERT INTO change_proposal(store_id,target,source) VALUES(1,'store.name','NAVER')"))
        self.assertEqual(1,self.execute(APPLY,"INSERT INTO apply_log(store_id,target,origin) VALUES(1,'store.name','PROPOSAL')"))
        self.denied(REPLAY,"INSERT INTO apply_log(store_id,target,origin) VALUES(1,'store.name','PROPOSAL')")
        self.denied(APPLY,"INSERT INTO response_replay_run(operation_id) VALUES(%s)",(str(uuid.uuid4()),))

    def test_service_can_capture_but_cannot_rewrite_raw_or_agent_authority(self):
        self.assertEqual(1,self.execute(SERVICE,"""INSERT INTO response_capture(capture_id,request_digest,store_id,source,payload,payload_digest)
            VALUES('role-test',%s,1,'NAVER','{}',%s)""",('a'*64,'b'*64)))
        self.execute(SERVICE,"UPDATE response_capture SET status='HELD'")
        self.denied(SERVICE,"UPDATE response_capture SET payload='forged'")
        self.denied(SERVICE,"UPDATE response_agent_review_approval SET state='REVOKED'")
        self.denied(SERVICE,"DELETE FROM response_agent_budget_request")

    def test_service_actual_capture_assessment_and_duplicate_incident_sql(self):
        capture='snapshot/service/ResponseCaptureService.java'
        evidence='snapshot/repository/MenuEvidenceRepository.java'
        with closing(self.role_connection(SERVICE)) as db:
            try:
                with db.cursor() as c:
                    c.execute('SELECT pg_try_advisory_xact_lock(9911247)')
                    self.assertTrue(c.fetchone()[0])
                    for capture_id in ('collection-first','collection-second'):
                        args=(capture_id,'1'*64,1,'NAVER','123','2026-10-14T00:00:00Z',1,'{}','2'*64)
                        insert=collection_sql(capture,'INSERT INTO response_capture')
                        c.execute(insert,args);self.assertEqual(1,c.rowcount)
                        c.execute(insert,args);self.assertEqual(0,c.rowcount)
                        c.execute(collection_sql(capture,'SELECT request_digest'),(capture_id,))
                        self.assertEqual('1'*64,c.fetchone()[0])
                        c.execute(collection_sql(capture,'UPDATE response_capture SET status'),
                            ('HELD','parser','contract','[]',True,capture_id))
                        incident=collection_sql(capture,'INSERT INTO response_incident (')
                        c.execute(incident,('9'*64,'NAVER','parser','contract','[]',True))
                        self.assertEqual(1 if capture_id=='collection-first' else 0,c.rowcount)
                        sample=collection_sql(capture,'INSERT INTO response_incident_sample')
                        c.execute(sample,('9'*64,capture_id));self.assertEqual(1,c.rowcount)
                        c.execute(sample,('9'*64,capture_id));self.assertEqual(0,c.rowcount)
                    c.execute(collection_sql(evidence,'INSERT INTO menu_evidence_observation'),
                        ('collection-first',1,'NAVER','123','2026-10-14 09:00:00','2026-10-14 09:00:01',
                         '2'*64,'[]','[]',True,True,'parser-profile'))
                    self.assertEqual(1,c.rowcount) # also exercises the BIGSERIAL sequence grant
                    c.execute(collection_sql(capture,'UPDATE response_capture SET processed_response'),
                        ('{"changed":false}','collection-first'))
                    c.execute(collection_sql(capture,'SELECT processed_response'),('collection-first',))
                    self.assertEqual('{"changed":false}',c.fetchone()[0])
                    c.execute('SELECT count(*) FROM response_incident_sample WHERE fingerprint=%s',('9'*64,))
                    self.assertEqual(2,c.fetchone()[0])
            finally:db.rollback()
        # Conflict handling is DO NOTHING. Do not expand incident/raw mutation authority.
        self.denied(SERVICE,"UPDATE response_incident SET model_eligible=false")
        self.denied(SERVICE,"UPDATE menu_evidence_observation SET menu_complete=true")

    def test_service_snapshot_unchanged_signal_and_reconcile_privileges(self):
        with closing(self.role_connection(SERVICE)) as db:
            try:
                with db.cursor() as c:
                    # Snapshot PESSIMISTIC_WRITE and due-dispatch SKIP LOCKED both require UPDATE.
                    c.execute("SELECT * FROM store_sync_state WHERE store_id=1 AND source='NAVER' FOR UPDATE")
                    self.assertEqual(1,len(c.fetchall()))
                    c.execute("""UPDATE store_sync_state target SET last_attempt_at=now(),claimed_at=now(),
                        claim_token=%s,attempt_seq=target.attempt_seq+1 FROM
                        (SELECT id FROM store_sync_state WHERE store_id=1 ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) picked
                        WHERE target.id=picked.id""",(str(uuid.uuid4()),))
                    self.assertEqual(1,c.rowcount)
                    c.execute("""INSERT INTO place_snapshot(store_id,source,fetched_at,content_hash,content_hash_version,payload)
                        VALUES(1,'NAVER',now(),%s,3,'{}') RETURNING id""",('3'*64,))
                    snapshot_id=c.fetchone()[0]
                    c.execute("""INSERT INTO store_signal(store_id,snapshot_id,fetched_at,signals,parser_version,
                        signal_hash,signal_observed_at,observation_id,payload_retained)
                        VALUES(1,%s,now(),'{}',1,%s,now(),'normal-first',true)""",(snapshot_id,'4'*64))
                    # The next unchanged observation updates evidence/counters, not a new snapshot.
                    c.execute("""UPDATE store_signal SET signal_observed_at=now(),observation_id='normal-second',
                        payload_retained=false,visitor_reviews_total=2 WHERE store_id=1""")
                    c.execute("""UPDATE store_sync_state SET visit_count=visit_count+1,last_success_at=now(),
                        next_due_at=now()+interval '1 day',content_hash=%s,content_hash_version=3,
                        non_menu_hash=%s,menu_hash=%s,scope_hash_version=1,store_fields_observed_at=now(),
                        menu_observed_at=now(),hours_known='{}' WHERE store_id=1 AND source='NAVER'""",('3'*64,'5'*64,'6'*64))
                    c.execute('SELECT * FROM store_field_provenance WHERE store_id=1');c.fetchall()
                    c.execute("""INSERT INTO change_proposal(store_id,target,old_value,new_value,source,snapshot_id,
                        producer,target_family,policy_version,reconcile_run_id)
                        VALUES(1,'store.name','"before"','"after"','NAVER',%s,
                        'NAVER_SNAPSHOT_RECONCILE','STORE_NAME','test',%s) RETURNING id""",(snapshot_id,str(uuid.uuid4())))
                    proposal=c.fetchone()[0]
                    c.execute("""UPDATE change_proposal SET status='SUPERSEDED',review_note='superseded',updated_at=now()
                        WHERE store_id=1 AND producer='NAVER_SNAPSHOT_RECONCILE' AND target_family='STORE_NAME' AND status='PENDING'""")
                    self.assertEqual(1,c.rowcount)
                    c.execute("SELECT EXISTS(SELECT 1 FROM response_replay_proposal WHERE proposal_id=%s AND relation='CREATED')",(proposal,))
                    self.assertFalse(c.fetchone()[0])
                    c.execute("SELECT observation_id,visitor_reviews_total FROM store_signal WHERE store_id=1")
                    self.assertEqual(('normal-second',2),c.fetchone())
                    c.execute('SELECT count(*) FROM place_snapshot WHERE store_id=1')
                    self.assertEqual(1,c.fetchone()[0])
            finally:db.rollback()
        self.denied(SERVICE,"UPDATE store SET name='forbidden'")

    def test_existing_roles_without_receipt_or_changed_password_hold_without_reset(self):
        with self.assertRaisesRegex(ReleaseHold,'EXISTING_ROLE_WITHOUT_CREDENTIALS'):
            provision_roles(lambda:psycopg2.connect(DSN),self.root/'unknown.json')
        changed=copy.deepcopy(self.credentials);changed['roles'][WORKER]['password']='x'*64
        altered=self.root/'changed.json';write_json(altered,changed)
        with self.assertRaisesRegex(ReleaseHold,'EXISTING_ROLE_OR_CREDENTIAL_CHANGED'):
            provision_roles(lambda:psycopg2.connect(DSN),altered)
        self.assertEqual([(WORKER,)],self.execute(WORKER,'SELECT current_user'))

    def test_unknown_commit_receipt_is_reconciled_without_password_change(self):
        receipt=copy.deepcopy(self.credentials);receipt['state']='PREPARED';write_json(self.path,receipt)
        self.assertEqual('APPLIED',provision_roles(lambda:psycopg2.connect(DSN),self.path)['state'])
        self.assertEqual(self.credentials,json.loads(self.path.read_text()))

    def test_privilege_drift_holds_instead_of_silently_revoking_or_expanding(self):
        with closing(psycopg2.connect(DSN)) as db,db,db.cursor() as c:c.execute('GRANT UPDATE ON store TO '+WORKER)
        try:
            with self.assertRaisesRegex(ReleaseHold,'ROLE_GRANTS_CHANGED'):
                provision_roles(lambda:psycopg2.connect(DSN),self.path)
        finally:
            with closing(psycopg2.connect(DSN)) as db,db,db.cursor() as c:c.execute('REVOKE UPDATE ON store FROM '+WORKER)


if __name__=='__main__':unittest.main()
