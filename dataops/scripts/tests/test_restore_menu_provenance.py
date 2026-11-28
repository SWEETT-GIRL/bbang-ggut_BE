import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import restore_menu_description_provenance as repair
import psycopg2
from psycopg2 import sql


def bundle():
    value = {'name': '초코식빵', 'price': 6000, 'description': ''}
    return {'version': 1, 'storeId': 1, 'externalId': '123', 'snapshot': {
        'namespace': 'legacy-local-dataops', 'id': 99, 'storeId': 1,
        'raw': {'ROOT_QUERY': {'placeDetail(input)': {'base': {'__ref': 'base'}, 'baemin': {'menus': [{'__ref': 'menu'}]}}},
                'base': {'id': '123'}, 'menu': {'__typename': 'PlaceDetail_BaeminMenu', 'source': 'baemin',
                                              'name': '초코식빵', 'menuId': 'a', 'price': '6000', 'desc': ''}}},
        'entries': [{'menuId': 7, **value, 'servingUpdatedAt': '2026-09-27 18:25:16', 'rawRef': 'menu', 'sourceMenuId': 'a',
            'proposal': {'id': 8, 'status': 'APPROVED', 'source': 'NAVER', 'storeId': 1, 'snapshotId': 99, 'target': 'menu.add.초코식빵', 'newValue': value},
            'applyLog': {'id': 9, 'proposalId': 8, 'origin': 'PROPOSAL', 'storeId': 1, 'snapshotId': 99,
                         'target': 'menu.add.초코식빵', 'newValue': value},
            'legacyProvenance': {'field': 'menu.7', 'snapshotId': 99, 'storeId': 1, 'source': 'NAVER'}}]}


class EvidenceValidationTest(unittest.TestCase):
    def test_original_empty_description_requires_actual_source_and_applied_chain(self):
        self.assertEqual(len(repair.validate_bundle(bundle())), 1)
        for mutate in (
            lambda b: b['snapshot']['raw']['ROOT_QUERY']['placeDetail(input)']['baemin'].update(menus=[]),
            lambda b: b['snapshot']['raw']['menu'].update(source='naver'),
            lambda b: b['entries'][0]['proposal'].update(status='PENDING'),
            lambda b: b['entries'][0]['applyLog'].update(proposalId=55),
            lambda b: b['entries'][0]['legacyProvenance'].update(field='menu.8'),
            lambda b: b['entries'][0]['legacyProvenance'].update(source='USER'),
            lambda b: b['entries'][0].update(description='changed'),
            lambda b: b['snapshot']['raw']['base'].update(id='999'),
        ):
            bad = copy.deepcopy(bundle()); mutate(bad)
            with self.assertRaises(ValueError): repair.validate_bundle(bad)

    def test_hash_pins_reviewed_file_and_duplicate_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'evidence.json'; file.write_text(json.dumps(bundle()))
            digest = hashlib.sha256(file.read_bytes()).hexdigest()
            repair.load_bundle(file, digest)
            file.write_text(file.read_text() + '\n')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'): repair.load_bundle(file, digest)
        bad = bundle(); bad['entries'] *= 2
        with self.assertRaisesRegex(ValueError, 'repeated'): repair.validate_bundle(bad)


