"""Bounded, durable recovery of acknowledged holds on the single batch host.

Only receipt codes are interpreted here. A new fetch gets a new capture ID and
the original dispatch generation; the server still owns freshness, provenance,
validation and all writes. Delivery retries keep their original capture ID.
"""
from datetime import datetime
from collections import Counter
import time


BACKOFF_SECONDS = (900, 3600, 21600)
MAX_AGE_SECONDS = 48 * 3600
PERMISSION_REASON = "EXACT_HOURS_LOCK_PERMISSION_REQUIRED"
RECOLLECT_REASONS = frozenset({
    "EXACT_HOURS_LOCK_TIMEOUT", "EXACT_HOURS_STATEMENT_TIMEOUT",
    "VALUE_DRIFTED", "MENU_CATEGORY_CHANGED", "MENU_CONFLICT",
    "OBSERVATION_EXPIRED", "STALE_OBSERVATION", "STALE_EVIDENCE", "POLICY_CHANGED",
    "BAEMIN_GROUP_OUT_OF_SCOPE",
})
# These coarse legacy reasons need server source evidence to distinguish null from truncation.
MENU_AVAILABILITY_REASONS = frozenset({"MENU_INCOMPLETE", "BAEMIN_INCOMPLETE"})
COMPLETED_STATES = frozenset({"RESOLVED", "FALLBACK_COMPLETED", "SOURCE_NOT_PROVIDED"})
ACTIONABLE_STATES = frozenset({"WAITING", "CONFIGURATION_BLOCKED", "EXHAUSTED", "EXPIRED",
    "REVIEW_REQUIRED", "AGENT_REQUIRED", "MENU_DECISION_REQUIRED", "AUDIT_REQUIRED"})
SOURCE_METRICS = {"FALLBACK_COMPLETED": "menu_fallback_completed",
                  "SOURCE_NOT_PROVIDED": "menu_source_not_provided", "SOURCE_UNVERIFIED": "menu_source_unverified"}
STRUCTURE_REASONS = frozenset({
    "PARSER_EXCEPTION", "ARGUMENTS_UNSUPPORTED", "ROOT_UNKNOWN", "ROOT_TYPE_CHANGED",
    "REQUIRED_FIELD_UNREADABLE", "FIELD_TYPE_UNSUPPORTED", "MENU_CONTAINER_UNRECOGNIZED",
    "MENU_CONTAINER_MISSING", "HOURS_CONTAINER_UNSUPPORTED", "HOURS_OUTPUT_EMPTY",
    "HOURS_SHAPE_UNSUPPORTED", "DAY_UNSUPPORTED", "TIME_UNSUPPORTED",
    "MENU_CONTAINER_UNSUPPORTED", "MENU_ENTITY_UNSUPPORTED", "MENU_NAME_UNREADABLE",
    "PRICE_SHAPE_UNSUPPORTED", "IMAGE_SHAPE_UNSUPPORTED", "MENU_OUTPUT_MISMATCH",
    "BAEMIN_CONTAINER_UNSUPPORTED", "BAEMIN_MENU_CONTAINER_UNSUPPORTED",
    "BAEMIN_ENTITY_UNSUPPORTED", "BAEMIN_NAME_UNREADABLE", "BAEMIN_FIELD_TYPE_UNSUPPORTED",
    "BAEMIN_IMAGE_UNSUPPORTED", "BAEMIN_GROUP_UNSUPPORTED", "BAEMIN_OUTPUT_MISMATCH",
    "STRUCTURE_CHANGED",
})


def receipt_reasons(receipt):
    automatic = receipt.get("automaticApply", {})
    reasons = set(receipt.get("reasons", []))
    reasons.update(item.get("reason", "UNCLASSIFIED_HOLD")
                   for item in automatic.get("items", []) if item.get("status") == "HELD")
    observation = automatic.get("observationHold")
    if observation and (observation != "OBSERVATION_HELD" or not reasons):
        reasons.add(observation)
    if automatic.get("held", 0) and not reasons:
        reasons.add("UNCLASSIFIED_HOLD")
    return sorted(reasons)


