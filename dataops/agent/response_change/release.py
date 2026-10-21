"""Trusted, adapter-driven parser deployment state machine.

This module does not install a service, find/kill a PID, run a shell, approve a PR,
or write a database. Production use uses a durable journal and explicitly configured concrete,
fenced host/replay/registry adapters. The in-memory fakes in tests are not such an
implementation. Every effect has a persisted in-flight boundary; an interrupted
effect can only continue after its adapter reconciles the operation receipt.
"""
from __future__ import annotations

from datetime import datetime, timezone
from contextlib import contextmanager
import hashlib
from pathlib import Path
from typing import Protocol
import uuid

import psycopg2
from psycopg2.extras import Json, RealDictCursor

from .approval import ApprovalVerifier, PgReviewStore, PublicationBinding, ReviewManifest, content_digest, ID, SHA

SCOPE = "DEPLOY_REPLAY_PROPOSE"
TERMINAL = frozenset({"ACTIVE", "STALE", "HELD", "ROLLED_BACK", "REPLAY_HELD"})
IDENTITY_FIELDS = ("release_id", "artifact_sha256", "parser_digest", "profile_digest", "contract_digest", "database_identity")
ACTION_START = {"prepare": "QUEUED", "drain": "PREPARED", "start": "DRAINED",
                "canary": "CANARY", "replay": "REPLAY", "rollback": "ROLLBACK_NEEDED"}


class ReleaseHold(ValueError):
    pass


class AdapterUnavailable(ReleaseHold):
    pass


class ReleaseRegistry(Protocol):
    def committed_release(self, release_id: str) -> dict:
        """Return controller-owned full service attestation, never E1 parser-only provenance."""

    def validated_candidate(self, validation_digest: str) -> dict:
        """Return frozen build result whose complete validation/builder lineage was verified."""

    def canary_policy(self, policy_digest: str) -> dict:
        """Return pre-approval exact targets and independently validated expected result digest."""


class ReleaseJournal(Protocol):
    def service_lock(self, service_id: str):
        """Exclusive cross-process lock yielding a fence with assert_held() and token."""

    def claim(self, service_id: str, operation_id: str, approval, request_digest: str, fence) -> dict:
        """Atomically consume exact approval and create QUEUED receipt, or return same receipt.

        Must enforce unique approval->operation, validate scope/expiry/revocation in
        the approval store, and reject changed request or stale fence. Production
        implementations must combine approval consumption and journal insertion in
        one durable transaction; a Python lock/dict cannot implement this contract.
        """

    def load(self, service_id: str, operation_id: str, fence) -> dict | None:
        """Read receipt without consuming approval; include original consumed scope/digest.

        Existing receipts retain approval_state=CONSUMED, approval_scope,
        manifest_digest, request_digest, and approval_digest even after expiry.
        Those facts allow read-only reconciliation and fixed-base compensation,
        never a fresh start/replay operation under expired authority.
        """

    def save(self, record: dict, changes: dict, fence) -> dict:
        """Durable CAS on record revision + fence before returning the new receipt."""


class HostAdapter(Protocol):
    def observe(self, service_id: str, fence) -> dict:
        """Read owned service's real artifact/readiness/DB identity and generation.

        An unhealthy response is definitive after a bounded readiness check. A
        missing/unreachable response is UNKNOWN, not proof that a start failed.
        """

    def prepare(self, command: dict, fence) -> dict:
        """Verify/copy both exact JARs into immutable controller-owned release paths.

        Preserve rollback bytes. Verify hashes while copying, never rebuild. Return
        candidate_path/base_path. No service transition occurs at this boundary.
        """

    def drain(self, command: dict, fence) -> dict:
        """Block incoming work, drain old-generation work and fence all old writers."""

    def start(self, command: dict, fence) -> dict:
        """CAS old generation, atomically switch immutable path, start owned service read-only.

        Must check fence and expected live generation at effect time. Persist its
        own operation receipt; do not use arbitrary PID discovery/termination.
        """

    def rollback(self, command: dict, fence) -> dict:
        """Fence/drain this operation's candidate, CAS generation+JAR, restore old JAR read-only.

        Use a fresh rollback generation; never reactivate the old generation's work.
        """

    def reconcile(self, operation_id: str, action: str, fence) -> dict:
        """Read durable effect receipt: DONE/result, proven NOT_STARTED, or UNKNOWN."""


