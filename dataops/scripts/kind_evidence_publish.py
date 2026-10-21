"""Scoped profile/feature publisher with a durable local intent.

Imports open no connections. The caller performs target/schema/permission and frozen
evidence preflight, then injects a serving connection and a local connection factory.
This is not the full v3 operation/reconciliation framework: uncertain attempts are
held for explicit reconciliation, never automatically replayed.
"""
import datetime as dt
import hashlib
import json
import re
import uuid


LOCK_KEY = 604_925_081  # Shared by both loaders, on the actual serving database.
UNRESOLVED = ("PREPARED", "APPLYING", "UNKNOWN")


class PublishBlockedError(RuntimeError):
    pass


class PublishConflictError(RuntimeError):
    pass


class PublishOutcomeUnknown(RuntimeError):
    def __init__(self, attempt_id, message):
        self.attempt_id = attempt_id
        super().__init__(f"publish {attempt_id}: {message}; reconcile before retry")


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)


def _time(value):
    if value is None or value == "":
        return None
    value = value if isinstance(value, dt.datetime) else dt.datetime.fromisoformat(str(value))
    if value.tzinfo is not None:
        value = value.astimezone(dt.timezone(dt.timedelta(hours=9))).replace(tzinfo=None)
    return value


def _rows(rows, columns, label):
    columns = list(columns)
    if (len(set(columns)) != len(columns)
            or any(not re.fullmatch(r"[a-z_][a-z_0-9]*", c) for c in columns)
            or not {"store_id", "computed_at"}.issubset(columns)):
        raise ValueError(f"{label}: valid, unique columns including store_id/computed_at required")
    id_at, version_at = columns.index("store_id"), columns.index("computed_at")
    result, versions = {}, {}
    for row in rows:
        if len(row) != len(columns):
            raise ValueError(f"{label}: row/column length mismatch")
        sid = int(row[id_at])
        if sid <= 0 or sid in result:
            raise ValueError(f"{label}: invalid/duplicate store_id {sid}")
        version = _time(row[version_at])
        if version is None:
            raise ValueError(f"{label}: store {sid} has no computed_at")
        result[sid], versions[sid] = list(row), version
    return columns, result, versions


def _upsert_sql(table, columns):
    assignments = [f"{c}=EXCLUDED.{c}" for c in columns if c != "store_id"]
    if table == "store_profile" and "updated_at" not in columns:
        assignments.append("updated_at=now()")
    return (f"INSERT INTO {table} ({', '.join(columns)}) VALUES %s "
            f"ON CONFLICT (store_id) DO UPDATE SET {', '.join(assignments)} "
            f"WHERE {table}.computed_at IS NULL OR {table}.computed_at <= EXCLUDED.computed_at "
            "RETURNING store_id")


def _record_status(connect_local, attempt_id, status, error=None):
    conn = connect_local()
    try:
        cur = conn.cursor()
        try:
            cur.execute("UPDATE kind_evidence_publish_attempt SET status=%s, error=%s, updated_at=now() "
                        "WHERE id=%s", (status, error, attempt_id))
            if cur.rowcount != 1:
                raise RuntimeError("publish intent missing during outcome recording")
            conn.commit()
        finally:
            cur.close()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def _unknown(connect_local, attempt_id, reason):
    # Even if the local DB is unavailable, PREPARED/APPLYING remains an unresolved
    # blocker. Do not turn a failed status write into permission for another DML.
    try:
        _record_status(connect_local, attempt_id, "UNKNOWN", reason)
    except Exception:
        pass
    return PublishOutcomeUnknown(attempt_id, reason)


def _before(cur, table, scope):
    cur.execute(f"SELECT store_id, to_jsonb(t) FROM {table} t "
                "WHERE store_id=ANY(%s) ORDER BY store_id FOR UPDATE", (scope,))
    return {int(sid): row if isinstance(row, dict) else json.loads(row) for sid, row in cur.fetchall()}