def classify(receipt, reasons):
    automatic = receipt.get("automaticApply", {})
    if automatic.get("unknown", 0) or automatic.get("failed", 0):
        return "AUDIT_REQUIRED"
    reasons = set(reasons)
    if "STALE_ATTEMPT" in reasons:
        return "SUPERSEDED"  # Never remove the old generation to bypass a newer worker.
    if reasons & STRUCTURE_REASONS:
        return "AGENT_REQUIRED"  # Existing response_incident is the agent's evidence inbox.
    if PERMISSION_REASON in reasons:
        return "CONFIGURATION_BLOCKED"
    if reasons & RECOLLECT_REASONS:
        return "WAITING"
    if "MENU_POLICY_REVIEW_REQUIRED" in reasons:
        return "MENU_DECISION_REQUIRED"
    if reasons - MENU_AVAILABILITY_REASONS:
        return "REVIEW_REQUIRED"
    if receipt.get("assessment") == "HELD":
        return "REVIEW_REQUIRED"
    if reasons:
        return source_outcome(receipt)
    return "RESOLVED"


def source_outcome(receipt):
    """Use server evidence, never reparse raw menus or infer success from an old receipt."""
    sources = receipt.get("menuSources")
    if not isinstance(sources, dict) or type(sources.get("version")) is not int or sources["version"] != 1:
        return "SOURCE_UNVERIFIED"
    states = {"AVAILABLE", "EMPTY", "NOT_PROVIDED", "INCOMPLETE"}
    naver, baemin = sources.get("naver"), sources.get("baemin")
    if not isinstance(naver, str) or not isinstance(baemin, str) or naver not in states or baemin not in states:
        return "SOURCE_UNVERIFIED"
    if "INCOMPLETE" in (naver, baemin):
        return "WAITING"  # A real truncated/filtered list uses the existing finite retry budget.
    automatic = receipt.get("automaticApply", {})
    keys = ("applied", "alreadyApplied", "held", "failed", "unknown")
    if (not receipt.get("captureId") or automatic.get("captureId") != receipt["captureId"]
            or any(type(automatic.get(key)) is not int or automatic[key] < 0 for key in keys)
            or any(automatic[key] for key in ("held", "failed", "unknown"))
            or automatic.get("observationHold")):
        return "SOURCE_UNVERIFIED"
    if naver in ("AVAILABLE", "EMPTY"):
        return "RESOLVED"
    return "FALLBACK_COMPLETED" if baemin == "AVAILABLE" else "SOURCE_NOT_PROVIDED"


def observed_time(record):
    return datetime.fromisoformat(record["request"]["observedAt"].replace("Z", "+00:00")).timestamp()


def identity(record):
    request = record["request"]
    return request.get("storeId"), request.get("source"), request.get("externalId")


def bounded_status(recovery, now):
    status = recovery.get("status", "")
    if status in ("WAITING", "CONFIGURATION_BLOCKED"):
        if now >= recovery["startedAt"] + MAX_AGE_SECONDS:
            return "EXPIRED"
        if recovery["attempts"] >= len(BACKOFF_SECONDS):
            return "EXHAUSTED"
    return status