class ReplayAdapter(Protocol):
    def canary(self, command: dict, fence) -> dict:
        """Read-only exact <=20 captures, compare prior expected outputs, writes=0."""

    def replay(self, command: dict, fence) -> dict:
        """Exact captures only, separate replay receipts, stale guards, proposals only.

        Keep original capture observation time/attempt and collection receipts;
        retain CREATED/REUSED proposal lineage. Never mutate visit/due counters,
        expire out-of-scope proposals, approve a proposal or write serving data.
        """

    def reconcile(self, operation_id: str, action: str, fence) -> dict:
        """Read per-phase durable receipts; UNKNOWN must not trigger blind replay."""


class _PgServiceFence:
    def __init__(self, connection, service_id, epoch, lock_key):
        self.connection, self.service_id, self.token = connection, service_id, epoch
        self.lock_key = lock_key
        self.active = True

    def assert_held(self):
        _require(self.active and not self.connection.closed, "SERVICE_FENCE_LOST")
        try:
            with self.connection, self.connection.cursor() as cursor:
                # PostgreSQL documents bigint advisory keys as classid high32 / objid low32 / objsubid=1.
                cursor.execute("""SELECT fencing_epoch FROM response_agent_release_service WHERE service_id=%s
                    AND EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid()
                      AND granted AND mode='ExclusiveLock' AND objsubid=1 AND classid::bigint=%s AND objid::bigint=%s)""",
                    (self.service_id, self.lock_key >> 32, self.lock_key & 0xFFFFFFFF))
                row = cursor.fetchone()
                _require(row is not None and row[0] == self.token, "SERVICE_FENCE_LOST")
        except psycopg2.Error:
            raise ReleaseHold("SERVICE_FENCE_LOST") from None


