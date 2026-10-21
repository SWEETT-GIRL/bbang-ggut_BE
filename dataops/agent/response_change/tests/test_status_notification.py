"""Real disposable PostgreSQL outbox and fake GitHub transport, no external API."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import psycopg2

from agent.response_change.approval import ApprovalHold, OutboxLeaseLost
from agent.response_change.github_review import DeliveryUnknown
from agent.response_change.status_notification import (PgStatusStore, GitHubStatusPublisher, deliver_status,
    summary, render_status)
from agent.response_change.tests import test_orchestration as fixture
from agent.tests.test_approval import RUN, REPO, SHA


class FakeGitHub:
    """Only the exact fixed GET/comment POST subset; no PR approval API exists."""
    def __init__(self,number=21):
        self.number=number;self.comments=[];self.posts=[];self.lose_response=False;self.skip_write=False;self.fail_read=False

    def _api(self,path):
        if self.fail_read:raise DeliveryUnknown('injected read unavailable')
        if path==f'pulls/{self.number}':
            return {'number':self.number,'base':{'repo':{'full_name':REPO}},'head':{'sha':'b'*40}}
        if path==f'issues/{self.number}/comments?per_page=100&page=1':return self.comments.copy()
        raise AssertionError('unexpected endpoint '+path)

    def transport(self,arguments):
        self.posts.append(arguments)
        assert arguments[:5]==['api',f'repos/{REPO}/issues/{self.number}/comments','--method','POST','--input']
        body=json.loads(Path(arguments[5]).read_text())['body']
        if not self.skip_write:self.comments.append({'id':123,'body':body})
        if self.lose_response:raise DeliveryUnknown('injected lost response')
        return json.dumps({'id':123})


class PgStatusNotificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture.PgOrchestrationTest.setUpClass.__func__(cls)
        with psycopg2.connect(cls.dsn) as connection,connection.cursor() as cursor:
            cursor.execute('CREATE ROLE status_publisher LOGIN')

    @classmethod
    def tearDownClass(cls):fixture.PgOrchestrationTest.tearDownClass.__func__(cls)

    admin=fixture.PgOrchestrationTest.admin
    connect=fixture.PgOrchestrationTest.connect
    rows=fixture.PgOrchestrationTest.rows
    run_row=fixture.PgOrchestrationTest.run_row
    published=fixture.PgOrchestrationTest.published

    def publisher_connect(self):
        return psycopg2.connect(self.dsn.replace('user=postgres','user=status_publisher'),options='-c search_path='+self.schema)

    def setUp(self):
        fixture.PgOrchestrationTest.setUp(self)
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute('GRANT USAGE ON SCHEMA '+self.schema+' TO status_publisher')
            cursor.execute('GRANT SELECT ON response_agent_status_notification,response_agent_review_manifest,response_agent_review_publication TO status_publisher')
            cursor.execute('GRANT UPDATE ON response_agent_status_notification TO status_publisher')
        self.status=PgStatusStore(self.publisher_connect);self.github=FakeGitHub();self.publisher=GitHubStatusPublisher(self.github)

    def terminal(self,published=True):
        self.run_row()
        if published:self.published('REPORT_ONLY')
        self.candidate.prepare.return_value={'outcome':'UNKNOWN','reason_code':'OUTCOME_UNCONFIRMED'}
        self.assertEqual('UNKNOWN',self.runner.tick(phase='prepare')['state'])

    def due(self):
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute("UPDATE response_agent_status_notification SET next_attempt_at=CURRENT_TIMESTAMP-interval '1 second'")

    def deliver(self):
        claim=self.status.claim('publisher')
        self.assertIsNotNone(claim)
        return deliver_status(self.status,self.publisher,claim,REPO)

    def test_terminal_event_and_notification_commit_together_and_rollback_together(self):
        self.run_row()
        with patch('agent.response_change.status_notification.enqueue_status',side_effect=RuntimeError('outbox unavailable')):
            with self.assertRaises(RuntimeError):self.runner.tick(phase='prepare')
        self.assertEqual([('RUNNING',)],self.rows('SELECT state FROM response_agent_orchestration_job'))
        self.assertEqual([('START',)],self.rows('SELECT event FROM response_agent_orchestration_event'))
        self.assertEqual([(0,)],self.rows('SELECT count(*) FROM response_agent_status_notification'))
        self.assertEqual('IDLE',self.runner.tick(phase='prepare')['state'])
        self.assertEqual([('INTERRUPTED_OUTCOME_UNKNOWN',)],self.rows("SELECT payload->>'reason_code' FROM response_agent_status_notification"))

    def test_same_unknown_is_quiet_but_confirmed_completion_enqueues_new_status(self):
        self.terminal()
        self.candidate.status.return_value=self.candidate.prepare.return_value
        self.assertEqual('UNKNOWN',self.runner.tick(phase='prepare',reconcile=True)['state'])
        self.assertEqual('UNKNOWN',self.runner.tick(phase='prepare',reconcile=True)['state'])
        self.assertEqual([(1,)],self.rows('SELECT count(*) FROM response_agent_status_notification'))
        self.candidate.status.return_value={'outcome':'PREPARED','manifest_digest':SHA}
        self.assertEqual('DONE',self.runner.tick(phase='prepare',reconcile=True)['state'])
        self.assertEqual([(2,)],self.rows('SELECT count(*) FROM response_agent_status_notification'))
        self.assertEqual(1,self.candidate.prepare.call_count)

    def test_unmerged_waiting_does_not_notify(self):
        self.published();self.release.advance.side_effect=ApprovalHold('PR_NOT_MERGED')
        self.assertEqual('WAITING',self.runner.tick(phase='approval')['state'])
        self.assertEqual([(0,)],self.rows('SELECT count(*) FROM response_agent_status_notification'))

    def test_concurrent_publisher_claims_only_one_item(self):
        self.terminal()
        with ThreadPoolExecutor(max_workers=2) as executor:
            claims=list(executor.map(lambda name:self.status.claim(name),('first','second')))
        self.assertEqual(1,sum(claim is not None for claim in claims))

    def test_lost_post_response_reconciles_existing_comment_without_repost(self):
        self.terminal();self.github.lose_response=True
        self.assertIsNone(self.deliver())
        self.assertEqual([('DELIVERY_UNKNOWN',)],self.rows('SELECT state FROM response_agent_status_notification'))
        self.due();self.github.lose_response=False
        result=self.deliver()
        self.assertEqual(123,result['comment_id']);self.assertEqual(1,len(self.github.posts))
        self.assertEqual([('DELIVERED',)],self.rows('SELECT state FROM response_agent_status_notification'))
        self.assertIn('기존 검토 파일·승인 범위는 바뀌지 않습니다',self.github.comments[0]['body'])

    def test_unknown_post_without_visible_comment_never_blindly_posts_again(self):
        self.terminal();self.github.lose_response=True;self.github.skip_write=True
        self.assertIsNone(self.deliver());self.due()
        self.github.lose_response=False;self.github.skip_write=False
        self.assertIsNone(self.deliver());self.assertEqual(1,len(self.github.posts))
        self.assertEqual([('DELIVERY_UNKNOWN',)],self.rows('SELECT state FROM response_agent_status_notification'))

    def test_old_unknown_delivery_does_not_starve_new_terminal_notification(self):
        self.terminal();self.github.lose_response=True;self.github.skip_write=True
        self.assertIsNone(self.deliver())
        self.candidate.status.return_value={'outcome':'PREPARED','manifest_digest':SHA}
        self.runner.tick(phase='prepare',reconcile=True)
        # Both become due before the next 60s job; the newer pending event has
        # waited longer than the older event's explicitly scheduled retry.
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute("UPDATE response_agent_status_notification SET next_attempt_at=CURRENT_TIMESTAMP-interval '1 second' WHERE state='DELIVERY_UNKNOWN'")
            cursor.execute("UPDATE response_agent_status_notification SET next_attempt_at=CURRENT_TIMESTAMP-interval '2 seconds' WHERE state='PENDING'")
        claim=self.status.claim('next')
        self.assertEqual('DONE',claim.payload['state']);self.assertFalse(claim.reconciliation_required)

    def test_missing_pr_waits_and_read_failure_before_post_is_safe_to_poll(self):
        self.terminal(published=False)
        self.assertIsNone(self.deliver());self.assertEqual([],self.github.posts)
        self.assertEqual([('PENDING','STATUS_PR_NOT_PUBLISHED')],self.rows('SELECT state,error_code FROM response_agent_status_notification'))
        self.published('REPORT_ONLY');self.due();self.github.fail_read=True
        self.assertIsNone(self.deliver());self.assertEqual([],self.github.posts)
        self.due();self.github.fail_read=False
        self.assertIsNotNone(self.deliver());self.assertEqual(1,len(self.github.posts))

    def test_expired_sender_cannot_renew_or_mark_delivery(self):
        self.terminal();old=self.status.claim('old')
        with self.admin() as connection,connection.cursor() as cursor:
            cursor.execute("UPDATE response_agent_status_notification SET lease_until=CURRENT_TIMESTAMP-interval '1 second'")
        new=self.status.claim('new');self.assertTrue(new.reconciliation_required)
        with self.assertRaises(OutboxLeaseLost):self.status.renew(old)
        with self.assertRaises(OutboxLeaseLost):self.status.finish(old,reference={'id':1})
        self.assertIsNone(deliver_status(self.status,self.publisher,new,REPO));self.assertEqual([],self.github.posts)

    def test_identity_immutable_publisher_cannot_enqueue_or_change_approval(self):
        self.terminal()
        with self.admin() as connection,connection.cursor() as cursor:
            with self.assertRaises(psycopg2.Error):cursor.execute("UPDATE response_agent_status_notification SET payload='{}'")
        for sql in ('DELETE FROM response_agent_status_notification','INSERT INTO response_agent_status_notification SELECT * FROM response_agent_status_notification',
                    "UPDATE response_agent_review_manifest SET review_state='VALIDATED'"):
            with self.publisher_connect() as connection,connection.cursor() as cursor:
                with self.assertRaises(psycopg2.errors.InsufficientPrivilege):cursor.execute(sql)
        with self.connect() as connection,connection.cursor() as cursor:
            with self.assertRaises(psycopg2.errors.InsufficientPrivilege):cursor.execute("UPDATE response_agent_status_notification SET state='DELIVERED'")

    def test_wrong_repo_cannot_send_comment(self):
        self.terminal();claim=self.status.claim('publisher')
        self.assertIsNone(deliver_status(self.status,self.publisher,claim,'other/repo'))
        self.assertEqual([],self.github.posts)


class StatusRendererTest(unittest.TestCase):
    def test_raw_prose_and_secrets_never_render_and_counts_are_strict(self):
        value=summary({'kind':'ADVANCE_REVIEW','run_id':RUN},'HELD',{'reason_code':'password=secret\n@someone',
            'controller_state':'HELD','raw_payload':'secret raw','operation_id':'../escape','applied_count':True,
            'operating_writes':0,'message':'Run a command'})
        body=render_status(value,'<!-- response-agent-status:'+'a'*64+' -->')
        for text in ('password','@someone','secret raw','../escape','Run a command','확정 반영 건수'):
            self.assertNotIn(text,body)
        self.assertIn('DETAILS_REQUIRE_LOCAL_REVIEW',body)
        with self.assertRaises(ApprovalHold):render_status({**value,'message':'injected'},'<!-- response-agent-status:'+'a'*64+' -->')


if __name__=='__main__':unittest.main()