class HoldRecovery:
    def __init__(self, spool, now):
        self.spool = spool
        self.latest = {}
        # Read one immutable capture at a time; do not retain payloads for all stores.
        for path in spool.root.glob("*.json"):
            record = spool.read(path)
            if "automaticApply" not in record.get("receipt", {}) and not record.get("recovery"):
                continue
            if not record["request"].get("observedAt") or record["request"].get("source") != "NAVER":
                continue
            key = identity(record)
            rank = (observed_time(record), record["capture_id"])
            if key not in self.latest or rank > self.latest[key][0]:
                self.latest[key] = (rank, path)
        for _, path in self.latest.values():
            record = spool.read(path)
            self.observe(record, now)

    def observe(self, record, now):
        if "receipt" not in record and not record.get("recovery"):
            return
        reasons = (receipt_reasons(record["receipt"]) if "receipt" in record
                   else record["recovery"]["reasons"])
        status = (classify(record["receipt"], reasons) if "receipt" in record
                  else record["recovery"]["status"])
        if status == "INCOMPLETE": status = "SOURCE_UNVERIFIED"
        previous = record.get("recovery", {})
        if not previous:
            previous = {"originCaptureId": record["capture_id"], "attempts": 0,
                        "startedAt": observed_time(record),
                        "nextAttemptAt": observed_time(record) + BACKOFF_SECONDS[0]}
        held_counts = (dict(Counter(item.get("reason", "UNCLASSIFIED_HOLD")
                                   for item in record["receipt"].get("automaticApply", {}).get("items", [])
                                   if item.get("status") == "HELD")) if "receipt" in record
                       else previous.get("heldCounts", {}))
        tasks = []
        if set(reasons) & STRUCTURE_REASONS: tasks.append("PARSER_REPAIR")
        if "MENU_POLICY_REVIEW_REQUIRED" in reasons: tasks.append("MENU_CLASSIFICATION")
        recovery = {**previous, "version": 2, "reasons": reasons, "status": status, "heldCounts": held_counts,
                    "analysisTasks": tasks}
        recovery["status"] = bounded_status(recovery, now)
        if record.get("recovery") != recovery:
            record = {**record, "recovery": recovery}
            self.spool.save(record)
        self.latest[identity(record)] = ((observed_time(record), record["capture_id"]), self.spool.path(record["capture_id"]))

    def due(self, now, hours_ready, limit=50):
        candidates = []
        for _, path in self.latest.values():
            record = self.spool.read(path)
            recovery = record.get("recovery", {})
            if record["state"] == "PENDING" or recovery.get("status") not in ("WAITING", "CONFIGURATION_BLOCKED"):
                continue
            if now >= recovery["startedAt"] + MAX_AGE_SECONDS or recovery["attempts"] >= len(BACKOFF_SECONDS):
                continue
            if recovery["nextAttemptAt"] > now:
                continue
            if PERMISSION_REASON in recovery["reasons"] and not hours_ready:
                continue
            # A recovery without the original dispatch generation could evade a stale-attempt check.
            if type(record["request"].get("attemptSeq")) is not int:
                continue
            candidates.append((recovery["nextAttemptAt"], path))
        result = []
        for _, path in sorted(candidates, key=lambda value: value[0])[:limit]:
            record = self.spool.read(path)
            # Do not hold every raw Apollo payload in memory while dispatching.
            result.append({"capture_id": record["capture_id"], "recovery": record["recovery"],
                           "request": {key: value for key, value in record["request"].items() if key != "payload"}})
        return result

    def reserve(self, record, now):
        """Return None when no longer due; otherwise consume the retry BEFORE I/O."""
        record = self.spool.read(self.spool.path(record["capture_id"]))
        recovery = dict(record["recovery"])
        status = bounded_status(recovery, now)
        if status != recovery["status"]:
            recovery["status"] = status
            self.spool.save({**record, "recovery": recovery})
        if (recovery["status"] not in ("WAITING", "CONFIGURATION_BLOCKED")
                or now < recovery["nextAttemptAt"]):
            return None
        recovery["attempts"] += 1
        recovery["nextAttemptAt"] = now + BACKOFF_SECONDS[min(recovery["attempts"], len(BACKOFF_SECONDS) - 1)]
        if recovery["attempts"] >= len(BACKOFF_SECONDS):
            recovery["status"] = "EXHAUSTED"
        record = {**record, "recovery": recovery}
        self.spool.save(record)
        return record

    def metadata(self, request, parent=None):
        # A normal due lease may revisit the same unresolved store. Keep the budget.
        key = (request.get("storeId"), request.get("source"), request.get("externalId"))
        if parent is None and key in self.latest:
            parent = self.spool.read(self.latest[key][1])
        if (parent and parent.get("recovery")
                and parent["recovery"]["status"] not in COMPLETED_STATES | {"SOURCE_UNVERIFIED"}):
            return {**parent["recovery"], "parentCaptureId": parent["capture_id"]}
        return None

    def counts(self, now=None):
        """Report retry states at publication time without modifying capture receipts."""
        now = time.time() if now is None else now
        states = ("waiting", "configuration_blocked", "exhausted", "expired", "review_required",
                  "agent_required", "menu_decision_required", "audit_required", "superseded")
        result = {"recovery_" + state: 0 for state in states}
        result.update({metric: 0 for metric in SOURCE_METRICS.values()})
        result["recovery_pending"] = 0
        held_kinds = {PERMISSION_REASON: "hold_permission_required", "SOURCE_UNKNOWN": "hold_source_unknown",
                      "DELETION_EVIDENCE_REQUIRES_REVIEW": "hold_deletion_review", "SOURCE_CONFLICT": "hold_source_conflict",
                      "MENU_POLICY_REVIEW_REQUIRED": "hold_menu_policy_review"}
        result.update({kind: 0 for kind in held_kinds.values()})
        for _, path in self.latest.values():
            record = self.spool.read(path)
            status = bounded_status(record.get("recovery", {}), now)
            # Old fetch failures may carry a pre-upgrade INCOMPLETE without a receipt.
            key = SOURCE_METRICS.get("SOURCE_UNVERIFIED" if status == "INCOMPLETE" else status, "recovery_" + status.lower())
            if key in result:
                result[key] += 1
            if status in ACTIONABLE_STATES:
                result["recovery_pending"] += 1
            for reason, count in record.get("recovery", {}).get("heldCounts", {}).items():
                if reason in held_kinds:
                    result[held_kinds[reason]] += count
        return result