class PgReleaseJournal:
    """Durable adapter: dedicated advisory-lock session, epoch and revision CAS.

    The connection factory must set bounded connection/network timeouts and must
    never reconnect an existing fence session. All operations use that same private
    session; the lock survives statement transactions and ends on connection loss.
    Host effects still require their own generation/fence check: PostgreSQL cannot
    atomically commit a service-manager operation.
    """
    MUTABLE = frozenset({"state", "pending_action", "prepared", "canary_receipt", "rollback_receipt",
                         "rollback_runtime", "replay_receipt", "hold_code", "operating_writes",
                         "incident_resolved", "reconciliation"})

    def __init__(self, connect):
        self.connect = connect
        self.approvals = PgReviewStore(connect)

    @contextmanager
    def service_lock(self, service_id):
        _require(isinstance(service_id, str) and ID.fullmatch(service_id), "SERVICE_ID_INVALID")
        # Positive namespace avoids the negative store-observation barrier keys.
        key = int.from_bytes(hashlib.sha256(("agent-release:" + service_id).encode()).digest()[:8], "big") & ((1 << 63) - 1)
        connection = self.connect()
        fence = None
        try:
            with connection, connection.cursor() as cursor:
                cursor.execute("SET statement_timeout='5s'")
                cursor.execute("SELECT pg_try_advisory_lock(%s)", (key,))
                _require(cursor.fetchone()[0] is True, "SERVICE_BUSY")
                cursor.execute("INSERT INTO response_agent_release_service(service_id) VALUES (%s) ON CONFLICT DO NOTHING", (service_id,))
                cursor.execute("""UPDATE response_agent_release_service SET fencing_epoch=fencing_epoch+1,
                    updated_at=CURRENT_TIMESTAMP WHERE service_id=%s RETURNING fencing_epoch""", (service_id,))
                fence = _PgServiceFence(connection, service_id, cursor.fetchone()[0], key)
            yield fence
        finally:
            if fence is not None:
                fence.active = False
            if not connection.closed:
                try:
                    connection.rollback()
                    with connection, connection.cursor() as cursor:
                        cursor.execute("SELECT pg_advisory_unlock(%s)", (key,))
                except psycopg2.Error:
                    pass
                connection.close()

    def _fence(self, service_id, fence):
        _require(isinstance(fence, _PgServiceFence) and fence.service_id == service_id, "SERVICE_FENCE_MISMATCH")
        fence.assert_held()

    def _row(self, cursor, service_id, operation_id, *, locked=False):
        cursor.execute("""SELECT r.*,a.state AS approval_state,a.scope AS stored_scope,
            a.manifest_digest AS stored_manifest,a.operation_id AS consumed_operation
            FROM response_agent_release_operation r JOIN response_agent_review_approval a USING(approval_digest)
            WHERE r.service_id=%s AND r.operation_id=%s""" + (" FOR UPDATE OF r" if locked else ""), (service_id, operation_id))
        row = cursor.fetchone()
        if row is None:
            return None
        _require(row["approval_state"] == "CONSUMED" and row["stored_scope"] == SCOPE
                 and row["stored_manifest"] == row["manifest_digest"] and row["consumed_operation"] == operation_id,
                 "CONSUMED_APPROVAL_RECEIPT_CHANGED")
        # Authority/state columns override JSON, preventing a receipt blob from inventing permission.
        return {**row["receipt"], **{key: row[key] for key in ("operation_id", "service_id", "approval_digest",
                "manifest_digest", "request_digest", "approval_scope", "revision", "state", "pending_action", "approval_state")}}

    def load(self, service_id, operation_id, fence):
        self._fence(service_id, fence)
        with fence.connection, fence.connection.cursor(cursor_factory=RealDictCursor) as cursor:
            return self._row(cursor, service_id, operation_id)

    def claim(self, service_id, operation_id, approval, request_digest, fence):
        self._fence(service_id, fence)
        _require(approval.scope == SCOPE and isinstance(request_digest, str) and SHA.fullmatch(request_digest), "RELEASE_CLAIM_SCOPE")
        with fence.connection:
            with fence.connection.cursor(cursor_factory=RealDictCursor) as cursor:
                existing = self._row(cursor, service_id, operation_id, locked=True)
            if existing is not None:
                _require(existing["approval_digest"] == approval.digest and existing["request_digest"] == request_digest
                         and existing["manifest_digest"] == approval.manifest_digest, "OPERATION_BINDING_CHANGED")
                with fence.connection.cursor() as cursor:
                    cursor.execute("""SELECT 1 FROM response_agent_review_manifest WHERE manifest_digest=%s
                        AND review_state='VALIDATED' AND expires_at>CURRENT_TIMESTAMP FOR SHARE""", (approval.manifest_digest,))
                    _require(cursor.fetchone() is not None, "APPROVAL_EXECUTION_REVOKED_OR_EXPIRED")
                return existing
            # PgReviewStore uses tuple cursors. These mutations and the insert share one transaction.
            with fence.connection.cursor() as cursor:
                approval_digest = self.approvals.approve(cursor, approval)
                consumed = self.approvals.consume(cursor, approval_digest, scope=SCOPE, operation_id=operation_id)
                _require(consumed == approval.manifest_digest, "APPROVAL_ALREADY_CONSUMED_OR_INVALID")
                cursor.execute("""INSERT INTO response_agent_release_operation
                    (operation_id,service_id,approval_digest,manifest_digest,request_digest,approval_scope,state)
                    VALUES (%s,%s,%s,%s,%s,%s,'QUEUED')""",
                    (operation_id, service_id, approval.digest, approval.manifest_digest, request_digest, SCOPE))
            with fence.connection.cursor(cursor_factory=RealDictCursor) as cursor:
                return self._row(cursor, service_id, operation_id)

    def save(self, record, changes, fence):
        self._fence(record["service_id"], fence)
        _require(isinstance(changes, dict) and set(changes) <= self.MUTABLE, "IMMUTABLE_RELEASE_RECEIPT")
        with fence.connection, fence.connection.cursor(cursor_factory=RealDictCursor) as cursor:
            current = self._row(cursor, record["service_id"], record["operation_id"], locked=True)
            _require(current is not None and current["revision"] == record["revision"], "STALE_RELEASE_REVISION")
            updated = {**current, **changes}
            cursor.execute("""UPDATE response_agent_release_operation r SET receipt=%s,state=%s,pending_action=%s,
                revision=r.revision+1,updated_at=CURRENT_TIMESTAMP FROM response_agent_release_service s
                WHERE r.operation_id=%s AND r.service_id=s.service_id AND s.service_id=%s
                AND s.fencing_epoch=%s AND r.revision=%s RETURNING r.operation_id""",
                (Json({key: value for key, value in updated.items() if key in self.MUTABLE}), updated["state"],
                 updated.get("pending_action"), record["operation_id"], record["service_id"], fence.token, record["revision"]))
            _require(cursor.fetchone() is not None, "SERVICE_FENCE_OR_REVISION_LOST")
            return self._row(cursor, record["service_id"], record["operation_id"])


