"""Fenced PostgreSQL incident dispatch with an explicitly approved input catalog.

This adapter consumes the existing capture tables. Novel incidents without a
human-frozen, redacted case and independent oracle are HELD without a model call.
The worker has no serving writer. Its executor is supplied by the trusted host,
never by incident content, and must checkpoint BEFORE every paid invocation.
"""
from contextlib import contextmanager
from dataclasses import dataclass, replace
from decimal import Decimal, ROUND_CEILING
import hashlib
import json
import math
import os
import re
from pathlib import Path
from typing import Callable
import uuid

from psycopg2.extras import Json, RealDictCursor

from .ledger import digest

TERMINAL = frozenset({"AWAITING_REVIEW", "HELD", "ERROR"})
MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
MAX_BUNDLE_BYTES = 512 * 1024
INCIDENT_BUDGET_USD = Decimal("0.50")
DAILY_BUDGET_USD = Decimal("2.00")
BUDGET_IDENTITY_VERSION = "source-structural-signature-v1"


class LostLease(RuntimeError):
    """Stop before further external work when the persisted claim is no longer ours."""


class Hold(ValueError):
    """Stable public reason; never contains raw payloads or credentials."""


@dataclass(frozen=True)
class FrozenBundle:
    case: dict
    source: str
    legacy_test: str
    fixtures: list
    deployment_parser_digest: str
    contract_version: str
    # Explicit production payload digests approved when the sanitized case was frozen.
    capture_payload_digests: tuple[str, ...]

    def digest(self):
        return digest(self.__dict__)


class ApprovedBundleCatalog:
    """Exact incident bindings, not a classifier that invents field meaning/oracles.

    current_parser_digest is the deployed ResponseDigests.classes value, not a
    single Java source hash. The approved bundle binds that deployment identity
    separately from the frozen source, test, oracle and captured payload hashes.
    A changed deployment requires a new explicit catalog approval.
    """
    def __init__(self, bundles, *, current_parser_digest, contract_version):
        self.current_parser_digest = current_parser_digest
        self.contract_version = contract_version
        # Freeze a deep copy now: a caller cannot mutate a registered oracle later.
        self.bundles = {key: FrozenBundle(**json.loads(json.dumps(value.__dict__)))
                        for key, value in bundles.items()}
        self.approved_digests = {key: value.digest() for key, value in self.bundles.items()}

    def prepare(self, incident, samples):
        if incident["source"] != "NAVER":
            raise Hold("UNSUPPORTED_SOURCE")
        if incident["parser_digest"] != self.current_parser_digest:
            raise Hold("DEPLOYED_PARSER_MISMATCH")
        if incident["contract_version"] != self.contract_version:
            raise Hold("FIELD_CONTRACT_MISMATCH")
        bundle = self.bundles.get(incident["fingerprint"])
        if bundle is None:
            raise Hold("APPROVED_REDACTED_CASE_REQUIRED")
        if (bundle.digest() != self.approved_digests[incident["fingerprint"]]
                or bundle.deployment_parser_digest != incident["parser_digest"]
                or bundle.contract_version != incident["contract_version"]):
            raise Hold("APPROVED_BUNDLE_BINDING_MISMATCH")
        if len(json.dumps(bundle.__dict__).encode()) > MAX_BUNDLE_BYTES:
            raise Hold("APPROVED_BUNDLE_TOO_LARGE")
        # Read no payload text into the model. Stored bytes must still match their
        # immutable receipt and the approved source mapping before using the case.
        matched = False
        for sample in samples:
            if (sample["source"] != incident["source"]
                    or sample["parser_digest"] != incident["parser_digest"]
                    or sample["contract_version"] != incident["contract_version"]):
                continue
            payload = sample.get("payload")
            if payload is None or len(payload.encode()) > MAX_PAYLOAD_BYTES:
                continue
            if hashlib.sha256(payload.encode()).hexdigest() != sample["payload_digest"]:
                raise Hold("CAPTURE_DIGEST_MISMATCH")
            if sample["payload_digest"] in bundle.capture_payload_digests:
                matched = True
        if not matched:
            raise Hold("APPROVED_CAPTURE_NOT_FOUND")
        return FrozenBundle(**json.loads(json.dumps(bundle.__dict__)))


