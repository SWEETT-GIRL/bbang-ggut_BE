"""No database, credentials, or network: verify publisher boundaries with fakes."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kind_evidence_publish import (PublishBlockedError, PublishConflictError,
                                   PublishOutcomeUnknown, publish)


class Cursor:
    def __init__(self, owner):
        self.owner, self.rows, self.rowcount = owner, [], 0

    def execute(self, sql, params=None):
        env = self.owner.env
        env.events.append((self.owner.kind, sql, params))
        self.rows, self.rowcount = [], 1
        if self.owner.kind == "local":
            if sql.startswith("SELECT id, status"):
                self.rows = [(aid, value["status"]) for aid, value in env.journal.items()
                             if value["status"] in ("PREPARED", "APPLYING", "UNKNOWN")][:1]
            elif sql.startswith("INSERT INTO kind_evidence_publish_attempt"):
                aid, target, manifest, before = params
                env.journal[aid] = {"status": "PREPARED", "manifest": json.loads(manifest),
                                    "before": json.loads(before)}
            elif sql.startswith("UPDATE kind_evidence_publish_attempt"):
                status, error, aid = params
                if status in env.fail_status:
                    raise RuntimeError("local status unavailable")
                env.journal[aid]["status"] = status
        elif "to_jsonb(t) FROM store_profile" in sql:
            self.rows = list(env.before_profiles.items())
        elif "to_jsonb(t) FROM store_recommendation_feature" in sql:
            self.rows = list(env.before_features.items())
        elif sql.startswith("DELETE FROM"):
            env.deleted = set(params[0])
            self.rows = [(sid,) for sid in params[0] if sid in env.before_features]
        elif sql.startswith("SELECT store_id FROM store_recommendation_feature"):
            self.rows = [(sid,) for sid in env.remaining_invalidations]
        elif sql.startswith("SELECT has_table_privilege"):
            self.rows = [(env.can_delete,)]

    def fetchall(self):
        return self.rows

    def fetchone(self):
        return self.rows[0] if self.rows else None

    def close(self):
        pass


class Connection:
    autocommit = False

    def __init__(self, env, kind):
        self.env, self.kind = env, kind

    def cursor(self):
        return Cursor(self)

    def commit(self):
        self.env.events.append((self.kind, "COMMIT", None))
        if self.kind == "serving" and self.env.fail_commit:
            raise RuntimeError("commit response lost")

    def rollback(self):
        self.env.events.append((self.kind, "ROLLBACK", None))

    def close(self):
        pass


class Environment:
    def __init__(self):
        self.events, self.journal = [], {}
        self.before_profiles, self.before_features = {}, {}
        self.fail_status, self.fail_commit, self.fail_feature = set(), False, False
        self.reject_profile, self.deleted, self.can_delete = False, set(), True
        self.remaining_invalidations = set()
        self.serving = Connection(self, "serving")

    def connect_local(self):
        return Connection(self, "local")

    def execute_values(self, cur, sql, rows, **kwargs):
        self.events.append(("serving", sql, rows))
        if "INSERT INTO store_recommendation_feature" in sql and self.fail_feature:
            raise RuntimeError("feature constraint")
        if "INSERT INTO store_profile" in sql and self.reject_profile:
            return []
        return [(int(row[0]),) for row in rows]

    def run(self, **overrides):
        args = dict(target="lab", connection=self.serving, connect_local=self.connect_local,
                    profile_rows=[[1, "2026-09-25 12:00:00"], [2, "2026-09-25 12:00:00"]],
                    feature_rows=[[1, "2026-09-25 12:01:00"]], invalidated_ids=[2],
                    profile_columns=["store_id", "computed_at"], profile_template="(%s,%s::timestamp)",
                    feature_columns=["store_id", "computed_at"], feature_template="(%s,%s::timestamp)",
                    manifest={"evidence_fingerprints": {"1": "a", "2": "b"}},
                    execute_values_fn=self.execute_values)
        args.update(overrides)
        return publish(**args)


class PublisherTest(unittest.TestCase):
    def test_intent_precedes_dml_and_both_tables_share_one_commit(self):
        env = Environment()
        env.before_features = {2: {"computed_at": "2026-09-24 00:00:00", "label": 0.8}}
        result = env.run()
        self.assertEqual("APPLIED", result["status"])
        self.assertEqual(1, result["invalidated_count"])
        writes = [i for i, (db, sql, _) in enumerate(env.events)
                  if db == "serving" and sql.startswith(("INSERT", "DELETE"))]
        intent_commit = next(i for i, event in enumerate(env.events) if event[:2] == ("local", "COMMIT"))
        self.assertLess(intent_commit, min(writes))
        self.assertEqual(1, sum(event[:2] == ("serving", "COMMIT") for event in env.events))
        attempt = env.journal[result["attempt_id"]]
        self.assertEqual([1, 2], attempt["manifest"]["scope_ids"])
        self.assertEqual([2], attempt["manifest"]["expected_after"]["invalidated_ids"])
        self.assertEqual(0.8, attempt["before"]["store_recommendation_feature"]["2"]["label"])

    def test_feature_error_rolls_back_profile_and_marks_failed(self):
        env = Environment()
        env.fail_feature = True
        with self.assertRaisesRegex(RuntimeError, "feature constraint"):
            env.run()
        self.assertIn(("serving", "ROLLBACK", None), env.events)
        self.assertNotIn(("serving", "COMMIT", None), env.events)
        self.assertEqual("FAILED", next(iter(env.journal.values()))["status"])

    def test_unknown_commit_blocks_followup_write(self):
        env = Environment()
        env.fail_commit = True
        with self.assertRaises(PublishOutcomeUnknown):
            env.run()
        self.assertEqual("UNKNOWN", next(iter(env.journal.values()))["status"])
        env.fail_commit = False
        env.events.clear()
        with self.assertRaises(PublishBlockedError):
            env.run()
        self.assertFalse(any(sql.startswith(("INSERT INTO store_profile", "DELETE FROM"))
                             for _, sql, _ in env.events))

    def test_local_outcome_failure_holds_serving_success(self):
        env = Environment()
        env.fail_status = {"APPLIED", "UNKNOWN"}
        with self.assertRaises(PublishOutcomeUnknown):
            env.run()
        self.assertEqual("APPLYING", next(iter(env.journal.values()))["status"])
        self.assertEqual(1, sum(event[:2] == ("serving", "COMMIT") for event in env.events))

    def test_explicit_scope_and_disjoint_invalidation_required(self):
        for invalidated in ([3], [1, 2], [], [2, 2]):
            env = Environment()
            with self.assertRaises(ValueError):
                env.run(invalidated_ids=invalidated)
            self.assertEqual([], env.events)

    def test_newer_target_profile_rejected_before_dml(self):
        env = Environment()
        env.before_profiles = {1: {"computed_at": "2026-09-26 00:00:00"}}
        with self.assertRaises(PublishConflictError):
            env.run()
        self.assertEqual({}, env.journal)
        self.assertFalse(any(sql.startswith("INSERT") for _, sql, _ in env.events))

    def test_newer_feature_cannot_be_invalidated_by_old_profile(self):
        env = Environment()
        env.before_features = {2: {"computed_at": "2026-09-26 00:00:00"}}
        with self.assertRaises(PublishConflictError):
            env.run()
        self.assertEqual(set(), env.deleted)

    def test_expected_target_version_is_checked_under_lock(self):
        env = Environment()
        env.before_profiles = {1: {"computed_at": "2026-09-24 00:00:00"}}
        with self.assertRaisesRegex(PublishConflictError, "changed since preflight"):
            env.run(expected_profile_versions={1: None, 2: None})
        self.assertEqual({}, env.journal)

    def test_concurrent_insert_version_cas_failure_rolls_back(self):
        env = Environment()
        env.reject_profile = True
        with self.assertRaisesRegex(PublishConflictError, "CAS"):
            env.run()
        self.assertIn(("serving", "ROLLBACK", None), env.events)
        self.assertNotIn(("serving", "COMMIT", None), env.events)

    def test_dry_run_rolls_back_target_and_audits_local_outcome(self):
        env = Environment()
        result = env.run(dry_run=True)
        self.assertEqual("ROLLED_BACK", result["status"])
        self.assertNotIn(("serving", "COMMIT", None), env.events)
        self.assertEqual("ROLLED_BACK", env.journal[result["attempt_id"]]["status"])

    def test_newer_concurrent_feature_invalidation_conflict_rolls_back(self):
        env = Environment()
        env.remaining_invalidations = {2}
        with self.assertRaisesRegex(PublishConflictError, "invalidation version CAS"):
            env.run()
        self.assertIn(("serving", "ROLLBACK", None), env.events)
        self.assertNotIn(("serving", "COMMIT", None), env.events)

    def test_invalidation_permission_is_checked_before_intent_or_dml(self):
        env = Environment()
        env.can_delete = False
        with self.assertRaisesRegex(PublishBlockedError, "DELETE privilege"):
            env.run()
        self.assertEqual({}, env.journal)
        self.assertFalse(any(sql.startswith(("INSERT", "DELETE")) for _, sql, _ in env.events))


if __name__ == "__main__":
    unittest.main()