def _require(condition, code):
    if not condition:
        raise ReleaseHold(code)


def _jar_digest(path) -> str:
    file = Path(path)
    _require(file.is_absolute() and file.is_file() and not file.is_symlink(), "ARTIFACT_PATH_INVALID")
    value = hashlib.sha256()
    with file.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def _identity(value: dict) -> dict:
    return {field: value[field] for field in IDENTITY_FIELDS}


class ReleaseController:
    """Advance at most one host/replay effect per call; no implicit operational defaults."""
    def __init__(self, *, journal, registry, host, replay, provider, verifier: ApprovalVerifier):
        if any(adapter is None for adapter in (journal, registry, host, replay, provider, verifier)):
            raise AdapterUnavailable("CONCRETE_RELEASE_ADAPTERS_REQUIRED")
        self.journal, self.registry, self.host = journal, registry, host
        self.replay, self.provider, self.verifier = replay, provider, verifier

    def _inputs(self, manifest: ReviewManifest):
        document = manifest.document
        _require(document["kind"] == "PARSER_REPAIR" and document["scope"] == SCOPE, "PARSER_APPROVAL_REQUIRED")
        base = self.registry.committed_release(document["base_release"])
        # The registry's authority is separate from a local source/JAR comparison.
        _require(base.get("attestation_kind") == "COMMITTED_DEPLOYED_SERVICE"
                 and base.get("release_id") == document["base_release"]
                 and base.get("source_commit") == document["base_commit"], "FULL_COMMITTED_BASE_REQUIRED")
        _require(base.get("service_id") and base.get("generation") is not None, "BASE_SERVICE_GENERATION_REQUIRED")
        _require(base.get("schema_compatible") is True and base.get("backup_verified") is True,
                 "BASE_RECOVERY_PRECONDITIONS_REQUIRED")
        candidate = self.registry.validated_candidate(document["validation_digest"])
        for candidate_key, manifest_key in (("artifact_sha256", "artifact_sha256"), ("profile_digest", "structure_profile_digest"),
                ("contract_digest", "semantic_contract_digest"), ("validation_digest", "validation_digest"),
                ("candidate_tree_digest", "candidate_tree_digest"), ("patch_digest", "patch_digest"),
                ("build_recipe_digest", "build_recipe_digest")):
            _require(candidate.get(candidate_key) == document[manifest_key], "CANDIDATE_APPROVAL_BINDING_CHANGED")
        _require(candidate.get("base_artifact_sha256") == base["artifact_sha256"]
                 and candidate.get("base_commit") == base["source_commit"]
                 and candidate.get("database_identity") == base["database_identity"]
                 and candidate.get("contract_digest") == base["contract_digest"], "CANDIDATE_BASE_CHANGED")
        _require(candidate.get("release_id") != base["release_id"] and candidate.get("schema_changes") == [],
                 "PARSER_RELEASE_SCHEMA_OR_ID_CHANGED")
        _require(candidate.get("generation") is not None and candidate["generation"] != base["generation"], "NEW_GENERATION_REQUIRED")
        for release in (base, candidate):
            _require(all(isinstance(release.get(key), str) and SHA.fullmatch(release[key])
                         for key in ("artifact_sha256", "parser_digest", "profile_digest", "contract_digest")), "RELEASE_DIGEST_REQUIRED")
            _require(isinstance(release.get("release_id"), str) and ID.fullmatch(release["release_id"])
                     and isinstance(release.get("generation"), str) and ID.fullmatch(release["generation"])
                     and isinstance(release.get("database_identity"), str) and bool(release["database_identity"]), "RELEASE_IDENTITY_REQUIRED")
        policy = self.registry.canary_policy(document["canary_policy_digest"])
        _require(content_digest(policy) == document["canary_policy_digest"], "CANARY_POLICY_CHANGED")
        targets = policy.get("targets")
        _require(isinstance(targets, list) and 1 <= len(targets) <= 20 and policy.get("mode") == "READ_ONLY",
                 "CANARY_BOUNDS_OR_MODE")
        _require(len({target["capture_id"] for target in targets}) == len(targets)
                 and all(target in document["targets"] for target in targets), "CANARY_TARGETS_CHANGED")
        _require(isinstance(policy.get("expected_result_digest"), str) and SHA.fullmatch(policy["expected_result_digest"]),
                 "CANARY_ORACLE_REQUIRED")
        return document, base, candidate, policy

    def _check_artifacts(self, base, candidate, record=None):
        prepared = (record or {}).get("prepared")
        for release, prepared_key in ((base, "base_path"), (candidate, "candidate_path")):
            path = prepared[prepared_key] if prepared else release["artifact_path"]
            _require(_jar_digest(path) == release["artifact_sha256"], "ARTIFACT_DIGEST_CHANGED")

    def _receipt_binding(self, record, operation_id, request_digest, manifest):
        _require(record.get("operation_id") == operation_id and record.get("request_digest") == request_digest
                 and record.get("manifest_digest") == manifest.digest and record.get("approval_scope") == SCOPE
                 and record.get("approval_state") == "CONSUMED" and isinstance(record.get("approval_digest"), str)
                 and SHA.fullmatch(record["approval_digest"]), "CONSUMED_APPROVAL_RECEIPT_CHANGED")

    def _owned_candidate(self, base, candidate, fence):
        """Ownership check for compensation when readiness itself is incorrect."""
        fence.assert_held()
        live = self.host.observe(base["service_id"], fence)
        _require(live.get("service_id") == base["service_id"] and live.get("generation") == candidate["generation"]
                 and live.get("artifact_sha256") == candidate["artifact_sha256"], "LIVE_RELEASE_CHANGED")
        return live

    def _live(self, service_id, expected, fence, *, drained=False, healthy=True):
        fence.assert_held()
        live = self.host.observe(service_id, fence)
        _require(live.get("service_id") == service_id and _identity(live) == _identity(expected)
                 and live.get("generation") == expected["generation"], "LIVE_RELEASE_CHANGED")
        _require(live.get("writes_enabled") is False, "LIVE_WRITES_NOT_DISABLED")
        if healthy:
            _require(live.get("healthy") is True, "LIVE_UNHEALTHY")
        if drained:
            _require(live.get("drained") is True, "OLD_GENERATION_NOT_DRAINED")
        return live

    def _save(self, record, fence, **changes):
        fence.assert_held()
        return self.journal.save(record, changes, fence)

    def _command(self, record, base, candidate, manifest, policy, action):
        targets = policy["targets"] if action == "canary" else manifest.document["targets"]
        return {"operation_id": record["operation_id"], "manifest_digest": manifest.digest,
                "service_id": base["service_id"], "base": base, "candidate": candidate,
                "prepared": record.get("prepared"), "writes_enabled": False,
                "targets": targets, "targets_digest": content_digest(targets),
                "expected_result_digest": policy["expected_result_digest"], "action": action,
                "rollback_generation": record["operation_id"] + "-rollback",
                "rollback_release_id": record["operation_id"] + "-rollback",
                "expected_generation": candidate["generation"] if action in {"canary", "replay", "rollback"}
                    else base["generation"]}

    def _accept(self, record, action, result, base, candidate, policy, manifest, fence):
        _require(isinstance(result, dict) and result.get("operation_id") == record["operation_id"], "EFFECT_RECEIPT_MISMATCH")
        if action == "prepare":
            _require(_jar_digest(result["candidate_path"]) == candidate["artifact_sha256"]
                     and _jar_digest(result["base_path"]) == base["artifact_sha256"], "PREPARED_ARTIFACT_CHANGED")
            return self._save(record, fence, state="PREPARED", prepared=result, pending_action=None)
        if action == "drain":
            self._live(base["service_id"], base, fence, drained=True)
            return self._save(record, fence, state="DRAINED", pending_action=None)
        if action == "start":
            return self._save(record, fence, state="VERIFYING", pending_action=None)
        if action == "rollback":
            rollback_identity = {**base, "generation": record["operation_id"] + "-rollback",
                                 "release_id": record["operation_id"] + "-rollback"}
            self._live(base["service_id"], rollback_identity, fence)
            return self._save(record, fence, state="ROLLED_BACK", pending_action=None,
                              rollback_receipt=result, rollback_runtime=rollback_identity)
        targets = policy["targets"] if action == "canary" else manifest.document["targets"]
        _require(result.get("manifest_digest") == manifest.digest and result.get("targets_digest") == content_digest(targets)
                 and result.get("operating_writes") == 0 and type(result.get("operating_writes")) is int
                 and result.get("runtime") == _identity(candidate), "REPLAY_RECEIPT_SCOPE_CHANGED")
        self._live(base["service_id"], candidate, fence)
        if action == "canary":
            if result.get("passed") is not True or result.get("result_digest") != policy["expected_result_digest"]:
                return self._save(record, fence, state="ROLLBACK_NEEDED", pending_action=None,
                                  hold_code="CANARY_FAILED", canary_receipt=result)
            return self._save(record, fence, state="REPLAY", pending_action=None, canary_receipt=result)
        items = result.get("items", [])
        _require(isinstance(items, list) and len(items) == len(targets)
                 and len({row["capture_id"] for row in items}) == len(items)
                 and {row["capture_id"] for row in items} == {target["capture_id"] for target in targets}, "REPLAY_COVERAGE_CHANGED")
        _require(all(row.get("status") in {"PROPOSED", "NO_CHANGE", "STALE", "HELD", "FAILED", "UNKNOWN", "NOT_ATTEMPTED"} for row in items),
                 "REPLAY_STATUS_UNVERIFIED")
        if any(row["status"] == "UNKNOWN" for row in items):
            return self._save(record, fence, state="RECOVERY_REQUIRED", pending_action="replay",
                              replay_receipt=result, hold_code="REPLAY_OUTCOME_UNKNOWN")
        state = "ACTIVE" if all(row["status"] in {"PROPOSED", "NO_CHANGE", "STALE"} for row in items) else "REPLAY_HELD"
        return self._save(record, fence, state=state, pending_action=None, replay_receipt=result,
                          operating_writes=0, incident_resolved=False)

    def advance(self, manifest: ReviewManifest, binding: PublicationBinding, *, now=None):
        """Verify current PR authority, then perform at most one serialized effect."""
        document, base, candidate, policy = self._inputs(manifest)
        operation_id = "release-" + uuid.uuid5(uuid.NAMESPACE_URL, "response-release:" + manifest.digest).hex
        request_digest = content_digest({"manifest": manifest.digest, "publication": binding.__dict__,
                                         "base": base, "candidate": candidate, "canary": policy})
        with self.journal.service_lock(base["service_id"]) as fence:
            fence.assert_held()
            record = self.journal.load(base["service_id"], operation_id, fence)
            if record is not None:
                self._receipt_binding(record, operation_id, request_digest, manifest)
                if record["state"] in TERMINAL or record.get("pending_action"):
                    return record
            self._check_artifacts(base, candidate, record)
            if record is None or record["state"] not in {"ROLLBACK_NEEDED", "VERIFYING"}:
                snapshot = self.provider.read_snapshot(binding, manifest)
                evidence = self.verifier.evaluate(manifest, binding, snapshot, now=now or datetime.now(timezone.utc))
                record = self.journal.claim(base["service_id"], operation_id, evidence, request_digest, fence)
                self._receipt_binding(record, operation_id, request_digest, manifest)
            if record["state"] in TERMINAL:
                return record
            if record.get("pending_action"):
                # No automatic retry: callers must explicitly reconcile an in-flight or unknown effect.
                return record
            state = record["state"]
            if state == "VERIFYING":
                try:
                    self._live(base["service_id"], candidate, fence)
                    return self._save(record, fence, state="CANARY")
                except ReleaseHold as error:
                    try:
                        self._owned_candidate(base, candidate, fence)
                        return self._save(record, fence, state="ROLLBACK_NEEDED", hold_code="HEALTH_FAILED")
                    except (ReleaseHold, OSError):
                        pass
                    return self._save(record, fence, state="RECOVERY_REQUIRED", hold_code=str(error))
                except OSError:
                    return self._save(record, fence, state="VERIFYING", hold_code="RUNTIME_READINESS_UNKNOWN")
            action = next((name for name, phase in ACTION_START.items() if phase == state), None)
            _require(action is not None, "RECONCILIATION_REQUIRED")
            try:
                if action in {"prepare", "drain", "start"}:
                    self._live(base["service_id"], base, fence, drained=action == "start")
                elif action == "rollback":
                    self._owned_candidate(base, candidate, fence)
                else:
                    self._live(base["service_id"], candidate, fence, healthy=action != "rollback")
                if action in {"start", "rollback"}:
                    prepared = record.get("prepared", {})
                    _require(_jar_digest(prepared.get("candidate_path", "")) == candidate["artifact_sha256"]
                             and _jar_digest(prepared.get("base_path", "")) == base["artifact_sha256"], "PREPARED_ARTIFACT_CHANGED")
            except (ReleaseHold, OSError) as error:
                target = "RECOVERY_REQUIRED" if action == "rollback" else "REPLAY_HELD" if action == "replay" else "STALE"
                return self._save(record, fence, state=target, hold_code=str(error) if isinstance(error, ReleaseHold) else "ARTIFACT_UNAVAILABLE")
            record = self._save(record, fence, state=action.upper() + "_IN_FLIGHT", pending_action=action)
            command = self._command(record, base, candidate, manifest, policy, action)
            try:
                adapter = self.replay if action in {"canary", "replay"} else self.host
                result = getattr(adapter, action)(command, fence)
                return self._accept(record, action, result, base, candidate, policy, manifest, fence)
            except Exception:
                # Outcome may have happened before the transport/process failure. Never infer non-execution.
                return self._save(record, fence, state="RECOVERY_REQUIRED", hold_code="EFFECT_OUTCOME_UNKNOWN")

    def reconcile(self, manifest: ReviewManifest, binding: PublicationBinding, *, now=None):
        """Read adapter receipts only. DONE progresses; NOT_STARTED permits a later retry.

        This method itself does not start/stop/switch/replay anything. It uses the
        exact previously consumed approval receipt, so expiry cannot hide unknown
        results. A later fresh start/replay still requires current authority;
        fixed-base rollback can use the original consumed compensation authority.
        """
        _, base, candidate, policy = self._inputs(manifest)
        operation_id = "release-" + uuid.uuid5(uuid.NAMESPACE_URL, "response-release:" + manifest.digest).hex
        request_digest = content_digest({"manifest": manifest.digest, "publication": binding.__dict__,
                                         "base": base, "candidate": candidate, "canary": policy})
        with self.journal.service_lock(base["service_id"]) as fence:
            record = self.journal.load(base["service_id"], operation_id, fence)
            _require(record is not None, "CONSUMED_APPROVAL_RECEIPT_REQUIRED")
            self._receipt_binding(record, operation_id, request_digest, manifest)
            action = record.get("pending_action")
            if action is None:
                return record
            adapter = self.replay if action in {"canary", "replay"} else self.host
            fence.assert_held()
            try:
                outcome = adapter.reconcile(operation_id, action, fence)
                if outcome.get("outcome") == "DONE":
                    return self._accept(record, action, outcome["result"], base, candidate, policy, manifest, fence)
                if outcome.get("outcome") == "NOT_STARTED":
                    return self._save(record, fence, state=ACTION_START[action], pending_action=None,
                                      hold_code=None, reconciliation="PROVEN_NOT_STARTED")
            except Exception:
                pass
            return self._save(record, fence, state="RECOVERY_REQUIRED", hold_code="EFFECT_OUTCOME_UNKNOWN")