@dataclass(frozen=True)
class Claim:
    run_id: str
    fingerprint: str
    owner: str
    epoch: int
    budget_usd: float
    run_directory: Path
    prior_report: dict
    bundle_digest: str | None
    recovered: bool
    budget_incident_id: str | None = None


def _valid_budget(value):
    if isinstance(value, bool) or not math.isfinite(float(value)) or float(value) <= 0:
        raise ValueError("an explicit positive finite budget is required")
    return Decimal(str(value))


def _request_exposure(report):
    requests = report.get("requests", [])
    if not isinstance(requests, list):
        raise ValueError("invalid request journal")
    confirmed = reserved = Decimal(0)
    ids = set()
    for index, request in enumerate(requests):
        # Older Runner versions lack IDs. Stable ordinal is safe within a fixed
        # immutable prefix; updated executors may provide request_id explicitly.
        request_id = request.get("request_id", str(index))
        if request_id in ids:
            raise ValueError("duplicate request identity")
        ids.add(request_id)
        amount = request.get("cost_usd", request.get("reserved_usd"))
        if amount is None or isinstance(amount, bool):
            raise ValueError("request exposure missing")
        value = Decimal(str(amount))
        if not value.is_finite() or value < 0:
            raise ValueError("invalid request exposure")
        if "cost_usd" in request:
            confirmed += value
        else:
            reserved += value
    return confirmed, reserved


def _budget_identity(incident):
    """Trusted detector signature, independent of version, fingerprint and run.

    Structural code/type remain meaningful. Dynamic path IDs/arguments/indices
    are normalized. A changed-signature revision requires reviewed lineage; model
    output cannot select a budget ID or mutate the immutable alias.
    """
    try:
        issues = json.loads(incident["diagnostics"])
        signature = set()
        for issue in issues:
            if not isinstance(issue, dict):
                raise ValueError("invalid diagnostic")
            if not issue.get("modelEligible", True):
                continue
            path = re.sub(r"\([^)]*\)", "(*)", str(issue.get("path", "")))
            path = re.sub(r"\[\d+\]", "[*]", path)
            path = re.sub(r":\d+", ":*", path)
            signature.add((path, str(issue.get("code", "")), str(issue.get("actualType", ""))))
    except (ValueError, TypeError):
        signature = set()
    # Missing diagnostics never give each fingerprint a fresh budget.
    return digest([BUDGET_IDENTITY_VERSION, incident["source"], sorted(signature)])


def _request_id(request, index):
    value = request.get("request_id", str(index))
    if not isinstance(value, str) or not value or len(value) > 200:
        raise ValueError("invalid request identity")
    return value