@unittest.skipUnless(os.environ.get('DATAOPS_RESTORE_TEST_DSN'), 'requires disposable PostgreSQL with CREATEDB')
class RestorationPostgresTest(unittest.TestCase):
    def setUp(self):
        self.admin = psycopg2.connect(os.environ['DATAOPS_RESTORE_TEST_DSN'])
        self.admin.autocommit = True
        self.database = 'restore_test_' + uuid.uuid4().hex
        with self.admin.cursor() as cursor:
            cursor.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(self.database)))
        self.db = psycopg2.connect(os.environ['DATAOPS_RESTORE_TEST_DSN'], dbname=self.database)
        self.reader = psycopg2.connect(os.environ['DATAOPS_RESTORE_TEST_DSN'], dbname=self.database)
        self.reader.set_session(readonly=True)
        with self.db.cursor() as cursor:
            cursor.execute("SET TIME ZONE 'UTC'")
            cursor.execute('CREATE TABLE store_sync_state(store_id bigint,source text,external_id text,state text,last_attempt_at timestamp,next_due_at timestamp)')
            cursor.execute("INSERT INTO store_sync_state VALUES(1,'NAVER','123','ACTIVE',NULL,NULL)")
            cursor.execute('CREATE TABLE store_field_provenance(store_id bigint,field text,source text,snapshot_id bigint,PRIMARY KEY(store_id,field))')
            migration = Path(__file__).resolve().parents[3] / 'batch/src/main/resources/db/dataops-migration/V20261128010000__menu_provenance_restoration.sql'
            cursor.execute(migration.read_text())
            cursor.execute('CREATE TABLE store(id bigint,naver_place_id text,status text)')
            cursor.execute("INSERT INTO store VALUES(1,'123','ACTIVE')")
            cursor.execute('CREATE TABLE menu(id bigint,store_id bigint,name text,price int,description text,updated_at timestamp,created_by_member_id bigint,deleted_at timestamp)')
            cursor.execute("INSERT INTO menu VALUES(7,1,'초코식빵',6000,'','2026-09-27 18:25:16',NULL,NULL)")
        self.db.commit()

    def tearDown(self):
        self.reader.close(); self.db.close()
        with self.admin.cursor() as cursor:
            cursor.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(self.database)))
        self.admin.close()

    def run_repair(self, execute=True):
        return repair.restore(self.db, self.reader, bundle(), 'a' * 64, execute=execute)

    def scalar(self, query):
        with self.db.cursor() as cursor:
            cursor.execute(query)
            return cursor.fetchone()[0]

    def mutate(self, query):
        with self.db.cursor() as cursor: cursor.execute(query)
        self.db.commit()

    def test_dry_run_rolls_back_audit_provenance_and_schedule(self):
        self.assertEqual(self.run_repair(False)['restored'], 1)
        self.assertEqual(self.scalar('SELECT count(*) FROM store_field_provenance'), 0)
        self.assertEqual(self.scalar('SELECT count(*) FROM menu_provenance_restoration'), 0)
        self.assertIsNone(self.scalar('SELECT next_due_at FROM store_sync_state'))

    def test_field_only_restore_and_repeated_execution_do_not_rewrite_serving(self):
        self.assertEqual(self.run_repair()['restored'], 1)
        self.assertEqual(self.scalar('SELECT field FROM store_field_provenance'), 'menu.7.description')
        self.assertIsNone(self.scalar('SELECT snapshot_id FROM store_field_provenance'))
        self.assertEqual(self.scalar('SELECT description FROM menu'), '')
        self.mutate("UPDATE menu SET description='automatically applied later'")
        self.assertEqual(self.run_repair()['already_restored'], 1)
        self.assertEqual(self.scalar('SELECT count(*) FROM menu_provenance_restoration'), 1)

    def test_changed_revision_or_user_menu_blocks_all_writes(self):
        for mutation in ("UPDATE menu SET updated_at=now()", "UPDATE menu SET created_by_member_id=5"):
            self.mutate(mutation)
            with self.assertRaises(ValueError): self.run_repair()
            self.assertEqual(self.scalar('SELECT count(*) FROM menu_provenance_restoration'), 0)
            self.assertEqual(self.scalar('SELECT count(*) FROM store_field_provenance'), 0)

    def test_existing_whole_or_field_provenance_is_not_overwritten(self):
        for field in ('menu.7', 'menu.7.description'):
            self.mutate(f"INSERT INTO store_field_provenance VALUES(1,'{field}','USER',NULL)")
            with self.assertRaisesRegex(ValueError, 'existing provenance'): self.run_repair()
            self.assertEqual(self.scalar('SELECT count(*) FROM menu_provenance_restoration'), 0)

    def test_active_lease_or_observation_prevents_repair(self):
        self.mutate("UPDATE store_sync_state SET last_attempt_at=timezone('Asia/Seoul', now())")
        with self.assertRaisesRegex(ValueError, 'leased'): self.run_repair()
        self.mutate('UPDATE store_sync_state SET last_attempt_at=NULL')
        with psycopg2.connect(os.environ['DATAOPS_RESTORE_TEST_DSN'], dbname=self.database) as other:
            with other.cursor() as cursor: cursor.execute('SELECT pg_advisory_xact_lock(%s)', (-(1 << 63) | 1,))
            with self.assertRaisesRegex(ValueError, 'busy'): self.run_repair()
        self.assertEqual(self.scalar('SELECT count(*) FROM store_field_provenance'), 0)
        self.mutate("UPDATE store_sync_state SET last_attempt_at=timezone('Asia/Seoul', now())-interval '3 hours'")
        self.assertEqual(self.run_repair()['restored'], 1)
        self.assertLess(abs(self.scalar("SELECT extract(epoch FROM next_due_at-timezone('Asia/Seoul', now())) FROM store_sync_state")), 5)


if __name__ == '__main__': unittest.main()
