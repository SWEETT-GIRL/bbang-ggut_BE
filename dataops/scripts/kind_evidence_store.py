"""Read-only evidence input adapter and frozen profile/feature input contract.

No connection is opened on import. Callers inject local_csv; tests never load .env.
The ledger is append-only; an ID high-water mark bounds reads (not an MVCC snapshot).
Legacy tombstones are independent copies, not queries of current proposal status.
"""
import hashlib
import json
from collections import defaultdict

from kind_evidence import EvidencePolicy, StoreEvidence, build_store_evidence

CONTEXT_VERSION = 2


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def menu_fingerprint(menus):
    return digest(sorted((str(m["id"]), str(m.get("name") or ""),
                          str(m.get("kind") or ""), int(m.get("likes") or 0)) for m in menus))


def group_menus(menus):
    grouped = defaultdict(list)
    for menu in menus:
        grouped[str(menu["store_id"])].append(menu)
    return grouped


def load_store_evidence(ids, menus, query, policy=None):
    """Read only the requested cohort. Missing migrations fail, never fall back to PENDING.

    Both new ledger and legacy copy are bounded to one captured high watermark.
    External-ID switches cannot merge observations from different places.
    """
    ids = sorted({int(sid) for sid in ids})
    if not ids:
        return {}, {"observation_id": 0, "legacy_proposal_id": 0}
    scope = ",".join(map(str, ids))
    limits = query("SELECT COALESCE((SELECT max(id) FROM menu_evidence_observation),0) AS observation_id, "
                   "COALESCE((SELECT max(proposal_id) FROM menu_evidence_legacy_removal),0) AS legacy_proposal_id;")[0]
    cutoff = {key: int(limits[key]) for key in ("observation_id", "legacy_proposal_id")}
    identities = {str(r["store_id"]): r["external_id"] for r in query(
        f"SELECT store_id, external_id FROM store_sync_state WHERE source='NAVER' AND store_id IN ({scope});")}
    events = query("SELECT id, observation_id, store_id, source, external_id, observed_at, received_at, "
                   "menu_names::text, representative_names::text, menu_complete, identity_verified, parser_version "
                   f"FROM menu_evidence_observation WHERE store_id IN ({scope}) AND id <= {cutoff['observation_id']} "
                   "ORDER BY id;")
    observations = defaultdict(list)
    for raw in events:
        event = dict(raw)
        for key in ("menu_names", "representative_names"):
            event[key] = json.loads(event[key]) if isinstance(event[key], str) else event[key]
        for key in ("menu_complete", "identity_verified"):
            event[key] = event[key] is True or str(event[key]).lower() in ("t", "true", "1")
        observations[str(event["store_id"])].append(event)
    legacy = defaultdict(list)
    for row in query("SELECT proposal_id, store_id, menu_id, menu_name, evidence_at, source, status_at_capture, "
                     "snapshot_id, proposal_created_at, time_basis "
                     f"FROM menu_evidence_legacy_removal WHERE store_id IN ({scope}) "
                     f"AND proposal_id <= {cutoff['legacy_proposal_id']} ORDER BY proposal_id;"):
        legacy[str(row["store_id"])].append(row)
    grouped = group_menus(menus)
    result = {}
    for sid in map(str, ids):
        if not identities.get(sid):
            raise ValueError(f"store {sid}: NAVER external_id missing; do not publish unverified kind evidence")
        result[sid] = build_store_evidence(observations[sid], legacy[sid], grouped[sid],
                                           external_id=identities[sid], policy=policy or EvidencePolicy())
    return result, cutoff


def freeze_context(evidence, signal, menus, snapshot_id, cutoff, *, shadow=False):
    context = {"version": CONTEXT_VERSION, "evidence": evidence.as_dict(),
               "evidence_fingerprint": evidence.fingerprint,
               "signal_fingerprint": digest(signal), "menu_fingerprint": menu_fingerprint(menus),
               "snapshot_id": str(snapshot_id or ""), "cutoff": cutoff, "shadow": shadow,
               "publication_status": evidence.publication_status,
               "publication_blockers": evidence.publication_blockers}
    return context, digest(context)


def read_frozen_evidence(context, expected_fingerprint):
    if isinstance(context, str):
        context = json.loads(context)
    if not context or context.get("version") != CONTEXT_VERSION:
        raise ValueError("missing/version-mismatched kind evidence; rebuild profile first")
    if context.get("shadow"):
        raise ValueError("shadow/keep-vanished profile cannot be used for label publishing")
    if digest(context) != expected_fingerprint:
        raise ValueError("kind evidence context checksum mismatch")
    evidence = StoreEvidence.from_dict(context["evidence"])
    if evidence.fingerprint != context["evidence_fingerprint"]:
        raise ValueError("kind evidence fingerprint mismatch")
    if (context.get("publication_status") != evidence.publication_status
            or context.get("publication_blockers") != evidence.publication_blockers):
        raise ValueError("publication status differs from actual frozen evidence; rebuild first")
    return evidence


def restore_context(context, expected_fingerprint, signal, menus, snapshot_id, *, external_id):
    if isinstance(context, str):
        context = json.loads(context)
    evidence = read_frozen_evidence(context, expected_fingerprint)
    if context["signal_fingerprint"] != digest(signal) or context["snapshot_id"] != str(snapshot_id or ""):
        raise ValueError("signal changed after profile build; rebuild profile before labels")
    if context["menu_fingerprint"] != menu_fingerprint(menus):
        raise ValueError("menu inputs changed after profile build; rebuild profile before labels")
    if not external_id or evidence.external_id != str(external_id):
        raise ValueError("NAVER mapping changed after profile build; rebuild profile before labels")
    return evidence