class PgDispatcher:
    def __init__(self, connection_factory: Callable, provider, output_root, *,
                 incident_budget_usd=.50, daily_budget_usd=2, terminal_sink=None):
        self.connection_factory = connection_factory
        self.provider = provider
        # Trusted host callback only; it may enqueue database outbox rows but perform no external I/O.
        self.terminal_sink = terminal_sink
        self.output_root = Path(output_root).resolve()
        self.incident_budget = _valid_budget(incident_budget_usd)
        self.daily_budget = _valid_budget(daily_budget_usd)
        if self.incident_budget > INCIDENT_BUDGET_USD or self.daily_budget > DAILY_BUDGET_USD:
            raise ValueError("approved cumulative budget may not be increased")

    @contextmanager
    def transaction(self):
        db = self.connection_factory()
        try:
            with db:
                with db.cursor(cursor_factory=RealDictCursor) as cursor:
                    yield cursor
        finally:
            db.close()

    def _bind_budget(self, cursor, incident):
        # Existing alias is authoritative even if future diagnostic policy changes.
        cursor.execute("SELECT budget_incident_id FROM response_agent_budget_alias WHERE fingerprint=%s",
                       (incident["fingerprint"],))
        bound = cursor.fetchone()
        if bound:
            return bound["budget_incident_id"]
        identity = _budget_identity(incident)
        cursor.execute("""INSERT INTO response_agent_budget_incident
            (budget_incident_id,source,identity_version,budget_usd) VALUES (%s,%s,%s,%s)
            ON CONFLICT DO NOTHING""", (identity, incident["source"], BUDGET_IDENTITY_VERSION, self.incident_budget))
        cursor.execute("""INSERT INTO response_agent_budget_alias(fingerprint,budget_incident_id)
            VALUES (%s,%s) ON CONFLICT DO NOTHING""", (incident["fingerprint"], identity))
        cursor.execute("SELECT budget_incident_id FROM response_agent_budget_alias WHERE fingerprint=%s",
                       (incident["fingerprint"],))
        return cursor.fetchone()["budget_incident_id"]

    def enqueue(self, *, budget_usd):
        """Copy eligible identities; captured source records remain read-only."""
        budget = min(_valid_budget(budget_usd), self.incident_budget)
        with self.transaction() as cursor:
            cursor.execute("""
                SELECT i.fingerprint,i.source,i.parser_digest,i.contract_version,i.diagnostics
                FROM response_incident i WHERE i.model_eligible=TRUE AND NOT EXISTS
                  (SELECT 1 FROM response_agent_run r WHERE r.fingerprint=i.fingerprint
                   AND r.parser_digest=i.parser_digest AND r.contract_version=i.contract_version)
                ORDER BY i.created_at,i.fingerprint LIMIT 32
                """)
            for incident in cursor.fetchall():
                identity = self._bind_budget(cursor, incident)
                run_id = str(uuid.uuid4())
                cursor.execute("""
                    INSERT INTO response_agent_run
                    (run_id,fingerprint,parser_digest,contract_version,state,owner,lease_until,budget_usd,
                     run_directory,budget_incident_id)
                    VALUES (%s,%s,%s,%s,'QUEUED','unclaimed',CURRENT_TIMESTAMP,%s,%s,%s)
                    ON CONFLICT (fingerprint,parser_digest,contract_version) DO NOTHING
                    """, (run_id, incident["fingerprint"], incident["parser_digest"],
                          incident["contract_version"], budget, str(self.output_root / run_id), identity))

    def claim(self, *, owner, budget_usd, lease_seconds=300):
        _valid_budget(budget_usd)
        if not isinstance(owner, str) or not 1 <= len(owner) <= 200:
            raise ValueError("owner required")
        if not 30 <= lease_seconds <= 3600:
            raise ValueError("lease must be 30..3600 seconds")
        self.enqueue(budget_usd=budget_usd)
        with self.transaction() as cursor:
            cursor.execute("""
                SELECT r.*,i.source,i.diagnostics FROM response_agent_run r
                JOIN response_incident i ON i.fingerprint=r.fingerprint
                  AND i.parser_digest=r.parser_digest AND i.contract_version=r.contract_version
                WHERE i.model_eligible=TRUE AND
                  (r.state='QUEUED' OR (r.state='RUNNING' AND r.lease_until<CURRENT_TIMESTAMP))
                ORDER BY i.created_at,i.fingerprint FOR UPDATE OF r SKIP LOCKED LIMIT 1
                """)
            row = cursor.fetchone()
            if row is None:
                return None
            recovering = row["state"] == "RUNNING"
            if row["budget_incident_id"] is None:
                if row["checkpoint"].get("requests"):
                    cursor.execute("""UPDATE response_agent_run SET state='HELD',
                        stop_reason='LEGACY_BUDGET_RECONCILIATION_REQUIRED' WHERE run_id=%s""", (row["run_id"],))
                    return None
                identity = self._bind_budget(cursor, row)
                cursor.execute("UPDATE response_agent_run SET budget_incident_id=%s WHERE run_id=%s",
                               (identity, row["run_id"]))
                row["budget_incident_id"] = identity
            # Original budget survives lease recovery and configuration changes.
            cursor.execute("""
                UPDATE response_agent_run SET budget_usd=CASE WHEN state='QUEUED' THEN LEAST(budget_usd,%s) ELSE budget_usd END,
                state='RUNNING',owner=%s,fencing_epoch=fencing_epoch+1,
                lease_until=CURRENT_TIMESTAMP + %s * INTERVAL '1 second',updated_at=CURRENT_TIMESTAMP
                WHERE run_id=%s RETURNING fencing_epoch,budget_usd
                """, (min(_valid_budget(budget_usd), self.incident_budget), owner, lease_seconds, row["run_id"]))
            updated = cursor.fetchone()
            epoch = updated["fencing_epoch"]
            claim = Claim(str(row["run_id"]), row["fingerprint"], owner, epoch,
                          float(updated["budget_usd"]), Path(row["run_directory"]), row["checkpoint"],
                          row["bundle_digest"], recovering, row["budget_incident_id"])
            incident = {key: row[key] for key in
                        ("fingerprint", "source", "parser_digest", "contract_version", "diagnostics", "budget_incident_id")}
            return claim, incident

    def samples(self, fingerprint):
        with self.transaction() as cursor:
            cursor.execute("""
                SELECT c.capture_id,c.store_id,c.external_id,c.observed_at,c.attempt_seq,c.status,c.received_at,
                       c.source,c.parser_digest,c.contract_version,c.payload_digest,
                       CASE WHEN octet_length(c.payload)<=%s THEN c.payload ELSE NULL END AS payload
                FROM response_incident_sample s JOIN response_capture c ON c.capture_id=s.capture_id
                WHERE s.fingerprint=%s ORDER BY c.received_at,c.capture_id LIMIT 8
                """, (MAX_PAYLOAD_BYTES, fingerprint))
            return list(cursor.fetchall())

    def captured_sources(self, capture_ids):
        """Read only the immutable source selection frozen for this analysis."""
        if not isinstance(capture_ids, list) or not 1 <= len(capture_ids) <= 6:
            raise Hold("FROZEN_CAPTURE_BINDING_INVALID")
        with self.transaction() as cursor:
            cursor.execute("""SELECT capture_id,store_id,external_id,observed_at,attempt_seq,status,
                       source,parser_digest,contract_version,payload_digest,
                       CASE WHEN octet_length(payload)<=%s THEN payload ELSE NULL END AS payload
                FROM response_capture WHERE capture_id=ANY(%s)""", (MAX_PAYLOAD_BYTES, capture_ids))
            return list(cursor.fetchall())

    def checkpoint(self, claim, report, *, lease_seconds=300, bundle_digest=None):
        """Durably reserve spend and fence ownership before invoking any model."""
        if not 30 <= lease_seconds <= 3600:
            raise ValueError("lease must be 30..3600 seconds")
        _request_exposure(report)  # Validate all costs before opening the transaction.
        with self.transaction() as cursor:
            cursor.execute("""SELECT * FROM response_agent_run WHERE run_id=%s
                AND state='RUNNING' AND owner=%s AND fencing_epoch=%s
                AND lease_until>CURRENT_TIMESTAMP FOR UPDATE""",
                           (claim.run_id, claim.owner, claim.epoch))
            row = cursor.fetchone()
            if row is None:
                raise LostLease("claim expired or superseded")
            if ("frozen_current_bundle" in row["checkpoint"]
                    and row["checkpoint"]["frozen_current_bundle"] != report.get("frozen_current_bundle")):
                raise Hold("FROZEN_BUNDLE_CHANGED_DURING_RUN")
            old_requests = row["checkpoint"].get("requests", [])
            new_requests = report.get("requests", [])
            if len(new_requests) < len(old_requests):
                raise ValueError("cannot erase paid request history")
            for old, new in zip(old_requests, new_requests):
                for field in ("request_id", "reserved_usd", "started_at", "cost_usd"):
                    if field in old and old[field] != new.get(field):
                        raise ValueError("cannot rewrite request history")
                if "cost_usd" not in old and "cost_usd" not in new and old != new:
                    # Safe response metadata additions are not a retry or erasure.
                    if any(new.get(key) != value for key, value in old.items() if key != "state"):
                        raise ValueError("cannot replace unresolved request")
            new_exposure = sum(max(Decimal(str(request.get("reserved_usd", 0))),
                                   Decimal(str(request.get("cost_usd", 0))))
                               for request in new_requests[len(old_requests):])
            prior_confirmed, prior_reserved = _request_exposure({"requests": new_requests[:len(old_requests)]})
            if prior_confirmed + prior_reserved + new_exposure > row["budget_usd"] and len(new_requests) > len(old_requests):
                raise Hold("run budget exceeded before inference")
            self._budget_checkpoint(cursor, row, new_requests, report)
            if report:
                report["budget_ledger"] = self._budget_summary(cursor, row)
            if row["bundle_digest"] and bundle_digest and row["bundle_digest"] != bundle_digest:
                raise Hold("BUNDLE_CHANGED_DURING_RUN")
            cursor.execute("""UPDATE response_agent_run SET checkpoint=%s,
                bundle_digest=COALESCE(bundle_digest,%s),
                lease_until=CURRENT_TIMESTAMP + %s * INTERVAL '1 second',updated_at=CURRENT_TIMESTAMP
                WHERE run_id=%s""", (Json(report), bundle_digest, lease_seconds, claim.run_id))

    def _budget_checkpoint(self, cursor, run, requests, report):
        """Lock run -> incident -> ordered KST days, journal and checkpoint atomically.

        A settlement cannot change its reservation's day or incident. Existing cost
        is immutable; unknown calls keep their full reservation even after a hold.
        """
        cursor.execute("SELECT * FROM response_agent_budget_policy WHERE policy_id=TRUE")
        policy = cursor.fetchone()
        cursor.execute("""SELECT * FROM response_agent_budget_request WHERE run_id=%s
                          ORDER BY request_id""", (run["run_id"],))
        existing = {row["request_id"]: row for row in cursor.fetchall()}
        requested = {_request_id(request, index): request for index, request in enumerate(requests)}
        if not set(existing).issubset(requested):
            raise ValueError("cannot erase cumulative paid request history")
        additions = set(requested) - set(existing)
        old_ids = {_request_id(request, index) for index, request in enumerate(run["checkpoint"].get("requests", []))}
        if old_ids - set(existing):
            raise Hold("LEGACY_BUDGET_RECONCILIATION_REQUIRED")
        if additions and (policy is None or policy["legacy_reconciliation_required"]):
            raise Hold("LEGACY_BUDGET_RECONCILIATION_REQUIRED")
        identity = run["budget_incident_id"]
        if identity is None:
            if requests:
                raise Hold("LEGACY_BUDGET_RECONCILIATION_REQUIRED")
            return
        cursor.execute("""UPDATE response_agent_budget_incident SET budget_usd=LEAST(budget_usd,%s)
            WHERE budget_incident_id=%s RETURNING budget_usd""", (self.incident_budget, identity))
        account = cursor.fetchone()
        if account is None:
            raise Hold("BUDGET_INCIDENT_BINDING_MISSING")
        cursor.execute("SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date AS day")
        today = cursor.fetchone()["day"]
        days = {row["budget_day_kst"] for row in existing.values()}
        if additions:
            days.add(today)
        for day in sorted(days):
            cursor.execute("""INSERT INTO response_agent_budget_day (budget_day_kst,budget_usd)
                VALUES (%s,%s) ON CONFLICT DO NOTHING""", (day, self.daily_budget))
            cursor.execute("""UPDATE response_agent_budget_day SET budget_usd=LEAST(budget_usd,%s)
                WHERE budget_day_kst=%s""", (self.daily_budget, day))
        for request_id, request in requested.items():
            if request_id in additions:
                amount = request.get("reserved_usd")
                if amount is None or isinstance(amount, bool):
                    raise ValueError("new request requires pre-call reservation")
                reserve = Decimal(str(amount)).quantize(Decimal("0.00000001"), rounding=ROUND_CEILING)
                if not reserve.is_finite() or reserve < 0:
                    raise ValueError("invalid request reservation")
                cursor.execute("""INSERT INTO response_agent_budget_request
                    (run_id,request_id,budget_incident_id,budget_day_kst,reserved_usd,reservation)
                    VALUES (%s,%s,%s,%s,%s,%s)""",
                    (run["run_id"], request_id, identity, today, reserve, Json({
                        "request": request,
                        "billing_context": {key: report[key] for key in
                            ("model", "price_source", "price_checked_date", "pricing_version", "unit_prices",
                             "unit_prices_usd_per_million",
                             "runtime_sources", "dependencies") if key in report}})))
            elif (existing[request_id]["budget_incident_id"] != identity or
                  Decimal(str(request.get("reserved_usd"))).quantize(Decimal("0.00000001"), rounding=ROUND_CEILING) != existing[request_id]["reserved_usd"]):
                raise ValueError("cannot rewrite budget request binding")
            if "cost_usd" in request:
                cost = Decimal(str(request["cost_usd"])).quantize(Decimal("0.00000001"), rounding=ROUND_CEILING)
                cursor.execute("SELECT cost_usd FROM response_agent_budget_settlement WHERE run_id=%s AND request_id=%s",
                               (run["run_id"], request_id))
                settled = cursor.fetchone()
                if settled and settled["cost_usd"] != cost:
                    raise ValueError("cannot rewrite settled request cost")
                if not settled:
                    cursor.execute("""INSERT INTO response_agent_budget_settlement(run_id,request_id,cost_usd,receipt)
                        VALUES (%s,%s,%s,%s)""", (run["run_id"], request_id, cost, Json(request)))
        # Always record real late usage, even if provider exceeds its reservation.
        # A later new call then holds, rather than falsifying/losing the actual cost.
        if additions:
            cursor.execute("""SELECT COALESCE(SUM(CASE WHEN r.run_id=%s AND r.request_id=ANY(%s)
                    THEN GREATEST(r.reserved_usd,COALESCE(s.cost_usd,0))
                    ELSE COALESCE(s.cost_usd,r.reserved_usd) END),0) AS exposure
                FROM response_agent_budget_request r LEFT JOIN response_agent_budget_settlement s
                USING(run_id,request_id) WHERE r.budget_incident_id=%s""", (run["run_id"], list(additions), identity))
            if cursor.fetchone()["exposure"] > account["budget_usd"]:
                raise Hold("INCIDENT_BUDGET_EXHAUSTED")
            for day in sorted(days):
                cursor.execute("""SELECT d.budget_usd,COALESCE(SUM(CASE WHEN r.run_id=%s AND r.request_id=ANY(%s)
                        THEN GREATEST(r.reserved_usd,COALESCE(s.cost_usd,0))
                        ELSE COALESCE(s.cost_usd,r.reserved_usd) END),0) AS exposure
                    FROM response_agent_budget_day d LEFT JOIN response_agent_budget_request r
                    ON r.budget_day_kst=d.budget_day_kst LEFT JOIN response_agent_budget_settlement s
                    ON s.run_id=r.run_id AND s.request_id=r.request_id
                    WHERE d.budget_day_kst=%s GROUP BY d.budget_usd""", (run["run_id"], list(additions), day))
                daily = cursor.fetchone()
                if daily["exposure"] > daily["budget_usd"]:
                    raise Hold("DAILY_BUDGET_EXHAUSTED")
            cursor.execute("""SELECT EXISTS(SELECT 1 FROM response_agent_budget_request r
                LEFT JOIN response_agent_budget_settlement s USING(run_id,request_id)
                WHERE r.budget_incident_id=%s AND s.cost_usd IS NULL
                AND NOT (r.run_id=%s AND r.request_id=ANY(%s))) AS unresolved""",
                (identity, run["run_id"], list(additions)))
            if cursor.fetchone()["unresolved"]:
                raise Hold("UNRESOLVED_PAID_REQUEST_REQUIRES_RECONCILIATION")

    def _budget_summary(self, cursor, run):
        cursor.execute("""SELECT a.budget_usd,
            COALESCE(SUM(s.cost_usd),0) AS confirmed,
            COALESCE(SUM(CASE WHEN s.cost_usd IS NULL THEN r.reserved_usd ELSE 0 END),0) AS reserved
            FROM response_agent_budget_incident a LEFT JOIN response_agent_budget_request r
            ON r.budget_incident_id=a.budget_incident_id LEFT JOIN response_agent_budget_settlement s
            ON s.run_id=r.run_id AND s.request_id=r.request_id
            WHERE a.budget_incident_id=%s GROUP BY a.budget_usd""", (run["budget_incident_id"],))
        account = cursor.fetchone()
        if account is None:
            return {"status": "LEGACY_RECONCILIATION_REQUIRED"}
        cursor.execute("""SELECT r.request_id,r.budget_day_kst FROM response_agent_budget_request r
            WHERE r.run_id=%s ORDER BY r.created_at,r.request_id""", (run["run_id"],))
        reservation_days = {row["request_id"]: row["budget_day_kst"].isoformat() for row in cursor.fetchall()}
        cursor.execute("""SELECT budget_usd FROM response_agent_budget_day
            WHERE budget_day_kst=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date""")
        day = cursor.fetchone()
        return {"budget_incident_id": run["budget_incident_id"],
                "incident_limit_usd": float(account["budget_usd"]),
                "incident_confirmed_usd": float(account["confirmed"]),
                "incident_unknown_reserved_usd": float(account["reserved"]),
                "daily_limit_usd": float(min(self.daily_budget, day["budget_usd"]) if day else self.daily_budget),
                "reservation_days_kst": reservation_days}

    def report_path(self, claim):
        return self.output_root / claim.run_id / ("dispatch-epoch-" + str(claim.epoch)) / "report.json"

    def _persist_terminal(self, claim, report):
        """Rebuild review files from the authoritative fenced DB checkpoint, no model."""
        run_directory = self.output_root / claim.run_id
        directory = self.report_path(claim).parent
        if (Path(claim.run_directory) != run_directory or run_directory.resolve() != run_directory
                or directory.resolve() != directory or (directory.exists() and not directory.is_dir())):
            raise Hold("RUN_ARTIFACT_PATH_MISMATCH")
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        def write_private(name, text):
            temporary = directory / ("." + name + "." + uuid.uuid4().hex + ".tmp")
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(fd, "w") as handle:
                    handle.write(text)
                    handle.flush()
                    os.fsync(handle.fileno())
                temporary.replace(directory / name)
            finally:
                if temporary.exists():
                    temporary.unlink()
        write_private("report.json", json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        confirmed, reserved = _request_exposure(report)
        review = "\n".join([
            "# PostgreSQL dispatched agent review", "", "Authority: persisted PostgreSQL checkpoint.",
            f"Status: {report['status']}", f"Incident: {claim.fingerprint}", f"Run: {claim.run_id}",
            f"Case: {report.get('case_id', 'See case digest in report.json')}",
            f"Model requests: {len(report.get('requests', []))}",
            f"Confirmed estimated cost: ${confirmed}", f"Unpriced reserved exposure: ${reserved}",
            f"Original run budget: ${claim.budget_usd}", "",
            str((report.get("decision") or {}).get("reason") or report.get("stop_reason") or "Human review required."),
            "", "See report.json for exact evidence, tool calls, patches and validation results.",
            "No operating reprocess, serving writes, approval or deployment is authorized by this report.", ""])
        write_private("dispatch-review.md", review)
        if not (directory / "review.md").exists():
            write_private("review.md", review)
        targets = {"case_id": report.get("case_id"), "incident_fingerprint": claim.fingerprint,
                   "scope": "captured observations for review only; no operating write authority",
                   "source_digests": report.get("capture_payload_digests", []),
                   "targets": report.get("reprocess_targets", []),
                   "operating_reprocess_authorized": False}
        write_private("reprocess-targets.json", json.dumps(targets, ensure_ascii=False, indent=2) + "\n")
        # File fsync alone does not persist newly created directory entries.
        # Complete these before marking the PostgreSQL row terminal.
        for path in (directory, run_directory, self.output_root):
            descriptor = os.open(path, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

    def finish(self, claim, report, *, reason=None, lease_seconds=300):
        if report.get("status") not in TERMINAL:
            raise ValueError("executor must return terminal report")
        report["dispatch_export"] = {"fencing_epoch": claim.epoch, "report": str(self.report_path(claim))}
        self.checkpoint(claim, report, lease_seconds=lease_seconds)
        # If file materialization fails, leave RUNNING with terminal checkpoint so
        # a new fenced owner can finish the files without calling the model again.
        self._persist_terminal(claim, report)
        with self.transaction() as cursor:
            cursor.execute("""UPDATE response_agent_run SET state=%s,stop_reason=%s,
                updated_at=CURRENT_TIMESTAMP WHERE run_id=%s AND state='RUNNING'
                AND owner=%s AND fencing_epoch=%s AND lease_until>CURRENT_TIMESTAMP""",
                (report["status"], reason, claim.run_id, claim.owner, claim.epoch))
            if cursor.rowcount != 1:
                raise LostLease("claim expired or superseded")
            if self.terminal_sink is not None:
                # Atomic with terminal state. Failure leaves a recoverable terminal checkpoint,
                # so retrying outbox insertion never pays for another model analysis.
                self.terminal_sink(cursor, claim, json.loads(json.dumps(report)))

    def run_one(self, executor, *, owner, budget_usd, lease_seconds=300):
        """Run at most one incident; duplicates/unsupported cases make zero API calls.

        executor(bundle, claim, checkpoint) receives the exact immutable prior
        report through claim.prior_report. It must restore that run, not reset its
        budget, messages or patches. A recovered unresolved request is always HELD.
        """
        result = self.claim(owner=owner, budget_usd=budget_usd, lease_seconds=lease_seconds)
        if result is None:
            return {"status": "IDLE", "new_model_calls": 0}
        claim, incident = result
        prior = claim.prior_report
        previous_request_count = len(prior.get("requests", []))
        try:
            if prior.get("status") in TERMINAL:
                # Terminal report committed before process death, state update lost.
                self.finish(claim, prior, reason="RECOVERED_TERMINAL_REPORT", lease_seconds=lease_seconds)
                return {"status": prior["status"], "run_id": claim.run_id, "recovered": True,
                        "new_model_calls": 0, "report": str(self.report_path(claim))}
            if any("cost_usd" not in row for row in prior.get("requests", [])):
                raise Hold("UNRESOLVED_PAID_REQUEST_REQUIRES_RECONCILIATION")
            if claim.recovered and _valid_budget(budget_usd) < _valid_budget(claim.budget_usd):
                raise Hold("LOWER_RESUME_BUDGET_REQUIRES_REVIEW")
            frozen = prior.get("frozen_current_bundle")
            current_provider = callable(getattr(self.provider, "freeze", None)) and callable(getattr(self.provider, "restore", None))
            if frozen is not None:
                if not current_provider:
                    raise Hold("FROZEN_BUNDLE_PROVIDER_CHANGED")
                bundle = self.provider.restore(incident, frozen, self.captured_sources)
            else:
                if current_provider and (claim.bundle_digest or prior.get("requests") or prior.get("recovery")):
                    # Previously paid/current runs without a source selection
                    # cannot silently adopt whichever samples exist now.
                    raise Hold("FROZEN_BUNDLE_MISSING_REVIEW_REQUIRED")
                bundle = self.provider.prepare(incident, self.samples(claim.fingerprint))
                if current_provider:
                    frozen = self.provider.freeze(bundle)
            if claim.bundle_digest and claim.bundle_digest != bundle.digest():
                raise Hold("APPROVED_BUNDLE_CHANGED")
            def enrich(report):
                value = json.loads(json.dumps(report))
                if frozen is not None:
                    value["frozen_current_bundle"] = json.loads(json.dumps(frozen))
                return value
            def checkpoint(report):
                self.checkpoint(claim, enrich(report), lease_seconds=lease_seconds, bundle_digest=bundle.digest())
            checkpoint(prior)
            executor_claim = claim
            if frozen is not None and not prior.get("status") and not prior.get("requests") and not prior.get("recovery"):
                # A crash after freezing but before Runner initialization must
                # not make a metadata-only checkpoint look like runner state.
                executor_claim = replace(claim, prior_report={})
            report = enrich(executor(bundle, executor_claim, checkpoint))
            self.finish(claim, report, lease_seconds=lease_seconds)
            return {"status": report["status"], "run_id": claim.run_id,
                    "new_model_calls": len(report.get("requests", [])) - previous_request_count,
                    "recovered": claim.recovered, "report": str(self.report_path(claim))}
        except LostLease:
            raise  # A superseded worker must never alter the new owner's state.
        except Exception as error:
            # The executor may have reserved or received paid work since claim.
            # Never finalize from the stale pre-run snapshot and erase that spend.
            with self.transaction() as cursor:
                cursor.execute("""SELECT checkpoint FROM response_agent_run WHERE run_id=%s
                    AND state='RUNNING' AND owner=%s AND fencing_epoch=%s
                    AND lease_until>CURRENT_TIMESTAMP""", (claim.run_id, claim.owner, claim.epoch))
                row = cursor.fetchone()
                if row is None:
                    raise LostLease("claim expired or superseded") from None
                latest = row["checkpoint"]
            if latest.get("status") in TERMINAL:
                # Preserve a finished validation/decision if only writing its
                # local artifacts failed. Retrying the lease performs no model call.
                raise
            unresolved = any("cost_usd" not in item for item in latest.get("requests", []))
            reason = (str(error) if isinstance(error, Hold) else
                      "UNRESOLVED_PAID_REQUEST_REQUIRES_RECONCILIATION" if unresolved else
                      "EXECUTOR_ERROR_" + type(error).__name__)
            state = "HELD" if isinstance(error, Hold) or unresolved else "ERROR"
            report = dict(latest, status=state, stop_reason=reason)
            self.finish(claim, report, reason=reason, lease_seconds=lease_seconds)
            return {"status": state, "run_id": claim.run_id, "reason": reason,
                    "new_model_calls": len(report.get("requests", [])) - previous_request_count,
                    "report": str(self.report_path(claim))}
