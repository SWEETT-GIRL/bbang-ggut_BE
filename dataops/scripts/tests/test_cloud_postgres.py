"""실제 PostgreSQL 트랜잭션 경계 검증. 전용 로컬 테스트 DB에서만 명시적으로 실행한다."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import sys
import threading
import unittest
import uuid

import psycopg2
from psycopg2.extensions import parse_dsn
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_candidates import persist
from localdata_status_delta import enqueue_closures


@unittest.skipUnless(os.environ.get('DATAOPS_TEST_POSTGRES_DSN'), 'dedicated local PostgreSQL fixture required')
class PostgresConcurrencyTest(unittest.TestCase):
    def setUp(self):
        self.dsn = os.environ['DATAOPS_TEST_POSTGRES_DSN']
        if parse_dsn(self.dsn).get('host') not in ('127.0.0.1', 'localhost'):
            self.fail('test fixture must be an explicit localhost database')
        self.mgmt = 'test-' + uuid.uuid4().hex
        self.sid = 800000000000 + uuid.uuid4().int % 100000000000
        self.addCleanup(self.cleanup)

    def cleanup(self):
        with psycopg2.connect(self.dsn) as conn, conn.cursor() as cursor:
            cursor.execute('DELETE FROM new_store_candidate WHERE mgmt_no=%s', (self.mgmt,))
            cursor.execute('DELETE FROM change_proposal WHERE store_id=%s', (self.sid,))
        conn.close()

    def concurrent(self, function):
        barrier = threading.Barrier(3)
        def run(_):
            conn = psycopg2.connect(self.dsn)
            try:
                barrier.wait(timeout=10)
                return function(conn)
            finally:
                conn.close()
        with ThreadPoolExecutor(max_workers=3) as executor:
            return list(executor.map(run, range(3)))

    def test_same_candidate_delivered_concurrently_is_inserted_once_and_conflict_is_held(self):
        row = dict(mgmt_no=self.mgmt, name='테스트', address='서울 테스트로 1', dataset='bakeries',
                   date='2026-11-05', place_id='test-place')
        results = self.concurrent(lambda conn: persist(conn, [row]))
        self.assertEqual(sum(result[0] for result in results), 1)
        self.assertEqual(sum(result[1] for result in results), 2)
        conn = psycopg2.connect(self.dsn)
        try:
            self.assertEqual(persist(conn, [{**row, 'place_id': 'different'}]), (0, 0, [self.mgmt]))
        finally:
            conn.close()

    def test_same_closure_delivered_concurrently_is_one_pending_proposal_and_unknown_is_never_enqueued(self):
        row = dict(store_id=self.sid, mgmt_no=self.mgmt, verdict='ENQUEUE', reason='HTTP_ABSENT',
                   closed_ymd='20261105', observed_at='2026-11-05T00:00:00+00:00')
        results = self.concurrent(lambda conn: enqueue_closures(conn, [row, {**row, 'verdict': 'UNKNOWN_HOLD'}]))
        self.assertEqual(sum(results), 1)
        with psycopg2.connect(self.dsn) as conn, conn.cursor() as cursor:
            cursor.execute('SELECT status,new_value FROM change_proposal WHERE store_id=%s', (self.sid,))
            result = cursor.fetchall()
        conn.close()
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0][0], 'PENDING')
