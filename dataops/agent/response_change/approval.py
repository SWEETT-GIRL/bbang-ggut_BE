"""Trusted immutable review authority and PostgreSQL delivery/approval journals.

No model tool imports this module. A PR body, label or approve boolean never
creates authority: only a host-frozen manifest plus authenticated GitHub merge
facts may do so. This module cannot deploy code or write serving data.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from hashlib import sha256
import json
import re
import uuid

from psycopg2.extras import Json, RealDictCursor


PARSER_PATHS = frozenset({
    "src/main/java/sweetgirl/dataops/snapshot/parser/NaverPlaceParser.java",
    "src/main/resources/response-agent/menu-structure-profile-v1.json",
})
SCOPES = {"PARSER_REPAIR": "DEPLOY_REPLAY_PROPOSE", "DATA_APPLY": "APPLY_EXACT_MANIFEST", "REPORT_ONLY": "NONE"}
DIGEST_FIELDS = frozenset({"candidate_tree_digest", "patch_digest", "structure_profile_digest",
    "semantic_contract_digest", "validation_digest", "build_recipe_digest", "artifact_sha256",
    "replay_target_manifest_digest", "canary_policy_digest"})
COMMON = frozenset({"schema_version", "review_id", "kind", "scope", "incident_id", "run_id", "revision",
    "base_release", "base_commit", "expires_at", "files", "targets", "row_limit", "preview_digest"})
SHA = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}\Z")


class ApprovalHold(ValueError):
    """Stable public code; raw input and credentials never appear in errors."""


class OutboxLeaseLost(RuntimeError):
    pass


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def content_digest(value):
    return sha256(canonical(value).encode()).hexdigest()


def _check(condition, reason):
    if not condition:
        raise ApprovalHold(reason)


def _timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        _check(parsed.tzinfo is not None, "TIMESTAMP_ZONE_REQUIRED")
        return parsed.astimezone(timezone.utc)
    except (TypeError, ValueError, AttributeError):
        raise ApprovalHold("INVALID_TIMESTAMP") from None


def _sha(value):
    return isinstance(value, str) and SHA.fullmatch(value) is not None


def _safe_path(path):
    return isinstance(path, str) and 0 < len(path) <= 240 and not path.startswith("/") and all(
        part not in {"", ".", ".."} for part in path.split("/")) and "\\" not in path and not any(ord(c) < 32 for c in path)


def code_tree_digest(entries, review_id):
    """Hash the complete Git blob/mode inventory excluding this review's documents.

    Git tree SHA and this SHA-256 inventory digest are different identities. The
    artifact builder must freeze this inventory; an approval cannot infer it.
    """
    prefix = f"docs/response-agent-reviews/{review_id}/"
    excluded = {prefix+name for name in ("review.md", "manifest.json", "data-manifest.json")}
    checked = {}
    for path, value in entries.items():
        _check(_safe_path(path), "INVALID_TREE_PATH")
        _check(isinstance(value, dict) and set(value) == {"sha", "mode"}
               and isinstance(value["sha"], str) and COMMIT.fullmatch(value["sha"])
               and value["mode"] in {"100644", "100755"}, "UNSUPPORTED_TREE_ENTRY")
        if path not in excluded:
            checked[path] = value
    return content_digest(checked)


def render_data_manifest(targets):
    return canonical({"schema_version": 1, "targets": targets}) + "\n"


def render_data_preview(targets):
    """Every old/new value and lineage is visible; external text stays inside a JSON fence."""
    data = json.dumps({"schema_version": 1, "targets": targets},ensure_ascii=False,sort_keys=True,indent=2,allow_nan=False)
    fence = "`" * max(3,1+max((len(match.group()) for match in re.finditer(r"`+",data)),default=0))
    return ("# 가게 정보 변경 검토\n\n" + f"고정 대상: {len(targets)}건\n\n"
            "이 문서와 data-manifest.json의 대상·전후 값만 승인 범위입니다. 코드 배포는 포함하지 않습니다.\n\n"
            + fence + "json\n" + data + "\n" + fence + "\n")


@dataclass(frozen=True, init=False)
class ReviewManifest:
    _canonical: str

    def __init__(self, document):
        # A canonical string is the stored representation: callers cannot mutate nested targets.
        try:
            frozen = json.loads(canonical(document))
        except (TypeError, ValueError):
            raise ApprovalHold("MANIFEST_NOT_JSON") from None
        _check(isinstance(frozen, dict) and set(frozen) == COMMON | DIGEST_FIELDS, "MANIFEST_FIELDS")
        _check(isinstance(frozen["schema_version"],int) and frozen["schema_version"] == 1 and not isinstance(frozen["schema_version"], bool), "MANIFEST_VERSION")
        kind = frozen["kind"]
        _check(isinstance(kind,str) and kind in SCOPES and frozen["scope"] == SCOPES[kind], "MANIFEST_SCOPE")
        for field in ("review_id", "incident_id", "run_id", "base_release"):
            _check(isinstance(frozen[field], str) and ID.fullmatch(frozen[field]), "MANIFEST_ID")
        _check(isinstance(frozen["revision"], int) and not isinstance(frozen["revision"], bool)
               and frozen["revision"] > 0, "MANIFEST_REVISION")
        _check(isinstance(frozen["base_commit"], str) and COMMIT.fullmatch(frozen["base_commit"]), "BASE_COMMIT")
        _timestamp(frozen["expires_at"])
        prefix = f"docs/response-agent-reviews/{frozen['review_id']}/"
        allowed = {prefix + "review.md"}
        if kind == "PARSER_REPAIR":
            allowed |= PARSER_PATHS
        elif kind == "DATA_APPLY":
            allowed |= {prefix + "data-manifest.json"}
        files = frozen["files"]
        _check(isinstance(files, dict) and 0 < len(files) <= 8 and prefix + "review.md" in files, "MANIFEST_FILES")
        _check(all(path in allowed and _sha(value) for path, value in files.items()), "FILE_ALLOWLIST")
        if kind == "PARSER_REPAIR":
            _check(bool(PARSER_PATHS & files.keys()), "PARSER_PATCH_MISSING")
        if kind == "DATA_APPLY":
            _check(prefix + "data-manifest.json" in files, "DATA_DOCUMENT_MISSING")
        for field in DIGEST_FIELDS:
            _check(_sha(frozen[field]) if kind != "REPORT_ONLY" else frozen[field] is None, "MANIFEST_DIGEST")
        _check(_sha(frozen["preview_digest"]), "PREVIEW_DIGEST")
        _check(files[prefix + "review.md"] == frozen["preview_digest"], "PREVIEW_BINDING")
        targets = frozen["targets"]
        _check(isinstance(targets, list) and len(targets) <= 1000, "TARGET_BOUNDS")
        _check(isinstance(frozen["row_limit"], int) and not isinstance(frozen["row_limit"], bool)
               and frozen["row_limit"] == len(targets), "TARGET_ROW_LIMIT")
        if kind == "REPORT_ONLY":
            _check(not targets, "REPORT_HAS_EXECUTION_TARGETS")
        else:
            _check(bool(targets), "EXECUTION_TARGETS_REQUIRED")
            _check(frozen["replay_target_manifest_digest"] == content_digest(targets), "TARGET_DIGEST")
        identities = set()
        for target in targets:
            _check(isinstance(target, dict), "TARGET_SHAPE")
            if kind == "PARSER_REPAIR":
                _check(set(target) == {"capture_id", "payload_digest", "store_id", "external_id", "observed_at", "attempt_seq"}, "CAPTURE_TARGET_FIELDS")
                _check(_sha(target["payload_digest"]), "CAPTURE_TARGET_DIGEST")
                _timestamp(target["observed_at"])
                _check(isinstance(target["attempt_seq"],int) and not isinstance(target["attempt_seq"],bool)
                       and target["attempt_seq"] >= 0, "CAPTURE_ATTEMPT")
                identity = target["capture_id"]
            else:
                _check(set(target) == {"proposal_id", "store_id", "external_id", "operation", "field",
                    "old_value", "new_value", "capture_id", "capture_digest", "release_id", "reprocess_item_id"}, "DATA_TARGET_FIELDS")
                _check(isinstance(target["operation"],str) and target["operation"] in {"UPDATE", "REGISTER"}, "DATA_OPERATION")
                _check(isinstance(target["field"], str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,199}",target["field"]), "DATA_FIELD")
                _check(_sha(target["capture_digest"]), "DATA_CAPTURE_DIGEST")
                for field in ("release_id", "reprocess_item_id", "capture_id"):
                    _check(isinstance(target[field], str) and ID.fullmatch(target[field]), "DATA_LINEAGE")
                identity = str(target["proposal_id"])
                _check(isinstance(target["proposal_id"], int) and not isinstance(target["proposal_id"], bool)
                       and target["proposal_id"] > 0, "PROPOSAL_ID")
            _check(isinstance(identity, str) and ID.fullmatch(identity) and identity not in identities, "TARGET_IDENTITY")
            identities.add(identity)
            _check(isinstance(target["store_id"], int) and not isinstance(target["store_id"], bool)
                   and target["store_id"] > 0 and isinstance(target["external_id"], str)
                   and ID.fullmatch(target["external_id"]), "TARGET_STORE")
        if kind == "DATA_APPLY":
            _check(files[prefix+"data-manifest.json"] == sha256(render_data_manifest(targets).encode()).hexdigest(),
                   "DATA_DOCUMENT_BINDING")
            _check(frozen["preview_digest"] == sha256(render_data_preview(targets).encode()).hexdigest(),
                   "DATA_PREVIEW_BINDING")
        object.__setattr__(self, "_canonical", canonical(frozen))

    @property
    def document(self):
        return json.loads(self._canonical)

    @property
    def digest(self):
        return sha256(self._canonical.encode()).hexdigest()


@dataclass(frozen=True)
class PublicationBinding:
    repository: str
    pr_number: int
    head_sha: str
    manifest_digest: str

    def __post_init__(self):
        _check(isinstance(self.repository, str) and REPO.fullmatch(self.repository), "REPOSITORY")
        _check(isinstance(self.pr_number, int) and not isinstance(self.pr_number, bool) and self.pr_number > 0, "PR_NUMBER")
        _check(isinstance(self.head_sha, str) and COMMIT.fullmatch(self.head_sha) and _sha(self.manifest_digest), "PUBLICATION_BINDING")


@dataclass(frozen=True)
class PullRequestSnapshot:
    # Constructed only by the trusted authenticated provider adapter, not from webhook/model JSON.
    repository: str
    pr_number: int
    head_sha: str
    merged: bool
    merge_commit_sha: str | None
    merged_by_id: int | None
    merged_at: str | None
    draft: bool
    files_json: str
    head_code_tree_digest: str
    merge_code_tree_digest: str | None
    head_repository: str
    merge_files_json: str | None = None


@dataclass(frozen=True)
class ApprovalEvidence:
    manifest_digest: str
    repository: str
    pr_number: int
    head_sha: str
    merge_commit_sha: str
    actor_id: int
    merged_at: str
    scope: str

    @property
    def event_key(self):
        return f"{self.repository}:{self.pr_number}:{self.merge_commit_sha}"

    @property
    def digest(self):
        return content_digest(self.__dict__)


class ApprovalVerifier:
    def __init__(self, *, allowed_repository, allowed_actor_ids):
        _check(isinstance(allowed_repository, str) and REPO.fullmatch(allowed_repository), "REPOSITORY")
        _check(bool(allowed_actor_ids) and all(isinstance(actor, int) and not isinstance(actor, bool) and actor > 0
                                             for actor in allowed_actor_ids), "APPROVER_REQUIRED")
        self.repository = allowed_repository
        self.actors = frozenset(allowed_actor_ids)

    def evaluate(self, manifest, binding, snapshot, *, now=None):
        document = manifest.document
        current = now or datetime.now(timezone.utc)
        _check(current.tzinfo is not None, "TIMESTAMP_ZONE_REQUIRED")
        _check(document["kind"] != "REPORT_ONLY", "REPORT_ONLY_NOT_AUTHORITY")
        _check(current < _timestamp(document["expires_at"]), "APPROVAL_EXPIRED")
        _check(binding.manifest_digest == manifest.digest, "MANIFEST_BINDING_CHANGED")
        _check(binding.repository == snapshot.repository == snapshot.head_repository == self.repository
               and binding.pr_number == snapshot.pr_number, "PR_REPOSITORY_MISMATCH")
        _check(binding.head_sha == snapshot.head_sha, "PR_HEAD_CHANGED")
        _check(snapshot.merged is True and snapshot.draft is False and snapshot.merge_commit_sha
               and COMMIT.fullmatch(snapshot.merge_commit_sha), "PR_NOT_MERGED")
        _check(isinstance(snapshot.merged_by_id,int) and not isinstance(snapshot.merged_by_id,bool)
               and snapshot.merged_by_id in self.actors, "MERGER_NOT_ALLOWED")
        merged_at = _timestamp(snapshot.merged_at)
        _check(merged_at <= current and merged_at < _timestamp(document["expires_at"]), "MERGE_TIME_INVALID")
        try:
            files = json.loads(snapshot.files_json)
            merge_files = json.loads(snapshot.merge_files_json)
        except (ValueError, TypeError):
            raise ApprovalHold("PR_FILES_UNVERIFIED") from None
        _check(files == document["files"] and merge_files == document["files"], "PR_FILES_CHANGED")
        _check(snapshot.head_code_tree_digest == document["candidate_tree_digest"]
               and snapshot.merge_code_tree_digest == document["candidate_tree_digest"], "MERGE_TREE_CHANGED")
        return ApprovalEvidence(manifest.digest, binding.repository, binding.pr_number, binding.head_sha,
                                snapshot.merge_commit_sha, snapshot.merged_by_id, snapshot.merged_at, document["scope"])


@dataclass(frozen=True)
class OutboxClaim:
    event_id: str
    owner: str
    epoch: int
    marker: str
    payload: dict
    reconciliation_required: bool
    manifest_digest: str = ""


def _values(row, *fields):
    return [row[field] for field in fields] if isinstance(row,dict) else row


class PgReviewStore:
    """All enqueue/write methods accepting a cursor belong to the caller's business transaction."""
    def __init__(self, connect):
        self.connect = connect

    def freeze(self, cursor, manifest, *, validated=False):
        doc = manifest.document
        review_state = "VALIDATED" if validated is True and doc["kind"] != "REPORT_ONLY" else "HELD"
        cursor.execute("""INSERT INTO response_agent_review_manifest
            (manifest_digest, run_id, revision, kind, scope, document, expires_at, review_state)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING""",
            (manifest.digest, doc["run_id"], doc["revision"], doc["kind"], doc["scope"], Json(doc), doc["expires_at"], review_state))
        cursor.execute("SELECT document FROM response_agent_review_manifest WHERE manifest_digest=%s", (manifest.digest,))
        row = cursor.fetchone()
        _check(row is not None and _values(row,"document")[0] == doc, "MANIFEST_STORE_CONFLICT")

    def bind_publication(self, cursor, binding):
        cursor.execute("""INSERT INTO response_agent_review_publication
            (manifest_digest, repository, pr_number, head_sha) VALUES (%s,%s,%s,%s) ON CONFLICT DO NOTHING""",
            (binding.manifest_digest, binding.repository, binding.pr_number, binding.head_sha))
        cursor.execute("""SELECT repository,pr_number,head_sha FROM response_agent_review_publication
            WHERE manifest_digest=%s""", (binding.manifest_digest,))
        row = cursor.fetchone()
        _check(row is not None and tuple(_values(row,"repository","pr_number","head_sha")) == (binding.repository, binding.pr_number, binding.head_sha), "PUBLICATION_STORE_CONFLICT")

    def approve(self, cursor, evidence):
        # FK to exact publication and uniqueness make duplicate authenticated merge delivery idempotent.
        cursor.execute("""SELECT m.document,p.repository,p.pr_number,p.head_sha FROM response_agent_review_manifest m
            JOIN response_agent_review_publication p USING(manifest_digest)
            WHERE m.manifest_digest=%s AND m.review_state='VALIDATED' AND m.expires_at>CURRENT_TIMESTAMP FOR UPDATE OF m""", (evidence.manifest_digest,))
        row = cursor.fetchone()
        row = _values(row,"document","repository","pr_number","head_sha") if row is not None else None
        _check(row is not None and row[0]["kind"] != "REPORT_ONLY" and row[0]["scope"] == evidence.scope
               and tuple(row[1:]) == (evidence.repository,evidence.pr_number,evidence.head_sha), "APPROVAL_STORE_BINDING")
        cursor.execute("""INSERT INTO response_agent_review_approval
            (approval_digest,manifest_digest,event_key,actor_id,scope,evidence,state)
            VALUES (%s,%s,%s,%s,%s,%s,'APPROVED') ON CONFLICT DO NOTHING""",
            (evidence.digest,evidence.manifest_digest,evidence.event_key,evidence.actor_id,evidence.scope,Json(evidence.__dict__)))
        cursor.execute("SELECT approval_digest FROM response_agent_review_approval WHERE manifest_digest=%s", (evidence.manifest_digest,))
        _check(_values(cursor.fetchone(),"approval_digest")[0] == evidence.digest, "APPROVAL_EVENT_CONFLICT")
        return evidence.digest

    def consume(self, cursor, approval_digest, *, scope, operation_id):
        _check(scope in {"DEPLOY_REPLAY_PROPOSE", "APPLY_EXACT_MANIFEST"}
               and isinstance(operation_id,str) and ID.fullmatch(operation_id), "APPROVAL_CONSUMER")
        cursor.execute("""UPDATE response_agent_review_approval a SET state='CONSUMED',operation_id=%s,
            consumed_at=CURRENT_TIMESTAMP FROM response_agent_review_manifest m
            WHERE a.approval_digest=%s AND a.manifest_digest=m.manifest_digest AND a.scope=%s
            AND a.state='APPROVED' AND m.review_state='VALIDATED' AND m.expires_at>CURRENT_TIMESTAMP RETURNING a.manifest_digest""",
            (operation_id,approval_digest,scope))
        row = cursor.fetchone()
        return _values(row,"manifest_digest")[0] if row else None

    def invalidate(self, cursor, manifest_digest, *, state):
        _check(_sha(manifest_digest) and state in {"HELD", "SUPERSEDED", "REJECTED"}, "REVIEW_INVALIDATION")
        cursor.execute("UPDATE response_agent_review_manifest SET review_state=%s WHERE manifest_digest=%s",
                       (state,manifest_digest))
        cursor.execute("""UPDATE response_agent_review_approval SET state='REVOKED'
            WHERE manifest_digest=%s AND state='APPROVED'""", (manifest_digest,))
        # Already consumed operations remain consumed; the controller must stop/reconcile its next item.

    def enqueue(self, cursor, *, manifest_digest, event_kind, destination, revision, payload):
        _check(_sha(manifest_digest) and isinstance(event_kind,str) and ID.fullmatch(event_kind)
               and isinstance(destination,str) and REPO.fullmatch(destination)
               and isinstance(revision,int) and not isinstance(revision,bool) and revision > 0, "OUTBOX_KEY")
        encoded = canonical(payload)
        _check(len(encoded.encode()) <= 256 * 1024, "OUTBOX_PAYLOAD_LIMIT")
        key = content_digest([manifest_digest,event_kind,destination,revision])
        event_id = str(uuid.uuid5(uuid.NAMESPACE_URL,"response-agent:"+key))
        marker = f"<!-- response-agent:{key} -->"
        payload_sha = sha256(encoded.encode()).hexdigest()
        cursor.execute("""INSERT INTO response_agent_notification_outbox
            (event_id,event_key,manifest_digest,event_kind,destination,revision,payload_digest,payload,marker)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING""",
            (event_id,key,manifest_digest,event_kind,destination,revision,payload_sha,Json(payload),marker))
        cursor.execute("SELECT payload_digest FROM response_agent_notification_outbox WHERE event_key=%s",(key,))
        row = cursor.fetchone()
        _check(row is not None and _values(row,"payload_digest")[0] == payload_sha,"OUTBOX_CONTENT_CONFLICT")
        return event_id

    def claim(self, *, owner, lease_seconds=60, kinds=None):
        _check(isinstance(owner,str) and ID.fullmatch(owner) and isinstance(lease_seconds,int)
               and 5 <= lease_seconds <= 300, "OUTBOX_LEASE")
        _check(kinds is None or isinstance(kinds, (list, tuple)) and bool(kinds) and set(kinds) <= set(SCOPES), "OUTBOX_KIND_FILTER")
        with self.connect() as db:
            with db.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute("""SELECT o.* FROM response_agent_notification_outbox o
                    JOIN response_agent_review_manifest m USING(manifest_digest)
                    WHERE o.state IN ('PENDING','SENDING','DELIVERY_UNKNOWN') AND o.next_attempt_at<=CURRENT_TIMESTAMP
                    AND (o.lease_until IS NULL OR o.lease_until<CURRENT_TIMESTAMP)
                    AND (%s::text[] IS NULL OR m.kind=ANY(%s::text[]))
                    ORDER BY o.created_at,o.event_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED""", (kinds, kinds))
                row = cur.fetchone()
                if row is None: return None
                _check(content_digest(row["payload"]) == row["payload_digest"], "OUTBOX_PAYLOAD_CHANGED")
                cur.execute("""UPDATE response_agent_notification_outbox SET state='SENDING',owner=%s,
                    fencing_epoch=fencing_epoch+1,lease_until=CURRENT_TIMESTAMP+(%s * INTERVAL '1 second'),
                    attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE event_id=%s RETURNING fencing_epoch""",
                    (owner,lease_seconds,row["event_id"]))
                epoch = cur.fetchone()["fencing_epoch"]
                return OutboxClaim(str(row["event_id"]),owner,epoch,row["marker"],row["payload"],row["state"] != "PENDING",row["manifest_digest"])

    def renew(self, claim, *, lease_seconds=60):
        _check(isinstance(lease_seconds,int) and 50 <= lease_seconds <= 300,"OUTBOX_LEASE")
        with self.connect() as db:
            with db.cursor() as cur:
                cur.execute("""UPDATE response_agent_notification_outbox
                    SET lease_until=CURRENT_TIMESTAMP+(%s * INTERVAL '1 second'),updated_at=CURRENT_TIMESTAMP
                    WHERE event_id=%s AND owner=%s AND fencing_epoch=%s AND state='SENDING'
                    AND lease_until>CURRENT_TIMESTAMP""",(lease_seconds,claim.event_id,claim.owner,claim.epoch))
                if cur.rowcount != 1: raise OutboxLeaseLost("OUTBOX_LEASE_LOST")

    def finish(self, claim, *, provider_reference=None, error_code=None, publication_binding=None):
        state = "DELIVERED" if provider_reference is not None else "DELIVERY_UNKNOWN"
        if error_code is not None:
            _check(isinstance(error_code,str) and ID.fullmatch(error_code),"OUTBOX_ERROR_CODE")
        with self.connect() as db:
            with db.cursor() as cur:
                if publication_binding is not None:
                    _check(provider_reference is not None and publication_binding.manifest_digest == claim.manifest_digest,
                           "OUTBOX_PUBLICATION_BINDING")
                    self.bind_publication(cur, publication_binding)
                cur.execute("""UPDATE response_agent_notification_outbox SET state=%s,provider_reference=%s,
                    error_code=%s,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '60 seconds',
                    updated_at=CURRENT_TIMESTAMP WHERE event_id=%s AND owner=%s AND fencing_epoch=%s
                    AND state='SENDING' AND lease_until>CURRENT_TIMESTAMP""",
                    (state,Json(provider_reference) if provider_reference is not None else None,error_code,
                     claim.event_id,claim.owner,claim.epoch))
                if cur.rowcount != 1: raise OutboxLeaseLost("OUTBOX_LEASE_LOST")
