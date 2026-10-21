"""Opt-in SQL tests against a NEW disposable PostgreSQL container only.

Normal discovery skips these tests. The operator creates a throwaway PostgreSQL
container with password ``codex_ephemeral_test_only`` and sets its loopback port as
KIND_EVIDENCE_TEST_PORT. No application .env or connection helper is imported.
Each test creates two uniquely named databases and drops only those databases.
Serving fixtures are deliberately small; this is not a full BE/Flyway E2E test.
"""
import os
from datetime import datetime
import sys
import threading
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kind_evidence_publish import (PublishBlockedError, PublishConflictError,
                                   PublishOutcomeUnknown, publish)

PORT = os.environ.get("KIND_EVIDENCE_TEST_PORT")
MIGRATIONS = Path(__file__).resolve().parents[3] / "batch/src/main/resources/db/dataops-migration"


class CommitResponseLost:
    def __init__(self, connection, after_commit):
        self.connection, self.after_commit = connection, after_commit

    def __getattr__(self, name):
        return getattr(self.connection, name)

    def commit(self):
        if self.after_commit:
            self.connection.commit()
        raise OSError("injected commit response loss")


@unittest.skipUnless(PORT, "requires an explicitly created disposable PostgreSQL container")
class PublisherPostgresTest(unittest.TestCase):
    def connect(self, database):
        return self.pg.connect(host="127.0.0.1", port=int(PORT), user="postgres",
                               password="codex_ephemeral_test_only", dbname=database,
                               application_name="codex_kind_evidence_ephemeral_test",
                               connect_timeout=3)

    def local(self):
        return self.connect(self.local_name)

    def serving(self):
        return self.connect(self.serving_name)

    def setUp(self):
        import psycopg2
        from psycopg2.extras import execute_values
        self.pg, self.execute_values = psycopg2, execute_values
        suffix = uuid.uuid4().hex
        self.local_name = "codex_evidence_local_" + suffix
        self.serving_name = "codex_evidence_serving_" + suffix
        self.admin = self.connect("postgres")
        self.admin.autocommit = True
        with self.admin.cursor() as cur:
            for database in (self.local_name, self.serving_name):
                cur.execute("CREATE DATABASE " + database)
        self.addCleanup(self.cleanup_databases)
        self.sql(self.local, """
            CREATE TABLE change_proposal (
                id bigint PRIMARY KEY, store_id bigint NOT NULL, target text NOT NULL,
                old_value jsonb, source text NOT NULL, status text NOT NULL,
                snapshot_id bigint, created_at timestamp NOT NULL DEFAULT '2026-09-25 12:00:00');
            CREATE TABLE place_snapshot (
                id bigint PRIMARY KEY, store_id bigint NOT NULL, source text NOT NULL,
                fetched_at timestamp NOT NULL);
            CREATE TABLE store_profile_draft (store_id bigint PRIMARY KEY);
            INSERT INTO place_snapshot VALUES
              (101,10,'NAVER','2026-09-25 08:00:00'),
              (102,99,'NAVER','2026-09-25 09:00:00'),
              (103,10,'OTHER','2026-09-25 09:00:00');
            INSERT INTO change_proposal (id,store_id,target,old_value,source,status,snapshot_id) VALUES
              (1,10,'menu.7.removed','{"name":"소금빵"}','NAVER','APPROVED',101),
              (2,10,'menu.8.removed','{"name":"치아바타"}','NAVER','PENDING',102),
              (3,10,'menu.9.removed','{"name":"베이글","reason":"category"}','NAVER','APPROVED',101),
              (4,10,'menu.10.removed','{"name":"식빵"}','OTHER','APPROVED',103),
              (5,10,'menu.11.removed','{"name":"크루아상"}','NAVER','REJECTED',101),
              (6,10,'menu.12.removed','{"name":"단팥빵"}','NAVER','APPLYING',103),
              (7,10,'menu.13.removed','{"name":"밤식빵"}','NAVER','PENDING',NULL),
              (8,10,'menu.14.removed','{"name":"우유식빵"}','NAVER','PENDING',999),
              (9,10,'menu.15.removed','{"name":"스콘"}','NAVER','PENDING',101),
              (10,10,'menu.16.removed','{"name":"쿠키"}','NAVER','PENDING',101);
        """)
        for name in ("V20260925100000__create_menu_evidence_ledger.sql",
                     "V20260925103000__profile_kind_evidence.sql",
                     "V20260925104000__kind_evidence_publish_attempt.sql"):
            self.sql(self.local, (MIGRATIONS / name).read_text())
        # Simulate provenance disappearing or changing after the initial copy.
        # The additive correction must preserve those legacy rows as UNKNOWN.
        self.sql(self.local, "DELETE FROM change_proposal WHERE id=9; "
                             "UPDATE change_proposal SET store_id=11 WHERE id=10;")
        self.sql(self.local, (MIGRATIONS / "V20260925105000__correct_legacy_menu_evidence_time.sql").read_text())
        self.sql(self.serving, """
            CREATE TABLE store_profile (
                store_id bigint PRIMARY KEY, computed_at timestamp NOT NULL,
                kinds text[] NOT NULL, updated_at timestamp);
            CREATE TABLE store_recommendation_feature (
                store_id bigint PRIMARY KEY, computed_at timestamp NOT NULL,
                value numeric NOT NULL CHECK (value BETWEEN 0 AND 1));
            INSERT INTO store_profile VALUES
                (1,'2026-09-24','{old}',null),(2,'2026-09-24','{old}',null),
                (3,'2026-09-24','{untouched}',null);
            INSERT INTO store_recommendation_feature VALUES
                (1,'2026-09-24',0.1),(2,'2026-09-24',0.2),(3,'2026-09-24',0.3);
        """)

    def cleanup_databases(self):
        with self.admin.cursor() as cur:
            for database in (self.local_name, self.serving_name):
                cur.execute("DROP DATABASE " + database + " WITH (FORCE)")
        self.admin.close()

    def sql(self, factory, sql, params=None):
        conn = factory()
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                result = cur.fetchall() if cur.description else None
            conn.commit()
            return result
        finally:
            conn.close()

    def run_publish(self, **overrides):
        conn = self.serving()
        args = dict(
            target="ephemeral", connection=conn, connect_local=self.local,
            profile_rows=[[1, "2026-09-25 12:00:00", ["new"]],
                          [2, "2026-09-25 12:00:00", []]],
            feature_rows=[[1, "2026-09-25 12:01:00", 0.8]], invalidated_ids=[2],
            profile_columns=["store_id", "computed_at", "kinds"],
            profile_template="(%s,%s::timestamp,%s::text[])",
            feature_columns=["store_id", "computed_at", "value"],
            feature_template="(%s,%s::timestamp,%s)",
            manifest={"evidence_fingerprints": {"1": "fixture1", "2": "fixture2"}})
        args.update(overrides)
        try:
            return publish(**args)
        finally:
            conn.close()

    def states(self):
        return self.sql(self.local, "SELECT status FROM kind_evidence_publish_attempt ORDER BY created_at")

    def profiles(self):
        return self.sql(self.serving, "SELECT store_id,kinds FROM store_profile ORDER BY store_id")

    def features(self):
        return self.sql(self.serving, "SELECT store_id,value::float FROM store_recommendation_feature ORDER BY store_id")

    def test_migrations_preserve_only_eligible_legacy_and_survive_proposal_delete(self):
        self.assertEqual([(1,), (2,), (6,), (7,), (8,), (9,), (10,)], self.sql(
            self.local, "SELECT proposal_id FROM menu_evidence_legacy_removal ORDER BY proposal_id"))
        self.sql(self.local, "DELETE FROM change_proposal")
        self.assertEqual([(7,)], self.sql(self.local, "SELECT count(*) FROM menu_evidence_legacy_removal"))
        self.assertEqual([(None, None)], self.sql(self.local,
            "INSERT INTO store_profile_draft(store_id) VALUES(1) RETURNING kind_evidence,kind_evidence_fingerprint"))

    def test_legacy_evidence_time_uses_exact_verified_snapshot_not_proposal_creation(self):
        self.assertEqual([(101, datetime(2026, 9, 25, 8), datetime(2026, 9, 25, 12),
                           "SNAPSHOT_FETCHED_AT")], self.sql(self.local,
            "SELECT snapshot_id,evidence_at,proposal_created_at,time_basis "
            "FROM menu_evidence_legacy_removal WHERE proposal_id=1"))
        # Even after both source rows disappear, the corrected timestamp and its
        # provenance remain an independent copy, not a live join at replay time.
        self.sql(self.local, "DELETE FROM change_proposal; DELETE FROM place_snapshot;")
        self.assertEqual([(datetime(2026, 9, 25, 8), "SNAPSHOT_FETCHED_AT")], self.sql(self.local,
            "SELECT evidence_at,time_basis FROM menu_evidence_legacy_removal WHERE proposal_id=1"))

    def test_unverifiable_legacy_times_remain_null_without_dropping_tombstones(self):
        self.assertEqual([(2, 102), (6, 103), (7, None), (8, 999), (9, None), (10, 101)],
                         self.sql(self.local,
            "SELECT proposal_id,snapshot_id FROM menu_evidence_legacy_removal "
            "WHERE evidence_at IS NULL AND time_basis='UNKNOWN' ORDER BY proposal_id"))
        self.assertEqual([(6,)], self.sql(self.local,
            "SELECT count(*) FROM menu_evidence_legacy_removal "
            "WHERE time_basis='UNKNOWN' AND proposal_created_at='2026-09-25 12:00:00'"))

    def test_actual_sql_atomic_success_audit_and_scope_only_delete(self):
        result = self.run_publish()
        self.assertEqual("APPLIED", result["status"])
        self.assertEqual(1, result["invalidated_count"])
        self.assertEqual([(1, ["new"]), (2, []), (3, ["untouched"])], self.profiles())
        self.assertEqual([(1, 0.8), (3, 0.3)], self.features())
        manifest, before = self.sql(self.local,
            "SELECT manifest,before_image FROM kind_evidence_publish_attempt")[0]
        self.assertEqual([1, 2], manifest["scope_ids"])
        self.assertEqual([2], manifest["expected_after"]["invalidated_ids"])
        self.assertEqual(["old"], before["store_profile"]["1"]["kinds"])
        self.assertEqual([("APPLIED",)], self.states())

    def test_real_feature_constraint_rolls_back_profile_as_well(self):
        before = self.profiles(), self.features()
        with self.assertRaises(self.pg.errors.CheckViolation):
            self.run_publish(feature_rows=[[1, "2026-09-25 12:01:00", 2]])
        self.assertEqual(before, (self.profiles(), self.features()))
        self.assertEqual([("FAILED",)], self.states())

    def test_real_dry_run_retains_target_and_audits_rollback(self):
        before = self.profiles(), self.features()
        self.assertEqual("ROLLED_BACK", self.run_publish(dry_run=True)["status"])
        self.assertEqual(before, (self.profiles(), self.features()))
        self.assertEqual([("ROLLED_BACK",)], self.states())

    def test_newer_profile_or_feature_rejected_without_intent(self):
        for table in ("store_profile", "store_recommendation_feature"):
            with self.subTest(table=table):
                self.sql(self.serving, "UPDATE " + table + " SET computed_at='2026-09-26' WHERE store_id=2")
                with self.assertRaises(PublishConflictError):
                    self.run_publish()
                self.assertEqual([], self.states())
                self.sql(self.serving, "UPDATE " + table + " SET computed_at='2026-09-24' WHERE store_id=2")

    def test_commit_ack_loss_after_real_commit_holds_retry(self):
        conn = self.serving()
        try:
            with self.assertRaises(PublishOutcomeUnknown):
                self.run_publish(connection=CommitResponseLost(conn, after_commit=True))
        finally:
            conn.close()
        self.assertEqual([(1, 0.8), (3, 0.3)], self.features())
        self.assertEqual([("UNKNOWN",)], self.states())
        with self.assertRaises(PublishBlockedError):
            self.run_publish()

    def test_commit_ack_loss_before_commit_also_holds_retry(self):
        before = self.profiles(), self.features()
        conn = self.serving()
        try:
            with self.assertRaises(PublishOutcomeUnknown):
                self.run_publish(connection=CommitResponseLost(conn, after_commit=False))
        finally:
            conn.close()
        self.assertEqual(before, (self.profiles(), self.features()))
        self.assertEqual([("UNKNOWN",)], self.states())

    def test_local_outcome_outage_leaves_durable_applying_after_serving_success(self):
        calls = []
        def fail_outcome():
            calls.append(True)
            if len(calls) >= 3:
                raise OSError("injected local outage")
            return self.local()
        with self.assertRaises(PublishOutcomeUnknown):
            self.run_publish(connect_local=fail_outcome)
        self.assertEqual([(1, 0.8), (3, 0.3)], self.features())
        self.assertEqual([("APPLYING",)], self.states())
        with self.assertRaises(PublishBlockedError):
            self.run_publish()

    def test_concurrent_cooperating_publishers_cannot_enter_dml_together(self):
        reached, release, second_dml, second_finished = [threading.Event() for _ in range(4)]
        outcomes = {}
        def pause_dml(cur, sql, rows, **kwargs):
            reached.set()
            if not release.wait(5):
                raise RuntimeError("test did not release first publisher")
            return self.execute_values(cur, sql, rows, **kwargs)
        def observe_dml(cur, sql, rows, **kwargs):
            second_dml.set()
            return self.execute_values(cur, sql, rows, **kwargs)
        def run(which, executor):
            try:
                outcomes[which] = self.run_publish(execute_values_fn=executor)
            except Exception as error:
                outcomes[which] = error
            finally:
                if which == "second":
                    second_finished.set()
        first = threading.Thread(target=run, args=("first", pause_dml), daemon=True)
        second = threading.Thread(target=run, args=("second", observe_dml), daemon=True)
        first.start()
        self.assertTrue(reached.wait(3))
        second.start()
        try:
            self.assertFalse(second_dml.wait(0.2))
            self.assertFalse(second_finished.is_set())
        finally:
            release.set()
            first.join(5)
            second.join(5)
        self.assertFalse(first.is_alive() or second.is_alive())
        self.assertEqual("APPLIED", outcomes["first"]["status"])
        # The advisory lock is released at serving commit, before local APPLIED.
        # The second run may safely hold on APPLYING or proceed after APPLIED.
        self.assertTrue(isinstance(outcomes["second"], PublishBlockedError)
                        or outcomes["second"]["status"] == "APPLIED")

    def test_concurrent_new_profile_cas_rolls_back_whole_cohort(self):
        self.sql(self.serving, "DELETE FROM store_profile WHERE store_id=1")
        raced = []
        def race(cur, sql, rows, **kwargs):
            if not raced:
                raced.append(True)
                self.sql(self.serving, "INSERT INTO store_profile VALUES(1,'2026-09-26','{concurrent}',null)")
            return self.execute_values(cur, sql, rows, **kwargs)
        with self.assertRaises(PublishConflictError):
            self.run_publish(execute_values_fn=race)
        self.assertEqual([(1, ["concurrent"]), (2, ["old"]), (3, ["untouched"])], self.profiles())
        self.assertEqual([(1, 0.1), (2, 0.2), (3, 0.3)], self.features())
        self.assertEqual([("FAILED",)], self.states())


if __name__ == "__main__":
    unittest.main()