def _check_versions(before, incoming, table):
    for sid, row in before.items():
        previous = _time(row.get("computed_at"))
        if previous is not None and previous > incoming[sid]:
            raise PublishConflictError(f"{table} store {sid}: target computed_at is newer than selected build")


def publish(*, target, connection, connect_local, profile_rows, feature_rows,
            invalidated_ids, profile_columns, profile_template, feature_columns,
            feature_template, manifest, dry_run=False, execute_values_fn=None,
            expected_profile_versions=None):
    """Publish one already validated cohort; commits/rolls back but does not close target.

    ``feature_columns`` includes store_id. Every profile ID must have exactly one
    feature row OR explicit invalidation. No omitted row becomes a deletion.
    ``expected_profile_versions`` optionally pins target computed_at (None = absent)
    for every selected ID; comparison occurs under serving row/advisory locks.
    Dry-run issues real target DML then rolls it back, and persists ROLLED_BACK audit.
    """
    if connection.autocommit:
        raise ValueError("publisher requires serving autocommit=False")
    if not target or not isinstance(manifest, dict):
        raise ValueError("target and manifest are required")
    pcols, profiles, pversions = _rows(profile_rows, profile_columns, "profile")
    fcols, features, fversions = _rows(feature_rows, feature_columns, "feature")
    raw_invalidated = list(invalidated_ids)
    invalidated = {int(sid) for sid in raw_invalidated}
    scope = sorted(profiles)
    if not scope:
        raise ValueError("empty publish scope")
    if len(invalidated) != len(raw_invalidated) or invalidated & set(features):
        raise ValueError("duplicate invalidation or feature/invalidation overlap")
    if set(features) | invalidated != set(scope):
        raise ValueError("feature rows and explicit invalidations must exactly cover profile scope")
    expected = None
    if expected_profile_versions is not None:
        expected = {int(sid): _time(value) for sid, value in expected_profile_versions.items()}
        if set(expected) != set(scope):
            raise ValueError("expected_profile_versions must cover exactly the selected scope")
    if execute_values_fn is None:
        from psycopg2.extras import execute_values
        execute_values_fn = execute_values

    attempt_id = str(uuid.uuid4())
    cur = connection.cursor()
    intent_saved = commit_started = serving_committed = False
    try:
        cur.execute("SELECT pg_advisory_xact_lock(%s)", (LOCK_KEY,))
        if invalidated:
            cur.execute("SELECT has_table_privilege(current_user, 'store_recommendation_feature', 'DELETE')")
            permission = cur.fetchone()
            if not permission or not permission[0]:
                raise PublishBlockedError("explicit feature invalidation requires DELETE privilege")
        # Check the journal while holding the serving lock, so another cooperating
        # publisher cannot pass the check between intent and target commit.
        local = connect_local()
        try:
            lcur = local.cursor()
            try:
                lcur.execute("SELECT id, status FROM kind_evidence_publish_attempt "
                             "WHERE target=%s AND status IN ('PREPARED','APPLYING','UNKNOWN') "
                             "ORDER BY created_at LIMIT 1", (target,))
                unresolved = lcur.fetchone()
                if unresolved:
                    raise PublishBlockedError(f"{target}: unresolved publish {unresolved[0]} ({unresolved[1]})")
                before = {"store_profile": _before(cur, "store_profile", scope),
                          "store_recommendation_feature": _before(cur, "store_recommendation_feature", scope)}
                _check_versions(before["store_profile"], pversions, "store_profile")
                all_feature_versions = {sid: fversions.get(sid, pversions[sid]) for sid in scope}
                _check_versions(before["store_recommendation_feature"], all_feature_versions,
                                "store_recommendation_feature")
                if expected is not None:
                    for sid in scope:
                        actual = _time(before["store_profile"].get(sid, {}).get("computed_at"))
                        if actual != expected[sid]:
                            raise PublishConflictError(f"store {sid}: target profile changed since preflight")
                payload = {"profiles": [profiles[sid] for sid in scope],
                           "features": [features[sid] for sid in sorted(features)],
                           "invalidated_ids": sorted(invalidated), "profile_columns": pcols,
                           "feature_columns": fcols}
                frozen_manifest = dict(manifest, scope_ids=scope, invalidated_ids=sorted(invalidated),
                                       incoming_profile_versions={str(k): str(v) for k, v in pversions.items()},
                                       expected_profile_versions={str(sid): before["store_profile"].get(sid, {}).get("computed_at")
                                                                  for sid in scope},
                                       expected_after=payload,
                                       payload_fingerprint=hashlib.sha256(_json(payload).encode()).hexdigest(),
                                       dry_run=bool(dry_run))
                lcur.execute("INSERT INTO kind_evidence_publish_attempt "
                             "(id,target,status,manifest,before_image) VALUES (%s,%s,'PREPARED',%s::jsonb,%s::jsonb)",
                             (attempt_id, target, _json(frozen_manifest), _json(before)))
                local.commit()  # Durable intent MUST precede all serving DML.
                intent_saved = True
            finally:
                lcur.close()
        except Exception:
            local.rollback()
            raise
        finally:
            local.close()

        _record_status(connect_local, attempt_id, "APPLYING")
        loaded = execute_values_fn(cur, _upsert_sql("store_profile", pcols),
                                   [profiles[sid] for sid in scope], template=profile_template,
                                   page_size=1000, fetch=True)
        if {int(row[0]) for row in loaded} != set(scope):
            raise PublishConflictError("profile version CAS rejected part of the selected scope")
        if features:
            loaded = execute_values_fn(cur, _upsert_sql("store_recommendation_feature", fcols),
                                       [features[sid] for sid in sorted(features)], template=feature_template,
                                       page_size=1000, fetch=True)
            if {int(row[0]) for row in loaded} != set(features):
                raise PublishConflictError("feature version CAS rejected part of the selected scope")
        if invalidated:
            selected = sorted(invalidated)
            cur.execute("DELETE FROM store_recommendation_feature f "
                        "USING unnest(%s::bigint[], %s::timestamp[]) AS chosen(store_id, max_version) "
                        "WHERE f.store_id=chosen.store_id "
                        "AND (f.computed_at IS NULL OR f.computed_at <= chosen.max_version) RETURNING f.store_id",
                        (selected, [pversions[sid] for sid in selected]))
            removed = {int(row[0]) for row in cur.fetchall()}
            # A concurrent insert into a previously absent feature row must not
            # escape the version guard while this run claims invalidation success.
            cur.execute("SELECT store_id FROM store_recommendation_feature WHERE store_id=ANY(%s)", (selected,))
            if cur.fetchall():
                raise PublishConflictError("feature invalidation version CAS rejected part of the selected scope")
        else:
            removed = set()
        if dry_run:
            connection.rollback()
            status = "ROLLED_BACK"
        else:
            commit_started = True
            connection.commit()
            serving_committed = True
            status = "APPLIED"
        try:
            _record_status(connect_local, attempt_id, status)
        except Exception as error:
            raise _unknown(connect_local, attempt_id,
                           f"serving {status.lower()} but local outcome write failed: {type(error).__name__}") from error
        return {"attempt_id": attempt_id, "status": status, "profile_count": len(profiles),
                "feature_count": len(features), "invalidated_count": len(removed)}
    except PublishOutcomeUnknown:
        raise
    except Exception as error:
        rollback_failed = False
        try:
            connection.rollback()
        except Exception:
            rollback_failed = True
        if commit_started or serving_committed or (rollback_failed and intent_saved):
            raise _unknown(connect_local, attempt_id,
                           f"serving transaction outcome uncertain: {type(error).__name__}") from error
        if intent_saved:
            try:
                _record_status(connect_local, attempt_id, "FAILED", type(error).__name__)
            except Exception:
                raise _unknown(connect_local, attempt_id,
                               f"serving rolled back but local outcome write failed: {type(error).__name__}") from error
        raise
    finally:
        cur.close()
